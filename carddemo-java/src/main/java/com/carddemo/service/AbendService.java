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

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;

/**
 * The single abend path of the migrated estate: emits the operator diagnostic, then raises.
 *
 * <p><strong>Thirteen legacy abend sites, two tiers, one service.</strong> Nine are on the batch tier and
 * every one is a static call to {@code CEE3ABD} from a paragraph named {@code 9999-ABEND-PROGRAM}:
 * {@code app/cbl/CBACT01C.cbl} line 173, {@code CBACT02C} line 158, {@code CBACT03C} line 158,
 * {@code CBACT04C} line 632, {@code CBCUS01C} line 158, {@code CBTRN01C} line 473, {@code CBTRN02C}
 * line 711, {@code CBTRN03C} line 630 and {@code CBSTM03A.CBL} line 923. Four are on the online tier and
 * every one is a CICS {@code ABEND} command issued from a paragraph named {@code ABEND-ROUTINE}:
 * {@code app/cbl/COACTUPC.cbl} line 4222, {@code COACTVWC} line 934, {@code COCRDSLC} line 875 and
 * {@code COCRDUPC} line 1550. There is no third path, which is why there is no third entry point here.
 *
 * <p>Those four online sites belong to a five-program family &mdash; {@code COACTUPC}, {@code COACTVWC},
 * {@code COCRDLIC}, {@code COCRDSLC} and {@code COCRDUPC} &mdash; the only family that includes the
 * attention-key copybook {@code CSSTRPFY} and the screen work-area copybook {@code CVCRD01Y}. Four sites
 * across five members is not an oversight: {@code COCRDLIC} comments out its inclusion of the
 * abend-context copybook at {@code app/cbl/COCRDLIC.cbl} line 283, whereas the other four include it live
 * at {@code COACTUPC} line 632, {@code COACTVWC} line 238, {@code COCRDSLC} line 224 and
 * {@code COCRDUPC} line 343. Having no context to send, {@code COCRDLIC} registers no abend handler and
 * issues no abend. The estate's other twelve online programs never abend, and wiring them to this service
 * would invent behaviour the legacy does not have.
 *
 * <p><strong>Emit-then-abend is the contract, not a convenience.</strong> Every legacy site emits its
 * diagnostic <em>before</em> it abends, and every entry point here reproduces that ordering: the log call
 * precedes the {@code throw} in the same method body, never in a caller's {@code catch}. On a mainframe
 * the diagnostic reached the operator whether or not anything survived to handle the abend, so a
 * {@code catch}-based equivalent would lose exactly the diagnostic the operator needs at the moment the
 * run is failing. All nine batch members display the literal {@code ABENDING PROGRAM} immediately before
 * calling {@code CEE3ABD}, and the two that carry a file status display that status first as well; the
 * online routine transmits the whole 134-byte context to the terminal, deregisters the program's abend
 * handler, and only then issues the abend.
 *
 * <p><strong>The 134-byte context.</strong> {@code app/cpy/CSMSG02Y.cpy} declares {@code ABEND-DATA} as
 * four space-initialised character fields summing to 134. This service does not restate those widths: it
 * populates {@link AbendException}, which owns them as constants and renders the image through its own
 * fixed-width context method, so the layout is declared in exactly one place. The culprit field carries
 * the <strong>program name</strong> &mdash; eight characters, precisely the width of a COBOL member name
 * &mdash; so callers should pass the legacy member name they are translating and a Java log line and a
 * mainframe abend then name the same culprit.
 *
 * <p><strong>The diagnostic vocabulary belongs to the caller, not to this service.</strong> Operation
 * descriptions are the legacy display literals and each call site chose its own wording; resource names
 * are the legacy DD, dataset and CICS file names. Every one arrives as a parameter and none is declared
 * here.
 *
 * <p><strong>What this service deliberately does not do.</strong> It does not terminate the process and
 * holds no notion of a return code: an abend becomes a thrown exception, and translating the batch tier's
 * non-zero completion code belongs to the job and step layer. It does not model program cancellation
 * either. The {@code CANCEL} token appears six times in {@code app/cbl} and not one occurrence is the
 * COBOL {@code CANCEL} statement: four are the {@code CANCEL} option of the CICS {@code HANDLE ABEND}
 * command, which deregisters the issuing program's own handler on the line immediately above each online
 * abend, and two are prose inside comments. The equivalent of deregistering a handler in a Spring runtime
 * is simply the absence of a {@code catch}, so no registration, deregistration or module-unload operation
 * is offered.
 *
 * <p>Stateless and immutable: no instance field, no mutable static, no accumulated abend history and no
 * counter, so a single container-managed instance is safely shared across every thread on both tiers.
 */
