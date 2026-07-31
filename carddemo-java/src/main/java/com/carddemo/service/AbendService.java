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
 * <h2>Provenance</h2>
 * Translated from the AWS CardDemo z/OS mainframe application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy authorities are
 * {@code app/cpy/CSMSG02Y.cpy}, which declares the 134-byte {@code ABEND-DATA} context; the nine
 * batch calls to the Language Environment abort routine {@code CEE3ABD}; and the four online CICS
 * {@code ABEND} commands. The legacy source is cited, never transcribed.
 *
 * <h2>What this service replaces</h2>
 * The estate has exactly two abend paths and thirteen abend sites, and this one service replaces
 * all of them. There is no third path, which is why there is no third entry point here.
 *
 * <p>Nine sites are on the batch tier and every one of them is a static call to {@code CEE3ABD}
 * from a paragraph the members name {@code 9999-ABEND-PROGRAM}:
 *
 * <ul>
 *   <li>{@code app/cbl/CBACT01C.cbl} line 173</li>
 *   <li>{@code app/cbl/CBACT02C.cbl} line 158</li>
 *   <li>{@code app/cbl/CBACT03C.cbl} line 158</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} line 632</li>
 *   <li>{@code app/cbl/CBCUS01C.cbl} line 158</li>
 *   <li>{@code app/cbl/CBTRN01C.cbl} line 473</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} line 711</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl} line 630</li>
 *   <li>{@code app/cbl/CBSTM03A.CBL} line 923</li>
 * </ul>
 *
 * <p>Four sites are on the online tier and every one of them is a CICS {@code ABEND} command
 * issued from a paragraph the members name {@code ABEND-ROUTINE}:
 *
 * <ul>
 *   <li>{@code app/cbl/COACTUPC.cbl} line 4222</li>
 *   <li>{@code app/cbl/COACTVWC.cbl} line 934</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl} line 875</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} line 1550</li>
 * </ul>
 *
 * <p>Those four online sites belong exclusively to a five-program family -- {@code COACTUPC},
 * {@code COACTVWC}, {@code COCRDLIC}, {@code COCRDSLC} and {@code COCRDUPC} -- which is the only
 * family in the estate that includes the attention-key copybook {@code CSSTRPFY} and the screen
 * work-area copybook {@code CVCRD01Y}. Four sites across five members is not an oversight in this
 * translation: {@code COCRDLIC} contains no abend construct whatsoever, and the reason is visible
 * one line at a time in the source. Its inclusion of the abend-context copybook is commented out
 * at {@code app/cbl/COCRDLIC.cbl} line 283, whereas the other four members include it live at
 * {@code COACTUPC} line 632, {@code COACTVWC} line 238, {@code COCRDSLC} line 224 and
 * {@code COCRDUPC} line 343. Having no context to send, {@code COCRDLIC} also registers no abend
 * handler and issues no abend. A reader should therefore expect the online abend path to be
 * reachable from four of those five features and from no other online program: the estate's other
 * twelve online programs never abend, and wiring them to this service would invent behaviour the
 * legacy does not have.
 *
 * <h2>Emit-then-abend is the contract, not a convenience</h2>
 * Every legacy abend site emits its diagnostic <em>before</em> it abends, and this service
 * reproduces that ordering at every entry point: the log call precedes the {@code throw} in the
 * same method body, never in a caller's {@code catch}. The ordering is observable, so reversing it
 * would be a behavioural regression rather than a stylistic difference. On a mainframe the
 * diagnostic reached the operator whether or not anything survived to handle the abend; a
 * {@code catch}-based equivalent would lose exactly the diagnostic the operator needs at exactly
 * the moment the run is failing.
 *
 * <p>The batch tier proves the ordering nine times over: all nine members display the literal
 * {@code ABENDING PROGRAM} immediately before calling {@code CEE3ABD}, and the two members that
 * carry a file status display that status first as well. {@code app/cbl/CBACT01C.cbl} does so at
 * three structurally identical sites, on its read path (lines 110 to 113), its open path (lines 144
 * to 147) and its close path (lines 162 to 165); each displays the operation diagnostic, moves the
 * raw two-byte status into a display field, performs the status display, and only then performs the
 * abend paragraph. The online tier proves the same ordering differently: the abend routine in
 * {@code app/cbl/COACTUPC.cbl} transmits the whole 134-byte context to the terminal, deregisters
 * the program's abend handler, and only then issues the abend.
 *
 * <h2>The 134-byte context</h2>
 * {@code app/cpy/CSMSG02Y.cpy} declares {@code ABEND-DATA} as four character fields, each
 * initialised to spaces: {@code ABEND-CODE} at {@code PIC X(4)}, {@code ABEND-CULPRIT} at
 * {@code PIC X(8)}, {@code ABEND-REASON} at {@code PIC X(50)} and {@code ABEND-MSG} at
 * {@code PIC X(72)}, which sum to 134. This service does not restate those widths. It populates
 * {@link AbendException}, which owns them as constants and renders the image through its own
 * fixed-width context method, so the layout is declared in exactly one place.
 *
 * <p>The culprit field carries the <strong>program name</strong>. The online routine moves the
 * member's own program-name literal into {@code ABEND-CULPRIT}, and eight characters is precisely
 * the width of a COBOL member name. Callers should pass the legacy member name they are
 * translating -- {@code CBACT01C}, {@code COACTUPC} and so on -- so that a Java log line and a
 * mainframe abend name the same culprit.
 *
 * <h2>Diagnostic vocabulary this service accepts but does not own</h2>
 * The operation descriptions are the legacy display literals, and they belong to the calling
 * service because each call site chose its own wording. The estate's file-oriented abend paths use
 * {@code ERROR READING ACCOUNT FILE}, {@code ERROR OPENING ACCTFILE},
 * {@code ERROR CLOSING ACCOUNT FILE}, {@code ERROR READING DISCLOSURE GROUP FILE},
 * {@code ERROR READING DEFAULT DISCLOSURE GROUP} and {@code ERROR READING XREF FILE}. The resource
 * names are the legacy DD, dataset and CICS file names: {@code ACCTDAT}, {@code DISCGRP},
 * {@code TCATBAL}, {@code CARDDAT}, {@code CUSTDAT}, {@code TRANSACT}, {@code USRSEC} and
 * {@code CARDXREF}. They are listed here so a reader can see the vocabulary at a glance; every one
 * arrives as a parameter and none is declared in this class.
 *
 * <h2>What this service deliberately does not do</h2>
 * It does not terminate the process, and it holds no notion of a return code. An abend becomes a
 * thrown exception; translating the batch tier's non-zero completion code is the job and step
 * layer's responsibility, and the partial-success code that the transaction posting program sets
 * belongs to that program's own service. It does not model program cancellation either. The
 * {@code CANCEL} token appears six times in {@code app/cbl}, and not one occurrence is the COBOL
 * {@code CANCEL} statement: four are the {@code CANCEL} option of the CICS
 * {@code HANDLE ABEND} command, which deregisters the issuing program's own abend handler on the
 * line immediately above each online abend, and two are prose inside comments. Handler
 * registration and deregistration have no equivalent in a Spring runtime -- the equivalent of
 * deregistering a handler is simply the absence of a {@code catch} -- so no registration,
 * deregistration or module-unload operation is offered here.
 *
 * <h2>Thread safety</h2>
 * Stateless and immutable: no instance field, no mutable static, no accumulated abend history and
 * no counter. A single container-managed instance is safely shared by every caller on every thread,
 * on both the request-serving and the batch-executing side.
 */
