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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.batch.step.AbstractCobolStep;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.exception.JobSubmissionException;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Exercises the single REST failure adapter of the migrated API layer.
 *
 * <h2>What is under test</h2>
 * {@link GlobalExceptionHandler} turns each of the six failure carriers of the migrated estate into
 * an HTTP status and a sanitized body. It has no legacy counterpart as a component, because the
 * legacy transactions each handled their own failures inline and wrote their diagnostics to the
 * console; what it does have is a legacy counterpart per failure, and those are what fix the status
 * and body of each response. The tests therefore assert the mapping one failure at a time.
 *
 * <h2>Why the job-submission failure returns a success status</h2>
 * The one mapping that looks wrong and is not is job submission. The legacy transient data queue that
 * carried a submitted job was defined with the ignore-on-error option, and the reporting transaction
 * that wrote to it placed a message on the screen and carried on rather than abandoning the
 * conversation. Reproducing that means the request completes: the caller is told, in the body, that
 * no job was submitted, and the status stays successful because the legacy transaction did not fail.
 * A five-hundred here would be a behavioural change dressed up as correctness.
 *
 * <h2>Why the response body is asserted to be sanitized</h2>
 * Two failures carry internal detail that must not cross the boundary. A file-status failure carries
 * a two-byte COBOL status code and the data set name it was raised against, and a record-not-found
 * failure carries the key that was looked up. Neither reaches the body: the first is replaced by the
 * generic abend text and the second by a fixed phrase, so a caller learns the outcome without
 * learning the estate's internals. Both are asserted by checking that the body does <em>not</em>
 * contain the internal detail, because an assertion that only checked the expected text would pass
 * even if the detail were appended to it.
 *
 * <p>Provenance: the legacy authorities are the common-message copybook {@code app/cpy/CSMSG01Y.cpy},
 * the queue definition {@code app/csd/CARDDEMO.CSD} and the account-maintenance rollback at
 * {@code app/cbl/COACTUPC.cbl}, at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.</p>
 */
@DisplayName("GlobalExceptionHandler - the six failure carriers at the REST boundary")
class GlobalExceptionHandlerBoundaryTest {

    /** Fixed phrase a keyed read that found nothing reports, carrying no key. */
    private static final String RECORD_NOT_FOUND_MESSAGE = "Record not found";

    /** Program name standing in for the legacy culprit field. */
    private static final String PROGRAM = "COACTUPC";

    /** Data set name that must never reach a response body. */
    private static final String RESOURCE = "ACCTDAT";

    /** Account key that must never reach a response body. */
    private static final String KEY = "00000000011";

    /** Java-side field name of a validated screen field. */
    private static final String FIELD = "acctStatus";

    /** Screen field identifier of the same field, from the account-maintenance mapset. */
    private static final String SCREEN_FIELD = "ACSTTUS";

    /** The adapter under test; it holds no state, so one instance per test method suffices. */
    private GlobalExceptionHandler handler;

    /** Creates a fresh adapter before each test. */
    @BeforeEach
    void createHandler() {
        handler = new GlobalExceptionHandler();
    }

    @Nested
    @DisplayName("abend - the terminal failure that reached the boundary")
    class Abend {

        @Test
        @DisplayName("an abend is reported as a server error carrying its terminal message")
        void anAbendIsReportedAsAServerError() {
            AbendException abend = new AbendException(
                    AbendException.ONLINE_ABEND_CODE, PROGRAM, "ACCOUNT MASTER UNAVAILABLE",
                    "PLEASE CONTACT SYSTEM ADMINISTRATOR.");

            ResponseEntity<ErrorResponse> response = handler.handleAbend(abend);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .isEqualTo("PLEASE CONTACT SYSTEM ADMINISTRATOR.");
        }

