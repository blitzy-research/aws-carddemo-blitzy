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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Exercises the single abend path of the migrated estate.
 *
 * <h2>What is under test</h2>
 * {@link AbendService} stands in for two legacy terminal-failure mechanisms that behave identically
 * from a caller's point of view: the nine batch calls to the Language Environment abort routine
 * {@code CEE3ABD}, and the four online CICS {@code ABEND} commands. Both emit an operator diagnostic
 * and then destroy the enclave, so neither ever returns to its caller. Every method of this service
 * that stands in for one of them therefore throws, and the tests assert that it throws rather than
 * that it returns a status, because a returning abend would be a behavioural regression that no
 * amount of correct diagnostic text could compensate for.
 *
 * <p>Two methods deliberately do not throw and are tested for exactly that. {@code displayIoStatus}
 * reproduces the legacy {@code DISPLAY 'FILE STATUS IS: NNNN'} paragraph, which announces a status
 * and falls through to whatever the program does next; and the rejection guard turns a caller defect
 * — asking to abend on a status that reports success or end of file — into an
 * {@link IllegalArgumentException}, which is a programming error and not an abend.</p>
 *
 * <h2>Why the emitted diagnostic is asserted, not merely allowed</h2>
 * In the legacy system the operator diagnostic <em>is</em> the failure report: there was no other
 * channel, and a batch failure was diagnosed from the job log alone. The composed line is therefore
 * treated here as an observable contract and asserted character for character, including the two
 * places where a field is omitted entirely when it was not supplied and the marker that stands in
 * for a field that was supplied blank. A log line assembled by conditional appends has a branch per
 * optional field, and each of those branches changes what an operator sees.
 *
 * <h2>Why the logger level is manipulated</h2>
 * Two behaviours are level-gated and cannot be observed at a single fixed level. The diagnostic
 * assembly is skipped wholesale when error logging is disabled, which is a deliberate cost
 * avoidance on a path that runs while a program is dying; and the 134-byte fixed-width context image
 * is emitted only at debug level, because it restates in one line what the structured fields already
 * carry and is useful only to someone reading the legacy layout. Both are driven here by setting the
 * level explicitly rather than by relying on whatever the ambient configuration happens to be.
 *
 * <p>Provenance: the legacy authorities are {@code app/cpy/CSMSG02Y.cpy}, which declares the
 * 134-byte {@code ABEND-DATA} context, and the batch and online abend call sites, at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.</p>
 */
@DisplayName("AbendService - the CEE3ABD and CICS ABEND terminal failure path")
class AbendServiceBoundaryTest {

    /** Legacy abend code carried by every batch abend, from the three-character batch user code. */
    private static final String BATCH_CODE = "999";

    /** Legacy abend code carried by every online abend, from the four-character CICS abend code. */
    private static final String ONLINE_CODE = "9999";

    /** Program name standing in for the legacy culprit field, within its eight-byte width. */
    private static final String PROGRAM = "CBACT01C";

    /** Data set name standing in for the legacy resource under which the failure was raised. */
    private static final String RESOURCE = "ACCTFILE";

    /** Legacy gerund for a read, as the read operation reports itself. */
    private static final String OPERATION = "READING";

    /** Reason text, well within the fifty-character legacy reason field. */
    private static final String REASON = "ACCOUNT MASTER UNAVAILABLE";

    /** Marker the service substitutes for a field that was not supplied. */
    private static final String NOT_SUPPLIED = "(none)";

    /** Marker the service substitutes for a status outside the declared status vocabulary. */
    private static final String OUTSIDE_VOCABULARY = "(outside declared vocabulary)";

    /** Fixed prefix of every diagnostic line, from the legacy display literal. */
    private static final String ABENDING_PROGRAM = "ABENDING PROGRAM";

    /** Status reporting a successful operation, which must never abend. */
    private static final String STATUS_OK = "00";

    /** Status reporting end of file, which terminates a read loop normally. */
    private static final String STATUS_END_OF_FILE = "10";

