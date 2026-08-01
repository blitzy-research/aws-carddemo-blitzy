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

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies {@link AbendService}, the substitution for the legacy abend path.
 *
 * <p><strong>What the legacy authority is.</strong> Two constructs converge on this service.
 * The batch tier calls {@code CEE3ABD} at nine sites, always after emitting a diagnostic; a
 * representative pair is {@code app/cbl/CBACT01C.cbl} lines 90-114, where the program displays the
 * failing operation and the raw two-byte file status and only then abends. The online tier uses
 * {@code EXEC CICS ABEND} in the five-program family that includes {@code app/cbl/COACTUPC.cbl},
 * whose abend routine at line 4206 supplies the operator message this service falls back to. The
 * fixed-width context the service carries is the {@code ABEND-DATA} group of
 * {@code app/cpy/CSMSG02Y.cpy}: a four-character code, an eight-character culprit, a fifty-character
 * reason and a seventy-two-character message, 134 bytes in total.
 *
 * <p><strong>The two properties that matter most.</strong> First, <em>diagnose then raise</em>: the
 * legacy always emits its display before transferring control, because once the abend has happened
 * the program is gone and an operator has only the job log. A translation that threw first and
 * logged in a handler would lose the raw status for any caller that does not catch. This class
 * asserts the ordering directly, by capturing log events and checking that the diagnostic is present
 * at the moment the exception surfaces.
 *
 * <p>Second, <em>a non-error status is a caller bug, not an abend</em>. The all-clear code and the
 * end-of-file code both reach this service only through a mis-wired caller, and the service refuses
 * them with an {@link IllegalArgumentException} rather than abending a healthy program. That
 * refusal is what keeps the end-of-file arm of a read loop from being mistaken for a failure.
 *
 * <p><strong>A coverage-instrument artefact, recorded so it is not mistaken for a gap.</strong>
 * Six lines of the service are reported as uncovered: the single delegating statement and the
 * closing brace of each of the three convenience overloads that forward to a form which always
 * throws. Those lines are executed by this class, but a coverage probe placed at a method's normal
 * exit can never fire in a method that has no normal exit, so the instrument cannot record them. The
 * bodies are therefore proven by mutation instead: transposing the operation and resource arguments
 * in the file-status delegate, substituting a non-default operator message in the online delegate,
 * and injecting I/O context into the two-argument batch delegate each break assertions in this
 * class, which is only possible if the delegating statements run.
 */
@DisplayName("AbendService — diagnose, then raise")
class AbendServiceParityTest {

    /** A batch program name, within the eight-character {@code ABEND-CULPRIT} width. */
    private static final String BATCH_PROGRAM = "CBACT01C";

    /** An online program name from the family that uses the CICS abend handler. */
    private static final String ONLINE_PROGRAM = "COACTUPC";

    /** A reason within the fifty-character {@code ABEND-REASON} width. */
    private static final String REASON = "UNRECOVERABLE READ FAILURE";

    /** The DD name of the file an operation acted on. */
    private static final String RESOURCE = "ACCTFILE";

    /** A legacy I/O verb, as the batch programs spell it in their displays. */
    private static final String OPERATION = "READING";

    /** The marker the service substitutes for an argument that was not supplied. */
    private static final String NOT_SUPPLIED = "(none)";

    /** The marker the service substitutes for a status outside the declared vocabulary. */
    private static final String OUTSIDE_VOCABULARY = "(outside declared vocabulary)";

    /** The banner every abend diagnostic opens with. */
    private static final String BANNER = "ABENDING PROGRAM";

    /** The service under test. It injects no collaborator and holds no state. */
    private final AbendService service = new AbendService();

    /** Captures the events the service publishes so the diagnostic can be inspected. */
    private ListAppender<ILoggingEvent> captured;

    /** The logger the appender is attached to. */
    private ch.qos.logback.classic.Logger serviceLogger;

