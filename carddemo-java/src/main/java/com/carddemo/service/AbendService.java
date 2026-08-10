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
 * The single abend path of the migrated estate: it emits the operator diagnostic, then raises.
 *
 * <p>Thirteen legacy abend sites, two tiers, one service. Nine are batch calls to {@code CEE3ABD}
 * from a paragraph named {@code 9999-ABEND-PROGRAM}; four are online CICS {@code ABEND} commands
 * from a paragraph named {@code ABEND-ROUTINE}, all four in the five-program family that includes the
 * attention-key and screen work-area copybooks. The fifth member of that family sends no abend
 * because it comments out its inclusion of the abend-context copybook and so has no context to send.
 * The estate's other twelve online programs never abend, and wiring them here would invent behaviour
 * the legacy does not have - which is why there is no third entry point.
 *
 * <p>Emit-then-abend is the contract rather than a convenience. Every legacy site emits its
 * diagnostic <em>before</em> abending, so every method here logs and then throws in the same body,
 * never from a caller's {@code catch}: on a mainframe the diagnostic reached the operator whether or
 * not anything survived the abend, and a catch-based equivalent would lose precisely the diagnostic
 * the operator needs while the run is failing. The batch members display {@code ABENDING PROGRAM}
 * immediately before the call, and the two that carry a file status display that status first; the
 * online routine transmits the whole context to the terminal, deregisters the handler, and only then
 * abends.
 *
 * <p>The 134-byte context declared by {@code app/cpy/CSMSG02Y.cpy} is not restated here: this service
 * populates {@link AbendException}, which owns the widths and renders the image, so the layout is
 * declared in exactly one place. The culprit field is eight characters - exactly a COBOL member
 * name - so callers pass the legacy member name they are translating, and a Java log line and a
 * mainframe abend then name the same culprit.
 *
 * <p>The diagnostic vocabulary belongs to the caller: operation descriptions are the legacy display
 * literals each site chose and resource names are the legacy DD, dataset and CICS file names, so
 * every one arrives as a parameter and none is declared here.
 *
 * <p>What this service deliberately does not do: it never terminates the process and holds no return
 * code, because translating the batch tier's completion code belongs to the job and step layer; and
 * it models no program cancellation, because every {@code CANCEL} token in the estate is either the
 * CICS handler-deregistration option or comment prose, never the COBOL statement, and the equivalent
 * of deregistering a handler in a Spring runtime is simply the absence of a {@code catch}.
 *
 * <p>Stateless and immutable, so one container-managed instance is safely shared across both tiers.
 */
@Service
public final class AbendService {
    private static final Logger LOG = LoggerFactory.getLogger(AbendService.class);

    private static final String ABENDING_PROGRAM = "ABENDING PROGRAM";

    private static final String NOT_SUPPLIED = "(none)";

    private static final String OUTSIDE_VOCABULARY = "(outside declared vocabulary)";

    public AbendService() {
    }

    /**
     * Abends the batch tier: logs the diagnostic, then raises.
     *
     * @param programName the legacy member name to name as the culprit, eight characters
     * @param reason the legacy display literal describing why the run is failing
     */
    public void abendBatch(String programName, String reason) {
        abendBatch(programName, reason, null, null, null);
    }

    /**
     * Abends the batch tier after a failed file operation, displaying the raw two-byte file status
     * before the abend diagnostic exactly as the two legacy members that carry a status do.
     *
     * @param programName the legacy member name to name as the culprit
     * @param reason the legacy display literal describing why the run is failing
     * @param rawFileStatus the raw two-character file status, logged before the abend
     * @param operation the legacy description of the operation that failed
     * @param resourceName the legacy DD, dataset or CICS file name
     */
    public void abendBatch(String programName, String reason, String rawFileStatus,
            String operation, String resourceName) {
        logAbendDiagnostic(AbendException.BATCH_ABEND_CODE, programName, reason, rawFileStatus,
                operation, resourceName);
        throw new AbendException(AbendException.BATCH_ABEND_CODE, programName, reason,
                AbendException.DEFAULT_MESSAGE, null);
    }

