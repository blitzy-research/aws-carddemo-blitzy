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

import java.util.ArrayList;
import java.util.List;

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.exception.JobSubmissionException;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies {@link GlobalExceptionHandler}, the single place where an internal failure becomes an HTTP
 * response.
 *
 * <p><strong>Why this class is worth testing carefully.</strong> Two of its six mappings deliberately
 * discard the exception's own message and substitute a fixed one, and a third deliberately answers with a
 * success status. Each of those three is a decision that a reader would be entitled to mistake for a bug,
 * so each is asserted here together with the reason it is correct.
 *
 * <p><strong>The two substitutions are non-leakage guarantees, not laziness.</strong> A file-status
 * failure carries the raw two-byte status, the operation and the dataset name in its message; a keyed-read
 * miss carries the record type, the key and the resource name. Neither belongs in a response body — the
 * key in particular can be an account or card number — so the handler replaces both with a fixed text and
 * logs the detail instead. This suite proves the substitution in both directions: that the response does
 * <em>not</em> carry the detail, and that the exception's own message <em>does</em>, so the substitution is
 * demonstrably doing work rather than being a no-op over an already-empty message.
 *
 * <p><strong>The success status on a failed job submission reproduces a legacy contract.</strong> The
 * transient data queue the report screen writes to is defined with errors ignored, so a write failure in
 * the legacy system produced an operator message and the transaction continued. Answering with a server
 * error would change that observable behaviour, so the handler answers with success and carries the same
 * operator text the legacy program displayed. This is the single most surprising mapping in the class and
 * it is asserted explicitly, including that the status is in the successful series rather than merely
 * being some particular code.
 *
 * <p><strong>Why the null defence in the field-error translation matters.</strong> The response's field
 * error rejects a null field name, a null screen field identifier and a null state outright, while the
 * exception's field error normalises only the identifier. A validation failure carrying a null field name
 * or a null state would therefore fail while being turned into a response, converting a client's bad
 * request into a server error, if the handler did not normalise first. The suite drives exactly that input
 * and proves the handler absorbs it.
 *
 * <p><strong>Both invocation styles are exercised.</strong> The behavioural assertions call the handler
 * methods directly, which is where the mapping decisions live. A final group dispatches real requests
 * through a standalone MVC setup, which proves the annotations actually route — a method that behaves
 * perfectly but is never reached would pass the first group and fail the second.
 */
@DisplayName("GlobalExceptionHandler — internal failure to HTTP response")
class GlobalExceptionHandlerParityTest {

    /** The fixed text a keyed-read miss is reported as. */
    private static final String RECORD_NOT_FOUND_MESSAGE = "Record not found";

    /** A raw file status that is neither success nor end of file, so the exception accepts it. */
    private static final String FAILING_FILE_STATUS = "35";

    /** The handler under test. It holds no state, so one instance serves every assertion. */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * Returns the body of a response, failing rather than returning null when the body is absent.
     *
     * @param response the response to unwrap
     * @return the response body
     */
    private static ErrorResponse bodyOf(final ResponseEntity<ErrorResponse> response) {
        assertThat(response).isNotNull();
        assertThat(response.getBody()).as("every mapping must answer with a body").isNotNull();
        return response.getBody();
    }

    /**
     * Returns the status code of a response.
     *
     * @param response the response to inspect
     * @return the status code
     */
    private static HttpStatusCode statusOf(final ResponseEntity<ErrorResponse> response) {
        assertThat(response).isNotNull();
        return response.getStatusCode();
    }

    /**
     * Builds a validation failure carrying the supplied field errors.
     *
     * @param message     the summary message
     * @param fieldErrors the field errors
     * @return the validation failure
     */
    private static ValidationException validationFailure(
            final String message, final List<ValidationException.FieldError> fieldErrors) {
        return new ValidationException(message, fieldErrors);
    }

    /**
     * Builds one exception-side field error.
     *
     * @param field      the logical field name
     * @param bmsFieldId the screen field identifier
     * @param state      the field state
     * @param message    the per-field message
     * @return the field error
     */
    private static ValidationException.FieldError fieldError(final String field,
            final String bmsFieldId, final ValidationException.FieldState state,
            final String message) {
        return new ValidationException.FieldError(field, bmsFieldId, state, message);
    }

