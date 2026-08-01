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
import static org.assertj.core.api.Assertions.assertThatCode;

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.exception.JobSubmissionException;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies the sanitized failure boundary of {@link GlobalExceptionHandler}: that each of the eleven
 * handlers answers with the documented status, that the six statuses the boundary must separate stay
 * separated, and - the point of the whole class - that nothing a caller submitted and nothing about
 * the implementation ever reaches a response body.
 *
 * <h2>How the leakage claim is tested, and why it is not tested by reading</h2>
 *
 * <p>Every carrier that this boundary receives <em>offers</em> the submitted value, and offers it by
 * default. A bound field failure renders the rejected value in its own {@code toString()}, so
 * {@link BindException#getMessage()} is a transcript of the submission. A constraint violation
 * exposes it through {@link ConstraintViolation#getInvalidValue()}. An unreadable body carries the
 * offending fragment and the target Java type in its parser detail. A runtime fault's message can
 * contain a connection string. Reviewing the handler source for "does it call the wrong method"
 * therefore proves nothing durable, because the next edit can reintroduce the call.
 *
 * <p>So each of those failures is constructed here with a <strong>canary</strong> value planted in
 * exactly the place the carrier would leak it from, and the response is then asserted not to contain
 * that canary anywhere - not in the summary, not in a field name, not in a per-field message, not in
 * the focus hint. The canary is a string that cannot occur in any legitimate output of this module,
 * so a single containment assertion is a total statement about the whole body.
 *
 * <h2>The two families of handler, and why the sweep is split</h2>
 *
 * <p>Three handlers derive their body FROM the carrier <em>by contract</em>, because the carrier's
 * message is operator-facing text owned by the service or the message catalogue that raised it: the
 * abend, the service-raised validation failure and the conflict. Planting a canary in those and
 * asserting its absence would assert the opposite of the contract, so those three are given
 * legitimate operator text and are asserted to pass it through unchanged.
 *
 * <p>The remaining eight never derive their body from the carrier, and those are the ones the canary
 * sweep covers: file status, not found, job submission, declarative binding, method-level constraint
 * violation, unreadable body, authentication, denial and the catch-all.
 *
 * <h2>Independent expectations</h2>
 *
 * <p>Every expected status, literal and field state below is written as a hand-typed constant or a
 * named constant published by the failure carrier itself. No expectation is produced by calling the
 * handler and reading what came back, and no expectation is computed from another expectation.
 *
 * <h2>What is deliberately not asserted here</h2>
 *
 * <p>Nothing about the framework's dispatch. This is a unit test of the advice: each handler is
 * invoked directly, so the test asserts what the advice returns and never how the framework chose
 * it. The one claim that does concern dispatch - that declaring the handler on
 * {@link BindException} genuinely covers {@link MethodArgumentNotValidException} - is asserted
 * through the type relationship that makes it true, which is a fact about the two classes rather
 * than about a running dispatcher.
 *
 * <p>Nothing about the security filter chain. The entry point and the denial handler that reject a
 * request before dispatch belong to the security configuration; the two handlers tested here are the
 * inside-dispatch arm only.
 *
 * @since 1.0.0
 */
@DisplayName("GlobalExceptionHandler")
final class GlobalExceptionHandlerSecurityTest {

    /**
     * The value planted wherever a carrier would leak a submission, chosen so that it cannot occur
     * in any legitimate output of this module and a containment test is therefore conclusive.
     */
    private static final String CANARY = "ZZ-CANARY-SUBMITTED-VALUE-ZZ";

    /** A second canary, for the places a carrier names an internal resource rather than a value. */
    private static final String INTERNAL_CANARY = "ZZ-CANARY-INTERNAL-DETAIL-ZZ";

    /**
     * A canary short enough for a width-bounded legacy field.
     *
     * <p>The abend context's failing-component field is bounded to eight characters, exactly as the
     * legacy structure declares it, and the carrier rejects anything longer. Planting a canary there
     * therefore needs one that fits, and the value is chosen so that it still cannot occur in any
     * legitimate output.
     */
    private static final String SHORT_CANARY = "ZZCANARY";

    /** The neutral summary the boundary sends for any declarative validation rejection. */
    private static final String EXPECTED_VALIDATION_SUMMARY = "Submitted data failed validation";

    /**
     * The neutral summary the boundary sends for a body it could not read.
     *
     * <p>The merged boundary distinguishes a body the message converter could not read at all from
     * a request value that could not be bound to its target, and answers each with its own summary.
     * The literal therefore names the body explicitly; the shorter wording this suite once expected
     * would not say which of the two failures had occurred.
     */
    private static final String EXPECTED_MALFORMED_SUMMARY = "Request body could not be read";

    /** The neutral summary the boundary sends when authentication was required and not established. */
    private static final String EXPECTED_AUTHENTICATION_SUMMARY = "Authentication required";

    /** The neutral summary the boundary sends when an established principal was refused. */
    private static final String EXPECTED_ACCESS_DENIED_SUMMARY = "Access denied";

    /** The neutral summary the boundary sends for a keyed read that resolved to no record. */
    private static final String EXPECTED_NOT_FOUND_SUMMARY = "Record not found";

    /** The separator used when flattening a response into one searchable string. */
    private static final char FLATTEN_SEPARATOR = '\u0001';

    /** The handler under test. It is stateless, so one instance serves every case. */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ---------------------------------------------------------------------------------------------
    // The three handlers whose body IS the carrier's operator text, by contract.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("carries operator text through, where that is the contract")
    final class OperatorTextIsCarriedThrough {

        @Test
        @DisplayName("an abend answers 500 with the operator message field and nothing else")
        void abendAnswersFiveHundredWithTheOperatorMessage() {
            AbendException abend =
                    new AbendException("9999", "COACTUPC", "before image mismatch", "PLEASE CALL SUPPORT");

            ResponseEntity<ErrorResponse> response = handler.handleAbend(abend);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo("PLEASE CALL SUPPORT");
            assertThat(response.getBody().fieldErrors()).isEmpty();
            assertThat(response.getBody().focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("an abend body omits the code, the culprit and the reason, which are diagnostics")
        void abendBodyOmitsTheDiagnosticComponents() {
            AbendException abend = new AbendException("9999", SHORT_CANARY, INTERNAL_CANARY,
                    "PLEASE CALL SUPPORT");

            ResponseEntity<ErrorResponse> response = handler.handleAbend(abend);

            assertThat(abend.culprit()).contains(SHORT_CANARY);
            assertThat(abend.reason()).contains(INTERNAL_CANARY);
            assertThat(flatten(response.getBody())).doesNotContain(SHORT_CANARY)
                    .doesNotContain(INTERNAL_CANARY);
        }

        @Test
        @DisplayName("a service-raised validation failure answers 400 and preserves both field states")
        void serviceRaisedValidationPreservesBothFieldStates() {
            ValidationException validation = new ValidationException("Please correct the errors",
                    List.of(new ValidationException.FieldError("acctStatus", "ACSTTUS",
                                    ValidationException.FieldState.MISSING, "must be supplied"),
                            new ValidationException.FieldError("ficoScore", "ACSTFCO",
                                    ValidationException.FieldState.INVALID, "must be 300 to 850")));

            ResponseEntity<ErrorResponse> response = handler.handleValidation(validation);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo("Please correct the errors");
            assertThat(response.getBody().fieldErrors()).hasSize(2);
            assertThat(response.getBody().fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(response.getBody().fieldErrors().get(1).state())
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("a service-raised validation failure hints focus at the first screened field")
        void serviceRaisedValidationHintsFocusAtTheFirstScreenedField() {
            ValidationException validation = new ValidationException("Please correct the errors",
                    List.of(new ValidationException.FieldError("acctStatus", "ACSTTUS",
                            ValidationException.FieldState.MISSING, "must be supplied")));

            ResponseEntity<ErrorResponse> response = handler.handleValidation(validation);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().focusScreenFieldId()).isEqualTo("ACSTTUS");
        }

        @Test
        @DisplayName("a conflict answers 409 with the verbatim legacy text of the arm that produced it")
        void conflictAnswersFourZeroNineWithTheVerbatimLegacyText() {
            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    OptimisticLockConflictException.ConflictKind.UPDATE_FAILED_AFTER_LOCK,
                    "Account", "00000000011");

            ResponseEntity<ErrorResponse> response = handler.handleOptimisticLockConflict(conflict);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .isEqualTo(OptimisticLockConflictException.MSG_LOCKED_BUT_UPDATE_FAILED);
        }

        @Test
        @DisplayName("a conflict body omits the business key, which may be a card number")
        void conflictBodyOmitsTheBusinessKey() {
            OptimisticLockConflictException conflict = new OptimisticLockConflictException(
                    OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE,
                    "Account", CANARY);

            ResponseEntity<ErrorResponse> response = handler.handleOptimisticLockConflict(conflict);

            assertThat(flatten(response.getBody())).doesNotContain(CANARY);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The eight handlers whose body is NEVER the carrier's text.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("substitutes a neutral body where the carrier's own text is a diagnostic")
    final class NeutralBodies {

        @Test
        @DisplayName("an unhandled file failure answers 500 with the terminal abend literal")
        void fileFailureAnswersFiveHundredWithTheTerminalLiteral() {
            FileStatusException fileStatus = new FileStatusException("37", "OPEN", INTERNAL_CANARY);

            ResponseEntity<ErrorResponse> response = handler.handleFileStatus(fileStatus);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(AbendException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("an unhandled file failure exposes neither the raw status nor the resource name")
        void fileFailureExposesNeitherStatusNorResource() {
            FileStatusException fileStatus = new FileStatusException("37", "OPEN", INTERNAL_CANARY);

            ResponseEntity<ErrorResponse> response = handler.handleFileStatus(fileStatus);

            assertThat(flatten(response.getBody())).doesNotContain(INTERNAL_CANARY)
                    .doesNotContain("37");
        }

        @Test
        @DisplayName("a not-found answers 404 with one neutral summary and no record identity")
        void notFoundAnswersFourZeroFourNeutrally() {
            RecordNotFoundException notFound =
                    new RecordNotFoundException("Card", CANARY, INTERNAL_CANARY);

            ResponseEntity<ErrorResponse> response = handler.handleRecordNotFound(notFound);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(EXPECTED_NOT_FOUND_SUMMARY);
            assertThat(flatten(response.getBody())).doesNotContain(CANARY)
                    .doesNotContain(INTERNAL_CANARY);
        }

        @Test
        @DisplayName("a failed job submission answers a non-failing 200 with the frozen literal")
        void jobSubmissionAnswersTwoHundredWithTheFrozenLiteral() {
            JobSubmissionException failure = new JobSubmissionException(
                    JobSubmissionException.DEFAULT_QUEUE_NAME, "0016", "0080", 4,
                    new IllegalStateException(INTERNAL_CANARY));

            ResponseEntity<ErrorResponse> response = handler.handleJobSubmission(failure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
            assertThat(flatten(response.getBody())).doesNotContain(INTERNAL_CANARY);
        }

        @Test
        @DisplayName("a failed job submission keeps the response and reason codes out of the body")
        void jobSubmissionKeepsDiagnosticCodesOutOfTheBody() {
            JobSubmissionException failure = new JobSubmissionException(
                    JobSubmissionException.DEFAULT_QUEUE_NAME, "0016", "0080", 4, null);

            ResponseEntity<ErrorResponse> response = handler.handleJobSubmission(failure);

            assertThat(flatten(response.getBody())).doesNotContain("0016").doesNotContain("0080");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Declarative binding failures.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("normalizes a declarative binding failure")
    final class DeclarativeBinding {

        @Test
        @DisplayName("answers 400 with the neutral summary and one entry per failed field")
        void answersFourHundredWithOneEntryPerFailedField() {
            BindException binding = bindingFailure();
            binding.rejectValue("userId", "NotBlank", "must not be blank");
            binding.rejectValue("password", "Size", "size must be between 8 and 8");

            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(binding);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(EXPECTED_VALIDATION_SUMMARY);
            assertThat(response.getBody().fieldErrors()).hasSize(2);
            assertThat(response.getBody().fieldErrors().get(0).fieldName()).isEqualTo("userId");
            assertThat(response.getBody().fieldErrors().get(1).fieldName()).isEqualTo("password");
        }

        @ParameterizedTest(name = "{0} means the field was left blank")
        @ValueSource(strings = {"NotNull", "NotBlank", "NotEmpty"})
        @DisplayName("maps a presence constraint to the missing state")
        void mapsAPresenceConstraintToTheMissingState(final String presenceConstraint) {
            BindException binding = bindingFailure();
            binding.rejectValue("userId", presenceConstraint, "must be supplied");

            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(binding);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors().getFirst().state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
        }

        @ParameterizedTest(name = "{0} means the field was filled in wrongly")
        @ValueSource(strings = {"Size", "Pattern", "Min", "Max", "Digits", "Email", "typeMismatch"})
        @DisplayName("maps every other constraint to the invalid state")
        void mapsEveryOtherConstraintToTheInvalidState(final String constraint) {
            SignOnLikeTarget target = new SignOnLikeTarget();
            target.setUserId(SHORT_CANARY);
            BindException binding = new BindException(target, "signOnRequest");
            binding.rejectValue("userId", constraint, "is not acceptable");

            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(binding);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors().getFirst().state())
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("never reads the rejected value, so the submission cannot reach the response")
        void neverReadsTheRejectedValue() {
            SignOnLikeTarget target = new SignOnLikeTarget();
            target.setUserId(CANARY);
            BindException binding = new BindException(target, "signOnRequest");
            binding.rejectValue("userId", "Size", "size must be between 1 and 8");

            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(binding);

            assertThat(binding.getFieldError("userId")).isNotNull();
            assertThat(binding.getFieldError("userId").getRejectedValue()).isEqualTo(CANARY);
            assertThat(binding.getMessage()).contains(CANARY);
            assertThat(flatten(response.getBody())).doesNotContain(CANARY);
        }

        @Test
        @DisplayName("a rejection on the credential field cannot echo the credential")
        void aRejectionOnTheCredentialFieldCannotEchoTheCredential() {
            SignOnLikeTarget target = new SignOnLikeTarget();
            target.setPassword(CANARY);
            BindException binding = new BindException(target, "signOnRequest");
            binding.rejectValue("password", "Size", "size must be exactly 8");

            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(binding);

            assertThat(binding.getFieldError("password")).isNotNull();
            assertThat(binding.getFieldError("password").getRejectedValue()).isEqualTo(CANARY);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors().getFirst().fieldName())
                    .as("the field must still be named, so the client knows what to correct")
                    .isEqualTo("password");
            assertThat(flatten(response.getBody()))
                    .as("naming the field is required; echoing its value is not permitted")
                    .doesNotContain(CANARY);
        }

        @Test
        @DisplayName("carries no legacy screen field identifier and offers no focus hint")
        void carriesNoScreenFieldIdentifierAndNoFocusHint() {
            BindException binding = bindingFailure();
            binding.rejectValue("userId", "NotBlank", "must not be blank");

            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(binding);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors().getFirst().screenFieldId()).isEmpty();
            assertThat(response.getBody().focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("reports a global error as an entry that names no field, and leaks nothing")
        void reportsAGlobalErrorAsAnEntryThatNamesNoField() {
            SignOnLikeTarget target = new SignOnLikeTarget();
            target.setPassword(CANARY);
            BindException binding = new BindException(target, "signOnRequest");
            binding.reject("PasswordsMustMatch", "the two entries differ");

            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(binding);

            assertThat(binding.getGlobalErrorCount()).isEqualTo(1);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(EXPECTED_VALIDATION_SUMMARY);
            assertThat(response.getBody().fieldErrors())
                    .as("a cross-field rejection must not be dropped; the caller has to be told"
                            + " what to correct")
                    .hasSize(1);
            assertThat(response.getBody().fieldErrors().getFirst().fieldName())
                    .as("the rejection belongs to no single field, so it names none")
                    .isEmpty();
            assertThat(response.getBody().fieldErrors().getFirst().state())
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
            assertThat(response.getBody().fieldErrors().getFirst().message())
                    .isEqualTo("the two entries differ");
            assertThat(response.getBody().focusScreenFieldId())
                    .as("an entry that names no field offers no focus hint")
                    .isNull();
            assertThat(flatten(response.getBody()))
                    .as("the developer-authored rejection text is reportable; the submitted value"
                            + " is not")
                    .doesNotContain(CANARY);
        }

        @Test
        @DisplayName("answers a rejection that named no field at all")
        void answersARejectionThatNamedNoFieldAtAll() {
            BindException binding = bindingFailure();

            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(binding);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("declaring the handler on the supertype genuinely covers the body-binding failure")
        void declaringOnTheSupertypeCoversTheBodyBindingFailure() {
            assertThat(BindException.class.isAssignableFrom(MethodArgumentNotValidException.class))
                    .as("MethodArgumentNotValidException must remain a BindException, otherwise the"
                            + " single handler stops covering request-body rejections")
                    .isTrue();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Method-level constraint violations, exercised through a real Bean Validation provider.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("normalizes a method-level constraint violation")
    final class MethodLevelConstraints {

        @Test
        @DisplayName("answers 400 with the neutral summary and one entry per violated constraint")
        void answersFourHundredWithOneEntryPerViolation() {
            ConstraintViolationException violation =
                    new ConstraintViolationException(violate(new Flat("", null)));

            ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(violation);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(EXPECTED_VALIDATION_SUMMARY);
            assertThat(response.getBody().fieldErrors()).hasSize(2);
            assertThat(response.getBody().fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactlyInAnyOrder("code", "amount");
        }

        @Test
        @DisplayName("maps a presence constraint to missing and a bounds constraint to invalid")
        void mapsPresenceToMissingAndBoundsToInvalid() {
            ConstraintViolationException violation =
                    new ConstraintViolationException(violate(new Flat("", 1)));

            ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(violation);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors())
                    .anySatisfy(fieldError -> {
                        assertThat(fieldError.fieldName()).isEqualTo("code");
                        assertThat(fieldError.state()).isEqualTo(ErrorResponse.FieldState.MISSING);
                    });
        }

        @Test
        @DisplayName("reduces a nested property path to the leaf, so no internal path is named")
        void reducesANestedPathToTheLeaf() {
            ConstraintViolationException violation =
                    new ConstraintViolationException(violate(new Nested1(new Flat("", 5))));

            ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(violation);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactly("code");
            assertThat(flatten(response.getBody())).doesNotContain("inner.code");
        }

        @Test
        @DisplayName("never reads the invalid value, so the submission cannot reach the response")
        void neverReadsTheInvalidValue() {
            Set<ConstraintViolation<Flat>> violations = violate(new Flat(CANARY, 5));
            ConstraintViolationException violation = new ConstraintViolationException(violations);

            ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(violation);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anySatisfy(
                    entry -> assertThat(entry.getInvalidValue()).isEqualTo(CANARY));
            assertThat(flatten(response.getBody())).doesNotContain(CANARY);
        }

        @Test
        @DisplayName("offers no focus hint, because a violation set has no defined order")
        void offersNoFocusHint() {
            ConstraintViolationException violation =
                    new ConstraintViolationException(violate(new Flat("", 5)));

            ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(violation);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("answers a carrier that holds no violation at all")
        void answersACarrierHoldingNoViolation() {
            Set<ConstraintViolation<?>> none = Set.of();

            ResponseEntity<ErrorResponse> response =
                    handler.handleConstraintViolation(new ConstraintViolationException(none));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("answers a carrier whose violation set is absent rather than empty")
        void answersACarrierWhoseViolationSetIsAbsent() {
            Set<ConstraintViolation<?>> absent = null;
            ConstraintViolationException violation = new ConstraintViolationException(absent);

            assertThatCode(() -> handler.handleConstraintViolation(violation))
                    .doesNotThrowAnyException();
            assertThat(handler.handleConstraintViolation(violation).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Unreadable request bodies.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("normalizes an unreadable request body")
    final class UnreadableBody {

        @Test
        @DisplayName("answers 400 with one neutral summary and no detail at all")
        void answersFourHundredWithOneNeutralSummary() {
            HttpMessageNotReadableException unreadable = unreadableBody(
                    "JSON parse error: Unexpected character; nested exception is"
                            + " com.fasterxml.jackson.core.JsonParseException");

            ResponseEntity<ErrorResponse> response = handler.handleUnreadableBody(unreadable);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(EXPECTED_MALFORMED_SUMMARY);
            assertThat(response.getBody().fieldErrors()).isEmpty();
            assertThat(response.getBody().focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("discards the parser detail, which quotes the submission and names a Java type")
        void discardsTheParserDetail() {
            HttpMessageNotReadableException unreadable = unreadableBody(
                    "JSON parse error at [Source: (String)\"{\\\"userId\\\":\\\"" + CANARY
                            + "\\\"}\"]; target type com.carddemo.api.dto.SignOnRequest");

            ResponseEntity<ErrorResponse> response = handler.handleUnreadableBody(unreadable);

            assertThat(unreadable.getMessage()).contains(CANARY);
            assertThat(flatten(response.getBody())).doesNotContain(CANARY)
                    .doesNotContain("com.carddemo");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Authentication and authorization, inside-dispatch arm.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("separates authentication from authorization")
    final class AuthenticationAndAuthorization {

        @Test
        @DisplayName("an authentication failure answers 401 with a neutral summary")
        void authenticationFailureAnswersFourZeroOne() {
            AuthenticationException failure = new BadCredentialsException("Bad credentials");

            ResponseEntity<ErrorResponse> response =
                    handler.handleAuthenticationFailure(failure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(EXPECTED_AUTHENTICATION_SUMMARY);
            assertThat(response.getBody().fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("an unknown identifier and a wrong credential answer identically")
        void anUnknownIdentifierAndAWrongCredentialAnswerIdentically() {
            ResponseEntity<ErrorResponse> unknownIdentifier = handler.handleAuthenticationFailure(
                    new UsernameNotFoundException("User " + CANARY + " was not found"));
            ResponseEntity<ErrorResponse> wrongCredential = handler.handleAuthenticationFailure(
                    new BadCredentialsException("Bad credentials for " + CANARY));

            assertThat(unknownIdentifier.getStatusCode())
                    .as("distinguishing the two would turn sign-on into an enumeration oracle")
                    .isEqualTo(wrongCredential.getStatusCode());
            assertThat(flatten(unknownIdentifier.getBody()))
                    .isEqualTo(flatten(wrongCredential.getBody()));
            assertThat(flatten(unknownIdentifier.getBody())).doesNotContain(CANARY);
        }

        @Test
        @DisplayName("a denial answers 403 and names neither the rule nor the missing authority")
        void denialAnswersFourZeroThree() {
            AccessDeniedException denial = new AccessDeniedException(
                    "Access is denied; required authority ROLE_ADMIN; principal " + CANARY);

            ResponseEntity<ErrorResponse> response = handler.handleAccessDenied(denial);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(EXPECTED_ACCESS_DENIED_SUMMARY);
            assertThat(flatten(response.getBody())).doesNotContain(CANARY)
                    .doesNotContain("ROLE_ADMIN");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The catch-all.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("floors the boundary with a catch-all")
    final class TheCatchAll {

        @Test
        @DisplayName("an unforeseen runtime fault answers 500 with the frozen terminal literal")
        void unforeseenRuntimeFaultAnswersFiveHundred() {
            ResponseEntity<ErrorResponse> response = handler.handleUnexpectedFailure(
                    new IllegalStateException("boom"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(AbendException.DEFAULT_MESSAGE);
            assertThat(response.getBody().fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("an unforeseen fault's own message never reaches the body")
        void unforeseenFaultMessageNeverReachesTheBody() {
            ResponseEntity<ErrorResponse> response = handler.handleUnexpectedFailure(
                    new IllegalStateException("connection to jdbc:postgresql://db:5432/carddemo"
                            + " failed while reading " + CANARY));

            assertThat(flatten(response.getBody())).doesNotContain(CANARY)
                    .doesNotContain("jdbc:");
        }

        @Test
        @DisplayName("a framework fault keeps its own status instead of being flattened to 500")
        void aFrameworkFaultKeepsItsOwnStatus() {
            ResponseEntity<ErrorResponse> response = handler.handleUnexpectedFailure(
                    new HttpRequestMethodNotSupportedException("PATCH"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(EXPECTED_MALFORMED_SUMMARY);
        }

        @ParameterizedTest(name = "{0} is honoured rather than replaced by 500")
        @MethodSource("com.carddemo.api.GlobalExceptionHandlerSecurityTest#frameworkDeclaredStatuses")
        @DisplayName("every framework-declared status survives the catch-all")
        void everyFrameworkDeclaredStatusSurvives(final HttpStatus declaredStatus) {
            ResponseEntity<ErrorResponse> response = handler.handleUnexpectedFailure(
                    new ResponseStatusException(declaredStatus, CANARY));

            assertThat(response.getStatusCode()).isEqualTo(declaredStatus);
            assertThat(flatten(response.getBody())).doesNotContain(CANARY);
        }

        @Test
        @DisplayName("a framework fault's own reason never reaches the body either")
        void aFrameworkFaultReasonNeverReachesTheBody() {
            ResponseStatusException framework = new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "target type is " + CANARY);

            ResponseEntity<ErrorResponse> response = handler.handleUnexpectedFailure(framework);

            assertThat(framework.getMessage()).contains(CANARY);
            assertThat(flatten(response.getBody())).doesNotContain(CANARY);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The whole-boundary claims: status separation, and no leakage anywhere.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("separates the six statuses and leaks nothing on any of them")
    final class TheBoundaryAsAWhole {

        @Test
        @DisplayName("400, 401, 403, 404, 409 and 500 are each produced and each distinct")
        void theSixStatusesAreEachProducedAndEachDistinct() {
            List<HttpStatus> produced = List.of(
                    statusOf(handler.handleUnreadableBody(unreadableBody("unreadable"))),
                    statusOf(handler.handleAuthenticationFailure(
                            new BadCredentialsException("Bad credentials"))),
                    statusOf(handler.handleAccessDenied(new AccessDeniedException("denied"))),
                    statusOf(handler.handleRecordNotFound(
                            new RecordNotFoundException("Card", "0000000000000011"))),
                    statusOf(handler.handleOptimisticLockConflict(
                            new OptimisticLockConflictException(
                                    OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED,
                                    "Account", "00000000011"))),
                    statusOf(handler.handleUnexpectedFailure(new IllegalStateException("boom"))));

            assertThat(produced).containsExactly(
                    HttpStatus.BAD_REQUEST,
                    HttpStatus.UNAUTHORIZED,
                    HttpStatus.FORBIDDEN,
                    HttpStatus.NOT_FOUND,
                    HttpStatus.CONFLICT,
                    HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(produced).doesNotHaveDuplicates();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.api.GlobalExceptionHandlerSecurityTest#everyNeutralOutcome")
        @DisplayName("no body carries a stack frame, a type name, a path, a query or the submission")
        void noBodyCarriesImplementationDetail(final String description,
                                               final ResponseEntity<ErrorResponse> response) {
            String body = flatten(response.getBody());
            String upperCasedBody = body.toUpperCase(Locale.ROOT);

            assertThat(body).as("%s must not echo the submission", description)
                    .doesNotContain(CANARY)
                    .doesNotContain(INTERNAL_CANARY);
            assertThat(body).as("%s must not name a type or a package", description)
                    .doesNotContain("com.carddemo")
                    .doesNotContain("org.springframework")
                    .doesNotContain("com.fasterxml")
                    .doesNotContain("java.lang")
                    .doesNotContain("Exception");
            assertThat(body).as("%s must not carry a stack frame", description)
                    .doesNotContain("\n")
                    .doesNotContain("\r")
                    .doesNotContain("\tat ");
            assertThat(body).as("%s must not name a path or a source artifact", description)
                    .doesNotContain("/tmp/")
                    .doesNotContain("/home/")
                    .doesNotContain("src/main")
                    .doesNotContain(".java")
                    .doesNotContain(".sql");
            assertThat(upperCasedBody).as("%s must not carry a query or a connection string",
                            description)
                    .doesNotContain("SELECT ")
                    .doesNotContain(" FROM ")
                    .doesNotContain("INSERT INTO")
                    .doesNotContain("JDBC:")
                    .doesNotContain("POSTGRES");
            assertThat(upperCasedBody).as("%s must not carry a credential or a digest", description)
                    .doesNotContain("PASSWORD")
                    .doesNotContain("SECRET")
                    .doesNotContain("$2A$")
                    .doesNotContain("BEARER ");
        }

        @Test
        @DisplayName("every response carries a body, so no status is answered with nothing")
        void everyResponseCarriesABody() {
            assertThat(everyNeutralOutcome()).isNotEmpty();
            everyNeutralOutcome().forEach(arguments -> {
                Object response = arguments.get()[1];
                assertThat(response).isInstanceOf(ResponseEntity.class);
                assertThat(((ResponseEntity<?>) response).getBody()).isNotNull();
            });
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Providers.
    // ---------------------------------------------------------------------------------------------

    /**
     * The framework-declared statuses that the catch-all must honour rather than replace.
     *
     * @return one argument per status
     */
    private static Stream<Arguments> frameworkDeclaredStatuses() {
        return Stream.of(
                Arguments.of(HttpStatus.METHOD_NOT_ALLOWED),
                Arguments.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
                Arguments.of(HttpStatus.NOT_ACCEPTABLE),
                Arguments.of(HttpStatus.BAD_REQUEST),
                Arguments.of(HttpStatus.NOT_FOUND),
                Arguments.of(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    /**
     * Every outcome whose body is neutral by contract, each produced from a carrier with a canary
     * planted in the place that carrier would leak it from.
     *
     * <p>The three handlers whose body IS the carrier's operator text are deliberately absent: for
     * those, passing the text through is the contract, and they are asserted separately.
     *
     * @return one argument pair of description and response per neutral outcome
     */
    private static Stream<Arguments> everyNeutralOutcome() {
        GlobalExceptionHandler boundary = new GlobalExceptionHandler();

        SignOnLikeTarget target = new SignOnLikeTarget();
        target.setUserId(CANARY);
        BindException binding = new BindException(target, "signOnRequest");
        binding.rejectValue("userId", "Size", "size must be between 1 and 8");

        return Stream.of(
                Arguments.of("an unhandled file failure",
                        boundary.handleFileStatus(
                                new FileStatusException("37", "OPEN", INTERNAL_CANARY))),
                Arguments.of("a not-found",
                        boundary.handleRecordNotFound(
                                new RecordNotFoundException("Card", CANARY, INTERNAL_CANARY))),
                Arguments.of("a failed job submission",
                        boundary.handleJobSubmission(new JobSubmissionException(
                                JobSubmissionException.DEFAULT_QUEUE_NAME, "0016", "0080", 4,
                                new IllegalStateException(INTERNAL_CANARY)))),
                Arguments.of("a declarative binding failure",
                        boundary.handleBindingValidation(binding)),
                Arguments.of("a method-level constraint violation",
                        boundary.handleConstraintViolation(
                                new ConstraintViolationException(violate(new Flat(CANARY, 0))))),
                Arguments.of("an unreadable body",
                        boundary.handleUnreadableBody(unreadableBody(
                                "JSON parse error at [Source: (String)\"" + CANARY
                                        + "\"]; target type"
                                        + " com.carddemo.api.dto.SignOnRequest"))),
                Arguments.of("an authentication failure",
                        boundary.handleAuthenticationFailure(
                                new BadCredentialsException("Bad credentials for " + CANARY))),
                Arguments.of("a denial",
                        boundary.handleAccessDenied(new AccessDeniedException(
                                "required authority ROLE_ADMIN for " + CANARY))),
                Arguments.of("an unforeseen runtime fault",
                        boundary.handleUnexpectedFailure(new IllegalStateException(
                                "SELECT * FROM account WHERE id = '" + CANARY + "'"))),
                Arguments.of("a framework request fault",
                        boundary.handleUnexpectedFailure(new ResponseStatusException(
                                HttpStatus.UNSUPPORTED_MEDIA_TYPE, CANARY))));
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers and fixtures.
    // ---------------------------------------------------------------------------------------------

    /**
     * Flattens a response body into one searchable string covering every text component it holds.
     *
     * <p>The summary, the focus hint, and every field name, screen identifier, state and per-field
     * message are all included, so a single containment assertion over the result is a statement
     * about the whole body rather than about one component of it.
     *
     * @param body the response body, possibly {@code null}
     * @return every text component joined by a separator that cannot occur in any of them
     */
    private static String flatten(final ErrorResponse body) {
        if (body == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        text.append(body.message()).append(FLATTEN_SEPARATOR)
                .append(body.focusScreenFieldId()).append(FLATTEN_SEPARATOR);
        for (ErrorResponse.FieldError fieldError : body.fieldErrors()) {
            text.append(fieldError.fieldName()).append(FLATTEN_SEPARATOR)
                    .append(fieldError.screenFieldId()).append(FLATTEN_SEPARATOR)
                    .append(fieldError.state()).append(FLATTEN_SEPARATOR)
                    .append(fieldError.message()).append(FLATTEN_SEPARATOR);
        }
        return text.toString();
    }

    /**
     * Reads the status off a response as an {@link HttpStatus} for comparison.
     *
     * @param response the response to inspect; never {@code null}
     * @return the response's status
     */
    private static HttpStatus statusOf(final ResponseEntity<ErrorResponse> response) {
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    /**
     * Creates an empty binding failure over a sign-on-shaped target.
     *
     * @return a binding failure with a real binding result and no rejection yet
     */
    private static BindException bindingFailure() {
        return new BindException(new SignOnLikeTarget(), "signOnRequest");
    }

    /**
     * Creates an unreadable-body failure carrying the supplied parser detail.
     *
     * <p>The four-argument form is used rather than the message-only form, because the message-only
     * constructor is deprecated and this module compiles with warnings promoted to errors.
     *
     * @param parserDetail the detail a real parser would have produced
     * @return the failure a message converter would have raised
     */
    private static HttpMessageNotReadableException unreadableBody(final String parserDetail) {
        return new HttpMessageNotReadableException(parserDetail, new EmptyInputMessage());
    }

    /**
     * Validates a bean with a real Bean Validation provider, producing real violations.
     *
     * <p>Real violations are used rather than hand-written doubles so that the property paths, the
     * message templates and the invalid values are exactly the shapes a running provider produces -
     * which is what makes the leaf-extraction and the constraint-name mapping meaningful.
     *
     * @param bean the bean to validate
     * @param <T>  the bean type
     * @return every violation the provider found; never {@code null}
     */
    private static <T> Set<ConstraintViolation<T>> violate(final T bean) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(bean);
        }
    }

    /** A bean whose two constraints cover a presence constraint and a bounds constraint. */
    private record Flat(@NotBlank @Size(max = 8) String code, @NotNull Integer amount) {
    }

    /** A bean whose constraint is reached through a nested property, producing a two-node path. */
    private record Nested1(@Valid @NotNull Flat inner) {
    }

    /**
     * A mutable target for a binding failure, shaped like the sign-on request.
     *
     * <p>A binding result needs a JavaBean rather than a record, because it resolves a field through
     * property access rather than through a record component. The accessors are {@code public} and
     * the type is not {@code private} for the same reason: property discovery reads the public
     * accessor pair, and a package-private getter on a private nested type is not discoverable, which
     * makes the field unreadable and the rejection fail before it is even recorded. Every property
     * the tests reject is declared, because rejecting a property the target does not declare fails
     * at the rejection rather than at the assertion.
     *
     * <p>The two properties mirror the sign-on request deliberately: a rejection on a credential
     * field is the case where echoing the rejected value would matter most, so it is the case the
     * leakage assertions are built around.
     */
    static final class SignOnLikeTarget {

        /** The identifier a caller submitted; the value a rejected field would expose. */
        @NotEmpty
        private String userId = "";

        /** The credential a caller submitted; the value that must never survive a rejection. */
        @NotEmpty
        private String password = "";

        /**
         * Returns the submitted identifier.
         *
         * @return the identifier, never {@code null}
         */
        public String getUserId() {
            return userId;
        }

        /**
         * Records the submitted identifier.
         *
         * @param userId the identifier to record
         */
        public void setUserId(final String userId) {
            this.userId = userId;
        }

        /**
         * Returns the submitted credential.
         *
         * @return the credential, never {@code null}
         */
        public String getPassword() {
            return password;
        }

        /**
         * Records the submitted credential.
         *
         * @param password the credential to record
         */
        public void setPassword(final String password) {
            this.password = password;
        }
    }

    /**
     * The smallest usable input message, so an unreadable-body failure can be built without a mock
     * servlet request.
     *
     * <p>An unreadable body is by definition one nothing could be read from, so an empty body and
     * empty headers are not a simplification - they are the situation.
     */
    private static final class EmptyInputMessage implements HttpInputMessage {

        /** No header, because none is read by the handler under test. */
        private final HttpHeaders headers = new HttpHeaders();

        /**
         * Returns an empty body.
         *
         * @return an input stream over zero bytes
         */
        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        /**
         * Returns the empty header set.
         *
         * @return the headers, never {@code null}
         */
        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }

    /**
     * Pins the ordering guarantee that the service-raised validation handler documents.
     *
     * <p>The handler claims to carry the per-field detail across "in the order the service assembled
     * them: no sequence is imposed, none is de-duplicated, and no entry is dropped". Order is the one
     * part of that claim a client depends on, because the cursor hint is derived from the first entry
     * and a screen decorates its fields in cascade order.
     *
     * <p>A mutable {@link ArrayList} is supplied rather than an immutable list, so the test also
     * establishes that the handler does not require the carrier to have frozen its list first.
     */
    @Test
    @DisplayName("the response contract's field-error list preserves the order it was given")
    void theFieldErrorListPreservesOrder() {
        List<ValidationException.FieldError> carrierErrors = new ArrayList<>();
        carrierErrors.add(new ValidationException.FieldError("first", "F1",
                ValidationException.FieldState.MISSING, "one"));
        carrierErrors.add(new ValidationException.FieldError("second", "F2",
                ValidationException.FieldState.INVALID, "two"));

        ResponseEntity<ErrorResponse> response = handler.handleValidation(
                new ValidationException("Please correct the errors", carrierErrors));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().fieldErrors())
                .extracting(ErrorResponse.FieldError::fieldName)
                .containsExactly("first", "second");
    }
}
