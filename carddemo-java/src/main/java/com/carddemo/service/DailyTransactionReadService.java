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

import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.DailyTransactionRepository;
import com.carddemo.util.BatchCancellation;
import com.carddemo.util.BoundedKeysetIterator;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.SensitiveLogRedactor;

/**
 * The daily-transaction extract-and-verify pass: it walks the daily-transaction input in order and,
 * for each record, resolves the card number through the cross-reference and then reads the account
 * the cross-reference names, reporting every outcome as a diagnostic.
 *
 * <p><strong>This is the estate's orphan, and it is translated in full.</strong> The sole authority is
 * {@code app/cbl/CBTRN01C.cbl} - 491 lines, 18 paragraphs, its abend call at line 473 - and
 * <em>no</em> member of {@code app/jcl}, <em>no</em> cataloged procedure in {@code app/proc} and
 * <em>no</em> entry in {@code app/csd/CARDDEMO.CSD} names that program anywhere in the estate. It
 * appears in no job step: it is a complete, syntactically valid, fully formed batch program that
 * nothing invokes. Being unwired is a property of the wiring and not of the logic, so the logic is
 * carried across paragraph for paragraph; the corresponding job configuration in
 * {@code com.carddemo.batch} is <em>defined but unwired</em> - excluded from the default pipeline and
 * exercised only by tests. Consequently <strong>this service's only exercise path is its tests</strong>.
 * The asymmetry with the estate's one deliberately unmigrated artefact is intentional and both halves
 * are recorded in {@code docs/decision-log.md}: executable logic is migrated even when nothing calls
 * it, whereas a data copybook with zero inclusions anywhere is not migrated at all.
 *
 * <p><strong>Six files opened, six closed, three ever read.</strong> The legacy program opens
 * {@code DALYTRAN} sequentially and {@code CUSTFILE}, {@code XREFFILE}, {@code CARDFILE},
 * {@code ACCTFILE} and {@code TRANFILE} for random access, then closes all six - but it reads only
 * three of them: the daily-transaction input at line 203, the cross-reference by card number at line
 * 229 and the account by account identifier at line 243. {@code CUSTFILE}, {@code CARDFILE} and
 * {@code TRANFILE} are opened and closed and never read. Their paragraphs are preserved with their
 * full branch structure rather than dropped, because dropping them would lose four of the eighteen
 * traceability units; what the relational target cannot reproduce is a failure to open a file it has
 * no gateway for, and that is stated on each of those methods rather than hidden.
 *
 * <p><strong>The program writes nothing.</strong> There is no {@code WRITE} and no {@code REWRITE} in
 * any of the 491 lines, so there is no write boundary here and nothing is ever saved: the single
 * transaction this service opens is read-only. There is likewise no {@code COMPUTE}; the only
 * arithmetic in the member is {@code ADD 8 TO ZERO GIVING APPL-RESULT}, which sets an internal result
 * flag and touches no monetary field. No behaviour has been invented to fill that space.
 *
 * <p><strong>The read loop re-verifies its last record, and that is reproduced.</strong> In
 * {@code MAIN-PARA} the verification block at lines 170 to 184 sits <em>outside</em> the
 * end-of-file test at line 167, which guards only the record display at
 * line 168. A COBOL {@code READ INTO} leaves its receiving item unchanged at end of file, so on the
 * end-of-file iteration the block runs once more against the record area as the previous read left
 * it, verifying the last record a second time. That is what the program does, so it is what this
 * service does. It is visible rather than silent: the extra pass is counted in
 * {@code verificationPasses} and marked {@code afterEndOfFile} on the verification it produces, while
 * {@code recordsRead} and {@code recordsVerified} count only genuine reads and therefore match an
 * input's record count exactly.
 *
 * <p><strong>End of file is never an error.</strong> The legacy tier normalises a raw two-byte file
 * status into a coarse result and branches on that coarse value, not on the raw code. The read at
 * lines 204 to 212 is three-way - {@code "00"} to success, {@code "10"} to end of file, anything else
 * to error - while all twelve open and close paragraphs are two-way, {@code "00"} to success and
 * anything else to error, with no end-of-file arm at all, so a {@code "10"} reported by an open is an
 * error there and not a normal outcome. The two shapes are kept apart in
 * {@code normaliseReadStatus} and {@code normaliseOpenOrCloseStatus}. Collapsing end of file into
 * error would turn this program's normal completion into an abend, which is why the distinction is
 * load-bearing rather than cosmetic. Only the status values the source actually compares are
 * recognised; the two values that earlier documentation cites but no source member compares are not
 * referenced here and no code path depends on them.
 *
 * <p><strong>Why the coarse result is mirrored privately.</strong> The batch step template owns the
 * shared tri-state, but it declares it {@code protected} inside a class that implements a Spring
 * Batch type, in a package this layer must not import; the sibling file-maintenance service exposes
 * none. Rather than publish a competing abstraction, the private nested {@code ApplResult} below
 * mirrors this member's own {@code APPL-RESULT} working-storage field and its two level-88 condition
 * names, {@code APPL-AOK} and {@code APPL-EOF}, carrying the same normalised values the estate moves
 * for them. It is private and named after the field it mirrors precisely so that it cannot become a
 * third general-purpose representation of the tri-state.
 *
 * <p><strong>Emit, then abend.</strong> Every failing I/O site in the member displays its own error
 * literal, moves the raw status into the display field, displays that status and only then calls
 * {@code CEE3ABD}. That order is the contract, because on a mainframe the diagnostic reached the
 * operator whether or not anything survived the abend. Each error arm here therefore logs the legacy
 * literal, then emits the raw two-byte status, then raises through {@code AbendService}, which
 * supplies the batch abend code from {@code AbendException}'s own constant - the constant whose value
 * is exactly the {@code 999} the member moves into {@code ABCODE} at line 472.
 *
 * <p><strong>A source defect is preserved rather than corrected.</strong>
 * {@code 9000-DALYTRAN-CLOSE} tests the daily-transaction status at line 364 but its error arm
 * displays {@code ERROR CLOSING CUSTOMER FILE} at line 372 and reports the <em>customer</em> file's
 * status at line 373 - the neighbouring customer-close paragraph carries the identical literal, so
 * this is a copy of it. The diagnostic is externally observable, so it is reproduced exactly and
 * raised for {@code docs/decision-log.md} rather than quietly repaired.
 *
 * <p>Stateless and immutable: every collaborator is injected through the constructor and the class
 * holds no mutable field, so one container-managed instance is safely shared. The counters, flags,
 * per-record statuses and record area that the legacy program keeps in working storage live in a
 * holder created fresh for each invocation, which is what lets two callers run concurrently without
 * observing one another.
 */
