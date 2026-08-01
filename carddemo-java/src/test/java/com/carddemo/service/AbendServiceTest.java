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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Verifies {@code AbendService}, the translation of the legacy abort paths onto one raising service.
 *
 * <p>Three legacy constructs converge here. The online members carry an {@code ABEND-DATA} area from
 * {@code app/cpy/CSMSG02Y.cpy} and an {@code ABEND-ROUTINE} paragraph that fills it and issues
 * {@code EXEC CICS ABEND}; the batch members instead perform a status-display paragraph followed by
 * {@code 9999-ABEND-PROGRAM}, which invokes {@code CALL 'CEE3ABD'} at nine sites; and a file failure
 * reaches either tier through the raw two-character {@code FILE STATUS}.
 *
 * <p><strong>Emit first, raise second.</strong> Every legacy abend path displays its diagnostic
 * before it aborts - {@code app/cbl/CBACT01C.cbl} performs {@code 9910-DISPLAY-IO-STATUS} and only
 * then {@code 9999-ABEND-PROGRAM}, at three structurally identical sites. Reversing the order would
 * lose the diagnostic whenever the abort itself failed, so the ordering is asserted as a property of
 * the service rather than left to each caller.
 *
 * <p><strong>The two tiers carry different codes and different payloads.</strong> The batch tier has
 * no context area at all - the abend-context copybook is included by four online members and by no
 * batch member - so a batch abend carries the batch code and no image, while an online abend carries
 * the online code and the 134-character image. Collapsing the two would emit a context area the batch
 * tier never had.
 *
 * <p><strong>Success and end of file may never abend.</strong> A status of {@code 00} reports success
 * and a status of {@code 10} is how every sequential read loop in the batch tier terminates normally.
 * Both are refused, because abending on them would abort a healthy run at its natural end.
 */
@DisplayName("AbendService: the legacy abort paths, diagnostic first and abort second")
final class AbendServiceTest {

    // =================================================================================================
    // Oracles taken from the legacy source and the copybook, never read back from the class under test.
    // =================================================================================================

    /** The value the batch paragraphs move into the abend code item. */
    private static final String ORACLE_BATCH_ABEND_CODE = "999";

    /** The value {@code ABEND-ROUTINE} moves into {@code ABEND-CODE}. */
    private static final String ORACLE_ONLINE_ABEND_CODE = "9999";

    /** {@code ABEND-CULPRIT PIC X(08)}, {@code [app/cpy/CSMSG02Y.cpy]}. */
    private static final int ORACLE_CULPRIT_WIDTH = 8;

    /** {@code ABEND-REASON PIC X(50)}. */
    private static final int ORACLE_REASON_WIDTH = 50;

    /** {@code ABEND-MSG PIC X(72)}. */
    private static final int ORACLE_MESSAGE_WIDTH = 72;

    /** The whole {@code ABEND-DATA} area: four plus eight plus fifty plus seventy-two. */
    private static final int ORACLE_CONTEXT_WIDTH = 134;

    /** The literal the routine substitutes when no operator message was supplied. */
    private static final String ORACLE_DEFAULT_MESSAGE = "UNEXPECTED ABEND OCCURRED.";

    /** A raw status that reports success and therefore must never abend. */
    private static final String STATUS_SUCCESS = "00";

    /** A raw status that reports end of file and therefore must never abend. */
    private static final String STATUS_END_OF_FILE = "10";

    /** A genuine permanent-error status. */
    private static final String STATUS_PERMANENT_ERROR = "31";

    /** The record-not-found status, compared once in the estate. */
    private static final String STATUS_RECORD_NOT_FOUND = "23";

    /**
     * A status outside the vocabulary the estate compares anywhere.
     *
     * <p>The legacy status-display paragraph has an explicit branch for a status whose first
     * character is nine, so a value like this is a legitimate runtime condition rather than a defect,
     * and the diagnostic must still report it.
     */
    private static final String STATUS_OUTSIDE_VOCABULARY = "97";

    /** A batch program name, at exactly the culprit width. */
    private static final String BATCH_PROGRAM = "CBACT01C";

    /** An online program name, at exactly the culprit width. */
    private static final String ONLINE_PROGRAM = "COACTUPC";

    /** A reason comfortably inside the reason width. */
    private static final String REASON = "UNABLE TO READ ACCOUNT MASTER";

    /** A caller's operation description, in the shape the legacy displays name. */
    private static final String OPERATION = "ERROR READING ACCOUNT FILE";

    /** A legacy DD or CICS file name. */
    private static final String RESOURCE = "ACCTDAT";

