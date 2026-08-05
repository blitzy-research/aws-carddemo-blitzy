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
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.RefusalBodyRenderer;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.hibernate.StaleObjectStateException;
import org.hibernate.StaleStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The single REST failure adapter for the migrated CardDemo API layer: it turns each of the six
 * failure carriers in {@code com.carddemo.exception} into a sanitized {@link ErrorResponse} body
 * and a stable HTTP status, and it routes every diagnostic that the legacy programs wrote to the
 * console into SLF4J instead of into the response.
 *
 * <p>The 3270 estate had no error object and no error status. Each online program declared its own
 * fixed-width screen message field - {@code COSGN00C} declares {@code WS-MESSAGE} as
 * {@code PIC X(80)} at {@code app/cbl/COSGN00C.cbl} line 38 - moved a literal into it, and re-sent
 * the map. The common message pair in {@code app/cpy/CSMSG01Y.cpy}, included by all seventeen online
 * programs, is where the estate first centralized operator text and is this class's antecedent in
 * that sense; the two space-padded {@code PIC X(50)} values themselves belong to the message
 * catalogue and are neither redeclared, trimmed nor merged here, and neither is reachable from any
 * failure this class handles - they are the program-exit and unmapped-key outcomes, which are
 * ordinary screen results rather than failures.
 *
 * <p><strong>What each failure maps to.</strong> Exactly six handlers are declared, one per carrier,
 * and there is deliberately no catch-all:
 *
 * <ul>
 *   <li>{@link AbendException} - {@code 500 Internal Server Error}. Terminal and unrecoverable: the
 *       estate's nine batch calls to the Language Environment abort routine and its four online
 *       abend commands all end the unit of work. The body carries at most the operator message
 *       field, and only when the abend code is the online one: the legacy online routine sent that
 *       field to a terminal ({@code app/cbl/COACTUPC.cbl} lines 4203 to 4224), whereas the batch
 *       sites called the abort routine with a code and no message and wrote their diagnostic to the
 *       job log, so a batch abend answers with the legacy default literal instead and its detail is
 *       logged. See DL-084.</li>
 *   <li>{@link FileStatusException} - {@code 500 Internal Server Error}. An unhandled file operation
 *       failure. The legacy sequence at all three I/O sites of {@code app/cbl/CBACT01C.cbl} - read
 *       at lines 110 to 113, open at 144 to 147, close at 162 to 165 - is diagnostic first, raw
 *       two-byte status second, abend third, so the terminal outcome the operator sees is the abend
 *       text and the status stays on the diagnostic channel.</li>
 *   <li>{@link RecordNotFoundException} - {@code 404 Not Found}. A keyed read that resolved to no
 *       record, the condition the estate reports as file status {@code 23}.</li>
 *   <li>{@link ValidationException} - {@code 400 Bad Request}, with the per-field detail
 *       preserved.</li>
 *   <li>{@link OptimisticLockConflictException} - {@code 409 Conflict}. Recoverable: the legacy
 *       write path re-displays the record for review and never abends.</li>
 *   <li>{@link JobSubmissionException} - a <strong>non-failing</strong> {@code 200 OK}.</li>
 * </ul>
 *
 * <h2>Framework rejections answer in the same shape</h2>
 *
 * <p>Six carriers are not the whole failure surface of a REST controller. A request can also be
 * rejected before any service runs - a declarative constraint on a request body, a constraint on a
 * controller parameter, a missing query parameter, a value that will not convert to its declared
 * type, or a body the message converter cannot read at all. Those rejections are raised by the web
 * framework and by the validation provider, not by this module, and left unhandled they would be
 * rendered in the framework's own representation. That would give the API two error shapes for the
 * same class of outcome - a caller-caused rejection - and the second shape would carry transport
 * and framework metadata that {@link ErrorResponse} deliberately does not have.
 *
 * <p>Each of those rejections is therefore translated explicitly, one handler per named framework
 * type, into the same {@code 400 Bad Request} plus {@link ErrorResponse} that a
 * {@link ValidationException} produces. Three properties of the translation matter:
 *
 * <ul>
 *   <li><strong>The two legacy states survive.</strong> A framework field error carries the value
 *       that was rejected; that value is <em>inspected</em> to decide between the two states -
 *       absent or blank means the field was not supplied, anything else means it was supplied and
 *       failed - and is then discarded. It is never placed in a body and never written to a log,
 *       because a rejected value can be a password or a card primary account number.</li>
 *   <li><strong>Only the module's own text crosses the boundary.</strong> The summary is one of
 *       three neutral, screen-independent literals declared here, and a per-field message is the
 *       constraint message the module's own request contract declares. The framework exception's
 *       own detail message is never used: it embeds the rejected value, the declared parameter
 *       type or a fragment of the submitted body.</li>
 *   <li><strong>No rejection is answered by a generic fallback.</strong> Every rejection above is
 *       handled by a handler that names its concrete framework type, and this class deliberately
 *       does not inherit from the framework's own exception-handling base class, so no
 *       problem-detail representation is introduced and no rejection is flattened into a
 *       misleading {@code 400}.</li>
 * </ul>
 *
 * <h2>Authorization failures raised inside the dispatch, and the half that is not here</h2>
 *
 * <p>Two of the statuses the boundary must separate come from the security layer, and the layer
 * raises them in two different places. A request rejected <em>before</em> dispatch - an absent or
 * unusable credential, or a URL-level rule denying an anonymous caller - is rejected by the
 * security filter chain, which never enters the servlet dispatch and therefore cannot reach any
 * exception advice. Those are answered by the entry point and the denial handler that the filter
 * chain installs, and both belong to the security configuration rather than here.
 *
 * <p>What reaches this class is the other half: a failure raised <em>inside</em> the dispatch, after
 * a principal has been established, which is where a method-level authorization check fails.
 * {@link org.springframework.security.core.AuthenticationException} is answered with
 * {@code 401 Unauthorized} and
 * {@link org.springframework.security.access.AccessDeniedException} with {@code 403 Forbidden},
 * both in the same sanitized shape as everything else instead of a container default. Neither
 * handler duplicates the filter chain's work and neither replaces it: they are the inside-dispatch
 * arm of the same separation.
 *
 * <h2>The terminal handler preserves framework status semantics instead of flattening them</h2>
 *
 * <p>One handler is declared on {@link Exception}, and it exists for a single reason: a failure this
 * class did not anticipate must not be rendered by whatever default the servlet container happened
 * to install, because that default can carry a stack trace, an internal type name or a fragment of
 * the submitted body. It is the last resort and it can never shadow the handlers above, since the
 * framework resolves the most specific declared handler for the thrown type and reaches
 * {@link Exception} only when no closer match exists.
 *
 * <p>Answering everything it sees with {@code 500} would be wrong, because the framework's own
 * request faults - an unsupported method, an unacceptable or unsupported media type, an
 * unresolvable path - are client faults the framework already classifies correctly. So the handler
 * asks the failure what status it carries: {@link org.springframework.web.ErrorResponse} is the
 * interface every framework exception that knows its own status implements, so a failure
 * implementing it is answered with that status and a neutral body, and only a failure carrying no
 * status of its own becomes {@code 500}. The status is honoured; the framework's own detail never
 * is, because it can name a parameter, a media type, a target Java type or a path.
 *
 * <h2>The job-submission rule is a contract, not a leniency</h2>
 *
 * <p><strong>Sanitization contract.</strong> No response produced here ever carries a stack trace,
 * an exception class name, a raw SQL fragment, a schema or table name, a secret, a credential, a
 * password digest, a filesystem or internal path, job-control text, screen control bytes,
 * fixed-width record storage, or the raw 134-byte abend context. Two carriers compose their detail
 * message as a diagnostic rather than as operator text - the file-status carrier renders the raw
 * two-byte status together with the legacy resource name, and the not-found carrier renders its own
 * type name together with the searched key - so neither message is ever copied into a body. The four
 * remaining carriers expose an operator-facing message by contract, and those pass through
 * unchanged, untrimmed and unpadded, because the legacy fields they derive from are fixed-width and
 * space-significant. Two values are withheld from the <em>log</em> as well: a business key can be a
 * card primary account number and the estate provides no field-level masking for one (decision log
 * entry D-14), so the not-found and conflict handlers log the record type and the entity name but
 * never the key. The layer that performed the read has already logged its own diagnostic, including
 * the raw status, before the failure was raised.
 *
 * <p><strong>Diagnostic channel.</strong> The estate's only instrumentation was 217 console display
 * statements, and this class is where the last of them fire: the abend, file-status and
 * job-submission handlers reproduce the diagnostic the legacy emitted immediately before it abended
 * or re-sent the screen, using parameterized messages so the structured encoder can index the
 * values. The validation and not-found handlers log at debug level, because the legacy online tier
 * emitted no console output for either condition - it simply re-sent the map - and a client-caused
 * rejection is not an operational event. The conflict handler logs at warning level: contention on
 * the account-update path is an operational signal an operator needs, and surfacing it is part of
 * the observability the migration adds rather than a change to what the caller sees.
 *
 * <p><strong>Scope.</strong> The advice is bound to the package of this class, so it applies to the
 * module's own REST controllers and to nothing else, and it declares no request mapping, so it
 * neither maps nor shadows the management endpoints or the published interface description. It
 * configures no problem-detail representation and no content negotiation, and it performs no
 * forwarding: the estate's twenty-five program-to-program transfers become route constants in a
 * response body, so a failure here ends the exchange rather than redirecting it. It holds no message
 * catalogue of its own either - per-screen operator text belongs to the services that own those
 * screens, so where a carrier supplies no operator-facing message this class emits one neutral,
 * screen-independent summary rather than borrowing a literal from a screen it knows nothing about.
 *
 * <p>The class is {@code final}, holds no injected collaborator, and its only state is one static
 * logger and two static text constants, all immutable, so a single instance serves every request
 * concurrently, which is how the framework uses it.
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
     * Placeholder recorded on the diagnostic channel when a persistence-provider conflict carries no
     * entity name this boundary can read. Never reaches a response body.
     */
    private static final String UNNAMED_CONFLICTING_ENTITY = "UnnamedEntity";

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
     * The neutral summary emitted when the framework or the validation provider rejected the
     * request before any service ran.
     *
     * <p>Like {@link #RECORD_NOT_FOUND_MESSAGE} this is deliberately not a legacy literal. The
     * estate's validation texts are per-field and per-screen, and the service that owns a screen
     * emits its own summary when it runs its own cascade; a rejection that never reached a service
     * has no screen to borrow wording from. The per-field entries carry the specific reasons, so
     * this line only has to say what class of failure occurred.
     */
    private static final String VALIDATION_FAILED_MESSAGE = "Submitted data failed validation";

    /**
     * The neutral summary emitted when the request body could not be read at all.
     *
     * <p>No field detail accompanies it, because a body that could not be parsed has no fields to
     * attribute an error to. The parse diagnostic is not surfaced: it quotes the offending fragment
     * of the submitted payload, which may hold a credential.
     */
    private static final String MALFORMED_REQUEST_BODY_MESSAGE = "Request body could not be read";

    /**
     * The neutral summary emitted when a request value could not be bound to the operation - a
     * missing parameter, header, path variable or cookie, or a value that will not convert to its
     * declared type.
     *
     * <p>It names no parameter, no header and no type. Where the failure identifies the request
     * value it applies to, that name travels as a per-field entry instead, which is where a client
     * looks for it.
     */
    private static final String REQUEST_BINDING_FAILED_MESSAGE = "Request value could not be bound";

    /**
     * The neutral summary emitted when the request names an operation that exists at a path that
     * does not, or a path that exists for a method that does not accept it.
     *
     * <p>It names neither the method attempted nor the methods allowed. The allowed set travels in
     * the {@code Allow} header the framework already populates, which is where a client reads it, and
     * repeating it in the body would turn an error summary into an inventory of the routing table.
     */
    private static final String METHOD_NOT_SUPPORTED_MESSAGE = "Request method is not supported";

    /**
     * The neutral summary emitted when the request reached no operation at all.
     *
     * <p>Kept distinct from {@link #RECORD_NOT_FOUND_MESSAGE}, which is a verbatim legacy text
     * meaning a keyed read found no row. A request that matched no route never reached a read, and
     * borrowing the record text for it would report a data outcome for a routing outcome.
     */
    private static final String ROUTE_NOT_FOUND_MESSAGE = "Requested resource was not found";

    /**
     * The neutral summary emitted when no representation the caller declared it would accept can be
     * produced.
     */
    private static final String REPRESENTATION_NOT_AVAILABLE_MESSAGE =
            "Requested representation is not available";

    /**
     * The neutral summary emitted when the request carries a media type the operation cannot read.
     *
     * <p>Distinct from {@link #MALFORMED_REQUEST_BODY_MESSAGE}: a body in an unsupported media type
     * was never parsed, whereas a malformed body was parsed and rejected, and reporting the second
     * for the first sends the caller looking for a payload defect that is not there.
     */
    private static final String MEDIA_TYPE_NOT_SUPPORTED_MESSAGE =
            "Request media type is not supported";

    /**
     * The neutral summary emitted when the framework rejected the request before any handler of this
     * class could classify it more precisely, and the status alone is what is known.
     *
     * <p>It is deliberately unspecific rather than falsely specific. The alternative this replaces -
     * reporting every framework-declared fault as an unreadable body - preserved the status while
     * making the diagnostic untrue for method, media-type and acceptability faults.
     */
    private static final String REQUEST_REJECTED_MESSAGE = "Request could not be processed";

    /**
     * The summary returned when a credential was required and none was established. Deliberately
     * free of detail: naming which credential was missing, or whether a principal existed at all,
     * tells a prober more than it tells a caller.
     *
     * <p><strong>Shared with the security filter chain, and public for that reason alone.</strong> An
     * authentication refusal can be reached two ways: raised inside the dispatch, where the handler
     * below answers it, or raised in the filter chain <em>before</em> the dispatch, where no
     * exception handler in this class is ever consulted. Those two paths must not answer the same
     * condition with two different bodies, so both read one literal. Its home is the neutral refusal
     * contract in the base layer, which is the one package both this boundary and the security chain are
     * permitted to depend on; this constant is that literal, republished here because the handler below
     * and its tests are written in terms of it. One literal with one home is the only form of that
     * agreement a reader can check by inspection. The chain that refuses before the dispatch is reasoned
     * in {@code docs/decision-log.md} DL-096.</p>
     */
    public static final String AUTHENTICATION_REQUIRED_MESSAGE =
            RefusalBodyRenderer.AUTHENTICATION_REQUIRED_MESSAGE;

    /**
     * The summary returned when an established principal was refused. It names neither the rule
     * that refused nor the role that would have satisfied it, for the same reason.
     *
     * <p>Shared with the security filter chain's access-denied handler, and public for the same
     * reason as the constant above: an authorization refusal decided by a filter-chain rule and one
     * decided inside the dispatch are the same condition and must read identically.</p>
     */
    public static final String ACCESS_DENIED_MESSAGE = RefusalBodyRenderer.ACCESS_DENIED_MESSAGE;

    /**
     * The bean-validation constraints that assert presence rather than shape. A field rejected by
     * one of these was not supplied, which is the legacy BLANK state, and the estate distinguishes
     * that from a supplied value that failed its edits - the decoration macro in
     * {@code app/cpy/CSSETATY.cpy} writes its {@code '*'} marker only for the blank case. The
     * constraint name is consulted in addition to the rejected value because a composed constraint
     * or a custom message can reject a present-but-unusable value that is still, semantically, an
     * absence.
     */
    private static final Set<String> PRESENCE_CONSTRAINT_NAMES =
            Set.of("NotNull", "NotBlank", "NotEmpty");

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
     * <p>The body carries at most the operator message field. That field is the one piece of the
     * legacy 134-byte abend context intended for a person: it is bounded to 72 characters, it is
     * never blank because the carrier substitutes the legacy default literal when nothing was
     * supplied, and it is exactly what the online abend routine of
     * {@code app/cbl/COACTUPC.cbl} places in front of the operator at lines 4205 to 4207. The
     * remaining three pieces - the abend code, the failing component and the reason - are
     * diagnostics and go to the log, so the raw context is never rendered into a response and the
     * fixed-width image of it is never requested at all.
     *
     * <p>Whether even that field crosses the boundary depends on the abend code, because the legacy
     * had two abend channels and only one of them faced a person. The online routine at
     * {@code app/cbl/COACTUPC.cbl} lines 4203 to 4224 sends the abend area to the terminal with
     * {@code EXEC CICS SEND} and then abends under code {@code 9999}, so text carried under that
     * code was written to be read by the operator. The batch routine reached it differently: the
     * nine {@code CALL 'CEE3ABD'} sites - among them {@code app/cbl/CBACT01C.cbl} line 173 - pass
     * the abort routine a code and no message at all, and the diagnostic that preceded them went to
     * {@code DISPLAY}, which is the job log. {@code app/cbl/CBACT01C.cbl} lines 110 to 113 are the
     * pattern: the program displays which file failed, moves the raw file status into the I/O status
     * field, displays that too, and only then abends. None of it ever reached a terminal.
     *
     * <p>{@code AbstractCobolStep} reproduces that batch diagnostic faithfully, composing a message
     * that names the operation, the resource and the raw file status. Rendering it into an HTTP body
     * would hand an end user the internal resource name and status code that the legacy gave only to
     * the job log, so this handler withholds it and answers with the legacy's own default literal -
     * the same text {@code ABEND-ROUTINE} substitutes when no message was set. The withheld detail
     * is logged in full, and the classification is fail-closed: only the online abend code carries
     * its text outward, so an abend arriving under any other code is treated as internal rather than
     * assumed safe. Recorded as DL-084.
     *
     * <p>The abend's own message is logged because this module composed it: it is the reproduced legacy
     * diagnostic, naming the operation, the resource and the raw file status and nothing a caller
     * supplied. What is <em>not</em> logged is the abend object, whose cause chain carries messages
     * this module did not author; the chain travels as sanitised type names instead. See
     * {@link FailureDiagnostics}.
     *
     * @param exception the abend, never {@code null} when invoked by the framework
     * @return a {@code 500} response whose body holds the operator-facing text and nothing else
     */
    @ExceptionHandler(AbendException.class)
    public ResponseEntity<ErrorResponse> handleAbend(AbendException exception) {
        LOG.error("Abend reached the REST boundary: abendCode={} reason={} failureChain={}",
                exception.code(), exception.reason(),
                FailureDiagnostics.failureChainOf(exception));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(operatorTextOf(exception)));
    }

    /**
     * Selects the abend text that may cross the boundary.
     *
     * <p>An abend raised under the online code carries text that the legacy sent to a terminal, so
     * it is returned as supplied. Every other code - the batch code among them, and any code the
     * estate does not use - is treated as an internal diagnostic and answers with the legacy default
     * literal instead. The direction of the test matters: an allow-list of the one operator-facing
     * code fails closed, whereas a deny-list of the batch code would let an unrecognised code
     * publish whatever text it happened to carry.
     *
     * @param exception the abend being translated
     * @return the text to publish, never {@code null}
     */
    private static String operatorTextOf(AbendException exception) {
        if (AbendException.ONLINE_ABEND_CODE.equals(exception.code())) {
            return exception.getMessage();
        }
        return AbendException.DEFAULT_MESSAGE;
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
     * that performed the operation logged it before raising the failure, and this handler adds only
     * the boundary record.
     *
     * <p><strong>The failure object itself is not handed to the logger.</strong> An earlier revision
     * passed it, on the reasoning that the context already composed by the carrier should be preserved
     * rather than re-derived. It is preserved - the carrier already logged it, at the site that holds
     * the raw status - and passing the object here would add nothing to that record while publishing
     * the one part of it this module did not author: the message of the failure and of every cause
     * beneath it. Beneath a file-operation failure is a data-access failure and beneath that a driver
     * failure, whose message carries the connection string it could not open and the values it
     * rejected. What this record adds instead is the sanitised failure chain, which is the part that
     * says where to look, composed entirely of type names. See {@link FailureDiagnostics}.
     *
     * @param exception the file operation failure, never {@code null} when invoked by the
     *                  framework
     * @return a {@code 500} response whose body holds the terminal operator text and no status
     *         detail
     */
    @ExceptionHandler(FileStatusException.class)
    public ResponseEntity<ErrorResponse> handleFileStatus(FileStatusException exception) {
        LOG.error("Unhandled file operation failure reached the REST boundary: failureChain={}"
                        + " rootFailureType={}",
                FailureDiagnostics.failureChainOf(exception),
                FailureDiagnostics.rootFailureTypeOf(exception));
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
     * <p>The carrier's own detail message names its type and the legacy resource and carries a fixed
     * placeholder where the searched key would be, so the key cannot reach a log or a report through
     * it; even so the message is never copied into the body, which carries one neutral summary
     * instead. The log here records the record type and the resource only. Neither path renders the
     * key, which may be a card primary account number that the estate does not mask - the carrier's
     * {@code key()} accessor is the single deliberate way to reach it.
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
     * <p>The body is rendered from the typed arm and the entity name rather than from
     * {@link Throwable#getMessage()}. The two are provably the same bytes, because
     * {@code OptimisticLockConflictException} accepts no detail message other than the one its arm
     * resolves to, so rendering from the arm is not a second source of truth - it is the only one,
     * read directly. What it removes is the possibility of a caller-composed message becoming the
     * discriminator this boundary publishes while the arm says something else. Recorded as
     * DL-083.</p>
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
                .body(new ErrorResponse(
                        exception.conflictKind().defaultMessage(exception.entityName())));
    }

    /**
     * Handles an optimistic-lock failure raised by the persistence framework rather than by the
     * module's own write paths, and answers it with the same {@code 409} the module's own conflict
     * produces.
     *
     * <p><strong>Why this arm has to exist separately.</strong> The module raises
     * {@link OptimisticLockConflictException} where it detects a conflict itself, and the arm above
     * answers that. But the {@code @Version} attribute on the account and card entities is enforced by
     * the persistence provider at flush time, not by module code, and the provider raises its own type.
     * Without this arm that type reached the terminal handler and became a {@code 500} carrying the
     * abend literal &mdash; which tells a client the server broke when in fact the client's screen was
     * simply stale. The status was wrong, the text was wrong, and the condition it described is one the
     * legacy system had a specific message for.
     *
     * <p><strong>Why every one of these types maps to the same arm.</strong> A version mismatch at flush
     * is precisely the condition the legacy write paths detected by re-reading the record and comparing
     * it against the image they had presented &mdash; the record changed between the read and the write.
     * The estate answers that with one text, so the mapping is to
     * {@link OptimisticLockConflictException.ConflictKind#RECORD_CHANGED_BEFORE_UPDATE}, whose message
     * does not vary by entity. The entity is therefore recorded on the diagnostic channel rather than
     * placed in the body, so no provider type name and no persistent class name reaches a client.
     *
     * <p><strong>The three type families this catches, and why three are needed.</strong>
     * {@link OptimisticLockingFailureException} is Spring's translated form and the superclass of
     * {@link ObjectOptimisticLockingFailureException}, which a Spring Data repository raises, so naming
     * the superclass covers both. {@link OptimisticLockException} is the specification type, which
     * escapes untranslated when a flush happens outside a repository call. {@link StaleStateException}
     * is the provider's own, and it is the superclass of {@link StaleObjectStateException}; it descends
     * from {@code PersistenceException} rather than from the specification's optimistic type, so it is
     * genuinely not covered by the second and has to be named.
     *
     * <p>Pessimistic lock-acquisition failures are deliberately <em>not</em> folded in here. They are a
     * different condition with a different legacy message, and no write path in this module currently
     * produces one; mapping them speculatively would put text on a response for a state the module
     * cannot reach.
     *
     * @param exception the provider's conflict, never {@code null} when invoked by the framework
     * @return a {@code 409} response carrying the verbatim legacy record-changed text
     */
    @ExceptionHandler({
        OptimisticLockingFailureException.class,
        OptimisticLockException.class,
        StaleStateException.class})
    public ResponseEntity<ErrorResponse> handleProviderOptimisticLockFailure(Exception exception) {
        LOG.warn("Concurrent update conflict raised by the persistence provider: entity={} "
                        + "failureChain={}",
                conflictingEntityNameOf(exception), FailureDiagnostics.failureChainOf(exception));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(OptimisticLockConflictException.ConflictKind
                        .RECORD_CHANGED_BEFORE_UPDATE.defaultMessage()));
    }

    /**
     * Names the entity a provider conflict concerns, for the diagnostic channel only.
     *
     * <p>Each of the three type families carries the entity differently, and none of them carries it in
     * a way the others can read, so each is asked in its own terms. Nothing here is reflective: every
     * accessor named is declared on the type being asked. An unknown shape yields the placeholder rather
     * than a guess, because a wrong entity name in a log is worse than an absent one.
     *
     * @param exception the provider's conflict
     * @return the entity name, or a placeholder when the type carries none
     */
    private static String conflictingEntityNameOf(Exception exception) {
        if (exception instanceof ObjectOptimisticLockingFailureException objectLevel
                && objectLevel.getPersistentClassName() != null) {
            return objectLevel.getPersistentClassName();
        }
        if (exception instanceof StaleObjectStateException staleObject
                && staleObject.getEntityName() != null) {
            return staleObject.getEntityName();
        }
        if (exception instanceof OptimisticLockException specificationType
                && specificationType.getEntity() != null) {
            return specificationType.getEntity().getClass().getName();
        }
        return UNNAMED_CONFLICTING_ENTITY;
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
                        + " failedCardOrdinal={} failureChain={}; the request completes and no job was"
                        + " submitted",
                exception.responseCode(), exception.reasonCode(), exception.queueName(),
                exception.failedCardOrdinal(), FailureDiagnostics.failureChainOf(exception));
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ErrorResponse(JobSubmissionException.DEFAULT_MESSAGE));
    }

    /**
     * Handles a declarative validation or binding failure on a request body or model attribute.
     *
     * <p>Both named types are translated here because both carry the same evidence - a binding
     * result holding one entry per rejected field plus any class-level entries - and translating
     * them separately would duplicate that translation without changing a single response. The
     * subtype is named explicitly alongside its supertype so that the handled surface is visible
     * at this declaration rather than inferred from a class hierarchy.
     *
     * <p>The status is {@code 400 Bad Request}, the same status a service-raised
     * {@link ValidationException} produces, because the outcome is the same: the caller must change
     * the submission. Per-field entries preserve the two legacy states, derived from whether the
     * rejected value was absent or blank; the rejected value itself is discarded rather than
     * reported. A class-level entry has no field to name, so it travels with an empty field name
     * and its own message.
     *
     * <p>The log records the number of rejected fields and nothing else - no field name, no
     * message, no rejected value and no throwable - because the failure is caller-caused rather
     * than operational and because a rejected value may be a credential.
     *
     * @param exception the binding or validation failure, never {@code null} when invoked by the
     *                  framework
     * @return a {@code 400} response whose body holds the neutral summary and one entry per
     *         rejected field
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ErrorResponse> handleBindingValidation(BindException exception) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        for (org.springframework.validation.FieldError fieldError : exception.getFieldErrors()) {
            fieldErrors.add(new ErrorResponse.FieldError(
                    orEmpty(fieldError.getField()),
                    EMPTY,
                    fieldStateFor(fieldError.getCode(), fieldError.getRejectedValue()),
                    fieldError.getDefaultMessage()));
        }
        for (ObjectError objectError : exception.getGlobalErrors()) {
            fieldErrors.add(new ErrorResponse.FieldError(
                    EMPTY, EMPTY, ErrorResponse.FieldState.INVALID, objectError.getDefaultMessage()));
        }
        LOG.debug("Request failed declarative validation: fieldErrorCount={}", fieldErrors.size());
        return badRequest(VALIDATION_FAILED_MESSAGE, fieldErrors);
    }

    /**
     * Handles a constraint failure on a controller method parameter or return value.
     *
     * <p>This is the failure the framework raises when a constraint is declared directly on a
     * handler parameter rather than on a request body, so the evidence arrives per parameter rather
     * than as one binding result. Each parameter contributes its resolvable errors; an error that
     * identifies a property of a validated object names that property, and an error on the
     * parameter itself is named by the parameter, which is available because the module compiles
     * with parameter names retained.
     *
     * <p>The two legacy states are derived exactly as they are for a body failure: from the
     * rejected property value where one is reported, and otherwise from the argument the parameter
     * received. Neither is placed in the response or the log.
     *
     * @param exception the method-validation failure, never {@code null} when invoked by the
     *                  framework
     * @return a {@code 400} response whose body holds the neutral summary and one entry per
     *         rejected parameter or property
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(
            HandlerMethodValidationException exception) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            String parameterName = orEmpty(result.getMethodParameter().getParameterName());
            for (MessageSourceResolvable resolvable : result.getResolvableErrors()) {
                if (resolvable instanceof org.springframework.validation.FieldError fieldError) {
                    fieldErrors.add(new ErrorResponse.FieldError(
                            orEmpty(fieldError.getField()),
                            EMPTY,
                            stateOfRejectedValue(fieldError.getRejectedValue()),
                            fieldError.getDefaultMessage()));
                } else {
                    fieldErrors.add(new ErrorResponse.FieldError(
                            parameterName,
                            EMPTY,
                            stateOfRejectedValue(result.getArgument()),
                            resolvable.getDefaultMessage()));
                }
            }
        }
        LOG.debug("Handler parameters failed validation: fieldErrorCount={}", fieldErrors.size());
        return badRequest(VALIDATION_FAILED_MESSAGE, fieldErrors);
    }

    /**
     * Handles a constraint failure raised by the validation provider itself.
     *
     * <p>This arrives when validation is triggered programmatically or on a bean method rather than
     * by the web layer, so the evidence is a set of violations rather than an ordered result. A set
     * has no order, and a response whose entries reorder between two identical requests is not a
     * contract a client can test against, so the entries are sorted by field name and then by
     * message. That ordering is imposed by this handler alone; the source-ordered cascade that the
     * legacy screens ran belongs to the services and reaches this class as a
     * {@link ValidationException}, whose order is preserved untouched.
     *
     * <p>A violation names its property by path; the leaf of that path is the field a client sent,
     * and the interior nodes - which include the invoked method name - are internal detail and are
     * dropped. The invalid value is inspected for the two-state decision and discarded.
     *
     * @param exception the constraint failure, never {@code null} when invoked by the framework
     * @return a {@code 400} response whose body holds the neutral summary and one entry per
     *         violation, in a stable order
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        Set<ConstraintViolation<?>> violations = exception.getConstraintViolations();
        if (violations != null) {
            for (ConstraintViolation<?> violation : violations) {
                fieldErrors.add(new ErrorResponse.FieldError(
                        leafPropertyName(violation.getPropertyPath()),
                        EMPTY,
                        fieldStateFor(violation.getMessageTemplate(), violation.getInvalidValue()),
                        violation.getMessage()));
            }
            fieldErrors.sort(Comparator.comparing(ErrorResponse.FieldError::fieldName)
                    .thenComparing(fieldError -> orEmpty(fieldError.message())));
        }
        LOG.debug("Constraints were violated: fieldErrorCount={}", fieldErrors.size());
        return badRequest(VALIDATION_FAILED_MESSAGE, fieldErrors);
    }

    /**
     * Handles a required request parameter that was not supplied.
     *
     * <p>The parameter name is part of the module's own published interface, so it is safe to
     * return, and it is the one piece of information a client needs. The state is MISSING, which is
     * the legacy blank case: the value was never supplied, so the remedy is to supply one. Nothing
     * else from the failure is used - its detail message additionally names the declared Java
     * parameter type, which is internal.
     *
     * @param exception the missing-parameter failure, never {@code null} when invoked by the
     *                  framework
     * @return a {@code 400} response naming the missing parameter and nothing further
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestParameter(
            MissingServletRequestParameterException exception) {
        LOG.debug("Required request parameter was not supplied: parameter={}",
                exception.getParameterName());
        return badRequest(REQUEST_BINDING_FAILED_MESSAGE, List.of(new ErrorResponse.FieldError(
                orEmpty(exception.getParameterName()), EMPTY, ErrorResponse.FieldState.MISSING,
                REQUEST_BINDING_FAILED_MESSAGE)));
    }

    /**
     * Handles a request value that was supplied but will not convert to its declared type.
     *
     * <p>The state is INVALID, which is the legacy not-OK case: a value was supplied and failed its
     * edit, so the remedy is to correct it. The parameter name is returned because it is part of
     * the published interface; the value that failed to convert and the type it failed to convert
     * to are both withheld, the first because it is caller data and the second because it is
     * internal.
     *
     * @param exception the conversion failure, never {@code null} when invoked by the framework
     * @return a {@code 400} response naming the parameter that could not be converted
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        LOG.debug("Request value could not be converted: parameter={}", exception.getName());
        return badRequest(REQUEST_BINDING_FAILED_MESSAGE, List.of(new ErrorResponse.FieldError(
                orEmpty(exception.getName()), EMPTY, ErrorResponse.FieldState.INVALID,
                REQUEST_BINDING_FAILED_MESSAGE)));
    }

    /**
     * Handles the remaining request-binding failures - a missing header, path variable or cookie,
     * and an unsatisfied parameter condition.
     *
     * <p>These are declared by the framework as one family, and the family is named here rather
     * than enumerated member by member so that no member of it can escape into a second response
     * shape. It is not a catch-all: it handles request binding and nothing else, and the two
     * members with a value a client can act on - a missing parameter and a failed conversion - are
     * handled above by their own more specific declarations, which the framework prefers.
     *
     * <p>No per-field entry is produced, because the members reached here identify the request
     * value only inside a detail message that also carries framework and type detail.
     *
     * @param exception the binding failure, never {@code null} when invoked by the framework
     * @return a {@code 400} response whose body holds the neutral summary and no field detail
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ErrorResponse> handleRequestBinding(
            ServletRequestBindingException exception) {
        LOG.debug("Request value could not be bound to the operation");
        return badRequest(REQUEST_BINDING_FAILED_MESSAGE, List.of());
    }

    /**
     * Handles a request body the message converter could not read.
     *
     * <p>A body that did not parse has no fields to attribute an error to, so the response carries
     * the neutral summary alone. The parse diagnostic is used neither in the body nor in the log: it
     * quotes the offending fragment of the payload, and a sign-on body's payload holds a password.
     *
     * @param exception the unreadable-body failure, never {@code null} when invoked by the
     *                  framework
     * @return a {@code 400} response whose body holds the neutral summary and no field detail
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException exception) {
        LOG.debug("Request body could not be read by the configured message converter");
        return badRequest(MALFORMED_REQUEST_BODY_MESSAGE, List.of());
    }

    /**
     * Answers a credential failure raised inside the dispatch with {@code 401 Unauthorized}.
     *
     * <p>This is the inside-dispatch arm of the separation described on the class: the filter chain
     * answers what it rejects before dispatch, and this answers what a method-level check rejects
     * afterwards. The body carries the neutral literal only. The failure itself is logged at
     * warning level with its cause, because an operator needs it and a caller must not have it.
     *
     * @param exception the authentication failure, never {@code null} when invoked by the framework
     * @return a {@code 401} response whose body names no credential, principal or rule
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationFailure(
            AuthenticationException exception) {
        LOG.warn("Authentication was required and not established at the REST boundary:"
                + " failureChain={}", FailureDiagnostics.failureChainOf(exception));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(AUTHENTICATION_REQUIRED_MESSAGE));
    }

    /**
     * Answers an authorization refusal raised inside the dispatch with {@code 403 Forbidden}.
     *
     * <p>The distinction from {@code 401} is the one the estate drew: a principal exists and is not
     * entitled, which is the sign-on program routing a non-administrator away from the
     * administrative menu rather than refusing the sign-on itself. The body names neither the rule
     * that refused nor the role that would have satisfied it.
     *
     * @param exception the access denial, never {@code null} when invoked by the framework
     * @return a {@code 403} response whose body names no rule or role
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        LOG.warn("An established principal was refused at the REST boundary: failureChain={}",
                FailureDiagnostics.failureChainOf(exception));
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(ACCESS_DENIED_MESSAGE));
    }

    /**
     * The terminal handler: answers anything unanticipated without disclosing how it failed.
     *
     * <p>A failure that classifies itself - every framework request fault implements
     * {@link org.springframework.web.ErrorResponse} - is answered with the status it declares and a
     * neutral body, so a client fault is never reported as a server fault. Anything else becomes
     * {@code 500 Internal Server Error} carrying {@link AbendException#DEFAULT_MESSAGE}, the same
     * frozen terminal literal the online abend path uses, so the boundary has exactly one terminal
     * text rather than one per cause. The cause is logged, never returned.
     *
     * <p>The neutral body is chosen from the declared status by {@link #neutralSummaryFor}, not
     * fixed. Returning the unreadable-body summary for every framework fault - which is what this
     * previously did - preserved the status and falsified the diagnostic, telling a caller who used
     * the wrong method, the wrong media type or an unsatisfiable {@code Accept} header to go looking
     * for a defect in a payload that was never the problem. Recorded as DL-085.
     *
     * @param exception the unanticipated failure, never {@code null} when invoked by the framework
     * @return the framework's own status with a neutral body accurate for that status, or
     *         {@code 500} with the terminal literal
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedFailure(Exception exception) {
        if (exception instanceof org.springframework.web.ErrorResponse declaredStatusFailure) {
            HttpStatusCode status = declaredStatusFailure.getStatusCode();
            LOG.debug("Framework request fault reached the REST boundary: status={} failureChain={}",
                    status.value(), FailureDiagnostics.failureChainOf(exception));
            return ResponseEntity.status(status)
                    .body(new ErrorResponse(neutralSummaryFor(status)));
        }
        LOG.error("Unhandled failure reached the REST boundary: failureChain={} rootFailureType={}",
                FailureDiagnostics.failureChainOf(exception),
                FailureDiagnostics.rootFailureTypeOf(exception));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(AbendException.DEFAULT_MESSAGE));
    }

    /**
     * Chooses the neutral summary for a framework fault from the status it declared.
     *
     * <p>Only the statuses this boundary can actually reach through the terminal handler are named.
     * A caller-caused status the framework declares but this class does not translate explicitly
     * falls to {@link #REQUEST_REJECTED_MESSAGE}, which is unspecific rather than wrong; a
     * server-side status falls to {@link AbendException#DEFAULT_MESSAGE}, so a framework fault that
     * is genuinely ours reads the same as any other terminal failure instead of implying the caller
     * did something.
     *
     * <p>The comparison is by numeric value against {@link HttpStatus} constants rather than a switch
     * over the enum, because {@link HttpStatusCode} is an interface: a framework fault may declare a
     * status that resolves to no enum constant at all, and a numeric comparison handles that without
     * a nullable intermediate.
     *
     * @param status the status the framework fault declared
     * @return the summary to publish, never {@code null}
     */
    private static String neutralSummaryFor(HttpStatusCode status) {
        if (!status.is4xxClientError()) {
            return AbendException.DEFAULT_MESSAGE;
        }
        int value = status.value();
        if (value == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return METHOD_NOT_SUPPORTED_MESSAGE;
        }
        if (value == HttpStatus.NOT_ACCEPTABLE.value()) {
            return REPRESENTATION_NOT_AVAILABLE_MESSAGE;
        }
        if (value == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) {
            return MEDIA_TYPE_NOT_SUPPORTED_MESSAGE;
        }
        if (value == HttpStatus.NOT_FOUND.value()) {
            return ROUTE_NOT_FOUND_MESSAGE;
        }
        return REQUEST_REJECTED_MESSAGE;
    }

    /**
     * Builds the one caller-caused rejection response every framework translation returns.
     *
     * <p>It exists so that the status, the body shape and the focus hint are decided in exactly one
     * place: a translation that composed its own {@code ResponseEntity} could drift from the others
     * without any test noticing. The focus hint is derived by the same rule the service-raised path
     * uses, which yields no hint for a framework failure because a framework failure names no
     * legacy screen field.
     *
     * @param message     the neutral summary for this class of failure
     * @param fieldErrors the translated per-field detail, possibly empty
     * @return a {@code 400 Bad Request} carrying the canonical error body
     */
    private static ResponseEntity<ErrorResponse> badRequest(String message,
            List<ErrorResponse.FieldError> fieldErrors) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(message, fieldErrors, focusScreenFieldId(fieldErrors)));
    }

    /**
     * Decides the two legacy field states from the constraint that was violated, falling back to
     * the rejected value when the constraint is not named.
     *
     * <p>A presence constraint means the field was not supplied, which is the BLANK state the
     * legacy screen marked with an asterisk; anything else means a value arrived and failed its
     * edits. Where the framework does not report which constraint fired, the rejected value decides
     * on its own, exactly as {@link #stateOfRejectedValue(Object)} describes.
     *
     * @param constraintName the violated constraint's code or message template, possibly
     *                       {@code null}
     * @param rejectedValue  the value that was rejected, possibly {@code null}; inspected and
     *                       discarded, never reported
     * @return MISSING when the field was not supplied, otherwise INVALID
     */
    private static ErrorResponse.FieldState fieldStateFor(String constraintName,
            Object rejectedValue) {
        if (constraintName != null) {
            for (String presenceConstraint : PRESENCE_CONSTRAINT_NAMES) {
                if (constraintName.contains(presenceConstraint)) {
                    return ErrorResponse.FieldState.MISSING;
                }
            }
        }
        return stateOfRejectedValue(rejectedValue);
    }

    /**
     * Decides which of the two legacy states a framework rejection represents, by inspecting the
     * rejected value and then discarding it.
     *
     * <p>This is the same distinction the legacy screen-decoration macro drew: a field whose flag
     * was blank had never been filled in and was marked as well as highlighted, while a field whose
     * flag was not-OK had been filled in wrongly and was only highlighted. A value that is absent,
     * or that is text consisting entirely of whitespace, is the blank case; anything else is the
     * not-OK case.
     *
     * <p>The value is read here and nowhere else, and it does not leave this method. It is never
     * returned, never placed in a response and never logged, because a rejected value can be a
     * password on a sign-on field or a card primary account number on a card field, and the estate
     * provides field-level masking for neither.
     *
     * @param rejectedValue the value the framework rejected, possibly {@code null}
     * @return MISSING when nothing usable was supplied, otherwise INVALID
     */
    private static ErrorResponse.FieldState stateOfRejectedValue(Object rejectedValue) {
        if (rejectedValue == null) {
            return ErrorResponse.FieldState.MISSING;
        }
        if (rejectedValue instanceof CharSequence text && text.toString().isBlank()) {
            return ErrorResponse.FieldState.MISSING;
        }
        return ErrorResponse.FieldState.INVALID;
    }

    /**
     * Reduces a validation property path to the single name a client sent.
     *
     * <p>A provider-raised violation identifies its property as a path, and for a method-level
     * validation that path begins with the invoked method and its parameter before reaching the
     * property itself. Only the last node is returned: the interior nodes name internal components,
     * and the module's request contract is expressed in terms of the leaf.
     *
     * @param propertyPath the violated property's path, possibly {@code null}
     * @return the leaf node's name, or the empty string when the path names nothing
     */
    private static String leafPropertyName(Path propertyPath) {
        if (propertyPath == null) {
            return EMPTY;
        }
        String leafName = EMPTY;
        for (Path.Node node : propertyPath) {
            if (node.getName() != null) {
                leafName = node.getName();
            }
        }
        return leafName;
    }

    /**
     * Translates the carrier's per-field detail into the response contract's per-field detail.
     *
     * <p>The two field-error types are structurally identical and semantically identical, and the
     * duplication is intentional: the response contract may not depend on the failure-carrier
     * package, so the translation has to happen at this layer. This method is the only outbound
     * translation in the module. The inbound one - turning a field decoration into the failure a
     * service throws - is {@code service/FieldErrorTranslationService}, placed there for the same
     * reason this one is placed here. Decision log entry DL-080 records the arrangement.
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
     * <p>There is deliberately no fallback arm for an absent state, because the carrier rejects a
     * {@code null} state at construction: the legacy flag the two constants derive from is either
     * not-OK or blank whenever the screen-decoration macro fires, so a third state does not exist
     * to be translated. Guessing one here would be worse than the failure it hides - asserting
     * MISSING would tell an operator they left a field blank that they may well have filled in,
     * and asserting INVALID would tell them to correct a value they may never have entered - and
     * either way it would conceal a malformed producer behind a plausible response.
     *
     * @param state the carrier's state, never {@code null}
     * @return the corresponding response-contract state, never {@code null}
     */
    private static ErrorResponse.FieldState toResponseFieldState(
            ValidationException.FieldState state) {
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
