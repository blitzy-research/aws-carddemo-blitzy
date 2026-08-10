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

import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link AbendService}, the Java stand-in for the CardDemo
 * abnormal-termination path.
 *
 * <h2>What is under test</h2>
 *
 * <p>The legacy estate ends a program abnormally in two different ways, and this
 * service carries both:</p>
 *
 * <ul>
 *   <li>The batch tier calls the Language Environment abort routine
 *       {@code CEE3ABD} at nine sites across the batch programs, always after a
 *       diagnostic display of the failing file status.</li>
 *   <li>The online tier issues a CICS abend, and only in the five-program family
 *       that registers an abend handler. The other twelve online programs have no
 *       abend path at all, which is why this service is wired exactly where the
 *       legacy wired it and nowhere else.</li>
 * </ul>
 *
 * <p>The context image the service produces is the 134-byte structure declared in
 * {@code app/cpy/CSMSG02Y.cpy}: a 4-character abend code, an 8-character
 * culprit program name, a 50-character reason, and a 72-character message. Those
 * widths are contractual, so the exception refuses an over-length component
 * rather than truncating it — a truncated culprit would misidentify the failing
 * program in an operator's diagnostic.</p>
 *
 * <h2>The misuse guard that protects the batch read loop</h2>
 *
 * <p>Every batch program normalises a two-byte file status into a coarse outcome
 * before branching, and the end-of-file arm is how a sequential read loop
 * terminates <em>normally</em>. Abending on a status of success or end of file
 * would therefore turn an ordinary loop exit into a job failure, so the service
 * refuses both with a diagnostic rather than accepting them. These tests pin that
 * refusal, because it is the difference between a job that ends and a job that
 * fails.</p>
 *
 * <p>Provenance: legacy sources read at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text
 * is reproduced here.</p>
 */
@DisplayName("AbendService - the CEE3ABD batch abort and the CICS online abend")
class AbendServiceBaselineTest {

    /** A representative batch program name at the full eight-character width. */
    private static final String BATCH_PROGRAM = "CBACT01C";

    /** A representative online program name at the full eight-character width. */
    private static final String ONLINE_PROGRAM = "COSGN00C";

    /** A file status that is a genuine error and so may abend. */
    private static final String STATUS_RECORD_NOT_FOUND = "23";

    /** A read operation name, as the batch diagnostic reports it. */
    private static final String OPERATION = "READ";

    /** A resource name, as the batch diagnostic reports it. */
    private static final String RESOURCE = "ACCTDAT";

    private final AbendService service = new AbendService();

    @Nested
    @DisplayName("abendBatch - the Language Environment abort path")
    class AbendBatch {