@Service
@Transactional(readOnly = true)
public class DailyTransactionReadService {
    private static final Logger LOG = LoggerFactory.getLogger(DailyTransactionReadService.class);

    /** The legacy member name, eight characters, named as the culprit of any abend raised here. */
    private static final String PROGRAM_NAME = "CBTRN01C";

    /** JPA property backing the daily-transaction record identity, used to order the sequential scan. */
    private static final int KEYSET_PAGE_SIZE = BoundedKeysetIterator.DEFAULT_PAGE_SIZE;

    private static final String DD_DALYTRAN = "DALYTRAN";

    private static final String DD_CUSTFILE = "CUSTFILE";

    private static final String DD_XREFFILE = "XREFFILE";

    private static final String DD_CARDFILE = "CARDFILE";

    private static final String DD_ACCTFILE = "ACCTFILE";

    private static final String DD_TRANFILE = "TRANFILE";

    private static final String OPERATION_OPEN = "OPEN INPUT";

    private static final String OPERATION_READ = "READ";

    private static final String OPERATION_CLOSE = "CLOSE";

    private static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBTRN01C";

    private static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBTRN01C";

    private static final String ERROR_READING_DALYTRAN = "ERROR READING DAILY TRANSACTION FILE";

    private static final String ERROR_OPENING_DALYTRAN = "ERROR OPENING DAILY TRANSACTION FILE";

    private static final String ERROR_OPENING_CUSTFILE = "ERROR OPENING CUSTOMER FILE";

    private static final String ERROR_OPENING_XREFFILE = "ERROR OPENING CROSS REF FILE";

    private static final String ERROR_OPENING_CARDFILE = "ERROR OPENING CARD FILE";

    private static final String ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCOUNT FILE";

    private static final String ERROR_OPENING_TRANFILE = "ERROR OPENING TRANSACTION FILE";

    private static final String ERROR_CLOSING_CUSTFILE = "ERROR CLOSING CUSTOMER FILE";

    private static final String ERROR_CLOSING_XREFFILE = "ERROR CLOSING CROSS REF FILE";

    private static final String ERROR_CLOSING_CARDFILE = "ERROR CLOSING CARD FILE";

    private static final String ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCOUNT FILE";

    private static final String ERROR_CLOSING_TRANFILE = "ERROR CLOSING TRANSACTION FILE";

    private static final String INVALID_CARD_NUMBER_FOR_XREF = "INVALID CARD NUMBER FOR XREF";

    private static final String SUCCESSFUL_READ_OF_XREF = "SUCCESSFUL READ OF XREF";

    private static final String SUCCESSFUL_READ_OF_ACCOUNT_FILE = "SUCCESSFUL READ OF ACCOUNT FILE";

    private static final String INVALID_ACCOUNT_NUMBER_FOUND = "INVALID ACCOUNT NUMBER FOUND";

    private static final String ACCOUNT_NOT_FOUND =
            "ACCOUNT RECORD NOT FOUND FOR RESOLVED CROSS-REFERENCE";

    private static final String CARD_NOT_VERIFIED =
            "CARD NUMBER COULD NOT BE VERIFIED; SKIPPING DAILY TRANSACTION RECORD";

    private static final String REDACTED_CARD_NUMBER = "***REDACTED***";

    private static final String DALYTRAN_RECORD_READ =
            "DALYTRAN-RECORD read fileStatus=00";

    /** {@code WS-XREF-READ-STATUS} and {@code WS-ACCT-READ-STATUS} after a successful keyed read. */
    private static final int READ_STATUS_OK = 0;

    /** The value both keyed-read paragraphs move on their {@code INVALID KEY} arm. */
    private static final int READ_STATUS_INVALID_KEY = 4;

    private final DailyTransactionRepository dailyTransactionRepository;

    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    private final AccountRepository accountRepository;

    private final AbendService abendService;

    /**
     * Creates the service with the three gateways the member reads through and the estate's abend path.
     *
     * @param dailyTransactionRepository gateway for the {@code DALYTRAN} sequential input
     * @param cardCrossReferenceRepository gateway for the {@code XREFFILE} keyed read
     * @param accountRepository gateway for the {@code ACCTFILE} keyed read
     * @param abendService the estate's single abend path, which emits before it raises
     */
    public DailyTransactionReadService(
            final DailyTransactionRepository dailyTransactionRepository,
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final AccountRepository accountRepository,
            final AbendService abendService) {
        this.dailyTransactionRepository = Objects.requireNonNull(dailyTransactionRepository,
                "dailyTransactionRepository must not be null");
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository must not be null");
        this.abendService = Objects.requireNonNull(abendService, "abendService must not be null");
    }