    /**
     * Abends the online tier: logs the diagnostic, then raises.
     *
     * @param programName the legacy member name to name as the culprit
     * @param reason the legacy display literal describing why the transaction is failing
     */
    public void abendOnline(String programName, String reason) {
        abendOnline(programName, reason, AbendException.DEFAULT_MESSAGE);
    }

    /**
     * Abends the online tier carrying the terminal message the legacy routine transmitted before
     * issuing its abend.
     *
     * @param programName the legacy member name to name as the culprit
     * @param reason the legacy display literal describing why the transaction is failing
     * @param terminalMessage the text the legacy routine sent to the terminal
     */
    public void abendOnline(String programName, String reason, String terminalMessage) {
        throw onlineAbend(programName, reason, terminalMessage);
    }

    /**
     * Records the online abend diagnostic and returns the failure for the caller to raise.
     *
     * <h2>Why a method that returns the exception rather than throwing it</h2>
     *
     * <p>Three online abend sites cannot call {@link #abendOnline} and never did: they sit inside
     * {@code Optional.orElseThrow} suppliers and inside a lookup that has to return, so they need the
     * exception as a value. Before this method existed they built it themselves, which is why the online
     * abend surface had three diagnostics and one of them was silence - {@code MenuService} logged nothing
     * at all before raising, so a menu dispatch that abended left no record naming the culprit or the
     * reason. Handing back the exception is what lets every one of those sites keep its shape and still
     * produce the one record.
     *
     * <p>The legacy contract is unchanged and is the reason the order is fixed: the diagnostic is emitted
     * before the failure is raised, exactly as the legacy routine displayed before abending, so a caller
     * that throws what this returns preserves the ordering it always had.
     *
     * <p>See {@code docs/decision-log.md} entry DL-312.
     *
     * @param  programName the legacy member name to name as the culprit
     * @param  reason the legacy display literal describing why the transaction is failing
     * @param  terminalMessage the text the legacy routine sent to the terminal
     * @return the failure to raise, already recorded
     */
    public static AbendException onlineAbend(String programName, String reason,
            String terminalMessage) {
        return onlineAbend(programName, reason, terminalMessage, null, null);
    }

    /**
     * Records the online abend diagnostic with the two optional legacy fields, and returns the failure.
     *
     * <p>The operation and resource fields carry what the legacy display carried beside the culprit: the
     * transfer rule being applied, and the destination that could not be resolved. They are the reason a
     * caller that already had a richer diagnostic of its own loses nothing by routing through here.
     *
     * @param  programName the legacy member name to name as the culprit
     * @param  reason the legacy display literal describing why the transaction is failing
     * @param  terminalMessage the text the legacy routine sent to the terminal
     * @param  operation the legacy description of the operation that failed, or {@code null}
     * @param  resourceName the legacy destination or resource name, or {@code null}
     * @return the failure to raise, already recorded
     */
    public static AbendException onlineAbend(String programName, String reason,
            String terminalMessage, String operation, String resourceName) {
        logAbendDiagnostic(AbendException.ONLINE_ABEND_CODE, programName, reason, null, operation,
                resourceName);
        return new AbendException(AbendException.ONLINE_ABEND_CODE, programName, reason,
                terminalMessage, null);
    }

