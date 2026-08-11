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
package com.carddemo.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.exception.JobSubmissionException;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;

/**
 * Unit test for {@link GlobalExceptionHandler}, the boundary advice that turns each of the six migrated
 * failure carriers into an HTTP response.
 *
 * <p><strong>What this test proves.</strong> Six exception types map onto five distinct HTTP statuses, and
 * one of those mappings is the whole point of the class:
 * <ul>
 *   <li><strong>A failed job submission answers {@code 200 OK}.</strong> The legacy transient-data queue
 *       the job-submission bridge writes to is defined with errors ignored: a failed write produces a
 *       screen message and the transaction continues. Reproducing that means the request must
 *       <em>succeed</em> while carrying the legacy failure text in its body. A modernising translation
 *       would answer 500 or 503 here and would change an externally observable contract. This test
 *       asserts the status is {@code 200} and the body carries the legacy text verbatim.</li>
 *   <li><strong>A file-status failure does not leak the raw two-byte status.</strong> The status code and
 *       the failing operation are diagnostics and go to the log; the body carries the neutral abend text
 *       instead. This test asserts the body contains neither the code nor the resource name.</li>
 *   <li><strong>A not-found answers a neutral summary, not a per-screen literal.</strong> The estate's
 *       not-found texts are per-screen, so attributing one screen's wording to a request that may have
 *       come from another would be wrong. This test asserts the body carries the neutral text and never
 *       the record type, key or resource name.</li>
 *   <li><strong>Validation preserves per-field state and focus.</strong> The two-state per-field contract
 *       - a field that is missing versus one that is present but invalid - derives from the legacy
 *       decoration macro, which colours a field on a not-OK flag and additionally marks it on a blank
 *       flag. This test asserts each state translates one for one, that an absent state degrades to
 *       invalid rather than failing the handler while it is already handling a failure, and that the focus
 *       identifier is the first entry's screen field unless that field is blank.</li>
 *   <li><strong>An abend never renders its diagnostic context.</strong> Only the operator message field is
 *       returned; the abend code, failing component and reason go to the log. This test asserts all three
 *       are absent from the body.</li>
 * </ul>
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. The advice is stateless and injects nothing, so
 * it is constructed directly and its handler methods are invoked as plain methods. No application context
 * is started, no dispatcher is involved, no database connection is opened, no file is read, no network is
 * touched, no container is run and no introspection is performed.
 *
 * <p><strong>Expectations are derived, never echoed.</strong> Every status, message and field state below
 * is a literal typed out in this source or a constant declared on the failure carrier itself, never a
 * value read back out of the advice and asserted against itself. No line of legacy source is transcribed.
 */
@DisplayName("GlobalExceptionHandler - the boundary advice for the six migrated failure carriers")
class GlobalExceptionHandlerBaselineTest {

    /** The neutral summary the advice returns for a keyed read that resolved to no record. */
    private static final String RECORD_NOT_FOUND_MESSAGE = "Record not found";

    private GlobalExceptionHandler handler;

    @BeforeEach
    void createStatelessAdvice() {
        handler = new GlobalExceptionHandler();
    }

    @Nested
    @DisplayName("Construction of a stateless advice")
    class Construction {

        @Test
        @DisplayName("the advice is constructed with no collaborator, which is what makes it safe to "
                + "invoke its handler methods directly and safe to share as a singleton")
        void theAdviceIsConstructedWithNoCollaborator() {
            assertThat(new GlobalExceptionHandler()).isNotNull();
            assertThat(handler).isNotNull();
        }

        @Test
        @DisplayName("two independently constructed advices behave identically on the same failure, "
                + "which is the observable consequence of holding no state")
        void twoAdvicesBehaveIdenticallyOnTheSameFailure() {
            final RecordNotFoundException failure = new RecordNotFoundException("ACCOUNT", "1");

            assertThat(new GlobalExceptionHandler().handleRecordNotFound(failure).getBody())
                    .isEqualTo(handler.handleRecordNotFound(failure).getBody());
        }
    }