    /**
     * Invokes every mapping once and collects the responses, so an invariant can be asserted across all
     * six without repeating the construction.
     *
     * @return one response per mapping
     */
    private List<ResponseEntity<ErrorResponse>> everyMapping() {
        final List<ResponseEntity<ErrorResponse>> responses = new ArrayList<>();
        responses.add(handler.handleAbend(
                new AbendException("9999", "COACTUPC", "STORAGE VIOLATION", "ABEND IN UPDATE")));
        responses.add(handler.handleFileStatus(
                new FileStatusException(FAILING_FILE_STATUS, "READ", "ACCTDAT")));
        responses.add(handler.handleRecordNotFound(
                new RecordNotFoundException("Account", "00000000011", "ACCTDAT")));
        responses.add(handler.handleValidation(validationFailure("Please correct the field",
                List.of(fieldError("acctId", "ACCTSID", ValidationException.FieldState.MISSING,
                        "Account number must be supplied")))));
        responses.add(handler.handleOptimisticLockConflict(new OptimisticLockConflictException(
                OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE,
                "Account", "00000000011")));
        responses.add(handler.handleJobSubmission(
                new JobSubmissionException("JOBS", "17", "0", 4, new IllegalStateException("queue"))));
        return responses;
    }

    // =================================================================================================
    // THE ABEND MAPPING
    // =================================================================================================

    /**
     * Verifies the mapping for an abend that reached the boundary.
     */
    @Nested
    @DisplayName("the abend mapping")
    class AbendMapping {

