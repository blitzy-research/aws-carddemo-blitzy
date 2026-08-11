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
package com.carddemo.batch.step;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockingDetails;

/**
 * Verifies {@link AbstractCobolStep}, the Template Method that absorbs the open, read-loop,
 * status-normalisation, close, diagnostic and abend skeleton every batch program of the CardDemo
 * mainframe estate repeats.
 *
 * <h2>The legacy authority</h2>
 *
 * <p>The skeleton under test is not an invention. It is the shape of {@code app/cbl/CBACT01C.cbl},
 * whose procedure division announces its own start, performs an open paragraph, loops reading until an
 * end-of-file flag flips, processes each delivered record, performs a close paragraph and announces its
 * own end. The same shape, with the same paragraph numbering, recurs in {@code CBACT02C},
 * {@code CBACT03C}, {@code CBCUS01C}, {@code CBTRN01C}, {@code CBTRN02C}, {@code CBTRN03C} and
 * {@code CBACT04C}, and its individual guarded operations recur in {@code CBSTM03A.CBL} and
 * {@code CBSTM03B.CBL} even though those two are a dispatcher and its data-access helper rather than a
 * read loop. Ten batch programs in total, one skeleton.
 *
 * <p><strong>The release stamp may never become a per-member assertion.</strong> It is not universal
 * across the estate: seventy-eight legacy members carry it, three carry later stamps, all seventeen
 * screen definitions differ from it and twenty-five members carry none at all, so such an assertion
 * would fail for a third of the estate while appearing to prove provenance. Nothing here reads the
 * legacy tree at runtime.
 *
 * <h2>What is asserted, and why it is asserted in two levels</h2>
 *
 * <p>The legacy programs never branch on the raw two-byte file status. They normalise it into a coarser
 * signed integer first and branch on that, so this file proves the two levels separately and never
 * collapses them.
 *
 * <p><strong>Level one, raw status to coarse result.</strong> On the read path
 * ({@code app/cbl/CBACT01C.cbl} lines 93 to 103) success moves zero, the at-end code moves sixteen and
 * everything else moves twelve. On the open, close and write paths the normalisation has only two arms:
 * success moves zero and <em>everything else</em> moves twelve, with no at-end arm anywhere
 * ({@code app/cbl/CBACT01C.cbl} lines 136 to 140 and 154 to 158, and the write paragraphs of
 * {@code app/cbl/CBTRN02C.cbl}). The consequence is the single most easily mistranslated fact in the
 * batch tier: the at-end code is end of file <strong>only on a read</strong>. On an open, a close or a
 * write it is a terminal failure that abends, and a translation that treated it as an empty file would
 * swallow a genuine error silently. Both readings are asserted here, side by side.
 *
 * <p><strong>Level two, coarse result to control branch.</strong> Zero continues, sixteen sets the
 * loop's termination flag and stops cleanly, and twelve emits the failing operation's error text with
 * the raw two-byte status, then emits the abend announcement, and only then ends the run
 * ({@code app/cbl/CBACT01C.cbl} lines 104 to 115, its status-display paragraph at lines 176 to 189 and
 * its abend paragraph at lines 169 to 173). The coarse item is referenced on 223 lines of
 * {@code app/cbl}, so it, and not the raw code, is what the estate actually tests.
 *
 * <h2>Decision-log candidates raised by this file</h2>
 *
 * <p>These notes exist so that the audit trail is complete. This file raises them and does not act on
 * them: {@code docs/traceability-matrix.md}, {@code docs/decision-log.md} and
 * {@code docs/gate-evidence.md} are owned elsewhere and are neither created nor edited here.
 *
 * <ol>
 * <li><strong>The terminal coarse value has no condition name.</strong> The coarse item declares a
 * level-88 name for zero and another for sixteen ({@code app/cbl/CBACT01C.cbl} lines 62 and 63) but
 * <em>none</em> for twelve, which appears throughout the estate as a bare literal. The Java model
 * therefore names all three outcomes while the legacy named only two, and the extra name is a
 * readability gain that changes no behaviour.</li>
 * <li><strong>Two documented-but-unexercised status codes.</strong> An earlier section of the technical
 * specification attributes duplicate-key and file-not-found handling to two status codes that are
 * compared nowhere in the estate. The status vocabulary appearing anywhere in the source is
 * {@code 00}, {@code 01}, {@code 02}, {@code 04}, {@code 05}, {@code 10}, {@code 12}, {@code 23} and
 * {@code 31}, and in status-test context only {@code 00}, {@code 10} and {@code 23} are ever compared.
 * {@link FileStatus} may carry the two extra codes as documented values, but no assertion in this file
 * depends on them and none may be added, because a test that depends on them would assert a behaviour
 * the legacy never exhibited.</li>
 * <li><strong>A source defect that must not be propagated.</strong> The daily-rejects close paragraph
 * of {@code app/cbl/CBTRN02C.cbl} correctly tests its own file's status to decide that the close
 * failed, and then reports the cross-reference file's status instead (line 649). Its three sibling
 * close paragraphs in the same program each report their own status, so the defect is confined to that
 * one line. The template cannot reproduce it, because the status travels from the failing operation to
 * the diagnostic as a parameter rather than through a shared display field, and a test below proves the
 * reported status and resource always belong to the operation that actually failed.</li>
 * <li><strong>The batch timestamp is not the online timestamp.</strong> The batch form separates the
 * day from the hour with a hyphen, uses dots between the time components, carries two digits of
 * hundredths and ends in four literal zeros. The online form uses a space in that position, colons
 * between the time components and six fractional digits. A negative test below rules the online form
 * out explicitly, because the two forms are the same width and a substitution would be invisible to any
 * assertion that only checked the length.</li>
 * <li><strong>An inspection note on shape, not behaviour.</strong> The coarse-outcome type and the
 * batch-timestamp helper are members of {@link AbstractCobolStep} and are reached below only through
 * that nesting. A top-level {@code FileStatusNormalizer}, {@code IoOutcome} or
 * {@code Db2TimestampFormatter} would be a duplicate of state the template already owns and must not
 * exist. Their absence is an inspection matter and is recorded here rather than probed at runtime;
 * what this file asserts is behaviour.</li>
 * </ol>
 *
 * <h2>How the assertions are built</h2>
 *
 * <p>Every expected value below is an independent oracle: the coarse values, the field widths, the
 * timestamp image and the status vocabulary are declared here as plain constants or composed with plain
 * string operations, and no expectation is ever produced by asking the class under test, a formatter, a
 * template or a record mapper what it would produce. The one deliberate exception is the abend code,
 * which the prompt for this file requires be read from the constant that publishes it rather than
 * restated as a literal, so that a change to the published code cannot leave a stale literal behind.
 * Widths are asserted on encoded bytes rather than on character counts, and nothing is trimmed before
 * a fixed-width comparison. Time is supplied by a fixed clock, formatting is given an explicit locale,
 * and this file touches no database, no queue, no object store, no container and no application
 * context: it is a unit test of a template, and the integration obligations belong to the tests that
 * own real infrastructure.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractCobolStep: the batch lifecycle template, its two-level status model and its "
        + "ordered abend path")
final class AbstractCobolStepTest {

    // Independent oracles. Every figure below was read from the legacy source and is restated here
    // as a constant so that no expectation is ever produced by the class under test.

    /** The coarse result the legacy moves on success, and the value behind its first condition name. */
    private static final int ORACLE_COARSE_SUCCESS = 0;

    /**
     * The sentinel the legacy arms before an open, a write and a close, meaning "operation attempted,
     * outcome not yet normalised". It has no condition name and is never a normalised outcome.
     */
    private static final int ORACLE_COARSE_SENTINEL = 8;

    /** The terminal coarse result. The legacy declares no condition name for it. */
    private static final int ORACLE_COARSE_TERMINAL = 12;

    /** The coarse result meaning end of file, and the value behind the legacy's second condition name. */
    private static final int ORACLE_COARSE_END_OF_FILE = 16;

    /** Width of the abend code component of the legacy abend work area. */
    private static final int ORACLE_ABEND_CODE_WIDTH = 4;

    /** Width of the abend culprit component, which also bounds a legacy program name. */
    private static final int ORACLE_ABEND_CULPRIT_WIDTH = 8;

    /** Width of the abend reason component. */
    private static final int ORACLE_ABEND_REASON_WIDTH = 50;

    /** Width of the abend message component. */
    private static final int ORACLE_ABEND_MESSAGE_WIDTH = 72;

    /** Total width of the abend work area: the four components summed. */
    private static final int ORACLE_ABEND_CONTEXT_WIDTH = 134;

    /** Width of the batch timestamp image. */
    private static final int ORACLE_TIMESTAMP_WIDTH = 26;

    /** The literal four characters the populating paragraph moves into the timestamp's trailing field. */
    private static final String ORACLE_TIMESTAMP_TAIL = "0000";

    /** Width of the timestamp's trailing field, stated as a width so no assertion counts characters. */
    private static final int ORACLE_TIMESTAMP_TAIL_WIDTH = 4;

    /**
     * The character the populating paragraph moves into the timestamp's first three separators, held as
     * an encoded byte because a fixed-width position is a byte and never a code point.
     */
    private static final byte ORACLE_DATE_SEPARATOR = (byte) '-';

    /** The character the populating paragraph moves into the timestamp's last three separators. */
    private static final byte ORACLE_TIME_SEPARATOR = (byte) '.';

    /** Lowest digit byte, the lower bound of every numeric position in the timestamp image. */
    private static final byte ORACLE_ZERO_DIGIT = (byte) '0';

    /** Highest digit byte, the upper bound of every numeric position in the timestamp image. */
    private static final byte ORACLE_NINE_DIGIT = (byte) '9';

    /**
     * The byte the online timestamp form carries between the date and the time. The batch form carries
     * a separator instead, and proving the two apart is the point of the negative assertion.
     */
    private static final byte ORACLE_ONLINE_DATE_AND_TIME_DIVIDER = (byte) ' ';

    /** The separator the online timestamp form uses between time components; the batch form uses none. */
    private static final String ORACLE_ONLINE_TIME_SEPARATOR_TEXT = ":";

    /** The divider the online timestamp form uses between date and time, as text. */
    private static final String ORACLE_ONLINE_DATE_AND_TIME_DIVIDER_TEXT = " ";

    /** Nanoseconds the pinned instant carries, rendering as the hundredths the image ends on. */
    private static final int PINNED_NANOSECONDS = 870_000_000;

    /**
     * Nanoseconds that render to the same hundredths as the pinned instant only if sub-hundredth
     * precision is truncated. Rounding to nearest would carry the hundredths one higher.
     */
    private static final int TRUNCATED_NANOSECONDS = 879_000_000;

    /** The smallest year that no longer fits the timestamp's four-byte year field. */
    private static final int ORACLE_SMALLEST_UNREPRESENTABLE_YEAR = 10_000;

    /** The largest year the four-byte year field holds: one below the smallest five-digit value. */
    private static final int ORACLE_LARGEST_REPRESENTABLE_YEAR =
            ORACLE_SMALLEST_UNREPRESENTABLE_YEAR - 1;

    /** The smallest year the four-byte year field holds. */
    private static final int ORACLE_SMALLEST_REPRESENTABLE_YEAR = 0;

    /** A year below the field's floor, which must be refused rather than rendered narrower. */
    private static final int ORACLE_YEAR_BELOW_THE_FLOOR = -1;

    /** Width of the raw file-status field, which the legacy splits into two separately named bytes. */
    private static final int ORACLE_RAW_STATUS_WIDTH = 2;

    /** The name given to the step built around the tasklet, so its propagation can be observed. */
    private static final String ORACLE_STEP_NAME = "acctfileStep";

    /** Gerund the open diagnostics use. */
    private static final String ORACLE_GERUND_OPEN = "OPENING";

    /** Gerund the read diagnostics use. */
    private static final String ORACLE_GERUND_READ = "READING";

    /** Gerund the write diagnostics use. */
    private static final String ORACLE_GERUND_WRITE = "WRITING TO";

    /** Gerund the close diagnostics use. */
    private static final String ORACLE_GERUND_CLOSE = "CLOSING";

    /** Text the legacy abend paragraph displays before it ends the run. */
    private static final String ORACLE_ABEND_ANNOUNCEMENT = "ABENDING PROGRAM";

    /** Text the legacy status-display paragraph emits ahead of the rendered status, in both its arms. */
    private static final String ORACLE_STATUS_DISPLAY = "FILE STATUS IS:";

    /** Raw success status, the only code the estate treats as success. */
    private static final String STATUS_SUCCESS = "00";

    /** Raw at-end status, end of file on a read and terminal on every other operation. */
    private static final String STATUS_END_OF_FILE = "10";

    /** Raw record-not-found status, numeric and terminal to this template. */
    private static final String STATUS_RECORD_NOT_FOUND = "23";

    /** Raw permanent-error status, numeric and terminal. */
    private static final String STATUS_PERMANENT_ERROR = "31";

    /** A code outside the estate's vocabulary whose first byte is nine, exercising the catch-all arm. */
    private static final String STATUS_UNLISTED_LEADING_NINE = "99";

    /** A non-numeric code outside the estate's vocabulary, exercising the other rendering arm. */
    private static final String STATUS_UNLISTED_NON_NUMERIC = "AB";

    /** Metric the template records one sample on per lifecycle. */
    private static final String ORACLE_METRIC_NAME = "carddemo.batch.cobol.step";

    /** Tag key naming the legacy program the step stands in for. */
    private static final String ORACLE_TAG_STEP = "step";

    /** Tag key naming how the lifecycle ended. */
    private static final String ORACLE_TAG_OUTCOME = "outcome";

    /** Tag value for a lifecycle that reached its end. */
    private static final String ORACLE_OUTCOME_COMPLETED = "COMPLETED";

    /** Tag value for a lifecycle that ended in an abend. */
    private static final String ORACLE_OUTCOME_ABENDED = "ABENDED";

    /** Diagnostics the terminal path emits before the failure reaches the caller. */
    private static final int ORACLE_TERMINAL_DIAGNOSTIC_COUNT = 2;

    /** The legacy program this step stands in for; also the abend culprit, at exactly the culprit width. */
    private static final String PROGRAM_NAME = "CBACT01C";

    /** The file the scripted lifecycle opens, reads and closes. */
    private static final String RESOURCE_NAME = "ACCTFILE";

    /** A second file, so a diagnostic can be shown to name the file that actually failed. */
    private static final String OTHER_RESOURCE_NAME = "XREFFILE";

    /** A third file, standing in for the one whose close paragraph carries the documented defect. */
    private static final String REJECTS_RESOURCE_NAME = "DALYREJS";

    /** A pinned instant, so the timestamp image can be asserted byte for byte. */
    private static final Instant PINNED_INSTANT = Instant.parse("2022-07-06T14:23:41.87Z");

    /** The image the pinned instant must render to, composed here and not by the class under test. */
    private static final String PINNED_TIMESTAMP_IMAGE = "2022-07-06-14.23.41.87" + ORACLE_TIMESTAMP_TAIL;

    /**
     * A second fixed offset, used to prove the injected clock's zone is honoured and not quietly
     * replaced by the host's. A pure offset is used rather than a named region so the expectation cannot
     * move with a time-zone database update.
     */
    private static final ZoneOffset SECOND_OFFSET = ZoneOffset.ofHours(9);

    /**
     * The image the pinned instant must render to under {@link #SECOND_OFFSET}, computed by hand here:
     * the same instant, nine hours later on the wall clock, on the same calendar day.
     */
    private static final String PINNED_TIMESTAMP_IMAGE_AT_SECOND_OFFSET =
            "2022-07-06-23.23.41.87" + ORACLE_TIMESTAMP_TAIL;

    /** First record the scripted read delivers. */
    private static final String FIRST_RECORD = "FIRST RECORD IMAGE";

    /** Second record the scripted read delivers. */
    private static final String SECOND_RECORD = "SECOND RECORD IMAGE";

    /** Captures the diagnostics the template emits, in the order it emits them. */
    @Mock
    private Appender<ILoggingEvent> appender;

    /** Stands in for the step repository when a real step is built around the tasklet. */
    @Mock
    private JobRepository jobRepository;

    /** Stands in for the transaction manager when a real step is built around the tasklet. */
    @Mock
    private PlatformTransactionManager transactionManager;

    /** The template's own logger, which the capture is attached to. */
    private Logger templateLogger;

    /** The level the logger carried before the capture lowered it. */
    private Level restoreLevel;

    /** The registry the lifecycle timer registers with. */
    private MeterRegistry meterRegistry;

    /** A clock pinned to {@link #PINNED_INSTANT}, so no assertion depends on the wall clock. */
    private Clock fixedClock;

    @BeforeEach
    void attachDiagnosticCapture() {
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.templateLogger = context.getLogger(AbstractCobolStep.class);
        this.restoreLevel = this.templateLogger.getLevel();
        this.templateLogger.setLevel(Level.DEBUG);
        this.templateLogger.addAppender(this.appender);
        this.meterRegistry = new SimpleMeterRegistry();
        this.fixedClock = Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC);
    }

    @AfterEach
    void detachDiagnosticCapture() {
        this.templateLogger.detachAppender(this.appender);
        this.templateLogger.setLevel(this.restoreLevel);
    }

    /**
     * Builds a step whose scripted read delivers each supplied record in turn and then reports end of
     * file.
     *
     * <p>Takes a list rather than a variable-length argument list on purpose: a generic variable-length
     * parameter is a compilation failure under the module's warning settings, and an explicit list says
     * the same thing without one.</p>
     *
     * @param records the records the read should deliver, in order
     * @return the scripted step
     */
    private ScriptedStep stepDelivering(final List<String> records) {
        final ScriptedStep step = new ScriptedStep(PROGRAM_NAME, this.meterRegistry, this.fixedClock);
        for (final String record : records) {
            step.deliverRecord(record);
        }
        return step;
    }

    /**
     * Renders every diagnostic the template emitted, in order.
     *
     * @return the captured logging events
     */
    private List<ILoggingEvent> capturedEvents() {
        return mockingDetails(this.appender).getInvocations().stream()
                .map(invocation -> invocation.<ILoggingEvent>getArgument(0))
                .toList();
    }

    /**
     * Renders the formatted text of every diagnostic the template emitted, in order.
     *
     * @return the formatted messages
     */
    private List<String> diagnostics() {
        return capturedEvents().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Renders the formatted text of the diagnostics the template emitted at the error level.
     *
     * @return the formatted error messages, in order
     */
    private List<String> errorDiagnostics() {
        return capturedEvents().stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * Renders the formatted text of every warning the template emitted, in order.
     *
     * <p>Separate from {@link #errorDiagnostics()} because the template uses the two levels for
     * different purposes: an error announces a failure that ends the run, whereas a warning reports
     * a secondary problem that has been retained rather than allowed to displace the primary. A test
     * that pooled the two could not tell those apart.</p>
     *
     * @return the captured warning texts, in emission order
     */
    private List<String> warningDiagnostics() {
        return capturedEvents().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * Slices the abend work area at a component boundary without trimming anything.
     *
     * @param context the rendered work area
     * @param offset  the zero-based start of the component
     * @param width   the component's declared width
     * @return the component image, padded exactly as the work area holds it
     */
    private static String component(final String context, final int offset, final int width) {
        return context.substring(offset, offset + width);
    }

    /**
     * Counts the encoded bytes of a value, which is the only width that means anything for a
     * fixed-width field.
     *
     * @param value the value to measure
     * @return the encoded length
     */
    private static int encodedLength(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Asserts that a run of positions in the timestamp image holds digits and nothing else.
     *
     * @param image  the encoded image
     * @param offset the zero-based start of the run
     * @param width  how many positions the run covers
     */
    private static void assertDigits(final byte[] image, final int offset, final int width) {
        for (int index = offset; index < offset + width; index++) {
            assertThat(image[index])
                    .as("position %s", Integer.toString(index + 1))
                    .isBetween(ORACLE_ZERO_DIGIT, ORACLE_NINE_DIGIT);
        }
    }

    // The minimal concrete subclass the template is driven through. It implements the four hooks,
    // records what it was asked to do in plain fields and adds no logic of its own.

    /**
     * A concrete step that replays a scripted read sequence and records the lifecycle it was driven
     * through.
     *
     * <p>Each hook routes through the template's own guarded helper, exactly as a real step is
     * documented to, so the tests exercise the sentinel, the normalisation cascade and the ordered
     * diagnostic rather than a bypass of them. The constructor calls nothing overridable.</p>
     */
    private static final class ScriptedStep extends AbstractCobolStep<String> {

        /** The order in which the template invoked the hooks. */
        private final List<String> lifecycle = new ArrayList<>();

        /** Results the scripted read reports, in order; exhaustion is end of file. */
        private final Deque<IoResult<String>> scriptedReads = new ArrayDeque<>();

        /** Records the template handed to the processing hook. */
        private final List<String> processed = new ArrayList<>();

        /** Raw status the scripted open reports. */
        private String openStatus = STATUS_SUCCESS;

        /** Raw status the scripted close reports. */
        private String closeStatus = STATUS_SUCCESS;

        /** When set, the scripted open throws this instead of reporting a status. */
        private Exception openFailure;

        /** When set, the scripted read throws this instead of reporting a result. */
        private Exception readFailure;

        /**
         * When set, the scripted processing hook throws this after recording the record it was handed.
         *
         * <p>This is the only way to fail mid-stream rather than at open, and the distinction is
         * observable: the template increments its record counter before entering the processing hook,
         * so a primary failure raised here is reported against a non-zero count while one raised at
         * open is reported against zero. The record is recorded before the throw so a test can tell
         * "processing was never entered" apart from "processing was entered and then failed".</p>
         */
        private RuntimeException processFailure;

        /** How many times the failure-path handle release was entered. */
        private int releaseAttempts;

        /**
         * When set, the failure-path handle release throws this after recording its attempt.
         *
         * <p>This is the only way to reach the template's secondary-failure arm, where a release
         * that fails while a primary failure is already in flight must not displace the primary.
         * The attempt is recorded before the throw so a test can tell "release was never entered"
         * apart from "release was entered and then failed".</p>
         */
        private RuntimeException releaseFailure;

        ScriptedStep(final String programName, final MeterRegistry meterRegistry, final Clock clock) {
            super(programName, meterRegistry, clock);
        }

        /** Queues one successful read delivering the given record. */
        void deliverRecord(final String record) {
            this.scriptedReads.addLast(IoResult.of(STATUS_SUCCESS, record));
        }

        @Override
        protected void openResources() {
            this.lifecycle.add("open");
            openResource(RESOURCE_NAME, () -> {
                if (this.openFailure != null) {
                    throw this.openFailure;
                }
                return this.openStatus;
            });
        }

        @Override
        protected Optional<String> readNextRecord() {
            this.lifecycle.add("read");
            return readRecord(RESOURCE_NAME, () -> {
                if (this.readFailure != null) {
                    throw this.readFailure;
                }
                final IoResult<String> next = this.scriptedReads.pollFirst();
                return next == null ? IoResult.endOfFile() : next;
            });
        }

        @Override
        protected void processRecord(final String record) {
            this.lifecycle.add("process");
            this.processed.add(record);
            if (this.processFailure != null) {
                throw this.processFailure;
            }
        }

        @Override
        protected void closeResources() {
            this.lifecycle.add("close");
            closeResource(RESOURCE_NAME, () -> this.closeStatus);
        }

        @Override
        protected void releaseResources() {
            this.lifecycle.add("release");
            this.releaseAttempts++;
            if (this.releaseFailure != null) {
                throw this.releaseFailure;
            }
        }
    }

    // Level one of the status model: the raw two-byte code becomes a coarse normalised result. The
    // read path has three arms; every other path has two.

    @Nested
    @DisplayName("level one on the read path: the raw two-byte code becomes a coarse result")
    final class RawStatusToCoarseResultOnTheReadPath {

        @ParameterizedTest(name = "a read reporting the raw status {0} normalises to the coarse result {1}")
        @CsvSource({
            STATUS_SUCCESS + ", " + ORACLE_COARSE_SUCCESS,
            STATUS_END_OF_FILE + ", " + ORACLE_COARSE_END_OF_FILE,
            STATUS_RECORD_NOT_FOUND + ", " + ORACLE_COARSE_TERMINAL,
            STATUS_UNLISTED_LEADING_NINE + ", " + ORACLE_COARSE_TERMINAL,
        })
        @DisplayName("the read cascade has three arms: success, at end, and everything else")
        void theReadCascadeHasThreeArms(final String rawStatus, final int expectedCoarseResult) {
            final ScriptedStep step = stepDelivering(List.of());

            final AbstractCobolStep.IoOutcome outcome =
                    step.normaliseStatus(AbstractCobolStep.IoOperation.READ, rawStatus);

            assertThat(outcome.applResult()).isEqualTo(expectedCoarseResult);
        }

        @Test
        @DisplayName("the at-end code is end of file on a read, which is what terminates every read loop")
        void theAtEndCodeIsEndOfFileOnARead() {
            final ScriptedStep step = stepDelivering(List.of());

            final AbstractCobolStep.IoOutcome outcome =
                    step.normaliseStatus(AbstractCobolStep.IoOperation.READ, STATUS_END_OF_FILE);

            assertThat(outcome).isEqualTo(AbstractCobolStep.IoOutcome.END_OF_FILE);
            assertThat(outcome.applResult()).isEqualTo(ORACLE_COARSE_END_OF_FILE);
            assertThat(AbstractCobolStep.IoOperation.READ.endOfFileTerminatesNormally()).isTrue();
        }

        @Test
        @DisplayName("a code the estate never names is terminal rather than an exception")
        void anUnlistedCodeIsTerminalRatherThanAnException() {
            final ScriptedStep step = stepDelivering(List.of());

            assertThat(step.normaliseStatus(AbstractCobolStep.IoOperation.READ,
                    STATUS_UNLISTED_NON_NUMERIC))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
            assertThat(step.normaliseStatus(AbstractCobolStep.IoOperation.READ,
                    STATUS_UNLISTED_LEADING_NINE).applResult())
                    .isEqualTo(ORACLE_COARSE_TERMINAL);
        }

        @Test
        @DisplayName("every raw code the cascade takes is two bytes wide, as the split status field is")
        void everyRawCodeIsTwoBytesWide() {
            assertThat(FileStatusException.CODE_LENGTH).isEqualTo(ORACLE_RAW_STATUS_WIDTH);
            assertThat(encodedLength(STATUS_SUCCESS)).isEqualTo(ORACLE_RAW_STATUS_WIDTH);
            assertThat(encodedLength(STATUS_END_OF_FILE)).isEqualTo(ORACLE_RAW_STATUS_WIDTH);
            assertThat(encodedLength(STATUS_RECORD_NOT_FOUND)).isEqualTo(ORACLE_RAW_STATUS_WIDTH);
            assertThat(encodedLength(STATUS_UNLISTED_LEADING_NINE)).isEqualTo(ORACLE_RAW_STATUS_WIDTH);
            assertThat(encodedLength(FileStatus.SUCCESS.getCode())).isEqualTo(ORACLE_RAW_STATUS_WIDTH);
            assertThat(encodedLength(FileStatus.END_OF_FILE.getCode()))
                    .isEqualTo(ORACLE_RAW_STATUS_WIDTH);
        }

        @Test
        @DisplayName("the codes the estate compares carry the values the source compares them against")
        void theComparedCodesCarryTheirSourceValues() {
            assertThat(FileStatus.SUCCESS.getCode()).isEqualTo(STATUS_SUCCESS);
            assertThat(FileStatus.END_OF_FILE.getCode()).isEqualTo(STATUS_END_OF_FILE);
            assertThat(FileStatus.RECORD_NOT_FOUND.getCode()).isEqualTo(STATUS_RECORD_NOT_FOUND);
            assertThat(FileStatus.SUCCESS.isSuccess()).isTrue();
            assertThat(FileStatus.END_OF_FILE.isEndOfFile()).isTrue();
            assertThat(FileStatus.RECORD_NOT_FOUND.isSuccess()).isFalse();
            assertThat(FileStatus.RECORD_NOT_FOUND.isEndOfFile()).isFalse();
        }
    }

    @Nested
    @DisplayName("level one on the open, close and write paths: two arms only, and no end of file")
    final class RawStatusToCoarseResultOnTheOtherPaths {

        @ParameterizedTest(name = "an {0} reporting the raw status {1} normalises to the coarse result {2}")
        @CsvSource({
            "OPEN, " + STATUS_SUCCESS + ", " + ORACLE_COARSE_SUCCESS,
            "OPEN, " + STATUS_END_OF_FILE + ", " + ORACLE_COARSE_TERMINAL,
            "OPEN, " + STATUS_UNLISTED_LEADING_NINE + ", " + ORACLE_COARSE_TERMINAL,
            "CLOSE, " + STATUS_SUCCESS + ", " + ORACLE_COARSE_SUCCESS,
            "CLOSE, " + STATUS_END_OF_FILE + ", " + ORACLE_COARSE_TERMINAL,
            "CLOSE, " + STATUS_UNLISTED_LEADING_NINE + ", " + ORACLE_COARSE_TERMINAL,
            "WRITE, " + STATUS_SUCCESS + ", " + ORACLE_COARSE_SUCCESS,
            "WRITE, " + STATUS_END_OF_FILE + ", " + ORACLE_COARSE_TERMINAL,
            "WRITE, " + STATUS_UNLISTED_LEADING_NINE + ", " + ORACLE_COARSE_TERMINAL,
        })
        @DisplayName("success normalises to the success result and everything else to the terminal one")
        void twoArmsOnly(final AbstractCobolStep.IoOperation operation, final String rawStatus,
                final int expectedCoarseResult) {
            final ScriptedStep step = stepDelivering(List.of());

            assertThat(step.normaliseStatus(operation, rawStatus).applResult())
                    .isEqualTo(expectedCoarseResult);
        }

        @ParameterizedTest(name = "the at-end code is terminal, not end of file, on an {0}")
        @CsvSource({"OPEN", "CLOSE", "WRITE"})
        @DisplayName("the at-end code is terminal outside a read, which is the defect a naive reading "
                + "would introduce")
        void theAtEndCodeIsTerminalOutsideARead(final AbstractCobolStep.IoOperation operation) {
            final ScriptedStep step = stepDelivering(List.of());

            final AbstractCobolStep.IoOutcome outcome =
                    step.normaliseStatus(operation, STATUS_END_OF_FILE);

            assertThat(outcome).isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
            assertThat(outcome.applResult()).isEqualTo(ORACLE_COARSE_TERMINAL);
            assertThat(outcome.applResult()).isNotEqualTo(ORACLE_COARSE_END_OF_FILE);
            assertThat(operation.endOfFileTerminatesNormally()).isFalse();
        }

        @Test
        @DisplayName("the same raw code therefore takes two different arms according to the operation")
        void theSameCodeTakesTwoDifferentArms() {
            final ScriptedStep step = stepDelivering(List.of());

            assertThat(step.normaliseStatus(AbstractCobolStep.IoOperation.READ, STATUS_END_OF_FILE)
                    .applResult())
                    .isEqualTo(ORACLE_COARSE_END_OF_FILE);
            assertThat(step.normaliseStatus(AbstractCobolStep.IoOperation.CLOSE, STATUS_END_OF_FILE)
                    .applResult())
                    .isEqualTo(ORACLE_COARSE_TERMINAL);
        }

        @Test
        @DisplayName("each operation reports the gerund its legacy diagnostic used")
        void eachOperationReportsItsLegacyGerund() {
            assertThat(AbstractCobolStep.IoOperation.OPEN.legacyGerund())
                    .isEqualTo(ORACLE_GERUND_OPEN);
            assertThat(AbstractCobolStep.IoOperation.READ.legacyGerund())
                    .isEqualTo(ORACLE_GERUND_READ);
            assertThat(AbstractCobolStep.IoOperation.WRITE.legacyGerund())
                    .isEqualTo(ORACLE_GERUND_WRITE);
            assertThat(AbstractCobolStep.IoOperation.CLOSE.legacyGerund())
                    .isEqualTo(ORACLE_GERUND_CLOSE);
        }
    }

    // Level two of the status model: the coarse result selects one of three control branches.

    @Nested
    @DisplayName("level two, first branch: the success result continues and the read loop advances")
    final class CoarseSuccessContinues {

        @Test
        @DisplayName("every delivered record is processed once, in file order, and the loop advances")
        void everyDeliveredRecordIsProcessedInOrder() {
            final ScriptedStep step = stepDelivering(List.of(FIRST_RECORD, SECOND_RECORD));

            final AbstractCobolStep.ExecutionSummary summary = step.run();

            assertThat(step.processed).containsExactly(FIRST_RECORD, SECOND_RECORD);
            assertThat(step.lifecycle).containsExactly("open", "read", "process", "read", "process",
                    "read", "close");
            assertThat(summary.recordsRead()).isEqualTo(2L);
        }

        @Test
        @DisplayName("a continuing read sets no end-of-file state and emits no error diagnostic")
        void aContinuingReadEmitsNoErrorDiagnostic() {
            final ScriptedStep step = stepDelivering(List.of(FIRST_RECORD));

            step.run();

            assertThat(errorDiagnostics()).isEmpty();
            assertThat(diagnostics()).isNotEmpty();
            assertThat(diagnostics()).noneMatch(text -> text.contains(ORACLE_ABEND_ANNOUNCEMENT));
        }

        @Test
        @DisplayName("a successful guarded operation returns quietly and logs nothing at all")
        void aSuccessfulGuardedOperationLogsNothing() {
            final ScriptedStep step = stepDelivering(List.of());

            step.openResource(RESOURCE_NAME, () -> STATUS_SUCCESS);
            step.writeRecord(RESOURCE_NAME, () -> STATUS_SUCCESS);
            step.closeResource(RESOURCE_NAME, () -> STATUS_SUCCESS);

            assertThat(diagnostics()).isEmpty();
        }
    }

    @Nested
    @DisplayName("level two, second branch: the end-of-file result stops the loop cleanly")
    final class CoarseEndOfFileStopsCleanly {

        @Test
        @DisplayName("the at-end read is not processed and the lifecycle reaches its close")
        void theAtEndReadIsNotProcessedAndTheCloseIsReached() {
            final ScriptedStep step = stepDelivering(List.of(FIRST_RECORD));

            final AbstractCobolStep.ExecutionSummary summary = step.run();

            assertThat(step.processed).containsExactly(FIRST_RECORD);
            assertThat(step.lifecycle).containsExactly("open", "read", "process", "read", "close");
            assertThat(step.lifecycle).doesNotContain("release");
            assertThat(summary.recordsRead()).isEqualTo(1L);
        }

        @Test
        @DisplayName("an empty file is a normal outcome: no exception, no error text, a complete summary")
        void anEmptyFileIsANormalOutcome() {
            final ScriptedStep step = stepDelivering(List.of());

            final AbstractCobolStep.ExecutionSummary summary = step.run();

            assertThat(summary.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(summary.recordsRead()).isZero();
            assertThat(summary.startedAt()).isEqualTo(PINNED_TIMESTAMP_IMAGE);
            assertThat(summary.completedAt()).isEqualTo(PINNED_TIMESTAMP_IMAGE);
            assertThat(errorDiagnostics()).isEmpty();
            assertThat(step.processed).isEmpty();
        }

        @Test
        @DisplayName("the end-of-file result is reported by the read helper as an absent record")
        void theEndOfFileResultIsReportedAsAnAbsentRecord() {
            final ScriptedStep step = stepDelivering(List.of());

            final Optional<String> delivered = step.readRecord(RESOURCE_NAME,
                    AbstractCobolStep.IoResult::endOfFile);

            assertThat(delivered).isEmpty();
            assertThat(diagnostics()).isEmpty();
        }
    }

    @Nested
    @DisplayName("level two, third branch: the terminal result diagnoses, then names the raw status, "
            + "then ends the run")
    final class CoarseTerminalResultDiagnosesThenAbends {

        @Test
        @DisplayName("both diagnostics are emitted before the failure ever reaches the caller")
        void bothDiagnosticsPrecedeThePropagatedFailure() {
            final ScriptedStep step = stepDelivering(List.of());

            final AbendException thrown = catchThrowableOfType(AbendException.class,
                    () -> step.closeResource(REJECTS_RESOURCE_NAME, () -> STATUS_PERMANENT_ERROR));
            final int diagnosticsBeforePropagation =
                    mockingDetails(AbstractCobolStepTest.this.appender).getInvocations().size();

            assertThat(thrown).isNotNull();
            assertThat(diagnosticsBeforePropagation).isEqualTo(ORACLE_TERMINAL_DIAGNOSTIC_COUNT);

            final InOrder ordered = inOrder(AbstractCobolStepTest.this.appender);
            ordered.verify(AbstractCobolStepTest.this.appender).doAppend(argThat(event ->
                    event.getLevel() == Level.ERROR
                            && event.getFormattedMessage().contains(ORACLE_GERUND_CLOSE)
                            && event.getFormattedMessage().contains(REJECTS_RESOURCE_NAME)
                            && event.getFormattedMessage().contains(ORACLE_STATUS_DISPLAY)
                            && event.getFormattedMessage().contains(STATUS_PERMANENT_ERROR)));
            ordered.verify(AbstractCobolStepTest.this.appender).doAppend(argThat(event ->
                    event.getLevel() == Level.ERROR
                            && event.getFormattedMessage().contains(ORACLE_ABEND_ANNOUNCEMENT)
                            && event.getFormattedMessage().contains(PROGRAM_NAME)));
            ordered.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("the per-call-site terminal helper reaches the same two diagnostics in the same "
                + "order")
        void thePerCallSiteHelperReachesTheSameOrderedDiagnostics() {
            final ScriptedStep step = stepDelivering(List.of());

            final AbendException thrown = catchThrowableOfType(AbendException.class,
                    () -> step.abendOnIoFailure(AbstractCobolStep.IoOperation.READ, RESOURCE_NAME,
                            STATUS_RECORD_NOT_FOUND));

            assertThat(thrown).isNotNull();

            final InOrder ordered = inOrder(AbstractCobolStepTest.this.appender);
            ordered.verify(AbstractCobolStepTest.this.appender).doAppend(argThat(event ->
                    event.getFormattedMessage().contains(ORACLE_GERUND_READ)
                            && event.getFormattedMessage().contains(STATUS_RECORD_NOT_FOUND)));
            ordered.verify(AbstractCobolStepTest.this.appender).doAppend(argThat(event ->
                    event.getFormattedMessage().contains(ORACLE_ABEND_ANNOUNCEMENT)));
            ordered.verifyNoMoreInteractions();
        }

        @ParameterizedTest(name = "the diagnostic carries the raw status {0} verbatim")
        @CsvSource({STATUS_RECORD_NOT_FOUND, STATUS_UNLISTED_LEADING_NINE,
            STATUS_UNLISTED_NON_NUMERIC})
        @DisplayName("the raw two-byte status is present in the diagnostic under both legacy rendering "
                + "arms")
        void theRawStatusIsPresentUnderBothRenderingArms(final String rawStatus) {
            final ScriptedStep step = stepDelivering(List.of());

            final AbendException thrown = catchThrowableOfType(AbendException.class,
                    () -> step.openResource(RESOURCE_NAME, () -> rawStatus));

            assertThat(thrown).isNotNull();
            assertThat(errorDiagnostics()).hasSize(ORACLE_TERMINAL_DIAGNOSTIC_COUNT);
            assertThat(errorDiagnostics().get(0))
                    .contains(ORACLE_STATUS_DISPLAY)
                    .contains(rawStatus);
            assertThat(thrown.getMessage()).contains(rawStatus);
        }

        @Test
        @DisplayName("a terminal read status ends the run rather than terminating the loop")
        void aTerminalReadStatusEndsTheRun() {
            final ScriptedStep step = new ScriptedStep(PROGRAM_NAME,
                    AbstractCobolStepTest.this.meterRegistry, AbstractCobolStepTest.this.fixedClock);
            step.scriptedReads.addLast(
                    AbstractCobolStep.IoResult.of(STATUS_PERMANENT_ERROR, FIRST_RECORD));

            final AbendException thrown = catchThrowableOfType(AbendException.class, step::run);

            assertThat(thrown).isNotNull();
            assertThat(step.processed).isEmpty();
            assertThat(errorDiagnostics()).anyMatch(text -> text.contains(ORACLE_GERUND_READ));
            assertThat(errorDiagnostics()).anyMatch(text -> text.contains(ORACLE_ABEND_ANNOUNCEMENT));
        }

        @Test
        @DisplayName("an abend is terminal, so the close family never runs after one")
        void anAbendIsTerminalSoTheCloseFamilyNeverRuns() {
            final ScriptedStep step = stepDelivering(List.of());
            step.openStatus = STATUS_END_OF_FILE;

            final AbendException thrown = catchThrowableOfType(AbendException.class, step::run);

            assertThat(thrown).isNotNull();
            assertThat(step.lifecycle).containsExactly("open", "release");
            assertThat(step.lifecycle).doesNotContain("close");
            assertThat(step.releaseAttempts).isEqualTo(1);
        }

        @Test
        @DisplayName("a release that itself fails while a failure is already in flight is retained as "
                + "suppressed, so the primary failure still reaches the caller unchanged")
        void aFailingReleaseIsRetainedAsSuppressedAndNeverDisplacesThePrimaryFailure() {
            // This is the one arm where two failures are live at once. The legacy program abends on
            // the first failure and its diagnostic is what an operator reads, so the release problem
            // must never become the failure that surfaces: losing the primary would rename the
            // incident, and rethrowing the secondary would report a cleanup fault as though it were
            // the cause. The template's contract is therefore to attach the secondary to the primary
            // and carry on unwinding.
            final ScriptedStep step = stepDelivering(List.of());
            step.openStatus = STATUS_END_OF_FILE;
            final RuntimeException secondary =
                    new IllegalStateException("handle release refused by the scripted resource");
            step.releaseFailure = secondary;

            final AbendException thrown = catchThrowableOfType(AbendException.class, step::run);

            // The primary is unchanged: same type, and not the secondary in disguise.
            assertThat(thrown)
                    .as("the primary failure must reach the caller, not the release failure")
                    .isNotNull()
                    .isNotSameAs(secondary);
            assertThat(thrown).isNotInstanceOf(IllegalStateException.class);

            // The secondary is attached exactly once. A count matters as much as the identity: a
            // release invoked twice, or an exception attached twice, would both show up here.
            assertThat(thrown.getSuppressed())
                    .as("the release failure must be retained as the single suppressed cause")
                    .containsExactly(secondary);

            // Release was entered once and once only, even though it threw.
            assertThat(step.releaseAttempts)
                    .as("the failing release must be attempted exactly once, never retried")
                    .isEqualTo(1);
            assertThat(step.lifecycle)
                    .as("the close family must still be skipped: the abend is terminal")
                    .containsExactly("open", "release");

            // The secondary is reported at warning level, naming the program, and is not promoted to
            // an error: promoting it would put two failures at the same severity in the log and leave
            // an operator unable to tell which one ended the run.
            //
            // This assertion previously ended at "RETAINED AS SUPPRESSED", because the secondary was
            // handed to the logger as a throwable and rendered separately from the message. That was
            // the defect: rendering a throwable publishes its own narrative, which is where a data
            // layer quotes the connection string it failed on. The diagnostic now names the classified
            // failure chain in the message itself, so a reader still learns what kind of failure the
            // release refused with while nothing the failure said is published.
            assertThat(warningDiagnostics())
                    .as("the retained secondary must be reported once, at warning level, naming its "
                            + "classified chain rather than its own narrative")
                    .containsExactly("SECONDARY FAILURE RELEASING HANDLES OF PROGRAM "
                            + PROGRAM_NAME + "; RETAINED AS SUPPRESSED; failureChain="
                            + IllegalStateException.class.getSimpleName());
            assertThat(warningDiagnostics())
                    .as("and the secondary's own message must not reach the warning stream either")
                    .noneMatch(text -> text.contains(secondary.getMessage()));

            // The primary's own diagnostics are untouched by the secondary. Running the whole
            // lifecycle emits the terminal pair plus one run-level line reporting the abnormal
            // termination and the records read, so the expected count is the operation-level pair
            // and that one addition.
            assertThat(errorDiagnostics())
                    .as("the primary failure's diagnostics must be unaffected by the release failure")
                    .hasSize(ORACLE_TERMINAL_DIAGNOSTIC_COUNT + 1);
            assertThat(errorDiagnostics())
                    .anyMatch(text -> text.contains(ORACLE_ABEND_ANNOUNCEMENT));

            // The point of the whole arm: the secondary is nowhere in the error stream. Were it
            // promoted, two failures would sit at the same severity and an operator could not tell
            // which one ended the run.
            assertThat(errorDiagnostics())
                    .as("the retained secondary must not also be reported as an error")
                    .noneMatch(text -> text.contains("SECONDARY FAILURE"))
                    .noneMatch(text -> text.contains(secondary.getMessage()));
        }

        @Test
        @DisplayName("a processing failure part-way through the file, with a release that also fails, "
                + "keeps the primary and still reports the records already read")
        void aProcessingFailureWithAFailingReleaseKeepsThePrimaryAndReportsTheRecordsRead() {
            // The companion arm above fails at open, where nothing has been read yet. This one fails
            // mid-stream, which is the case the legacy program actually meets in production: a record
            // is rejected after the file has started flowing. It is a genuinely different observation
            // because the template increments its record counter before entering the processing hook,
            // so the abnormal-termination diagnostic an operator reads must carry a non-zero count.
            // The primary here is also raised outside any guarded operation, so no status diagnostic
            // precedes it and the whole error stream is the single run-level line.
            final ScriptedStep step = stepDelivering(List.of("FIRST RECORD", "SECOND RECORD"));
            final RuntimeException primary =
                    new IllegalStateException("record rejected by the scripted processing hook");
            final RuntimeException secondary =
                    new IllegalStateException("handle release refused by the scripted resource");
            step.processFailure = primary;
            step.releaseFailure = secondary;

            final IllegalStateException thrown =
                    catchThrowableOfType(IllegalStateException.class, step::run);

            // The caller receives the very object the processing hook raised, not a wrapper and not
            // the release failure.
            assertThat(thrown)
                    .as("the processing failure must reach the caller unchanged")
                    .isSameAs(primary);
            assertThat(thrown.getSuppressed())
                    .as("the release failure must be retained as the single suppressed cause")
                    .containsExactly(secondary);

            // The loop aborted on the first record: the second read was never attempted, and the
            // close family was skipped because the failure is terminal.
            assertThat(step.lifecycle)
                    .as("the run must abort on the failing record rather than continuing the file")
                    .containsExactly("open", "read", "process", "release");
            assertThat(step.processed)
                    .as("processing must have been entered on the first record only")
                    .containsExactly("FIRST RECORD");
            assertThat(step.releaseAttempts)
                    .as("the failing release must be attempted exactly once, never retried")
                    .isEqualTo(1);

            // The counter is live: one record had been read when the failure was raised. This is the
            // observable difference from the open-failure arm, which reports zero.
            assertThat(errorDiagnostics())
                    .as("a mid-stream failure must report the records already read")
                    .containsExactly("EXECUTION OF PROGRAM " + PROGRAM_NAME
                            + " TERMINATED ABNORMALLY AFTER 1 RECORD(S) READ");

            // No guarded operation failed, so no status diagnostic and no abend announcement belong
            // in the stream, and the retained secondary must not appear there either.
            assertThat(errorDiagnostics())
                    .as("an unguarded processing failure must not fabricate a status diagnostic")
                    .noneMatch(text -> text.contains(ORACLE_ABEND_ANNOUNCEMENT))
                    .noneMatch(text -> text.contains(secondary.getMessage()));

            // As in the open-failure arm above, this expectation used to end at "RETAINED AS
            // SUPPRESSED" because the throwable was rendered alongside the message. The classified
            // chain now appears in the message and the throwable is no longer handed over, so the
            // secondary's own narrative is absent from the warning stream as well as the error one.
            assertThat(warningDiagnostics())
                    .as("the retained secondary must be reported once, at warning level, naming its "
                            + "classified chain rather than its own narrative")
                    .containsExactly("SECONDARY FAILURE RELEASING HANDLES OF PROGRAM "
                            + PROGRAM_NAME + "; RETAINED AS SUPPRESSED; failureChain="
                            + IllegalStateException.class.getSimpleName());
            assertThat(warningDiagnostics())
                    .as("and the secondary's own message must not reach the warning stream either")
                    .noneMatch(text -> text.contains(secondary.getMessage()));
        }

        @Test
        @DisplayName("the terminal path ends the run rather than handing back a recoverable status")
        void theTerminalPathEndsTheRunRatherThanHandingBackAStatus() {
            final ScriptedStep step = stepDelivering(List.of());

            final AbendException thrown = catchThrowableOfType(AbendException.class,
                    () -> step.writeRecord(RESOURCE_NAME, () -> STATUS_PERMANENT_ERROR));

            assertThat(thrown).isNotNull();
            assertThat(thrown).isNotInstanceOf(FileStatusException.class);
            assertThat(errorDiagnostics().get(0)).contains(ORACLE_GERUND_WRITE);
        }
    }

    // The pre-operation sentinel: armed before an open, a write and a close, meaning the operation
    // was attempted and its outcome is not yet normalised.

    @Nested
    @DisplayName("the pre-operation sentinel: a state that is none of the three normalised outcomes")
    final class PreOperationSentinel {

        @ParameterizedTest(name = "an {0} arms the sentinel before the operation runs")
        @CsvSource({"OPEN", "WRITE", "CLOSE"})
        @DisplayName("the open, write and close paths arm the sentinel the legacy moves")
        void theOpenWriteAndClosePathsArmTheSentinel(
                final AbstractCobolStep.IoOperation operation) {
            assertThat(operation.armedApplResult()).isEqualTo(ORACLE_COARSE_SENTINEL);
        }

        @Test
        @DisplayName("the read path arms no sentinel, because its paragraph never moves one")
        void theReadPathArmsNoSentinel() {
            assertThat(AbstractCobolStep.IoOperation.READ.armedApplResult())
                    .isEqualTo(ORACLE_COARSE_TERMINAL)
                    .isNotEqualTo(ORACLE_COARSE_SENTINEL);
        }

        @Test
        @DisplayName("the sentinel is distinct from every one of the three normalised outcomes")
        void theSentinelIsDistinctFromEveryNormalisedOutcome() {
            assertThat(ORACLE_COARSE_SENTINEL)
                    .isNotEqualTo(ORACLE_COARSE_SUCCESS)
                    .isNotEqualTo(ORACLE_COARSE_TERMINAL)
                    .isNotEqualTo(ORACLE_COARSE_END_OF_FILE);
            assertThat(AbstractCobolStep.IoOutcome.OK.applResult())
                    .isNotEqualTo(ORACLE_COARSE_SENTINEL);
            assertThat(AbstractCobolStep.IoOutcome.END_OF_FILE.applResult())
                    .isNotEqualTo(ORACLE_COARSE_SENTINEL);
            assertThat(AbstractCobolStep.IoOutcome.ERROR.applResult())
                    .isNotEqualTo(ORACLE_COARSE_SENTINEL);
        }

        @Test
        @DisplayName("an operation still carrying the sentinel is terminal, never success and never at "
                + "end")
        void anOperationStillCarryingTheSentinelIsTerminal() {
            final AbstractCobolStep.IoOutcome fromSentinel =
                    AbstractCobolStep.IoOutcome.fromApplResult(ORACLE_COARSE_SENTINEL);

            assertThat(fromSentinel).isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
            assertThat(fromSentinel).isNotEqualTo(AbstractCobolStep.IoOutcome.OK);
            assertThat(fromSentinel).isNotEqualTo(AbstractCobolStep.IoOutcome.END_OF_FILE);
            assertThat(fromSentinel.applResult()).isEqualTo(ORACLE_COARSE_TERMINAL);
        }

        @Test
        @DisplayName("an operation that reports no status at all ends the run and leaks no absent value")
        void anOperationThatReportsNoStatusAtAllEndsTheRun() {
            final ScriptedStep step = stepDelivering(List.of());
            step.openFailure = new IOException("the device reported no status");

            final AbendException thrown = catchThrowableOfType(AbendException.class,
                    step::openResources);

            assertThat(thrown).isNotNull();
            assertThat(thrown.getCause()).isInstanceOf(IOException.class);
            assertThat(errorDiagnostics()).hasSize(ORACLE_TERMINAL_DIAGNOSTIC_COUNT);
            assertThat(errorDiagnostics().get(0))
                    .contains(ORACLE_GERUND_OPEN)
                    .contains(RESOURCE_NAME)
                    .contains(ORACLE_STATUS_DISPLAY)
                    .doesNotContain("null");
            assertThat(errorDiagnostics().get(1)).contains(ORACLE_ABEND_ANNOUNCEMENT);
        }

        @Test
        @DisplayName("a failure already diagnosed once is not diagnosed a second time")
        void aFailureAlreadyDiagnosedIsNotDiagnosedTwice() {
            final ScriptedStep step = stepDelivering(List.of());
            step.readFailure = new AbendException(PROGRAM_NAME, "ALREADY DIAGNOSED DOWNSTREAM");

            final AbendException thrown = catchThrowableOfType(AbendException.class,
                    step::readNextRecord);

            assertThat(thrown).isNotNull();
            assertThat(thrown.reason()).isEqualTo("ALREADY DIAGNOSED DOWNSTREAM");
            assertThat(diagnostics()).isEmpty();
        }
    }

    // The abend code and the four-component work area the legacy abend routine fills in.

    @Nested
    @DisplayName("the abend: the published batch code and the four-component work area")
    final class AbendCodeAndWorkArea {

        @Test
        @DisplayName("the abend carries the published batch code rather than a restated literal")
        void theAbendCarriesThePublishedBatchCode() {
            final ScriptedStep step = stepDelivering(List.of());

            final AbendException thrown = catchThrowableOfType(AbendException.class,
                    () -> step.closeResource(RESOURCE_NAME, () -> STATUS_PERMANENT_ERROR));

            assertThat(thrown).isNotNull();
            assertThat(thrown.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
            assertThat(encodedLength(AbendException.BATCH_ABEND_CODE))
                    .isLessThanOrEqualTo(ORACLE_ABEND_CODE_WIDTH);
            assertThat(errorDiagnostics().get(1)).contains(AbendException.BATCH_ABEND_CODE);
        }

        @Test
        @DisplayName("the batch code is not the online code, so a batch failure is never mistaken for a "
                + "screen abend")
        void theBatchCodeIsNotTheOnlineCode() {
            assertThat(AbendException.BATCH_ABEND_CODE).isNotEqualTo(AbendException.ONLINE_ABEND_CODE);
        }

        @Test
        @DisplayName("the published component widths are the four the abend work area declares")
        void thePublishedComponentWidthsAreTheDeclaredOnes() {
            assertThat(AbendException.CODE_LENGTH).isEqualTo(ORACLE_ABEND_CODE_WIDTH);
            assertThat(AbendException.CULPRIT_LENGTH).isEqualTo(ORACLE_ABEND_CULPRIT_WIDTH);
            assertThat(AbendException.REASON_LENGTH).isEqualTo(ORACLE_ABEND_REASON_WIDTH);
            assertThat(AbendException.MESSAGE_LENGTH).isEqualTo(ORACLE_ABEND_MESSAGE_WIDTH);
            assertThat(AbendException.CONTEXT_LENGTH).isEqualTo(ORACLE_ABEND_CONTEXT_WIDTH);
            assertThat(ORACLE_ABEND_CODE_WIDTH + ORACLE_ABEND_CULPRIT_WIDTH
                    + ORACLE_ABEND_REASON_WIDTH + ORACLE_ABEND_MESSAGE_WIDTH)
                    .isEqualTo(ORACLE_ABEND_CONTEXT_WIDTH);
        }

        @Test
        @DisplayName("the rendered work area encodes to the four component widths and to their total")
        void theRenderedWorkAreaEncodesToItsComponentWidths() {
            final ScriptedStep step = stepDelivering(List.of());

            final AbendException thrown = catchThrowableOfType(AbendException.class,
                    () -> step.closeResource(REJECTS_RESOURCE_NAME, () -> STATUS_PERMANENT_ERROR));

            assertThat(thrown).isNotNull();

            final String workArea = thrown.toFixedWidthContext();
            final int culpritOffset = ORACLE_ABEND_CODE_WIDTH;
            final int reasonOffset = culpritOffset + ORACLE_ABEND_CULPRIT_WIDTH;
            final int messageOffset = reasonOffset + ORACLE_ABEND_REASON_WIDTH;
            final String code = component(workArea, 0, ORACLE_ABEND_CODE_WIDTH);
            final String culprit = component(workArea, culpritOffset, ORACLE_ABEND_CULPRIT_WIDTH);
            final String reason = component(workArea, reasonOffset, ORACLE_ABEND_REASON_WIDTH);
            final String message = component(workArea, messageOffset, ORACLE_ABEND_MESSAGE_WIDTH);

            assertThat(encodedLength(workArea)).isEqualTo(ORACLE_ABEND_CONTEXT_WIDTH);
            assertThat(encodedLength(code)).isEqualTo(ORACLE_ABEND_CODE_WIDTH);
            assertThat(encodedLength(culprit)).isEqualTo(ORACLE_ABEND_CULPRIT_WIDTH);
            assertThat(encodedLength(reason)).isEqualTo(ORACLE_ABEND_REASON_WIDTH);
            assertThat(encodedLength(message)).isEqualTo(ORACLE_ABEND_MESSAGE_WIDTH);
            assertThat(encodedLength(code) + encodedLength(culprit) + encodedLength(reason)
                    + encodedLength(message)).isEqualTo(ORACLE_ABEND_CONTEXT_WIDTH);
        }

        @Test
        @DisplayName("each component holds what the legacy field of that name held, padded not trimmed")
        void eachComponentHoldsWhatItsLegacyFieldHeld() {
            final ScriptedStep step = stepDelivering(List.of());

            final AbendException thrown = catchThrowableOfType(AbendException.class,
                    () -> step.closeResource(REJECTS_RESOURCE_NAME, () -> STATUS_PERMANENT_ERROR));

            assertThat(thrown).isNotNull();

            final String workArea = thrown.toFixedWidthContext();
            final int culpritOffset = ORACLE_ABEND_CODE_WIDTH;
            final int reasonOffset = culpritOffset + ORACLE_ABEND_CULPRIT_WIDTH;
            final int messageOffset = reasonOffset + ORACLE_ABEND_REASON_WIDTH;
            final String expectedCode = String.format(Locale.ROOT,
                    "%-" + ORACLE_ABEND_CODE_WIDTH + "s", AbendException.BATCH_ABEND_CODE);

            assertThat(component(workArea, 0, ORACLE_ABEND_CODE_WIDTH)).isEqualTo(expectedCode);
            assertThat(component(workArea, culpritOffset, ORACLE_ABEND_CULPRIT_WIDTH))
                    .isEqualTo(PROGRAM_NAME);
            assertThat(component(workArea, reasonOffset, ORACLE_ABEND_REASON_WIDTH))
                    .contains(STATUS_PERMANENT_ERROR)
                    .contains(ORACLE_GERUND_CLOSE)
                    .contains(REJECTS_RESOURCE_NAME);
            assertThat(component(workArea, messageOffset, ORACLE_ABEND_MESSAGE_WIDTH))
                    .contains(ORACLE_STATUS_DISPLAY)
                    .contains(STATUS_PERMANENT_ERROR);
            assertThat(thrown.culprit()).isEqualTo(PROGRAM_NAME);
            assertThat(encodedLength(thrown.culprit())).isEqualTo(ORACLE_ABEND_CULPRIT_WIDTH);
        }
    }

    // The documented source defect that must not be propagated: a diagnostic reporting one file's
    // status while announcing another file's failure.

    @Nested
    @DisplayName("the diagnostic reports the status and the file of the operation that actually failed")
    final class FailingOperationReportsItsOwnStatus {

        @Test
        @DisplayName("a failing close reports its own file and its own status, never a neighbour's")
        void aFailingCloseReportsItsOwnFileAndStatus() {
            final ScriptedStep step = stepDelivering(List.of());

            step.closeResource(OTHER_RESOURCE_NAME, () -> STATUS_SUCCESS);
            final AbendException thrown = catchThrowableOfType(AbendException.class,
                    () -> step.closeResource(REJECTS_RESOURCE_NAME, () -> STATUS_PERMANENT_ERROR));

            assertThat(thrown).isNotNull();
            assertThat(errorDiagnostics()).hasSize(ORACLE_TERMINAL_DIAGNOSTIC_COUNT);
            assertThat(errorDiagnostics().get(0))
                    .contains(ORACLE_GERUND_CLOSE)
                    .contains(REJECTS_RESOURCE_NAME)
                    .contains(STATUS_PERMANENT_ERROR)
                    .doesNotContain(OTHER_RESOURCE_NAME);
            assertThat(thrown.reason())
                    .contains(REJECTS_RESOURCE_NAME)
                    .doesNotContain(OTHER_RESOURCE_NAME);
            assertThat(thrown.getMessage())
                    .contains(REJECTS_RESOURCE_NAME)
                    .contains(STATUS_PERMANENT_ERROR)
                    .doesNotContain(OTHER_RESOURCE_NAME);
        }

        @Test
        @DisplayName("two operations failing on different files report two different files")
        void twoOperationsFailingOnDifferentFilesReportDifferentFiles() {
            final ScriptedStep step = stepDelivering(List.of());

            final AbendException first = catchThrowableOfType(AbendException.class,
                    () -> step.openResource(RESOURCE_NAME, () -> STATUS_RECORD_NOT_FOUND));
            final AbendException second = catchThrowableOfType(AbendException.class,
                    () -> step.writeRecord(OTHER_RESOURCE_NAME, () -> STATUS_PERMANENT_ERROR));

            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(first.getMessage())
                    .contains(RESOURCE_NAME)
                    .contains(STATUS_RECORD_NOT_FOUND)
                    .doesNotContain(OTHER_RESOURCE_NAME);
            assertThat(second.getMessage())
                    .contains(OTHER_RESOURCE_NAME)
                    .contains(STATUS_PERMANENT_ERROR)
                    .doesNotContain(RESOURCE_NAME);
        }

        @Test
        @DisplayName("a guarded operation refuses to run without naming the file it acts on")
        void aGuardedOperationRefusesToRunUnnamed() {
            final ScriptedStep step = stepDelivering(List.of());

            assertThat(catchThrowableOfType(NullPointerException.class,
                    () -> step.closeResource(null, () -> STATUS_SUCCESS))).isNotNull();
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> step.closeResource("   ", () -> STATUS_SUCCESS))).isNotNull();
            assertThat(diagnostics()).isEmpty();
        }
    }

    // The batch timestamp image, asserted position by position on encoded bytes.

    @Nested
    @DisplayName("the batch timestamp image: twenty-six bytes in the batch form, not the online form")
    final class BatchTimestampImage {

        @Test
        @DisplayName("the image is exactly twenty-six bytes when encoded")
        void theImageIsExactlyTwentySixBytesWhenEncoded() {
            final ScriptedStep step = stepDelivering(List.of());

            final String image = step.currentBatchTimestamp();

            assertThat(encodedLength(image)).isEqualTo(ORACLE_TIMESTAMP_WIDTH);
            assertThat(AbstractCobolStep.BATCH_TIMESTAMP_LENGTH).isEqualTo(ORACLE_TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("every position holds what the redefinition declares for it, separator by separator")
        void everyPositionHoldsWhatTheRedefinitionDeclares() {
            final ScriptedStep step = stepDelivering(List.of());

            final byte[] image = step.currentBatchTimestamp().getBytes(StandardCharsets.US_ASCII);

            assertThat(image).hasSize(ORACLE_TIMESTAMP_WIDTH);
            assertDigits(image, 0, 4);
            assertThat(image[4]).as("position 5").isEqualTo(ORACLE_DATE_SEPARATOR);
            assertDigits(image, 5, 2);
            assertThat(image[7]).as("position 8").isEqualTo(ORACLE_DATE_SEPARATOR);
            assertDigits(image, 8, 2);
            assertThat(image[10]).as("position 11").isEqualTo(ORACLE_DATE_SEPARATOR);
            assertDigits(image, 11, 2);
            assertThat(image[13]).as("position 14").isEqualTo(ORACLE_TIME_SEPARATOR);
            assertDigits(image, 14, 2);
            assertThat(image[16]).as("position 17").isEqualTo(ORACLE_TIME_SEPARATOR);
            assertDigits(image, 17, 2);
            assertThat(image[19]).as("position 20").isEqualTo(ORACLE_TIME_SEPARATOR);
            assertDigits(image, 20, 2);
            assertThat(image[22]).as("position 23").isEqualTo(ORACLE_ZERO_DIGIT);
            assertThat(image[23]).as("position 24").isEqualTo(ORACLE_ZERO_DIGIT);
            assertThat(image[24]).as("position 25").isEqualTo(ORACLE_ZERO_DIGIT);
            assertThat(image[25]).as("position 26").isEqualTo(ORACLE_ZERO_DIGIT);
        }

        @Test
        @DisplayName("the whole image matches the one the pinned instant must produce")
        void theWholeImageMatchesThePinnedExpectation() {
            final ScriptedStep step = stepDelivering(List.of());

            assertThat(step.currentBatchTimestamp()).isEqualTo(PINNED_TIMESTAMP_IMAGE);
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2022, 7, 6, 14, 23, 41, PINNED_NANOSECONDS)))
                    .isEqualTo(PINNED_TIMESTAMP_IMAGE);
        }

        @Test
        @DisplayName("the trailing field is the four literal characters the populating paragraph moves")
        void theTrailingFieldIsTheFourLiteralCharacters() {
            final ScriptedStep step = stepDelivering(List.of());

            final String image = step.currentBatchTimestamp();
            final String tail = component(image, ORACLE_TIMESTAMP_WIDTH - ORACLE_TIMESTAMP_TAIL_WIDTH,
                    ORACLE_TIMESTAMP_TAIL_WIDTH);

            assertThat(tail).isEqualTo(ORACLE_TIMESTAMP_TAIL);
            assertThat(encodedLength(tail)).isEqualTo(ORACLE_TIMESTAMP_TAIL_WIDTH);
            assertThat(encodedLength(ORACLE_TIMESTAMP_TAIL)).isEqualTo(ORACLE_TIMESTAMP_TAIL_WIDTH);
        }

        @Test
        @DisplayName("the image is the batch form and not the online form, which is the same width")
        void theImageIsTheBatchFormAndNotTheOnlineForm() {
            final ScriptedStep step = stepDelivering(List.of());

            final String text = step.currentBatchTimestamp();
            final byte[] image = text.getBytes(StandardCharsets.US_ASCII);

            assertThat(image[10]).as("position 11 divides the date from the time")
                    .isEqualTo(ORACLE_DATE_SEPARATOR)
                    .isNotEqualTo(ORACLE_ONLINE_DATE_AND_TIME_DIVIDER);
            assertThat(text).doesNotContain(ORACLE_ONLINE_TIME_SEPARATOR_TEXT);
            assertThat(text).doesNotContain(ORACLE_ONLINE_DATE_AND_TIME_DIVIDER_TEXT);
            assertThat(component(text, ORACLE_TIMESTAMP_WIDTH - ORACLE_TIMESTAMP_TAIL_WIDTH,
                    ORACLE_TIMESTAMP_TAIL_WIDTH))
                    .isEqualTo(ORACLE_TIMESTAMP_TAIL);
            assertThat(encodedLength(text)).isEqualTo(ORACLE_TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("precision below a hundredth of a second is truncated, never rounded")
        void precisionBelowAHundredthIsTruncated() {
            final String image = AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2022, 7, 6, 14, 23, 41, TRUNCATED_NANOSECONDS));

            assertThat(image).isEqualTo(PINNED_TIMESTAMP_IMAGE);
            assertThat(encodedLength(image)).isEqualTo(ORACLE_TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("the injected clock's zone decides the wall-clock reading, not the host's")
        void theInjectedClocksZoneDecidesTheWallClockReading() {
            final ScriptedStep shifted = new ScriptedStep(PROGRAM_NAME,
                    AbstractCobolStepTest.this.meterRegistry,
                    Clock.fixed(PINNED_INSTANT, SECOND_OFFSET));

            final String image = shifted.currentBatchTimestamp();

            assertThat(image).isEqualTo(PINNED_TIMESTAMP_IMAGE_AT_SECOND_OFFSET);
            assertThat(image).isNotEqualTo(PINNED_TIMESTAMP_IMAGE);
            assertThat(encodedLength(image)).isEqualTo(ORACLE_TIMESTAMP_WIDTH);
            assertThat(component(image, ORACLE_TIMESTAMP_WIDTH - ORACLE_TIMESTAMP_TAIL_WIDTH,
                    ORACLE_TIMESTAMP_TAIL_WIDTH)).isEqualTo(ORACLE_TIMESTAMP_TAIL);
        }

        @Test
        @DisplayName("the same instant produces the same image twice, because the clock is injected")
        void theSameInstantProducesTheSameImageTwice() {
            final ScriptedStep step = stepDelivering(List.of());

            final AbstractCobolStep.ExecutionSummary summary = step.run();

            assertThat(summary.startedAt()).isEqualTo(summary.completedAt());
            assertThat(summary.startedAt()).isEqualTo(PINNED_TIMESTAMP_IMAGE);
        }

        @Test
        @DisplayName("a year the four-byte field cannot hold is refused rather than silently widened")
        void anUnrepresentableYearIsRefused() {
            final LocalDateTime aboveTheCeiling =
                    LocalDateTime.of(ORACLE_SMALLEST_UNREPRESENTABLE_YEAR, 1, 1, 0, 0, 0, 0);
            final LocalDateTime belowTheFloor =
                    LocalDateTime.of(ORACLE_YEAR_BELOW_THE_FLOOR, 1, 1, 0, 0, 0, 0);

            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> AbstractCobolStep.formatBatchTimestamp(aboveTheCeiling))).isNotNull();
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> AbstractCobolStep.formatBatchTimestamp(belowTheFloor))).isNotNull();
        }

        @Test
        @DisplayName("the earliest and latest years the field can hold both render at the legacy width")
        void theBoundaryYearsBothRenderAtTheLegacyWidth() {
            final String earliest = AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(ORACLE_SMALLEST_REPRESENTABLE_YEAR, 1, 1, 0, 0, 0, 0));
            final String latest = AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(ORACLE_LARGEST_REPRESENTABLE_YEAR, 12, 31, 23, 59, 59, 0));

            assertThat(encodedLength(earliest)).isEqualTo(ORACLE_TIMESTAMP_WIDTH);
            assertThat(encodedLength(latest)).isEqualTo(ORACLE_TIMESTAMP_WIDTH);
            assertDigits(earliest.getBytes(StandardCharsets.US_ASCII), 0, 4);
            assertDigits(latest.getBytes(StandardCharsets.US_ASCII), 0, 4);
        }
    }

    // Shape: the nested types are reached through the template, and the template is a single-pass,
    // sequential tasklet whose name reaches the step built around it.

    @Nested
    @DisplayName("the nested types are members of the template and are reached through it")
    final class NestedTypesReachedThroughTheTemplate {

        @Test
        @DisplayName("the coarse-outcome type carries exactly the three outcomes and their values")
        void theCoarseOutcomeTypeCarriesThreeOutcomes() {
            assertThat(AbstractCobolStep.IoOutcome.values()).hasSize(3);
            assertThat(AbstractCobolStep.IoOutcome.OK.applResult()).isEqualTo(ORACLE_COARSE_SUCCESS);
            assertThat(AbstractCobolStep.IoOutcome.END_OF_FILE.applResult())
                    .isEqualTo(ORACLE_COARSE_END_OF_FILE);
            assertThat(AbstractCobolStep.IoOutcome.ERROR.applResult())
                    .isEqualTo(ORACLE_COARSE_TERMINAL);
        }

        @Test
        @DisplayName("the operation type carries exactly the four legacy verbs")
        void theOperationTypeCarriesFourVerbs() {
            assertThat(AbstractCobolStep.IoOperation.values()).hasSize(4);
            assertThat(AbstractCobolStep.IoOperation.READ.endOfFileTerminatesNormally()).isTrue();
            assertThat(AbstractCobolStep.IoOperation.OPEN.endOfFileTerminatesNormally()).isFalse();
            assertThat(AbstractCobolStep.IoOperation.WRITE.endOfFileTerminatesNormally()).isFalse();
            assertThat(AbstractCobolStep.IoOperation.CLOSE.endOfFileTerminatesNormally()).isFalse();
        }

        @Test
        @DisplayName("the result type keeps the raw status travelling with the record it belongs to")
        void theResultTypeKeepsTheStatusWithTheRecord() {
            final AbstractCobolStep.IoResult<String> delivered =
                    AbstractCobolStep.IoResult.of(STATUS_SUCCESS, FIRST_RECORD);
            final AbstractCobolStep.IoResult<String> atEnd = AbstractCobolStep.IoResult.endOfFile();

            assertThat(delivered.rawStatus()).isEqualTo(STATUS_SUCCESS);
            assertThat(delivered.record()).isEqualTo(FIRST_RECORD);
            assertThat(atEnd.rawStatus()).isEqualTo(STATUS_END_OF_FILE);
            assertThat(atEnd.record()).isNull();
        }

        @Test
        @DisplayName("the summary type reports what one execution observed and refuses an absent part")
        void theSummaryTypeReportsWhatOneExecutionObserved() {
            final AbstractCobolStep.ExecutionSummary summary = new AbstractCobolStep.ExecutionSummary(
                    PROGRAM_NAME, 2L, PINNED_TIMESTAMP_IMAGE, PINNED_TIMESTAMP_IMAGE);

            assertThat(summary.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(summary.recordsRead()).isEqualTo(2L);
            assertThat(summary.startedAt()).isEqualTo(PINNED_TIMESTAMP_IMAGE);
            assertThat(summary.completedAt()).isEqualTo(PINNED_TIMESTAMP_IMAGE);
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new AbstractCobolStep.ExecutionSummary(PROGRAM_NAME, -1L,
                            PINNED_TIMESTAMP_IMAGE, PINNED_TIMESTAMP_IMAGE))).isNotNull();
        }

        @Test
        @DisplayName("the timestamp helper is a member of the template, reached through it")
        void theTimestampHelperIsAMemberOfTheTemplate() {
            assertThat(AbstractCobolStep.BATCH_TIMESTAMP_LENGTH).isEqualTo(ORACLE_TIMESTAMP_WIDTH);
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2022, 7, 6, 14, 23, 41, PINNED_NANOSECONDS)))
                    .isEqualTo(PINNED_TIMESTAMP_IMAGE);
        }
    }

    @Nested
    @DisplayName("step construction: the supplied name reaches the built step and execution stays "
            + "sequential")
    final class StepConstruction {

        @Test
        @DisplayName("the program name is reported back verbatim and bounds itself to the culprit width")
        void theProgramNameIsReportedBackVerbatim() {
            final ScriptedStep step = stepDelivering(List.of());

            assertThat(step.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(encodedLength(step.programName()))
                    .isLessThanOrEqualTo(ORACLE_ABEND_CULPRIT_WIDTH);
        }

        @Test
        @DisplayName("a name longer than the legacy culprit field is refused at construction")
        void anOverlongProgramNameIsRefusedAtConstruction() {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new ScriptedStep(PROGRAM_NAME + "X",
                            AbstractCobolStepTest.this.meterRegistry,
                            AbstractCobolStepTest.this.fixedClock))).isNotNull();
        }

        @Test
        @DisplayName("the name supplied to the step builder reaches the built step")
        void theSuppliedNameReachesTheBuiltStep() {
            final ScriptedStep step = stepDelivering(List.of());

            final Step built = new StepBuilder(ORACLE_STEP_NAME,
                    AbstractCobolStepTest.this.jobRepository)
                    .tasklet(step, AbstractCobolStepTest.this.transactionManager)
                    .build();

            assertThat(built.getName()).isEqualTo(ORACLE_STEP_NAME);
            assertThat(built).isInstanceOf(TaskletStep.class);
        }

        @Test
        @DisplayName("the template is a single-pass tasklet, so one execution is one whole lifecycle")
        void theTemplateIsASinglePassTasklet() throws Exception {
            final ScriptedStep step = stepDelivering(List.of(FIRST_RECORD));

            assertThat(step).isInstanceOf(Tasklet.class);
            assertThat(step.execute(null, null)).isEqualTo(RepeatStatus.FINISHED);
            assertThat(step.lifecycle).containsExactly("open", "read", "process", "read", "close");
            assertThat(step.processed).containsExactly(FIRST_RECORD);
        }

        @Test
        @DisplayName("one instance keeps no per-execution state, so the same instance may run twice")
        void oneInstanceMayRunTwice() {
            final ScriptedStep step = stepDelivering(List.of(FIRST_RECORD));

            final AbstractCobolStep.ExecutionSummary first = step.run();
            step.deliverRecord(SECOND_RECORD);
            final AbstractCobolStep.ExecutionSummary second = step.run();

            assertThat(first.recordsRead()).isEqualTo(1L);
            assertThat(second.recordsRead()).isEqualTo(1L);
            assertThat(step.processed).containsExactly(FIRST_RECORD, SECOND_RECORD);
        }
    }

    // Observability: the lifecycle timer is asserted for presence, name and tag shape only. No
    // numeric latency, throughput, availability or capacity figure is asserted anywhere, because the
    // repository documents no legacy performance baseline to compare one against.

    @Nested
    @DisplayName("the lifecycle timer: present, named, and tagged with the step and the outcome")
    final class StepExecutionTimer {

        @Test
        @DisplayName("a completing lifecycle registers the timer under the completed outcome")
        void aCompletingLifecycleRegistersTheCompletedOutcome() {
            final ScriptedStep step = stepDelivering(List.of(FIRST_RECORD));

            step.run();

            final Timer timer = AbstractCobolStepTest.this.meterRegistry.find(ORACLE_METRIC_NAME)
                    .timer();
            assertThat(timer).isNotNull();
            assertThat(timer.getId().getTags().stream().map(Tag::getKey).toList())
                    .containsExactlyInAnyOrder(ORACLE_TAG_STEP, ORACLE_TAG_OUTCOME);
            assertThat(timer.getId().getTag(ORACLE_TAG_STEP)).isEqualTo(PROGRAM_NAME);
            assertThat(timer.getId().getTag(ORACLE_TAG_OUTCOME)).isEqualTo(ORACLE_OUTCOME_COMPLETED);
            assertThat(timer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("an abending lifecycle registers the timer under a distinguishing outcome")
        void anAbendingLifecycleRegistersTheAbendedOutcome() {
            final ScriptedStep step = stepDelivering(List.of());
            step.openStatus = STATUS_PERMANENT_ERROR;

            final AbendException thrown = catchThrowableOfType(AbendException.class, step::run);

            assertThat(thrown).isNotNull();

            final Timer timer = AbstractCobolStepTest.this.meterRegistry.find(ORACLE_METRIC_NAME)
                    .tag(ORACLE_TAG_OUTCOME, ORACLE_OUTCOME_ABENDED)
                    .timer();
            assertThat(timer).isNotNull();
            assertThat(timer.getId().getTag(ORACLE_TAG_STEP)).isEqualTo(PROGRAM_NAME);
            assertThat(timer.count()).isEqualTo(1L);
            assertThat(AbstractCobolStepTest.this.meterRegistry.find(ORACLE_METRIC_NAME)
                    .tag(ORACLE_TAG_OUTCOME, ORACLE_OUTCOME_COMPLETED).timer()).isNull();
        }

        @Test
        @DisplayName("the two outcomes are distinct tag values, so one never masks the other")
        void theTwoOutcomesAreDistinctTagValues() {
            assertThat(ORACLE_OUTCOME_COMPLETED).isNotEqualTo(ORACLE_OUTCOME_ABENDED);
            assertThat(ORACLE_TAG_STEP).isNotEqualTo(ORACLE_TAG_OUTCOME);
        }
    }
}
