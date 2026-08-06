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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.api.dto.BatchJobLaunchRequest;
import com.carddemo.api.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.carddemo.config.FixedLocaleMessageInterpolator;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.exception.JobSubmissionException;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.RollbackException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.StaleObjectStateException;
import org.hibernate.StaleStateException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.MapBindingResult;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link GlobalExceptionHandler}, the module's single REST failure adapter.
 *
 * <p>Two properties are under test and they are not the same property. The first is that each of
 * the six failure carriers in {@code com.carddemo.exception} reaches the caller as the status and
 * the sanitized {@link ErrorResponse} body the legacy behaviour requires. The second is that a
 * request rejected by the web framework or by the validation provider - before any service runs -
 * reaches the caller in <em>that same body shape</em> rather than in the framework's own
 * representation. The second property is what keeps the API to one error contract for one class of
 * outcome, and it is asserted here per named framework type.</p>
 *
 * <p>The advice is exercised by direct invocation rather than through a servlet stack. Every
 * handler is a pure function of the failure it is handed, so a container adds nothing to the
 * assertion and would add a moving part to it. Each expected value below is hand written: no
 * expectation is read back from the class under test, and the module's own frozen literals are
 * referenced through the carriers that publish them so that a change to one is a test failure
 * rather than a silently agreeing pair.</p>
 *
 * <p>Provenance: behaviour cited, never transcribed, from the CardDemo COBOL estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("GlobalExceptionHandler: the module's single REST failure adapter")
class GlobalExceptionHandlerTest {

    /**
     * The neutral summary the advice emits for a caller-caused validation rejection. Hand written
     * here; the advice holds it privately, so the two must be kept in step deliberately.
     */
    private static final String ORACLE_VALIDATION_FAILED = "Submitted data failed validation";

    /** The neutral summary the advice emits when a request body could not be read at all. */
    private static final String ORACLE_MALFORMED_BODY = "Request body could not be read";

    /** The neutral summary the advice emits when a request value could not be bound. */
    private static final String ORACLE_BINDING_FAILED = "Request value could not be bound";

    /** The neutral summary the advice emits for a keyed read that resolved to no record. */
    private static final String ORACLE_RECORD_NOT_FOUND = "Record not found";

    /** The empty string, named so that an intentionally absent value reads as intentional. */
    private static final String EMPTY_TEXT = "";

    /** A card primary account number, the value class the estate provides no masking for. */
    private static final String SENSITIVE_CARD_NUMBER = "4111111111111111";

    /** A password-shaped value, used to prove no rejected value is ever echoed or logged. */
    private static final String SENSITIVE_PASSWORD = "PASSWORD";

    /** The request-contract property name the account-update screen exposes for the status. */
    private static final String PROPERTY_ACCOUNT_STATUS = "acctStatus";

    /** The request-contract property name for a card number. */
    private static final String PROPERTY_CARD_NUMBER = "cardNumber";

    /** The legacy screen field tag for the account status, one of the 39 decorated fields. */
    private static final String BMS_ACCOUNT_STATUS = "ACSTTUS";

    /** The advice under test. It is stateless, so one instance serves every assertion. */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** The validation provider used to obtain genuine constraint violations. */
    private static ValidatorFactory validatorFactory;

    /** The validator obtained from {@link #validatorFactory}. */
    private static Validator validator;