@Service
public final class AbendService {

    /**
     * The diagnostic channel that replaces the estate's console display statements.
     *
     * <p>Named for this class, so that the {@code com.carddemo.service} level configured per
     * profile governs it. The logger lives here and not on the exception types precisely because
     * emit-then-abend is this service's obligation: the exceptions carry context and deliberately
     * hold no logger, so there is exactly one place where an abend is recorded.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AbendService.class);

    /**
     * The operator-facing text the batch members display immediately before abending, reproduced
     * verbatim from {@code 9999-ABEND-PROGRAM} in all nine of them, for example
     * {@code app/cbl/CBACT01C.cbl} line 170.
     *
     * <p>Kept as the leading token of the abend log line so that an operator searching a Java log
     * for the phrase they already know finds the same event.
     */
    private static final String ABENDING_PROGRAM = "ABENDING PROGRAM";

    /**
     * Rendered in a log line in place of a value the caller did not supply.
     *
     * <p>The legacy fields are initialised to spaces, so a missing value is never an error here.
     * A visible marker is used rather than an empty gap because an empty gap in a log line reads
     * as a formatting fault and invites a reader to doubt the record.
     */
    private static final String NOT_SUPPLIED = "(none)";

    /**
     * Rendered beside a raw file status that falls outside the vocabulary the estate exercises.
     *
     * <p>Such a status is a legitimate runtime condition rather than a fault. The legacy
     * status-display paragraph has a dedicated branch for a status that is not numeric or whose
     * first character is {@code 9}, so the estate itself anticipates values it never tests against.
     * The marker records that the code was not recognised while the raw characters are logged
     * unchanged next to it.
     */
    private static final String OUTSIDE_VOCABULARY = "(outside declared vocabulary)";