    /** Status reporting that a keyed read found no record, which is a genuine failure. */
    private static final String STATUS_NOT_FOUND = "23";

    /** Two-character status that the declared status vocabulary does not name. */
    private static final String STATUS_UNDECLARED = "99";

    /** Status reporting a permanent input-output error, which is a genuine failure. */
    private static final String STATUS_PERMANENT_ERROR = "31";

    /** Width of a COBOL file status, in characters. */
    private static final int STATUS_WIDTH = 2;

    /** Sum of the four legacy abend context field widths: 4 + 8 + 50 + 72. */
    private static final int CONTEXT_WIDTH = 134;

    /** The service under test; it holds no state, so one instance per test method suffices. */
    private AbendService service;

    /** Captures the events the service logs so the composed diagnostic can be asserted. */
    private ListAppender<ILoggingEvent> appender;

    /** The service's own logger, retained so its level can be restored after each test. */
    private Logger logger;

    /** Level the logger carried before a test changed it, restored afterwards. */
    private Level originalLevel;

    /**
     * Attaches a capturing appender to the service's logger and pins the level to debug.
     *
     * <p>Debug is the most permissive level the service reacts to, so pinning it here means every
     * emission path runs unless a test narrows the level deliberately. The level is captured first so
     * that {@link #detachAppender()} can put it back and leave no cross-test influence.</p>
     */
    @BeforeEach
    void attachAppender() {
        service = new AbendService();
        logger = (Logger) LoggerFactory.getLogger(AbendService.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    /** Removes the capturing appender and restores the level the logger carried beforehand. */
    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    /**
     * Returns the formatted messages of every captured event, in emission order.
     *
     * @return the rendered log lines
     */
    private List<String> capturedLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Returns the single formatted message captured at error level.
     *
     * @return the rendered error line
     */
    private String capturedErrorLine() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no error event was captured"));
    }

    @Nested
    @DisplayName("batch abend - stands in for CALL 'CEE3ABD'")
    class BatchAbend {

        @Test
        @DisplayName("the two-argument form raises with the batch abend code and default message")
        void twoArgumentFormRaisesWithBatchCode() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(PROGRAM, REASON))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(BATCH_CODE);
                        assertThat(abend.culprit()).isEqualTo(PROGRAM);
                        assertThat(abend.reason()).isEqualTo(REASON);
                        assertThat(abend.getMessage()).isEqualTo(AbendException.DEFAULT_MESSAGE);
                        assertThat(abend.getCause()).isNull();
                    });
        }

        @Test
        @DisplayName("the two-argument form emits the diagnostic without an optional field")
        void twoArgumentFormEmitsDiagnosticWithoutOptionalFields() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(PROGRAM, REASON));

            assertThat(capturedErrorLine())
                    .isEqualTo(ABENDING_PROGRAM + " abendCode=" + BATCH_CODE
                            + " culprit=" + PROGRAM + " reason=" + REASON);
        }

        @Test
        @DisplayName("the five-argument form appends operation, resource and status to the diagnostic")
        void fiveArgumentFormAppendsEveryOptionalField() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(
                            PROGRAM, REASON, STATUS_NOT_FOUND, OPERATION, RESOURCE));

            assertThat(capturedErrorLine())
                    .isEqualTo(ABENDING_PROGRAM + " abendCode=" + BATCH_CODE
                            + " culprit=" + PROGRAM + " reason=" + REASON
                            + " operation=" + OPERATION + " resource=" + RESOURCE
                            + " fileStatus=" + STATUS_NOT_FOUND + " statusName=RECORD_NOT_FOUND");
        }

        @Test
        @DisplayName("the five-argument form still raises the batch abend, not a status exception")
        void fiveArgumentFormStillRaisesTheBatchAbend() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(
                            PROGRAM, REASON, STATUS_NOT_FOUND, OPERATION, RESOURCE))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(BATCH_CODE);
                        assertThat(abend.getCause()).isNull();
                    });
        }

        @Test
        @DisplayName("an omitted operation and resource are left out of the diagnostic entirely")
        void omittedOperationAndResourceAreLeftOut() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(
                            PROGRAM, REASON, STATUS_NOT_FOUND, null, null));

            assertThat(capturedErrorLine())
                    .doesNotContain(" operation=")
                    .doesNotContain(" resource=")
                    .contains(" fileStatus=" + STATUS_NOT_FOUND);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("a blank operation is treated as not supplied and omitted")
        void blankOperationIsOmitted(String blankOperation) {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(
                            PROGRAM, REASON, STATUS_NOT_FOUND, blankOperation, RESOURCE));

            assertThat(capturedErrorLine()).doesNotContain(" operation=");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("a blank file status suppresses both the status and its mnemonic")
        void blankFileStatusSuppressesBothStatusFields(String blankStatus) {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(
                            PROGRAM, REASON, blankStatus, OPERATION, RESOURCE));

            assertThat(capturedErrorLine())
                    .doesNotContain(" fileStatus=")
                    .doesNotContain(" statusName=");
        }

        @Test
        @DisplayName("an absent program name is reported by the not-supplied marker")
        void absentProgramNameIsReportedByTheMarker() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(null, REASON));

            assertThat(capturedErrorLine()).contains(" culprit=" + NOT_SUPPLIED);
        }

        @Test
        @DisplayName("an absent reason is reported by the not-supplied marker")
        void absentReasonIsReportedByTheMarker() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(PROGRAM, null));

            assertThat(capturedErrorLine()).contains(" reason=" + NOT_SUPPLIED);
        }

        @Test
        @DisplayName("the batch path emits no context image, which is the online path's diagnostic")
        void theBatchPathEmitsNoContextImage() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(PROGRAM, REASON));

            assertThat(capturedLines()).noneMatch(line -> line.contains("abendContext="));
        }
    }

    @Nested
    @DisplayName("online abend - stands in for EXEC CICS ABEND")
    class OnlineAbend {

        /** Terminal text a transaction places on the screen before the abend takes the task down. */
        private static final String TERMINAL_MESSAGE = "PLEASE CONTACT SYSTEM ADMINISTRATOR.";

        @Test
        @DisplayName("the two-argument form raises with the online code and the default message")
        void twoArgumentFormRaisesWithOnlineCode() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(PROGRAM, REASON))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(ONLINE_CODE);
                        assertThat(abend.culprit()).isEqualTo(PROGRAM);
                        assertThat(abend.reason()).isEqualTo(REASON);
                        assertThat(abend.getMessage()).isEqualTo(AbendException.DEFAULT_MESSAGE);
                    });
        }

        @Test
        @DisplayName("the three-argument form carries the supplied terminal message")
        void threeArgumentFormCarriesTheTerminalMessage() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(PROGRAM, REASON, TERMINAL_MESSAGE))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(ONLINE_CODE);
                        assertThat(abend.getMessage()).isEqualTo(TERMINAL_MESSAGE);
                    });
        }

        @Test
        @DisplayName("the online diagnostic omits operation, resource and status, which it never has")
        void theOnlineDiagnosticOmitsTheFileFields() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(PROGRAM, REASON));

            assertThat(capturedErrorLine())
                    .isEqualTo(ABENDING_PROGRAM + " abendCode=" + ONLINE_CODE
                            + " culprit=" + PROGRAM + " reason=" + REASON);
        }

        @Test
        @DisplayName("the online path emits the 134-byte context image at debug level")
        void theOnlinePathEmitsTheContextImageAtDebug() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(PROGRAM, REASON, TERMINAL_MESSAGE));

            assertThat(capturedLines())
                    .anyMatch(line -> line.contains("abendContext=")
                            && line.contains("contextLength=" + CONTEXT_WIDTH));
        }

        @Test
        @DisplayName("the emitted context image is the exception's own fixed-width image")
        void theEmittedContextImageIsTheExceptionsOwnImage() {
            AbendException expected =
                    new AbendException(ONLINE_CODE, PROGRAM, REASON, TERMINAL_MESSAGE);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(PROGRAM, REASON, TERMINAL_MESSAGE));

            assertThat(capturedLines())
                    .anyMatch(line -> line.contains(expected.toFixedWidthContext()));
        }

        @Test
        @DisplayName("no context image is emitted when debug logging is switched off")
        void noContextImageWhenDebugIsOff() {
            logger.setLevel(Level.INFO);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(PROGRAM, REASON, TERMINAL_MESSAGE));

            assertThat(capturedLines()).noneMatch(line -> line.contains("abendContext="));
        }

        @Test
        @DisplayName("the abend still raises when every diagnostic level is switched off")
        void theAbendStillRaisesWhenLoggingIsOff() {
            logger.setLevel(Level.OFF);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(PROGRAM, REASON))
                    .satisfies(abend -> assertThat(abend.code()).isEqualTo(ONLINE_CODE));

            assertThat(capturedLines()).isEmpty();
        }

        @Test
        @DisplayName("an absent terminal message falls back to the default abend text")
        void anAbsentTerminalMessageFallsBackToTheDefault() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(PROGRAM, REASON, null))
                    .satisfies(abend ->
                            assertThat(abend.getMessage()).isEqualTo(AbendException.DEFAULT_MESSAGE));
        }
    }

    @Nested
    @DisplayName("status display - stands in for DISPLAY 'FILE STATUS IS: NNNN'")
    class StatusDisplay {

        @Test
        @DisplayName("announcing a status does not raise, because the legacy display fell through")
        void announcingAStatusDoesNotRaise() {
            service.displayIoStatus(STATUS_NOT_FOUND, OPERATION, RESOURCE);

            assertThat(capturedLines()).hasSize(1);
        }

        @Test
        @DisplayName("the announcement carries the legacy display prefix and every supplied field")
        void theAnnouncementCarriesThePrefixAndFields() {
            service.displayIoStatus(STATUS_NOT_FOUND, OPERATION, RESOURCE);

            assertThat(capturedErrorLine())
                    .isEqualTo(FileStatusException.DISPLAY_PREFIX
                            + " fileStatus=" + STATUS_NOT_FOUND
                            + " statusName=RECORD_NOT_FOUND"
                            + " operation=" + OPERATION
                            + " resource=" + RESOURCE);
        }

        @Test
        @DisplayName("a success status may be announced even though it must never abend")
        void aSuccessStatusMayBeAnnounced() {
            service.displayIoStatus(STATUS_OK, OPERATION, RESOURCE);

            assertThat(capturedErrorLine()).contains(" statusName=SUCCESS");
        }

        @Test
        @DisplayName("an absent field is announced by the not-supplied marker rather than omitted")
        void anAbsentFieldIsAnnouncedByTheMarker() {
            service.displayIoStatus(null, null, null);

            assertThat(capturedErrorLine())
                    .isEqualTo(FileStatusException.DISPLAY_PREFIX
                            + " fileStatus=" + NOT_SUPPLIED
                            + " statusName=" + NOT_SUPPLIED
                            + " operation=" + NOT_SUPPLIED
                            + " resource=" + NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a status outside the declared vocabulary is named as such")
        void aStatusOutsideTheVocabularyIsNamedAsSuch() {
            service.displayIoStatus(STATUS_UNDECLARED, OPERATION, RESOURCE);

            assertThat(capturedErrorLine()).contains(" statusName=" + OUTSIDE_VOCABULARY);
        }

        @Test
        @DisplayName("nothing is emitted when error logging is switched off")
        void nothingIsEmittedWhenErrorLoggingIsOff() {
            logger.setLevel(Level.OFF);

            service.displayIoStatus(STATUS_NOT_FOUND, OPERATION, RESOURCE);

            assertThat(capturedLines()).isEmpty();
        }
    }

    @Nested
    @DisplayName("file status abend - the failure that carries its own status")
    class FileStatusAbend {

        @Test
        @DisplayName("the raw-status form raises with the status as its reason and a status cause")
        void theRawStatusFormRaisesWithTheStatusAsItsReason() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(
                            PROGRAM, STATUS_NOT_FOUND, OPERATION, RESOURCE))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(BATCH_CODE);
                        assertThat(abend.culprit()).isEqualTo(PROGRAM);
                        assertThat(abend.reason()).isEqualTo("FILE STATUS " + STATUS_NOT_FOUND);
                        assertThat(abend.getMessage()).isEqualTo(AbendException.DEFAULT_MESSAGE);
                        assertThat(abend.getCause()).isInstanceOf(FileStatusException.class);
                    });
        }

        @Test
        @DisplayName("the cause carries the status, operation and resource it was raised for")
        void theCauseCarriesTheStatusOperationAndResource() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(
                            PROGRAM, STATUS_NOT_FOUND, OPERATION, RESOURCE))
                    .satisfies(abend -> {
                        FileStatusException cause = (FileStatusException) abend.getCause();
                        assertThat(cause.code()).isEqualTo(STATUS_NOT_FOUND);
                        assertThat(cause.operation()).isEqualTo(OPERATION);
                        assertThat(cause.resourceName()).isEqualTo(RESOURCE);
                    });
        }

        @Test
        @DisplayName("the raw-status form emits the full diagnostic including the status mnemonic")
        void theRawStatusFormEmitsTheFullDiagnostic() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(
                            PROGRAM, STATUS_NOT_FOUND, OPERATION, RESOURCE));

            assertThat(capturedErrorLine())
                    .isEqualTo(ABENDING_PROGRAM + " abendCode=" + BATCH_CODE
                            + " culprit=" + PROGRAM
                            + " reason=FILE STATUS " + STATUS_NOT_FOUND
                            + " operation=" + OPERATION + " resource=" + RESOURCE
                            + " fileStatus=" + STATUS_NOT_FOUND + " statusName=RECORD_NOT_FOUND");
        }

        @Test
        @DisplayName("the failure form reuses the supplied exception as the abend cause")
        void theFailureFormReusesTheSuppliedException() {
            FileStatusException failure =
                    new FileStatusException(STATUS_NOT_FOUND, OPERATION, RESOURCE);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, failure))
                    .satisfies(abend -> assertThat(abend.getCause()).isSameAs(failure));
        }

        @Test
        @DisplayName("a status outside the declared vocabulary still abends")
        void aStatusOutsideTheVocabularyStillAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(
                            PROGRAM, STATUS_UNDECLARED, OPERATION, RESOURCE))
                    .satisfies(abend ->
                            assertThat(abend.reason()).isEqualTo("FILE STATUS " + STATUS_UNDECLARED));

            assertThat(capturedErrorLine()).contains(" statusName=" + OUTSIDE_VOCABULARY);
        }

        @Test
        @DisplayName("a missing failure is a caller defect, not an abend")
        void aMissingFailureIsACallerDefect() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, null))
                    .withMessage("A FileStatusException is required to abend on a file status,"
                            + " but null was supplied.");
        }

        @Test
        @DisplayName("the missing-failure defect is reported with the culprit that requested it")
        void theMissingFailureDefectNamesTheCulprit() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, null));

            assertThat(capturedErrorLine())
                    .isEqualTo(ABENDING_PROGRAM + " culprit=" + PROGRAM
                            + " reason=ABEND REQUESTED WITH NO FILE FAILURE SUPPLIED");
        }

        @Test
        @DisplayName("the missing-failure defect reports an absent culprit by the marker")
        void theMissingFailureDefectReportsAnAbsentCulprit() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(null, null));

            assertThat(capturedErrorLine()).contains(" culprit=" + NOT_SUPPLIED);
        }
    }

    @Nested
    @DisplayName("rejection guard - a status that must never abend")
    class RejectionGuard {

        @Test
        @DisplayName("an absent status is rejected as a caller defect")
        void anAbsentStatusIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(
                            PROGRAM, null, OPERATION, RESOURCE))
                    .withMessage("A COBOL FILE STATUS of exactly " + STATUS_WIDTH
                            + " characters is required to abend on a file status, but null was"
                            + " supplied.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "0", "000", "0000"})
        @DisplayName("a status of the wrong width is rejected and its width is reported")
        void aStatusOfTheWrongWidthIsRejected(String malformed) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(
                            PROGRAM, malformed, OPERATION, RESOURCE))
                    .withMessage("A COBOL FILE STATUS must be exactly " + STATUS_WIDTH
                            + " characters, but \"" + malformed + "\" has length "
                            + malformed.length() + ".");
        }

        @Test
        @DisplayName("a success status is rejected because it is not an error")
        void aSuccessStatusIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(
                            PROGRAM, STATUS_OK, OPERATION, RESOURCE))
                    .withMessage("File status \"00\" reports a successful operation, which is not"
                            + " an error and must not abend.");
        }

        @Test
        @DisplayName("an end-of-file status is rejected because a read loop ends on it normally")
        void anEndOfFileStatusIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(
                            PROGRAM, STATUS_END_OF_FILE, OPERATION, RESOURCE))
                    .withMessage("File status \"10\" reports end of file, which is how a sequential"
                            + " read loop terminates normally and must not abend; handle it as the"
                            + " end-of-file arm instead.");
        }

        @Test
        @DisplayName("a rejected request is reported as a misuse, not as an abend diagnostic")
        void aRejectedRequestIsReportedAsAMisuse() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(
                            PROGRAM, STATUS_END_OF_FILE, OPERATION, RESOURCE));

            assertThat(capturedErrorLine())
                    .isEqualTo(ABENDING_PROGRAM + " culprit=" + PROGRAM
                            + " operation=" + OPERATION + " resource=" + RESOURCE
                            + " fileStatus=" + STATUS_END_OF_FILE
                            + " misuse=File status \"10\" reports end of file, which is how a"
                            + " sequential read loop terminates normally and must not abend;"
                            + " handle it as the end-of-file arm instead.")
                    .doesNotContain(" abendCode=");
        }

        @Test
        @DisplayName("a rejected request reports an absent operation and resource by the marker")
        void aRejectedRequestReportsAbsentFieldsByTheMarker() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, STATUS_OK, null, null));

            assertThat(capturedErrorLine())
                    .contains(" operation=" + NOT_SUPPLIED)
                    .contains(" resource=" + NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a rejected request is still rejected when logging is switched off")
        void aRejectedRequestIsStillRejectedWhenLoggingIsOff() {
            logger.setLevel(Level.OFF);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(
                            PROGRAM, STATUS_OK, OPERATION, RESOURCE));

            assertThat(capturedLines()).isEmpty();
        }

        @Test
        @DisplayName("a genuine error status passes the guard rather than being rejected")
        void aGenuineErrorStatusPassesTheGuard() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(
                            PROGRAM, STATUS_NOT_FOUND, OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("a non-error status cannot even be wrapped, so the failure form never sees one")
        void aNonErrorStatusCannotEvenBeWrapped() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FileStatusException(STATUS_OK, OPERATION, RESOURCE));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            new FileStatusException(STATUS_END_OF_FILE, OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("the failure form derives its reason from the failure's own status")
        void theFailureFormDerivesItsReasonFromTheFailure() {
            FileStatusException permanentError =
                    new FileStatusException(STATUS_PERMANENT_ERROR, OPERATION, RESOURCE);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, permanentError))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("FILE STATUS " + STATUS_PERMANENT_ERROR));

            assertThat(capturedErrorLine()).contains(" statusName=PERMANENT_ERROR");
        }
    }
}