        @Test
        @DisplayName("raises an abend carrying the batch abend code, the culprit, and the reason")
        void raisesABatchAbend() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, "INTEREST RUN FAILED"))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(BATCH_PROGRAM);
                        assertThat(abend.reason()).isEqualTo("INTEREST RUN FAILED");
                        assertThat(abend.getMessage()).isEqualTo(AbendException.DEFAULT_MESSAGE);
                        assertThat(abend.getCause()).isNull();
                    });
        }

        @Test
        @DisplayName("carries the same abend when a file status, operation, and resource are supplied")
        void carriesTheDiagnosticArguments() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, "READ FAILED",
                            STATUS_RECORD_NOT_FOUND, OPERATION, RESOURCE))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.reason()).isEqualTo("READ FAILED");
                        assertThat(abend.getCause())
                                .as("the argument form records the status only in the diagnostic")
                                .isNull();
                    });
        }

        @Test
        @DisplayName("tolerates absent arguments, reporting them as empty rather than failing")
        void toleratesAbsentArguments() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(null, null))
                    .satisfies(abend -> {
                        assertThat(abend.culprit()).isEmpty();
                        assertThat(abend.reason()).isEmpty();
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                    });
        }

        @Test
        @DisplayName("refuses a culprit wider than the legacy program-name field")
        void refusesAnOverlongCulprit() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendBatch("C".repeat(9), "REASON"))
                    .withMessageContaining("culprit exceeds")
                    .withMessageContaining("length 9");
        }

        @Test
        @DisplayName("refuses a reason wider than the legacy reason field")
        void refusesAnOverlongReason() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, "R".repeat(51)))
                    .withMessageContaining("reason exceeds")
                    .withMessageContaining("length 51");
        }

        @Test
        @DisplayName("accepts a reason at exactly the legacy reason width")
        void acceptsAReasonAtTheExactWidth() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, "R".repeat(50)))
                    .satisfies(abend -> assertThat(abend.reason()).hasSize(50));
        }
    }

    @Nested
    @DisplayName("abendOnline - the CICS abend path")
    class AbendOnline {

        @Test
        @DisplayName("raises an abend carrying the online abend code and the default terminal message")
        void raisesAnOnlineAbend() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(ONLINE_PROGRAM, "SIGNON FAILED"))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.ONLINE_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(ONLINE_PROGRAM);
                        assertThat(abend.reason()).isEqualTo("SIGNON FAILED");
                        assertThat(abend.getMessage()).isEqualTo(AbendException.DEFAULT_MESSAGE);
                    });
        }

        @Test
        @DisplayName("uses a distinct abend code from the batch path")
        void usesADistinctCodeFromBatch() {
            assertThat(AbendException.ONLINE_ABEND_CODE)
                    .isNotEqualTo(AbendException.BATCH_ABEND_CODE);
        }

        @Test
        @DisplayName("carries a supplied terminal message to the operator instead of the default")
        void carriesASuppliedTerminalMessage() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(ONLINE_PROGRAM, "SIGNON FAILED",
                            "PLEASE CALL SUPPORT"))
                    .satisfies(abend ->
                            assertThat(abend.getMessage()).isEqualTo("PLEASE CALL SUPPORT"));
        }

        @Test
        @DisplayName("refuses a terminal message wider than the legacy message field")
        void refusesAnOverlongTerminalMessage() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnline(ONLINE_PROGRAM, "R", "M".repeat(73)))
                    .withMessageContaining("message exceeds")
                    .withMessageContaining("length 73");
        }

        @Test
        @DisplayName("accepts a terminal message at exactly the legacy message width")
        void acceptsAMessageAtTheExactWidth() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(ONLINE_PROGRAM, "R", "M".repeat(72)))
                    .satisfies(abend -> assertThat(abend.getMessage()).hasSize(72));
        }
    }

    @Nested
    @DisplayName("The 134-byte context image")
    class ContextImage {

        @Test
        @DisplayName("renders exactly the legacy structure width")
        void rendersTheLegacyStructureWidth() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, "FILE STATUS 23"))
                    .satisfies(abend -> assertThat(abend.toFixedWidthContext())
                            .hasSize(AbendException.CONTEXT_LENGTH));
        }

        @Test
        @DisplayName("lays the four components out at their declared widths and offsets")
        void laysTheComponentsOutAtDeclaredOffsets() {
            assertThat(AbendException.CONTEXT_LENGTH)
                    .isEqualTo(AbendException.CODE_LENGTH + AbendException.CULPRIT_LENGTH
                            + AbendException.REASON_LENGTH + AbendException.MESSAGE_LENGTH)
                    .isEqualTo(134);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, "FILE STATUS 23"))
                    .satisfies(abend -> {
                        final String image = abend.toFixedWidthContext();
                        final int culpritStart = AbendException.CODE_LENGTH;
                        final int reasonStart = culpritStart + AbendException.CULPRIT_LENGTH;
                        final int messageStart = reasonStart + AbendException.REASON_LENGTH;

                        assertThat(image.substring(0, culpritStart))
                                .as("the code is padded into its four positions")
                                .startsWith(AbendException.BATCH_ABEND_CODE)
                                .hasSize(AbendException.CODE_LENGTH);
                        assertThat(image.substring(culpritStart, reasonStart))
                                .isEqualTo(BATCH_PROGRAM);
                        assertThat(image.substring(reasonStart, messageStart))
                                .startsWith("FILE STATUS 23")
                                .hasSize(AbendException.REASON_LENGTH);
                        assertThat(image.substring(messageStart))
                                .startsWith(AbendException.DEFAULT_MESSAGE)
                                .hasSize(AbendException.MESSAGE_LENGTH);
                    });
        }
    }

    @Nested
    @DisplayName("displayIoStatus - the diagnostic display that precedes an abort")
    class DisplayIoStatus {

        @Test
        @DisplayName("reports a status without raising anything, because it only displays")
        void reportsWithoutRaising() {
            assertThatCode(() ->
                    service.displayIoStatus(STATUS_RECORD_NOT_FOUND, OPERATION, RESOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("tolerates absent arguments")
        void toleratesAbsentArguments() {
            assertThatCode(() -> service.displayIoStatus(null, null, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("tolerates a status outside the declared vocabulary")
        void toleratesAStatusOutsideTheVocabulary() {
            assertThatCode(() -> service.displayIoStatus("99", OPERATION, RESOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("tolerates a blank status")
        void toleratesABlankStatus() {
            assertThatCode(() -> service.displayIoStatus("  ", "  ", "  "))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("abendOnFileStatus - abending on a genuine input-output failure")
    class AbendOnFileStatus {

        @Test
        @DisplayName("abends with the failing status as the reason and the failure as the cause")
        void abendsWithTheStatusAsReason() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_RECORD_NOT_FOUND, OPERATION, RESOURCE))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.reason())
                                .isEqualTo("FILE STATUS " + STATUS_RECORD_NOT_FOUND);
                        assertThat(abend.getCause()).isInstanceOf(FileStatusException.class);
                    });
        }

        @Test
        @DisplayName("abends on a status outside the declared vocabulary rather than ignoring it")
        void abendsOnAStatusOutsideTheVocabulary() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() ->
                            service.abendOnFileStatus(BATCH_PROGRAM, "99", OPERATION, RESOURCE))
                    .satisfies(abend -> assertThat(abend.reason()).isEqualTo("FILE STATUS 99"));
        }

        @Test
        @DisplayName("refuses to abend on a successful operation, which is a misuse")
        void refusesToAbendOnSuccess() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM,
                            FileStatusException.STATUS_SUCCESS, OPERATION, RESOURCE))
                    .withMessageContaining("successful operation")
                    .withMessageContaining("must not abend");
        }

        @Test
        @DisplayName("refuses to abend on end of file, because that is how a read loop ends normally")
        void refusesToAbendOnEndOfFile() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM,
                            FileStatusException.STATUS_END_OF_FILE, OPERATION, RESOURCE))
                    .withMessageContaining("end of file")
                    .withMessageContaining("must not abend");
        }

        @Test
        @DisplayName("refuses a null status, stating the required width")
        void refusesANullStatus() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, (String) null,
                            OPERATION, RESOURCE))
                    .withMessageContaining("exactly " + FileStatusException.CODE_LENGTH)
                    .withMessageContaining("null was supplied");
        }

        @ParameterizedTest(name = "refuses a status of [{0}], which is not two characters")
        @ValueSource(strings = {"2", "230", "", "    "})
        @DisplayName("refuses a status that is not exactly two characters")
        void refusesAWronglySizedStatus(final String rawFileStatus) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, rawFileStatus,
                            OPERATION, RESOURCE))
                    .withMessageContaining("must be exactly "
                            + FileStatusException.CODE_LENGTH + " characters");
        }

        @Test
        @DisplayName("abends from a supplied file failure, keeping it as the cause")
        void abendsFromASuppliedFailure() {
            final FileStatusException failure =
                    new FileStatusException(STATUS_RECORD_NOT_FOUND, OPERATION, RESOURCE);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, failure))
                    .satisfies(abend -> {
                        assertThat(abend.reason())
                                .isEqualTo("FILE STATUS " + STATUS_RECORD_NOT_FOUND);
                        assertThat(abend.getCause()).isSameAs(failure);
                    });
        }

        @Test
        @DisplayName("refuses a null file failure, because there would be nothing to report")
        void refusesANullFailure() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            service.abendOnFileStatus(BATCH_PROGRAM, (FileStatusException) null))
                    .withMessageContaining("A FileStatusException is required");
        }

        @Test
        @DisplayName("tolerates an absent culprit when reporting a genuine failure")
        void toleratesAnAbsentCulprit() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(null, STATUS_RECORD_NOT_FOUND,
                            OPERATION, RESOURCE))
                    .satisfies(abend -> assertThat(abend.culprit()).isEmpty());
        }
    }

    @Nested
    @DisplayName("the two level-gated diagnostic branches")
    class DiagnosticLevelGating {

        /**
         * A reason well inside the 50-character reason field and free of the bracket
         * characters that delimit the emitted context image, so the image can be
         * recovered from the formatted message without ambiguity.
         */
        private static final String REASON = "SIMULATED FAILURE FOR DIAGNOSTIC GATING";

        /** A terminal message well inside the 72-character message field. */
        private static final String TERMINAL_MESSAGE = "PLEASE CONTACT SUPPORT.";

        private Logger logger;
        private ListAppender<ILoggingEvent> recorder;
        private Level originalLevel;

        @BeforeEach
        void attachRecorder() {
            logger = (Logger) LoggerFactory.getLogger(AbendService.class);
            originalLevel = logger.getLevel();
            recorder = new ListAppender<>();
            recorder.setContext(logger.getLoggerContext());
            recorder.start();
            logger.addAppender(recorder);
        }

        @AfterEach
        void detachRecorderAndRestoreLevel() {
            logger.detachAppender(recorder);
            recorder.stop();
            // A null own level restores inheritance from the parent, which is the
            // state the logger was in before this group ran.
            logger.setLevel(originalLevel);
        }

        @Test
        @DisplayName("inherits a level that records the diagnostic but withholds the context image")
        void theInheritedLevelRecordsTheDiagnosticButWithholdsTheContextImage() {
            assertThat(logger.getLevel())
                    .as("this group must start from the inherited level, not a level a"
                            + " previous test left behind")
                    .isNull();
            assertThat(logger.isErrorEnabled()).isTrue();
            assertThat(logger.isDebugEnabled()).isFalse();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(ONLINE_PROGRAM, REASON,
                            TERMINAL_MESSAGE));

            assertThat(recorder.list).hasSize(1);
            assertThat(recorder.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
            assertThat(recorder.list.getFirst().getFormattedMessage())
                    .contains(ONLINE_PROGRAM)
                    .contains(REASON);
        }

        @Test
        @DisplayName("withholds the batch diagnostic entirely when the logger is switched off,"
                + " yet still abends with an unchanged context")
        void switchingTheLoggerOffSuppressesTheDiagnosticButNotTheAbend() {
            logger.setLevel(Level.OFF);
            assertThat(logger.isErrorEnabled()).isFalse();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, REASON))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(BATCH_PROGRAM);
                        assertThat(abend.reason()).isEqualTo(REASON);
                        assertThat(abend.getMessage()).isEqualTo(AbendException.DEFAULT_MESSAGE);
                    });

            assertThat(recorder.list)
                    .as("a suppressed diagnostic must produce no event at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("withholds the file-status diagnostic when the logger is switched off,"
                + " yet still abends carrying the originating file failure")
        void switchingTheLoggerOffSuppressesTheFileStatusDiagnostic() {
            logger.setLevel(Level.OFF);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_RECORD_NOT_FOUND, OPERATION, RESOURCE))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.reason())
                                .isEqualTo("FILE STATUS " + STATUS_RECORD_NOT_FOUND);
                        assertThat(abend.getCause())
                                .isInstanceOf(FileStatusException.class);
                    });

            assertThat(recorder.list).isEmpty();
        }

        @Test
        @DisplayName("emits NO fixed-width context image on the online path even once the logger admits "
                + "debug events, and the area stays on the exception where the legacy transmitted it")
        void raisingTheLevelToDebugStillEmitsNoContextImage() {
            // THIS ASSERTION IS THE REVERSE OF THE ONE IT REPLACES, and the reversal is the finding. The
            // fourth field of the legacy area is a 72-character operator message slot - the one part not
            // drawn from a vocabulary this module owns - so a debug switch used to copy an arbitrary
            // caller-facing value into centralised logging in a format built for a 3270 screen. The legacy
            // routine transmitted that area to the terminal the operator was sitting at, which is a
            // different act with a different audience. Every width and offset assertion below is retained
            // verbatim; only its SOURCE moved, from a log record to the exception.
            // See docs/decision-log.md entry DL-312.
            logger.setLevel(Level.DEBUG);
            assertThat(logger.isDebugEnabled()).isTrue();

            final AbendException raised = catchThrowableOfType(AbendException.class,
                    () -> service.abendOnline(ONLINE_PROGRAM, REASON, TERMINAL_MESSAGE));

            assertThat(recorder.list)
                    .as("one record, at error level, whatever the level admits")
                    .hasSize(1);
            assertThat(recorder.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
            assertThat(recorder.list.getFirst().getFormattedMessage())
                    .doesNotContain("contextLength=")
                    .doesNotContain("abendContext=")
                    .doesNotContain(TERMINAL_MESSAGE);

            String image = raised.toFixedWidthContext();

            // The width is asserted twice: once against the published constant and once
            // against the sum of the four field widths declared by the abend copybook, so
            // the two can never drift apart unnoticed.
            assertThat(image).hasSize(AbendException.CONTEXT_LENGTH);
            assertThat(image).hasSize(AbendException.CODE_LENGTH
                    + AbendException.CULPRIT_LENGTH
                    + AbendException.REASON_LENGTH
                    + AbendException.MESSAGE_LENGTH);

            // Each component occupies its own fixed slice, in declaration order.
            assertThat(image).startsWith(AbendException.ONLINE_ABEND_CODE);
            assertThat(image.substring(AbendException.CODE_LENGTH,
                    AbendException.CODE_LENGTH + AbendException.CULPRIT_LENGTH))
                    .isEqualTo(ONLINE_PROGRAM);
            assertThat(image).contains(REASON).contains(TERMINAL_MESSAGE);
        }

        @Test
        @DisplayName("never emits a context image on the batch path, even at debug, because only"
                + " the online family registered an abend handler")
        void theBatchPathEmitsNoContextImageEvenAtDebug() {
            logger.setLevel(Level.DEBUG);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, REASON));

            List<ILoggingEvent> events = List.copyOf(recorder.list);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getLevel()).isEqualTo(Level.ERROR);
            assertThat(events)
                    .as("the context image belongs to the online abend path alone")
                    .noneMatch(event -> event.getLevel() == Level.DEBUG);
        }
    }
}
