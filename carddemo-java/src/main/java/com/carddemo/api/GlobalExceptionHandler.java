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

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.exception.JobSubmissionException;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single REST failure adapter for the migrated CardDemo API layer: it turns each of the six
 * failure carriers in {@code com.carddemo.exception} into a sanitized
 * {@link ErrorResponse} body and a stable HTTP status, and it routes every diagnostic that the
 * legacy programs wrote to the console into SLF4J instead of into the response.
 *
 * <h2>Legacy antecedent</h2>
 *
 * <p>The 3270 estate had no error object and no error status. Each online program declared its own
 * fixed-width screen message field - {@code COSGN00C} declares {@code WS-MESSAGE} as
 * {@code PIC X(80)} at {@code app/cbl/COSGN00C.cbl} line 38 - moved a literal into it, and
 * re-sent the map. Two of those literals were not per-program at all: the common message pair in
 * {@code app/cpy/CSMSG01Y.cpy} is included by all seventeen online programs and carries the
 * thank-you and invalid-key texts as two separate {@code PIC X(50)} space-padded values. That
 * copybook is the antecedent of this class, in the sense that it is where the estate first
 * centralized operator text; the two values themselves belong to the message catalogue service and
 * are deliberately neither redeclared, trimmed nor merged here, and neither of them is reachable
 * from any failure this class handles - they are the program-exit and unmapped-key outcomes, which
 * are ordinary screen results rather than failures.
 *
 * <h2>What each failure maps to</h2>
 *
 * <p>Exactly six handlers are declared, one per carrier, and there is deliberately no catch-all:
 *
 * <ul>
 *   <li>{@link AbendException} - {@code 500 Internal Server Error}. Terminal and unrecoverable:
 *       the estate's nine batch calls to the Language Environment abort routine and its four
 *       online abend commands all end the unit of work. The body carries only the operator message
 *       field, which the legacy online abend routine defaults to a fixed literal
 *       ({@code app/cbl/COACTUPC.cbl} lines 4205 to 4207).</li>
 *   <li>{@link FileStatusException} - {@code 500 Internal Server Error}. An unhandled file
 *       operation failure. The legacy sequence at all three I/O sites of
 *       {@code app/cbl/CBACT01C.cbl} - read at lines 110 to 113, open at 144 to 147, close at 162
 *       to 165 - is diagnostic first, raw two-byte status second, abend third, so the terminal
 *       outcome the operator sees is the abend text and the status stays on the diagnostic
 *       channel.</li>
 *   <li>{@link RecordNotFoundException} - {@code 404 Not Found}. A keyed read that resolved to no
 *       record, the condition the estate reports as file status {@code 23}.</li>
 *   <li>{@link ValidationException} - {@code 400 Bad Request}, with the per-field detail
 *       preserved.</li>
 *   <li>{@link OptimisticLockConflictException} - {@code 409 Conflict}. Recoverable: the legacy
 *       write path re-displays the record for review and never abends.</li>
 *   <li>{@link JobSubmissionException} - a <strong>non-failing</strong> {@code 200 OK}. See
 *       below.</li>
 * </ul>
 *
 * <h2>The job-submission rule is a contract, not a leniency</h2>
 *
 * <p>The estate has exactly one online-to-batch bridge, the queue write in
 * {@code app/cbl/CORPT00C.cbl} lines 515 to 535, and its destination is declared with
 * {@code ERROROPTION(IGNORE)} in {@code app/csd/CARDDEMO.CSD}. On a non-normal response the
 * program writes the response and reason codes to its diagnostic channel, moves the failure
 * literal into its screen message field, repositions the cursor and re-sends the screen. It does
 * not abend and it does not abort the request: control returns to the user normally. The owning
 * service is expected to catch that failure at the publish boundary and never let it reach this
 * class; if it does reach here, this class still refuses to convert a request the legacy completes
 * into an HTTP failure. It answers {@code 200 OK} with the frozen failure literal in the body, and
 * the response and reason codes go to the log rather than to the caller.
 *
 * <p>{@code 202 Accepted} is deliberately not used: nothing was accepted for processing. The write
 * failed, and because the legacy emitter tests the same error flag that the failure raises, the
 * cards after the failing one are never sent either. The response reports a completed request that
 * submitted no job, which is exactly what the legacy screen reported.
 *
 * <h2>Sanitization contract</h2>
 *
 * <p>No response produced here ever carries a stack trace, an exception class name, a raw SQL
 * fragment, a schema or table name, a secret, a credential, a password digest, a filesystem or
 * internal path, job-control text, screen control bytes, fixed-width record storage, or the raw
 * 134-byte abend context. Two carriers compose their detail message as a diagnostic rather than as
 * operator text - the file-status carrier renders the raw two-byte status together with the legacy
 * resource name, and the not-found carrier renders its own type name together with the searched
 * key - so neither message is ever copied into a body. The four remaining carriers expose an
 * operator-facing message by contract, and those are passed through unchanged, untrimmed and
 * unpadded, because the legacy fields they derive from are fixed-width and space-significant.
 *
 * <p>Two values are withheld from the <em>log</em> as well as from the body. A business key can be
 * a card primary account number, and the estate provides no field-level masking for one, so the
 * not-found and conflict handlers log the record type and the entity name but never the key. The
 * layer that performed the read has already logged its own diagnostic, including the raw status,
 * before the failure was raised.
 *
 * <h2>Diagnostic channel</h2>
 *
 * <p>The estate's only instrumentation was 217 console display statements. Those become SLF4J
 * events, and this class is where the last of them fire: the abend, file-status and
 * job-submission handlers reproduce the diagnostic the legacy emitted immediately before it
 * abended or re-sent the screen, using parameterized messages so the structured encoder can index
 * the values. The validation and not-found handlers log at debug level, because the legacy online
 * tier emitted no console output for either condition - it simply re-sent the map - and a
 * client-caused rejection is not an operational event. The conflict handler logs at warning level:
 * contention on the account-update path is an operational signal an operator needs, and surfacing
 * it is part of the observability the migration adds rather than a change to what the caller
 * sees.
 *
 * <h2>Scope, and what this class deliberately does not do</h2>
 *
 * <p>The advice is bound to the package of this class, so it applies to the module's own REST
 * controllers and to nothing else. It declares no request mapping, so it neither maps nor shadows
 * the management endpoints or the published interface description, and it cannot intervene in a
 * request handled by either. It configures no interface documentation, no problem-detail
 * representation and no content negotiation. It performs no forwarding: the estate's twenty-five
 * program-to-program transfers become route constants in a response body, never a server-side
 * dispatch, so a failure here ends the exchange rather than redirecting it.
 *
 * <p>It also holds no message catalogue of its own. Per-screen operator text belongs to the
 * services that own those screens, so where a carrier supplies no operator-facing message this
 * class emits one neutral, screen-independent summary rather than borrowing a literal from a
 * screen it knows nothing about.
 *
 * <h2>Thread safety</h2>
 *
 * <p>The class is {@code final}, holds no injected collaborator, and its only state is one static
 * logger and two static text constants, all immutable. A single instance therefore serves every
 * request concurrently, which is how the framework uses it.
 *
 * <h2>Provenance</h2>
 *
 * <p>Behaviour cited, never transcribed, from the CardDemo COBOL estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@RestControllerAdvice(basePackageClasses = GlobalExceptionHandler.class)