@Service
public final class AbendService {

    /**
     * The diagnostic channel that replaces the estate's console display statements. Named for this class so
     * the per-profile {@code com.carddemo.service} level governs it. The logger lives here and not on the
     * exception types precisely because emit-then-abend is this service's obligation: the exceptions carry
     * context and deliberately hold no logger, so there is exactly one place where an abend is recorded.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AbendService.class);

    /**
     * The operator-facing text the batch members display immediately before abending, reproduced verbatim
     * from {@code 9999-ABEND-PROGRAM} in all nine of them, for example {@code app/cbl/CBACT01C.cbl} line
     * 170. Kept as the leading token of the abend log line so an operator searching a Java log for the
     * phrase they already know finds the same event.
     */
    private static final String ABENDING_PROGRAM = "ABENDING PROGRAM";

    /**
     * Rendered in a log line in place of a value the caller did not supply. The legacy fields are
     * initialised to spaces, so a missing value is never an error here. A visible marker is used rather
     * than an empty gap because an empty gap reads as a formatting fault.
     */
    private static final String NOT_SUPPLIED = "(none)";

    /**
     * Rendered beside a raw file status that falls outside the vocabulary the estate exercises. Such a
     * status is a legitimate runtime condition rather than a fault: the legacy status-display paragraph has
     * a dedicated branch for a status that is not numeric or whose first character is {@code 9}, so the
     * estate itself anticipates values it never tests against. The raw characters are logged unchanged
     * beside the marker.
     */
    private static final String OUTSIDE_VOCABULARY = "(outside declared vocabulary)";

    /**
     * Creates the abend service singleton. Declared explicitly rather than left implicit so that the
     * absence of collaborators is a visible, reviewable property of the class.
     */
    public AbendService() {
        // No collaborators to inject: the abend path depends on nothing but its own arguments.
    }

    /**
     * Abends on the batch tier with no file-status context, reproducing {@code 9999-ABEND-PROGRAM}.
     *
     * <p>This is the shape of the batch abend that carries no {@code FILE STATUS}, of which
     * {@code app/cbl/CBSTM03A.CBL} (line 923) is the estate's example: it reaches its abend
     * paragraph from a subprogram call-linkage return code rather than from a file status, and it
     * has no status-display paragraph at all.
     *
     * <p><strong>Never returns normally.</strong> The diagnostic is logged at ERROR first and the
     * abend is raised second, in that order, within this method.
     *
     * @param programName the failing program, carried into the legacy {@code ABEND-CULPRIT} field;
     *                    pass the legacy member name being translated, for example
     *                    {@code CBSTM03A}. At most {@value AbendException#CULPRIT_LENGTH}
     *                    characters
     * @param reason      why the run is being abended, carried into the legacy
     *                    {@code ABEND-REASON} field; at most
     *                    {@value AbendException#REASON_LENGTH} characters
     * @throws AbendException         always, once the diagnostic has been emitted
     * @throws IllegalArgumentException if {@code programName} or {@code reason} is longer than its
     *                                  legacy field. The diagnostic has already been emitted by
     *                                  the time this can happen, so no diagnostic is lost
     */
    public void abendBatch(String programName, String reason) {
        abendBatch(programName, reason, null, null, null);
    }

