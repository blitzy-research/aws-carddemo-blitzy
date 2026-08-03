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
        logAbendDiagnostic(AbendException.ONLINE_ABEND_CODE, programName, reason, null, null, null);
        AbendException abend = new AbendException(AbendException.ONLINE_ABEND_CODE, programName,
                reason, terminalMessage, null);
        emitContextImage(abend);
        throw abend;
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

    private static void appendIfSupplied(StringBuilder target, String label, String value) {
        if (isSupplied(value)) {
            target.append(label).append(value);
        }
    }

    private static void emitContextImage(AbendException abend) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("abendContext=[{}] contextLength={}",
                    abend.toFixedWidthContext(), AbendException.CONTEXT_LENGTH);
        }
    }

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