    /**
     * Starts the validation provider with the message locale pinned.
     *
     * <p>Assertions below compare the interpolated constraint message character for character, and the
     * provider interpolates in the JVM default locale, so under a localized default the same violation
     * reports localized prose - Turkish renders the blank message as {@code boş değer olamaz}. The
     * continuous-integration definition deliberately re-runs this tier with the default locale
     * overridden, so the interpolation locale is fixed here rather than left to the machine. It is
     * fixed by installing the application's own {@code FixedLocaleMessageInterpolator}, which pins
     * {@link java.util.Locale#ROOT} and so resolves the provider's base bundle - the English text
     * asserted below - rather than any translation of it.
     */
    @BeforeAll
    static void startValidationProvider() {
        // Built with the SAME pinned interpolator the application installs on its own validator,
        // rather than with the provider's default. These assertions compare rendered message text, and
        // the provider renders against a locale: the default configuration would resolve a translated
        // bundle whenever the host's default locale had one, so this test would pass on one machine
        // and fail on another while the code under test was identical. Using the application's own
        // statement of the rule - com.carddemo.config.FixedLocaleMessageInterpolator - means the text
        // asserted here is the text a client receives, and neither side can be pinned without the
        // other.
        validatorFactory = Validation.byDefaultProvider().configure()
                .messageInterpolator(new FixedLocaleMessageInterpolator())
                .buildValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void stopValidationProvider() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    // Test-local helpers. None of these consults the class under test.

    /**
     * A request-contract stand-in carrying one mandatory text field and one bounded field, used to
     * obtain genuine violations from the validation provider rather than fabricated ones.
     */
    private static final class SubmittedAccount {

        /** Mandatory field: a blank value is the legacy blank flag. */
        @NotBlank
        private final String acctStatus;

        /** Bounded field: an over-long value is the legacy not-OK flag. */
        @Size(max = 2)
        private final String openMonth;

        /** Nested contract, so a violated path has an interior node above its leaf. */
        @Valid
        private final SubmittedAddress address;

        private SubmittedAccount(String acctStatus, String openMonth, SubmittedAddress address) {
            this.acctStatus = acctStatus;
            this.openMonth = openMonth;
            this.address = address;
        }

        String getAcctStatus() {
            return this.acctStatus;
        }

        String getOpenMonth() {
            return this.openMonth;
        }

        SubmittedAddress getAddress() {
            return this.address;
        }
    }

    /** The nested half of {@link SubmittedAccount}, so a violation path reads address then zip. */
    private static final class SubmittedAddress {

        /** Mandatory nested field, giving a two-node property path. */
        @NotBlank
        private final String zip;

        private SubmittedAddress(String zip) {
            this.zip = zip;
        }

        String getZip() {
            return this.zip;
        }
    }

    /**
     * A handler-method stand-in. It is never invoked; it exists so that a genuine method parameter
     * with a retained name can be constructed for the framework failures that carry one.
     *
     * @param cardNumber the parameter whose retained name the assertions expect
     */
    private static void sampleEndpoint(String cardNumber) {
        throw new AssertionError("sampleEndpoint exists only as a parameter source: " + cardNumber);
    }

    /**
     * Builds the method parameter the framework failures need, with name discovery enabled so the
     * retained parameter name is visible exactly as it is at runtime.
     *
     * @return the first parameter of {@link #sampleEndpoint(String)}
     */
    private static MethodParameter sampleMethodParameter() {
        MethodParameter parameter = new MethodParameter(sampleMethod(), 0);
        parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        return parameter;
    }

    /**
     * Looks up {@link #sampleEndpoint(String)}. This is the one place the test reads its own
     * structure, and it does so because the framework failure types are constructed from a method
     * parameter and there is no other way to obtain one.
     *
     * @return the sample handler method
     */
    private static Method sampleMethod() {
        try {
            return GlobalExceptionHandlerTest.class.getDeclaredMethod("sampleEndpoint", String.class);
        } catch (NoSuchMethodException absent) {
            throw new AssertionError("sampleEndpoint must exist for parameter construction", absent);
        }
    }

    /**
     * A minimal inbound message, supplied because the non-deprecated unreadable-body constructor
     * requires one. Its content is never read by the advice.
     */
    private static final class EmptyInputMessage implements HttpInputMessage {

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public HttpHeaders getHeaders() {
            return new HttpHeaders();
        }
    }

    /**
     * Builds one rejected handler parameter, as the framework hands it to the advice.
     *
     * <p>The container, container index and container key are all absent because the parameter under
     * test is a plain value rather than an element of a validated collection, which is the shape the
     * module's own endpoints produce. The source extractor returns the resolvable unchanged: the
     * advice never unwraps a resolvable to a provider-specific type, and supplying an extractor that
     * did so would make the fixture depend on a provider the advice does not name.</p>
     *
     * @param argument the value the parameter received, possibly {@code null}
     * @param errors   the resolvable errors the provider reported for it, at least one
     * @return the rejected-parameter result
     */
    private static ParameterValidationResult parameterRejection(Object argument,
            MessageSourceResolvable... errors) {
        return new ParameterValidationResult(sampleMethodParameter(), argument, List.of(errors), null,
                null, null, (resolvable, targetType) -> resolvable);
    }

    /**
     * Wraps rejected parameters into the failure the framework raises for them.
     *
     * @param results the rejected parameters, at least one
     * @return the method-validation failure
     */
    private static HandlerMethodValidationException methodValidationFailure(
            ParameterValidationResult... results) {
        return new HandlerMethodValidationException(MethodValidationResult.create(
                new GlobalExceptionHandlerTest(), sampleMethod(), List.of(results)));
    }

    /**
     * Builds a binding result over a submitted map, so a rejected value can be established exactly
     * without depending on bean introspection.
     *
     * @param submitted the values the caller is treated as having submitted
     * @return an empty binding result over those values
     */
    private static MapBindingResult bindingResultOver(Map<String, Object> submitted) {
        return new MapBindingResult(submitted, "submittedAccount");
    }

    /**
     * Asserts the properties every framework translation must hold, whatever its detail.
     *
     * @param response        the response the advice produced
     * @param expectedSummary the neutral summary the advice must have used
     */
    private static void assertFrameworkRejection(ResponseEntity<ErrorResponse> response,
            String expectedSummary) {
        assertThat(response.getStatusCode()).as("framework rejections are caller-caused")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ErrorResponse body = response.getBody();
        assertThat(body).as("a translated framework rejection always carries a body").isNotNull();
        assertThat(body.message()).as("only the module's own neutral summary crosses the boundary")
                .isEqualTo(expectedSummary);

        // A framework failure names no legacy screen field, so every entry carries an empty tag and
        // the response offers no focus hint at all.
        assertThat(body.fieldErrors()).allSatisfy(fieldError ->
                assertThat(fieldError.screenFieldId()).isEmpty());
        assertThat(body.focusScreenFieldId()).as("no legacy screen field to focus").isNull();

        assertNothingSensitiveEscaped(body);
    }

    /**
     * Asserts that no response text carries a rejected value, a framework type name, a stack frame
     * or any other internal detail.
     *
     * @param body the response body to inspect
     */
    private static void assertNothingSensitiveEscaped(ErrorResponse body) {
        assertThat(body.message()).doesNotContain(SENSITIVE_CARD_NUMBER, SENSITIVE_PASSWORD,
                "Exception", "org.springframework", "com.carddemo", "java.lang", "\tat ");
        assertThat(body.fieldErrors()).allSatisfy(fieldError -> {
            if (fieldError.message() != null) {
                assertThat(fieldError.message()).doesNotContain(SENSITIVE_CARD_NUMBER,
                        SENSITIVE_PASSWORD, "Exception", "org.springframework", "com.carddemo",
                        "java.lang", "\tat ");
            }
        });
    }

    // 1. The six failure carriers

    @Nested
    @DisplayName("the six module failure carriers")
    class CarrierHandlers {

        @Test
        @DisplayName("an online abend answers 500 with the operator message field alone, so the abend "
                + "code, the failing component and the reason stay on the diagnostic channel")
        void onlineAbendAnswersFiveHundredWithTheOperatorMessageOnly() {
            AbendException abend = new AbendException(AbendException.ONLINE_ABEND_CODE, "COACTUPC",
                    "record was changed", "UPDATE ABANDONED, RECORD WAS CHANGED");

            ResponseEntity<ErrorResponse> response = handler.handleAbend(abend);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo(abend.getMessage());
            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.focusScreenFieldId()).isNull();
            // The three diagnostic components are not rendered anywhere in the body.
            assertThat(body.message()).doesNotContain("COACTUPC",
                    AbendException.ONLINE_ABEND_CODE);
        }

        @Test
        @DisplayName("an abend log omits a caller-carried culprit, including embedded line controls")
        void anAbendLogOmitsTheCallerCarriedCulprit() {
            final String unsafeCulprit = "BAD\nAPP";
            final AbendException abend = new AbendException(
                    AbendException.ONLINE_ABEND_CODE,
                    unsafeCulprit,
                    "record was changed",
                    "UPDATE ABANDONED, RECORD WAS CHANGED");
            final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
            final Level previousLevel = logger.getLevel();
            final ListAppender<ILoggingEvent> recorder = new ListAppender<>();
            recorder.start();
            logger.setLevel(Level.ERROR);
            logger.addAppender(recorder);
            try {
                handler.handleAbend(abend);
            } finally {
                logger.detachAppender(recorder);
                logger.setLevel(previousLevel);
                recorder.stop();
            }

            assertThat(recorder.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage())
                        .contains("abendCode=" + AbendException.ONLINE_ABEND_CODE)
                        .contains("reason=record was changed")
                        .doesNotContain(unsafeCulprit, "culprit=", "\n", "\r");
            });
        }

        @Test
        @DisplayName("an abend under any code other than the online one answers with the terminal "
                + "literal instead of its own message, because only the online routine put its text "
                + "in front of a person - the batch sites call CEE3ABD with a code and no message and "
                + "DISPLAY their diagnostic to the job log")
        void anAbendUnderAnyOtherCodeAnswersWithTheTerminalLiteral() {
            AbendException unrecognisedCode = new AbendException("0001", "COACTUPC",
                    "record was changed", "UPDATE ABANDONED, RECORD WAS CHANGED");
            AbendException batchCode = new AbendException(AbendException.BATCH_ABEND_CODE,
                    "CBACT01C", "read failed",
                    "ERROR READING ACCOUNT MASTER - FILE STATUS IS: 35");

            for (AbendException internal : List.of(unrecognisedCode, batchCode)) {
                ErrorResponse body = handler.handleAbend(internal).getBody();

                assertThat(body).isNotNull();
                assertThat(body.message())
                        .as("abendCode %s", internal.code())
                        .isEqualTo(AbendException.DEFAULT_MESSAGE);
                assertThat(body.message()).isNotEqualTo(internal.getMessage());
            }
        }

        @Test
        @DisplayName("an unhandled file operation failure answers 500 with the terminal abend text, never "
                + "the raw two-byte status or the legacy resource name")
        void fileStatusAnswersFiveHundredWithoutTheRawStatus() {
            FileStatusException failure = new FileStatusException("35", "OPEN", "ACCTFILE");

            ResponseEntity<ErrorResponse> response = handler.handleFileStatus(failure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo(AbendException.DEFAULT_MESSAGE);
            assertThat(body.message()).doesNotContain("35", "ACCTFILE", "OPEN");
            assertThat(body.fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("a keyed read that resolved to nothing answers 404 with one neutral summary, never the "
                + "searched key, because a business key can be a card primary account number")
        void recordNotFoundAnswersFourZeroFourWithoutTheKey() {
            RecordNotFoundException missing =
                    new RecordNotFoundException("CARD", SENSITIVE_CARD_NUMBER, "CARDDAT");

            ResponseEntity<ErrorResponse> response = handler.handleRecordNotFound(missing);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo(ORACLE_RECORD_NOT_FOUND);
            assertThat(body.fieldErrors()).isEmpty();
            assertNothingSensitiveEscaped(body);
        }

        @Test
        @DisplayName("a field-level validation failure answers 400 carrying every entry in the order the "
                + "service assembled it, with both legacy states preserved and the focus hint taken from "
                + "the first entry")
        void validationAnswersFourHundredPreservingBothStatesAndOrder() {
            ValidationException failure = new ValidationException("Account update rejected", List.of(
                    new ValidationException.FieldError(PROPERTY_ACCOUNT_STATUS, BMS_ACCOUNT_STATUS,
                            ValidationException.FieldState.MISSING, "Account status must be supplied"),
                    new ValidationException.FieldError("openYear", "OPNYEAR",
                            ValidationException.FieldState.INVALID, "Open year must be four digits")));

            ResponseEntity<ErrorResponse> response = handler.handleValidation(failure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo("Account update rejected");
            assertThat(body.fieldErrors()).hasSize(2);
            assertThat(body.fieldErrors().get(0).fieldName()).isEqualTo(PROPERTY_ACCOUNT_STATUS);
            assertThat(body.fieldErrors().get(0).screenFieldId()).isEqualTo(BMS_ACCOUNT_STATUS);
            assertThat(body.fieldErrors().get(0).state())
                    .isSameAs(ErrorResponse.FieldState.MISSING);
            assertThat(body.fieldErrors().get(1).state())
                    .isSameAs(ErrorResponse.FieldState.INVALID);
            assertThat(body.focusScreenFieldId()).isEqualTo(BMS_ACCOUNT_STATUS);
        }

        @Test
        @DisplayName("a validation failure whose first entry is not screen bound offers no focus hint, and "
                + "an absent field name is reported as empty rather than propagating a null")
        void validationWithoutAScreenFieldOffersNoFocusHint() {
            ValidationException failure = new ValidationException("Rejected", List.of(
                    new ValidationException.FieldError(null, "   ",
                            ValidationException.FieldState.INVALID, null)));

            ResponseEntity<ErrorResponse> response = handler.handleValidation(failure);

            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().get(0).fieldName()).isEmpty();
            // The tag itself is carried untrimmed inside the entry; only the hint declines it.
            assertThat(body.fieldErrors().get(0).screenFieldId()).isEqualTo("   ");
            assertThat(body.fieldErrors().get(0).message()).isNull();
            assertThat(body.focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("a summary-only validation failure answers 400 with no field detail, which is the "
                + "first-submission shape the legacy screen showed before any decoration")
        void validationWithoutFieldDetailAnswersFourHundred() {
            ResponseEntity<ErrorResponse> response =
                    handler.handleValidation(new ValidationException("Select a report type"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo("Select a report type");
            assertThat(body.hasFieldErrors()).isFalse();
            assertThat(body.focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("all three conflict arms answer 409 with their own verbatim legacy text, because a "
                + "status code cannot encode four texts and none of the three abends")
        void everyConflictArmAnswersFourZeroNineWithItsOwnText() {
            for (OptimisticLockConflictException.ConflictKind kind
                    : OptimisticLockConflictException.ConflictKind.values()) {
                OptimisticLockConflictException conflict =
                        new OptimisticLockConflictException(kind, "Account", SENSITIVE_CARD_NUMBER);

                ResponseEntity<ErrorResponse> response =
                        handler.handleOptimisticLockConflict(conflict);

                assertThat(response.getStatusCode()).as("arm %s", kind).isEqualTo(HttpStatus.CONFLICT);
                ErrorResponse body = response.getBody();
                assertThat(body).isNotNull();
                assertThat(body.message()).isEqualTo(conflict.getMessage());
                assertThat(body.fieldErrors()).isEmpty();
                assertNothingSensitiveEscaped(body);
            }
        }

        @Test
        @DisplayName("a failed job-submission publish answers 200 with the frozen failure literal, because "
                + "the legacy destination ignores write errors and the transaction completes")
        void aFailedJobSubmissionAnswersTwoHundredWithTheFrozenLiteral() {
            JobSubmissionException failure = new JobSubmissionException("JOBS.fifo",
                    "QueueDoesNotExistException", "the queue does not exist", 3,
                    new IllegalStateException("transport failure"));

            ResponseEntity<ErrorResponse> response = handler.handleJobSubmission(failure);

            assertThat(response.getStatusCode()).as("202 would claim work was accepted")
                    .isEqualTo(HttpStatus.OK);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
            // The diagnostic codes belong on the log line, exactly where the legacy put them.
            assertThat(body.message()).doesNotContain("QueueDoesNotExistException", "JOBS.fifo");
            assertThat(body.fieldErrors()).isEmpty();
        }
    }

    // 1b. Version conflicts the persistence provider detects rather than module code

    /**
     * Contract for the arm that answers a conflict raised by the persistence provider.
     *
     * <p><strong>What was wrong.</strong> The {@code @Version} attribute on the account and card
     * entities is enforced by the provider at flush time, not by module code, so the provider raises its
     * own type rather than the module's carrier. Only the module's carrier was mapped, so a provider
     * conflict fell through to the terminal handler and became a {@code 500} carrying the abend literal.
     * That told a client the server had broken when in fact its screen was merely stale: wrong status,
     * wrong text, and a condition the legacy system had a specific message for.
     *
     * <p><strong>What this proves.</strong> Two things, and the second is the one that keeps the fix
     * honest. The first is behavioural: each shape the conflict arrives in answers {@code 409} with the
     * same verbatim legacy text the module's own conflict produces, so a client cannot tell which layer
     * detected the staleness. The second is structural: all three declarations are load-bearing. None of
     * the three covers another, their nearest common ancestor is {@code RuntimeException}, and the one
     * supertype that does cover two of them also covers unrelated failures that must not answer
     * {@code 409}. Those facts are computed from the type lattice rather than asserted, so a later
     * "simplification" to a single broader declaration fails here instead of silently mapping a missing
     * entity or a rolled-back commit onto a conflict.
     */
    @Nested
    @DisplayName("a version conflict raised by the persistence provider")
    class ProviderOptimisticLockTranslation {

        @Test
        @DisplayName("Spring's translated conflict answers 409 with the verbatim legacy record-changed text")
        void springsTranslatedConflictAnswersConflict() {
            ResponseEntity<ErrorResponse> response = handler.handleProviderOptimisticLockFailure(
                    new OptimisticLockingFailureException("version mismatch on ACCTDAT"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message())
                    .isEqualTo(OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
            assertThat(body.fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("the specification's conflict answers 409, because a flush outside a repository call "
                + "escapes Spring's translation entirely")
        void theSpecificationConflictAnswersConflict() {
            ResponseEntity<ErrorResponse> response = handler.handleProviderOptimisticLockFailure(
                    new OptimisticLockException("row was updated by another transaction"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message())
                    .isEqualTo(OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
        }

        @Test
        @DisplayName("the provider's own stale-state failure answers 409, which is why it is named "
                + "separately rather than left to the specification type")
        void theProvidersOwnStaleStateFailureAnswersConflict() {
            ResponseEntity<ErrorResponse> response = handler.handleProviderOptimisticLockFailure(
                    new StaleStateException("Batch update returned unexpected row count"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message())
                    .isEqualTo(OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
        }

        @Test
        @DisplayName("the subclass a Spring Data repository actually raises is covered by the superclass "
                + "declaration, so the common case needs no separate arm")
        void theRepositorySubclassIsCoveredByTheSuperclassDeclaration() {
            // This is the shape a saveAndFlush on a stale @Version entity produces in practice.
            ResponseEntity<ErrorResponse> response = handler.handleProviderOptimisticLockFailure(
                    new ObjectOptimisticLockingFailureException("com.carddemo.domain.Account",
                            "00000000011"));

            assertThat(OptimisticLockingFailureException.class)
                    .as("the declared type is a supertype of the one the repository raises")
                    .isAssignableFrom(ObjectOptimisticLockingFailureException.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message())
                    .isEqualTo(OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
        }

        @Test
        @DisplayName("the provider's row-level subclass is covered by the stale-state declaration")
        void theProvidersRowLevelSubclassIsCovered() {
            ResponseEntity<ErrorResponse> response = handler.handleProviderOptimisticLockFailure(
                    new StaleObjectStateException("com.carddemo.domain.Card", SENSITIVE_CARD_NUMBER));

            assertThat(StaleStateException.class)
                    .isAssignableFrom(StaleObjectStateException.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message())
                    .isEqualTo(OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
        }

        @Test
        @DisplayName("a provider conflict and the module's own conflict are indistinguishable to a client, "
                + "because one condition has one status and one text")
        void aProviderConflictIsIndistinguishableFromTheModulesOwn() {
            // The legacy write paths detected staleness by re-reading the record and comparing it against
            // the image they had presented, and answered with one text. Which layer notices here is an
            // implementation detail of the port, so it must not be observable.
            ResponseEntity<ErrorResponse> fromProvider = handler.handleProviderOptimisticLockFailure(
                    new ObjectOptimisticLockingFailureException("com.carddemo.domain.Account", "11"));
            ResponseEntity<ErrorResponse> fromModule = handler.handleOptimisticLockConflict(
                    new OptimisticLockConflictException(OptimisticLockConflictException.ConflictKind
                            .RECORD_CHANGED_BEFORE_UPDATE, "Account", "00000000011"));

            assertThat(fromProvider.getStatusCode()).isEqualTo(fromModule.getStatusCode());
            ErrorResponse providerBody = fromProvider.getBody();
            ErrorResponse moduleBody = fromModule.getBody();
            assertThat(providerBody).isNotNull();
            assertThat(moduleBody).isNotNull();
            assertThat(providerBody.message()).isEqualTo(moduleBody.message());
            assertThat(providerBody.fieldErrors()).isEqualTo(moduleBody.fieldErrors());
        }

        @Test
        @DisplayName("no entity name, persistent class name, identifier or provider type name reaches the "
                + "body, however much of it the carrier holds")
        void theBodyDisclosesNothingTheCarrierHolds() {
            List<Exception> carriers = List.of(
                    new ObjectOptimisticLockingFailureException("com.carddemo.domain.Account",
                            SENSITIVE_CARD_NUMBER),
                    new StaleObjectStateException("com.carddemo.domain.Card", SENSITIVE_CARD_NUMBER),
                    new OptimisticLockException("stale row for " + SENSITIVE_CARD_NUMBER),
                    new StaleStateException("unexpected row count for " + SENSITIVE_PASSWORD),
                    new OptimisticLockingFailureException(SENSITIVE_PASSWORD,
                            new IllegalStateException(SENSITIVE_CARD_NUMBER)));

            assertThat(carriers).allSatisfy(carrier -> {
                ErrorResponse body =
                        handler.handleProviderOptimisticLockFailure(carrier).getBody();
                assertThat(body).isNotNull();
                assertNothingSensitiveEscaped(body);
                assertThat(body.message())
                        .as("the entity the conflict concerns is a diagnostic, not a disclosure")
                        .doesNotContain("Account", "Card", "domain", "409");
            });
        }

        @Test
        @DisplayName("every shape a provider conflict arrives in resolves to the conflict arm rather than "
                + "falling through to the terminal handler")
        void everyProviderConflictShapeResolvesToTheConflictArm() {
            // This is the assertion that actually pins the fix. Invoking the arm proves its body is
            // right; only resolution proves the container would reach it. Before the arm existed each of
            // these resolved to the terminal handler and became a 500 carrying the abend literal.
            List<Class<?>> shapes = List.of(
                    OptimisticLockingFailureException.class,
                    ObjectOptimisticLockingFailureException.class,
                    OptimisticLockException.class,
                    StaleStateException.class,
                    StaleObjectStateException.class);

            assertThat(shapes).allSatisfy(shape ->
                    assertThat(resolvedHandlerNameFor(shape))
                            .as("%s must resolve to the conflict arm", shape.getSimpleName())
                            .isEqualTo("handleProviderOptimisticLockFailure"));
        }

        @Test
        @DisplayName("the resolution check is not vacuous: a failure with no arm of its own still resolves "
                + "to the terminal handler, and the module's own conflict keeps its own arm")
        void theResolutionCheckDistinguishesArms() {
            // Without this, the assertion above would pass even if every type resolved to the same place.
            assertThat(resolvedHandlerNameFor(IllegalStateException.class))
                    .isEqualTo("handleUnexpectedFailure");
            assertThat(resolvedHandlerNameFor(OptimisticLockConflictException.class))
                    .isEqualTo("handleOptimisticLockConflict");
            assertThat(resolvedHandlerNameFor(RecordNotFoundException.class))
                    .isEqualTo("handleRecordNotFound");
        }

        @Test
        @DisplayName("none of the three declared types covers another, so no declaration is redundant")
        void noDeclaredTypeCoversAnother() {
            List<Class<?>> declared = List.of(OptimisticLockingFailureException.class,
                    OptimisticLockException.class, StaleStateException.class);

            for (Class<?> outer : declared) {
                for (Class<?> inner : declared) {
                    if (outer != inner) {
                        assertThat(outer.isAssignableFrom(inner))
                                .as("%s would make %s redundant", outer.getSimpleName(),
                                        inner.getSimpleName())
                                .isFalse();
                    }
                }
            }
        }

        @Test
        @DisplayName("the nearest common ancestor of the three is RuntimeException, which the advice "
                + "refuses to declare, so three declarations are the narrowest possible cover")
        void theNearestCommonAncestorIsTheForbiddenRuntimeException() {
            // Computed from the lattice rather than hardcoded, so it stays true if a dependency
            // reparents one of these types.
            List<Class<?>> shared = new ArrayList<>(superclassChainOf(
                    OptimisticLockingFailureException.class));
            shared.retainAll(superclassChainOf(OptimisticLockException.class));
            shared.retainAll(superclassChainOf(StaleStateException.class));

            assertThat(shared).isNotEmpty();
            assertThat(shared.get(0))
                    .as("nothing narrower than RuntimeException covers all three")
                    .isEqualTo(RuntimeException.class);
            assertThat(declaredHandledTypes())
                    .as("and the advice declares neither that ancestor nor anything above it")
                    .doesNotContain(RuntimeException.class);
        }

        @Test
        @DisplayName("the one supertype that does cover two of the three is not declared, because it also "
                + "covers failures that must not answer 409")
        void theTemptingSharedSupertypeIsNotDeclared() {
            // Two of the three descend from PersistenceException, which makes collapsing them look safe.
            // It is not: that supertype also covers a missing entity and a rolled-back commit, neither of
            // which is a conflict. Declaring it would map both onto the record-changed text.
            assertThat(PersistenceException.class)
                    .isAssignableFrom(OptimisticLockException.class)
                    .isAssignableFrom(StaleStateException.class);
            assertThat(PersistenceException.class)
                    .as("and it reaches well beyond conflicts")
                    .isAssignableFrom(EntityNotFoundException.class)
                    .isAssignableFrom(RollbackException.class);

            assertThat(declaredHandledTypes())
                    .doesNotContain(PersistenceException.class, EntityNotFoundException.class,
                            RollbackException.class);
        }

        @Test
        @DisplayName("pessimistic lock-acquisition failures are deliberately not folded in, because they "
                + "are a different condition with a different legacy message")
        void pessimisticFailuresAreNotFoldedIn() {
            // Asserted so the exclusion cannot be quietly reversed. No write path in this module produces
            // one, so mapping it would put text on a response for a state the module cannot reach.
            assertThat(declaredHandledTypes())
                    .doesNotContain(PessimisticLockingFailureException.class,
                            CannotAcquireLockException.class);
            assertThat(OptimisticLockingFailureException.class
                    .isAssignableFrom(CannotAcquireLockException.class))
                    .as("and the declared optimistic type does not reach a pessimistic failure anyway")
                    .isFalse();
        }

        @Test
        @DisplayName("a conflict wrapped in a cause chain is still answered from the arm's own literal, "
                + "never from a cause's message")
        void aWrappedConflictStillAnswersFromTheFrozenLiteral() {
            ResponseEntity<ErrorResponse> response = handler.handleProviderOptimisticLockFailure(
                    new OptimisticLockingFailureException("outer",
                            new StaleObjectStateException("com.carddemo.domain.Account",
                                    SENSITIVE_CARD_NUMBER)));

            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message())
                    .isEqualTo(OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
            assertNothingSensitiveEscaped(body);
        }

        @Test
        @DisplayName("every way a carrier can name or fail to name its entity is answered identically, "
                + "so the diagnostic naming can never change what a client sees")
        void everyDiagnosticNamingShapeIsAnsweredIdentically() {
            // The arm names the conflicting entity for the log line, and each type family carries it
            // differently: one as a persistent class name, one as an entity name, one as the entity
            // instance itself, and any of them can carry nothing at all. That naming is a diagnostic, so
            // the requirement is that it is total - it must never throw and never alter the response -
            // rather than that it succeeds. Every shape is exercised here because a null in any one of
            // them is the way a diagnostic helper turns a conflict into a 500.
            List<Exception> everyShape = List.of(
                    // carries a persistent class name
                    new ObjectOptimisticLockingFailureException("com.carddemo.domain.Account", "11"),
                    // is that type but carries no persistent class name
                    new ObjectOptimisticLockingFailureException("no class name",
                            new IllegalStateException("cause")),
                    // carries an entity name
                    new StaleObjectStateException("com.carddemo.domain.Card", "11"),
                    // is that type but carries no entity name
                    new StaleObjectStateException(null, "11"),
                    // carries the entity instance itself
                    new OptimisticLockException("stale", null, "an entity instance"),
                    // is that type but carries no entity
                    new OptimisticLockException("stale"),
                    // carries nothing at all
                    new StaleStateException("Batch update returned unexpected row count"),
                    new OptimisticLockingFailureException("version mismatch"));

            assertThat(everyShape).allSatisfy(carrier -> {
                ResponseEntity<ErrorResponse> response =
                        handler.handleProviderOptimisticLockFailure(carrier);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                ErrorResponse body = response.getBody();
                assertThat(body).isNotNull();
                assertThat(body.message())
                        .isEqualTo(OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
                assertThat(body.fieldErrors()).isEmpty();
                assertNothingSensitiveEscaped(body);
            });
        }

        @Test
        @DisplayName("a carrier holding no entity at all is answered identically, so the diagnostic "
                + "fallback never changes what a client sees")
        void aCarrierWithoutAnEntityIsAnsweredIdentically() {
            ResponseEntity<ErrorResponse> named = handler.handleProviderOptimisticLockFailure(
                    new ObjectOptimisticLockingFailureException("com.carddemo.domain.Account", "11"));
            ResponseEntity<ErrorResponse> anonymous = handler.handleProviderOptimisticLockFailure(
                    new StaleStateException("Batch update returned unexpected row count"));

            assertThat(anonymous.getStatusCode()).isEqualTo(named.getStatusCode());
            ErrorResponse anonymousBody = anonymous.getBody();
            ErrorResponse namedBody = named.getBody();
            assertThat(anonymousBody).isNotNull();
            assertThat(namedBody).isNotNull();
            assertThat(anonymousBody.message()).isEqualTo(namedBody.message());
        }
    }

    // 2. Request-body and binding rejections raised by the web framework

    @Nested
    @DisplayName("a request body rejected by declarative validation")
    class BindingValidationTranslation {

        @Test
        @DisplayName("a field rejected with a value present is the not-OK state, and the value itself never "
                + "appears in the response even though the framework carried it")
        void aFieldRejectedWithAValuePresentIsInvalidAndTheValueIsWithheld() {
            Map<String, Object> submitted = new LinkedHashMap<>();
            submitted.put(PROPERTY_CARD_NUMBER, SENSITIVE_CARD_NUMBER);
            BindException rejection = new BindException(bindingResultOver(submitted));
            rejection.addError(new org.springframework.validation.FieldError("submittedAccount",
                    PROPERTY_CARD_NUMBER, SENSITIVE_CARD_NUMBER, false, null, null,
                    "Card number must be 16 digits"));

            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(rejection);

            assertFrameworkRejection(response, ORACLE_VALIDATION_FAILED);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().get(0).fieldName()).isEqualTo(PROPERTY_CARD_NUMBER);
            assertThat(body.fieldErrors().get(0).state()).isSameAs(ErrorResponse.FieldState.INVALID);
            assertThat(body.fieldErrors().get(0).message()).isEqualTo("Card number must be 16 digits");
        }

        @Test
        @DisplayName("a field rejected with nothing supplied is the blank state, which is the distinction the "
                + "legacy decoration macro drew between marking a field and merely highlighting it")
        void aFieldRejectedWithNothingSuppliedIsMissing() {
            BindException rejection = new BindException(bindingResultOver(new LinkedHashMap<>()));
            rejection.addError(new org.springframework.validation.FieldError("submittedAccount",
                    PROPERTY_ACCOUNT_STATUS, null, false, null, null, "Account status is required"));

            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(rejection);

            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors().get(0).state()).isSameAs(ErrorResponse.FieldState.MISSING);
        }

        @Test
        @DisplayName("a field rejected with whitespace alone is also the blank state, because a fixed-width "
                + "screen field an operator never typed into arrives as spaces rather than as absent")
        void aFieldRejectedWithWhitespaceAloneIsMissing() {
            BindException rejection = new BindException(bindingResultOver(new LinkedHashMap<>()));
            rejection.addError(new org.springframework.validation.FieldError("submittedAccount",
                    PROPERTY_ACCOUNT_STATUS, "    ", false, null, null, "Account status is required"));

            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(rejection);

            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors().get(0).state()).isSameAs(ErrorResponse.FieldState.MISSING);
        }

        @Test
        @DisplayName("a whole-object rejection follows the per-field entries and names no field, so a client "
                + "reading entries in order still sees the field-attributable ones first")
        void aWholeObjectRejectionFollowsThePerFieldEntriesAndNamesNoField() {
            BindException rejection = new BindException(bindingResultOver(new LinkedHashMap<>()));
            rejection.addError(new org.springframework.validation.FieldError("submittedAccount",
                    PROPERTY_ACCOUNT_STATUS, "X", false, null, null, "Account status is not valid"));
            rejection.reject("crossField", "Open date must precede expiry date");

            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(rejection);

            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(2);
            assertThat(body.fieldErrors().get(0).fieldName()).isEqualTo(PROPERTY_ACCOUNT_STATUS);
            assertThat(body.fieldErrors().get(1).fieldName()).isEmpty();
            assertThat(body.fieldErrors().get(1).state()).isSameAs(ErrorResponse.FieldState.INVALID);
            assertThat(body.fieldErrors().get(1).message())
                    .isEqualTo("Open date must precede expiry date");
        }

        @Test
        @DisplayName("the annotated-body rejection the framework actually raises reaches the same handler and "
                + "produces the same shape, because it is a subtype of the family that is declared")
        void theAnnotatedBodyRejectionReachesTheSameHandler() {
            Map<String, Object> submitted = new LinkedHashMap<>();
            submitted.put(PROPERTY_ACCOUNT_STATUS, EMPTY_TEXT);
            MapBindingResult bindingResult = bindingResultOver(submitted);
            bindingResult.addError(new org.springframework.validation.FieldError("submittedAccount",
                    PROPERTY_ACCOUNT_STATUS, EMPTY_TEXT, false, null, null, "must not be blank"));
            MethodArgumentNotValidException rejection =
                    new MethodArgumentNotValidException(sampleMethodParameter(), bindingResult);

            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(rejection);

            assertFrameworkRejection(response, ORACLE_VALIDATION_FAILED);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().get(0).fieldName()).isEqualTo(PROPERTY_ACCOUNT_STATUS);
            assertThat(body.fieldErrors().get(0).state()).isSameAs(ErrorResponse.FieldState.MISSING);
        }

        @Test
        @DisplayName("a rejection carrying no per-field evidence still answers with the canonical body rather "
                + "than an empty payload")
        void aRejectionCarryingNoEvidenceStillAnswersWithTheCanonicalBody() {
            ResponseEntity<ErrorResponse> response = handler.handleBindingValidation(
                    new BindException(bindingResultOver(new LinkedHashMap<>())));

            assertFrameworkRejection(response, ORACLE_VALIDATION_FAILED);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.hasFieldErrors()).isFalse();
        }
    }

    // 3. Constraints declared on handler parameters

    @Nested
    @DisplayName("a constraint declared on a handler parameter")
    class HandlerMethodValidationTranslation {

        @Test
        @DisplayName("a rejected parameter is named by its retained parameter name, and its argument decides "
                + "the state without being placed in the response")
        void aRejectedParameterIsNamedByItsParameterName() {
            HandlerMethodValidationException rejection = methodValidationFailure(
                    parameterRejection(SENSITIVE_CARD_NUMBER,
                            new DefaultMessageSourceResolvable(new String[] {"Size"}, null,
                                    "size must be between 0 and 8")));

            ResponseEntity<ErrorResponse> response =
                    handler.handleHandlerMethodValidation(rejection);

            assertFrameworkRejection(response, ORACLE_VALIDATION_FAILED);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().get(0).fieldName()).isEqualTo(PROPERTY_CARD_NUMBER);
            assertThat(body.fieldErrors().get(0).state()).isSameAs(ErrorResponse.FieldState.INVALID);
            assertThat(body.fieldErrors().get(0).message()).isEqualTo("size must be between 0 and 8");
        }

        @Test
        @DisplayName("an error that identifies a property of a validated argument names that property rather "
                + "than the enclosing parameter, so a client is told which field to correct")
        void anErrorIdentifyingAPropertyNamesThePropertyNotTheParameter() {
            HandlerMethodValidationException rejection = methodValidationFailure(
                    parameterRejection(new Object(),
                            new org.springframework.validation.FieldError("submittedAccount",
                                    PROPERTY_ACCOUNT_STATUS, "  ", false, null, null,
                                    "must not be blank")));

            ResponseEntity<ErrorResponse> response =
                    handler.handleHandlerMethodValidation(rejection);

            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().get(0).fieldName()).isEqualTo(PROPERTY_ACCOUNT_STATUS);
            assertThat(body.fieldErrors().get(0).state()).isSameAs(ErrorResponse.FieldState.MISSING);
        }

        @Test
        @DisplayName("a parameter rejected with no argument at all is the blank state")
        void aParameterRejectedWithNoArgumentIsMissing() {
            HandlerMethodValidationException rejection = methodValidationFailure(
                    parameterRejection(null, new DefaultMessageSourceResolvable(
                            new String[] {"NotNull"}, null, "must not be null")));

            ResponseEntity<ErrorResponse> response =
                    handler.handleHandlerMethodValidation(rejection);

            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors().get(0).state()).isSameAs(ErrorResponse.FieldState.MISSING);
        }

        @Test
        @DisplayName("a parameter that failed two constraints contributes one entry per constraint, so a "
                + "client is told every reason the value was refused rather than only the first")
        void aParameterThatFailedTwoConstraintsContributesTwoEntries() {
            HandlerMethodValidationException rejection = methodValidationFailure(
                    parameterRejection("9",
                            new DefaultMessageSourceResolvable(new String[] {"Size"}, null,
                                    "size must be between 16 and 16"),
                            new DefaultMessageSourceResolvable(new String[] {"Pattern"}, null,
                                    "must match \"[0-9]{16}\"")));

            ResponseEntity<ErrorResponse> response =
                    handler.handleHandlerMethodValidation(rejection);

            assertFrameworkRejection(response, ORACLE_VALIDATION_FAILED);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(2);
            assertThat(body.fieldErrors()).extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactly(PROPERTY_CARD_NUMBER, PROPERTY_CARD_NUMBER);
            assertThat(body.fieldErrors()).extracting(ErrorResponse.FieldError::message)
                    .containsExactly("size must be between 16 and 16", "must match \"[0-9]{16}\"");
        }

        @Test
        @DisplayName("two rejected parameters are reported in the order the framework listed them, because "
                + "that order follows the handler signature and is the order a client reads its own request")
        void twoRejectedParametersAreReportedInTheOrderTheFrameworkListedThem() {
            HandlerMethodValidationException rejection = methodValidationFailure(
                    parameterRejection(null, new DefaultMessageSourceResolvable(
                            new String[] {"NotNull"}, null, "must not be null")),
                    parameterRejection("X", new DefaultMessageSourceResolvable(
                            new String[] {"Pattern"}, null, "must match the required pattern")));

            ResponseEntity<ErrorResponse> response =
                    handler.handleHandlerMethodValidation(rejection);

            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("the framework never raises this failure with no rejected parameter at all, which is why "
                + "the handler carries no empty-evidence branch to be left untested")
        void theFrameworkNeverRaisesThisFailureWithNoRejectedParameter() {
            assertThatThrownBy(GlobalExceptionHandlerTest::methodValidationFailure)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // 4. Violations raised by the validation provider itself

    @Nested
    @DisplayName("violations raised by the validation provider")
    class ConstraintViolationTranslation {

        @Test
        @DisplayName("every genuine violation becomes one entry named by the leaf of its property path, so a "
                + "nested field is reported by its own name and not by its container's")
        void everyViolationBecomesOneEntryNamedByTheLeafOfItsPath() {
            Set<ConstraintViolation<SubmittedAccount>> violations = validator.validate(
                    new SubmittedAccount("   ", "2026", new SubmittedAddress("")));
            assertThat(violations).as("the fixture must actually violate three constraints").hasSize(3);

            ResponseEntity<ErrorResponse> response =
                    handler.handleConstraintViolation(new ConstraintViolationException(violations));

            assertFrameworkRejection(response, ORACLE_VALIDATION_FAILED);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(3);
            assertThat(body.fieldErrors()).extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactly(PROPERTY_ACCOUNT_STATUS, "openMonth", "zip");
            assertThat(body.fieldErrors()).extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID, ErrorResponse.FieldState.MISSING);
            assertThat(body.fieldErrors()).extracting(ErrorResponse.FieldError::message)
                    .containsExactly("must not be blank", "size must be between 0 and 2",
                            "must not be blank");
        }

        @Test
        @DisplayName("entries are ordered by field name and then by message, because the provider reports an "
                + "unordered set and a body whose order varies between identical requests is untestable")
        void entriesAreOrderedByFieldNameAndThenByMessage() {
            Set<ConstraintViolation<SubmittedAccount>> violations = validator.validate(
                    new SubmittedAccount(null, "123456", new SubmittedAddress(null)));

            ResponseEntity<ErrorResponse> response =
                    handler.handleConstraintViolation(new ConstraintViolationException(violations));

            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            List<ErrorResponse.FieldError> entries = body.fieldErrors();
            for (int index = 1; index < entries.size(); index++) {
                ErrorResponse.FieldError previous = entries.get(index - 1);
                ErrorResponse.FieldError current = entries.get(index);
                int byName = previous.fieldName().compareTo(current.fieldName());
                assertThat(byName).as("entry %d must not sort before entry %d by name", index,
                        index - 1).isLessThanOrEqualTo(0);
                if (byName == 0) {
                    assertThat(orEmptyText(previous.message())
                            .compareTo(orEmptyText(current.message())))
                            .as("ties must be broken by message").isLessThanOrEqualTo(0);
                }
            }
        }

        @Test
        @DisplayName("the value that failed its constraint never reaches the response, even when it is a card "
                + "primary account number the estate provides no masking for")
        void theValueThatFailedItsConstraintNeverReachesTheResponse() {
            Set<ConstraintViolation<SubmittedAccount>> violations = validator.validate(
                    new SubmittedAccount(SENSITIVE_CARD_NUMBER, SENSITIVE_PASSWORD,
                            new SubmittedAddress("30301")));
            assertThat(violations).as("the over-long month must violate its bound").hasSize(1);

            ResponseEntity<ErrorResponse> response =
                    handler.handleConstraintViolation(new ConstraintViolationException(violations));

            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertNothingSensitiveEscaped(body);
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().get(0).fieldName()).isEqualTo("openMonth");
        }

        @Test
        @DisplayName("a rejection whose violation set is absent answers with the canonical body rather than "
                + "failing inside the handler")
        void aRejectionWhoseViolationSetIsAbsentAnswersWithTheCanonicalBody() {
            ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(
                    new ConstraintViolationException((Set<ConstraintViolation<?>>) null));

            assertFrameworkRejection(response, ORACLE_VALIDATION_FAILED);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.hasFieldErrors()).isFalse();
        }
    }

    // 5. Request values that could not be bound at all

    @Nested
    @DisplayName("a request value that could not be bound")
    class RequestBindingTranslation {

        @Test
        @DisplayName("a required parameter that was not supplied is named and reported as the blank state, "
                + "because the remedy is to supply a value rather than to correct one")
        void aRequiredParameterNotSuppliedIsNamedAndReportedAsMissing() {
            ResponseEntity<ErrorResponse> response = handler.handleMissingRequestParameter(
                    new MissingServletRequestParameterException("startDate", "java.lang.String"));

            assertFrameworkRejection(response, ORACLE_BINDING_FAILED);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().get(0).fieldName()).isEqualTo("startDate");
            assertThat(body.fieldErrors().get(0).state()).isSameAs(ErrorResponse.FieldState.MISSING);
            assertThat(body.fieldErrors().get(0).message()).isEqualTo(ORACLE_BINDING_FAILED);
            // The declared Java type the framework reports is internal and is withheld.
            assertThat(body.fieldErrors().get(0).message()).doesNotContain("java.lang.String");
        }

        @Test
        @DisplayName("a value that will not convert is named and reported as the not-OK state, with neither "
                + "the value nor the type it failed to convert to appearing anywhere")
        void aValueThatWillNotConvertIsNamedAndReportedAsInvalid() {
            ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(
                    new MethodArgumentTypeMismatchException(SENSITIVE_CARD_NUMBER, Integer.class,
                            PROPERTY_CARD_NUMBER, sampleMethodParameter(),
                            new NumberFormatException(SENSITIVE_CARD_NUMBER)));

            assertFrameworkRejection(response, ORACLE_BINDING_FAILED);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().get(0).fieldName()).isEqualTo(PROPERTY_CARD_NUMBER);
            assertThat(body.fieldErrors().get(0).state()).isSameAs(ErrorResponse.FieldState.INVALID);
            assertThat(body.fieldErrors().get(0).message()).doesNotContain("Integer");
        }

        @Test
        @DisplayName("a missing header answers in the same shape with no field detail, which proves the "
                + "declared family covers its members and none of them escapes into another shape")
        void aMissingHeaderAnswersInTheSameShapeWithNoFieldDetail() {
            ResponseEntity<ErrorResponse> response = handler.handleRequestBinding(
                    new MissingRequestHeaderException("X-Card-Number", sampleMethodParameter()));

            assertFrameworkRejection(response, ORACLE_BINDING_FAILED);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.hasFieldErrors()).isFalse();
        }

        @Test
        @DisplayName("the family's own detail message is discarded entirely, so a diagnostic that quoted "
                + "caller data cannot reach the caller through it")
        void theFamilyDetailMessageIsDiscardedEntirely() {
            ResponseEntity<ErrorResponse> response = handler.handleRequestBinding(
                    new ServletRequestBindingException(
                            "Could not bind value '" + SENSITIVE_PASSWORD + "'"));

            assertFrameworkRejection(response, ORACLE_BINDING_FAILED);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo(ORACLE_BINDING_FAILED);
        }
    }

    // 6. Request bodies that could not be read

    @Nested
    @DisplayName("a request body that could not be read")
    class UnreadableBodyTranslation {

        @Test
        @DisplayName("a body that did not parse answers 400 with the read summary and no field detail, "
                + "because a body that did not parse has no fields to attribute an error to")
        void aBodyThatDidNotParseAnswersWithTheReadSummaryAndNoFieldDetail() {
            ResponseEntity<ErrorResponse> response = handler.handleUnreadableBody(
                    new HttpMessageNotReadableException("JSON parse error at token 'x'",
                            new EmptyInputMessage()));

            assertFrameworkRejection(response, ORACLE_MALFORMED_BODY);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo(ORACLE_MALFORMED_BODY);
            assertThat(body.hasFieldErrors()).isFalse();
        }

        @Test
        @DisplayName("the parse diagnostic is discarded rather than reported, because it quotes the offending "
                + "fragment of the payload and a sign-on payload holds a password")
        void theParseDiagnosticIsDiscardedRatherThanReported() {
            ResponseEntity<ErrorResponse> response = handler.handleUnreadableBody(
                    new HttpMessageNotReadableException(
                            "Unexpected character in {\"password\":\"" + SENSITIVE_PASSWORD + "\"}",
                            new IllegalStateException(SENSITIVE_CARD_NUMBER),
                            new EmptyInputMessage()));

            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo(ORACLE_MALFORMED_BODY);
            assertNothingSensitiveEscaped(body);
        }

        @Test
        @DisplayName("a batch launch body carrying a property the closed launch surface does not declare "
                + "answers 400, and the refused property name is not echoed back, so a caller learns that "
                + "the body was refused without learning which names the surface holds")
        void aClosedLaunchSurfaceRefusalAnswersWithTheReadSummaryAndNamesNothing() throws Exception {
            UnrecognizedPropertyException refusal = null;
            try {
                new ObjectMapper().readValue("{\"transactionBackupCurrentGeneration\":\"G0007V00\"}",
                        BatchJobLaunchRequest.class);
            } catch (final UnrecognizedPropertyException expected) {
                refusal = expected;
            }
            assertThat(refusal)
                    .as("the closed surface is what raises the refusal, before any handler runs")
                    .isNotNull();

            ResponseEntity<ErrorResponse> response = handler.handleUnreadableBody(
                    new HttpMessageNotReadableException(refusal.getMessage(), refusal,
                            new EmptyInputMessage()));

            assertFrameworkRejection(response, ORACLE_MALFORMED_BODY);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.message()).isEqualTo(ORACLE_MALFORMED_BODY);
            assertThat(body.hasFieldErrors())
                    .as("a body that did not bind has no field to attribute an error to")
                    .isFalse();
            assertThat(body.toString())
                    .doesNotContain("transactionBackupCurrentGeneration")
                    .doesNotContain("interestParmDate");
        }
    }

    // 7. Invariants that hold across the whole advice

    @Nested
    @DisplayName("invariants across the whole advice")
    class AdviceInvariants {

        @Test
        @DisplayName("exactly one catch-all is declared and it is Exception, so nothing escapes the advice "
                + "into the container's default error page, while Throwable and Error stay undeclared "
                + "because a JVM error must not be reshaped into a response at all")
        void theOnlyCatchAllIsExceptionAndItIsNeitherThrowableNorError() {
            // Two requirements meet here and both are asserted, because either alone is unsafe.
            //
            // The first is closure: a failure the advice does not name must not reach the container's
            // default error handling, which composes its own body from the failure's own message and
            // would disclose whatever that message holds. A terminal Exception handler is the only way
            // to close that hole, and the paired assertion below pins what it may disclose - the frozen
            // terminal literal and nothing else.
            //
            // The second is restraint: the catch-all must not widen to Throwable or Error. An Error is
            // not a request fault, it is a statement that the JVM can no longer be relied upon, and
            // answering one with a 500 body would report a dead process as a served request. Those two
            // supertypes therefore remain forbidden, and RuntimeException remains forbidden too because
            // a handler at that level would sit between the named module carriers and the terminal
            // handler for no purpose the contract has, making the resolution order harder to reason
            // about without covering anything Exception does not already cover.
            assertThat(declaredHandledTypes())
                    .as("the terminal handler exists, so nothing reaches the default error page")
                    .contains(Exception.class);
            assertThat(declaredHandledTypes())
                    .as("no supertype broader than Exception, and no redundant level beneath it")
                    .doesNotContain(RuntimeException.class, Throwable.class, Error.class);
            assertThat(declaredHandledTypes())
                    .filteredOn(handled -> handled == Exception.class)
                    .as("exactly one terminal handler, so there is one terminal text rather than several")
                    .hasSize(1);

            ResponseEntity<ErrorResponse> terminal =
                    handler.handleUnexpectedFailure(new IllegalStateException(SENSITIVE_PASSWORD));
            assertThat(terminal.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            ErrorResponse terminalBody = terminal.getBody();
            assertThat(terminalBody).isNotNull();
            assertThat(terminalBody.message())
                    .as("the terminal literal is the frozen abend text, not the cause's own message")
                    .isEqualTo(AbendException.DEFAULT_MESSAGE);
            assertNothingSensitiveEscaped(terminalBody);
        }

        @Test
        @DisplayName("the advice declares exactly the twenty failure types this contract covers - the six "
                + "module carriers, the three provider conflict types, the eight framework rejections, the "
                + "two credential and entitlement refusals and the one terminal catch-all - and nothing else")
        void theAdviceDeclaresExactlyTheTwentyCoveredTypes() {
            // The inventory is asserted exhaustively rather than by sampling, so a handler cannot be
            // added or lost without this failing. It is twenty rather than fourteen because the
            // boundary answers six things beyond the request-shape faults: a credential failure raised
            // inside the dispatch, an entitlement refusal raised inside the dispatch, the three shapes a
            // version conflict arrives in when the persistence provider rather than module code detects
            // it, and anything the advice does not name. Each is grouped below with the reason it
            // belongs here.
            assertThat(declaredHandledTypes()).containsExactlyInAnyOrder(
                    // the six carriers this module raises for itself
                    AbendException.class,
                    FileStatusException.class,
                    RecordNotFoundException.class,
                    ValidationException.class,
                    OptimisticLockConflictException.class,
                    JobSubmissionException.class,
                    // the three shapes a provider-detected version conflict arrives in. Three are needed
                    // rather than one because none of them covers another and their nearest common
                    // ancestor is RuntimeException, which the invariant above forbids declaring. Spring's
                    // translated form is the superclass of the one a repository raises, so it covers
                    // both; the specification type escapes untranslated when a flush happens outside a
                    // repository call; and the provider's own type descends from PersistenceException
                    // rather than from the specification's optimistic type, so naming the other two does
                    // not reach it. The nested class below proves each of those three claims rather than
                    // asserting them, because a redundant declaration here would be invisible otherwise.
                    OptimisticLockingFailureException.class,
                    OptimisticLockException.class,
                    StaleStateException.class,
                    // the eight request-shape faults the framework raises before a service is reached
                    MethodArgumentNotValidException.class,
                    BindException.class,
                    HandlerMethodValidationException.class,
                    ConstraintViolationException.class,
                    MissingServletRequestParameterException.class,
                    MethodArgumentTypeMismatchException.class,
                    ServletRequestBindingException.class,
                    HttpMessageNotReadableException.class,
                    // the two security refusals raised inside the dispatch rather than by the filter chain
                    AuthenticationException.class,
                    AccessDeniedException.class,
                    // the terminal handler that closes the advice
                    Exception.class);
        }

        @Test
        @DisplayName("the two security refusals answer 401 and 403 with neutral literals, so a caller "
                + "cannot tell an unknown principal from an unentitled one by anything but the status")
        void theTwoSecurityRefusalsAnswerNeutrally() {
            ResponseEntity<ErrorResponse> unauthenticated = handler.handleAuthenticationFailure(
                    new org.springframework.security.authentication.BadCredentialsException(
                            SENSITIVE_PASSWORD));
            ResponseEntity<ErrorResponse> unentitled =
                    handler.handleAccessDenied(new AccessDeniedException("ROLE_ADMIN required"));

            assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(unentitled.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            ErrorResponse unauthenticatedBody = unauthenticated.getBody();
            ErrorResponse unentitledBody = unentitled.getBody();
            assertThat(unauthenticatedBody).isNotNull();
            assertThat(unentitledBody).isNotNull();
            assertNothingSensitiveEscaped(unauthenticatedBody);
            assertNothingSensitiveEscaped(unentitledBody);
            assertThat(unentitledBody.message())
                    .as("the refusing rule is never named to the caller")
                    .doesNotContain("ROLE_ADMIN");
            assertThat(unauthenticatedBody.fieldErrors())
                    .as("a credential refusal names no field, exactly as the sign-on screen names none")
                    .isEmpty();
            assertThat(unentitledBody.fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("every framework translation answers 400 and every one of them uses one of the three "
                + "neutral summaries, so the caller cannot distinguish which framework component refused")
        void everyFrameworkTranslationAnswersFourHundredWithOneOfThreeSummaries() {
            List<ResponseEntity<ErrorResponse>> responses = List.of(
                    handler.handleBindingValidation(
                            new BindException(bindingResultOver(new LinkedHashMap<>()))),
                    handler.handleHandlerMethodValidation(methodValidationFailure(
                            parameterRejection("X", new DefaultMessageSourceResolvable(
                                    new String[] {"Pattern"}, null, "must match")))),
                    handler.handleConstraintViolation(
                            new ConstraintViolationException(Set.of())),
                    handler.handleMissingRequestParameter(
                            new MissingServletRequestParameterException("endDate", "java.lang.String")),
                    handler.handleTypeMismatch(new MethodArgumentTypeMismatchException("x",
                            Integer.class, "page", sampleMethodParameter(),
                            new NumberFormatException("x"))),
                    handler.handleRequestBinding(new ServletRequestBindingException("bound")),
                    handler.handleUnreadableBody(new HttpMessageNotReadableException("parse",
                            new EmptyInputMessage())));

            assertThat(responses).allSatisfy(response -> {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                ErrorResponse body = response.getBody();
                assertThat(body).isNotNull();
                assertThat(body.message()).isIn(ORACLE_VALIDATION_FAILED, ORACLE_BINDING_FAILED,
                        ORACLE_MALFORMED_BODY);
            });
        }

        @Test
        @DisplayName("no response body carries a status number, a request path, a framework type name or a "
                + "stack frame, because the canonical body has no component that could hold one")
        void noResponseBodyCarriesFrameworkMetadata() {
            List<ResponseEntity<ErrorResponse>> responses = List.of(
                    handler.handleAbend(new AbendException("COACTUPC", "record was changed")),
                    handler.handleFileStatus(new FileStatusException("35", "OPEN", "ACCTFILE")),
                    handler.handleRecordNotFound(new RecordNotFoundException("CARD",
                            SENSITIVE_CARD_NUMBER)),
                    handler.handleValidation(new ValidationException(PROPERTY_ACCOUNT_STATUS,
                            BMS_ACCOUNT_STATUS, ValidationException.FieldState.MISSING,
                            "Account status must be supplied")),
                    handler.handleOptimisticLockConflict(new OptimisticLockConflictException(
                            OptimisticLockConflictException.ConflictKind
                                    .RECORD_CHANGED_BEFORE_UPDATE, "Account", "00000000011")),
                    handler.handleJobSubmission(new JobSubmissionException(
                            new IllegalStateException("transport failure"))),
                    handler.handleUnreadableBody(new HttpMessageNotReadableException("parse",
                            new EmptyInputMessage())));

            assertThat(responses).allSatisfy(response -> {
                ErrorResponse body = response.getBody();
                assertThat(body).isNotNull();
                assertNothingSensitiveEscaped(body);
                assertThat(body.message()).doesNotContain("400", "404", "409", "500", "/api/");
            });
        }
    }

    /**
     * Collects the failure types the advice declares handlers for.
     *
     * <p>This is the one assertion that has to read the advice's own declarations rather than its
     * behaviour, because the property under test - that no supertype broad enough to be a catch-all
     * is declared - is a statement about what is <em>absent</em>, and an absence cannot be observed
     * by invoking anything.</p>
     *
     * @return every type named by an {@code @ExceptionHandler} on the advice
     */
    private static List<Class<?>> declaredHandledTypes() {
        List<Class<?>> handled = new ArrayList<>();
        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            ExceptionHandler declaration = method.getAnnotation(ExceptionHandler.class);
            if (declaration != null) {
                handled.addAll(List.of(declaration.value()));
            }
        }
        return handled;
    }

    /**
     * Names the advice method that would win for a given failure type.
     *
     * <p>This emulates the container's most-specific-match resolution rather than trusting it, because
     * the property under test cannot be observed by invoking a handler directly. Every failure is
     * covered by <em>something</em> once a terminal {@code Exception} handler exists, so calling a
     * method proves only that the method's body is correct - not that the container would ever route to
     * it. Dropping a declared type is therefore invisible to a direct call: the type silently falls to
     * the terminal handler and the caller's status changes from a conflict to a server error. Resolving
     * the winner here makes that regression fail.
     *
     * @param carrier the failure type a client's request would produce
     * @return the name of the advice method the container would select
     */
    private static String resolvedHandlerNameFor(Class<?> carrier) {
        Map<Class<?>, String> candidates = new LinkedHashMap<>();
        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            ExceptionHandler declaration = method.getAnnotation(ExceptionHandler.class);
            if (declaration != null) {
                for (Class<?> declared : declaration.value()) {
                    if (declared.isAssignableFrom(carrier)) {
                        candidates.put(declared, method.getName());
                    }
                }
            }
        }
        assertThat(candidates)
                .as("every failure must be covered by at least the terminal handler")
                .isNotEmpty();
        // The most specific candidate is the one no other candidate is a strict subtype of.
        for (Map.Entry<Class<?>, String> candidate : candidates.entrySet()) {
            boolean anyNarrower = candidates.keySet().stream()
                    .anyMatch(other -> other != candidate.getKey()
                            && candidate.getKey().isAssignableFrom(other));
            if (!anyNarrower) {
                return candidate.getValue();
            }
        }
        throw new IllegalStateException("the declared types form a cycle, which is impossible");
    }

    /**
     * Collects a type and every superclass above it, nearest first.
     *
     * <p>Used to compute the nearest common ancestor of the three provider conflict types from the
     * lattice itself, so that the claim about how narrow the declarations can be does not silently
     * become false if a dependency reparents one of them.
     *
     * @param type the type to walk up from
     * @return the type followed by each superclass, ending at {@code Object}
     */
    private static List<Class<?>> superclassChainOf(Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            chain.add(current);
        }
        return chain;
    }

    /**
     * Normalizes an absent message to the empty string for comparison purposes.
     *
     * @param value the message to normalize, possibly {@code null}
     * @return the value itself when present, otherwise the empty string
     */
    private static String orEmptyText(String value) {
        return value == null ? EMPTY_TEXT : value;
    }
}