public final class GlobalExceptionHandler {

    /**
     * The diagnostic channel that replaces the console display statements of the legacy estate.
     *
     * <p>Static and final: the advice is a singleton with no injected collaborator, so a logger
     * held per instance would buy nothing. Nothing mutable is held anywhere in this class.
     */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * The neutral summary emitted when a keyed read resolved to no record.
     *
     * <p>This is deliberately <em>not</em> a legacy literal. The estate's not-found texts are
     * per-screen - the account, card, transaction and user screens each move their own wording
     * into their own message field - so reproducing any one of them here would attribute a
     * screen's text to a request that may have come from a different screen. The services own
     * those literals and normally answer with them directly; this advice is the boundary fallback
     * for a not-found that no service turned into its own response, and it says only what it can
     * verify.
     *
     * <p>It carries no record type, no key, no resource name and no wording that could identify
     * an internal component.
     */
    private static final String RECORD_NOT_FOUND_MESSAGE = "Record not found";

    /**
     * The value that stands in for an absent text component.
     *
     * <p>The response contract requires a field-error's field name and screen field identifier to
     * be present rather than {@code null}, while the failure carrier permits either to be absent -
     * it already normalizes an unscreened validation's screen field identifier to exactly this
     * value. Normalizing to it here keeps the translation total, so a malformed entry is still
     * reported to the client instead of failing this handler while it is already handling a
     * failure.
     */
    private static final String EMPTY = "";