        @Test
        @DisplayName("an abend answers with a server error")
        void anAbendAnswersWithAServerError() {
            final ResponseEntity<ErrorResponse> response = handler.handleAbend(
                    new AbendException("9999", "COACTUPC", "STORAGE VIOLATION", "ABEND IN UPDATE"));

            assertThat(statusOf(response)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(statusOf(response).is5xxServerError()).isTrue();
        }

        @Test
        @DisplayName("the body carries the abend's own message, because that message is already an "
                + "operator-facing text of bounded width rather than an internal diagnostic")
        void theBodyCarriesTheAbendsOwnMessage() {
            final AbendException exception =
                    new AbendException("9999", "COACTUPC", "STORAGE VIOLATION", "ABEND IN UPDATE");

            assertThat(bodyOf(handler.handleAbend(exception)).message())
                    .isEqualTo("ABEND IN UPDATE")
                    .isEqualTo(exception.getMessage());
        }

        @Test
        @DisplayName("an abend raised without a message reports the legacy default text")
        void anAbendWithoutAMessageReportsTheDefault() {
            final AbendException exception = new AbendException("COACTUPC", "STORAGE VIOLATION");

            assertThat(exception.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
            assertThat(bodyOf(handler.handleAbend(exception)).message())
                    .isEqualTo(AbendException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("the body carries no field errors and no focus field, because an abend is not a "
                + "field-level failure")
        void theBodyCarriesNoFieldLevelDetail() {
            final ErrorResponse body = bodyOf(handler.handleAbend(
                    new AbendException("9999", "COACTUPC", "STORAGE VIOLATION", "ABEND IN UPDATE")));

            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.hasFieldErrors()).isFalse();
            assertThat(body.focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("the response omits the culprit and the reason, which are logged rather than "
                + "returned")
        void theResponseOmitsTheCulpritAndReason() {
            final ErrorResponse body = bodyOf(handler.handleAbend(
                    new AbendException("9999", "COACTUPC", "STORAGE VIOLATION", "ABEND IN UPDATE")));

            assertThat(body.message()).doesNotContain("COACTUPC").doesNotContain("STORAGE VIOLATION");
        }
    }

    // =================================================================================================
    // THE FILE-STATUS MAPPING
    // =================================================================================================

    /**
     * Verifies the mapping for an unhandled file operation failure, whose message is deliberately
     * discarded.
     */
    @Nested
    @DisplayName("the file-status mapping")
    class FileStatusMapping {

        @Test
        @DisplayName("a file-status failure answers with a server error")
        void aFileStatusFailureAnswersWithAServerError() {
            final ResponseEntity<ErrorResponse> response = handler.handleFileStatus(
                    new FileStatusException(FAILING_FILE_STATUS, "READ", "ACCTDAT"));

            assertThat(statusOf(response)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("the body reports the legacy abend text rather than the exception's own message")
        void theBodyReportsTheLegacyAbendText() {
            final FileStatusException exception =
                    new FileStatusException(FAILING_FILE_STATUS, "READ", "ACCTDAT");

            assertThat(bodyOf(handler.handleFileStatus(exception)).message())
                    .isEqualTo(AbendException.DEFAULT_MESSAGE)
                    .isNotEqualTo(exception.getMessage());
        }

        @Test
        @DisplayName("the raw status, the operation and the dataset name do not reach the response, "
                + "although the exception's own message carries all three")
        void theInternalDetailDoesNotReachTheResponse() {
            final FileStatusException exception =
                    new FileStatusException(FAILING_FILE_STATUS, "READ", "ACCTDAT");

            assertThat(exception.getMessage())
                    .as("the substitution is only meaningful if there is detail to suppress")
                    .contains(FAILING_FILE_STATUS)
                    .contains("READ")
                    .contains("ACCTDAT");
            assertThat(bodyOf(handler.handleFileStatus(exception)).message())
                    .doesNotContain(FAILING_FILE_STATUS)
                    .doesNotContain("READ")
                    .doesNotContain("ACCTDAT");
        }

        @Test
        @DisplayName("the substitution is the same whatever the underlying status, so no status code can "
                + "be inferred from the response")
        void theSubstitutionIsTheSameWhateverTheStatus() {
            final List<String> statuses = List.of("35", "23", "37", "92");
            final List<String> messages = new ArrayList<>();

            for (final String status : statuses) {
                messages.add(bodyOf(handler.handleFileStatus(
                        new FileStatusException(status, "READ", "ACCTDAT"))).message());
            }

            assertThat(messages).containsOnly(AbendException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("a file-status failure carrying a cause still answers the same way, so the cause is "
                + "not unwrapped into the response")
        void aCausedFailureAnswersTheSameWay() {
            final FileStatusException exception = new FileStatusException(
                    FAILING_FILE_STATUS, "READ", "ACCTDAT",
                    new IllegalStateException("dataset not catalogued"));

            final ErrorResponse body = bodyOf(handler.handleFileStatus(exception));

            assertThat(body.message())
                    .isEqualTo(AbendException.DEFAULT_MESSAGE)
                    .doesNotContain("dataset not catalogued");
            assertThat(body.fieldErrors()).isEmpty();
        }
    }

    // =================================================================================================
    // THE KEYED-READ MISS MAPPING
    // =================================================================================================

    /**
     * Verifies the mapping for a keyed read that resolved to no record.
     */
    @Nested
    @DisplayName("the keyed-read miss mapping")
    class RecordNotFoundMapping {

        @Test
        @DisplayName("a keyed-read miss answers not found")
        void aKeyedReadMissAnswersNotFound() {
            final ResponseEntity<ErrorResponse> response = handler.handleRecordNotFound(
                    new RecordNotFoundException("Account", "00000000011", "ACCTDAT"));

            assertThat(statusOf(response)).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(statusOf(response).is4xxClientError()).isTrue();
        }

        @Test
        @DisplayName("the body reports a fixed text rather than the exception's own message")
        void theBodyReportsAFixedText() {
            final RecordNotFoundException exception =
                    new RecordNotFoundException("Account", "00000000011", "ACCTDAT");

            assertThat(bodyOf(handler.handleRecordNotFound(exception)).message())
                    .isEqualTo(RECORD_NOT_FOUND_MESSAGE)
                    .isNotEqualTo(exception.getMessage());
        }

        @Test
        @DisplayName("the searched key does not reach the response, and the exception is carrying one — a "
                + "key can be an account or card number, so it travels only on the controlled accessor")
        void theSearchedKeyDoesNotReachTheResponse() {
            final String key = "00000000011";
            final RecordNotFoundException exception =
                    new RecordNotFoundException("Account", key, "ACCTDAT");

            // The finding is unchanged: the key must not reach the caller. The premise is now read from
            // the controlled accessor rather than from the detail message, because the detail message no
            // longer carries the key either. A detail message is the most widely copied string on a
            // throwable - default uncaught-throwable logging, test reports and stack-trace aggregation
            // all reproduce it - so a natural key sitting there is a sixteen-digit primary account number
            // in three sinks that nobody chose. The key position is filled with a fixed placeholder, so
            // neither the value nor its length is recoverable from the message, and key() remains the one
            // path a caller takes deliberately.
            assertThat(exception.key())
                    .as("the substitution is only meaningful if there is detail to suppress")
                    .isEqualTo(key);
            assertThat(exception.getMessage())
                    .as("the resource name is a legacy DD name and stays, so the message shape is stable")
                    .contains("ACCTDAT");
            assertThat(exception.getMessage())
                    .as("the key position is a fixed placeholder, so neither the value nor its length"
                            + " survives in the message")
                    .doesNotContain(key)
                    .contains("key=***REDACTED***");

            assertThat(bodyOf(handler.handleRecordNotFound(exception)).message())
                    .doesNotContain(key)
                    .doesNotContain("ACCTDAT")
                    .doesNotContain("Account");
        }

        @Test
        @DisplayName("a miss raised with no detail at all maps identically, so the two forms are "
                + "indistinguishable to a client")
        void aMissWithNoDetailMapsIdentically() {
            final ResponseEntity<ErrorResponse> detailed = handler.handleRecordNotFound(
                    new RecordNotFoundException("Account", "00000000011", "ACCTDAT"));
            final ResponseEntity<ErrorResponse> bare =
                    handler.handleRecordNotFound(new RecordNotFoundException());

            assertThat(statusOf(bare)).isEqualTo(statusOf(detailed));
            assertThat(bodyOf(bare)).isEqualTo(bodyOf(detailed));
        }

        @Test
        @DisplayName("the body carries no field errors and no focus field")
        void theBodyCarriesNoFieldLevelDetail() {
            final ErrorResponse body = bodyOf(handler.handleRecordNotFound(
                    new RecordNotFoundException("Card", "4111111111111111", "CARDDAT")));

            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.focusScreenFieldId()).isNull();
            assertThat(body.message()).doesNotContain("4111111111111111");
        }
    }

    // =================================================================================================
    // THE VALIDATION MAPPING
    // =================================================================================================

    /**
     * Verifies the mapping for submitted data that failed validation, which is the only mapping that
     * carries field-level detail.
     */
    @Nested
    @DisplayName("the validation mapping")
    class ValidationMapping {

        @Test
        @DisplayName("a validation failure answers bad request")
        void aValidationFailureAnswersBadRequest() {
            final ResponseEntity<ErrorResponse> response =
                    handler.handleValidation(validationFailure("Please correct the field", List.of(
                            fieldError("acctId", "ACCTSID",
                                    ValidationException.FieldState.MISSING, "must be supplied"))));

            assertThat(statusOf(response)).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("the body carries the failure's own summary message, because a validation message is "
                + "written for the person who submitted the data")
        void theBodyCarriesTheSummaryMessage() {
            final ValidationException exception = validationFailure("Please correct the field",
                    List.of(fieldError("acctId", "ACCTSID",
                            ValidationException.FieldState.MISSING, "must be supplied")));

            assertThat(bodyOf(handler.handleValidation(exception)).message())
                    .isEqualTo("Please correct the field")
                    .isEqualTo(exception.getMessage());
        }

        @Test
        @DisplayName("field errors are translated one for one, in the order they were raised")
        void fieldErrorsAreTranslatedInOrder() {
            final ValidationException exception = validationFailure("Please correct the fields",
                    List.of(
                            fieldError("acctId", "ACCTSID",
                                    ValidationException.FieldState.MISSING, "must be supplied"),
                            fieldError("acctStatus", "ACSTTUS",
                                    ValidationException.FieldState.INVALID, "must be Y or N"),
                            fieldError("creditLimit", "ACRDLIM",
                                    ValidationException.FieldState.INVALID, "must be numeric")));

            final List<ErrorResponse.FieldError> translated =
                    bodyOf(handler.handleValidation(exception)).fieldErrors();

            assertThat(translated).hasSize(3);
            assertThat(translated).extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactly("acctId", "acctStatus", "creditLimit");
            assertThat(translated).extracting(ErrorResponse.FieldError::screenFieldId)
                    .containsExactly("ACCTSID", "ACSTTUS", "ACRDLIM");
            assertThat(translated).extracting(ErrorResponse.FieldError::message)
                    .containsExactly("must be supplied", "must be Y or N", "must be numeric");
        }

        @Test
        @DisplayName("a blank field maps to the missing state and a rejected field maps to the invalid "
                + "state, keeping the two error kinds distinguishable")
        void theTwoFieldStatesRemainDistinguishable() {
            final ValidationException exception = validationFailure("Please correct the fields",
                    List.of(
                            fieldError("acctId", "ACCTSID",
                                    ValidationException.FieldState.MISSING, "must be supplied"),
                            fieldError("acctStatus", "ACSTTUS",
                                    ValidationException.FieldState.INVALID, "must be Y or N")));

            assertThat(bodyOf(handler.handleValidation(exception)).fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("a field error with no state cannot be built at all, so the advice never has to "
                + "choose a state for the producer, and it still answers for both real states")
        void aStatelessFieldErrorCannotBeBuiltAndBothRealStatesStillAnswer() {
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
                    .isThrownBy(() -> fieldError("acctId", "ACCTSID", null, "must be supplied"))
                    .withMessageContaining("state must not be null")
                    .withMessageContaining("MISSING")
                    .withMessageContaining("INVALID");

            for (final ValidationException.FieldState state : ValidationException.FieldState.values()) {
                final ValidationException exception = validationFailure("Please correct the field",
                        List.of(fieldError("acctId", "ACCTSID", state, "must be supplied")));

                assertThatCode(() -> handler.handleValidation(exception)).doesNotThrowAnyException();
                assertThat(bodyOf(handler.handleValidation(exception)).fieldErrors())
                        .extracting(ErrorResponse.FieldError::state)
                        .containsExactly(ErrorResponse.FieldState.valueOf(state.name()));
            }
        }

        @Test
        @DisplayName("a field error with no field name is reported with an empty name, because the "
                + "response's field error rejects a null one outright")
        void aNamelessFieldErrorIsReportedWithAnEmptyName() {
            final ValidationException exception = validationFailure("Please correct the field",
                    List.of(fieldError(null, null, ValidationException.FieldState.INVALID, "bad")));

            assertThatCode(() -> handler.handleValidation(exception)).doesNotThrowAnyException();

            final List<ErrorResponse.FieldError> translated =
                    bodyOf(handler.handleValidation(exception)).fieldErrors();

            assertThat(translated).hasSize(1);
            assertThat(translated.getFirst().fieldName()).isEmpty();
            assertThat(translated.getFirst().screenFieldId()).isEmpty();
        }

        @Test
        @DisplayName("the focus field is the screen identifier of the first raised error, so the client "
                + "positions the cursor where the legacy screen did")
        void theFocusFieldIsTheFirstRaisedError() {
            final ValidationException exception = validationFailure("Please correct the fields",
                    List.of(
                            fieldError("acctStatus", "ACSTTUS",
                                    ValidationException.FieldState.INVALID, "must be Y or N"),
                            fieldError("acctId", "ACCTSID",
                                    ValidationException.FieldState.MISSING, "must be supplied")));

            assertThat(bodyOf(handler.handleValidation(exception)).focusScreenFieldId())
                    .isEqualTo("ACSTTUS");
        }

        @Test
        @DisplayName("there is no focus field when nothing was raised at field level")
        void thereIsNoFocusFieldWithoutFieldErrors() {
            final ErrorResponse body = bodyOf(handler.handleValidation(
                    new ValidationException("Please correct the field")));

            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.hasFieldErrors()).isFalse();
            assertThat(body.focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("there is no focus field when the first raised error names no screen field, rather "
                + "than an empty identifier the client would try to position on")
        void thereIsNoFocusFieldWhenTheFirstErrorNamesNoScreenField() {
            final ValidationException exception = validationFailure("Please correct the fields",
                    List.of(
                            fieldError("acctId", "", ValidationException.FieldState.MISSING, "missing"),
                            fieldError("acctStatus", "ACSTTUS",
                                    ValidationException.FieldState.INVALID, "invalid")));

            final ErrorResponse body = bodyOf(handler.handleValidation(exception));

            assertThat(body.focusScreenFieldId()).isNull();
            assertThat(body.fieldErrors()).hasSize(2);
        }

        @Test
        @DisplayName("a whitespace-only screen identifier is treated as naming no field, matching the "
                + "blank-filled fixed-width field it came from")
        void aWhitespaceOnlyScreenIdentifierNamesNoField() {
            final ValidationException exception = validationFailure("Please correct the field",
                    List.of(fieldError("acctId", "   ",
                            ValidationException.FieldState.MISSING, "missing")));

            assertThat(bodyOf(handler.handleValidation(exception)).focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("a per-field message may be absent without failing the translation")
        void aPerFieldMessageMayBeAbsent() {
            final ValidationException exception = validationFailure("Please correct the field",
                    List.of(fieldError("acctId", "ACCTSID",
                            ValidationException.FieldState.MISSING, null)));

            final List<ErrorResponse.FieldError> translated =
                    bodyOf(handler.handleValidation(exception)).fieldErrors();

            assertThat(translated).hasSize(1);
            assertThat(translated.getFirst().message()).isNull();
            assertThat(translated.getFirst().fieldName()).isEqualTo("acctId");
        }

        @Test
        @DisplayName("the reported field errors are unmodifiable, so a caller cannot alter a response "
                + "after it is built")
        void theReportedFieldErrorsAreUnmodifiable() {
            final List<ErrorResponse.FieldError> translated = bodyOf(handler.handleValidation(
                    validationFailure("Please correct the field", List.of(fieldError("acctId",
                            "ACCTSID", ValidationException.FieldState.MISSING, "missing")))))
                    .fieldErrors();

            assertThat(translated).isUnmodifiable();
        }
    }

    // =================================================================================================
    // THE CONCURRENT-UPDATE MAPPING
    // =================================================================================================

    /**
     * Verifies the mapping for a concurrent update conflict.
     */
    @Nested
    @DisplayName("the concurrent-update mapping")
    class OptimisticLockMapping {

        @Test
        @DisplayName("a conflict answers with a conflict status")
        void aConflictAnswersWithAConflictStatus() {
            final ResponseEntity<ErrorResponse> response =
                    handler.handleOptimisticLockConflict(new OptimisticLockConflictException(
                            OptimisticLockConflictException.ConflictKind
                                    .RECORD_CHANGED_BEFORE_UPDATE,
                            "Account", "00000000011"));

            assertThat(statusOf(response)).isEqualTo(HttpStatus.CONFLICT);
            assertThat(statusOf(response).is4xxClientError()).isTrue();
        }

        @Test
        @DisplayName("the body carries the legacy operator text for the kind of conflict that occurred")
        void theBodyCarriesTheLegacyOperatorText() {
            final ErrorResponse body =
                    bodyOf(handler.handleOptimisticLockConflict(new OptimisticLockConflictException(
                            OptimisticLockConflictException.ConflictKind
                                    .RECORD_CHANGED_BEFORE_UPDATE,
                            "Account", "00000000011")));

            assertThat(body.message()).isEqualTo(
                    OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
        }

        @Test
        @DisplayName("every kind of conflict answers with the same status but its own text, so the client "
                + "can distinguish them by message rather than by status")
        void everyKindAnswersWithTheSameStatusAndItsOwnText() {
            final List<String> messages = new ArrayList<>();

            for (final OptimisticLockConflictException.ConflictKind kind
                    : OptimisticLockConflictException.ConflictKind.values()) {
                final ResponseEntity<ErrorResponse> response = handler.handleOptimisticLockConflict(
                        new OptimisticLockConflictException(kind, "Account", "00000000011"));

                assertThat(statusOf(response)).as("status for %s", kind)
                        .isEqualTo(HttpStatus.CONFLICT);
                messages.add(bodyOf(response).message());
            }

            assertThat(messages).hasSize(
                    OptimisticLockConflictException.ConflictKind.values().length);
            assertThat(messages.stream().distinct().toList()).hasSameSizeAs(messages);
        }

        @Test
        @DisplayName("a lock failure on the customer record reports the customer-specific text, which the "
                + "legacy program distinguished from the account one")
        void aCustomerLockFailureReportsItsOwnText() {
            final ErrorResponse account =
                    bodyOf(handler.handleOptimisticLockConflict(new OptimisticLockConflictException(
                            OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED,
                            "Account", "00000000011")));
            final ErrorResponse customer =
                    bodyOf(handler.handleOptimisticLockConflict(new OptimisticLockConflictException(
                            OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED,
                            "Customer", "000000011")));

            assertThat(account.message()).isEqualTo(
                    OptimisticLockConflictException.MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE);
            assertThat(customer.message()).isEqualTo(
                    OptimisticLockConflictException.MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE);
            assertThat(account.message()).isNotEqualTo(customer.message());
        }

        @Test
        @DisplayName("the conflicting record's key does not reach the response")
        void theConflictingKeyDoesNotReachTheResponse() {
            final ErrorResponse body =
                    bodyOf(handler.handleOptimisticLockConflict(new OptimisticLockConflictException(
                            OptimisticLockConflictException.ConflictKind
                                    .RECORD_CHANGED_BEFORE_UPDATE,
                            "Account", "00000000011")));

            assertThat(body.message()).doesNotContain("00000000011");
            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.focusScreenFieldId()).isNull();
        }
    }

    // =================================================================================================
    // THE JOB-SUBMISSION MAPPING
    // =================================================================================================

    /**
     * Verifies the one mapping that answers with a success status, reproducing the legacy queue
     * definition's ignore-on-error behaviour.
     */
    @Nested
    @DisplayName("the job-submission mapping")
    class JobSubmissionMapping {

        @Test
        @DisplayName("a failed job submission answers with success, because the legacy queue ignored "
                + "write errors and the transaction continued")
        void aFailedSubmissionAnswersWithSuccess() {
            final ResponseEntity<ErrorResponse> response = handler.handleJobSubmission(
                    new JobSubmissionException("JOBS", "17", "0", 4,
                            new IllegalStateException("queue unavailable")));

            assertThat(statusOf(response)).isEqualTo(HttpStatus.OK);
            assertThat(statusOf(response).is2xxSuccessful()).isTrue();
            assertThat(statusOf(response).isError())
                    .as("answering with an error would change the legacy observable behaviour")
                    .isFalse();
        }

        @Test
        @DisplayName("the body carries the operator text the legacy program displayed, verbatim")
        void theBodyCarriesTheLegacyOperatorText() {
            assertThat(bodyOf(handler.handleJobSubmission(
                    new JobSubmissionException(new IllegalStateException("queue")))).message())
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("the reported text is the fixed one whatever the failing card ordinal or response "
                + "codes were, so no queue diagnostic reaches the client")
        void theReportedTextIsFixedWhateverTheDiagnostics() {
            final List<String> messages = new ArrayList<>();
            for (int ordinal = 1; ordinal <= 4; ordinal++) {
                messages.add(bodyOf(handler.handleJobSubmission(new JobSubmissionException(
                        "JOBS", "17", "0", ordinal, new IllegalStateException("queue")))).message());
            }

            assertThat(messages).containsOnly(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("a submission failure with no ordinal at all maps identically")
        void aSubmissionFailureWithNoOrdinalMapsIdentically() {
            final JobSubmissionException bare =
                    new JobSubmissionException(new IllegalStateException("queue"));

            assertThat(bare.failedCardOrdinal())
                    .isEqualTo(JobSubmissionException.ORDINAL_NOT_APPLICABLE);
            assertThat(statusOf(handler.handleJobSubmission(bare))).isEqualTo(HttpStatus.OK);
            assertThat(bodyOf(handler.handleJobSubmission(bare)).message())
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("the body carries no field errors, because a queue write failure is not a "
                + "field-level failure")
        void theBodyCarriesNoFieldLevelDetail() {
            final ErrorResponse body = bodyOf(handler.handleJobSubmission(
                    new JobSubmissionException("JOBS", "17", "0", 4,
                            new IllegalStateException("queue"))));

            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.focusScreenFieldId()).isNull();
        }
    }

    // =================================================================================================
    // INVARIANTS ACROSS EVERY MAPPING
    // =================================================================================================

    /**
     * Verifies the properties every mapping shares, so a mapping added later cannot quietly break them.
     */
    @Nested
    @DisplayName("invariants across every mapping")
    class SharedInvariants {

        @Test
        @DisplayName("every mapping answers with a body")
        void everyMappingAnswersWithABody() {
            final List<ResponseEntity<ErrorResponse>> responses = everyMapping();

            assertThat(responses).hasSize(6);
            for (final ResponseEntity<ErrorResponse> response : responses) {
                assertThat(response.getBody()).isNotNull();
            }
        }

        @Test
        @DisplayName("every reported message is present and non-blank, so no mapping answers with an "
                + "empty explanation")
        void everyReportedMessageIsPresentAndNonBlank() {
            for (final ResponseEntity<ErrorResponse> response : everyMapping()) {
                assertThat(bodyOf(response).message()).isNotNull().isNotBlank();
            }
        }

        @Test
        @DisplayName("no reported message names a Java type, a package or a stack frame")
        void noReportedMessageLeaksImplementationDetail() {
            for (final ResponseEntity<ErrorResponse> response : everyMapping()) {
                assertThat(bodyOf(response).message())
                        .doesNotContain("com.carddemo")
                        .doesNotContain("Exception")
                        .doesNotContain("java.")
                        .doesNotContain(".java:")
                        .doesNotContain("at ");
            }
        }

        @Test
        @DisplayName("every reported field-error list is present, so a client never has to guard against "
                + "an absent collection")
        void everyReportedFieldErrorListIsPresent() {
            for (final ResponseEntity<ErrorResponse> response : everyMapping()) {
                assertThat(bodyOf(response).fieldErrors()).isNotNull();
            }
        }

        @Test
        @DisplayName("only the validation mapping carries field-level detail")
        void onlyValidationCarriesFieldLevelDetail() {
            final List<ResponseEntity<ErrorResponse>> responses = everyMapping();
            int withFieldErrors = 0;

            for (final ResponseEntity<ErrorResponse> response : responses) {
                if (bodyOf(response).hasFieldErrors()) {
                    withFieldErrors++;
                }
            }

            assertThat(withFieldErrors).isOne();
        }

        @Test
        @DisplayName("the six mappings use five distinct statuses, one of them successful")
        void theSixMappingsUseFiveDistinctStatuses() {
            final List<HttpStatusCode> statuses = new ArrayList<>();
            for (final ResponseEntity<ErrorResponse> response : everyMapping()) {
                statuses.add(statusOf(response));
            }

            assertThat(statuses).containsExactly(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    HttpStatus.NOT_FOUND,
                    HttpStatus.BAD_REQUEST,
                    HttpStatus.CONFLICT,
                    HttpStatus.OK);
            assertThat(statuses.stream().distinct().toList()).hasSize(5);
            assertThat(statuses.stream().filter(HttpStatusCode::is2xxSuccessful).toList())
                    .hasSize(1);
        }

        @Test
        @DisplayName("the handler holds no state, so two instances answer identically")
        void theHandlerHoldsNoState() {
            final GlobalExceptionHandler other = new GlobalExceptionHandler();
            final RecordNotFoundException exception =
                    new RecordNotFoundException("Account", "00000000011", "ACCTDAT");

            assertThat(bodyOf(other.handleRecordNotFound(exception)))
                    .isEqualTo(bodyOf(handler.handleRecordNotFound(exception)));
        }
    }

    // =================================================================================================
    // DISPATCH WIRING
    // =================================================================================================

    /**
     * Verifies that the mappings are actually reached through real request dispatch, rather than only
     * behaving correctly when called directly.
     */
    @Nested
    @DisplayName("dispatch wiring")
    class DispatchWiring {

        /** A standalone dispatcher carrying the probe controller and the real advice. */
        private final MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        @Test
        @DisplayName("a keyed-read miss thrown from a controller is intercepted and answered not found "
                + "with the fixed text")
        void aKeyedReadMissIsInterceptedOnDispatch() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.get("/probe/record-not-found"))
                    .andExpect(MockMvcResultMatchers.status().isNotFound())
                    .andExpect(MockMvcResultMatchers.content()
                            .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.message")
                            .value(RECORD_NOT_FOUND_MESSAGE))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.fieldErrors").isEmpty());
        }

        @Test
        @DisplayName("a failed job submission thrown from a controller is intercepted and answered with "
                + "success, proving the surprising mapping survives real dispatch")
        void aFailedSubmissionIsInterceptedOnDispatch() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.get("/probe/job-submission"))
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.message")
                            .value(JobSubmissionException.DEFAULT_MESSAGE));
        }

        @Test
        @DisplayName("a validation failure thrown from a controller is intercepted with its field errors "
                + "and focus field intact")
        void aValidationFailureIsInterceptedOnDispatch() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.get("/probe/validation"))
                    .andExpect(MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.message")
                            .value("Please correct the field"))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.fieldErrors[0].screenFieldId")
                            .value("ACCTSID"))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.fieldErrors[0].state")
                            .value("MISSING"))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.focusScreenFieldId")
                            .value("ACCTSID"));
        }

        @Test
        @DisplayName("a file-status failure thrown from a controller is intercepted and the dataset name "
                + "does not appear anywhere in the served body")
        void aFileStatusFailureIsInterceptedOnDispatch() throws Exception {
            final String body = mockMvc.perform(MockMvcRequestBuilders.get("/probe/file-status"))
                    .andExpect(MockMvcResultMatchers.status().isInternalServerError())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(body)
                    .contains(AbendException.DEFAULT_MESSAGE)
                    .doesNotContain("ACCTDAT")
                    .doesNotContain(FAILING_FILE_STATUS);
        }

        @Test
        @DisplayName("a request that raises nothing is served normally, so the advice does not interfere "
                + "with a successful call")
        void aSuccessfulRequestIsUnaffected() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.get("/probe/ok"))
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.content().string("ok"));
        }
    }

    /**
     * A probe controller that raises one failure per endpoint, so real dispatch can be exercised.
     *
     * <p>It lives in the same package as the advice under test, which is what the advice's own package
     * selector requires; a probe in another package would not be advised and the dispatch group would be
     * asserting nothing.
     */
    @RestController
    @RequestMapping("/probe")
    static final class ProbeController {

        /**
         * Serves a successful response.
         *
         * @return a fixed body
         */
        @GetMapping("/ok")
        String ok() {
            return "ok";
        }

        /**
         * Raises a keyed-read miss.
         *
         * @return never returns
         */
        @GetMapping("/record-not-found")
        String recordNotFound() {
            throw new RecordNotFoundException("Account", "00000000011", "ACCTDAT");
        }

        /**
         * Raises a failed job submission.
         *
         * @return never returns
         */
        @GetMapping("/job-submission")
        String jobSubmission() {
            throw new JobSubmissionException("JOBS", "17", "0", 4,
                    new IllegalStateException("queue unavailable"));
        }

        /**
         * Raises a validation failure carrying one field error.
         *
         * @return never returns
         */
        @GetMapping("/validation")
        String validation() {
            throw new ValidationException("Please correct the field",
                    List.of(new ValidationException.FieldError("acctId", "ACCTSID",
                            ValidationException.FieldState.MISSING, "must be supplied")));
        }

        /**
         * Raises an unhandled file operation failure.
         *
         * @return never returns
         */
        @GetMapping("/file-status")
        String fileStatus() {
            throw new FileStatusException(FAILING_FILE_STATUS, "READ", "ACCTDAT");
        }
    }
}