    /**
     * Abends on the batch tier carrying whatever I/O context the caller has, reproducing the legacy sequence
     * of an operation diagnostic, then the raw file status, then {@code 9999-ABEND-PROGRAM}.
     *
     * <p>The shape of every file-driven batch abend. {@code app/cbl/CBACT01C.cbl} performs it at three
     * structurally identical sites &mdash; read path lines 110 to 113, open path lines 144 to 147, close
     * path lines 162 to 165 &mdash; and the remaining batch members repeat the pattern. All of that context
     * arrives here in one ERROR log line, emitted before the abend is raised, so an operator reads the
     * operation, the resource, the raw status and the culprit together.
     *
     * <p>The batch tier carries no {@code ABEND-DATA} area: the abend-context copybook is included by four
     * online members and by no batch member, and the batch paragraphs move a numeric abend code into a
     * binary item instead. The 134-character context image is therefore not emitted here; it belongs to the
     * online tier alone. The abend code is {@value AbendException#BATCH_ABEND_CODE}, the value the batch
     * paragraphs set.
     *
     * <p><strong>Never returns normally.</strong>
     *
     * @param programName   the failing program, carried into {@code ABEND-CULPRIT}; at most
     *                      {@value AbendException#CULPRIT_LENGTH} characters
     * @param reason        why the run is being abended, carried into {@code ABEND-REASON}; at most
     *                      {@value AbendException#REASON_LENGTH} characters
     * @param rawFileStatus the raw two-character {@code FILE STATUS} exactly as reported, or
     *                      {@code null} when the failing path has none. It is neither normalised
     *                      nor validated here: a status outside the vocabulary the estate
     *                      exercises is a legitimate runtime condition that the legacy
     *                      status-display paragraph has a dedicated branch for, and refusing it
     *                      would break the very diagnostic that exists to report it
     * @param operation     the caller's own description of the attempted operation, such as
     *                      {@code ERROR READING ACCOUNT FILE}, or {@code null} when there is none
     * @param resourceName  the legacy DD, dataset or CICS file name, such as {@code ACCTDAT}, or
     *                      {@code null} when there is none
     * @throws AbendException         always, once the diagnostic has been emitted
     * @throws IllegalArgumentException if {@code programName} or {@code reason} is longer than its
     *                                  legacy field
     */
    public void abendBatch(String programName, String reason, String rawFileStatus,
            String operation, String resourceName) {
        // Emit first, raise second. The diagnostic is built from the raw arguments rather than
        // from the exception, so that it survives even an over-length argument being rejected by
        // the exception's own width enforcement on the line below.
        logAbendDiagnostic(AbendException.BATCH_ABEND_CODE, programName, reason, rawFileStatus,
                operation, resourceName);
        throw new AbendException(AbendException.BATCH_ABEND_CODE, programName, reason,
                AbendException.DEFAULT_MESSAGE, null);
    }

    /**
     * Abends on the online tier with the default operator message, reproducing
     * {@code ABEND-ROUTINE}.
     *
     * <p>Equivalent to {@link #abendOnline(String, String, String)} with the message the legacy
     * substitutes when none was supplied, which the exception exposes as
     * {@code AbendException.DEFAULT_MESSAGE}.
     *
     * <p><strong>Never returns normally.</strong>
     *
     * @param programName the failing program, carried into {@code ABEND-CULPRIT}; pass the legacy
     *                    member name, for example {@code COACTUPC}. At most
     *                    {@value AbendException#CULPRIT_LENGTH} characters
     * @param reason      why the transaction is being abended, carried into
     *                    {@code ABEND-REASON}; at most {@value AbendException#REASON_LENGTH}
     *                    characters
     * @throws AbendException         always, once the diagnostic has been emitted
     * @throws IllegalArgumentException if {@code programName} or {@code reason} is longer than its
     *                                  legacy field
     */
    public void abendOnline(String programName, String reason) {
        abendOnline(programName, reason, AbendException.DEFAULT_MESSAGE);
    }