        @Test
        @DisplayName("an abend carries no field errors and no focus field")
        void anAbendCarriesNoFieldErrors() {
            AbendException abend = new AbendException(PROGRAM, "ACCOUNT MASTER UNAVAILABLE");

            ResponseEntity<ErrorResponse> response = handler.handleAbend(abend);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().hasFieldErrors()).isFalse();
            assertThat(response.getBody().focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("an abend with no terminal message falls back to the default abend text")
        void anAbendWithNoMessageFallsBackToTheDefault() {
            AbendException abend = new AbendException(PROGRAM, "ACCOUNT MASTER UNAVAILABLE");

            ResponseEntity<ErrorResponse> response = handler.handleAbend(abend);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(AbendException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("an abend produced by a real batch step keeps its internal file diagnostic off "
                + "the wire: neither the resource name nor the raw two-byte status reaches the body")
        void aBatchStepAbendKeepsItsFileDiagnosticOffTheWire() {
            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> new FailingReadStep(new SimpleMeterRegistry())
                            .execute(null, null))
                    .actual();

            // What the step composed, faithfully reproducing the legacy DISPLAY text.
            assertThat(abend.getMessage())
                    .contains(BATCH_RESOURCE)
                    .contains(BATCH_RAW_STATUS);
            assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);

            ResponseEntity<ErrorResponse> response = handler.handleAbend(abend);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .isEqualTo(AbendException.DEFAULT_MESSAGE)
                    .doesNotContain(BATCH_RESOURCE)
                    .doesNotContain(BATCH_RAW_STATUS)
                    .doesNotContain(BATCH_PROGRAM)
                    .doesNotContain("FILE STATUS");
            assertThat(response.getBody().hasFieldErrors()).isFalse();
        }
    }

    /**
     * The legacy data set name the batch diagnostic would have named, and which must not cross the
     * boundary. It is the resource name the {@code CLOSEFIL} and {@code OPENFIL} job streams toggle,
     * so publishing it would name an internal file to an end user.
     */
    private static final String BATCH_RESOURCE = "ACCTFILE";

    /** The raw two-character status the batch diagnostic would have reported. */
    private static final String BATCH_RAW_STATUS = "35";

    /** The legacy program the failing step stands in for, which is also the abend culprit. */
    private static final String BATCH_PROGRAM = "CBACT01C";

    /**
     * A minimal concrete batch step whose guarded read reports a bad status, so the abend under test
     * is composed by {@code AbstractCobolStep} itself rather than hand-written to look like one.
     *
     * <p>Constructing the exception by hand would prove only that the handler withholds text a test
     * wrote; driving the real template proves it withholds the text the batch tier actually
     * produces.</p>
     */
    private static final class FailingReadStep extends AbstractCobolStep<String> {

        private FailingReadStep(final MeterRegistry meterRegistry) {
            super(BATCH_PROGRAM, meterRegistry, Clock.systemUTC());
        }

        /** Fails the read the way the legacy did: a bad status rather than a thrown failure. */
        @Override
        protected Optional<String> readNextRecord() {
            abendOnIoFailure(IoOperation.READ, BATCH_RESOURCE, BATCH_RAW_STATUS);
            return Optional.empty();
        }

        @Override
        protected void openResources() {
            // No handle to acquire: the read fails before any resource would be used.
        }

        @Override
        protected void processRecord(final String record) {
            throw new AssertionError("the read abends, so no record is ever processed");
        }

        @Override
        protected void closeResources() {
            throw new AssertionError("the legacy close paragraphs never run after an abend");
        }
    }

    @Nested
    @DisplayName("file status - an input-output failure that no service handled")
    class FileStatus {