    /**
     * Creates the advice.
     *
     * <p>Declared explicitly, and empty by design. The handler is stateless: it resolves every
     * value it needs from the failure it is given, so it injects nothing, and there is no field to
     * initialize. Should a collaborator ever become genuinely necessary it must arrive through
     * this constructor as {@code private final} state - never through field injection.
     */
    public GlobalExceptionHandler() {
        // Intentionally empty: this advice is stateless and has no collaborator to bind.
    }

    /**
     * Handles a terminal abend.
     *
     * <p>The estate's abend surface is nine batch calls to the Language Environment abort routine
     * plus four online abend commands, and every one of them ends the unit of work, so the status
     * is {@code 500 Internal Server Error} and no recovery is offered.
     *
     * <p>The body carries the operator message field alone. That field is the one piece of the
     * legacy 134-byte abend context intended for a person: it is bounded to 72 characters, it is
     * never blank because the carrier substitutes the legacy default literal when nothing was
     * supplied, and it is exactly what the online abend routine of
     * {@code app/cbl/COACTUPC.cbl} places in front of the operator at lines 4205 to 4207. The
     * remaining three pieces - the abend code, the failing component and the reason - are
     * diagnostics and go to the log, so the raw context is never rendered into a response and the
     * fixed-width image of it is never requested at all.
     *
     * @param exception the abend, never {@code null} when invoked by the framework
     * @return a {@code 500} response whose body holds the operator message and nothing else
     */
    @ExceptionHandler(AbendException.class)
    public ResponseEntity<ErrorResponse> handleAbend(AbendException exception) {
        LOG.error("Abend reached the REST boundary: abendCode={} culprit={} reason={}",
                exception.code(), exception.culprit(), exception.reason(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(exception.getMessage()));
    }

    /**
     * Handles an unhandled file operation failure.
     *
     * <p>Reaching this handler means no layer below absorbed the failure, which in the legacy
     * design is the path that ends in an abend: at each of the three I/O sites of
     * {@code app/cbl/CBACT01C.cbl} the program emits its diagnostic, emits the raw two-byte
     * status, and only then abends. The status is therefore {@code 500 Internal Server Error} and
     * the body carries the same terminal text an operator would have seen on the screen the abend
     * routine sent.
     *
     * <p>The raw two-byte status is neither reconstructed, reformatted nor exposed here. The layer
     * that performed the operation logged it before raising the failure, and this handler adds
     * only the boundary record, passing the failure itself to the logger so that the context
     * already composed by the carrier is preserved without this class re-deriving any part of it.
     *
     * @param exception the file operation failure, never {@code null} when invoked by the
     *                  framework
     * @return a {@code 500} response whose body holds the terminal operator text and no status
     *         detail
     */
    @ExceptionHandler(FileStatusException.class)
    public ResponseEntity<ErrorResponse> handleFileStatus(FileStatusException exception) {
        LOG.error("Unhandled file operation failure reached the REST boundary", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(AbendException.DEFAULT_MESSAGE));
    }

    /**
     * Handles a keyed read that resolved to no record.
     *
     * <p>The status is {@code 404 Not Found}. In the legacy design this condition is file status
     * {@code 23} on an indexed read, and it is frequently benign: the interest calculation folds it
     * in with success and retries against the default disclosure group, and the transaction posting
     * stage answers it by creating the category-balance row rather than by failing. Those callers
     * absorb it, so a not-found that arrives here is one no caller excused, and the correct answer
     * is that the addressed resource does not exist.
     *
     * <p>The carrier's own detail message renders its type name together with the searched key and
     * the legacy resource name, so it is never copied into the body; the body carries one neutral
     * summary instead. The log records the record type and the resource but deliberately omits the
     * key, which may be a card primary account number that the estate does not mask.
     *
     * @param exception the not-found condition, never {@code null} when invoked by the framework
     * @return a {@code 404} response whose body holds a neutral summary and no record identity
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRecordNotFound(RecordNotFoundException exception) {
        LOG.debug("Keyed read resolved to no record: recordType={} resource={}",
                exception.recordType(), exception.resourceName());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(RECORD_NOT_FOUND_MESSAGE));
    }

    /**
     * Handles a field-level validation failure, preserving the two legacy error states per field.
     *
     * <p>The status is {@code 400 Bad Request}: the submission failed its edits and the caller must
     * change it before resubmitting, which is what the legacy screen asked for by re-displaying the
     * map with the offending fields decorated.
     *
     * <p>This handler is the one place in the module where a validation failure becomes a response
     * body, and the per-field detail is carried across rather than collapsed. The parameterized
     * screen-decoration macro that the account-update program expands 39 times distinguishes two
     * operator mistakes - a field left blank, which it marks as well as highlights, and a field
     * filled in wrongly, which it only highlights - so a single boolean would tell a client that
     * something is wrong without telling it whether to ask for a value or for a correction. Both
     * states survive the translation, one entry per field, in the order the service assembled them:
     * no sequence is imposed, none is de-duplicated, and no entry is dropped.
     *
     * <p>The summary message is the carrier's own, which is owned by the service or message
     * catalogue that raised the failure and is therefore operator-facing by contract. The carrier
     * never holds a submitted field value, so a failure on a credential field cannot echo what was
     * typed, and the log records only how many fields failed - never their contents.
     *
     * @param exception the validation failure, never {@code null} when invoked by the framework
     * @return a {@code 400} response whose body holds the summary message, one entry per failed
     *         field with its state preserved, and a focus hint when the first failure names a
     *         legacy screen field
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException exception) {
        List<ErrorResponse.FieldError> fieldErrors = toResponseFieldErrors(exception.fieldErrors());
        LOG.debug("Submitted data failed validation: fieldErrorCount={}", fieldErrors.size());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(exception.getMessage(), fieldErrors,
                        focusScreenFieldId(fieldErrors)));
    }

    /**
     * Handles a concurrent-update conflict on the record-update path.
     *
     * <p>The status is {@code 409 Conflict} for all three legacy arms, and that uniformity is
     * deliberate. None of the three abends: on a before-image mismatch the program re-displays the
     * record for review, and the rewrite-failed and lock-failed arms report their own text and
     * return control, so answering with a server-error status would misreport a recoverable
     * condition as a crash. The three arms stay distinguishable through the body instead, because
     * the carrier's detail message is exactly one of the four verbatim operator literals the legacy
     * condition names carry - and a status code cannot encode four texts without inventing three
     * more codes the legacy never had.
     *
     * <p>The conflict arm and the entity name go to the log so contention can be attributed; the
     * business key does not, for the same reason it is withheld from the not-found log.
     *
     * @param exception the conflict, never {@code null} when invoked by the framework
     * @return a {@code 409} response whose body holds the verbatim legacy text for the arm that
     *         produced the conflict
     */
    @ExceptionHandler(OptimisticLockConflictException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockConflict(
            OptimisticLockConflictException exception) {
        LOG.warn("Concurrent update conflict: conflictKind={} entity={}",
                exception.conflictKind(), exception.entityName());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(exception.getMessage()));
    }

    /**
     * Handles a failed job-submission publish <em>without</em> failing the request.
     *
     * <p>The status is {@code 200 OK}. The legacy destination is declared with errors ignored, and
     * the program behaves accordingly: it records the response and reason codes on its diagnostic
     * channel, puts the failure literal on the screen, and returns control to the user. The request
     * completed on the mainframe, so it completes here, and converting it into a server error would
     * be a behavioural regression rather than a stricter contract.
     *
     * <p>The body carries the frozen failure literal exactly as the legacy program moves it into
     * its screen message field. The carrier's own detail message is not used, because it may append
     * the response and reason codes as diagnostic context and those belong on the log line - which
     * is where the legacy put them, and in the same order and under the same labels. The queue name
     * and the one-based ordinal of the card that failed are logged alongside them; the card image
     * itself never is, so no job-control text leaves this class in either direction.
     *
     * <p>The level is error rather than warning even though the response succeeds: the estate has
     * exactly one online-to-batch bridge, and a failed write means the requested job will not run
     * and the cards after the failing one were never sent. That is an operational failure worth
     * paging on, and it is precisely the fact the response is not allowed to convey.
     *
     * @param exception the publish failure, never {@code null} when invoked by the framework
     * @return a {@code 200} response whose body holds the frozen failure literal and no diagnostic
     *         code
     */
    @ExceptionHandler(JobSubmissionException.class)
    public ResponseEntity<ErrorResponse> handleJobSubmission(JobSubmissionException exception) {
        LOG.error("RESP:{} REAS:{} job submission card was not written to queue={}"
                        + " failedCardOrdinal={}; the request completes and no job was submitted",
                exception.responseCode(), exception.reasonCode(), exception.queueName(),
                exception.failedCardOrdinal(), exception);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ErrorResponse(JobSubmissionException.DEFAULT_MESSAGE));
    }