    /**
     * Abends on the online tier with an explicit operator message, reproducing {@code ABEND-ROUTINE}.
     *
     * <p>The online path differs from the batch path in three ways, all taken from the source. The abend
     * code is {@value AbendException#ONLINE_ABEND_CODE} rather than the batch value, which is why the two
     * tiers have separate entry points and why a caller cannot reach the wrong one by accident. The four
     * online members populate the 134-byte {@code ABEND-DATA} area and transmit the whole of it before
     * abending, so this method emits the rendered context image as well as the field-by-field diagnostic.
     * And no online program declares a {@code FILE STATUS} item &mdash; the online tier tests the CICS
     * command response condition instead &mdash; so this method accepts no raw file status; offering one
     * would invite callers to invent a value the legacy never had.
     *
     * <p><strong>Never returns normally.</strong> Constructing the exception is not raising it: the instance
     * is built so its rendered image can be emitted, exactly as the legacy populated the context area before
     * transmitting it, and only then is it thrown.
     *
     * @param programName     the failing program, carried into {@code ABEND-CULPRIT}; at most
     *                        {@value AbendException#CULPRIT_LENGTH} characters
     * @param reason          why the transaction is being abended, carried into
     *                        {@code ABEND-REASON}; at most
     *                        {@value AbendException#REASON_LENGTH} characters
     * @param terminalMessage the operator message, carried into {@code ABEND-MSG}; at most
     *                        {@value AbendException#MESSAGE_LENGTH} characters. {@code null} or
     *                        blank is replaced by the exception's default message, faithful to the
     *                        legacy routine substituting its literal when the field carries no
     *                        supplied value
     * @throws AbendException         always, once the diagnostic has been emitted
     * @throws IllegalArgumentException if any argument is longer than its legacy field
     */
    public void abendOnline(String programName, String reason, String terminalMessage) {
        logAbendDiagnostic(AbendException.ONLINE_ABEND_CODE, programName, reason, null, null, null);
        AbendException abend = new AbendException(AbendException.ONLINE_ABEND_CODE, programName,
                reason, terminalMessage, null);
        emitContextImage(abend);
        throw abend;
    }

    /**
     * Emits a raw file status as an operator diagnostic <strong>without</strong> raising anything.
     *
     * <p>Reproduces the status-display paragraph that the batch members perform immediately before their
     * abend paragraph. Eight of the nine abending batch members carry one and they do not agree on its name:
     * six call it {@code 9910-DISPLAY-IO-STATUS} ({@code app/cbl/CBACT01C.cbl} line 176, {@code CBACT02C}
     * line 161, {@code CBACT03C} line 161, {@code CBACT04C} line 635, {@code CBTRN02C} line 714 and
     * {@code CBTRN03C} line 633) while two call it {@code Z-DISPLAY-IO-STATUS}
     * ({@code app/cbl/CBCUS01C.cbl} line 161 and {@code app/cbl/CBTRN01C.cbl} line 476). The ninth,
     * {@code CBSTM03A}, has none because it inspects a subprogram return code rather than a file status. One
     * Java name is given to both spellings, per decision log entry D-41.
     *
     * <p>It exists separately from the abend entry points because the legacy separated them: the status
     * display runs on its own wherever the program intends to <em>continue</em>, and immediately before the
     * abend paragraph wherever it does not. The status is logged verbatim under the legacy operator prefix,
     * which the exception type publishes as {@code FileStatusException.DISPLAY_PREFIX}, and is neither
     * normalised nor validated; the resolved vocabulary name is added beside it only as a reading aid.
     *
     * @param rawFileStatus the raw two-character {@code FILE STATUS} exactly as reported;
     *                      {@code null} is tolerated and rendered as an explicit marker, because a
     *                      diagnostic emitter must never itself fail on the path that is already
     *                      failing
     * @param operation     the caller's own description of the attempted operation, such as
     *                      {@code ERROR OPENING ACCTFILE}, or {@code null} when there is none
     * @param resourceName  the legacy DD, dataset or CICS file name, such as {@code ACCTDAT}, or
     *                      {@code null} when there is none
     */
    public void displayIoStatus(String rawFileStatus, String operation, String resourceName) {
        LOG.error("{} fileStatus={} statusName={} operation={} resource={}",
                FileStatusException.DISPLAY_PREFIX,
                orMarker(rawFileStatus),
                statusMnemonic(rawFileStatus),
                orMarker(operation),
                orMarker(resourceName));
    }