    /**
     * Runs the extract pass over the whole daily-transaction input, resolving the ordered source
     * itself.
     *
     * <p>This is the member as a job step would have driven it, had any job stream driven it: the six
     * opens, the read loop, the six closes. The scan is ordered explicitly by record identity
     * ascending, because the legacy read is a physical sequential read of a sequential dataset and the
     * repository imposes no ordering of its own; identity ascending is the deterministic relational
     * equivalent.
     *
     * @param verificationSink the destination each per-record outcome is offered to, as it is
     *                         produced; must not be {@code null}
     * @return the counts and the terminal result value
     * @throws NullPointerException if {@code verificationSink} is {@code null}
     * @throws com.carddemo.exception.AbendException if any file operation reports a status the member
     *         treats as an error, after the diagnostic and the raw status have been emitted
     */
    public DailyTransactionReadResult execute(
            final Consumer<DailyTransactionVerification> verificationSink) {
        return mainPara(null, verificationSink, Thread.currentThread()::isInterrupted);
    }

    /**
     * Runs the repository-backed pass while observing a cooperative stop probe.
     *
     * @param verificationSink the destination each per-record outcome is offered to
     * @param stopRequested live stop probe
     * @return the completed pass
     */
    public DailyTransactionReadResult execute(
            final Consumer<DailyTransactionVerification> verificationSink,
            final BooleanSupplier stopRequested) {
        return mainPara(null, verificationSink,
                Objects.requireNonNull(stopRequested, "stopRequested must not be null"));
    }

    /**
     * Runs the extract pass over an ordered source the caller supplies.
     *
     * <p>The order is the caller's: this method walks the records exactly as given, because the legacy
     * read returns them in the order the dataset holds them and imposes no sort of its own.
     *
     * <p>The source is walked once, forward only, and one record is in hand at a time. A caller that
     * streams a staged dataset therefore never materialises it; see {@code docs/decision-log.md} entry
     * DL-176.
     *
     * @param orderedDailyTransactions the records to walk, in order; may be empty but not {@code null}
     * @param verificationSink the destination each per-record outcome is offered to, as it is
     *                         produced; must not be {@code null}
     * @return the counts and the terminal result value
     * @throws NullPointerException if either argument is {@code null}
     * @throws com.carddemo.exception.AbendException if any file operation reports a status the member
     *         treats as an error, after the diagnostic and the raw status have been emitted
     */
    public DailyTransactionReadResult execute(
            final Iterable<DailyTransaction> orderedDailyTransactions,
            final Consumer<DailyTransactionVerification> verificationSink) {
        return execute(orderedDailyTransactions, verificationSink,
                Thread.currentThread()::isInterrupted);
    }

    /**
     * Runs the ordered pass while observing a cooperative stop probe between records.
     *
     * @param orderedDailyTransactions ordered input
     * @param verificationSink the destination each per-record outcome is offered to
     * @param stopRequested live stop probe
     * @return the completed pass
     */
    public DailyTransactionReadResult execute(
            final Iterable<DailyTransaction> orderedDailyTransactions,
            final Consumer<DailyTransactionVerification> verificationSink,
            final BooleanSupplier stopRequested) {
        Objects.requireNonNull(orderedDailyTransactions,
                "orderedDailyTransactions must not be null");
        return mainPara(orderedDailyTransactions, verificationSink,
                Objects.requireNonNull(stopRequested, "stopRequested must not be null"));
    }

    /**
     * Verifies one record: the cross-reference lookup and, when that succeeds, the account read.
     *
     * <p>This is the block {@code MAIN-PARA} performs at lines 170 to 184 for each record, exposed on
     * its own so a caller can verify a single record without driving the whole pass. It never throws
     * for a record that cannot be verified: a card number the cross-reference does not hold and an
     * account identifier the account file does not hold are both ordinary outcomes reported in the
     * returned verification, exactly as the legacy {@code INVALID KEY} arms report them.
     *
     * @param sourceRecord the record to verify; {@code null} is accepted and behaves as the legacy record
     *               area does before any read has filled it, yielding an unverified outcome
     * @return the outcome for this record
     */
    public DailyTransactionVerification verify(final DailyTransaction sourceRecord) {
        return verifyRecord(sourceRecord, false, new RunState());
    }

    /**
     * {@code MAIN-PARA} - lines 155 to 197.
     *
     * <p>Opens all six files in declaration order, drives the read loop, closes all six in the same
     * order and returns. An error on any of those twelve operations abends from within the paragraph
     * that reported it, so the closes do not run - which is faithful, because the legacy call to
     * {@code CEE3ABD} terminated the run where it stood.
     *
     * @param suppliedSource the ordered records to walk, or {@code null} to resolve the scan from the
     *                       repository once the opens have completed, which is where the legacy
     *                       program issues its first read
     * @param verificationSink the destination each per-record outcome is offered to, as it is produced
     * @return the counts and the terminal result value
     */
    private DailyTransactionReadResult mainPara(
            final Iterable<DailyTransaction> suppliedSource,
            final Consumer<DailyTransactionVerification> verificationSink,
            final BooleanSupplier stopRequested) {
        LOG.info(START_OF_EXECUTION);
        Objects.requireNonNull(verificationSink, "verificationSink must not be null");
        final RunState state = new RunState();

        BatchCancellation.checkpoint(stopRequested);
        dalytranOpen(state);
        custfileOpen(state);
        xreffileOpen(state);
        cardfileOpen(state);
        acctfileOpen(state);
        tranfileOpen(state);

        final Iterator<DailyTransaction> cursor = openCursor(suppliedSource);
        while (!state.endOfDailyTransFile) {
            BatchCancellation.checkpoint(stopRequested);
            // Line 165 re-tests the flag the loop condition has already tested. It cannot be false
            // here, and it is kept because the source keeps it: a reader comparing the two should
            // find the same guard in the same place.
            if (!state.endOfDailyTransFile) {
                dalytranGetNext(cursor, state);
                if (!state.endOfDailyTransFile) {
                    displayDalytranRecord(state.recordArea);
                }
                // Lines 170 to 184 are deliberately OUTSIDE the test above. On the end-of-file
                // iteration they therefore run once more against the record area as the last
                // successful read left it, re-verifying the final record. See the class comment.
                // The outcome is offered to the caller's destination as it is produced. Nothing
                // accumulates here, so a pass over a large dataset costs one outcome rather than one
                // per record; see docs/decision-log.md entry DL-176.
                verificationSink.accept(
                        verifyRecord(state.recordArea, state.endOfDailyTransFile, state));
            }
        }

        dalytranClose(state);
        custfileClose(state);
        xreffileClose(state);
        cardfileClose(state);
        acctfileClose(state);
        tranfileClose(state);

        LOG.info(END_OF_EXECUTION);
        return state.toResult();
    }