    /**
     * Creates the abend service singleton.
     *
     * <p>This service sits at the base of the dependency graph and injects no collaborator, so
     * constructor injection contributes no parameters. The constructor is declared explicitly
     * rather than left implicit so that the absence of collaborators is a visible, reviewable
     * property of the class.
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
     * Abends on the batch tier carrying whatever I/O context the caller has, reproducing the
     * legacy sequence of an operation diagnostic, then the raw file status, then
     * {@code 9999-ABEND-PROGRAM}.
     *
     * <p>This is the shape of every file-driven batch abend. {@code app/cbl/CBACT01C.cbl} performs
     * it at three structurally identical sites -- its read path (lines 110 to 113), its open path
     * (lines 144 to 147) and its close path (lines 162 to 165) -- and the remaining batch members
     * repeat the pattern. All of that context arrives here in one ERROR log line, emitted before
     * the abend is raised, so an operator reads the operation, the resource, the raw status and the
     * culprit together rather than reassembling them from separate lines.
     *
     * <p>The batch tier carries no {@code ABEND-DATA} area: the abend-context copybook is included
     * by four online members and by no batch member, and the batch paragraphs move a numeric abend
     * code into a binary item instead. The 134-character context image is therefore not emitted
     * here; it belongs to the online tier alone. The abend code is
     * {@value AbendException#BATCH_ABEND_CODE}, the value the batch paragraphs set.
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
     * Abends on the online tier with an explicit operator message, reproducing
     * {@code ABEND-ROUTINE}.
     *
     * <p>The online path differs from the batch path in three ways, all of them taken from the
     * source rather than chosen here. The abend code is {@value AbendException#ONLINE_ABEND_CODE}
     * rather than the batch value, which is why the two tiers have separate entry points and why a
     * caller cannot reach the wrong one by accident. The four online members populate the 134-byte
     * {@code ABEND-DATA} area and transmit the whole of it before abending, so this method emits
     * the rendered context image as well as the field-by-field diagnostic. And no online program in
     * the estate declares a {@code FILE STATUS} item -- the online tier tests the CICS command
     * response condition instead -- so this method accepts no raw file status; offering one would
     * invite callers to invent a value the legacy never had.
     *
     * <p><strong>Never returns normally.</strong> Note that constructing the exception is not
     * raising it: the instance is built so its rendered image can be emitted, exactly as the legacy
     * populated the context area before transmitting it, and only then is it thrown.
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
     * <p>This reproduces the status-display paragraph that the batch members perform immediately
     * before their abend paragraph. Eight of the nine abending batch members carry one, and they do
     * not agree on its name: six call it {@code 9910-DISPLAY-IO-STATUS}
     * ({@code app/cbl/CBACT01C.cbl} line 176, {@code CBACT02C} line 161, {@code CBACT03C} line 161,
     * {@code CBACT04C} line 635, {@code CBTRN02C} line 714 and {@code CBTRN03C} line 633) while two
     * call it {@code Z-DISPLAY-IO-STATUS} ({@code app/cbl/CBCUS01C.cbl} line 161 and
     * {@code app/cbl/CBTRN01C.cbl} line 476). The ninth, {@code CBSTM03A}, has no such paragraph
     * because it inspects a subprogram return code rather than a file status. The naming
     * inconsistency is a source artefact with no behavioural consequence -- the two variants are
     * otherwise identical routines -- so the translation gives it a single Java name and records the
     * divergence in the project decision log.
     *
     * <p>It exists separately from the abend entry points because the legacy separated them: the
     * status display is performed on its own wherever the program intends to <em>continue</em>, and
     * is performed immediately before the abend paragraph wherever it does not. A caller that has
     * decided to carry on can therefore still emit the diagnostic, which is the whole point of
     * keeping this method non-raising.
     *
     * <p>The status is logged verbatim under the legacy operator prefix, which the exception type
     * publishes as {@code FileStatusException.DISPLAY_PREFIX}, so a Java log line carries the same
     * leading text an operator already recognises. The status is neither normalised nor validated:
     * it is reported exactly as it arrived, and the resolved vocabulary name is added beside it only
     * as a reading aid.
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
     * Emits the diagnostic for a failed file operation and abends on the batch tier, chaining the
     * raw status as the cause.
     *
     * <p>This is the whole of the legacy file-failure sequence in one call: the operation
     * diagnostic, the raw status, and the abend, in that order. It is the convenience form of
     * {@link #abendBatch(String, String, String, String, String)} for the common case where the
     * trigger is a file status, and it differs from that method in one respect that matters -- the
     * raised abend carries a {@code FileStatusException} as its cause, so the raw two-character
     * status survives on the exception chain and not only in the log.
     *
     * <p>The status is validated before anything is constructed. A status of
     * {@value FileStatusException#STATUS_SUCCESS} reports success and a status of
     * {@value FileStatusException#STATUS_END_OF_FILE} reports end of file; neither is an error, and
     * end of file in particular is how every sequential read loop in the batch tier terminates
     * normally. Passing either is a programming fault rather than a runtime condition, so it is
     * rejected here with the same {@code IllegalArgumentException} the exception type itself raises,
     * rather than being allowed to construct an invalid instance. Rejecting it in this method keeps
     * the failure attributable to the misuse instead of surfacing from inside a constructor.
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
     * <p>Every abend entry point calls this before it raises, and nothing else in the module logs an
     * abend, so the ordering is a property of this class rather than a convention callers have to
     * remember. One structured line carries every field, because the legacy emitted the operation
     * text, the raw status and the abend announcement as consecutive displays that an operator read
     * together; splitting them across lines would let an interleaved log separate them.
     *
     * <p>The line opens with the literal the batch members display immediately before abending, so
     * that an operator searching for the phrase they already know still finds the event. The abend
     * code, the culprit and the reason are always present because every abend has all three. The
     * I/O context is appended only when the caller actually supplied it, which is the convention the
     * file-status exception already follows in composing its own detail message: a label with
     * nothing after it tells a reader less than its absence does, and the online tier legitimately
     * has no file status, no operation description and no resource name at all.
     *
     * <p>Composed rather than parameterised precisely because the field set is conditional, and
     * guarded by a level check so nothing is composed when nobody is listening. The composed value
     * is passed as the single-argument message, which SLF4J takes verbatim without substitution, so
     * a brace occurring inside a caller's own text cannot corrupt the line.
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
     * <p>Called on the online path only, because only the online members carry that area. The image
     * is emitted at DEBUG rather than at ERROR because the preceding ERROR line already carries all
     * four field values in a readable form; what this adds is the byte-exact image, including the
     * space padding, for a reader comparing Java output against a mainframe screen capture. The
     * level check avoids rendering the image at all when nobody is listening.
     *
     * <p>The image and its length both come from the exception, which owns the four legacy widths as
     * constants. No width, offset or padding rule is restated here: that knowledge belongs to the
     * exception and to the fixed-width utilities, not to a service.
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
     * <p>Deliberately factual and deliberately short. It is not an external-contract literal: the
     * reason field is only ever transmitted on the online tier, and no online program in the estate
     * has a file status, so no legacy text exists for this combination to be faithful to. Inventing
     * operator prose here would be feature expansion, so the value is the labelled status and
     * nothing more. It is comfortably inside the legacy reason width, because the status is always
     * exactly two characters.
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
     * <p>The lookup is a total function over every possible input: {@code null} renders as the
     * not-supplied marker and an unrecognised value renders as the outside-vocabulary marker, so no
     * status can make this diagnostic throw. That tolerance is taken from the source rather than
     * chosen for safety's sake -- the legacy status-display paragraph has an explicit branch for a
     * status that is not numeric or whose first character is {@code 9}, which is exactly the case of
     * a value outside the vocabulary the estate tests.
     *
     * <p>Nothing branches on the resolved value and nothing is normalised by it. The raw status is
     * always logged alongside it and remains the authoritative value.
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
     * The single test for whether a caller actually supplied a piece of optional context.
     *
     * <p>Blank counts as absent as well as {@code null}, because the legacy context fields are
     * initialised to spaces and a space-filled field carries no more information than an unset one.
     * Every rendering decision in this class routes through here, so a value cannot be treated as
     * present in one log line and absent in another.
     *
     * @param value the value to test, possibly {@code null}
     * @return {@code true} when the value is non-{@code null} and not blank
     */
    private static boolean isSupplied(String value) {
        return value != null && !value.isBlank();
    }
}