    /**
     * Emits the diagnostic for a failed file operation and abends on the batch tier, chaining the raw status
     * as the cause.
     *
     * <p>The whole of the legacy file-failure sequence in one call: operation diagnostic, raw status, abend,
     * in that order. The convenience form of {@link #abendBatch(String, String, String, String, String)} for
     * the common case where the trigger is a file status, differing in one respect that matters &mdash; the
     * raised abend carries a {@code FileStatusException} as its cause, so the raw two-character status
     * survives on the exception chain and not only in the log.
     *
     * <p>The status is validated before anything is constructed. {@value FileStatusException#STATUS_SUCCESS}
     * reports success and {@value FileStatusException#STATUS_END_OF_FILE} reports end of file; neither is an
     * error, and end of file in particular is how every sequential read loop in the batch tier terminates
     * normally. Passing either is a programming fault rather than a runtime condition, so it is rejected
     * here with the same {@code IllegalArgumentException} the exception type raises, keeping the failure
     * attributable to the misuse instead of surfacing from inside a constructor.
     *
     * <p><strong>Never returns normally.</strong>
     *
     * @param programName   the failing program, carried into {@code ABEND-CULPRIT}; at most
     *                      {@value AbendException#CULPRIT_LENGTH} characters
     * @param rawFileStatus the raw two-character {@code FILE STATUS} exactly as reported; must be
     *                      non-{@code null}, exactly {@value FileStatusException#CODE_LENGTH}
     *                      characters, and neither of the two non-error statuses
     * @param operation     the caller's own description of the attempted operation, such as
     *                      {@code ERROR READING DISCLOSURE GROUP FILE}, or {@code null} when there
     *                      is none
     * @param resourceName  the legacy DD, dataset or CICS file name, such as {@code DISCGRP}, or
     *                      {@code null} when there is none
     * @throws AbendException          always, once the diagnostic has been emitted, carrying a
     *                                 {@code FileStatusException} as its cause
     * @throws IllegalArgumentException if {@code rawFileStatus} is absent, is not exactly two
     *                                  characters, or reports success or end of file; or if
     *                                  {@code programName} is longer than its legacy field
     */
    public void abendOnFileStatus(String programName, String rawFileStatus, String operation,
            String resourceName) {
        rejectNonErrorStatus(rawFileStatus, programName, operation, resourceName);
        abendOnFileStatus(programName,
                new FileStatusException(rawFileStatus, operation, resourceName));
    }

    /**
     * Emits the diagnostic for an already-caught file failure and abends on the batch tier,
     * chaining it as the cause.
     *
     * <p>The companion form for a caller that already holds the failure -- typically a repository
     * or reader boundary that raised it -- rather than the raw status characters. The raw status,
     * the operation and the resource name are read back off the supplied failure, so a caller
     * cannot log one status and chain another.
     *
     * <p>No status re-validation is performed and none is needed: {@code FileStatusException} is
     * the error arm only, and its constructors reject success and end of file, so any instance that
     * exists is already a genuine error.
     *
     * <p><strong>Never returns normally.</strong>
     *
     * @param programName the failing program, carried into {@code ABEND-CULPRIT}; at most
     *                    {@value AbendException#CULPRIT_LENGTH} characters
     * @param failure     the file failure to report and chain; must not be {@code null}
     * @throws AbendException          always, once the diagnostic has been emitted, carrying
     *                                 {@code failure} as its cause
     * @throws IllegalArgumentException if {@code failure} is {@code null}, or if
     *                                  {@code programName} is longer than its legacy field
     */
    public void abendOnFileStatus(String programName, FileStatusException failure) {
        if (failure == null) {
            LOG.error("{} culprit={} reason=ABEND REQUESTED WITH NO FILE FAILURE SUPPLIED",
                    ABENDING_PROGRAM, orMarker(programName));
            throw new IllegalArgumentException(
                    "A FileStatusException is required to abend on a file status, but null was"
                            + " supplied.");
        }
        String rawFileStatus = failure.code();
        String reason = abendReasonFor(rawFileStatus);
        logAbendDiagnostic(AbendException.BATCH_ABEND_CODE, programName, reason, rawFileStatus,
                failure.operation(), failure.resourceName());
        throw new AbendException(AbendException.BATCH_ABEND_CODE, programName, reason,
                AbendException.DEFAULT_MESSAGE, failure);
    }