    /** The level the logger carried before the test lowered it. */
    private Level restoreLevel;

    @BeforeEach
    void attachAppender() {
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.serviceLogger = context.getLogger(AbendService.class);
        this.restoreLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.DEBUG);
        this.captured = new ListAppender<>();
        this.captured.setContext(context);
        this.captured.start();
        this.serviceLogger.addAppender(this.captured);
    }

    @AfterEach
    void detachAppender() {
        if (this.serviceLogger != null && this.captured != null) {
            this.serviceLogger.detachAppender(this.captured);
            this.captured.stop();
            this.serviceLogger.setLevel(this.restoreLevel);
        }
    }

    /**
     * Renders every captured event as its formatted message.
     *
     * @return the diagnostics the service emitted, in order
     */
    private List<String> diagnostics() {
        return this.captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Renders the captured events emitted at the error level.
     *
     * @return the error-level diagnostics, in order
     */
    private List<String> errorDiagnostics() {
        return this.captured.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * Returns the single error-level diagnostic the service emitted.
     *
     * @return the sole diagnostic, having first asserted that exactly one was emitted
     */
    private String soleErrorDiagnostic() {
        final List<String> emitted = errorDiagnostics();
        assertThat(emitted).hasSize(1);
        return emitted.get(0);
    }

    // THE BATCH ABEND

    /**
     * Verifies the batch abend, which stands in for {@code CALL 'CEE3ABD'}.
     *
     * <p>The abend code is the three-character batch value rather than the four-character online
     * one. That is not a rounding of the same number: the two tiers really do use different codes,
     * and an operator reading a job log distinguishes a batch abend from an online one by exactly
     * this field.
     */
    @Nested
    @DisplayName("abendBatch")
    class BatchAbend {

        @Test
        @DisplayName("the abend carries the batch code, the culprit and the reason")
        void theAbendCarriesTheLegacyFields() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, REASON))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.code()).isEqualTo("999");
                        assertThat(abend.culprit()).isEqualTo(BATCH_PROGRAM);
                        assertThat(abend.reason()).isEqualTo(REASON);
                        assertThat(abend.getMessage()).isEqualTo(AbendException.DEFAULT_MESSAGE);
                        assertThat(abend).hasNoCause();
                    });
        }

        @Test
        @DisplayName("the diagnostic is already emitted by the time the abend surfaces")
        void theDiagnosticPrecedesTheAbend() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, REASON));

            assertThat(errorDiagnostics()).singleElement()
                    .isEqualTo(BANNER + " abendCode=999 culprit=" + BATCH_PROGRAM
                            + " reason=" + REASON);
        }

        @Test
        @DisplayName("the five-argument form adds the operation, the file and the raw status")
        void theFiveArgumentFormAddsTheIoContext() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, REASON, "23", OPERATION,
                            RESOURCE));

            assertThat(errorDiagnostics()).singleElement()
                    .isEqualTo(BANNER + " abendCode=999 culprit=" + BATCH_PROGRAM
                            + " reason=" + REASON + " operation=" + OPERATION
                            + " resource=" + RESOURCE
                            + " fileStatus=23 statusName=RECORD_NOT_FOUND");
        }

        @Test
        @DisplayName("the raw status is translated to its mnemonic so a log reader need not decode it")
        void theStatusMnemonicAccompaniesTheRawCode() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, REASON, "35", OPERATION,
                            RESOURCE));

            assertThat(soleErrorDiagnostic())
                    .contains("fileStatus=35", "statusName=" + FileStatus.FILE_NOT_FOUND.name());
        }

        @Test
        @DisplayName("a status outside the declared vocabulary is named as such, never guessed")
        void anUnknownStatusIsNamedAsUnknown() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, REASON, "99", OPERATION,
                            RESOURCE));

            assertThat(soleErrorDiagnostic())
                    .contains("fileStatus=99", "statusName=" + OUTSIDE_VOCABULARY);
        }

        @Test
        @DisplayName("absent optional context is omitted rather than logged as an empty label")
        void absentContextIsOmitted() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, REASON, "  ", "  ", "  "));

            assertThat(soleErrorDiagnostic())
                    .doesNotContain("operation=", "resource=", "fileStatus=");
        }

        @Test
        @DisplayName("an absent culprit or reason is marked, so the diagnostic stays readable")
        void absentMandatoryContextIsMarked() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(null, null));

            assertThat(errorDiagnostics()).singleElement()
                    .isEqualTo(BANNER + " abendCode=999 culprit=" + NOT_SUPPLIED
                            + " reason=" + NOT_SUPPLIED);
        }

        @Test
        @DisplayName("an absent culprit and reason become empty fixed-width fields, not nulls")
        void absentFieldsBecomeEmptyStrings() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(null, null))
                    .satisfies(abend -> {
                        assertThat(abend.culprit()).isEmpty();
                        assertThat(abend.reason()).isEmpty();
                    });
        }

        @Test
        @DisplayName("a reason wider than the legacy field is refused rather than truncated silently")
        void anOverlongReasonIsRefused() {
            final String tooLong = "R".repeat(AbendException.REASON_LENGTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, tooLong))
                    .withMessage("reason exceeds legacy ABEND-REASON PIC X(50): length 51");
        }
    }

    // THE ONLINE ABEND

    /**
     * Verifies the online abend, which stands in for {@code EXEC CICS ABEND}.
     *
     * <p>The online tier differs in two respects. Its abend code is the four-character value, and it
     * carries a terminal message, because a 3270 operator sees text on a screen rather than a line in
     * a job log. When no message is supplied the service substitutes the legacy default rather than
     * leaving the field blank.
     */
    @Nested
    @DisplayName("abendOnline")
    class OnlineAbend {

        @Test
        @DisplayName("the abend carries the online code and the supplied terminal message")
        void theAbendCarriesTheOnlineCode() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(ONLINE_PROGRAM, REASON,
                            "UNABLE TO UPDATE ACCOUNT"))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.ONLINE_ABEND_CODE);
                        assertThat(abend.code()).isEqualTo("9999");
                        assertThat(abend.culprit()).isEqualTo(ONLINE_PROGRAM);
                        assertThat(abend.getMessage()).isEqualTo("UNABLE TO UPDATE ACCOUNT");
                    });
        }

        @Test
        @DisplayName("the two-argument form substitutes the legacy operator message")
        void theTwoArgumentFormSubstitutesTheDefaultMessage() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(ONLINE_PROGRAM, REASON))
                    .satisfies(abend -> assertThat(abend.getMessage())
                            .isEqualTo(AbendException.DEFAULT_MESSAGE)
                            .isEqualTo("UNEXPECTED ABEND OCCURRED."));
        }

        @Test
        @DisplayName("a blank terminal message is replaced rather than sent to the screen empty")
        void aBlankMessageIsReplaced() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(ONLINE_PROGRAM, REASON, "   "))
                    .satisfies(abend -> assertThat(abend.getMessage())
                            .isEqualTo(AbendException.DEFAULT_MESSAGE));
        }

        @Test
        @DisplayName("the diagnostic omits the I/O context, which the online tier does not carry")
        void theDiagnosticCarriesNoIoContext() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(ONLINE_PROGRAM, REASON));

            assertThat(errorDiagnostics()).singleElement()
                    .isEqualTo(BANNER + " abendCode=9999 culprit=" + ONLINE_PROGRAM
                            + " reason=" + REASON);
        }

        @Test
        @DisplayName("the 134-byte ABEND-DATA image is emitted alongside the diagnostic")
        void theFixedWidthContextImageIsEmitted() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(ONLINE_PROGRAM, REASON));

            assertThat(diagnostics()).anySatisfy(line ->
                    assertThat(line).contains("contextLength=" + AbendException.CONTEXT_LENGTH));
            assertThat(AbendException.CONTEXT_LENGTH).isEqualTo(134);
        }

        @Test
        @DisplayName("the emitted context image is exactly the legacy group width")
        void theContextImageIsExactlyOneHundredThirtyFourBytes() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(ONLINE_PROGRAM, REASON))
                    .satisfies(abend -> assertThat(abend.toFixedWidthContext())
                            .hasSize(AbendException.CONTEXT_LENGTH)
                            .startsWith("9999" + ONLINE_PROGRAM));
        }
    }

    // THE DISPLAY-ONLY PATH

    /**
     * Verifies the display that precedes an abend but can also be issued on its own.
     *
     * <p>The legacy programs display the file status through a work field whose name gives this
     * method its prefix, and they do so on paths that continue as well as on paths that abend. The
     * method therefore never throws.
     */
    @Nested
    @DisplayName("displayIoStatus")
    class DisplayOnly {

        @Test
        @DisplayName("the display carries the legacy prefix, the raw code and its mnemonic")
        void theDisplayCarriesTheLegacyPrefix() {
            service.displayIoStatus("23", OPERATION, RESOURCE);

            assertThat(errorDiagnostics()).singleElement()
                    .isEqualTo(FileStatusException.DISPLAY_PREFIX
                            + " fileStatus=23 statusName=RECORD_NOT_FOUND operation=" + OPERATION
                            + " resource=" + RESOURCE);
        }

        @Test
        @DisplayName("the method never throws, because the legacy display never transfers control")
        void theDisplayNeverThrows() {
            service.displayIoStatus(null, null, null);
            service.displayIoStatus("00", OPERATION, RESOURCE);
            service.displayIoStatus("10", OPERATION, RESOURCE);

            assertThat(errorDiagnostics()).hasSize(3);
        }

        @Test
        @DisplayName("an absent status is marked in both the code and the mnemonic position")
        void anAbsentStatusIsMarkedTwice() {
            service.displayIoStatus(null, null, null);

            assertThat(errorDiagnostics()).singleElement()
                    .isEqualTo(FileStatusException.DISPLAY_PREFIX
                            + " fileStatus=" + NOT_SUPPLIED + " statusName=" + NOT_SUPPLIED
                            + " operation=" + NOT_SUPPLIED + " resource=" + NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a blank status is treated as absent for the code but resolved for the mnemonic")
        void aBlankStatusIsMarkedButStillResolved() {
            service.displayIoStatus("  ", OPERATION, RESOURCE);

            assertThat(soleErrorDiagnostic())
                    .contains("fileStatus=" + NOT_SUPPLIED, "statusName=" + OUTSIDE_VOCABULARY);
        }

        @Test
        @DisplayName("the all-clear and end-of-file codes are displayed, not rejected")
        void theNonErrorCodesAreDisplayable() {
            service.displayIoStatus(FileStatus.SUCCESS.getCode(), OPERATION, RESOURCE);
            service.displayIoStatus(FileStatus.END_OF_FILE.getCode(), OPERATION, RESOURCE);

            assertThat(errorDiagnostics())
                    .anySatisfy(line -> assertThat(line).contains("statusName=SUCCESS"))
                    .anySatisfy(line -> assertThat(line).contains("statusName=END_OF_FILE"));
        }
    }

    // THE FILE-STATUS ABEND

    /**
     * Verifies the abend driven by a file status, in both of its forms.
     *
     * <p>The four-argument form validates before it acts, which is the interesting half. Two codes
     * must never reach an abend: the all-clear, because abending a successful operation would be a
     * fabricated failure, and end of file, because that is how every sequential read loop in the
     * estate terminates normally. Both are rejected with an {@link IllegalArgumentException} — and
     * the rejection is itself logged first, so a mis-wired caller leaves a trail.
     */
    @Nested
    @DisplayName("abendOnFileStatus")
    class FileStatusAbend {

        @Test
        @DisplayName("an error status abends, with the reason naming the raw code")
        void anErrorStatusAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, "23", OPERATION,
                            RESOURCE))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(BATCH_PROGRAM);
                        assertThat(abend.reason()).isEqualTo("FILE STATUS 23");
                        assertThat(abend.getMessage()).isEqualTo(AbendException.DEFAULT_MESSAGE);
                    });
        }

        @Test
        @DisplayName("the originating file failure is retained as the cause")
        void theFileFailureIsRetainedAsTheCause() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, "31", OPERATION,
                            RESOURCE))
                    .satisfies(abend -> {
                        assertThat(abend.getCause()).isInstanceOf(FileStatusException.class);
                        final FileStatusException cause = (FileStatusException) abend.getCause();
                        assertThat(cause.code()).isEqualTo("31");
                        assertThat(cause.operation()).isEqualTo(OPERATION);
                        assertThat(cause.resourceName()).isEqualTo(RESOURCE);
                    });
        }

        @Test
        @DisplayName("the diagnostic carries the full I/O context before the abend surfaces")
        void theDiagnosticCarriesTheIoContext() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, "23", OPERATION,
                            RESOURCE));

            assertThat(errorDiagnostics()).singleElement()
                    .isEqualTo(BANNER + " abendCode=999 culprit=" + BATCH_PROGRAM
                            + " reason=FILE STATUS 23 operation=" + OPERATION
                            + " resource=" + RESOURCE
                            + " fileStatus=23 statusName=RECORD_NOT_FOUND");
        }

        @Test
        @DisplayName("the all-clear code is refused, because success is not a failure")
        void theAllClearCodeIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, "00", OPERATION,
                            RESOURCE))
                    .withMessage("File status \"00\" reports a successful operation, which is not an"
                            + " error and must not abend.");
        }

        @Test
        @DisplayName("the end-of-file code is refused, because it is how a read loop ends")
        void theEndOfFileCodeIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, "10", OPERATION,
                            RESOURCE))
                    .withMessageContaining("reports end of file")
                    .withMessageContaining("handle it as the end-of-file arm instead");
        }

        @Test
        @DisplayName("a refusal is logged before it is raised, so a mis-wired caller leaves a trail")
        void aRefusalIsLoggedFirst() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, "00", OPERATION,
                            RESOURCE));

            assertThat(soleErrorDiagnostic())
                    .contains(BANNER, "culprit=" + BATCH_PROGRAM, "fileStatus=00",
                            "misuse=File status \"00\" reports a successful operation");
        }

        @Test
        @DisplayName("an absent status is refused with the width requirement stated")
        void anAbsentStatusIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, null, OPERATION,
                            RESOURCE))
                    .withMessage("A COBOL FILE STATUS of exactly 2 characters is required to abend"
                            + " on a file status, but null was supplied.");
        }

        @Test
        @DisplayName("a status of the wrong width is refused, naming the width it had")
        void aWrongWidthStatusIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, "023", OPERATION,
                            RESOURCE))
                    .withMessage("A COBOL FILE STATUS must be exactly 2 characters, but \"023\" has"
                            + " length 3.");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, "", OPERATION,
                            RESOURCE))
                    .withMessageContaining("has length 0");
        }

        @Test
        @DisplayName("a status outside the vocabulary still abends — the width is what is policed")
        void anUnknownButWellFormedStatusStillAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, "99", OPERATION,
                            RESOURCE))
                    .satisfies(abend -> assertThat(abend.reason()).isEqualTo("FILE STATUS 99"));
        }

        @Test
        @DisplayName("the two-argument form abends on an already-built file failure")
        void theTwoArgumentFormAcceptsAFailure() {
            final FileStatusException failure = new FileStatusException("35", "OPENING", RESOURCE);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, failure))
                    .satisfies(abend -> {
                        assertThat(abend.reason()).isEqualTo("FILE STATUS 35");
                        assertThat(abend.getCause()).isSameAs(failure);
                    });
        }

        @Test
        @DisplayName("an absent file failure is refused, and the refusal is logged first")
        void anAbsentFailureIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM,
                            (FileStatusException) null))
                    .withMessage("A FileStatusException is required to abend on a file status, but"
                            + " null was supplied.");

            assertThat(errorDiagnostics()).singleElement()
                    .isEqualTo(BANNER + " culprit=" + BATCH_PROGRAM
                            + " reason=ABEND REQUESTED WITH NO FILE FAILURE SUPPLIED");
        }

        @Test
        @DisplayName("an absent culprit alongside an absent failure is marked, not printed as null")
        void anAbsentCulpritIsMarkedOnTheRefusalPath() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(null, (FileStatusException) null));

            assertThat(soleErrorDiagnostic())
                    .contains("culprit=" + NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a failure carrying no operation or resource still abends cleanly")
        void aFailureWithoutContextStillAbends() {
            final FileStatusException bare = new FileStatusException("22", null, null);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, bare));

            assertThat(soleErrorDiagnostic())
                    .doesNotContain("operation=", "resource=")
                    .contains("fileStatus=22 statusName=DUPLICATE_KEY");
        }
    }

    // DIAGNOSTIC SUPPRESSION

    /**
     * Verifies that suppressing the diagnostic channel never suppresses the abend itself.
     *
     * <p>The legacy programs display before they abend, and the display is unconditional: a
     * {@code DISPLAY} statement has no level to be switched off. The Java translation routes the
     * display through SLF4J, which introduces a level check the COBOL never had, so the check has to
     * be proven harmless. What matters is that the abend is the contract and the diagnostic is the
     * courtesy: a deployment that silences the logger must still lose the run, not continue past a
     * failure it can no longer see.</p>
     */
    @Nested
    @DisplayName("with the diagnostic channel silenced")
    class DiagnosticSuppression {

        @Test
        @DisplayName("the batch abend still flies when the error level is disabled")
        void theBatchAbendStillFliesWithErrorDisabled() {
            serviceLogger.setLevel(Level.OFF);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(BATCH_PROGRAM, REASON))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(BATCH_PROGRAM);
                        assertThat(abend.reason()).isEqualTo(REASON);
                    });

            assertThat(diagnostics()).isEmpty();
        }

        @Test
        @DisplayName("the file-status abend still flies when the error level is disabled")
        void theFileStatusAbendStillFliesWithErrorDisabled() {
            serviceLogger.setLevel(Level.OFF);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM, "23", OPERATION,
                            RESOURCE))
                    .withCauseInstanceOf(FileStatusException.class);

            assertThat(diagnostics()).isEmpty();
        }

        @Test
        @DisplayName("a mis-wired caller is still refused when the error level is disabled")
        void aMisWiredCallerIsStillRefusedWithErrorDisabled() {
            serviceLogger.setLevel(Level.OFF);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(BATCH_PROGRAM,
                            FileStatusException.STATUS_END_OF_FILE, OPERATION, RESOURCE));

            assertThat(diagnostics()).isEmpty();
        }

        @Test
        @DisplayName("the status display emits nothing when the error level is disabled")
        void theStatusDisplayEmitsNothingWithErrorDisabled() {
            serviceLogger.setLevel(Level.OFF);

            service.displayIoStatus("23", OPERATION, RESOURCE);

            assertThat(diagnostics()).isEmpty();
        }

        @Test
        @DisplayName("the online abend keeps its diagnostic but drops the context image when debug "
                + "is disabled")
        void theContextImageIsWithheldWithDebugDisabled() {
            serviceLogger.setLevel(Level.ERROR);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(ONLINE_PROGRAM, REASON));

            assertThat(soleErrorDiagnostic()).startsWith(BANNER);
            assertThat(diagnostics()).noneMatch(line -> line.contains("abendContext="));
        }
    }
}
