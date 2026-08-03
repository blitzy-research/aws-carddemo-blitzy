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

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.GenericMessage;

import com.carddemo.exception.JobSubmissionException;
import com.carddemo.util.JclCardImageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JobSubmissionService}, the estate's only online-to-batch bridge.
 *
 * <p>The whole mainframe estate contains exactly one transient-data-queue write, in the
 * transaction-report request program (transaction {@code CR00}). Two regions of that member are the
 * authority for every assertion here, and both were read directly rather than inferred. In the
 * card-emitting loop a loop-control flag is cleared, the guard tests the one-based card index against
 * the declared array bound together with the end-of-stream and write-error flags, the current card is
 * moved into the eighty-character record, the end-of-stream test is made, and the write is then
 * performed <strong>unconditionally</strong>. In the queue-write paragraph the eighty-character
 * record is written capturing a response and a reason code; a normal response continues, and any
 * other response writes both codes to the diagnostic channel, raises the write-error flag, places a
 * fixed failure literal in the screen message field and re-sends the screen. It never abends and
 * never aborts the transaction.</p>
 *
 * <p>The paragraph name is misspelled in the source. That misspelling is a source anomaly carried in
 * the anomaly register rather than corrected, and it is deliberately not reproduced in any Java
 * identifier: the paragraph is misspelled, the method is not.</p>
 *
 * <p>Four attributes of the destination queue are contractual rather than incidental, and each is
 * asserted below. Its records are fixed and eighty bytes wide, so one card is one message and every
 * body is exactly eighty encoded bytes, space padded and never trimmed. Its records are unblocked, so
 * cards are published one at a time rather than concatenated into a single payload. Its writes
 * append, so order is significant and every card of one submission travels in a single
 * first-in-first-out message group. And its error option is to ignore, so a publish failure is
 * logged, the remaining cards are not sent, and control returns normally with nothing rethrown.</p>
 *
 * <p>The interface-contract gate for this bridge is discharged by draining a real emulated
 * first-in-first-out queue and asserting the full ordered sequence of seventeen eighty-byte messages
 * against what actually arrived. That obligation belongs to the sibling integration and end-to-end
 * test tree and must not be attempted here. This is a surefire unit test: it starts no container,
 * reaches no cloud service, opens no socket, binds no port and touches no database. It mocks the
 * messaging operations template and asserts the interactions, which is what makes it fast, hermetic
 * and safe to run on any developer machine.</p>
 *
 * <p>Every expected card image below is hand written in this file from the seventeen eighty-byte card
 * declarations of that member, with each pad width spelled out as an explicit repeat count so a
 * reviewer can check the eighty-column arithmetic without leaving the file. The card builder is never
 * called to produce an expectation: delegating to it would make these tests pass against any builder,
 * including a broken one, so the two artefacts are held apart on purpose and a drift in either is a
 * failure. The only use made of the builder's published constants is a cross-check that they still
 * agree with the hand-written legacy figures.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JobSubmissionService: the estate's only online-to-batch bridge, onto a FIFO queue")
class JobSubmissionServiceTest {

    // Configuration the bean is constructed with: both arrive from configuration in production and
    // neither is a literal in the service, so both are fixed here instead.

    /**
     * The canonical destination queue. The first-in-first-out suffix is mandatory - the queue service
     * refuses such a queue whose name lacks it - and ordering is contractual because the legacy queue
     * appends on every write.
     */
    private static final String QUEUE_NAME = "carddemo-jobs.fifo";

    private static final String MESSAGE_GROUP_ID = "carddemo-job-submission";

    private static final String START_DATE = "2022-01-01";

    private static final String END_DATE = "2022-07-06";

    private static final String OTHER_START_DATE = "2019-11-30";

    private static final String OTHER_END_DATE = "2020-02-29";

    private static final String CALLER_SUBMISSION_ID = "REPORT-REQUEST-0000000001";

    // The independent oracle, hand written from the legacy card declarations. Padding is written as an
    // explicit repeat count so the eighty-column arithmetic is visible: content width plus pad width is
    // eighty on every line, and a self-check test below proves it.

    private static final String ORACLE_JOB_CARD =
            "//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0," + " ".repeat(32);

    private static final String ORACLE_NOTIFY_CARD = "// NOTIFY=&SYSUID" + " ".repeat(63);

    private static final String ORACLE_COMMENT_CARD = "//*" + " ".repeat(77);

    private static final String ORACLE_JOBLIB_CARD =
            "//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')" + " ".repeat(34);

    private static final String ORACLE_EXEC_PROC_CARD = "//STEP10 EXEC PROC=TRANREPT" + " ".repeat(53);

    private static final String ORACLE_SYMNAMES_DD_CARD = "//STEP05R.SYMNAMES DD *" + " ".repeat(57);

    private static final String ORACLE_CARD_NUMBER_SYMBOL_CARD =
            "TRAN-CARD-NUM,263,16,ZD" + " ".repeat(57);

    private static final String ORACLE_PROCESSING_DATE_SYMBOL_CARD =
            "TRAN-PROC-DT,305,10,CH" + " ".repeat(58);

    private static final String ORACLE_IN_STREAM_TERMINATOR_CARD = "/*" + " ".repeat(78);

    private static final String ORACLE_DATEPARM_DD_CARD = "//STEP10R.DATEPARM DD *" + " ".repeat(57);

    /**
     * The end-of-stream sentinel, which <strong>is transmitted</strong>: the legacy loop sets its
     * termination flag and only then performs the write, so the sentinel is the seventeenth message
     * rather than a marker held back in storage.
     */
    private static final String ORACLE_SENTINEL_CARD = "/*EOF" + " ".repeat(75);

    private static final String ORACLE_SENTINEL_CONTENT = "/*EOF";

    private static final String ORACLE_START_SLOT_PREFIX = "PARM-START-DATE,C'";

    private static final String ORACLE_START_SLOT_SUFFIX = "'" + " ".repeat(51);

    private static final String ORACLE_END_SLOT_PREFIX = "PARM-END-DATE,C'";

    private static final String ORACLE_END_SLOT_SUFFIX = "'" + " ".repeat(53);

    private static final String ORACLE_PARAMETER_SLOT_SEPARATOR = " ";

    private static final String ORACLE_PARAMETER_SLOT_SUFFIX = " ".repeat(59);

    private static final int ORACLE_CARD_COUNT = 17;

    private static final int ORACLE_CARD_WIDTH = 80;

    private static final int ORACLE_SLOT_WIDTH = 10;

    private static final int ORACLE_START_SLOT_CARD = 11;

    private static final int ORACLE_END_SLOT_CARD = 12;

    private static final int ORACLE_PARAMETER_SLOT_CARD = 15;

    private static final int ORACLE_START_SLOT_OFFSET = 18;

    private static final int ORACLE_END_SLOT_OFFSET = 16;

    private static final int ORACLE_PARAMETER_START_SLOT_OFFSET = 0;

    private static final int ORACLE_PARAMETER_END_SLOT_OFFSET = 11;

    /** The declared bound of the legacy card table, carried as a defensive guard. */
    private static final int ORACLE_REDEFINE_CARD_BOUND = 1000;

    private static final int ORACLE_TOTAL_IMAGE_WIDTH = 1360;

    /**
     * The hand-written operator-facing failure literal, with exactly three separate full stops rather
     * than one ellipsis character, no trailing space and no further punctuation.
     */
    private static final String ORACLE_FAILURE_TEXT = "Unable to Write TDQ (JOBS)...";

    /** The hand-written legacy queue name, from the CICS resource definition. */
    private static final String ORACLE_QUEUE_IDENTITY = "JOBS";

    private static final int ORACLE_DEDUPLICATION_ID_MAX_LENGTH = 128;

    private static final String ORACLE_ORDINAL_SEPARATOR = "-";

    private static final int FIRST_CARD_ORDINAL = 1;

    private static final int MID_STREAM_FAILING_ORDINAL = 5;

    private static final String CAUSE_TEXT = "the queue service refused the card";

    /**
     * A marker a forged or leaked log record would carry.
     *
     * <p>Asserting on a marker rather than on the control characters around it is what makes a leak
     * visible rather than inferred: if this string reaches a diagnostic then the queue client's own
     * text was echoed, whatever became of the terminators it was wrapped in.
     */
    private static final String HOSTILE_MARKER = "QAMARKLEAKEDSECRET";

    /**
     * A publish-failure description of the kind a misconfigured or verbose queue client really
     * produces: it names a credential, echoes it, and carries the line terminators that would let a
     * log reader split one record into several.
     *
     * <p>None of this is hypothetical. A client that reports a signing failure by quoting the request
     * it signed, or an endpoint failure by quoting the URL it was given, puts deployment-supplied text
     * into an exception message; this class only has to assume that such a message can reach the
     * catch block, which it plainly can.
     */
    private static final String HOSTILE_CAUSE_TEXT = "refused: accessKey=AKIAQAEXAMPLEKEY secret="
            + HOSTILE_MARKER + "\r\nFORGED AUDIT ENTRY: administrator granted\n\tat some.frame.Deeper";

    /** A carriage return: the byte a log reader treats as ending a record. */
    private static final char CARRIAGE_RETURN = 13;

    /** A line feed: the other byte a log reader treats as ending a record. */
    private static final char LINE_FEED = 10;

    /** A tab: whitespace that is not a line terminator, kept separable from the two that are. */
    private static final char TAB = 9;

    /**
     * The hand-written bound on a derived diagnostic code, stated here independently of the service.
     *
     * <p>The legacy response and reason codes were fixed-width display fields, so a bound is the
     * faithful shape and an unbounded code is not. The number is written out rather than read from the
     * service so that a change to the service's bound is a change this class notices.
     */
    private static final int ORACLE_MAX_DIAGNOSTIC_CODE_LENGTH = 64;

    /** The hand-written bound on the number of failure types the recorded chain names. */
    private static final int ORACLE_MAX_FAILURE_CHAIN_DEPTH = 6;

    /**
     * The shape a derived diagnostic code must have: one unbroken token of ASCII letters, digits and
     * the two connectors, bounded in length.
     *
     * <p>Asserting the shape of the whole value rather than the absence of one hostile string is what
     * makes the guarantee hold for descriptions this class never thought of. No space, no control
     * byte and no punctuation can satisfy this pattern, so a value that matches it cannot split a log
     * record or be mistaken for the next field.
     */
    private static final String ORACLE_DIAGNOSTIC_CODE =
            "[A-Za-z0-9$_]{1," + ORACLE_MAX_DIAGNOSTIC_CODE_LENGTH + "}";

    /**
     * The shape the recorded failure chain must have: codes joined by the separator, optionally cut
     * short by the truncation marker.
     */
    private static final String ORACLE_FAILURE_CHAIN = ORACLE_DIAGNOSTIC_CODE
            + "(<-" + ORACLE_DIAGNOSTIC_CODE + ")*(<-\\.\\.\\.)?";

    /** The empty string, used where a blank configured value is under test. */
    private static final String EMPTY_TEXT = "";

    // Harness: the messaging operations template is mocked; nothing else is.

    /**
     * The mocked messaging operations template. Publishing is the only thing the service may do with it:
     * the queue is output-only, so any receive would surface as an unverified interaction and fail the
     * {@code verifyNoMoreInteractions} check every publishing test makes.
     */
    @Mock
    private SqsOperations sqsOperations;

    /**
     * Captures the fluent configurer the service hands to the template. Replaying each captured
     * configurer onto a recording options object is the only way to see the body, the message group and
     * the deduplication identifier, all three of which are part of the preserved contract.
     */
    @Captor
    private ArgumentCaptor<Consumer<SqsSendOptions<String>>> sendConfigurerCaptor;

    private JobSubmissionService service;

    private Logger serviceLogger;

    private ListAppender<ILoggingEvent> logRecorder;

    private Level originalLevel;

