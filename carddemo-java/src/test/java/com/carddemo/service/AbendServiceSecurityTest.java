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
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link AbendService}, the Java realisation of the nine CEE3ABD call sites
 * and the {@code EXEC CICS ABEND} path, whose context structure comes from copybook member
 * {@code CSMSG02Y}.
 *
 * <h2>The misuse guard is the interesting behaviour, not the abend</h2>
 *
 * <p>Every batch program in the estate normalises a raw file status before branching: status {@code 00}
 * becomes the success result, {@code 10} becomes the end-of-file result, and everything else becomes
 * the error result ({@code app/cbl/CBACT01C.cbl} L90-L114). {@code 10} in particular is how a
 * sequential read loop <em>terminates normally</em> - it is not a failure at all.</p>
 *
 * <p>So the single worst defect this service could permit is abending on {@code 00} or {@code 10}: a
 * job would fail at end of file, every time, on data that was perfectly valid. The service guards
 * against exactly that by rejecting both codes with an {@link IllegalArgumentException} rather than
 * raising an abend, and those two rejections are the assertions this test exists for. A caller that has
 * mistaken the end-of-file arm for the error arm is told so, loudly and at the call site, instead of
 * having its mistake laundered into a plausible-looking production abend.</p>
 *
 * <h2>Diagnostics are emitted before the abend is raised</h2>
 *
 * <p>The service logs from the raw arguments and only then constructs the exception. That ordering is
 * deliberate: the exception enforces the legacy field widths at construction, so an over-length culprit
 * would be rejected - and if the diagnostic were built from the exception, the very argument that caused
 * the problem would never reach the log. The assertions below therefore exercise both the width-legal
 * path and the width-illegal path, and confirm the latter still surfaces as a rejection rather than
 * silently succeeding.</p>
 */
@DisplayName("AbendService - the CEE3ABD and EXEC CICS ABEND path")
class AbendServiceSecurityTest {

    /** A culprit within the legacy {@code ABEND-CULPRIT PIC X(8)} width. */
    private static final String PROGRAM = "CBACT01C";

    /** A reason within the legacy {@code ABEND-REASON PIC X(50)} width. */
    private static final String REASON = "ACCOUNT FILE READ FAILED";

    /** An I/O operation name for the diagnostic. */
    private static final String OPERATION = "READ";

    /** A resource name for the diagnostic. */
    private static final String RESOURCE = "ACCTDAT";

    /** A raw file status that is genuinely an error, so abending on it is correct. */
    private static final String ERROR_STATUS = "23";