    /**
     * Records the online abend naming a <em>description</em> of the culprit, and returns the failure
     * carrying the culprit itself.
     *
     * <h2>Why the record and the exception name the culprit differently here</h2>
     *
     * <p>One online abend site names a culprit this module did not author: the navigation transfer whose
     * destination could not be resolved names the destination a caller nominated, bounded to the legacy
     * field width. That value must reach the exception, because the legacy abend area carries the name that
     * failed and a caller-facing failure is where it belongs. It must not reach a log record, and eight
     * characters is enough to explain why: this module's records are read as space-separated
     * {@code key=value} pairs, and {@code route=CA} fits inside the legacy field width, so a nomination can
     * imitate a field of the record it appears in. A separator or a control character inside the same eight
     * characters can end the record early and present its remainder as a second, invented entry.
     *
     * <p>Refusing only the dangerous characters would close the splitting attack and leave the forging one
     * open, so no caller text is published at all: the caller supplies the culprit, and the caller's own
     * site supplies a fixed-shape description of it - its length, or the position and code point of the
     * first character that is not printable. That description is what the record names.
     *
     * <p>Every other abend site names a culprit it authored, so for those sites the description and the
     * value are the same thing and {@link #onlineAbend} is the method to use. This overload exists for the
     * one site where they differ, and its name says so rather than leaving a reader to notice.
     *
     * <p>See {@code docs/decision-log.md} entry DL-312.
     *
     * @param  programName the culprit the exception carries, which may be caller-derived
     * @param  describedCulprit the fixed-shape description of that culprit, which the record names; must
     *                          contain no caller-supplied text
     * @param  reason the legacy display literal describing why the transaction is failing
     * @param  terminalMessage the text the legacy routine sent to the terminal
     * @param  operation the legacy description of the operation that failed, or {@code null}
     * @return the failure to raise, already recorded
     */
    public static AbendException onlineAbendWithDescribedCulprit(String programName,
            String describedCulprit, String reason, String terminalMessage, String operation) {
        logAbendDiagnostic(AbendException.ONLINE_ABEND_CODE, describedCulprit, reason, null, operation,
                null);
        return new AbendException(AbendException.ONLINE_ABEND_CODE, programName, reason,
                terminalMessage, null);
    }

    /**
     * Emits the file-status diagnostic without abending: the legacy display that precedes the decision
     * to abend, for a caller that has not yet made it.
     *
     * @param rawFileStatus the raw two-character file status
     * @param operation the legacy description of the operation that reported it
     * @param resourceName the legacy DD, dataset or CICS file name
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
     * Abends on a file status, refusing the two values that are not errors: success and end-of-file are
     * normal outcomes, and folding either into the abend path would erase the distinction the legacy
     * read loops depend on.
     *
     * @param programName the legacy member name to name as the culprit
     * @param rawFileStatus the raw two-character file status, which must be neither success nor
     *                      end-of-file
     * @param operation the legacy description of the operation that failed
     * @param resourceName the legacy DD, dataset or CICS file name
     */
    public void abendOnFileStatus(String programName, String rawFileStatus, String operation,
            String resourceName) {
        rejectNonErrorStatus(rawFileStatus, programName, operation, resourceName);
        abendOnFileStatus(programName,
                new FileStatusException(rawFileStatus, operation, resourceName));
    }