    @BeforeEach
    void constructServiceAndAttachLogRecorder() {
        this.service = new JobSubmissionService(this.sqsOperations, QUEUE_NAME, MESSAGE_GROUP_ID);

        // The failure diagnostic is an asserted behaviour, so capture must not depend on whatever level
        // the ambient logging configuration sets. The level is pinned here and restored in teardown.
        this.serviceLogger = (Logger) LoggerFactory.getLogger(JobSubmissionService.class);
        this.originalLevel = this.serviceLogger.getLevel();
        this.logRecorder = new ListAppender<>();
        this.logRecorder.setContext(this.serviceLogger.getLoggerContext());
        this.logRecorder.start();
        this.serviceLogger.addAppender(this.logRecorder);
        this.serviceLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachLogRecorder() {
        this.serviceLogger.detachAppender(this.logRecorder);
        this.logRecorder.stop();
        this.serviceLogger.setLevel(this.originalLevel);
    }

    // Oracle assembly, hand written; the card builder is never consulted.

    /**
     * Assembles the expected seventeen-card job image for one reporting period, entirely from the
     * hand-written literals above, in the legacy card group's own order.
     */
    private static List<String> oracleJobImage(final String startDate, final String endDate) {
        return List.of(
                ORACLE_JOB_CARD,
                ORACLE_NOTIFY_CARD,
                ORACLE_COMMENT_CARD,
                ORACLE_JOBLIB_CARD,
                ORACLE_COMMENT_CARD,
                ORACLE_EXEC_PROC_CARD,
                ORACLE_COMMENT_CARD,
                ORACLE_SYMNAMES_DD_CARD,
                ORACLE_CARD_NUMBER_SYMBOL_CARD,
                ORACLE_PROCESSING_DATE_SYMBOL_CARD,
                oracleStartSlotCard(startDate),
                oracleEndSlotCard(endDate),
                ORACLE_IN_STREAM_TERMINATOR_CARD,
                ORACLE_DATEPARM_DD_CARD,
                oracleParameterSlotCard(startDate, endDate),
                ORACLE_IN_STREAM_TERMINATOR_CARD,
                ORACLE_SENTINEL_CARD);
    }

    /** Card 11: eighteen-character prefix, ten-character slot, fifty-two-character suffix. */
    private static String oracleStartSlotCard(final String startDate) {
        return ORACLE_START_SLOT_PREFIX + startDate + ORACLE_START_SLOT_SUFFIX;
    }

    /** Card 12: sixteen-character prefix, ten-character slot, fifty-four-character suffix. */
    private static String oracleEndSlotCard(final String endDate) {
        return ORACLE_END_SLOT_PREFIX + endDate + ORACLE_END_SLOT_SUFFIX;
    }

    /** Card 15: ten-character slot, one-character separator, ten-character slot, fifty-nine pad. */
    private static String oracleParameterSlotCard(final String startDate, final String endDate) {
        return startDate + ORACLE_PARAMETER_SLOT_SEPARATOR + endDate + ORACLE_PARAMETER_SLOT_SUFFIX;
    }

    /**
     * A typed matcher for the fluent send configurer, written out so the generic argument stays explicit
     * at every stubbing site: the build promotes warnings to errors, and an inline wildcard matcher on a
     * generic messaging interface is the classic way to earn an unchecked warning here.
     */
    private static <T> Consumer<SqsSendOptions<T>> anySendConfigurer() {
        return any();
    }

    /**
     * A plausible accepted-publish outcome. The service reads the message identifier from it for its
     * per-card diagnostic, so the stub must return a real result rather than a null.
     */
    private static SendResult<String> acceptedPublish() {
        return new SendResult<>(UUID.randomUUID(), QUEUE_NAME,
                new GenericMessage<>("accepted"), Map.of());
    }

    /** Stubs the queue to accept every card it is offered. */
    private void queueAcceptsEveryCard() {
        when(this.sqsOperations.send(JobSubmissionServiceTest.<String>anySendConfigurer()))
                .thenReturn(acceptedPublish());
    }

    /**
     * Stubs the queue to accept cards until the given one-based ordinal and then to refuse that card and
     * every later one, which is how a queue that has become unreachable behaves.
     */
    private void queueRefusesFromCard(final int failingOrdinal) {
        queueRefusesFromCard(failingOrdinal, () -> new IllegalStateException(CAUSE_TEXT));
    }

    /**
     * Stubs the queue to accept cards until the given one-based ordinal and then to refuse that card
     * and every later one with a failure the caller supplies.
     *
     * <p>The failure is produced by a supplier rather than passed as an instance so that each refusal
     * raises its own object. Rethrowing one instance would let a single stack trace accumulate frames
     * across attempts, which is an artefact of the double rather than a property of the service.
     *
     * @param failingOrdinal the one-based ordinal of the first card to be refused
     * @param refusal        produces the failure each refused publish raises
     */
    private void queueRefusesFromCard(final int failingOrdinal,
            final Supplier<RuntimeException> refusal) {
        final AtomicInteger attempts = new AtomicInteger();
        when(this.sqsOperations.send(JobSubmissionServiceTest.<String>anySendConfigurer()))
                .thenAnswer(invocation -> {
                    if (attempts.incrementAndGet() >= failingOrdinal) {
                        throw refusal.get();
                    }
                    return acceptedPublish();
                });
    }

    /**
     * Verifies that exactly the expected number of publishes was attempted, that nothing else was asked
     * of the messaging template, and replays each captured configurer to recover what was sent.
     */
    private List<RecordedPublish> capturedPublishes(final int expectedAttempts) {
        verify(this.sqsOperations, times(expectedAttempts))
                .send(this.sendConfigurerCaptor.capture());
        verifyNoMoreInteractions(this.sqsOperations);

        final List<RecordedPublish> recorded = new ArrayList<>(expectedAttempts);
        for (final Consumer<SqsSendOptions<String>> configurer
                : this.sendConfigurerCaptor.getAllValues()) {
            final RecordedPublish publish = new RecordedPublish();
            configurer.accept(publish);
            recorded.add(publish);
        }
        return List.copyOf(recorded);
    }

    /** Asserts that the messaging template was never asked to publish anything. */
    private void assertNothingWasPublished() {
        verify(this.sqsOperations, never()).send(JobSubmissionServiceTest.<String>anySendConfigurer());
        verifyNoMoreInteractions(this.sqsOperations);
    }

    /** The bodies of the recorded publishes, in publish order. */
    private static List<String> payloadsOf(final List<RecordedPublish> recorded) {
        final List<String> payloads = new ArrayList<>(recorded.size());
        for (final RecordedPublish publish : recorded) {
            payloads.add(publish.payload);
        }
        return List.copyOf(payloads);
    }

    /** The deduplication identifiers of the recorded publishes, in publish order. */
    private static List<String> deduplicationIdsOf(final List<RecordedPublish> recorded) {
        final List<String> identifiers = new ArrayList<>(recorded.size());
        for (final RecordedPublish publish : recorded) {
            identifiers.add(publish.messageDeduplicationId);
        }
        return List.copyOf(identifiers);
    }

    /**
     * The encoded width of a fixed-width value, measured on single-byte encoded bytes rather than on the
     * character count, because the record width is a byte contract and a character count would silently
     * accept a value that does not fit the record.
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /** The single diagnostic the service records at error level, asserted to be the only one. */
    private ILoggingEvent onlyErrorDiagnostic() {
        final List<ILoggingEvent> errors = this.logRecorder.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertThat(errors).as("one refused card records exactly one operator diagnostic").hasSize(1);
        return errors.getFirst();
    }

    /**
     * Reads the whole value the diagnostic records under one label.
     *
     * <p>The value is taken up to the next space, which is exactly how a log reader parses these
     * space-separated fields. Reading the whole value is deliberate: a {@code contains} assertion can
     * be satisfied by a value that carries the expected text <em>and</em> more, so a claim about a
     * value's shape has to be made against the whole of it.
     *
     * @param label    the field label, including its equals sign
     * @param recorded the formatted diagnostic
     * @return the value recorded under the label, possibly empty, never {@code null}
     */
    private static String codeFollowing(final String label, final String recorded) {
        final int labelAt = recorded.indexOf(label);
        assertThat(labelAt).as("the diagnostic must carry the label %s: %s", label, recorded)
                .isNotNegative();
        final int valueFrom = labelAt + label.length();
        final int valueTo = recorded.indexOf(' ', valueFrom);
        return valueTo < 0 ? recorded.substring(valueFrom) : recorded.substring(valueFrom, valueTo);
    }

    /**
     * Builds a publish failure whose cause chain closes on itself.
     *
     * <p>Java forbids a throwable being its own direct cause but permits a longer cycle, so a pair
     * that cause each other is constructible - and is producible in the wild by a client that wraps a
     * retained failure in itself, or by a deserialised exception graph. A naive walk over such a chain
     * does not return, so the service's depth bound is what makes its chain rendering total, and this
     * factory is what exercises that.
     *
     * @return the outer failure of a two-element cause cycle
     */
    private static RuntimeException cyclicRefusal() {
        final IllegalStateException outer = new IllegalStateException(HOSTILE_CAUSE_TEXT);
        final IllegalArgumentException inner = new IllegalArgumentException(HOSTILE_CAUSE_TEXT);
        outer.initCause(inner);
        inner.initCause(outer);
        return outer;
    }

    @Nested
    @DisplayName("the hand-written oracle is itself eighty columns wide, seventeen cards deep")
    class OracleSelfCheck {

        @Test
        @DisplayName("every hand-written expected card is exactly eighty encoded bytes, so the oracle cannot be the thing that is wrong")
        void everyOracleCardIsExactlyEightyEncodedBytes() {
            final List<String> oracle = oracleJobImage(START_DATE, END_DATE);

            assertThat(oracle).as("hand-written expected cards").hasSize(ORACLE_CARD_COUNT)
                    .allSatisfy(card -> assertThat(encodedWidth(card))
                            .as("encoded width of '%s'", card).isEqualTo(ORACLE_CARD_WIDTH));
        }

        @Test
        @DisplayName("the three hand-written frame shapes add up to eighty: eighteen plus ten plus fifty-two, sixteen plus ten plus fifty-four, ten plus one plus ten plus fifty-nine")
        void theThreeFrameShapesAddUpToEighty() {
            assertThat(encodedWidth(ORACLE_START_SLOT_PREFIX)).as("card 11 prefix width")
                    .isEqualTo(ORACLE_START_SLOT_OFFSET);
            assertThat(encodedWidth(ORACLE_START_SLOT_SUFFIX)).as("card 11 suffix width")
                    .isEqualTo(ORACLE_CARD_WIDTH - ORACLE_START_SLOT_OFFSET - ORACLE_SLOT_WIDTH);
            assertThat(encodedWidth(ORACLE_END_SLOT_PREFIX)).as("card 12 prefix width")
                    .isEqualTo(ORACLE_END_SLOT_OFFSET);
            assertThat(encodedWidth(ORACLE_END_SLOT_SUFFIX)).as("card 12 suffix width")
                    .isEqualTo(ORACLE_CARD_WIDTH - ORACLE_END_SLOT_OFFSET - ORACLE_SLOT_WIDTH);
            assertThat(encodedWidth(ORACLE_PARAMETER_SLOT_SEPARATOR)).as("card 15 separator width")
                    .isEqualTo(ORACLE_PARAMETER_END_SLOT_OFFSET - ORACLE_SLOT_WIDTH);
            assertThat(encodedWidth(ORACLE_PARAMETER_SLOT_SUFFIX)).as("card 15 suffix width")
                    .isEqualTo(ORACLE_CARD_WIDTH - ORACLE_PARAMETER_END_SLOT_OFFSET
                            - ORACLE_SLOT_WIDTH);
        }

        @Test
        @DisplayName("the published contract figures still agree with the hand-written legacy figures, so a drift in either direction fails")
        void thePublishedFiguresAgreeWithTheLegacyFigures() {
            assertThat(JclCardImageBuilder.CARD_COUNT).as("published card count")
                    .isEqualTo(ORACLE_CARD_COUNT);
            assertThat(JclCardImageBuilder.CARD_IMAGE_WIDTH).as("published card width")
                    .isEqualTo(ORACLE_CARD_WIDTH);
            assertThat(JclCardImageBuilder.DATE_SLOT_WIDTH).as("published date-slot width")
                    .isEqualTo(ORACLE_SLOT_WIDTH);
            assertThat(JclCardImageBuilder.TOTAL_IMAGE_WIDTH).as("published concatenated width")
                    .isEqualTo(ORACLE_TOTAL_IMAGE_WIDTH);
            assertThat(JclCardImageBuilder.OVERSIZED_REDEFINE_CARD_BOUND)
                    .as("published defensive card bound").isEqualTo(ORACLE_REDEFINE_CARD_BOUND);
            assertThat(JclCardImageBuilder.EOF_SENTINEL_CARD).as("published sentinel content")
                    .isEqualTo(ORACLE_SENTINEL_CONTENT);
            assertThat(JobSubmissionException.RECORD_SIZE).as("published record size")
                    .isEqualTo(ORACLE_CARD_WIDTH);
        }
    }

    @Nested
    @DisplayName("configuration arrives from properties and is validated when the bean is built")
    class ConstructionContract {

        @Test
        @DisplayName("a destination that is not a first-in-first-out queue is refused at construction, because append ordering cannot be honoured without one")
        void aNonFifoDestinationIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(
                            JobSubmissionServiceTest.this.sqsOperations, "JOBS",
                            MESSAGE_GROUP_ID))
                    .withMessageContaining("carddemo.aws.sqs.job-submission-queue")
                    .withMessageContaining(".fifo");
        }