    /**
     * The verification block of {@code MAIN-PARA} - lines 170 to 184.
     *
     * <p>Resets the cross-reference status, moves the record's card number into the lookup key, reads
     * the cross-reference and, only when that read succeeded, resets the account status, moves the
     * cross-reference's account identifier into the account key and reads the account. The order of
     * the two tests is the source's: the account is never read for a card the cross-reference could
     * not resolve.
     *
     * @param sourceRecord the record whose card number is verified; may be {@code null}
     * @param afterEndOfFile whether this pass is the one the loop makes after end of file, over the
     *                       record area the previous read left in place
     * @param state the working-storage holder for this invocation
     * @return the outcome for this pass
     */
    private DailyTransactionVerification verifyRecord(final DailyTransaction sourceRecord,
            final boolean afterEndOfFile, final RunState state) {
        state.verificationPasses++;
        if (!afterEndOfFile && sourceRecord != null) {
            state.recordsVerified++;
        }

        state.xrefReadStatus = READ_STATUS_OK;
        final String cardNumber = sourceRecord == null ? null : sourceRecord.getDalytranCardNum();
        final Optional<CardCrossReference> crossReference = lookupXref(cardNumber, state);

        if (state.xrefReadStatus == READ_STATUS_OK) {
            state.acctReadStatus = READ_STATUS_OK;
            final String accountId = crossReference
                    .map(CardCrossReference::getXrefAcctId)
                    .orElse(null);
            readAccount(accountId, state);
            if (state.acctReadStatus != READ_STATUS_OK) {
                LOG.warn(ACCOUNT_NOT_FOUND);
                state.accountsNotFound++;
            }
            return new DailyTransactionVerification(
                    sourceRecord == null ? null : sourceRecord.getDalytranId(),
                    cardNumber,
                    accountId,
                    state.xrefReadStatus,
                    state.acctReadStatus,
                    true,
                    afterEndOfFile);
        }

        LOG.warn(CARD_NOT_VERIFIED);
        LOG.warn("CARD NUMBER: {}",
                redactedCardNumber(cardNumber, REDACTED_CARD_NUMBER));
        state.cardsNotVerified++;
        return new DailyTransactionVerification(
                sourceRecord == null ? null : sourceRecord.getDalytranId(),
                cardNumber,
                null,
                state.xrefReadStatus,
                READ_STATUS_OK,
                false,
                afterEndOfFile);
    }

    /**
     * {@code 1000-DALYTRAN-GET-NEXT} - lines 202 to 225.
     *
     * <p>Reads the next record, normalises the raw status three ways and branches on the coarse
     * result: success continues, end of file raises the loop's terminating flag, and anything else
     * emits the read diagnostic, emits the raw status and abends. This is the one paragraph in the
     * member with an end-of-file arm, and it is the reason the arm must never be folded into the error
     * arm - doing so would abend on the normal completion of every run.
     *
     * @param cursor the ordered source being walked
     * @param state the working-storage holder for this invocation
     */
    private void dalytranGetNext(final Iterator<DailyTransaction> cursor, final RunState state) {
        final String rawStatus = readNextRecord(cursor, state);
        state.applResult = normaliseReadStatus(rawStatus);

        if (state.applResult.isAok()) {
            state.recordsRead++;
            return;
        }
        if (state.applResult.isEof()) {
            state.endOfDailyTransFile = true;
            return;
        }

        LOG.error(ERROR_READING_DALYTRAN);
        zDisplayIoStatus(rawStatus, OPERATION_READ, DD_DALYTRAN);
        zAbendProgram(ERROR_READING_DALYTRAN, rawStatus, OPERATION_READ, DD_DALYTRAN);
    }

    /**
     * {@code 2000-LOOKUP-XREF} - lines 227 to 239.
     *
     * <p>Reads the cross-reference by card number, which is that record's own identity, so the read is
     * a keyed read of the primary key rather than of an alternate-index path. The paragraph has an
     * {@code INVALID KEY} arm and a {@code NOT INVALID KEY} arm and no error arm at all, so a card
     * number the cross-reference does not hold sets the read status to the invalid-key value and
     * returns; it never abends and it never throws. The three fields the success arm displays are the
     * three the cross-reference record carries.
     *
     * @param cardNumber the card number to resolve; an absent value cannot match and takes the
     *                   invalid-key arm, as a key of spaces does in the legacy program
     * @param state the working-storage holder for this invocation
     * @return the resolved cross-reference, or empty on the invalid-key arm
     */
    private Optional<CardCrossReference> lookupXref(final String cardNumber, final RunState state) {
        final Optional<CardCrossReference> crossReference = isAbsent(cardNumber)
                ? Optional.empty()
                : this.cardCrossReferenceRepository.findById(cardNumber);

        if (crossReference.isEmpty()) {
            LOG.warn(INVALID_CARD_NUMBER_FOR_XREF);
            state.xrefReadStatus = READ_STATUS_INVALID_KEY;
            return crossReference;
        }

        final CardCrossReference resolved = crossReference.orElseThrow();
        LOG.info(SUCCESSFUL_READ_OF_XREF);
        LOG.info("CARD NUMBER: {}",
                redactedCardNumber(resolved.getXrefCardNum(), REDACTED_CARD_NUMBER));
        LOG.info("ACCOUNT ID : {}", SensitiveLogRedactor.redact(resolved.getXrefAcctId()));
        LOG.info("CUSTOMER ID: {}", SensitiveLogRedactor.redact(resolved.getXrefCustId()));
        return crossReference;
    }