    /**
     * The single place an abend is recorded, and the reason emit-then-abend holds everywhere.
     *
     * <p>Every abend entry point calls this before it raises, and nothing else in the module logs an abend,
     * so the ordering is a property of this class rather than a convention callers have to remember. One
     * structured line carries every field, because the legacy emitted the operation text, the raw status and
     * the abend announcement as consecutive displays that an operator read together; splitting them would
     * let an interleaved log separate them. The line opens with the literal the batch members display
     * immediately before abending, so an operator searching for the phrase they already know still finds the
     * event.
     *
     * <p>Abend code, culprit and reason are always present. The I/O context is appended only when the caller
     * supplied it, because a label with nothing after it tells a reader less than its absence does and the
     * online tier legitimately has no file status, operation description or resource name. Composed rather
     * than parameterised precisely because the field set is conditional, guarded by a level check, and
     * passed as the single-argument message, which SLF4J takes verbatim so a brace inside a caller's own
     * text cannot corrupt the line.
     *
     * @param abendCode     the abend code being raised, which is the tier's own constant
     * @param programName   the failing program, as supplied by the caller
     * @param reason        the failure reason, as supplied by the caller
     * @param rawFileStatus the raw two-character file status, or {@code null} when there is none
     * @param operation     the caller's operation description, or {@code null} when there is none
     * @param resourceName  the legacy resource name, or {@code null} when there is none
     */
    private static void logAbendDiagnostic(String abendCode, String programName, String reason,
            String rawFileStatus, String operation, String resourceName) {
        if (!LOG.isErrorEnabled()) {
            return;
        }
        StringBuilder line = new StringBuilder();
        line.append(ABENDING_PROGRAM)
                .append(" abendCode=").append(orMarker(abendCode))
                .append(" culprit=").append(orMarker(programName))
                .append(" reason=").append(orMarker(reason));
        appendIfSupplied(line, " operation=", operation);
        appendIfSupplied(line, " resource=", resourceName);
        if (isSupplied(rawFileStatus)) {
            line.append(" fileStatus=").append(rawFileStatus)
                    .append(" statusName=").append(statusMnemonic(rawFileStatus));
        }
        LOG.error(line.toString());
    }

    /**
     * Appends a labelled value to a diagnostic line, and appends nothing at all when the value was
     * not supplied.
     *
     * @param target the line being composed
     * @param label  the label to write before the value, including its leading separator
     * @param value  the value to write, possibly {@code null} or blank
     */
    private static void appendIfSupplied(StringBuilder target, String label, String value) {
        if (isSupplied(value)) {
            target.append(label).append(value);
        }
    }