    private final AbendService service = new AbendService();

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("the service is constructible with no collaborators, because the abend path depends on nothing "
                + "but its own arguments")
        void theServiceIsConstructibleWithNoCollaborators() {
            assertThat(new AbendService()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Batch abend, which carries the three-character batch abend code")
    class BatchAbend {

        @Test
        @DisplayName("the two-argument form raises an abend carrying the batch abend code, the culprit and the reason")
        void theTwoArgumentFormRaisesABatchAbend() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(PROGRAM, REASON))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(PROGRAM);
                        assertThat(abend.reason()).isEqualTo(REASON);
                    });
        }

        @Test
        @DisplayName("the raised abend carries the default operator message rather than an empty one, so the terminal "
                + "text is never blank")
        void theRaisedAbendCarriesTheDefaultOperatorMessage() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(PROGRAM, REASON))
                    .satisfies(abend -> assertThat(abend.getMessage())
                            .isEqualTo(AbendException.DEFAULT_MESSAGE));
        }

        @Test
        @DisplayName("the five-argument form accepts the I/O context and still raises the batch abend code")
        void theFiveArgumentFormAcceptsIoContext() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(PROGRAM, REASON, ERROR_STATUS, OPERATION, RESOURCE))
                    .satisfies(abend -> assertThat(abend.code())
                            .isEqualTo(AbendException.BATCH_ABEND_CODE));
        }

        @Test
        @DisplayName("the five-argument form tolerates absent I/O context, so a program with no file involved can "
                + "still abend through the same entry point")
        void theFiveArgumentFormToleratesAbsentIoContext() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(PROGRAM, REASON, null, null, null));
        }

        @Test
        @DisplayName("the five-argument form tolerates a blank operation and resource, which the diagnostic omits "
                + "rather than rendering as empty labels")
        void theFiveArgumentFormToleratesBlankIoContext() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(PROGRAM, REASON, "   ", "   ", "   "));
        }

        @Test
        @DisplayName("a file status outside the declared vocabulary is still accepted in the diagnostic, because a "
                + "live data set can return one and the diagnostic must report it rather than suppress it")
        void aFileStatusOutsideTheVocabularyIsStillReported() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendBatch(PROGRAM, REASON, "99", OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("an over-length culprit is rejected by the exception's own width enforcement, which is why the "
                + "diagnostic is built from the raw arguments first")
        void anOverLengthCulpritIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendBatch("CBACT01C-TOO-LONG", REASON));
        }
    }

    @Nested
    @DisplayName("Online abend, which carries the four-character online abend code")
    class OnlineAbend {

        @Test
        @DisplayName("the two-argument form raises an abend carrying the online abend code")
        void theTwoArgumentFormRaisesAnOnlineAbend() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(PROGRAM, REASON))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.ONLINE_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(PROGRAM);
                        assertThat(abend.reason()).isEqualTo(REASON);
                    });
        }

        @Test
        @DisplayName("the online and batch abend codes are different, so a log reader can tell which tier failed")
        void theOnlineAndBatchCodesAreDifferent() {
            assertThat(AbendException.ONLINE_ABEND_CODE).isNotEqualTo(AbendException.BATCH_ABEND_CODE);
        }

        @Test
        @DisplayName("the two-argument form supplies the default operator message")
        void theTwoArgumentFormSuppliesTheDefaultMessage() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(PROGRAM, REASON))
                    .satisfies(abend -> assertThat(abend.getMessage())
                            .isEqualTo(AbendException.DEFAULT_MESSAGE));
        }

        @Test
        @DisplayName("the three-argument form carries a caller-supplied terminal message through to the abend, which "
                + "is what the screen would have displayed")
        void theThreeArgumentFormCarriesACallerSuppliedTerminalMessage() {
            final String terminalMessage = "ACCOUNT UPDATE FAILED - CONTACT SUPPORT";
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(PROGRAM, REASON, terminalMessage))
                    .satisfies(abend -> assertThat(abend.getMessage()).isEqualTo(terminalMessage));
        }

        @Test
        @DisplayName("the raised abend renders a fixed-width context image of the declared length, which is the "
                + "CSMSG02Y structure the debug diagnostic emits")
        void theRaisedAbendRendersAFixedWidthContextImage() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnline(PROGRAM, REASON))
                    .satisfies(abend -> assertThat(abend.toFixedWidthContext())
                            .hasSize(AbendException.CONTEXT_LENGTH));
        }
    }

    @Nested
    @DisplayName("I/O status diagnostic, which reports without raising")
    class IoStatusDiagnostic {

        @Test
        @DisplayName("reporting a status does not raise, because this entry point is the display half of the legacy "
                + "pattern rather than the abend half")
        void reportingAStatusDoesNotRaise() {
            assertThatNoException()
                    .isThrownBy(() -> service.displayIoStatus(ERROR_STATUS, OPERATION, RESOURCE));
        }

        @ParameterizedTest
        @EnumSource(FileStatus.class)
        @DisplayName("every declared status can be reported, so the diagnostic covers the whole vocabulary")
        void everyDeclaredStatusCanBeReported(final FileStatus status) {
            assertThatNoException()
                    .isThrownBy(() -> service.displayIoStatus(status.getCode(), OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("a status outside the declared vocabulary can be reported, because suppressing it would hide the "
                + "very failure the diagnostic exists to surface")
        void aStatusOutsideTheVocabularyCanBeReported() {
            assertThatNoException()
                    .isThrownBy(() -> service.displayIoStatus("99", OPERATION, RESOURCE));
        }

        @Test
        @DisplayName("absent and blank arguments are tolerated, so an unset status field cannot fail the diagnostic "
                + "path that exists to report it")
        void absentAndBlankArgumentsAreTolerated() {
            assertThatNoException().isThrownBy(() -> service.displayIoStatus(null, null, null));
            assertThatNoException().isThrownBy(() -> service.displayIoStatus("   ", "   ", "   "));
            assertThatNoException().isThrownBy(() -> service.displayIoStatus("", "", ""));
        }
    }

    @Nested
    @DisplayName("Abend on a file status, and the misuse guard that is the point of it")
    class AbendOnFileStatus {

        @Test
        @DisplayName("a genuine error status raises a batch abend whose reason names the status")
        void aGenuineErrorStatusRaisesABatchAbend() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, ERROR_STATUS, OPERATION, RESOURCE))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.reason()).contains(ERROR_STATUS);
                    });
        }

        @Test
        @DisplayName("the raised abend carries the originating file failure as its cause, so the two-byte status "
                + "survives into the exception chain")
        void theRaisedAbendCarriesTheFileFailureAsItsCause() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, ERROR_STATUS, OPERATION, RESOURCE))
                    .satisfies(abend -> assertThat(abend.getCause())
                            .isInstanceOf(FileStatusException.class));
        }

        @Test
        @DisplayName("abending on the success status 00 is rejected as caller misuse, because a successful operation "
                + "is not an error")
        void abendingOnTheSuccessStatusIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM,
                            FileStatusException.STATUS_SUCCESS, OPERATION, RESOURCE))
                    .withMessageContaining("successful operation");
        }

        @Test
        @DisplayName("abending on the end-of-file status 10 is rejected as caller misuse, because that is how every "
                + "sequential read loop in the batch tier terminates normally")
        void abendingOnTheEndOfFileStatusIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM,
                            FileStatusException.STATUS_END_OF_FILE, OPERATION, RESOURCE))
                    .withMessageContaining("end of file");
        }

        @Test
        @DisplayName("neither rejection is an abend, so a job cannot be brought down by a caller confusing the "
                + "end-of-file arm with the error arm")
        void neitherRejectionIsAnAbend() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, "00", OPERATION, RESOURCE))
                    .isNotInstanceOf(AbendException.class);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, "10", OPERATION, RESOURCE))
                    .isNotInstanceOf(AbendException.class);
        }

        @Test
        @DisplayName("an absent status is rejected with a message naming the required width, rather than abending on "
                + "a status nobody supplied")
        void anAbsentStatusIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, null, OPERATION, RESOURCE))
                    .withMessageContaining(String.valueOf(FileStatusException.CODE_LENGTH));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "0", "000", "0000", "  0", "2 3 "})
        @DisplayName("a status of the wrong width is rejected rather than padded or truncated into a match")
        void aStatusOfTheWrongWidthIsRejected(final String rawFileStatus) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, rawFileStatus, OPERATION, RESOURCE))
                    .withMessageContaining("exactly");
        }

        @ParameterizedTest
        @ValueSource(strings = {"01", "02", "04", "05", "12", "22", "23", "31", "35", "99", "AB"})
        @DisplayName("every two-character status other than 00 and 10 abends, including codes outside the declared "
                + "vocabulary, because the normalisation collapses all of them onto the error result")
        void everyStatusOtherThanSuccessAndEndOfFileAbends(final String rawFileStatus) {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, rawFileStatus, OPERATION, RESOURCE));
        }
    }

    @Nested
    @DisplayName("Abend from an existing file failure, the form a catch block calls")
    class AbendFromAnExistingFailure {

        @Test
        @DisplayName("a supplied file failure raises a batch abend carrying it as the cause")
        void aSuppliedFailureRaisesABatchAbend() {
            final FileStatusException failure =
                    new FileStatusException(ERROR_STATUS, OPERATION, RESOURCE);
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, failure))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.reason()).contains(ERROR_STATUS);
                        assertThat(abend.getCause()).isSameAs(failure);
                    });
        }

        @Test
        @DisplayName("an absent file failure is rejected as caller misuse rather than abending with no context at all")
        void anAbsentFailureIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM, (FileStatusException) null))
                    .isNotInstanceOf(AbendException.class)
                    .withMessageContaining("FileStatusException is required");
        }

        @Test
        @DisplayName("this overload does not re-apply the misuse guard, because a FileStatusException that already "
                + "exists represents a failure the caller has already classified")
        void thisOverloadDoesNotReapplyTheMisuseGuard() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(PROGRAM,
                            new FileStatusException("35", OPERATION, RESOURCE)));
        }

        @Test
        @DisplayName("an absent culprit is tolerated, so a failure raised from shared code with no program name can "
                + "still abend")
        void anAbsentCulpritIsTolerated() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.abendOnFileStatus(null,
                            new FileStatusException(ERROR_STATUS, OPERATION, RESOURCE)));
        }
    }
}