    /**
     * {@code 3000-READ-ACCOUNT} - lines 241 to 250.
     *
     * <p>Reads the account by account identifier. Like the cross-reference paragraph it has only an
     * {@code INVALID KEY} arm and a {@code NOT INVALID KEY} arm, so an account the file does not hold
     * sets the read status and returns rather than abending. The legacy read names a record area whose
     * fields the program never goes on to use, so nothing is carried out of here but the status.
     *
     * @param accountId the account identifier to read; an absent value takes the invalid-key arm
     * @param state the working-storage holder for this invocation
     */
    private void readAccount(final String accountId, final RunState state) {
        if (isAbsent(accountId) || this.accountRepository.findById(accountId).isEmpty()) {
            LOG.warn(INVALID_ACCOUNT_NUMBER_FOUND);
            state.acctReadStatus = READ_STATUS_INVALID_KEY;
            return;
        }
        LOG.info(SUCCESSFUL_READ_OF_ACCOUNT_FILE);
    }

    /**
     * {@code 0000-DALYTRAN-OPEN} - lines 252 to 268. Opens the daily-transaction input.
     *
     * @param state the working-storage holder for this invocation
     */
    private void dalytranOpen(final RunState state) {
        openInput(state, this.dailyTransactionRepository, ERROR_OPENING_DALYTRAN, DD_DALYTRAN);
    }

    /**
     * {@code 0100-CUSTFILE-OPEN} - lines 271 to 287. Opens the customer file.
     *
     * <p>The member opens this file and never reads it, and this layer holds no customer gateway, so
     * there is no resource to open and nothing that can report a failure: the status is success. The
     * paragraph is preserved with its full normalise-and-branch structure because it is one of the
     * eighteen traceability units, and its error arm is the shared arm the three files that do have
     * gateways exercise.
     *
     * @param state the working-storage holder for this invocation
     */
    private void custfileOpen(final RunState state) {
        openInput(state, null, ERROR_OPENING_CUSTFILE, DD_CUSTFILE);
    }

    /**
     * {@code 0200-XREFFILE-OPEN} - lines 289 to 305. Opens the cross-reference file.
     *
     * @param state the working-storage holder for this invocation
     */
    private void xreffileOpen(final RunState state) {
        openInput(state, this.cardCrossReferenceRepository, ERROR_OPENING_XREFFILE, DD_XREFFILE);
    }

    /**
     * {@code 0300-CARDFILE-OPEN} - lines 307 to 323. Opens the card file, which is never read; see
     * {@code custfileOpen} for why the status is success.
     *
     * @param state the working-storage holder for this invocation
     */
    private void cardfileOpen(final RunState state) {
        openInput(state, null, ERROR_OPENING_CARDFILE, DD_CARDFILE);
    }

    /**
     * {@code 0400-ACCTFILE-OPEN} - lines 325 to 341. Opens the account file.
     *
     * @param state the working-storage holder for this invocation
     */
    private void acctfileOpen(final RunState state) {
        openInput(state, this.accountRepository, ERROR_OPENING_ACCTFILE, DD_ACCTFILE);
    }

    /**
     * {@code 0500-TRANFILE-OPEN} - lines 343 to 359. Opens the posted-transaction file, which is never
     * read; see {@code custfileOpen} for why the status is success.
     *
     * @param state the working-storage holder for this invocation
     */
    private void tranfileOpen(final RunState state) {
        openInput(state, null, ERROR_OPENING_TRANFILE, DD_TRANFILE);
    }

    /**
     * {@code 9000-DALYTRAN-CLOSE} - lines 361 to 377. Closes the daily-transaction input.
     *
     * <p><strong>Preserved source defect.</strong> The paragraph tests the daily-transaction status at
     * line 364, but its error arm displays {@code ERROR CLOSING CUSTOMER FILE} at line 372 and reports
     * the customer file's status at line 373 rather than its own - a copy of the customer-close
     * paragraph that follows it. Both the literal and the misreported resource are externally
     * observable diagnostics, so both are reproduced here and the defect is recorded in
     * {@code docs/decision-log.md} instead of being repaired.
     *
     * @param state the working-storage holder for this invocation
     */
    private void dalytranClose(final RunState state) {
        closeFile(state, this.dailyTransactionRepository, ERROR_CLOSING_CUSTFILE, DD_CUSTFILE);
    }

    /**
     * {@code 9100-CUSTFILE-CLOSE} - lines 379 to 395. Closes the customer file; see
     * {@code custfileOpen} for why the status is success.
     *
     * @param state the working-storage holder for this invocation
     */
    private void custfileClose(final RunState state) {
        closeFile(state, null, ERROR_CLOSING_CUSTFILE, DD_CUSTFILE);
    }

    /**
     * {@code 9200-XREFFILE-CLOSE} - lines 397 to 413. Closes the cross-reference file.
     *
     * @param state the working-storage holder for this invocation
     */
    private void xreffileClose(final RunState state) {
        closeFile(state, this.cardCrossReferenceRepository, ERROR_CLOSING_XREFFILE, DD_XREFFILE);
    }

    /**
     * {@code 9300-CARDFILE-CLOSE} - lines 415 to 431. Closes the card file; see {@code custfileOpen}
     * for why the status is success.
     *
     * @param state the working-storage holder for this invocation
     */
    private void cardfileClose(final RunState state) {
        closeFile(state, null, ERROR_CLOSING_CARDFILE, DD_CARDFILE);
    }

    /**
     * {@code 9400-ACCTFILE-CLOSE} - lines 433 to 449. Closes the account file.
     *
     * @param state the working-storage holder for this invocation
     */
    private void acctfileClose(final RunState state) {
        closeFile(state, this.accountRepository, ERROR_CLOSING_ACCTFILE, DD_ACCTFILE);
    }