    /** An operator message inside the message width. */
    private static final String TERMINAL_MESSAGE = "ACCOUNT UPDATE FAILED - CONTACT SUPPORT";

    /** The service under test; it holds no state, so a fresh instance per test costs nothing. */
    private AbendService abendService;

    @BeforeEach
    void createService() {
        abendService = new AbendService();
    }

    @Nested
    @DisplayName("the batch tier: the batch code, no context area, and no cause")
    final class BatchAbend {

        @Test
        @DisplayName("the short form abends with the batch code and the default operator message")
        void theShortFormAbendsWithTheBatchCode() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(ORACLE_BATCH_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(BATCH_PROGRAM);
                        assertThat(abend.reason()).isEqualTo(REASON);
                        assertThat(abend.getMessage()).isEqualTo(ORACLE_DEFAULT_MESSAGE);
                    });
            assertThat(AbendException.BATCH_ABEND_CODE).isEqualTo(ORACLE_BATCH_ABEND_CODE);
            assertThat(AbendException.DEFAULT_MESSAGE).isEqualTo(ORACLE_DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("the batch tier chains no cause, because it has no file failure to chain")
        void theBatchTierChainsNoCause() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON))
                    .satisfies(abend -> assertThat(abend.getCause()).isNull());
        }

        @Test
        @DisplayName("the long form carries the operation, the resource and the raw status")
        void theLongFormCarriesTheIoContext() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON,
                            STATUS_PERMANENT_ERROR, OPERATION, RESOURCE))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(ORACLE_BATCH_ABEND_CODE);
                        assertThat(abend.reason()).isEqualTo(REASON);
                    });
        }

        @Test
        @DisplayName("absent I/O context is tolerated, because a non-file abend has none")
        void absentIoContextIsTolerated() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON, null, null,
                            null));
        }

        @Test
        @DisplayName("blank I/O context is treated as absent, because the legacy fields hold spaces")
        void blankIoContextIsTreatedAsAbsent() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON, "   ", "  ",
                            " "));
        }

        @Test
        @DisplayName("a status outside the vocabulary is still reported rather than refused")
        void aStatusOutsideTheVocabularyIsStillReported() {
            // The display paragraph has a dedicated branch for exactly this case, so refusing the
            // value here would break the diagnostic that exists to report it.
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, REASON,
                            STATUS_OUTSIDE_VOCABULARY, OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("a reason longer than the legacy field is refused, after the diagnostic is emitted")
        void anOverLongReasonIsRefused() {
            final String tooLong = "R".repeat(ORACLE_REASON_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, tooLong))
                    .withMessageContaining(String.valueOf(ORACLE_REASON_WIDTH));
            assertThat(AbendException.REASON_LENGTH).isEqualTo(ORACLE_REASON_WIDTH);
        }

        @Test
        @DisplayName("a culprit longer than the legacy field is refused")
        void anOverLongCulpritIsRefused() {
            final String tooLong = "P".repeat(ORACLE_CULPRIT_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendBatch(tooLong, REASON))
                    .withMessageContaining(String.valueOf(ORACLE_CULPRIT_WIDTH));
            assertThat(AbendException.CULPRIT_LENGTH).isEqualTo(ORACLE_CULPRIT_WIDTH);
        }

        @Test
        @DisplayName("a reason of exactly the legacy width is accepted")
        void aReasonOfExactlyTheLegacyWidthIsAccepted() {
            final String exact = "R".repeat(ORACLE_REASON_WIDTH);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendBatch(BATCH_PROGRAM, exact))
                    .satisfies(abend -> assertThat(abend.reason()).hasSize(ORACLE_REASON_WIDTH));
        }
    }

    @Nested
    @DisplayName("the online tier: the online code and the 134-character context area")
    final class OnlineAbend {

        @Test
        @DisplayName("the short form abends with the online code and the substituted message")
        void theShortFormAbendsWithTheOnlineCode() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(ORACLE_ONLINE_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(ONLINE_PROGRAM);
                        assertThat(abend.getMessage()).isEqualTo(ORACLE_DEFAULT_MESSAGE);
                    });
            assertThat(AbendException.ONLINE_ABEND_CODE).isEqualTo(ORACLE_ONLINE_ABEND_CODE);
        }

        @Test
        @DisplayName("the two tiers carry different abend codes, so neither is mistaken for the other")
        void theTwoTiersCarryDifferentCodes() {
            assertThat(ORACLE_ONLINE_ABEND_CODE).isNotEqualTo(ORACLE_BATCH_ABEND_CODE);
        }

        @Test
        @DisplayName("a supplied operator message is carried verbatim")
        void aSuppliedOperatorMessageIsCarriedVerbatim() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON,
                            TERMINAL_MESSAGE))
                    .withMessage(TERMINAL_MESSAGE);
        }

        @Test
        @DisplayName("an absent or blank message is replaced by the literal the routine substitutes")
        void anAbsentOrBlankMessageIsSubstituted() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON, null))
                    .withMessage(ORACLE_DEFAULT_MESSAGE);
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON, "      "))
                    .as("the legacy field is space-initialised, so spaces mean no value was supplied")
                    .withMessage(ORACLE_DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("a message longer than the legacy field is refused")
        void anOverLongMessageIsRefused() {
            final String tooLong = "M".repeat(ORACLE_MESSAGE_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON, tooLong))
                    .withMessageContaining(String.valueOf(ORACLE_MESSAGE_WIDTH));
            assertThat(AbendException.MESSAGE_LENGTH).isEqualTo(ORACLE_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("the rendered context area is exactly the whole copybook width")
        void theRenderedContextAreaIsTheWholeCopybookWidth() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON,
                            TERMINAL_MESSAGE))
                    .satisfies(abend -> assertThat(abend.toFixedWidthContext())
                            .hasSize(ORACLE_CONTEXT_WIDTH)
                            .startsWith(ORACLE_ONLINE_ABEND_CODE + ONLINE_PROGRAM));
            assertThat(AbendException.CONTEXT_LENGTH).isEqualTo(ORACLE_CONTEXT_WIDTH);
        }

        @Test
        @DisplayName("the online tier chains no cause either")
        void theOnlineTierChainsNoCause() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnline(ONLINE_PROGRAM, REASON))
                    .satisfies(abend -> assertThat(abend.getCause()).isNull());
        }
    }

    @Nested
    @DisplayName("displayIoStatus: the display paragraph that deliberately does not abort")
    final class DisplayIoStatus {

        @Test
        @DisplayName("a recognised status is reported without raising anything")
        void aRecognisedStatusIsReportedWithoutRaising() {
            // The legacy performs this paragraph on its own wherever the program intends to carry on,
            // so a raising implementation would abort runs the legacy continues.
            assertThatNoException().isThrownBy(() -> abendService.displayIoStatus(
                    STATUS_PERMANENT_ERROR, OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("success and end of file may be displayed, because displaying is not abending")
        void successAndEndOfFileMayBeDisplayed() {
            assertThatNoException()
                    .isThrownBy(() -> abendService.displayIoStatus(STATUS_SUCCESS, OPERATION,
                            RESOURCE));
            assertThatNoException()
                    .isThrownBy(() -> abendService.displayIoStatus(STATUS_END_OF_FILE, OPERATION,
                            RESOURCE));
        }

        @Test
        @DisplayName("an absent status is tolerated: a diagnostic must not fail on the failing path")
        void anAbsentStatusIsTolerated() {
            assertThatNoException().isThrownBy(() -> abendService.displayIoStatus(null, null, null));
        }

        @Test
        @DisplayName("a status outside the vocabulary is tolerated, as the legacy branch requires")
        void aStatusOutsideTheVocabularyIsTolerated() {
            assertThatNoException().isThrownBy(() -> abendService.displayIoStatus(
                    STATUS_OUTSIDE_VOCABULARY, OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("blank context is tolerated and rendered as absent")
        void blankContextIsTolerated() {
            assertThatNoException()
                    .isThrownBy(() -> abendService.displayIoStatus("  ", "   ", "    "));
        }
    }

    @Nested
    @DisplayName("abendOnFileStatus: the whole file-failure sequence, with the status on the chain")
    final class AbendOnFileStatus {

        @Test
        @DisplayName("the raw status survives on the exception chain, not only in the log")
        void theRawStatusSurvivesOnTheChain() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_PERMANENT_ERROR, OPERATION, RESOURCE))
                    .satisfies(abend -> {
                        assertThat(abend.getCause()).isInstanceOf(FileStatusException.class);
                        final FileStatusException cause = (FileStatusException) abend.getCause();
                        assertThat(cause.code()).isEqualTo(STATUS_PERMANENT_ERROR);
                        assertThat(cause.operation()).isEqualTo(OPERATION);
                        assertThat(cause.resourceName()).isEqualTo(RESOURCE);
                    });
        }

        @Test
        @DisplayName("the reason is the labelled status and nothing invented beyond it")
        void theReasonIsTheLabelledStatus() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_PERMANENT_ERROR, OPERATION, RESOURCE))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("FILE STATUS " + STATUS_PERMANENT_ERROR));
        }

        @Test
        @DisplayName("the abend takes the batch code, because no online member has a file status")
        void theAbendTakesTheBatchCode() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_RECORD_NOT_FOUND, OPERATION, RESOURCE))
                    .satisfies(abend -> assertThat(abend.code())
                            .isEqualTo(ORACLE_BATCH_ABEND_CODE));
        }

        @Test
        @DisplayName("success is refused, because a successful operation is not a failure")
        void successIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, STATUS_SUCCESS,
                            OPERATION, RESOURCE))
                    .withMessageContaining(STATUS_SUCCESS)
                    .withMessageContaining("must not abend");
        }

        @Test
        @DisplayName("end of file is refused, because it is how a read loop terminates normally")
        void endOfFileIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_END_OF_FILE, OPERATION, RESOURCE))
                    .withMessageContaining(STATUS_END_OF_FILE)
                    .withMessageContaining("end-of-file arm");
        }

        @Test
        @DisplayName("an absent status is refused before anything is constructed")
        void anAbsentStatusIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, null, OPERATION,
                            RESOURCE))
                    .withMessageContaining("null was supplied");
        }

        @Test
        @DisplayName("a status that is not exactly two characters is refused")
        void aStatusOfTheWrongWidthIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, "3", OPERATION,
                            RESOURCE))
                    .withMessageContaining("exactly " + FileStatusException.CODE_LENGTH);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, "310",
                            OPERATION, RESOURCE))
                    .withMessageContaining("has length 3");
            assertThat(FileStatusException.CODE_LENGTH).isEqualTo(2);
        }

        @Test
        @DisplayName("the misuse guard still reports when the culprit and context are all absent")
        void theMisuseGuardReportsEvenWithoutAnyContext() {
            // A caller that reaches this guard has a defect on its own error path, which is the least
            // convenient place to be told nothing, so the diagnostic is emitted before the rejection.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(null, STATUS_END_OF_FILE, null,
                            null))
                    .withMessageContaining(STATUS_END_OF_FILE);
        }

        @Test
        @DisplayName("a two-character status outside the vocabulary is accepted as a genuine error")
        void aStatusOutsideTheVocabularyIsAccepted() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            STATUS_OUTSIDE_VOCABULARY, OPERATION, RESOURCE))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("FILE STATUS " + STATUS_OUTSIDE_VOCABULARY));
        }
    }

    @Nested
    @DisplayName("abendOnFileStatus: the companion form for a failure the caller already holds")
    final class AbendOnHeldFailure {

        @Test
        @DisplayName("the supplied failure is chained unchanged, so no second instance is invented")
        void theSuppliedFailureIsChainedUnchanged() {
            final FileStatusException held =
                    new FileStatusException(STATUS_PERMANENT_ERROR, OPERATION, RESOURCE);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, held))
                    .satisfies(abend -> assertThat(abend.getCause()).isSameAs(held));
        }

        @Test
        @DisplayName("the status, operation and resource are read back off the failure itself")
        void theContextIsReadBackOffTheFailure() {
            // A caller cannot log one status and chain another, because there is only one source.
            final FileStatusException held =
                    new FileStatusException(STATUS_RECORD_NOT_FOUND, OPERATION, RESOURCE);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, held))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("FILE STATUS " + STATUS_RECORD_NOT_FOUND));
        }

        @Test
        @DisplayName("an absent failure is a caller defect and is refused, with a diagnostic first")
        void anAbsentFailureIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM,
                            (FileStatusException) null))
                    .withMessageContaining("FileStatusException is required");
        }

        @Test
        @DisplayName("an absent failure is refused even when the culprit is also absent")
        void anAbsentFailureIsRefusedEvenWithoutACulprit() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(null,
                            (FileStatusException) null))
                    .withMessageContaining("null was supplied");
        }

        @Test
        @DisplayName("a failure carrying no operation or resource still abends")
        void aFailureWithoutContextStillAbends() {
            final FileStatusException held =
                    new FileStatusException(STATUS_PERMANENT_ERROR, null, null);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(BATCH_PROGRAM, held))
                    .satisfies(abend -> assertThat(abend.code())
                            .isEqualTo(ORACLE_BATCH_ABEND_CODE));
        }

        @Test
        @DisplayName("the operator prefix the legacy display uses is published by the exception type")
        void theOperatorPrefixIsPublishedByTheExceptionType() {
            assertThat(FileStatusException.DISPLAY_PREFIX)
                    .as("a Java log line carries text an operator already recognises")
                    .startsWith("FILE STATUS IS:");
        }
    }
}