        @ParameterizedTest(name = "queue name [{0}]")
        @ValueSource(strings = {EMPTY_TEXT, "   "})
        @DisplayName("a blank destination fails when the bean is built rather than at the first submission")
        void aBlankDestinationFailsAtConstruction(final String blankQueueName) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(
                            JobSubmissionServiceTest.this.sqsOperations, blankQueueName,
                            MESSAGE_GROUP_ID))
                    .withMessageContaining("carddemo.aws.sqs.job-submission-queue")
                    .withMessageContaining("no default");
        }

        @Test
        @DisplayName("an absent destination fails when the bean is built, because the production profile resolves it from the environment with no fallback")
        void anAbsentDestinationFailsAtConstruction() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(
                            JobSubmissionServiceTest.this.sqsOperations, null, MESSAGE_GROUP_ID))
                    .withMessageContaining("carddemo.aws.sqs.job-submission-queue")
                    .withMessageContaining("no default");
        }

        @ParameterizedTest(name = "message group [{0}]")
        @ValueSource(strings = {EMPTY_TEXT, "   "})
        @DisplayName("a blank message group fails when the bean is built, because append ordering depends on every card sharing one group")
        void aBlankMessageGroupFailsAtConstruction(final String blankMessageGroup) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(
                            JobSubmissionServiceTest.this.sqsOperations, QUEUE_NAME,
                            blankMessageGroup))
                    .withMessageContaining("carddemo.aws.sqs.message-group-id")
                    .withMessageContaining("no default");
        }

        @Test
        @DisplayName("an absent message group fails when the bean is built")
        void anAbsentMessageGroupFailsAtConstruction() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(
                            JobSubmissionServiceTest.this.sqsOperations, QUEUE_NAME, null))
                    .withMessageContaining("carddemo.aws.sqs.message-group-id")
                    .withMessageContaining("no default");
        }

        @Test
        @DisplayName("a missing messaging collaborator is refused deterministically and names the parameter")
        void aMissingMessagingCollaboratorIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new JobSubmissionService(null, QUEUE_NAME, MESSAGE_GROUP_ID))
                    .withMessageContaining("sqsOperations");
        }

        @Test
        @DisplayName("the configured destination is the one every one of the seventeen messages is addressed to")
        void theConfiguredDestinationIsTheOneEveryMessageCarries() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(capturedPublishes(ORACLE_CARD_COUNT)).as("recorded publishes")
                    .hasSize(ORACLE_CARD_COUNT)
                    .allSatisfy(publish -> assertThat(publish.queue).as("destination queue")
                            .isEqualTo(QUEUE_NAME));
        }

        @Test
        @DisplayName("a destination named for the legacy queue identity is honoured unchanged, so the legacy name survives wherever a deployment chooses to use it")
        void aDestinationNamedForTheLegacyQueueIsHonoured() {
            final String legacyNamedQueue = JobSubmissionException.DEFAULT_QUEUE_NAME + ".fifo";
            final JobSubmissionService legacyNamedService = new JobSubmissionService(
                    JobSubmissionServiceTest.this.sqsOperations, legacyNamedQueue, MESSAGE_GROUP_ID);
            queueAcceptsEveryCard();

            legacyNamedService.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(capturedPublishes(ORACLE_CARD_COUNT)).as("recorded publishes")
                    .allSatisfy(publish -> assertThat(publish.queue).as("destination queue")
                            .isEqualTo(legacyNamedQueue));
        }
    }

    @Nested
    @DisplayName("a complete submission is seventeen ordered eighty-byte messages, one card per message")
    class SeventeenOrderedMessagesContract {

        @Test
        @DisplayName("exactly seventeen publishes are made and nothing else is asked of the messaging template")
        void exactlySeventeenPublishesAreMade() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(capturedPublishes(ORACLE_CARD_COUNT)).as("recorded publishes")
                    .hasSize(ORACLE_CARD_COUNT);
        }

        @Test
        @DisplayName("the published bodies are the seventeen hand-written card images, in the legacy card group's own order")
        void thePublishedBodiesAreTheOracleCardsInOrder() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            // Ordered equality, not set membership: the queue is defined append-on-write, so the
            // sequence itself is the contract.
            assertThat(payloadsOf(capturedPublishes(ORACLE_CARD_COUNT)))
                    .as("published bodies in publish order")
                    .containsExactlyElementsOf(oracleJobImage(START_DATE, END_DATE));
        }

        @Test
        @DisplayName("every published body is exactly eighty encoded bytes and keeps its trailing spaces, because the record is fixed width and is never trimmed")
        void everyPublishedBodyIsExactlyEightyEncodedBytes() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(capturedPublishes(ORACLE_CARD_COUNT)).as("recorded publishes")
                    .hasSize(ORACLE_CARD_COUNT)
                    .allSatisfy(publish -> {
                        assertThat(encodedWidth(publish.payload)).as("encoded body width")
                                .isEqualTo(ORACLE_CARD_WIDTH);
                        assertThat(publish.payload).as("body retains its fixed-width padding")
                                .isNotEqualTo(publish.payload.stripTrailing());
                    });
        }

        @Test
        @DisplayName("no published body is absent or blank, so no message can reach the batch tier carrying nothing")
        void noPublishedBodyIsAbsentOrBlank() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(payloadsOf(capturedPublishes(ORACLE_CARD_COUNT))).as("published bodies")
                    .hasSize(ORACLE_CARD_COUNT)
                    .doesNotContainNull()
                    .allSatisfy(body -> assertThat(body).as("body content").isNotBlank());
        }

        @Test
        @DisplayName("the outcome reports seventeen requested, seventeen published, no failure, and is complete rather than partial")
        void theOutcomeReportsACompleteSubmission() {
            queueAcceptsEveryCard();

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(result.cardsRequested()).as("cards requested").isEqualTo(ORACLE_CARD_COUNT);
            assertThat(result.cardsPublished()).as("cards published").isEqualTo(ORACLE_CARD_COUNT);
            assertThat(result.failed()).as("failure indicator").isFalse();
            assertThat(result.failureMessage()).as("failure text").isEmpty();
            assertThat(result.complete()).as("complete indicator").isTrue();
            assertThat(result.partial()).as("partial indicator").isFalse();
        }

        @Test
        @DisplayName("the caller-supplied-identity entry point publishes the same seventeen bodies in the same order, so only the identity differs")
        void theIdentityBearingEntryPointPublishesTheSameSeventeenBodies() {
            queueAcceptsEveryCard();

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(CALLER_SUBMISSION_ID, START_DATE, END_DATE);

            assertThat(result.complete()).as("complete indicator").isTrue();
            assertThat(payloadsOf(capturedPublishes(ORACLE_CARD_COUNT)))
                    .as("published bodies in publish order")
                    .containsExactlyElementsOf(oracleJobImage(START_DATE, END_DATE));
        }

        @Test
        @DisplayName("a canonical image handed in directly is published card for card, so the stream publisher and the report path cannot drift apart")
        void aCanonicalImageHandedInDirectlyIsPublishedCardForCard() {
            queueAcceptsEveryCard();

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitCanonicalJobImage(CALLER_SUBMISSION_ID,
                            oracleJobImage(START_DATE, END_DATE));

            assertThat(result.complete()).as("complete indicator").isTrue();
            assertThat(payloadsOf(capturedPublishes(ORACLE_CARD_COUNT)))
                    .as("published bodies in publish order")
                    .containsExactlyElementsOf(oracleJobImage(START_DATE, END_DATE));
        }

        @Test
        @DisplayName("an arbitrary well-formed stream is published card for card by the general stream publisher, one message per card")
        void anArbitraryWellFormedStreamIsPublishedCardForCard() {
            queueAcceptsEveryCard();
            final List<String> stream = List.of(ORACLE_JOB_CARD, ORACLE_NOTIFY_CARD,
                    ORACLE_SENTINEL_CARD);

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitJobStream(CALLER_SUBMISSION_ID, stream);

            assertThat(result.cardsRequested()).as("cards requested").isEqualTo(stream.size());
            assertThat(result.cardsPublished()).as("cards published").isEqualTo(stream.size());
            assertThat(payloadsOf(capturedPublishes(stream.size())))
                    .as("published bodies in publish order").containsExactlyElementsOf(stream);
        }

        @Test
        @DisplayName("neither a delivery delay nor a message header is ever set, because neither has a legacy antecedent and a delay would add a timing characteristic the migration must not inherit")
        void neitherADelayNorAHeaderIsEverSet() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(capturedPublishes(ORACLE_CARD_COUNT)).as("recorded publishes")
                    .allSatisfy(publish -> {
                        assertThat(publish.delaySettings).as("delivery delays set").isZero();
                        assertThat(publish.headerSettings).as("single headers set").isZero();
                        assertThat(publish.headerMapSettings).as("header maps set").isZero();
                    });
        }
    }

    @Nested
    @DisplayName("the four ten-character date slots sit at their verified offsets inside an eighty-column frame")
    class DateSubstitutionSlotContract {

        @Test
        @DisplayName("the first sort-symbol slot carries the start date at offset eighteen, with an eighteen-character prefix and a fifty-two-character suffix that are byte-identical to the frame")
        void theFirstSortSymbolSlotCarriesTheStartDate() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String card = payloadsOf(capturedPublishes(ORACLE_CARD_COUNT))
                    .get(ORACLE_START_SLOT_CARD - FIRST_CARD_ORDINAL);
            assertThat(encodedWidth(card)).as("card width").isEqualTo(ORACLE_CARD_WIDTH);
            assertThat(card.substring(0, ORACLE_START_SLOT_OFFSET)).as("frame prefix")
                    .isEqualTo(ORACLE_START_SLOT_PREFIX);
            assertThat(card.substring(ORACLE_START_SLOT_OFFSET,
                    ORACLE_START_SLOT_OFFSET + ORACLE_SLOT_WIDTH)).as("date slot")
                    .isEqualTo(START_DATE);
            assertThat(card.substring(ORACLE_START_SLOT_OFFSET + ORACLE_SLOT_WIDTH))
                    .as("frame suffix").isEqualTo(ORACLE_START_SLOT_SUFFIX);
        }

        @Test
        @DisplayName("the second sort-symbol slot carries the end date at offset sixteen, with a sixteen-character prefix and a fifty-four-character suffix that are byte-identical to the frame")
        void theSecondSortSymbolSlotCarriesTheEndDate() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String card = payloadsOf(capturedPublishes(ORACLE_CARD_COUNT))
                    .get(ORACLE_END_SLOT_CARD - FIRST_CARD_ORDINAL);
            assertThat(encodedWidth(card)).as("card width").isEqualTo(ORACLE_CARD_WIDTH);
            assertThat(card.substring(0, ORACLE_END_SLOT_OFFSET)).as("frame prefix")
                    .isEqualTo(ORACLE_END_SLOT_PREFIX);
            assertThat(card.substring(ORACLE_END_SLOT_OFFSET,
                    ORACLE_END_SLOT_OFFSET + ORACLE_SLOT_WIDTH)).as("date slot")
                    .isEqualTo(END_DATE);
            assertThat(card.substring(ORACLE_END_SLOT_OFFSET + ORACLE_SLOT_WIDTH))
                    .as("frame suffix").isEqualTo(ORACLE_END_SLOT_SUFFIX);
        }

        @Test
        @DisplayName("the parameter card carries both remaining slots as ten, one, ten and fifty-nine: start date, single separator, end date, padding")
        void theParameterCardCarriesBothRemainingSlots() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String card = payloadsOf(capturedPublishes(ORACLE_CARD_COUNT))
                    .get(ORACLE_PARAMETER_SLOT_CARD - FIRST_CARD_ORDINAL);
            assertThat(encodedWidth(card)).as("card width").isEqualTo(ORACLE_CARD_WIDTH);
            assertThat(card.substring(ORACLE_PARAMETER_START_SLOT_OFFSET,
                    ORACLE_PARAMETER_START_SLOT_OFFSET + ORACLE_SLOT_WIDTH))
                    .as("third date slot").isEqualTo(START_DATE);
            assertThat(card.substring(ORACLE_PARAMETER_START_SLOT_OFFSET + ORACLE_SLOT_WIDTH,
                    ORACLE_PARAMETER_END_SLOT_OFFSET)).as("slot separator")
                    .isEqualTo(ORACLE_PARAMETER_SLOT_SEPARATOR);
            assertThat(card.substring(ORACLE_PARAMETER_END_SLOT_OFFSET,
                    ORACLE_PARAMETER_END_SLOT_OFFSET + ORACLE_SLOT_WIDTH))
                    .as("fourth date slot").isEqualTo(END_DATE);
            assertThat(card.substring(ORACLE_PARAMETER_END_SLOT_OFFSET + ORACLE_SLOT_WIDTH))
                    .as("frame suffix").isEqualTo(ORACLE_PARAMETER_SLOT_SUFFIX);
        }

        @Test
        @DisplayName("changing the reporting period changes exactly the three slot-bearing cards and leaves the other fourteen byte-identical")
        void changingThePeriodChangesOnlyTheSlotBearingCards() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(OTHER_START_DATE, OTHER_END_DATE);

            final List<String> published = payloadsOf(capturedPublishes(ORACLE_CARD_COUNT));
            final List<String> firstPeriod = oracleJobImage(START_DATE, END_DATE);
            final List<String> secondPeriod = oracleJobImage(OTHER_START_DATE, OTHER_END_DATE);

            assertThat(published).as("published bodies for the second period")
                    .containsExactlyElementsOf(secondPeriod);
            for (int ordinal = FIRST_CARD_ORDINAL; ordinal <= ORACLE_CARD_COUNT; ordinal++) {
                final String expected = secondPeriod.get(ordinal - FIRST_CARD_ORDINAL);
                final String otherPeriod = firstPeriod.get(ordinal - FIRST_CARD_ORDINAL);
                if (ordinal == ORACLE_START_SLOT_CARD || ordinal == ORACLE_END_SLOT_CARD
                        || ordinal == ORACLE_PARAMETER_SLOT_CARD) {
                    assertThat(expected).as("slot-bearing card " + ordinal + " tracks the period")
                            .isNotEqualTo(otherPeriod);
                } else {
                    assertThat(expected).as("fixed card " + ordinal + " is period-independent")
                            .isEqualTo(otherPeriod);
                }
            }
        }

        @ParameterizedTest(name = "slot value [{0}]")
        @ValueSource(strings = {"2022-1-1", "22-01-01", "2022-01-01 ", " 2022-01-01", "2022/01/01",
            "2022-01-011", "2022-01-0"})
        @DisplayName("a slot value that is not exactly ten characters in the fixed year-month-day shape is refused before any card is published, so the eighty-column frame is never disturbed")
        void aMisshapedSlotValueIsRefusedBeforeAnythingIsPublished(final String misshapedDate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(misshapedDate, END_DATE));

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("a slot value naming a day that does not exist is refused before any card is published, so a calendar-invalid period cannot reach the batch tier")
        void aNonCalendarSlotValueIsRefusedBeforeAnythingIsPublished() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(START_DATE, "2021-02-29"));

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("a blank slot value is refused before any card is published, so a card can never carry an empty date inside its frame")
        void aBlankSlotValueIsRefusedBeforeAnythingIsPublished() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(" ".repeat(ORACLE_SLOT_WIDTH), END_DATE));

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("a slot value carrying the frame's own apostrophe is refused, so no value can break out of a sort character constant")
        void aSlotValueCarryingAnApostropheIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob("2022-01'01", END_DATE));

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("the caller-supplied-identity entry point refuses the same slot values, so the two report paths cannot diverge on validation")
        void theIdentityBearingEntryPointRefusesTheSameSlotValues() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(CALLER_SUBMISSION_ID, "2022-1-1", END_DATE));

            assertNothingWasPublished();
        }
    }

    @Nested
    @DisplayName("the end-of-stream sentinel is transmitted, because the legacy raises its termination flag before the unconditional write")
    class SentinelCardContract {

        @Test
        @DisplayName("the seventeenth and final message body is the sentinel card, at eighty bytes and space padded, because the flag is set before the write rather than after")
        void theFinalMessageBodyIsTheSentinelCard() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final List<String> published = payloadsOf(capturedPublishes(ORACLE_CARD_COUNT));
            final String finalBody = published.get(ORACLE_CARD_COUNT - FIRST_CARD_ORDINAL);
            assertThat(finalBody).as("the seventeenth message body").isEqualTo(ORACLE_SENTINEL_CARD);
            assertThat(encodedWidth(finalBody)).as("sentinel encoded width")
                    .isEqualTo(ORACLE_CARD_WIDTH);
            assertThat(finalBody).as("sentinel content before its padding")
                    .startsWith(ORACLE_SENTINEL_CONTENT);
            assertThat(finalBody.substring(ORACLE_SENTINEL_CONTENT.length()))
                    .as("sentinel padding")
                    .isEqualTo(" ".repeat(ORACLE_CARD_WIDTH - ORACLE_SENTINEL_CONTENT.length()));
        }

        @Test
        @DisplayName("the sentinel appears exactly once and only as the final message, so no earlier card can truncate the submission")
        void theSentinelAppearsOnlyOnceAndOnlyLast() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final List<String> published = payloadsOf(capturedPublishes(ORACLE_CARD_COUNT));
            assertThat(published).as("published bodies")
                    .filteredOn(ORACLE_SENTINEL_CARD::equals).hasSize(1);
            assertThat(published.subList(0, ORACLE_CARD_COUNT - FIRST_CARD_ORDINAL))
                    .as("the sixteen bodies before the sentinel")
                    .doesNotContain(ORACLE_SENTINEL_CARD);
        }

        @Test
        @DisplayName("no eighteenth publish is ever attempted: the sentinel ends the stream only after it has itself been published")
        void noEighteenthPublishIsEverAttempted() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            // times(17) followed by verifyNoMoreInteractions is what makes an eighteenth publish a
            // failure rather than something the assertions would tolerate.
            assertThat(capturedPublishes(ORACLE_CARD_COUNT)).as("publish attempts")
                    .hasSize(ORACLE_CARD_COUNT);
        }

        @Test
        @DisplayName("a sentinel reached early ends the stream after it is published, so the cards behind it are never sent")
        void aSentinelReachedEarlyEndsTheStreamAfterItIsPublished() {
            queueAcceptsEveryCard();
            final List<String> stream = List.of(ORACLE_JOB_CARD, ORACLE_SENTINEL_CARD,
                    ORACLE_NOTIFY_CARD);

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitJobStream(CALLER_SUBMISSION_ID, stream);

            assertThat(result.cardsRequested()).as("cards requested").isEqualTo(stream.size());
            assertThat(result.cardsPublished()).as("cards published").isEqualTo(2);
            assertThat(result.failed()).as("failure indicator").isFalse();
            assertThat(result.complete()).as("complete indicator").isFalse();
            assertThat(result.partial()).as("partial indicator").isFalse();
            assertThat(payloadsOf(capturedPublishes(2))).as("published bodies")
                    .containsExactly(ORACLE_JOB_CARD, ORACLE_SENTINEL_CARD);
        }

        @Test
        @DisplayName("an all-spaces card ends the stream after being published too, because the legacy combined relation tests spaces as well as the sentinel literal")
        void anAllSpacesCardAlsoEndsTheStreamAfterBeingPublished() {
            queueAcceptsEveryCard();
            final String blankCard = " ".repeat(ORACLE_CARD_WIDTH);
            final List<String> stream = List.of(ORACLE_JOB_CARD, blankCard, ORACLE_NOTIFY_CARD);

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitJobStream(CALLER_SUBMISSION_ID, stream);

            assertThat(result.cardsPublished()).as("cards published").isEqualTo(2);
            assertThat(payloadsOf(capturedPublishes(2))).as("published bodies")
                    .containsExactly(ORACLE_JOB_CARD, blankCard);
        }

        @Test
        @DisplayName("a canonical image whose sentinel is not the final card is refused before anything is published, so an accepted submission can never be truncated")
        void aCanonicalImageWithAnEarlySentinelIsRefused() {
            final List<String> misordered = new ArrayList<>(oracleJobImage(START_DATE, END_DATE));
            misordered.set(0, ORACLE_SENTINEL_CARD);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitCanonicalJobImage(CALLER_SUBMISSION_ID, misordered))
                    .withMessageContaining("ends the stream");

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("a canonical image with no sentinel at all is refused before anything is published")
        void aCanonicalImageWithNoSentinelIsRefused() {
            final List<String> sentinelless = new ArrayList<>(oracleJobImage(START_DATE, END_DATE));
            sentinelless.set(ORACLE_CARD_COUNT - FIRST_CARD_ORDINAL, ORACLE_COMMENT_CARD);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitCanonicalJobImage(CALLER_SUBMISSION_ID, sentinelless))
                    .withMessageContaining("end-of-stream card");

            assertNothingWasPublished();
        }

        @ParameterizedTest(name = "cards offered [{0}]")
        @ValueSource(ints = {16, 18})
        @DisplayName("a canonical image that is not exactly seventeen cards is refused before anything is published, in either direction")
        void aCanonicalImageOfTheWrongLengthIsRefused(final int cardCount) {
            final List<String> wrongLength = new ArrayList<>(oracleJobImage(START_DATE, END_DATE));
            if (cardCount < ORACLE_CARD_COUNT) {
                wrongLength.remove(0);
            } else {
                wrongLength.add(0, ORACLE_COMMENT_CARD);
            }

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitCanonicalJobImage(CALLER_SUBMISSION_ID, wrongLength))
                    .withMessageContaining("canonical job image");

            assertNothingWasPublished();
        }
    }

    @Nested
    @DisplayName("first-in-first-out arguments: one message group for the whole submission, one deduplication identifier per card")
    class FifoOrderingContract {

        @Test
        @DisplayName("all seventeen messages carry the same message group identifier, which is what preserves the append order the queue definition specifies")
        void allSeventeenMessagesShareOneMessageGroup() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(capturedPublishes(ORACLE_CARD_COUNT)).as("recorded publishes")
                    .hasSize(ORACLE_CARD_COUNT)
                    .extracting(publish -> publish.messageGroupId)
                    .containsOnly(MESSAGE_GROUP_ID);
        }

        @Test
        @DisplayName("every message carries a deduplication identifier, because a first-in-first-out queue without content-based deduplication requires one")
        void everyMessageCarriesADeduplicationIdentifier() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(deduplicationIdsOf(capturedPublishes(ORACLE_CARD_COUNT)))
                    .as("deduplication identifiers").hasSize(ORACLE_CARD_COUNT)
                    .doesNotContainNull()
                    .doesNotHaveDuplicates()
                    .allSatisfy(identifier -> {
                        assertThat(identifier).as("identifier content").isNotBlank()
                                .doesNotContainAnyWhitespaces();
                        assertThat(identifier.length()).as("identifier length")
                                .isLessThanOrEqualTo(ORACLE_DEDUPLICATION_ID_MAX_LENGTH);
                    });
        }

        @Test
        @DisplayName("within one submission the seventeen identifiers share one identity and differ only by the one-based card ordinal, so no two cards of a submission collapse")
        void theIdentifiersDifferOnlyByTheOneBasedOrdinal() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final List<String> identifiers =
                    deduplicationIdsOf(capturedPublishes(ORACLE_CARD_COUNT));
            final String first = identifiers.getFirst();
            final String identity = first.substring(0,
                    first.lastIndexOf(ORACLE_ORDINAL_SEPARATOR));
            for (int ordinal = FIRST_CARD_ORDINAL; ordinal <= ORACLE_CARD_COUNT; ordinal++) {
                assertThat(identifiers.get(ordinal - FIRST_CARD_ORDINAL))
                        .as("identifier of card " + ordinal)
                        .isEqualTo(identity + ORACLE_ORDINAL_SEPARATOR + ordinal);
            }
        }

        @Test
        @DisplayName("a caller-supplied identity is used verbatim, so the caller decides which two submissions the queue would treat as one")
        void aCallerSuppliedIdentityIsUsedVerbatim() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(CALLER_SUBMISSION_ID, START_DATE, END_DATE);

            final List<String> expected = new ArrayList<>(ORACLE_CARD_COUNT);
            for (int ordinal = FIRST_CARD_ORDINAL; ordinal <= ORACLE_CARD_COUNT; ordinal++) {
                expected.add(CALLER_SUBMISSION_ID + ORACLE_ORDINAL_SEPARATOR + ordinal);
            }
            assertThat(deduplicationIdsOf(capturedPublishes(ORACLE_CARD_COUNT)))
                    .as("deduplication identifiers").containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("two submissions of the same reporting period get entirely different identifiers, because the queue appends on write and the legacy re-wrote every card on every pass")
        void twoSubmissionsOfTheSamePeriodGetDifferentIdentifiers() {
            queueAcceptsEveryCard();

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);
            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final List<String> identifiers =
                    deduplicationIdsOf(capturedPublishes(ORACLE_CARD_COUNT * 2));
            final List<String> firstAttempt = identifiers.subList(0, ORACLE_CARD_COUNT);
            final List<String> secondAttempt =
                    identifiers.subList(ORACLE_CARD_COUNT, identifiers.size());

            assertThat(identifiers).as("identifiers across two attempts at one period")
                    .doesNotHaveDuplicates();
            assertThat(secondAttempt).as("the second attempt shares no identifier with the first")
                    .doesNotContainAnyElementsOf(firstAttempt);
        }

        @Test
        @DisplayName("an identity that would make the identifier longer than the queue service accepts is refused before anything is published")
        void anOverlongIdentityIsRefusedBeforeAnythingIsPublished() {
            final String overlong = "S".repeat(ORACLE_DEDUPLICATION_ID_MAX_LENGTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(overlong, START_DATE, END_DATE))
                    .withMessageContaining(String.valueOf(ORACLE_DEDUPLICATION_ID_MAX_LENGTH));

            assertNothingWasPublished();
        }

        @ParameterizedTest(name = "identity [{0}]")
        @ValueSource(strings = {EMPTY_TEXT, "   ", "REPORT REQUEST", "REPORT\tREQUEST"})
        @DisplayName("an identity that is blank or carries whitespace is refused, because a deduplication identifier may not carry whitespace")
        void anUnusableIdentityIsRefused(final String unusableIdentity) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(unusableIdentity, START_DATE, END_DATE))
                    .withMessageContaining("submissionId");

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("an absent identity is refused deterministically and names the parameter")
        void anAbsentIdentityIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(null, START_DATE, END_DATE))
                    .withMessageContaining("submissionId");

            assertNothingWasPublished();
        }
    }

    @Nested
    @DisplayName("a refused publish is non-fatal: it is logged, it stops the remaining cards, and control returns normally")
    class NonFatalFailureContract {

        @Test
        @DisplayName("a refused card mid-stream lets the submission return normally: no exception escapes, in line with the queue's ignore-on-error attribute")
        void aRefusedCardLetsTheSubmissionReturnNormally() {
            queueRefusesFromCard(MID_STREAM_FAILING_ORDINAL);

            assertThatNoException().isThrownBy(() -> JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE));
        }

        @Test
        @DisplayName("a card refused as the fifth attempt stops the remaining cards: exactly five publish attempts are made and the twelve cards behind it are never offered")
        void aRefusedCardStopsTheRemainingCards() {
            queueRefusesFromCard(MID_STREAM_FAILING_ORDINAL);

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final List<String> attempted =
                    payloadsOf(capturedPublishes(MID_STREAM_FAILING_ORDINAL));
            assertThat(attempted).as("bodies actually offered to the queue")
                    .containsExactlyElementsOf(oracleJobImage(START_DATE, END_DATE)
                            .subList(0, MID_STREAM_FAILING_ORDINAL));
            assertThat(attempted).as("the sentinel is never reached after a refusal")
                    .doesNotContain(ORACLE_SENTINEL_CARD);
        }

        @Test
        @DisplayName("no refused card is ever offered a second time: every attempt carries a distinct card ordinal, so the refused card is not retried")
        void noRefusedCardIsEverOfferedASecondTime() {
            queueRefusesFromCard(MID_STREAM_FAILING_ORDINAL);

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final List<RecordedPublish> attempts = capturedPublishes(MID_STREAM_FAILING_ORDINAL);
            // A card's identity is its ordinal, not its text: the legacy stream carries three
            // byte-identical comment cards, so duplicate bodies are correct and only duplicate
            // ordinals would betray a re-send of the card the queue had already refused.
            assertThat(deduplicationIdsOf(attempts)).as("card identities offered to the queue")
                    .hasSize(MID_STREAM_FAILING_ORDINAL).doesNotHaveDuplicates();
            assertThat(payloadsOf(attempts)).as("bodies offered to the queue")
                    .hasSize(MID_STREAM_FAILING_ORDINAL);
        }

        @Test
        @DisplayName("the outcome reports the refusal to the caller: failed, partial, and four of seventeen cards published")
        void theOutcomeReportsTheRefusalToTheCaller() {
            queueRefusesFromCard(MID_STREAM_FAILING_ORDINAL);

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(result.cardsRequested()).as("cards requested").isEqualTo(ORACLE_CARD_COUNT);
            assertThat(result.cardsPublished()).as("cards published")
                    .isEqualTo(MID_STREAM_FAILING_ORDINAL - FIRST_CARD_ORDINAL);
            assertThat(result.failed()).as("failure indicator").isTrue();
            assertThat(result.complete()).as("complete indicator").isFalse();
            assertThat(result.partial()).as("partial indicator").isTrue();
            assertThat(result.failureMessage()).as("operator-facing failure text")
                    .isEqualTo(ORACLE_FAILURE_TEXT);
        }

        @Test
        @DisplayName("the operator diagnostic is the frozen failure literal, byte for byte, with exactly three separate full stops and no ellipsis character")
        void theOperatorDiagnosticIsTheFrozenFailureLiteral() {
            queueRefusesFromCard(MID_STREAM_FAILING_ORDINAL);

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final ILoggingEvent diagnostic = onlyErrorDiagnostic();
            assertThat(diagnostic.getArgumentArray()).as("diagnostic arguments").isNotEmpty();
            assertThat(diagnostic.getArgumentArray()[0]).as("the operator-facing literal, untrimmed")
                    .isEqualTo(ORACLE_FAILURE_TEXT);
            assertThat(diagnostic.getFormattedMessage()).as("the recorded diagnostic")
                    .startsWith(ORACLE_FAILURE_TEXT);
            assertThat(ORACLE_FAILURE_TEXT).as("the hand-written literal")
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE)
                    .endsWith("...")
                    .doesNotEndWith("....")
                    .doesNotContain("\u2026")
                    .doesNotEndWith(" ");
        }

        @Test
        @DisplayName("the diagnostic carries the response and reason codes and the failing card's one-based ordinal, mirroring the legacy diagnostic write that precedes the screen message")
        void theDiagnosticCarriesTheCodesAndTheFailingOrdinal() {
            queueRefusesFromCard(MID_STREAM_FAILING_ORDINAL);

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            // The refusal used here carries a description and no cause, so the reason code is empty:
            // there is nothing beneath the response code to qualify it, and nothing may be invented
            // from the description. Both labels are still written, because the legacy paragraph
            // displays both codes whether or not either carries a value.
            assertThat(onlyErrorDiagnostic().getFormattedMessage()).as("the recorded diagnostic")
                    .contains("ordinal=" + MID_STREAM_FAILING_ORDINAL)
                    .contains("queue=" + QUEUE_NAME)
                    .contains("messageGroup=" + MESSAGE_GROUP_ID)
                    .contains("response=" + IllegalStateException.class.getSimpleName())
                    .contains("reason= ")
                    .contains("failureChain=" + IllegalStateException.class.getSimpleName())
                    .doesNotContain(CAUSE_TEXT);
        }

        @Test
        @DisplayName("the reason code names the type of the deepest cause, which is the qualification the response code needs and the one a client's exception chain actually carries")
        void theReasonCodeNamesTheDeepestCauseType() {
            // The legacy reason code qualified the response code - it said why, beneath the what -
            // and a messaging client expresses that qualification as a cause chain rather than as a
            // second numeric field. A send failure rooted in a socket timeout is a different
            // operational condition from one rooted in a missing queue, and this is the field that
            // distinguishes them without either failure's description being repeated.
            queueRefusesFromCard(MID_STREAM_FAILING_ORDINAL, () -> new IllegalStateException(
                    CAUSE_TEXT, new UnsupportedOperationException(CAUSE_TEXT,
                            new SocketTimeoutException(CAUSE_TEXT))));

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(onlyErrorDiagnostic().getFormattedMessage()).as("the recorded diagnostic")
                    .contains("response=" + IllegalStateException.class.getSimpleName())
                    .contains("reason=" + SocketTimeoutException.class.getSimpleName())
                    .contains("failureChain=" + IllegalStateException.class.getSimpleName()
                            + "<-" + UnsupportedOperationException.class.getSimpleName()
                            + "<-" + SocketTimeoutException.class.getSimpleName())
                    .doesNotContain(CAUSE_TEXT);
        }

        @Test
        @DisplayName("the failure type is constructed rather than thrown, and it is the codes that are logged - never the raw failure, whose stack trace would carry every description in its chain")
        void theFailureTypeIsConstructedRatherThanThrownAndTheRawFailureIsNotLogged() {
            // Two obligations are asserted together because they are one decision. The failure must
            // not be thrown, or the caller's request would abort where the legacy transaction
            // completes. And the failure must not be handed to the logger either: a rendered stack
            // trace carries getMessage() for every exception in the chain, so logging the throwable
            // reintroduces exactly the external text the codes exist to replace.
            queueRefusesFromCard(MID_STREAM_FAILING_ORDINAL,
                    () -> new IllegalStateException(HOSTILE_CAUSE_TEXT));

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(result.failed()).as("the caller is told through data, not through a throw")
                    .isTrue();

            final ILoggingEvent diagnostic = onlyErrorDiagnostic();
            assertThat(diagnostic.getThrowableProxy())
                    .as("no throwable may be rendered into this record, because its trace would"
                            + " carry the description of every exception in the chain")
                    .isNull();
            assertThat(diagnostic.getFormattedMessage())
                    .as("the record still identifies the failure by type")
                    .startsWith(ORACLE_FAILURE_TEXT)
                    .contains("response=" + IllegalStateException.class.getSimpleName());
        }

        @Test
        @DisplayName("a hostile publish-failure description reaches no part of the diagnostic: not the codes, not the chain, not a rendered trace, and not as a forged second record")
        void aHostileFailureDescriptionReachesNoPartOfTheDiagnostic() {
            // This is the property the class already enforces on every caller-supplied value, applied
            // to the one value that arrives from outside the module: the queue client's own exception
            // text. The description used here names a credential, echoes it, and carries both line
            // terminators, so a leak is unambiguous rather than inferred.
            queueRefusesFromCard(FIRST_CARD_ORDINAL,
                    () -> new IllegalStateException(HOSTILE_CAUSE_TEXT,
                            new IllegalArgumentException(HOSTILE_CAUSE_TEXT)));

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final ILoggingEvent diagnostic = onlyErrorDiagnostic();
            assertThat(diagnostic.getThrowableProxy()).as("no rendered trace").isNull();

            final String recorded = diagnostic.getFormattedMessage();
            assertThat(recorded).as("the recorded diagnostic")
                    .doesNotContain(HOSTILE_MARKER)
                    .doesNotContain("accessKey")
                    .doesNotContain("AKIAQAEXAMPLEKEY")
                    .doesNotContain("FORGED AUDIT ENTRY")
                    .doesNotContain("some.frame.Deeper");
            assertThat(recorded.indexOf(CARRIAGE_RETURN))
                    .as("a diagnostic must carry no raw carriage return: %s", recorded).isEqualTo(-1);
            assertThat(recorded.indexOf(LINE_FEED))
                    .as("a diagnostic must carry no raw line feed: %s", recorded).isEqualTo(-1);
            assertThat(recorded.indexOf(TAB))
                    .as("a diagnostic must carry no raw tab: %s", recorded).isEqualTo(-1);

            // And the codes it does carry are the sanitised type names, so suppression has not been
            // achieved by writing nothing.
            assertThat(recorded)
                    .contains("response=" + IllegalStateException.class.getSimpleName())
                    .contains("reason=" + IllegalArgumentException.class.getSimpleName());
        }

        @Test
        @DisplayName("every derived code is one unbroken token of bounded length, so no failure can lengthen a log record without limit or split it into two")
        void everyDerivedCodeIsOneBoundedToken() {
            // The legacy codes were fixed-width display fields, so an unbounded code has no legacy
            // antecedent. This asserts the shape of the whole values rather than the absence of one
            // hostile string, which is what makes the guarantee hold for descriptions this test never
            // thought of.
            queueRefusesFromCard(FIRST_CARD_ORDINAL,
                    () -> new IllegalStateException(HOSTILE_CAUSE_TEXT,
                            new IllegalArgumentException(HOSTILE_CAUSE_TEXT)));

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String recorded = onlyErrorDiagnostic().getFormattedMessage();
            assertThat(codeFollowing("response=", recorded)).as("the response code")
                    .isNotEmpty()
                    .hasSizeLessThanOrEqualTo(ORACLE_MAX_DIAGNOSTIC_CODE_LENGTH)
                    .matches(ORACLE_DIAGNOSTIC_CODE);
            assertThat(codeFollowing("reason=", recorded)).as("the reason code")
                    .isNotEmpty()
                    .hasSizeLessThanOrEqualTo(ORACLE_MAX_DIAGNOSTIC_CODE_LENGTH)
                    .matches(ORACLE_DIAGNOSTIC_CODE);
            assertThat(codeFollowing("failureChain=", recorded)).as("the failure chain")
                    .isNotEmpty()
                    .matches(ORACLE_FAILURE_CHAIN);
        }

        @Test
        @DisplayName("a self-referential cause chain terminates and is reported as truncated rather than followed forever")
        void aSelfReferentialCauseChainTerminates() {
            // A cause chain that closes on itself is producible - a client that wraps a retained
            // failure in itself, or a deserialised exception graph - and a naive walk over it does not
            // return. The depth bound is what makes this method total rather than merely usually
            // terminating, so it is exercised rather than assumed.
            queueRefusesFromCard(FIRST_CARD_ORDINAL, JobSubmissionServiceTest::cyclicRefusal);

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String chain = codeFollowing("failureChain=", onlyErrorDiagnostic()
                    .getFormattedMessage());
            assertThat(chain).as("the failure chain")
                    .matches(ORACLE_FAILURE_CHAIN)
                    .endsWith("<-...")
                    .startsWith(IllegalStateException.class.getSimpleName());
            assertThat(chain.split("<-", -1)).as("the chain is cut at its depth bound")
                    .hasSize(ORACLE_MAX_FAILURE_CHAIN_DEPTH + 1);
        }

        @Test
        @DisplayName("the submission-level consequence is recorded as a warning rather than an error, because the caller's request completes exactly as the legacy transaction does")
        void theSubmissionLevelConsequenceIsRecordedAsAWarning() {
            queueRefusesFromCard(MID_STREAM_FAILING_ORDINAL);

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final List<ILoggingEvent> warnings = JobSubmissionServiceTest.this.logRecorder.list
                    .stream().filter(event -> event.getLevel() == Level.WARN).toList();
            assertThat(warnings).as("submission-level warnings").hasSize(1);
            assertThat(warnings.getFirst().getFormattedMessage()).as("the recorded warning")
                    .contains("cardsPublished="
                            + (MID_STREAM_FAILING_ORDINAL - FIRST_CARD_ORDINAL))
                    .contains("cardsRequested=" + ORACLE_CARD_COUNT);
        }

        @Test
        @DisplayName("a refusal on the very first card publishes nothing at all and still returns normally, reporting a failure that is not partial")
        void aRefusalOnTheFirstCardPublishesNothingAndStillReturnsNormally() {
            queueRefusesFromCard(FIRST_CARD_ORDINAL);

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(result.cardsPublished()).as("cards published").isZero();
            assertThat(result.failed()).as("failure indicator").isTrue();
            assertThat(result.partial()).as("partial indicator").isFalse();
            assertThat(result.complete()).as("complete indicator").isFalse();
            assertThat(result.failureMessage()).as("operator-facing failure text")
                    .isEqualTo(ORACLE_FAILURE_TEXT);
            assertThat(capturedPublishes(FIRST_CARD_ORDINAL)).as("publish attempts").hasSize(1);
        }

        @Test
        @DisplayName("a submission whose every card is refused makes exactly one attempt, because the write-error flag is one of the emitting loop's guard conditions")
        void aSubmissionWhoseEveryCardIsRefusedMakesExactlyOneAttempt() {
            queueRefusesFromCard(FIRST_CARD_ORDINAL);

            assertThatNoException().isThrownBy(() -> JobSubmissionServiceTest.this.service
                    .submitCanonicalJobImage(CALLER_SUBMISSION_ID,
                            oracleJobImage(START_DATE, END_DATE)));

            assertThat(capturedPublishes(FIRST_CARD_ORDINAL)).as("publish attempts").hasSize(1);
        }
    }

    @Nested
    @DisplayName("the single-card entry point reports the write-error flag rather than raising it")
    class SingleCardEntryPointContract {

        @Test
        @DisplayName("an accepted card reports acceptance and is published verbatim, at eighty bytes, to the configured destination and group")
        void anAcceptedCardIsPublishedVerbatim() {
            queueAcceptsEveryCard();

            final boolean accepted = JobSubmissionServiceTest.this.service
                    .writeJobSubmissionQueue(CALLER_SUBMISSION_ID, ORACLE_JOB_CARD,
                            FIRST_CARD_ORDINAL);

            assertThat(accepted).as("acceptance indicator").isTrue();
            final RecordedPublish publish = capturedPublishes(1).getFirst();
            assertThat(publish.payload).as("published body").isEqualTo(ORACLE_JOB_CARD);
            assertThat(encodedWidth(publish.payload)).as("encoded body width")
                    .isEqualTo(ORACLE_CARD_WIDTH);
            assertThat(publish.queue).as("destination queue").isEqualTo(QUEUE_NAME);
            assertThat(publish.messageGroupId).as("message group").isEqualTo(MESSAGE_GROUP_ID);
            assertThat(publish.messageDeduplicationId).as("deduplication identifier")
                    .isEqualTo(CALLER_SUBMISSION_ID + ORACLE_ORDINAL_SEPARATOR + FIRST_CARD_ORDINAL);
        }

        @Test
        @DisplayName("a refused card reports refusal instead of throwing, and records the frozen diagnostic, which is the inverse of the legacy write-error flag")
        void aRefusedCardReportsRefusalInsteadOfThrowing() {
            queueRefusesFromCard(FIRST_CARD_ORDINAL);

            final boolean accepted = JobSubmissionServiceTest.this.service
                    .writeJobSubmissionQueue(CALLER_SUBMISSION_ID, ORACLE_JOB_CARD,
                            FIRST_CARD_ORDINAL);

            assertThat(accepted).as("acceptance indicator").isFalse();
            assertThat(onlyErrorDiagnostic().getFormattedMessage()).as("the recorded diagnostic")
                    .startsWith(ORACLE_FAILURE_TEXT);
        }

        @ParameterizedTest(name = "ordinal [{0}]")
        @ValueSource(ints = {0, -1})
        @DisplayName("an ordinal below one is refused, because the legacy card index is one-based and an identifier derived from a lower ordinal would be meaningless")
        void anOrdinalBelowOneIsRefused(final int unusableOrdinal) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .writeJobSubmissionQueue(CALLER_SUBMISSION_ID, ORACLE_JOB_CARD,
                                    unusableOrdinal))
                    .withMessageContaining("one-based");

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("a card of the wrong width is refused before it is published, because the record width is a byte contract rather than a suggestion")
        void aCardOfTheWrongWidthIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .writeJobSubmissionQueue(CALLER_SUBMISSION_ID, ORACLE_SENTINEL_CONTENT,
                                    FIRST_CARD_ORDINAL))
                    .withMessageContaining(String.valueOf(ORACLE_CARD_WIDTH));

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("a card carrying a character that is not a single US-ASCII byte is refused, because the published bytes would not be the caller's bytes")
        void aCardCarryingAMultiByteCharacterIsRefused() {
            final String multiByteCard = "\u00a3" + " ".repeat(ORACLE_CARD_WIDTH - 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .writeJobSubmissionQueue(CALLER_SUBMISSION_ID, multiByteCard,
                                    FIRST_CARD_ORDINAL));

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("a card carrying a control byte is refused, because a control byte inside a fixed-width record would reframe the record for the consumer that reads it")
        void aCardCarryingAControlByteIsRefused() {
            final String controlByteCard = "//*\n" + " ".repeat(ORACLE_CARD_WIDTH - 4);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .writeJobSubmissionQueue(CALLER_SUBMISSION_ID, controlByteCard,
                                    FIRST_CARD_ORDINAL))
                    .withMessageContaining("non-printable");

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("an absent card is refused deterministically and names the parameter")
        void anAbsentCardIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .writeJobSubmissionQueue(CALLER_SUBMISSION_ID, null, FIRST_CARD_ORDINAL))
                    .withMessageContaining("cardImage");

            assertNothingWasPublished();
        }
    }

    @Nested
    @DisplayName("the failure type this service reports through: frozen text, unchecked ancestry, chained cause")
    class FailureTypeContract {

        @Test
        @DisplayName("the frozen failure text is exactly the legacy screen literal, with three separate full stops")
        void theFrozenFailureTextIsExactlyTheLegacyScreenLiteral() {
            assertThat(JobSubmissionException.DEFAULT_MESSAGE).as("published failure literal")
                    .isEqualTo(ORACLE_FAILURE_TEXT)
                    .endsWith("...")
                    .doesNotEndWith("....")
                    .doesNotContain("\u2026");
        }

        @Test
        @DisplayName("the default queue name is the legacy queue identity, which is where that identity survives once the physical destination is renamed")
        void theDefaultQueueNameIsTheLegacyQueueIdentity() {
            assertThat(JobSubmissionException.DEFAULT_QUEUE_NAME).as("published default queue name")
                    .isEqualTo(ORACLE_QUEUE_IDENTITY);
        }

        @Test
        @DisplayName("the published record size is the eighty-character fixed width the queue definition specifies")
        void thePublishedRecordSizeIsEighty() {
            assertThat(JobSubmissionException.RECORD_SIZE).as("published record size")
                    .isEqualTo(ORACLE_CARD_WIDTH);
        }

        @Test
        @DisplayName("the failure type is unchecked, so no publishing signature is forced to declare it and a non-fatal condition cannot be turned into a checked contract")
        void theFailureTypeIsUnchecked() {
            assertThat(new JobSubmissionException(new IllegalStateException(CAUSE_TEXT)))
                    .as("the failure type").isInstanceOf(RuntimeException.class);
            assertThat(JobSubmissionException.class.getSuperclass()).as("the direct supertype")
                    .isEqualTo(RuntimeException.class);
        }

        @Test
        @DisplayName("a failure built from a cause alone carries the frozen text, the legacy queue identity and no attributable card ordinal")
        void aFailureBuiltFromACauseAloneCarriesTheFrozenText() {
            final IllegalStateException refusal = new IllegalStateException(CAUSE_TEXT);

            final JobSubmissionException failure = new JobSubmissionException(refusal);

            assertThat(failure.getMessage()).as("failure text").isEqualTo(ORACLE_FAILURE_TEXT);
            assertThat(failure.getCause()).as("chained cause").isSameAs(refusal);
            assertThat(failure.queueName()).as("queue name")
                    .isEqualTo(JobSubmissionException.DEFAULT_QUEUE_NAME);
            assertThat(failure.responseCode()).as("response code").isEmpty();
            assertThat(failure.reasonCode()).as("reason code").isEmpty();
            assertThat(failure.failedCardOrdinal()).as("failing card ordinal")
                    .isEqualTo(JobSubmissionException.ORDINAL_NOT_APPLICABLE);
        }

        @Test
        @DisplayName("a failure built with an explicit message keeps that message and its cause unchanged")
        void aFailureBuiltWithAnExplicitMessageKeepsBoth() {
            final IllegalStateException refusal = new IllegalStateException(CAUSE_TEXT);

            final JobSubmissionException failure =
                    new JobSubmissionException(ORACLE_FAILURE_TEXT, refusal);

            assertThat(failure.getMessage()).as("failure text").isEqualTo(ORACLE_FAILURE_TEXT);
            assertThat(failure.getCause()).as("chained cause").isSameAs(refusal);
        }

        @Test
        @DisplayName("a failure built with the fullest form records the codes and the one-based failing ordinal and still begins with the frozen text")
        void theFullestFormRecordsTheCodesAndTheOrdinal() {
            final IllegalStateException refusal = new IllegalStateException(CAUSE_TEXT);

            final JobSubmissionException failure = new JobSubmissionException(QUEUE_NAME,
                    IllegalStateException.class.getSimpleName(), CAUSE_TEXT,
                    MID_STREAM_FAILING_ORDINAL, refusal);

            assertThat(failure.getMessage()).as("failure text").startsWith(ORACLE_FAILURE_TEXT);
            assertThat(failure.queueName()).as("queue name").isEqualTo(QUEUE_NAME);
            assertThat(failure.responseCode()).as("response code")
                    .isEqualTo(IllegalStateException.class.getSimpleName());
            assertThat(failure.reasonCode()).as("reason code").isEqualTo(CAUSE_TEXT);
            assertThat(failure.failedCardOrdinal()).as("failing card ordinal")
                    .isEqualTo(MID_STREAM_FAILING_ORDINAL);
            assertThat(failure.getCause()).as("chained cause").isSameAs(refusal);
        }

        @Test
        @DisplayName("an absent message falls back to the frozen text, so the operator-facing text is never absent and never the word for a missing value")
        void anAbsentMessageFallsBackToTheFrozenText() {
            final JobSubmissionException failure = new JobSubmissionException(null, null);

            assertThat(failure.getMessage()).as("failure text").isEqualTo(ORACLE_FAILURE_TEXT);
            assertThat(failure.getCause()).as("chained cause").isNull();
        }
    }

    @Nested
    @DisplayName("the outcome record: three distinguishable states and a validated shape")
    class SubmissionOutcomeContract {

        @Test
        @DisplayName("a complete outcome is complete, is not partial and carries no failure text")
        void aCompleteOutcomeCarriesNoFailureText() {
            final JobSubmissionService.SubmissionResult result =
                    new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT, ORACLE_CARD_COUNT,
                            false, EMPTY_TEXT);

            assertThat(result.complete()).as("complete indicator").isTrue();
            assertThat(result.partial()).as("partial indicator").isFalse();
            assertThat(result.failureMessage()).as("failure text").isEmpty();
        }

        @Test
        @DisplayName("a partial outcome is a failure that published something, so it is partial and not complete")
        void aPartialOutcomeIsAFailureThatPublishedSomething() {
            final JobSubmissionService.SubmissionResult result =
                    new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT,
                            MID_STREAM_FAILING_ORDINAL - FIRST_CARD_ORDINAL, true,
                            ORACLE_FAILURE_TEXT);

            assertThat(result.complete()).as("complete indicator").isFalse();
            assertThat(result.partial()).as("partial indicator").isTrue();
            assertThat(result.failureMessage()).as("failure text").isEqualTo(ORACLE_FAILURE_TEXT);
        }

        @Test
        @DisplayName("a failure that published nothing is neither complete nor partial, which is how a first-card refusal is distinguished from a mid-stream one")
        void aFailureThatPublishedNothingIsNeitherCompleteNorPartial() {
            final JobSubmissionService.SubmissionResult result =
                    new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT, 0, true,
                            ORACLE_FAILURE_TEXT);

            assertThat(result.complete()).as("complete indicator").isFalse();
            assertThat(result.partial()).as("partial indicator").isFalse();
            assertThat(result.failed()).as("failure indicator").isTrue();
        }

        @Test
        @DisplayName("a failed outcome given no text takes the frozen literal, so the operator-facing text can never be empty on a failure")
        void aFailedOutcomeGivenNoTextTakesTheFrozenLiteral() {
            assertThat(new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT, 0, true,
                    EMPTY_TEXT).failureMessage()).as("failure text").isEqualTo(ORACLE_FAILURE_TEXT);
            assertThat(new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT, 0, true, null)
                    .failureMessage()).as("failure text").isEqualTo(ORACLE_FAILURE_TEXT);
        }

        @Test
        @DisplayName("a successful outcome discards any text it was given, so a success can never be reported carrying a failure message")
        void aSuccessfulOutcomeDiscardsAnyTextItWasGiven() {
            final JobSubmissionService.SubmissionResult result =
                    new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT, ORACLE_CARD_COUNT,
                            false, ORACLE_FAILURE_TEXT);

            assertThat(result.failureMessage()).as("failure text").isEmpty();
        }

        @ParameterizedTest(name = "requested [{0}]")
        @ValueSource(ints = {-1, -17})
        @DisplayName("a negative requested count is refused, because a submission cannot ask for fewer than no cards")
        void aNegativeRequestedCountIsRefused(final int requested) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService.SubmissionResult(requested, 0, false,
                            EMPTY_TEXT))
                    .withMessageContaining("cardsRequested");
        }

        @Test
        @DisplayName("a negative published count is refused, because a card cannot be un-published")
        void aNegativePublishedCountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT,
                            -1, false, EMPTY_TEXT))
                    .withMessageContaining("cardsPublished");
        }

        @Test
        @DisplayName("publishing more cards than were requested is refused, because the emitting loop cannot outrun its own stream")
        void publishingMoreThanWasRequestedIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService.SubmissionResult(FIRST_CARD_ORDINAL,
                            ORACLE_CARD_COUNT, false, EMPTY_TEXT))
                    .withMessageContaining("must not");
        }
    }

    @Nested
    @DisplayName("the caller's card stream is snapshotted and never mutated, and an unusable stream is refused before anything is published")
    class StreamOwnershipContract {

        @Test
        @DisplayName("the caller's own list is left byte-identical by a submission, so nothing the caller holds is rewritten")
        void theCallersOwnListIsLeftUnchanged() {
            queueAcceptsEveryCard();
            final List<String> callerOwned = new ArrayList<>(oracleJobImage(START_DATE, END_DATE));

            JobSubmissionServiceTest.this.service
                    .submitCanonicalJobImage(CALLER_SUBMISSION_ID, callerOwned);

            assertThat(callerOwned).as("the caller's own list after the submission")
                    .containsExactlyElementsOf(oracleJobImage(START_DATE, END_DATE));
        }

        @Test
        @DisplayName("mutating the caller's list after the call cannot change what was published, because the stream is copied on entry")
        void mutatingTheCallersListAfterwardsCannotChangeWhatWasPublished() {
            queueAcceptsEveryCard();
            final List<String> callerOwned = new ArrayList<>(oracleJobImage(START_DATE, END_DATE));

            JobSubmissionServiceTest.this.service
                    .submitCanonicalJobImage(CALLER_SUBMISSION_ID, callerOwned);
            callerOwned.clear();

            assertThat(payloadsOf(capturedPublishes(ORACLE_CARD_COUNT)))
                    .as("published bodies after the caller emptied its list")
                    .containsExactlyElementsOf(oracleJobImage(START_DATE, END_DATE));
        }

        @Test
        @DisplayName("an unmodifiable stream is accepted, so the service never needs to write into the list it was given")
        void anUnmodifiableStreamIsAccepted() {
            queueAcceptsEveryCard();
            final List<String> unmodifiable = oracleJobImage(START_DATE, END_DATE);

            assertThatNoException().isThrownBy(() -> JobSubmissionServiceTest.this.service
                    .submitCanonicalJobImage(CALLER_SUBMISSION_ID, unmodifiable));

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("the stream that was published is unmodifiable")
                    .isThrownBy(() -> unmodifiable.set(0, ORACLE_COMMENT_CARD));
            assertThat(capturedPublishes(ORACLE_CARD_COUNT)).as("publish attempts")
                    .hasSize(ORACLE_CARD_COUNT);
        }

        @Test
        @DisplayName("an empty stream is refused before anything is published, because a submission of no cards would trigger nothing at the batch tier")
        void anEmptyStreamIsRefusedBeforeAnythingIsPublished() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitJobStream(CALLER_SUBMISSION_ID, List.of()))
                    .withMessageContaining("at least one");

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("an absent stream is refused deterministically and names the parameter")
        void anAbsentStreamIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitJobStream(CALLER_SUBMISSION_ID, null))
                    .withMessageContaining("cardImages");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitCanonicalJobImage(CALLER_SUBMISSION_ID, null))
                    .withMessageContaining("cardImages");

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("a stream carrying an absent card is refused before anything is published, so a partial submission can only ever be the result of a refusal")
        void aStreamCarryingAnAbsentCardIsRefused() {
            final List<String> withAnAbsentCard = new ArrayList<>(ORACLE_CARD_COUNT);
            withAnAbsentCard.add(ORACLE_JOB_CARD);
            withAnAbsentCard.add(null);
            withAnAbsentCard.add(ORACLE_SENTINEL_CARD);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitJobStream(CALLER_SUBMISSION_ID, withAnAbsentCard));

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("a stream carrying a card of the wrong width is refused before anything is published, so no partial stream is left behind by a malformed later card")
        void aStreamCarryingAMalformedCardIsRefused() {
            final List<String> withAMalformedCard = List.of(ORACLE_JOB_CARD,
                    ORACLE_SENTINEL_CONTENT, ORACLE_SENTINEL_CARD);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitJobStream(CALLER_SUBMISSION_ID, withAMalformedCard))
                    .withMessageContaining(String.valueOf(ORACLE_CARD_WIDTH));

            assertNothingWasPublished();
        }
    }

    @Nested
    @DisplayName("absent reporting dates are refused deterministically, before any card exists")
    class AbsentInputContract {

        @Test
        @DisplayName("an absent start date is refused by name and publishes nothing, so no unattributable failure can surface later in the submission")
        void anAbsentStartDateIsRefusedByName() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(null, END_DATE))
                    .withMessageContaining("startDate");

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("an absent end date is refused by name and publishes nothing")
        void anAbsentEndDateIsRefusedByName() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(START_DATE, null))
                    .withMessageContaining("endDate");

            assertNothingWasPublished();
        }

        @Test
        @DisplayName("the caller-supplied-identity entry point refuses an absent date by naming the slot it belongs to, and publishes nothing")
        void theIdentityBearingEntryPointRefusesAnAbsentDateBySlotName() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(CALLER_SUBMISSION_ID, null, END_DATE))
                    .withMessageContaining(JclCardImageBuilder.SLOT_PARM_START_DATE);
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(CALLER_SUBMISSION_ID, START_DATE, null))
                    .withMessageContaining(JclCardImageBuilder.SLOT_PARM_END_DATE);

            assertNothingWasPublished();
        }
    }

    @Nested
    @DisplayName("the derived codes are total: every shape a failure's type or cause chain can take yields one bounded token")
    class DerivedCodeTotality {

        @Test
        @DisplayName("a cause chain exactly as deep as the bound is rendered whole, with no truncation marker, so the marker means what it says")
        void aChainExactlyAtTheBoundIsRenderedWhole() {
            // The truncation marker is only informative if it appears when and only when the chain
            // was actually cut. A chain of exactly the bounded depth is the boundary case where an
            // off-by-one would append it spuriously.
            queueRefusesFromCard(FIRST_CARD_ORDINAL,
                    () -> chainOfDepth(ORACLE_MAX_FAILURE_CHAIN_DEPTH));

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String chain = codeFollowing("failureChain=",
                    onlyErrorDiagnostic().getFormattedMessage());
            assertThat(chain).as("the failure chain").matches(ORACLE_FAILURE_CHAIN)
                    .doesNotContain("...");
            assertThat(chain.split("<-", -1)).as("a chain at the bound names every type")
                    .hasSize(ORACLE_MAX_FAILURE_CHAIN_DEPTH);
        }

        @Test
        @DisplayName("a cause chain one deeper than the bound is cut and marked, so a deep chain cannot lengthen a log record without limit")
        void aChainOneDeeperThanTheBoundIsCutAndMarked() {
            queueRefusesFromCard(FIRST_CARD_ORDINAL,
                    () -> chainOfDepth(ORACLE_MAX_FAILURE_CHAIN_DEPTH + 1));

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String chain = codeFollowing("failureChain=",
                    onlyErrorDiagnostic().getFormattedMessage());
            assertThat(chain).as("the failure chain").matches(ORACLE_FAILURE_CHAIN)
                    .endsWith("<-...");
            assertThat(chain.split("<-", -1)).as("the chain is cut at its depth bound")
                    .hasSize(ORACLE_MAX_FAILURE_CHAIN_DEPTH + 1);
        }

        @Test
        @DisplayName("a failure whose getCause returns itself terminates, because a type may override that method and a walk that trusts it would not return")
        void aFailureWhoseCauseIsItselfTerminates() {
            // Throwable.initCause refuses self-causation, but getCause is overridable and a client's
            // exception type may return this from it - deliberately or through a defect. The walk has
            // to be total against that, not merely against the chains this test imagined.
            queueRefusesFromCard(FIRST_CARD_ORDINAL, SelfCausingRefusal::new);

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String recorded = onlyErrorDiagnostic().getFormattedMessage();
            assertThat(codeFollowing("response=", recorded)).as("the response code")
                    .isEqualTo(SelfCausingRefusal.class.getSimpleName());
            // The reason code qualifies the response code by naming what lies beneath it. A
            // self-reference is a defect in the failure type rather than a real cause, so reporting
            // the same type twice would describe a two-element chain where there is one element.
            assertThat(codeFollowing("reason=", recorded))
                    .as("a self-causing failure has nothing beneath it, so the reason code is empty")
                    .isEmpty();
            assertThat(codeFollowing("failureChain=", recorded)).as("the failure chain")
                    .isEqualTo(SelfCausingRefusal.class.getSimpleName());
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, ORACLE_MAX_FAILURE_CHAIN_DEPTH})
        @DisplayName("the reason code is empty exactly when the chain names one type, which is the invariant that lets one field be read against the other")
        void theReasonCodeIsEmptyExactlyWhenTheChainNamesOneType(final int depth) {
            // Two fields derived by two methods can drift apart, and a reader who compares them would
            // then be misled. Asserting the relation across depths pins the relation rather than the
            // two values independently, so a change to either method that breaks the correspondence
            // is caught here.
            queueRefusesFromCard(FIRST_CARD_ORDINAL, () -> chainOfDepth(depth));

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String recorded = onlyErrorDiagnostic().getFormattedMessage();
            final boolean reasonIsEmpty = codeFollowing("reason=", recorded).isEmpty();
            final boolean chainNamesOneType =
                    codeFollowing("failureChain=", recorded).split("<-", -1).length == 1;

            assertThat(reasonIsEmpty).as("an empty reason code must mean a single-type chain")
                    .isEqualTo(chainNamesOneType);
            assertThat(chainNamesOneType).as("a chain of depth %d names one type only when depth is 1",
                    depth).isEqualTo(depth == 1);
        }

        @Test
        @DisplayName("a type name longer than the bound is cut to the bound, because the legacy codes were fixed-width display fields")
        void anOverlongTypeNameIsCutToTheBound() {
            queueRefusesFromCard(FIRST_CARD_ORDINAL,
                    ARefusalWhoseTypeNameIsDeliberatelyLongerThanTheSixtyFourCharacterDiagnosticCodeBound::new);

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String declared =
                    ARefusalWhoseTypeNameIsDeliberatelyLongerThanTheSixtyFourCharacterDiagnosticCodeBound
                            .class.getSimpleName();
            assertThat(declared).as("the fixture type must actually exceed the bound")
                    .hasSizeGreaterThan(ORACLE_MAX_DIAGNOSTIC_CODE_LENGTH);

            final String responseCode =
                    codeFollowing("response=", onlyErrorDiagnostic().getFormattedMessage());
            assertThat(responseCode).as("the response code")
                    .hasSize(ORACLE_MAX_DIAGNOSTIC_CODE_LENGTH)
                    .matches(ORACLE_DIAGNOSTIC_CODE)
                    .isEqualTo(declared.substring(0, ORACLE_MAX_DIAGNOSTIC_CODE_LENGTH));
        }

        @Test
        @DisplayName("a type name carrying a character outside ASCII is folded to the substitute, which is why the admitted set is narrower than what a Java identifier allows")
        void aNonAsciiTypeNameIsFoldedToTheSubstitute() {
            // Character.isJavaIdentifierPart admits non-ASCII letters and several Unicode formatting
            // and ignorable code points, none of which belong in a value written into a log record.
            // A type may legally be named with them, so the narrower ASCII rule is exercised rather
            // than asserted.
            queueRefusesFromCard(FIRST_CARD_ORDINAL, RefusalNamedInCaf\u00e9Style::new);

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String declared = RefusalNamedInCaf\u00e9Style.class.getSimpleName();
            assertThat(declared).as("the fixture type must actually carry a non-ASCII character")
                    .isNotEqualTo(declared.replaceAll("[^\\x00-\\x7F]", "_"));

            final String responseCode =
                    codeFollowing("response=", onlyErrorDiagnostic().getFormattedMessage());
            assertThat(responseCode).as("the response code")
                    .matches(ORACLE_DIAGNOSTIC_CODE)
                    .hasSameSizeAs(declared)
                    .isEqualTo(declared.replaceAll("[^A-Za-z0-9$_]", "_"))
                    .isNotEqualTo(declared);
        }

        @Test
        @DisplayName("a self-causing failure found part-way down a chain stops the walk there, so the walk is total wherever the self-reference sits rather than only at the top")
        void aSelfCausingFailureFoundPartWayDownStopsTheWalkThere() {
            // The self-reference at the top of a chain takes the no-cause path, which is a different
            // branch from the one that stops a walk already in progress. A client that wraps a
            // self-causing failure produces exactly this shape, and it is the case in which a walk
            // that only guards its first hop would not return.
            queueRefusesFromCard(FIRST_CARD_ORDINAL, () -> new IllegalStateException(
                    HOSTILE_CAUSE_TEXT, new SelfCausingRefusal()));

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String recorded = onlyErrorDiagnostic().getFormattedMessage();
            assertThat(codeFollowing("response=", recorded)).as("the response code")
                    .isEqualTo(IllegalStateException.class.getSimpleName());
            assertThat(codeFollowing("reason=", recorded))
                    .as("the walk stops at the self-causing element and names it")
                    .isEqualTo(SelfCausingRefusal.class.getSimpleName());
            assertThat(codeFollowing("failureChain=", recorded)).as("the failure chain")
                    .isEqualTo(IllegalStateException.class.getSimpleName()
                            + "<-" + SelfCausingRefusal.class.getSimpleName())
                    .doesNotContain("...");
        }

        @Test
        @DisplayName("a chain that reaches the depth bound and genuinely ends there carries no truncation marker, so the marker distinguishes a cut chain from a complete one")
        void aChainEndingExactlyAtTheBoundCarriesNoMarker() {
            // The marker is only informative if its absence means something. A chain whose last
            // element self-references has genuinely ended, even though the walk stopped at the bound,
            // so marking it as cut would assert something untrue about what lies beneath.
            queueRefusesFromCard(FIRST_CARD_ORDINAL,
                    () -> chainEndingInASelfReference(ORACLE_MAX_FAILURE_CHAIN_DEPTH));

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String chain = codeFollowing("failureChain=",
                    onlyErrorDiagnostic().getFormattedMessage());
            assertThat(chain).as("the failure chain").matches(ORACLE_FAILURE_CHAIN)
                    .doesNotContain("...")
                    .endsWith("<-" + SelfCausingRefusal.class.getSimpleName());
            assertThat(chain.split("<-", -1)).as("every type up to the bound is named")
                    .hasSize(ORACLE_MAX_FAILURE_CHAIN_DEPTH);
        }

        @Test
        @DisplayName("a type name made only of admissible characters passes through unchanged, so sanitisation costs a legitimate generated name nothing")
        void anAdmissibleTypeNamePassesThroughUnchanged() {
            // The rule has to be judged in both directions. Digits, the underscore and the dollar sign
            // all occur in names the platform itself generates, and a rule that mangled them would
            // damage exactly the codes an operator most needs to search for.
            queueRefusesFromCard(FIRST_CARD_ORDINAL, Refusal_2$WithDigitsAndConnectors::new);

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final String declared = Refusal_2$WithDigitsAndConnectors.class.getSimpleName();
            assertThat(declared).as("the fixture name must exercise every admissible class of character")
                    .contains("_").contains("$").containsPattern("[0-9]")
                    .containsPattern("[A-Z]").containsPattern("[a-z]");

            assertThat(codeFollowing("response=", onlyErrorDiagnostic().getFormattedMessage()))
                    .as("an admissible name is not rewritten")
                    .isEqualTo(declared);
        }

        /**
         * Builds a cause chain of the requested length, outermost first.
         *
         * @param depth how many throwables the chain holds, at least one
         * @return the outermost throwable of the chain
         */
        private RuntimeException chainOfDepth(final int depth) {
            RuntimeException chain = new IllegalStateException(HOSTILE_CAUSE_TEXT);
            for (int level = 1; level < depth; level++) {
                chain = new IllegalStateException(HOSTILE_CAUSE_TEXT, chain);
            }
            return chain;
        }

        /**
         * Builds a cause chain of the requested length whose deepest element causes itself.
         *
         * <p>Such a chain has genuinely ended at its deepest element even though a bounded walk stops
         * there, which is what separates "the chain ended" from "the walk gave up".
         *
         * @param depth how many throwables the chain holds, at least one
         * @return the outermost throwable of the chain
         */
        private RuntimeException chainEndingInASelfReference(final int depth) {
            RuntimeException chain = new SelfCausingRefusal();
            for (int level = 1; level < depth; level++) {
                chain = new IllegalStateException(HOSTILE_CAUSE_TEXT, chain);
            }
            return chain;
        }
    }

    /**
     * A refusal whose {@code getCause} returns the refusal itself.
     *
     * <p>{@link Throwable#initCause(Throwable)} refuses self-causation, so this shape is only
     * reachable by overriding the accessor - which a client's exception type may do, deliberately or
     * through a defect. It exists so that the service's cause walk is proved total against it rather
     * than assumed safe.
     */
    private static final class SelfCausingRefusal extends RuntimeException {

        /** Serialisation identity, required of every serialisable type in this module. */
        private static final long serialVersionUID = 1L;

        /** Creates the refusal with the hostile description used throughout this class. */
        SelfCausingRefusal() {
            super(HOSTILE_CAUSE_TEXT);
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    /**
     * A refusal whose type name is longer than the bound a derived diagnostic code is held to.
     *
     * <p>The name is the fixture: it has to exceed sixty-four characters for the truncation branch to
     * be reachable, and a type name is the one input to that branch this module does not author.
     */
    private static final class
            ARefusalWhoseTypeNameIsDeliberatelyLongerThanTheSixtyFourCharacterDiagnosticCodeBound
            extends RuntimeException {

        /** Serialisation identity, required of every serialisable type in this module. */
        private static final long serialVersionUID = 1L;

        /** Creates the refusal with the hostile description used throughout this class. */
        ARefusalWhoseTypeNameIsDeliberatelyLongerThanTheSixtyFourCharacterDiagnosticCodeBound() {
            super(HOSTILE_CAUSE_TEXT);
        }
    }

    /**
     * A refusal whose type name carries a character that is a legal Java identifier part but is not
     * ASCII.
     *
     * <p>The name is written with a Unicode escape so that this source file stays ASCII-only while the
     * compiled identifier does not. It exists so that the narrower ASCII rule the service applies is
     * exercised against a name that {@link Character#isJavaIdentifierPart(char)} would have admitted.
     */
    private static final class RefusalNamedInCaf\u00e9Style extends RuntimeException {

        /** Serialisation identity, required of every serialisable type in this module. */
        private static final long serialVersionUID = 1L;

        /** Creates the refusal with the hostile description used throughout this class. */
        RefusalNamedInCaf\u00e9Style() {
            super(HOSTILE_CAUSE_TEXT);
        }
    }

    /**
     * A refusal whose type name exercises every character class a derived code admits.
     *
     * <p>The name carries an upper-case letter, a lower-case letter, a digit, an underscore and a
     * dollar sign - all of which the platform itself puts into generated type names. It exists so that
     * the sanitising rule is judged in both directions: a legitimate name must pass through unchanged,
     * not merely a hostile one be rewritten.
     */
    private static final class Refusal_2$WithDigitsAndConnectors extends RuntimeException {

        /** Serialisation identity, required of every serialisable type in this module. */
        private static final long serialVersionUID = 1L;

        /** Creates the refusal with the hostile description used throughout this class. */
        Refusal_2$WithDigitsAndConnectors() {
            super(HOSTILE_CAUSE_TEXT);
        }
    }

    /**
     * Records what the service asked the queue to send for one card. The service configures its message
     * through the fluent options object the template hands it, so a recording implementation of that
     * interface is the only way to observe the body, the message group and the deduplication identifier.
     * The two settings the service must never make - a delivery delay and a message header, neither of
     * which has a legacy antecedent - are counted rather than stored, so their absence is asserted
     * positively.
     */
    private static final class RecordedPublish implements SqsSendOptions<String> {

        private String queue;

        private String payload;

        private String messageGroupId;

        private String messageDeduplicationId;

        private int headerSettings;

        private int headerMapSettings;

        private int delaySettings;

        @Override
        public SqsSendOptions<String> queue(final String destination) {
            this.queue = destination;
            return this;
        }

        @Override
        public SqsSendOptions<String> payload(final String cardImage) {
            this.payload = cardImage;
            return this;
        }

        @Override
        public SqsSendOptions<String> header(final String name, final Object value) {
            this.headerSettings++;
            return this;
        }

        @Override
        public SqsSendOptions<String> headers(final Map<String, Object> values) {
            this.headerMapSettings++;
            return this;
        }

        @Override
        public SqsSendOptions<String> delaySeconds(final Integer delay) {
            this.delaySettings++;
            return this;
        }

        @Override
        public SqsSendOptions<String> messageGroupId(final String groupId) {
            this.messageGroupId = groupId;
            return this;
        }

        @Override
        public SqsSendOptions<String> messageDeduplicationId(final String deduplicationId) {
            this.messageDeduplicationId = deduplicationId;
            return this;
        }
    }
}