    /**
     * {@code 9500-TRANFILE-CLOSE} - lines 451 to 467. Closes the posted-transaction file; see
     * {@code custfileOpen} for why the status is success.
     *
     * @param state the working-storage holder for this invocation
     */
    private void tranfileClose(final RunState state) {
        closeFile(state, null, ERROR_CLOSING_TRANFILE, DD_TRANFILE);
    }

    /**
     * {@code Z-ABEND-PROGRAM} - lines 469 to 473.
     *
     * <p>The legacy paragraph displays {@code ABENDING PROGRAM}, clears its timing field, moves
     * {@code 999} into the abend code and calls {@code CEE3ABD}. All four steps belong to
     * {@code AbendService}, which emits the diagnostic and only then raises, and which takes the
     * four-character batch abend code from {@code AbendException}'s own constant - a constant whose
     * value is exactly the {@code 999} this paragraph moves. Nothing here hardcodes that code, and
     * nothing here logs after the raise.
     *
     * @param reason the legacy display literal of the site that decided to abend
     * @param rawFileStatus the raw two-character status that failed
     * @param operation the failing operation
     * @param resourceName the data-definition name of the resource that failed
     */
    private void zAbendProgram(final String reason, final String rawFileStatus,
            final String operation, final String resourceName) {
        this.abendService.abendBatch(PROGRAM_NAME, reason, rawFileStatus, operation, resourceName);
    }

    /**
     * {@code Z-DISPLAY-IO-STATUS} - lines 476 to 489.
     *
     * <p>Emits the operator-facing status line that every error arm emits between its own literal and
     * the abend. The legacy paragraph renders the status into a four-character slot two ways -
     * one for the implementor-defined class whose second byte is binary rather than a digit, one for a
     * plain two-digit status - and that four-character slot, its prefix and the choice between the two
     * renderings all belong to {@code AbendService} and the file-status exception that declares the
     * prefix. Delegating keeps the estate with one status renderer rather than two and keeps
     * fixed-width formatting out of this service.
     *
     * @param rawFileStatus the raw two-character status to display
     * @param operation the operation that reported it
     * @param resourceName the data-definition name of the resource that reported it
     */
    private void zDisplayIoStatus(final String rawFileStatus, final String operation,
            final String resourceName) {
        this.abendService.displayIoStatus(rawFileStatus, operation, resourceName);
    }

    /**
     * The body the six open paragraphs share: arm the result, open, normalise two ways, branch.
     *
     * <p>All six are textually identical in the source but for the file they name and the literal they
     * display, so both arrive as parameters and the branch itself is written once. There is no
     * end-of-file arm, because none of the six has one: any status other than success is an error here.
     *
     * @param state the working-storage holder for this invocation
     * @param gateway the repository standing in for the file being opened, or {@code null} when the
     *                relational target holds no gateway for it and there is nothing to open
     * @param errorLiteral the literal the paragraph displays on its error arm
     * @param resourceName the data-definition name the paragraph reports
     */
    private void openInput(final RunState state, final JpaRepository<?, ?> gateway,
            final String errorLiteral, final String resourceName) {
        state.applResult = ApplResult.PENDING;
        final String rawStatus = fileStatusOf(gateway, resourceName);
        state.applResult = normaliseOpenOrCloseStatus(rawStatus);
        if (state.applResult.isAok()) {
            return;
        }
        LOG.error(errorLiteral);
        zDisplayIoStatus(rawStatus, OPERATION_OPEN, resourceName);
        zAbendProgram(errorLiteral, rawStatus, OPERATION_OPEN, resourceName);
    }

    /**
     * The body the six close paragraphs share.
     *
     * <p>Identical in effect to the open body: the closes arm the same value by adding eight to zero
     * where the opens move eight, which is the same store written two ways, and they normalise the
     * same two ways with no end-of-file arm.
     *
     * @param state the working-storage holder for this invocation
     * @param gateway the repository standing in for the file being closed, or {@code null} when the
     *                relational target holds no gateway for it
     * @param errorLiteral the literal the paragraph displays on its error arm
     * @param resourceName the data-definition name the paragraph reports
     */
    private void closeFile(final RunState state, final JpaRepository<?, ?> gateway,
            final String errorLiteral, final String resourceName) {
        state.applResult = ApplResult.PENDING;
        final String rawStatus = fileStatusOf(gateway, resourceName);
        state.applResult = normaliseOpenOrCloseStatus(rawStatus);
        if (state.applResult.isAok()) {
            return;
        }
        LOG.error(errorLiteral);
        zDisplayIoStatus(rawStatus, OPERATION_CLOSE, resourceName);
        zAbendProgram(errorLiteral, rawStatus, OPERATION_CLOSE, resourceName);
    }

    /**
     * The status an open or close reports for one resource.
     *
     * <p>A resource this service holds a gateway for is probed; a resource it holds no gateway for
     * reports success, because the relational target has no such resource to open, nothing to close
     * and therefore nothing that can fail. That is the one thing the twelve paragraphs cannot fully
     * reproduce, and it is stated rather than concealed.
     *
     * @param gateway the repository to probe, or {@code null} when there is none
     * @param resourceName the data-definition name, reported if the probe fails
     * @return a raw two-character status drawn from the vocabulary the source itself uses
     */
    private static String fileStatusOf(final JpaRepository<?, ?> gateway,
            final String resourceName) {
        if (gateway == null) {
            return FileStatus.SUCCESS.getCode();
        }
        try {
            gateway.count();
            return FileStatus.SUCCESS.getCode();
        } catch (final DataAccessException unavailable) {
            LOG.error("resource={} is not accessible; reporting file status {} failureChain={}",
                    resourceName, FileStatus.PERMANENT_ERROR.getCode(),
                    FailureDiagnostics.failureChainOf(unavailable));
            return FileStatus.PERMANENT_ERROR.getCode();
        }
    }