    /**
     * Translates the carrier's per-field detail into the response contract's per-field detail.
     *
     * <p>The two field-error types are structurally identical and semantically identical, and the
     * duplication is intentional: the response contract may not depend on the failure-carrier
     * package, so the translation has to happen at this layer. This method is that translation and
     * the only one in the module.
     *
     * <p>Two components are normalized because the carrier permits them to be absent while the
     * response contract requires them to be present. An absent field name becomes the empty string
     * rather than causing this handler to fail while it is already handling a failure, and an
     * absent screen field identifier - which the carrier already normalizes for a validation that
     * is not screen-bound - is carried through as the empty string. The per-field message is passed
     * through exactly as supplied, including {@code null} and including any padding, because the
     * legacy fields are space-significant. Order is preserved and nothing is filtered: the carrier
     * guarantees an unmodifiable, null-free list, so every entry it holds becomes exactly one
     * entry here.
     *
     * @param fieldErrors the carrier's per-field detail; never {@code null}, possibly empty
     * @return one response-contract entry per supplied entry, in the same order; empty when the
     *         failure carried no per-field detail
     */
    private static List<ErrorResponse.FieldError> toResponseFieldErrors(
            List<ValidationException.FieldError> fieldErrors) {
        List<ErrorResponse.FieldError> translated = new ArrayList<>(fieldErrors.size());
        for (ValidationException.FieldError fieldError : fieldErrors) {
            translated.add(new ErrorResponse.FieldError(
                    orEmpty(fieldError.field()),
                    orEmpty(fieldError.bmsFieldId()),
                    toResponseFieldState(fieldError.state()),
                    fieldError.message()));
        }
        return translated;
    }