        @Test
        @DisplayName("an unhandled file failure is reported as a server error")
        void anUnhandledFileFailureIsAServerError() {
            FileStatusException failure = new FileStatusException("23", "READING", RESOURCE);

            ResponseEntity<ErrorResponse> response = handler.handleFileStatus(failure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(AbendException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("neither the status code nor the data set name reaches the body")
        void neitherStatusNorDataSetNameReachesTheBody() {
            FileStatusException failure = new FileStatusException("31", "WRITING TO", RESOURCE);

            ResponseEntity<ErrorResponse> response = handler.handleFileStatus(failure);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .doesNotContain("31")
                    .doesNotContain(RESOURCE)
                    .doesNotContain("WRITING TO");
        }

        @Test
        @DisplayName("the body is the same whichever status was raised")
        void theBodyIsTheSameWhicheverStatusWasRaised() {
            ResponseEntity<ErrorResponse> first =
                    handler.handleFileStatus(new FileStatusException("23", "READING", RESOURCE));
            ResponseEntity<ErrorResponse> second =
                    handler.handleFileStatus(new FileStatusException("35", "OPENING", RESOURCE));

            assertThat(first.getBody()).isEqualTo(second.getBody());
        }
    }

    @Nested
    @DisplayName("record not found - the legacy status 23 read")
    class RecordNotFound {

        @Test
        @DisplayName("a missing record is reported as not found, not as a server error")
        void aMissingRecordIsReportedAsNotFound() {
            RecordNotFoundException missing =
                    new RecordNotFoundException("ACCOUNT", KEY, RESOURCE);

            ResponseEntity<ErrorResponse> response = handler.handleRecordNotFound(missing);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(RECORD_NOT_FOUND_MESSAGE);
        }

        @Test
        @DisplayName("the key that was looked up does not reach the body")
        void theKeyDoesNotReachTheBody() {
            RecordNotFoundException missing =
                    new RecordNotFoundException("ACCOUNT", KEY, RESOURCE);

            ResponseEntity<ErrorResponse> response = handler.handleRecordNotFound(missing);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .doesNotContain(KEY)
                    .doesNotContain(RESOURCE);
        }

        @Test
        @DisplayName("the fixed phrase is used even when the failure names nothing at all")
        void theFixedPhraseIsUsedForAnUnnamedFailure() {
            ResponseEntity<ErrorResponse> response =
                    handler.handleRecordNotFound(new RecordNotFoundException());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(RECORD_NOT_FOUND_MESSAGE);
        }
    }

    @Nested
    @DisplayName("validation - the field-level error surface of the legacy screens")
    class Validation {

        @Test
        @DisplayName("a validation failure is reported as a bad request carrying its own message")
        void aValidationFailureIsABadRequest() {
            ValidationException failure = new ValidationException("Please correct the fields marked");

            ResponseEntity<ErrorResponse> response = handler.handleValidation(failure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo("Please correct the fields marked");
        }

        @Test
        @DisplayName("a blank field is reported as missing, distinct from a rejected value")
        void aBlankFieldIsReportedAsMissing() {
            ValidationException failure = new ValidationException(
                    FIELD, SCREEN_FIELD, ValidationException.FieldState.MISSING,
                    "Account Status must be supplied");

            ResponseEntity<ErrorResponse> response = handler.handleValidation(failure);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors())
                    .containsExactly(new ErrorResponse.FieldError(
                            FIELD, SCREEN_FIELD, ErrorResponse.FieldState.MISSING,
                            "Account Status must be supplied"));
        }

        @Test
        @DisplayName("a rejected value is reported as invalid, distinct from an absent one")
        void aRejectedValueIsReportedAsInvalid() {
            ValidationException failure = new ValidationException(
                    FIELD, SCREEN_FIELD, ValidationException.FieldState.INVALID,
                    "Account Status must be Y or N");

            ResponseEntity<ErrorResponse> response = handler.handleValidation(failure);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors())
                    .singleElement()
                    .satisfies(fieldError -> assertThat(fieldError.state())
                            .isEqualTo(ErrorResponse.FieldState.INVALID));
        }

        @Test
        @DisplayName("an unstated field state is refused where it is produced, and neither of the two "
                + "states the legacy flag can hold is silently substituted for the other")
        void anUnstatedFieldStateIsRefusedRatherThanSubstituted() {
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
                    .isThrownBy(() -> new ValidationException.FieldError(
                            FIELD, SCREEN_FIELD, null, "Account Status is not acceptable"))
                    .withMessageContaining("state must not be null")
                    .withMessageContaining("MISSING")
                    .withMessageContaining("INVALID");

            for (ValidationException.FieldState state : ValidationException.FieldState.values()) {
                ValidationException failure = new ValidationException(
                        "Please correct the fields marked",
                        List.of(new ValidationException.FieldError(
                                FIELD, SCREEN_FIELD, state, "Account Status is not acceptable")));

                ResponseEntity<ErrorResponse> response = handler.handleValidation(failure);

                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().fieldErrors())
                        .singleElement()
                        .satisfies(fieldError -> assertThat(fieldError.state())
                                .as("no substitution between the two states")
                                .isEqualTo(ErrorResponse.FieldState.valueOf(state.name())));
            }
        }

        @Test
        @DisplayName("the cursor is placed on the first field in error, as the legacy screens did")
        void theCursorIsPlacedOnTheFirstFieldInError() {
            ValidationException failure = new ValidationException(
                    "Please correct the fields marked",
                    List.of(
                            new ValidationException.FieldError(
                                    FIELD, SCREEN_FIELD,
                                    ValidationException.FieldState.MISSING, "first"),
                            new ValidationException.FieldError(
                                    "creditLimit", "ACRDLIM",
                                    ValidationException.FieldState.INVALID, "second")));

            ResponseEntity<ErrorResponse> response = handler.handleValidation(failure);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().focusScreenFieldId()).isEqualTo(SCREEN_FIELD);
            assertThat(response.getBody().fieldErrors()).hasSize(2);
        }