    /**
     * The three-way normalisation of {@code 1000-DALYTRAN-GET-NEXT} - lines 204 to 212.
     *
     * <p>Success, then end of file, then everything else, tested in exactly that order, because COBOL
     * evaluates the nested condition top down and stops at the first match. An unrecognised status
     * resolves to nothing and therefore falls to the error arm, which is what the legacy
     * {@code ELSE MOVE 12} does with it.
     *
     * @param rawStatus the raw two-character status the read reported
     * @return the coarse result the paragraph branches on
     */
    private static ApplResult normaliseReadStatus(final String rawStatus) {
        final Optional<FileStatus> resolved = FileStatus.fromCode(rawStatus);
        if (resolved.isPresent() && resolved.get().isSuccess()) {
            return ApplResult.AOK;
        }
        if (resolved.isPresent() && resolved.get().isEndOfFile()) {
            return ApplResult.EOF;
        }
        return ApplResult.ERROR;
    }

    /**
     * The two-way normalisation every open and close paragraph performs.
     *
     * <p>Deliberately separate from the read's normalisation: these twelve paragraphs have no
     * end-of-file arm, so a status of end of file reported by an open or a close is an error to them.
     * Sharing one normalisation between the two shapes would invent an end-of-file outcome the
     * paragraphs cannot express.
     *
     * @param rawStatus the raw two-character status the operation reported
     * @return success, or error for every other value
     */
    private static ApplResult normaliseOpenOrCloseStatus(final String rawStatus) {
        final Optional<FileStatus> resolved = FileStatus.fromCode(rawStatus);
        if (resolved.isPresent() && resolved.get().isSuccess()) {
            return ApplResult.AOK;
        }
        return ApplResult.ERROR;
    }

    /**
     * Obtains the cursor the read loop walks, resolving the scan from the repository when the caller
     * supplied no source. Called after all six opens have completed, which is where the legacy program
     * issues its first read.
     *
     * @param suppliedSource the caller's ordered records, or {@code null} to scan
     * @return a cursor over the records to walk
     */
    private Iterator<DailyTransaction> openCursor(
            final Iterable<DailyTransaction> suppliedSource) {
        if (suppliedSource != null) {
            return suppliedSource.iterator();
        }
        return new BoundedKeysetIterator<>("", KEYSET_PAGE_SIZE,
                (cursor, size) -> this.dailyTransactionRepository
                        .findByDalytranIdGreaterThanOrderByDalytranIdAsc(
                                cursor, Limit.of(size.intValue())),
                DailyTransaction::getDalytranId, Comparator.naturalOrder());
    }

    /**
     * The read itself - line 203.
     *
     * <p>On end of file the record area is deliberately left holding whatever the previous successful
     * read put there, because a COBOL {@code READ INTO} leaves its receiving item unchanged at end of
     * file. That is what makes the loop's final pass re-verify the last record, and clearing the area
     * here would silently repair a behaviour the class comment commits to preserving.
     *
     * @param cursor the ordered source being walked
     * @param state the working-storage holder for this invocation
     * @return the raw two-character status this read reports
     */
    private static String readNextRecord(final Iterator<DailyTransaction> cursor,
            final RunState state) {
        if (!cursor.hasNext()) {
            return FileStatus.END_OF_FILE.getCode();
        }
        final DailyTransaction next = cursor.next();
        if (next == null) {
            // A source element carrying no record image is neither a record nor end of file, so it
            // takes the only remaining arm the paragraph has for it.
            return FileStatus.PERMANENT_ERROR.getCode();
        }
        state.recordArea = next;
        return FileStatus.SUCCESS.getCode();
    }

    /**
     * The record diagnostic at line 168, which writes the daily-transaction image.
     *
     * <p>The legacy statement writes the whole record area, including the full card number, amount,
     * merchant details, description and timestamps, to the console. Reproducing those values in an
     * exported application log would create a second uncontrolled copy of protected transaction data.
     * The translated diagnostic therefore preserves the one-event-per-record observation while
     * reporting only the successful raw read status. The fixed-width image remains available to the
     * business path and its mapper; the diagnostic is deliberately not another representation of it.
     *
     * @param dailyTransaction the record area to display
     */
    private static void displayDalytranRecord(final DailyTransaction dailyTransaction) {
        if (!LOG.isInfoEnabled() || dailyTransaction == null) {
            return;
        }
        LOG.info(DALYTRAN_RECORD_READ);
        LOG.info("CARD NUMBER: {}",
                redactedCardNumber(dailyTransaction.getDalytranCardNum(),
                        REDACTED_CARD_NUMBER));
    }

    private static String redactedCardNumber(final String value, final String standIn) {
        Objects.requireNonNull(standIn, "standIn");
        return SensitiveLogRedactor.redact(value);
    }

    /**
     * @param value the value to test
     * @return whether the value carries no key, a key of spaces being unmatchable in the legacy
     *         program just as an absent key is here
     */
    private static boolean isAbsent(final String value) {
        return value == null || value.isBlank();
    }

    /**
     * The coarse result of {@code APPL-RESULT}, mirroring that working-storage field and its two
     * level-88 condition names rather than re-declaring the batch tier's shared tri-state, which is
     * unreachable from this layer. Each constant carries the value the estate moves for it.
     *
     * <p>It is private on purpose: the estate is to have one general-purpose tri-state, owned by the
     * batch step template, and this is a faithful mirror of one member's field rather than a rival to
     * it.
     */
    private enum ApplResult {
        /** Value {@code 0}, tested by the level-88 name {@code APPL-AOK}. */
        AOK(0),

        /** Value {@code 8}: armed before an operation and never tested on its own. */
        PENDING(8),

        /** Value {@code 12}: the error arm, which has no level-88 name of its own. */
        ERROR(12),

        /** Value {@code 16}, tested by the level-88 name {@code APPL-EOF}. */
        EOF(16);

        private final int value;