    /**
     * Emits the rendered fixed-width abend context, reproducing the online transmission of the whole
     * {@code ABEND-DATA} area.
     *
     * <p>Called on the online path only, because only the online members carry that area. Emitted at DEBUG
     * rather than ERROR because the preceding ERROR line already carries all four field values readably;
     * what this adds is the byte-exact image, space padding included, for a reader comparing Java output
     * against a mainframe screen capture. The image and its length both come from the exception, which owns
     * the four legacy widths as constants, so no width, offset or padding rule is restated here.
     *
     * @param abend the abend whose context image is to be emitted; never {@code null}, because
     *              every caller has just constructed it
     */
    private static void emitContextImage(AbendException abend) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("abendContext=[{}] contextLength={}",
                    abend.toFixedWidthContext(), AbendException.CONTEXT_LENGTH);
        }
    }

    /**
     * Rejects the two statuses that are not errors, before any exception instance is built.
     *
     * <p>Mirrors the guard inside {@code FileStatusException} so that a misuse is reported against
     * the call that made it. The diagnostic is emitted before the rejection is raised, keeping the
     * emit-then-raise ordering uniform even on the misuse path: a caller that reaches this guard has
     * a defect on its error path, which is the least convenient place to be told nothing.
     *
     * @param rawFileStatus the candidate raw status
     * @param programName   the failing program, for the diagnostic only
     * @param operation     the caller's operation description, for the diagnostic only
     * @param resourceName  the legacy resource name, for the diagnostic only
     * @throws IllegalArgumentException if the status is absent, is not exactly
     *                                  {@value FileStatusException#CODE_LENGTH} characters, or
     *                                  reports success or end of file
     */
    private static void rejectNonErrorStatus(String rawFileStatus, String programName,
            String operation, String resourceName) {
        String rejection = rejectionReasonFor(rawFileStatus);
        if (rejection == null) {
            return;
        }
        LOG.error("{} culprit={} operation={} resource={} fileStatus={} misuse={}",
                ABENDING_PROGRAM,
                orMarker(programName),
                orMarker(operation),
                orMarker(resourceName),
                orMarker(rawFileStatus),
                rejection);
        throw new IllegalArgumentException(rejection);
    }

    /**
     * Decides whether a candidate raw status may be used to abend, and says why when it may not.
     *
     * <p>Separated from the guard that raises so that the decision is a single expression with no
     * side effect, and so the guard reads as emit-then-raise rather than as branching.
     *
     * @param rawFileStatus the candidate raw status, possibly {@code null}
     * @return {@code null} when the status is usable, otherwise the reason it is not
     */
    private static String rejectionReasonFor(String rawFileStatus) {
        if (rawFileStatus == null) {
            return "A COBOL FILE STATUS of exactly " + FileStatusException.CODE_LENGTH
                    + " characters is required to abend on a file status, but null was supplied.";
        }
        if (rawFileStatus.length() != FileStatusException.CODE_LENGTH) {
            return "A COBOL FILE STATUS must be exactly " + FileStatusException.CODE_LENGTH
                    + " characters, but \"" + rawFileStatus + "\" has length "
                    + rawFileStatus.length() + ".";
        }
        if (FileStatusException.STATUS_SUCCESS.equals(rawFileStatus)) {
            return "File status \"" + FileStatusException.STATUS_SUCCESS
                    + "\" reports a successful operation, which is not an error and must not abend.";
        }
        if (FileStatusException.STATUS_END_OF_FILE.equals(rawFileStatus)) {
            return "File status \"" + FileStatusException.STATUS_END_OF_FILE
                    + "\" reports end of file, which is how a sequential read loop terminates"
                    + " normally and must not abend; handle it as the end-of-file arm instead.";
        }
        return null;
    }

    /**
     * Builds the abend reason for a file-status-triggered abend.
     *
     * <p>Deliberately factual and deliberately short, and <em>not</em> an external-contract literal: the
     * reason field is only ever transmitted on the online tier and no online program has a file status, so
     * no legacy text exists for this combination to be faithful to. Inventing operator prose here would be
     * feature expansion, so the value is the labelled status and nothing more.
     *
     * @param rawFileStatus the validated raw two-character status
     * @return the reason text to carry into the legacy {@code ABEND-REASON} field
     */
    private static String abendReasonFor(String rawFileStatus) {
        return "FILE STATUS " + rawFileStatus;
    }

    /**
     * Resolves a raw status to its vocabulary name, purely as a reading aid for the log.
     *
     * <p>A total function over every possible input: {@code null} renders as the not-supplied marker and an
     * unrecognised value as the outside-vocabulary marker, so no status can make this diagnostic throw. That
     * tolerance is taken from the source &mdash; the legacy status-display paragraph has an explicit branch
     * for a status that is not numeric or whose first character is {@code 9}. Nothing branches on the
     * resolved value and nothing is normalised by it; the raw status is always logged alongside it and
     * remains authoritative.
     *
     * @param rawFileStatus the raw two-character status, possibly {@code null}
     * @return the vocabulary name, or a marker when the status is absent or unrecognised
     */
    private static String statusMnemonic(String rawFileStatus) {
        if (rawFileStatus == null) {
            return NOT_SUPPLIED;
        }
        Optional<FileStatus> resolved = FileStatus.fromCode(rawFileStatus);
        return resolved.map(FileStatus::name).orElse(OUTSIDE_VOCABULARY);
    }

    /**
     * Renders optional context for a log line, substituting a visible marker for an absent value.
     *
     * <p>Blank is treated as absent as well as {@code null}, because the legacy fields are
     * initialised to spaces and a space-filled field means the same thing as an unset one.
     *
     * @param value the value to render, possibly {@code null} or blank
     * @return the value unchanged, or the not-supplied marker when it is absent or blank
     */
    private static String orMarker(String value) {
        if (isSupplied(value)) {
            return value;
        }
        return NOT_SUPPLIED;
    }

    /**
     * The single test for whether a caller actually supplied a piece of optional context. Blank counts as
     * absent as well as {@code null}, because the legacy context fields are initialised to spaces. Every
     * rendering decision in this class routes through here, so a value cannot be treated as present in one
     * log line and absent in another.
     *
     * @param value the value to test, possibly {@code null}
     * @return {@code true} when the value is non-{@code null} and not blank
     */
    private static boolean isSupplied(String value) {
        return value != null && !value.isBlank();
    }
}