        @Test
        @DisplayName("no field in error leaves the cursor unplaced")
        void noFieldInErrorLeavesTheCursorUnplaced() {
            ResponseEntity<ErrorResponse> response = handler.handleValidation(
                    new ValidationException("Please correct the fields marked"));

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().focusScreenFieldId()).isNull();
            assertThat(response.getBody().hasFieldErrors()).isFalse();
        }

        @Test
        @DisplayName("a field error with no screen identifier leaves the cursor unplaced")
        void aFieldErrorWithNoScreenIdentifierLeavesTheCursorUnplaced() {
            ValidationException failure = new ValidationException(
                    FIELD, "", ValidationException.FieldState.INVALID, "not acceptable");

            ResponseEntity<ErrorResponse> response = handler.handleValidation(failure);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().focusScreenFieldId()).isNull();
            assertThat(response.getBody().fieldErrors()).hasSize(1);
        }

        @Test
        @DisplayName("an absent field name and screen identifier become empty, never null")
        void anAbsentFieldNameAndScreenIdentifierBecomeEmpty() {
            ValidationException failure = new ValidationException(
                    "Please correct the fields marked",
                    List.of(new ValidationException.FieldError(
                            null, null, ValidationException.FieldState.MISSING, null)));

            ResponseEntity<ErrorResponse> response = handler.handleValidation(failure);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors())
                    .singleElement()
                    .satisfies(fieldError -> {
                        assertThat(fieldError.fieldName()).isEmpty();
                        assertThat(fieldError.screenFieldId()).isEmpty();
                        assertThat(fieldError.message()).isNull();
                    });
        }

        @Test
        @DisplayName("every field error is carried across in submission order")
        void everyFieldErrorIsCarriedAcrossInOrder() {
            ValidationException failure = new ValidationException(
                    "Please correct the fields marked",
                    List.of(
                            new ValidationException.FieldError(
                                    "a", "AAA", ValidationException.FieldState.MISSING, "one"),
                            new ValidationException.FieldError(
                                    "b", "BBB", ValidationException.FieldState.INVALID, "two"),
                            new ValidationException.FieldError(
                                    "c", "CCC", ValidationException.FieldState.MISSING, "three")));

            ResponseEntity<ErrorResponse> response = handler.handleValidation(failure);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactly("a", "b", "c");
        }
    }

    @Nested
    @DisplayName("optimistic lock conflict - the before-and-after image compare")
    class LockConflict {

        @Test
        @DisplayName("a concurrent change is reported as a conflict, not as a bad request")
        void aConcurrentChangeIsReportedAsAConflict() {
            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE,
                    "Account", KEY);

            ResponseEntity<ErrorResponse> response =
                    handler.handleOptimisticLockConflict(conflict);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(conflict.getMessage());
        }

        @Test
        @DisplayName("a failed update after a successful lock is also a conflict")
        void aFailedUpdateAfterLockIsAlsoAConflict() {
            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    OptimisticLockConflictException.ConflictKind.UPDATE_FAILED_AFTER_LOCK,
                    "Card", "4444333322221111");

            ResponseEntity<ErrorResponse> response =
                    handler.handleOptimisticLockConflict(conflict);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .isEqualTo(OptimisticLockConflictException.MSG_LOCKED_BUT_UPDATE_FAILED);
        }

        @Test
        @DisplayName("a conflict carries no field errors, because no field was rejected")
        void aConflictCarriesNoFieldErrors() {
            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED,
                    "Account", KEY);

            ResponseEntity<ErrorResponse> response =
                    handler.handleOptimisticLockConflict(conflict);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().hasFieldErrors()).isFalse();
            assertThat(response.getBody().focusScreenFieldId()).isNull();
        }
    }

    @Nested
    @DisplayName("job submission - the ignore-on-error queue write")
    class JobSubmission {

        @Test
        @DisplayName("a failed submission completes the request, reproducing ignore-on-error")
        void aFailedSubmissionCompletesTheRequest() {
            JobSubmissionException failure = new JobSubmissionException(
                    "JOBS.fifo", "0016", "0000", 3, new IllegalStateException("no route"));

            ResponseEntity<ErrorResponse> response = handler.handleJobSubmission(failure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("the queue name and the response codes do not reach the body")
        void theQueueNameAndCodesDoNotReachTheBody() {
            JobSubmissionException failure = new JobSubmissionException(
                    "JOBS.fifo", "0016", "0084", 3, new IllegalStateException("no route"));

            ResponseEntity<ErrorResponse> response = handler.handleJobSubmission(failure);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .doesNotContain("JOBS.fifo")
                    .doesNotContain("0016")
                    .doesNotContain("0084");
        }

        @Test
        @DisplayName("a submission failure with no card ordinal reports the same body and status")
        void aSubmissionFailureWithNoOrdinalReportsTheSameBody() {
            JobSubmissionException failure =
                    new JobSubmissionException(new IllegalStateException("no route"));

            ResponseEntity<ErrorResponse> response = handler.handleJobSubmission(failure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("a submission failure carries no field errors and no focus field")
        void aSubmissionFailureCarriesNoFieldErrors() {
            JobSubmissionException failure =
                    new JobSubmissionException(new IllegalStateException("no route"));

            ResponseEntity<ErrorResponse> response = handler.handleJobSubmission(failure);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().hasFieldErrors()).isFalse();
            assertThat(response.getBody().focusScreenFieldId()).isNull();
        }
    }

    @Nested
    @DisplayName("status separation - no two failures share a status by accident")
    class StatusSeparation {

        @Test
        @DisplayName("the six failures map onto five distinct statuses")
        void theSixFailuresMapOntoFiveStatuses() {
            List<HttpStatusCode> statuses = List.of(
                    handler.handleAbend(
                            new AbendException(PROGRAM, "REASON")).getStatusCode(),
                    handler.handleFileStatus(
                            new FileStatusException("23", "READING", RESOURCE)).getStatusCode(),
                    handler.handleRecordNotFound(
                            new RecordNotFoundException()).getStatusCode(),
                    handler.handleValidation(
                            new ValidationException("bad")).getStatusCode(),
                    handler.handleOptimisticLockConflict(
                            new OptimisticLockConflictException(
                                    OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED,
                                    "Account", KEY)).getStatusCode(),
                    handler.handleJobSubmission(
                            new JobSubmissionException(
                                    new IllegalStateException("no route"))).getStatusCode());

            assertThat(statuses).containsExactly(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    HttpStatus.NOT_FOUND,
                    HttpStatus.BAD_REQUEST,
                    HttpStatus.CONFLICT,
                    HttpStatus.OK);
        }
    }
}