        ApplResult(final int normalisedValue) {
            this.value = normalisedValue;
        }

        /**
         * @return the normalised value the legacy program moves for this outcome
         */
        int value() {
            return this.value;
        }

        /**
         * @return whether {@code APPL-AOK} would be true
         */
        boolean isAok() {
            return this == AOK;
        }

        /**
         * @return whether {@code APPL-EOF} would be true, which is a normal outcome and never an error
         */
        boolean isEof() {
            return this == EOF;
        }
    }

    /**
     * The member's working storage, created fresh for each invocation so that the service itself holds
     * no mutable state and two callers cannot observe one another.
     *
     * <p>{@code recordArea} stands for the {@code DALYTRAN-RECORD} receiving item and is deliberately
     * not cleared at end of file. The two read statuses stand for {@code WS-XREF-READ-STATUS} and
     * {@code WS-ACCT-READ-STATUS}; the counters have no legacy counterpart and exist only to populate
     * the returned result, the legacy program having reported its outcomes solely by display.
     */
    private static final class RunState {
        private ApplResult applResult = ApplResult.AOK;

        private boolean endOfDailyTransFile;

        private DailyTransaction recordArea;

        private int xrefReadStatus = READ_STATUS_OK;

        private int acctReadStatus = READ_STATUS_OK;

        private int recordsRead;

        private int recordsVerified;

        private int verificationPasses;

        private int cardsNotVerified;

        private int accountsNotFound;

        private DailyTransactionReadResult toResult() {
            return new DailyTransactionReadResult(this.recordsRead, this.recordsVerified,
                    this.verificationPasses, this.cardsNotVerified, this.accountsNotFound,
                    this.applResult.value());
        }
    }

    /**
     * The outcome of verifying one record: what the legacy program reported only by display.
     *
     * @param dalytranId the record's identifier, or {@code null} when there was no record image
     * @param dalytranCardNum the card number the cross-reference was read with
     * @param xrefAcctId the account identifier the cross-reference yielded, or {@code null} when the
     *                   card was not resolved
     * @param xrefReadStatus {@code WS-XREF-READ-STATUS}: zero, or the invalid-key value
     * @param acctReadStatus {@code WS-ACCT-READ-STATUS}: zero, or the invalid-key value. Meaningful
     *                       only when {@code accountLookupAttempted} is true; reported as zero
     *                       otherwise, because the legacy paragraph is not entered and its field is
     *                       left holding whatever the previous record left there
     * @param accountLookupAttempted whether the account read was reached, which it is exactly when the
     *                               card was resolved
     * @param afterEndOfFile whether this is the extra pass the loop makes after end of file, over the
     *                       record area the previous read left in place
     */
    public record DailyTransactionVerification(String dalytranId, String dalytranCardNum,
            String xrefAcctId, int xrefReadStatus, int acctReadStatus,
            boolean accountLookupAttempted, boolean afterEndOfFile) {

        /**
         * Reports the cross-reference read's own outcome.
         *
         * @return whether the cross-reference resolved the card number
         */
        public boolean cardVerified() {
            return this.xrefReadStatus == READ_STATUS_OK;
        }

        /**
         * Reports the account read's outcome, distinguishing a miss from a read that never happened.
         *
         * @return whether the account read found its record; false when the read was never reached
         */
        public boolean accountFound() {
            return this.accountLookupAttempted && this.acctReadStatus == READ_STATUS_OK;
        }
    }

    /**
     * The outcome of one pass over the daily-transaction input.
     *
     * @param recordsRead the number of records read successfully, which matches an input's record
     *                    count exactly
     * @param recordsVerified the number of records verified from a record the read had just delivered,
     *                        which equals {@code recordsRead}
     * @param verificationPasses the number of times the verification block ran, which exceeds
     *                           {@code recordsVerified} by the one extra pass the loop makes after end
     *                           of file over the record area the previous read left in place
     * @param cardsNotVerified how many passes could not resolve their card number
     * @param accountsNotFound how many passes resolved a card but found no account
     * @param returnCode the terminal value of {@code APPL-RESULT}, zero when the pass completed
     *                   normally; an error abends rather than returning
     */
    public record DailyTransactionReadResult(int recordsRead, int recordsVerified,
            int verificationPasses, int cardsNotVerified, int accountsNotFound, int returnCode) {

        /**
         * Rejects any combination the pass could not have produced.
         *
         * <p>The per-record outcomes are <strong>not</strong> carried here. Each one reached the
         * caller's destination as it was produced, so this result is the pass's observations and never
         * a copy of a whole dataset's worth of detail; {@code verificationPasses} counts exactly how
         * many outcomes were offered. See {@code docs/decision-log.md} entry DL-176.
         */
        public DailyTransactionReadResult {
            requireNotNegative(recordsRead, "recordsRead");
            requireNotNegative(recordsVerified, "recordsVerified");
            requireNotNegative(verificationPasses, "verificationPasses");
            requireNotNegative(cardsNotVerified, "cardsNotVerified");
            requireNotNegative(accountsNotFound, "accountsNotFound");
            requireNotNegative(returnCode, "returnCode");
            if (recordsVerified > recordsRead) {
                throw new IllegalArgumentException("recordsVerified (" + recordsVerified + ") must not"
                        + " exceed recordsRead (" + recordsRead + ")");
            }
            if (verificationPasses < recordsVerified) {
                throw new IllegalArgumentException("verificationPasses (" + verificationPasses
                        + ") must not be fewer than recordsVerified (" + recordsVerified + ")");
            }
        }

        /**
         * Summarises the pass in one predicate, for a caller that needs no per-record detail.
         *
         * @return whether the pass completed without any record failing verification
         */
        public boolean allRecordsVerified() {
            return this.cardsNotVerified == 0 && this.accountsNotFound == 0;
        }

        private static void requireNotNegative(final int value, final String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative but was " + value);
            }
        }
    }
}