    /**
     * Maps one field state across the layer boundary.
     *
     * <p>The mapping is one-to-one and total, and the switch is exhaustive over the carrier's two
     * constants with no default arm, so adding a third state to either enum stops the build here
     * instead of silently degrading a response. Arrow form is used, so no arm can fall through into
     * the next.
     *
     * <p>An absent state is mapped to the "supplied but wrong" state. The carrier does not reject a
     * {@code null} state while the response contract does, and this handler must not fail while
     * handling a failure, so a malformed entry is still reported to the client. That state is the
     * weaker of the two claims: it tells the operator to correct the value, whereas asserting a
     * missing value would tell them they left a field blank that they may well have filled in.
     *
     * @param state the carrier's state, possibly {@code null}
     * @return the corresponding response-contract state, never {@code null}
     */
    private static ErrorResponse.FieldState toResponseFieldState(
            ValidationException.FieldState state) {
        if (state == null) {
            return ErrorResponse.FieldState.INVALID;
        }
        return switch (state) {
            case MISSING -> ErrorResponse.FieldState.MISSING;
            case INVALID -> ErrorResponse.FieldState.INVALID;
        };
    }

    /**
     * Derives the focus hint from the first field that failed.
     *
     * <p>The legacy programs repositioned the cursor onto a field in error before re-sending the
     * map, and the response contract carries that intent as an opaque screen field label. The first
     * entry is the one used, because the services assemble their per-field detail in the order
     * their validation cascade runs and the cascade's first failure is the field the legacy cursor
     * landed on.
     *
     * <p>No coordinate, no attribute byte and no control character is involved: the value is the
     * same label the failure already carried. When there is no field detail, or when the first
     * entry names no screen field, no hint is given at all and the component is omitted from the
     * payload. An identifier that holds nothing but spaces counts as naming no field, because it is
     * not something a client can place focus on; that is a decision about the hint and not a
     * rewrite of the label, which is still carried untrimmed inside its own entry.
     *
     * @param fieldErrors the translated per-field detail; never {@code null}
     * @return the screen field label to place focus on, or {@code null} when the response offers no
     *         hint
     */
    private static String focusScreenFieldId(List<ErrorResponse.FieldError> fieldErrors) {
        if (fieldErrors.isEmpty()) {
            return null;
        }
        String screenFieldId = fieldErrors.getFirst().screenFieldId();
        return screenFieldId.isBlank() ? null : screenFieldId;
    }

    /**
     * Normalizes an absent text value to the empty string.
     *
     * <p>Present values are returned byte for byte. Nothing is trimmed, folded or truncated,
     * because the legacy screen and record fields these values derive from are fixed-width and
     * their padding is significant.
     *
     * @param value the value to normalize, possibly {@code null}
     * @return the value itself when present, otherwise the empty string
     */
    private static String orEmpty(String value) {
        return value == null ? EMPTY : value;
    }
}