    /**
     * Abends on a file status already carried by a {@link FileStatusException}, preserving the
     * log-then-raise ordering rather than logging from a {@code catch} that has unwound past it.
     *
     * @param programName the legacy member name to name as the culprit
     * @param failure the carried status, operation and resource name
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

    private static void logAbendDiagnostic(String abendCode, String programName, String reason,
            String rawFileStatus, String operation, String resourceName) {
        if (!LOG.isErrorEnabled()) {
            return;
        }
        StringBuilder line = new StringBuilder();
        line.append(ABENDING_PROGRAM)
                .append(" abendCode=").append(fieldSafe(abendCode))
                .append(" culprit=").append(fieldSafe(programName))
                .append(" reason=").append(fieldSafe(reason));
        appendIfSupplied(line, " operation=", operation);
        appendIfSupplied(line, " resource=", resourceName);
        if (isSupplied(rawFileStatus)) {
            line.append(" fileStatus=").append(fieldSafe(rawFileStatus))
                    .append(" statusName=").append(fieldSafe(statusMnemonic(rawFileStatus)));
        }
        LOG.error(line.toString());
    }

    private static void appendIfSupplied(StringBuilder target, String label, String value) {
        if (isSupplied(value)) {
            target.append(label).append(fieldSafe(value));
        }
    }

    /**
     * Renders one field of the abend record so that a value this module did not author cannot change the
     * record's shape.
     *
     * <h2>Why this is needed here and was not needed before</h2>
     *
     * <p>Every value that reached this record used to be a literal or a two-character status from a closed
     * set. That stopped being true when the navigation abend was routed through this record, because the
     * culprit it names is a caller-supplied program nomination bounded to the legacy field width - the one
     * value on the abend surface whose content a caller chooses.
     *
     * <p>Two shapes are refused, and both are attacks on the reader rather than on this process. A value
     * containing a character that is not printable US-ASCII can end the record early and present its
     * remainder as a second, invented entry, so a carriage return inside an eight-character field
     * manufactures a log line. A value containing {@code =} can imitate one of this record's own fields,
     * and eight characters is enough: {@code route=CA} inside the culprit reads as a route assignment to
     * anything parsing space-separated pairs. Neither is possible for a value this module authored, which
     * is exactly why the check costs nothing on every other path.
     *
     * <p>A refused value is replaced by its length rather than dropped. The length is what distinguishes a
     * padded or truncated field from a misspelled one, and it publishes nothing the caller chose. The value
     * itself still travels on the exception, where it is structured data on a value object rather than
     * syntax in a log record.
     *
     * <p>A plain value passes through byte for byte, so every record this class wrote before this method
     * existed is unchanged. See {@code docs/decision-log.md} entry DL-312.
     *
     * @param  value the field value, possibly {@code null}
     * @return the value when it is plain, the not-supplied marker when it is absent, and a fixed-shape
     *         description carrying only its length when it is neither
     */
    private static String fieldSafe(String value) {
        if (!isSupplied(value)) {
            return NOT_SUPPLIED;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '=' || character < ' ' || character > '~') {
                return "<not a plain field: length " + value.length() + ">";
            }
        }
        return value;
    }

    /*
     * THE FIXED-WIDTH CONTEXT IMAGE IS DELIBERATELY NOT LOGGED, and its absence here is the fix rather
     * than an omission.
     *
     * An earlier version of this class emitted AbendException#toFixedWidthContext at debug level, which
     * rendered the whole 134-character legacy abend area into one log record: the four-character code, the
     * eight-character culprit, the fifty-character reason and - the reason it had to go - the
     * seventy-two-character operator message slot. That last field is the only part of the area whose
     * content is not drawn from a fixed vocabulary this module owns. It is the text a caller-facing failure
     * carries, so a single debug switch turned the abend path into a channel that copies an arbitrary
     * seventy-two-character value into the log stream, in a format built for a 3270 screen rather than for
     * a reader.
     *
     * What replaces it is the allow-listed record logAbendDiagnostic already writes: the abend code, the
     * culprit, the reason, and where they exist the operation, the resource and the raw file status with
     * its mnemonic. Every one of those is a value this module authored or a two-character status from a
     * closed set. Nothing is lost by the removal that the structured record does not already say, and the
     * image itself remains available on the exception, where it is structured data on a value object rather
     * than text in a log record - which is where the legacy transmission of that area belongs.
     *
     * See docs/decision-log.md entry DL-312.
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

    private static String abendReasonFor(String rawFileStatus) {
        return "FILE STATUS " + rawFileStatus;
    }

    private static String statusMnemonic(String rawFileStatus) {
        if (rawFileStatus == null) {
            return NOT_SUPPLIED;
        }
        Optional<FileStatus> resolved = FileStatus.fromCode(rawFileStatus);
        return resolved.map(FileStatus::name).orElse(OUTSIDE_VOCABULARY);
    }

    private static String orMarker(String value) {
        if (isSupplied(value)) {
            return value;
        }
        return NOT_SUPPLIED;
    }

    private static boolean isSupplied(String value) {
        return value != null && !value.isBlank();
    }
}