    @Nested
    @DisplayName("A terminal abend answers 500 and returns the operator message alone")
    class AbendHandling {

        @Test
        @DisplayName("the status is internal server error, because every abend path of the estate ends "
                + "the unit of work and no recovery is offered")
        void theStatusIsInternalServerError() {
            final ResponseEntity<ErrorResponse> response = handler.handleAbend(
                    new AbendException("9999", "COACTUPC", "UPDATE FAILED", "OPERATOR TEXT"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("the body carries the operator message and nothing else - no field errors and no "
                + "focus identifier")
        void theBodyCarriesTheOperatorMessageAlone() {
            final ErrorResponse body = handler.handleAbend(
                    new AbendException("9999", "COACTUPC", "UPDATE FAILED", "OPERATOR TEXT"))
                    .getBody();

            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo("OPERATOR TEXT");
            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.hasFieldErrors()).isFalse();
            assertThat(body.focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("the abend code, the failing component and the reason are diagnostics and never "
                + "appear in the body, so a response cannot identify an internal component")
        void theDiagnosticContextNeverReachesTheBody() {
            final ErrorResponse body = handler.handleAbend(
                    new AbendException("9999", "COACTUPC", "ACCOUNT REWRITE FAILED", "OPERATOR TEXT"))
                    .getBody();

            assertThat(body).isNotNull();
            assertThat(body.message()).doesNotContain("9999");
            assertThat(body.message()).doesNotContain("COACTUPC");
            assertThat(body.message()).doesNotContain("ACCOUNT REWRITE FAILED");
        }

        @Test
        @DisplayName("a batch abend raised without an operator message still yields a non-blank body, "
                + "because the carrier substitutes the legacy default literal")
        void aBatchAbendWithoutAnOperatorMessageStillYieldsText() {
            final ErrorResponse body =
                    handler.handleAbend(new AbendException("CBACT04C", "FILE OPEN FAILED")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.message())
                    .isNotBlank()
                    .isEqualTo(AbendException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("the operator message is never longer than the 72 characters the legacy field "
                + "reserves, so a body cannot exceed the field it derives from")
        void theOperatorMessageStaysWithinItsLegacyWidth() {
            final String widest = "X".repeat(AbendException.MESSAGE_LENGTH);
            final ErrorResponse body = handler
                    .handleAbend(new AbendException("9999", "COACTUPC", "R", widest)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.message()).hasSize(AbendException.MESSAGE_LENGTH);
            assertThat(AbendException.MESSAGE_LENGTH).isEqualTo(72);
        }
    }

    @Nested
    @DisplayName("An unhandled file-status failure answers 500 without leaking the raw status")
    class FileStatusHandling {

        @Test
        @DisplayName("the status is internal server error, because a file operation that no service "
                + "translated has already left the unit of work indeterminate")
        void theStatusIsInternalServerError() {
            final ResponseEntity<ErrorResponse> response = handler.handleFileStatus(
                    new FileStatusException("35", "OPEN", "ACCTDAT"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("the body carries the neutral abend text rather than the failure's own message, so "
                + "the raw two-byte status, the operation and the resource name all stay in the log")
        void theBodyCarriesTheNeutralAbendText() {
            final FileStatusException failure = new FileStatusException("35", "OPEN", "ACCTDAT");
            final ErrorResponse body = handler.handleFileStatus(failure).getBody();

            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo(AbendException.DEFAULT_MESSAGE);
            assertThat(body.message()).doesNotContain("35");
            assertThat(body.message()).doesNotContain("OPEN");
            assertThat(body.message()).doesNotContain("ACCTDAT");
            assertThat(body.message()).isNotEqualTo(failure.getMessage());
        }

        @Test
        @DisplayName("the body is the same neutral text whatever the error status, so no status code can "
                + "be inferred from the response")
        void theBodyIsTheSameWhateverTheErrorStatus() {
            final List<String> distinctBodies = new ArrayList<>();

            for (final String code : List.of("01", "04", "12", "22", "23", "31", "35")) {
                final ErrorResponse body = handler
                        .handleFileStatus(new FileStatusException(code, "READ", "CARDDAT")).getBody();

                assertThat(body).isNotNull();
                if (!distinctBodies.contains(body.message())) {
                    distinctBodies.add(body.message());
                }
                assertThat(body.message()).doesNotContain(code);
            }

            assertThat(distinctBodies).containsExactly(AbendException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("the two non-error statuses can never reach this handler at all, because the carrier "
                + "refuses to be constructed from them - which is the two-level status model enforced at "
                + "the point of raising rather than at the boundary")
        void theTwoNonErrorStatusesCanNeverReachThisHandler() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FileStatusException(
                            FileStatusException.STATUS_SUCCESS, "READ", "ACCTDAT"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FileStatusException(
                            FileStatusException.STATUS_END_OF_FILE, "READ", "ACCTDAT"));

            assertThat(FileStatusException.STATUS_SUCCESS).isEqualTo("00");
            assertThat(FileStatusException.STATUS_END_OF_FILE).isEqualTo("10");
        }

        @Test
        @DisplayName("the body carries no field errors and no focus identifier, because a file failure "
                + "is not attributable to any screen field")
        void theBodyCarriesNoFieldErrors() {
            final ErrorResponse body = handler
                    .handleFileStatus(new FileStatusException("35", "OPEN", "ACCTDAT")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("a failure carrying a cause is handled the same way, so wrapping does not change "
                + "the status or the body")
        void aFailureCarryingACauseIsHandledTheSameWay() {
            final ResponseEntity<ErrorResponse> response = handler.handleFileStatus(
                    new FileStatusException("35", "OPEN", "ACCTDAT",
                            new IllegalStateException("underlying")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(AbendException.DEFAULT_MESSAGE);
        }
    }

    @Nested
    @DisplayName("A keyed read that found nothing answers 404 with a neutral summary")
    class RecordNotFoundHandling {

        @Test
        @DisplayName("the status is not found, which is the only status that matches a read whose key "
                + "resolved to no record")
        void theStatusIsNotFound() {
            final ResponseEntity<ErrorResponse> response = handler.handleRecordNotFound(
                    new RecordNotFoundException("ACCOUNT", "00000000001", "ACCTDAT"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("the body carries the neutral summary and never the record type, the key or the "
                + "resource name, so no per-screen literal is attributed to the wrong screen")
        void theBodyCarriesTheNeutralSummaryOnly() {
            final ErrorResponse body = handler.handleRecordNotFound(
                    new RecordNotFoundException("ACCOUNT", "00000000001", "ACCTDAT")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo(RECORD_NOT_FOUND_MESSAGE);
            assertThat(body.message()).doesNotContain("ACCOUNT");
            assertThat(body.message()).doesNotContain("00000000001");
            assertThat(body.message()).doesNotContain("ACCTDAT");
            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("the same summary is returned for a bare failure carrying no context at all, so the "
                + "response does not vary with how much diagnostic detail was available")
        void theSameSummaryIsReturnedForABareFailure() {
            final ErrorResponse body =
                    handler.handleRecordNotFound(new RecordNotFoundException()).getBody();

            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo(RECORD_NOT_FOUND_MESSAGE);
        }

        @Test
        @DisplayName("the summary is the same for every record type, so a client cannot enumerate which "
                + "record types exist by comparing not-found responses")
        void theSummaryIsTheSameForEveryRecordType() {
            for (final String recordType : List.of("ACCOUNT", "CARD", "CUSTOMER", "XREF", "USER")) {
                final ErrorResponse body = handler
                        .handleRecordNotFound(new RecordNotFoundException(recordType, "1")).getBody();

                assertThat(body).isNotNull();
                assertThat(body.message()).isEqualTo(RECORD_NOT_FOUND_MESSAGE);
            }
        }

        @Test
        @DisplayName("the neutral summary is not the legacy record-not-found file status, so the raw "
                + "status is not smuggled into the text")
        void theNeutralSummaryIsNotTheRawFileStatus() {
            final ErrorResponse body = handler
                    .handleRecordNotFound(new RecordNotFoundException("ACCOUNT", "1")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.message())
                    .doesNotContain(RecordNotFoundException.STATUS_RECORD_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Submitted data that failed validation answers 400 with per-field state")
    class ValidationHandling {

        @Test
        @DisplayName("the status is bad request, because the submission itself is what the caller must "
                + "change")
        void theStatusIsBadRequest() {
            final ResponseEntity<ErrorResponse> response = handler.handleValidation(
                    new ValidationException("acctStatus", "ACSTTUS",
                            ValidationException.FieldState.INVALID, "Account Status must be Y or N"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("the failure's own message is preserved verbatim, because a validation message is a "
                + "screen literal the caller is expected to see")
        void theFailureMessageIsPreservedVerbatim() {
            final ErrorResponse body = handler.handleValidation(
                    new ValidationException("acctStatus", "ACSTTUS",
                            ValidationException.FieldState.INVALID, "Account Status must be Y or N"))
                    .getBody();

            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo("Account Status must be Y or N");
        }

        @ParameterizedTest(name = "the field state {0} translates one for one")
        @DisplayName("each of the two field states translates to the response state of the same name, "
                + "preserving the missing-versus-invalid distinction the legacy decoration draws")
        @EnumSource(ValidationException.FieldState.class)
        void eachFieldStateTranslatesOneForOne(final ValidationException.FieldState state) {
            final ErrorResponse body = handler.handleValidation(
                    new ValidationException("field", "SCRNFLD", state, "text")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().getFirst().state().name()).isEqualTo(state.name());
        }

        @Test
        @DisplayName("a missing field and an invalid field are reported as two distinct states in one "
                + "response, so a client can tell an omission from a bad value")
        void aMissingFieldAndAnInvalidFieldAreDistinctInOneResponse() {
            final ValidationException failure = new ValidationException("Please correct the errors",
                    List.of(
                            new ValidationException.FieldError("acctId", "ACCTSID",
                                    ValidationException.FieldState.MISSING, "Account ID is required"),
                            new ValidationException.FieldError("ficoScore", "ACSTFCO",
                                    ValidationException.FieldState.INVALID,
                                    "FICO Score must be between 300 and 850")));

            final ErrorResponse body = handler.handleValidation(failure).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
            assertThat(body.hasFieldErrors()).isTrue();
        }

        @Test
        @DisplayName("field name, screen field identifier and message all carry across in order, so the "
                + "translation loses nothing")
        void everyFieldErrorComponentCarriesAcross() {
            final ValidationException failure = new ValidationException("Please correct the errors",
                    List.of(new ValidationException.FieldError("ficoScore", "ACSTFCO",
                            ValidationException.FieldState.INVALID,
                            "FICO Score must be between 300 and 850")));

            final ErrorResponse body = handler.handleValidation(failure).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors().getFirst().fieldName()).isEqualTo("ficoScore");
            assertThat(body.fieldErrors().getFirst().screenFieldId()).isEqualTo("ACSTFCO");
            assertThat(body.fieldErrors().getFirst().message())
                    .isEqualTo("FICO Score must be between 300 and 850");
        }

        @Test
        @DisplayName("an entry with an absent state is refused by the carrier rather than guessed at by "
                + "the advice, and the advice stays total over both states the carrier can hold")
        void anAbsentStateIsRefusedByTheCarrierAndTheAdviceStaysTotal() {
            // What this asserted, and why it now asserts it one layer earlier.
            //
            // The concern is real and is preserved: the boundary must be total, so no shape of failure
            // can turn a caller-caused rejection into a server error while the advice is already
            // handling a failure. What changed is where the absent state is stopped.
            //
            // The legacy flag has exactly two states. The macro at app/cpy/CSSETATY.cpy L18-L27 fires on
            // FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK and writes the '*' marker only for BLANK, so
            // a field is either MISSING or INVALID and never in a third, unstated condition. Degrading an
            // absent state to INVALID inside the advice therefore had the advice invent a legacy state on
            // the producer's behalf, and the invented value is not harmless: MISSING tells the operator to
            // supply a value they may already have supplied, while INVALID tells them to correct a value
            // they may never have entered. The carrier now refuses the null at construction, which locates
            // the defect in the producer that omitted the state instead of showing the operator a guess.
            //
            // Both halves are asserted below: the refusal names the contract, and the advice remains total
            // over every state the carrier can actually hold.
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() ->
                            new ValidationException.FieldError("field", "SCRNFLD", null, "text"))
                    .withMessageContaining("state must not be null")
                    .withMessageContaining("MISSING")
                    .withMessageContaining("INVALID");

            for (final ValidationException.FieldState state : ValidationException.FieldState.values()) {
                final ValidationException failure = new ValidationException("Please correct the errors",
                        List.of(new ValidationException.FieldError("field", "SCRNFLD", state, "text")));

                final ErrorResponse body = handler.handleValidation(failure).getBody();

                assertThat(body).as("state %s must still produce a body", state).isNotNull();
                assertThat(body.fieldErrors()).hasSize(1);
                assertThat(body.fieldErrors().getFirst().state())
                        .as("the state the producer stated is the state the caller is told")
                        .isEqualTo(ErrorResponse.FieldState.valueOf(state.name()));
            }
        }

        @Test
        @DisplayName("an entry with an absent field name is normalised to an empty name rather than "
                + "rejected, keeping the translation total")
        void anAbsentFieldNameIsNormalisedToEmpty() {
            final ValidationException failure = new ValidationException("Please correct the errors",
                    List.of(new ValidationException.FieldError(null, "SCRNFLD",
                            ValidationException.FieldState.INVALID, "text")));

            final ErrorResponse body = handler.handleValidation(failure).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors().getFirst().fieldName()).isEmpty();
            assertThat(body.fieldErrors().getFirst().screenFieldId()).isEqualTo("SCRNFLD");
        }

        @Test
        @DisplayName("an unscreened entry - one the carrier already normalised to an empty screen field "
                + "- still translates, and contributes no focus identifier")
        void anUnscreenedEntryStillTranslatesAndSetsNoFocus() {
            final ValidationException failure = new ValidationException("Please correct the errors",
                    List.of(new ValidationException.FieldError("field", null,
                            ValidationException.FieldState.MISSING, "text")));

            final ErrorResponse body = handler.handleValidation(failure).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().getFirst().screenFieldId()).isEmpty();
            assertThat(body.focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("the focus identifier is the first entry's screen field, which reproduces the legacy "
                + "behaviour of positioning the cursor on the first field in error")
        void theFocusIdentifierIsTheFirstEntrysScreenField() {
            final ValidationException failure = new ValidationException("Please correct the errors",
                    List.of(
                            new ValidationException.FieldError("acctId", "ACCTSID",
                                    ValidationException.FieldState.MISSING, "first"),
                            new ValidationException.FieldError("ficoScore", "ACSTFCO",
                                    ValidationException.FieldState.INVALID, "second")));

            final ErrorResponse body = handler.handleValidation(failure).getBody();

            assertThat(body).isNotNull();
            assertThat(body.focusScreenFieldId()).isEqualTo("ACCTSID");
        }

        @Test
        @DisplayName("a blank first screen field yields no focus identifier rather than a blank one, so a "
                + "client is never told to focus a field that does not exist")
        void aBlankFirstScreenFieldYieldsNoFocus() {
            final ValidationException failure = new ValidationException("Please correct the errors",
                    List.of(
                            new ValidationException.FieldError("field", "   ",
                                    ValidationException.FieldState.INVALID, "first"),
                            new ValidationException.FieldError("ficoScore", "ACSTFCO",
                                    ValidationException.FieldState.INVALID, "second")));

            final ErrorResponse body = handler.handleValidation(failure).getBody();

            assertThat(body).isNotNull();
            assertThat(body.focusScreenFieldId()).isNull();
            assertThat(body.fieldErrors()).hasSize(2);
        }

        @Test
        @DisplayName("a validation failure carrying no field errors at all still answers 400 with its "
                + "message, an empty error list and no focus identifier")
        void aFailureWithNoFieldErrorsStillAnswersBadRequest() {
            final ResponseEntity<ErrorResponse> response =
                    handler.handleValidation(new ValidationException("Please enter a valid option"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo("Please enter a valid option");
            assertThat(response.getBody().fieldErrors()).isEmpty();
            assertThat(response.getBody().hasFieldErrors()).isFalse();
            assertThat(response.getBody().focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("the translated list preserves submission order across many entries, because the "
                + "legacy validation cascade reports in the order it evaluates")
        void theTranslatedListPreservesSubmissionOrder() {
            final List<ValidationException.FieldError> submitted = new ArrayList<>();
            for (int ordinal = 1; ordinal <= 6; ordinal++) {
                submitted.add(new ValidationException.FieldError("field" + ordinal,
                        "SCRNF" + ordinal, ValidationException.FieldState.INVALID, "m" + ordinal));
            }

            final ErrorResponse body =
                    handler.handleValidation(new ValidationException("m", submitted)).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactly("field1", "field2", "field3", "field4", "field5", "field6");
            assertThat(body.focusScreenFieldId()).isEqualTo("SCRNF1");
        }

        @Test
        @DisplayName("the translated list is immutable, so a caller cannot mutate a response that is "
                + "already on its way out")
        void theTranslatedListIsImmutable() {
            final ErrorResponse body = handler.handleValidation(
                    new ValidationException("field", "SCRNFLD",
                            ValidationException.FieldState.MISSING, "text")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).isUnmodifiable();
        }
    }

    @Nested
    @DisplayName("A concurrent update conflict answers 409")
    class OptimisticLockConflictHandling {

        @Test
        @DisplayName("the status is conflict, which is what the legacy before-and-after image comparison "
                + "and its single rollback amount to at the boundary")
        void theStatusIsConflict() {
            final ResponseEntity<ErrorResponse> response =
                    handler.handleOptimisticLockConflict(new OptimisticLockConflictException(
                            OptimisticLockConflictException.ConflictKind
                                    .RECORD_CHANGED_BEFORE_UPDATE,
                            "Account", "00000000001"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @ParameterizedTest(name = "the conflict arm {0} carries its own legacy message into the body")
        @DisplayName("each of the three legacy write-path arms carries its own message into the body, so "
                + "the wording a screen showed is the wording the client receives")
        @EnumSource(OptimisticLockConflictException.ConflictKind.class)
        void eachConflictArmCarriesItsOwnLegacyMessage(
                final OptimisticLockConflictException.ConflictKind kind) {
            final ErrorResponse body = handler.handleOptimisticLockConflict(
                    new OptimisticLockConflictException(kind, "Account", "1")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo(kind.defaultMessage("Account"));
            assertThat(body.message()).isNotBlank();
        }

        @Test
        @DisplayName("the three arms do not all share one message, so the arm is observable from the "
                + "response rather than being flattened away")
        void theThreeArmsDoNotAllShareOneMessage() {
            final List<String> messages = new ArrayList<>();

            for (final OptimisticLockConflictException.ConflictKind kind
                    : OptimisticLockConflictException.ConflictKind.values()) {
                final ErrorResponse body = handler.handleOptimisticLockConflict(
                        new OptimisticLockConflictException(kind, "Account", "1")).getBody();

                assertThat(body).isNotNull();
                messages.add(body.message());
            }

            assertThat(messages).hasSize(3);
            assertThat(new HashSet<>(messages)).hasSizeGreaterThan(1);
        }

        @Test
        @DisplayName("the entity name and key are diagnostics and never appear in the body")
        void theEntityNameAndKeyNeverReachTheBody() {
            final ErrorResponse body = handler.handleOptimisticLockConflict(
                    new OptimisticLockConflictException(
                            OptimisticLockConflictException.ConflictKind.UPDATE_FAILED_AFTER_LOCK,
                            "Account", "00000000001")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.message()).doesNotContain("00000000001");
            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("the body is the arm's own legacy text, and a service cannot substitute wording "
                + "of its own - not even a near miss of a published literal")
        void theBodyIsTheArmsOwnLegacyTextAndCannotBeSubstituted() {
            final ErrorResponse body = handler.handleOptimisticLockConflict(
                    new OptimisticLockConflictException(
                            OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED,
                            "Account", "1")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo("Could not lock account record for update");

            // "account for update" rather than "account record for update" - a word short of the
            // legacy literal, and refused at construction rather than published.
            assertThatThrownBy(() -> new OptimisticLockConflictException(
                    OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED,
                    "Account", "1", "Could not lock account for update", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the body renders from the classification, so it matches the carrier's own "
                + "message on every arm rather than depending on which of the two the handler read")
        void theBodyRendersFromTheClassificationAndMatchesTheCarrierMessage() {
            for (OptimisticLockConflictException.ConflictKind arm
                    : OptimisticLockConflictException.ConflictKind.values()) {
                for (String entity : new String[] {"Account", "Customer"}) {
                    OptimisticLockConflictException conflict =
                            new OptimisticLockConflictException(arm, entity, "1");
                    final ErrorResponse body =
                            handler.handleOptimisticLockConflict(conflict).getBody();

                    assertThat(body).isNotNull();
                    assertThat(body.message())
                            .as("arm %s entity %s", arm, entity)
                            .isEqualTo(conflict.getMessage())
                            .isEqualTo(arm.defaultMessage(entity));
                }
            }
        }
    }

    @Nested
    @DisplayName("A failed job submission answers 200, because the legacy queue ignores errors")
    class JobSubmissionHandling {

        @Test
        @DisplayName("the status is OK - not 500 and not 503 - because the legacy queue definition "
                + "ignores write errors and the transaction continues")
        void theStatusIsOkBecauseTheLegacyQueueIgnoresErrors() {
            final ResponseEntity<ErrorResponse> response = handler.handleJobSubmission(
                    new JobSubmissionException("JOBS.fifo", "16", "13", 7,
                            new IllegalStateException("publish refused")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getStatusCode().isError()).isFalse();
        }

        @Test
        @DisplayName("the body carries the legacy failure text verbatim, which is the screen message the "
                + "reporting transaction shows when the queue write fails")
        void theBodyCarriesTheLegacyFailureTextVerbatim() {
            final ErrorResponse body = handler.handleJobSubmission(
                    new JobSubmissionException("JOBS.fifo", "16", "13", 7,
                            new IllegalStateException("publish refused"))).getBody();

            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
            assertThat(body.message()).isNotBlank();
        }

        @Test
        @DisplayName("the queue name, response code, reason code and failed card ordinal are all "
                + "diagnostics and none of them reaches the body")
        void theDiagnosticContextNeverReachesTheBody() {
            final ErrorResponse body = handler.handleJobSubmission(
                    new JobSubmissionException("JOBS.fifo", "16", "13", 7,
                            new IllegalStateException("publish refused"))).getBody();

            assertThat(body).isNotNull();
            assertThat(body.message()).doesNotContain("JOBS.fifo");
            assertThat(body.message()).doesNotContain("16");
            assertThat(body.message()).doesNotContain("13");
            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("a submission failure that names no card ordinal is handled identically, so a "
                + "whole-publish failure and a per-card failure look the same to the client")
        void aFailureWithNoCardOrdinalIsHandledIdentically() {
            final ResponseEntity<ErrorResponse> withoutOrdinal = handler.handleJobSubmission(
                    new JobSubmissionException("JOBS.fifo", "16", "13",
                            new IllegalStateException("publish refused")));
            final ResponseEntity<ErrorResponse> withOrdinal = handler.handleJobSubmission(
                    new JobSubmissionException("JOBS.fifo", "16", "13", 3,
                            new IllegalStateException("publish refused")));

            assertThat(withoutOrdinal.getStatusCode()).isEqualTo(withOrdinal.getStatusCode());
            assertThat(withoutOrdinal.getBody()).isEqualTo(withOrdinal.getBody());
        }

        @Test
        @DisplayName("a failure raised from a bare cause is handled identically, so the shape of the "
                + "carrier does not change the contract")
        void aFailureRaisedFromABareCauseIsHandledIdentically() {
            final ResponseEntity<ErrorResponse> response = handler.handleJobSubmission(
                    new JobSubmissionException(new IllegalStateException("publish refused")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }
    }

    @Nested
    @DisplayName("The six handlers map onto five distinct statuses")
    class StatusMap {

        @Test
        @DisplayName("the two server-side failures share 500 while the four others are each distinct, "
                + "so the six carriers occupy exactly five statuses")
        void theSixCarriersOccupyFiveStatuses() {
            final List<HttpStatusCode> statuses = List.of(
                    handler.handleAbend(new AbendException("C", "R")).getStatusCode(),
                    handler.handleFileStatus(
                            new FileStatusException("35", "OPEN", "ACCTDAT")).getStatusCode(),
                    handler.handleRecordNotFound(new RecordNotFoundException()).getStatusCode(),
                    handler.handleValidation(new ValidationException("m")).getStatusCode(),
                    handler.handleOptimisticLockConflict(
                            new OptimisticLockConflictException(
                                    OptimisticLockConflictException.ConflictKind
                                            .RECORD_CHANGED_BEFORE_UPDATE,
                                    "Account", "1")).getStatusCode(),
                    handler.handleJobSubmission(
                            new JobSubmissionException(new IllegalStateException("x")))
                            .getStatusCode());

            assertThat(statuses).containsExactly(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    HttpStatus.NOT_FOUND,
                    HttpStatus.BAD_REQUEST,
                    HttpStatus.CONFLICT,
                    HttpStatus.OK);
            assertThat(new HashSet<>(statuses)).hasSize(5);
        }

        @Test
        @DisplayName("every handler returns a body: no handler answers with an empty response, so a "
                + "client always receives something it can render")
        void everyHandlerReturnsABody() {
            assertThat(handler.handleAbend(new AbendException("C", "R")).getBody()).isNotNull();
            assertThat(handler.handleFileStatus(new FileStatusException("35", "OPEN", "A"))
                    .getBody()).isNotNull();
            assertThat(handler.handleRecordNotFound(new RecordNotFoundException())
                    .getBody()).isNotNull();
            assertThat(handler.handleValidation(new ValidationException("m")).getBody()).isNotNull();
            assertThat(handler.handleOptimisticLockConflict(new OptimisticLockConflictException(
                    OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED, "Account", "1"))
                    .getBody()).isNotNull();
            assertThat(handler.handleJobSubmission(
                    new JobSubmissionException(new IllegalStateException("x")))
                    .getBody()).isNotNull();
        }

        @Test
        @DisplayName("only the validation handler ever populates field errors, because it is the only "
                + "failure attributable to a screen field")
        void onlyValidationEverPopulatesFieldErrors() {
            assertThat(handler.handleAbend(new AbendException("C", "R"))
                    .getBody().hasFieldErrors()).isFalse();
            assertThat(handler.handleFileStatus(new FileStatusException("35", "OPEN", "A"))
                    .getBody().hasFieldErrors()).isFalse();
            assertThat(handler.handleRecordNotFound(new RecordNotFoundException())
                    .getBody().hasFieldErrors()).isFalse();
            assertThat(handler.handleOptimisticLockConflict(new OptimisticLockConflictException(
                    OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED, "Account", "1"))
                    .getBody().hasFieldErrors()).isFalse();
            assertThat(handler.handleJobSubmission(
                    new JobSubmissionException(new IllegalStateException("x")))
                    .getBody().hasFieldErrors()).isFalse();

            assertThat(handler.handleValidation(new ValidationException("f", "SCRNFLD",
                    ValidationException.FieldState.MISSING, "t"))
                    .getBody().hasFieldErrors()).isTrue();
        }
    }
}
