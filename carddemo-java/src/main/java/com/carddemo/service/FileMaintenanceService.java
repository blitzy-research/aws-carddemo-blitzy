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
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.carddemo.domain.Account;
import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.AccountScanRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CardCrossReferenceScanRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardScanRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.util.BatchCancellation;
import com.carddemo.util.BoundedKeysetIterator;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.SensitiveLogRedactor;

/**
 * The four sequential file readers of the batch tier, and the home of the two-level I/O status model
 * that every batch member of the estate shares.
 *
 * <p><strong>What is translated here.</strong> Four legacy members, twenty-one paragraphs, one service:
 * {@code app/cbl/CBACT01C.cbl} (six paragraphs, 193 lines) reads the account master,
 * {@code app/cbl/CBACT02C.cbl} (five paragraphs, 178 lines) reads the card master,
 * {@code app/cbl/CBACT03C.cbl} (five paragraphs, 178 lines) reads the card cross-reference cluster, and
 * {@code app/cbl/CBCUS01C.cbl} (five paragraphs, 178 lines) reads the customer master. Every one has the
 * same shape - open, sequential read loop, status check, close, with the abend path on the error branch -
 * which is why one service carries all four rather than four services carrying one apiece.
 *
 * <p>A fifth sequential pass lives here and translates <strong>no member at all</strong>: the ordered
 * category-balance unload that {@code app/jcl/PRTCATBL.jcl} performs with a cataloged copy utility and an
 * external sort. It is in this class because it needs exactly the discipline this class owns, and it is
 * labelled as a job step rather than a program precisely so that it cannot be mistaken for a translated
 * member. Section "The third reader reads the cross-reference cluster" below records why that separation
 * had to be made.
 *
 * <p><strong>{@code CBACT01C} is the status-model exemplar</strong> and its structure is worth naming by
 * line, because nine further batch members repeat it verbatim. Lines 29-33 declare the file with
 * {@code ORGANIZATION IS INDEXED}, {@code ACCESS MODE IS SEQUENTIAL}, {@code RECORD KEY IS FD-ACCT-ID}
 * and {@code FILE STATUS IS ACCTFILE-STATUS}. Lines 46-48 declare that status as a split two-byte group
 * of two one-character fields. Lines 50-59 declare the display-support fields, among them a halfword
 * redefined as two bytes and a four-character status display field built from a single digit followed by
 * three. Lines 92-116 are the pattern this class exists to reproduce. Lines 169-173 are the abend
 * paragraph and lines 176-189 the status-display paragraph.
 *
 * <p><strong>The two-level model, and why one enum would not do.</strong> The programs never branch on
 * the raw two-byte status. Each normalises it into a coarser result first - success to zero, end of file
 * to sixteen, anything else to twelve - and then branches on that coarse variable through two condition
 * names, one bound to zero and one bound to sixteen. The coarse variable is referenced on roughly 223
 * lines of the estate, and it, not the raw code, is what the programs actually test. A third value,
 * twelve, is the error sentinel and carries no condition name of its own; a fourth, eight, is a
 * pre-operation sentinel moved into the variable immediately before every open and close so that a path
 * which forgets to set a result cannot be mistaken for success. All four live in the private nested
 * {@code ApplResult} enum, named after the legacy variable itself. The raw two-byte vocabulary stays
 * where it belongs, in {@code com.carddemo.domain.enums.FileStatus}; this class consumes that enum and
 * produces the coarse outcome, and no normaliser is imported from anywhere.
 *
 * <p><strong>End of file is never folded into error.</strong> Every sequential read loop in the estate
 * terminates on the end-of-file arm, so collapsing the two would turn a normal completion into an abend.
 * The separation is structural here rather than advisory: the read arm and the error arm are distinct
 * branches over distinct coarse values, and the error type this package's exception layer offers refuses
 * to be constructed from either the success or the end-of-file code at all.
 *
 * <p><strong>Normalisation is not uniform across operations, and the difference is deliberate.</strong>
 * The read paragraph normalises three ways because it has an end-of-file arm; the open paragraph (line
 * 133) and the close paragraph (line 151) normalise only two ways, because neither has one. A status of
 * end of file reported by an open would therefore be an error, not a normal outcome, and the private
 * normalisation method takes a flag so that this distinction survives translation.
 *
 * <p><strong>Emit, then abend - never the reverse.</strong> The legacy sequence at every failing I/O site
 * is: display the member's own failure literal, move the raw status into the display field, perform the
 * status-display paragraph, and only then perform the abend paragraph, which displays its own banner
 * before issuing the Language Environment abort. Every error arm here reproduces that order, so the raw
 * two-byte status is in the log record before anything is raised, and the raise itself is delegated to
 * the injected abend service. Nothing is logged from a caller's {@code catch}: on a mainframe the
 * diagnostic reached the operator whether or not anything survived the abend.
 *
 * <p><strong>Ordering is supplied, never assumed.</strong> Because access is sequential over an indexed
 * organisation, key order is the observable ordering of the output. No repository in this module declares
 * an ordering, so every read here passes an explicit ascending sort on the entity's business key. Every
 * cluster definition in the estate declares its key at offset zero, which is why the identity of each
 * entity is the natural business key and no generated surrogate exists anywhere in the module.
 *
 * <p><strong>Traceability - all twenty-one paragraphs.</strong> The two paragraphs every member shares
 * map to one Java method each, so a single name serves all four members and both legacy spellings stay
 * findable.
 *
 * <table>
 *   <caption>Legacy paragraph to Java method</caption>
 *   <tr><th>Member</th><th>Paragraph</th><th>Line</th><th>Method</th></tr>
 *   <tr><td>CBACT01C</td><td>(unnamed mainline)</td><td>70</td><td>readAccountFile</td></tr>
 *   <tr><td>CBACT01C</td><td>0000-ACCTFILE-OPEN</td><td>133</td><td>openAcctFile</td></tr>
 *   <tr><td>CBACT01C</td><td>1000-ACCTFILE-GET-NEXT</td><td>92</td><td>acctFileGetNext</td></tr>
 *   <tr><td>CBACT01C</td><td>1100-DISPLAY-ACCT-RECORD</td><td>118</td><td>displayAcctRecord</td></tr>
 *   <tr><td>CBACT01C</td><td>9000-ACCTFILE-CLOSE</td><td>151</td><td>closeAcctFile</td></tr>
 *   <tr><td>CBACT01C</td><td>9999-ABEND-PROGRAM</td><td>169</td><td>abendProgram</td></tr>
 *   <tr><td>CBACT01C</td><td>9910-DISPLAY-IO-STATUS</td><td>176</td><td>displayIoStatus</td></tr>
 *   <tr><td>CBACT02C</td><td>(unnamed mainline)</td><td>70</td><td>readCardFile</td></tr>
 *   <tr><td>CBACT02C</td><td>0000-CARDFILE-OPEN</td><td>118</td><td>openCardFile</td></tr>
 *   <tr><td>CBACT02C</td><td>1000-CARDFILE-GET-NEXT</td><td>92</td><td>cardFileGetNext</td></tr>
 *   <tr><td>CBACT02C</td><td>9000-CARDFILE-CLOSE</td><td>136</td><td>closeCardFile</td></tr>
 *   <tr><td>CBACT02C</td><td>9999-ABEND-PROGRAM</td><td>154</td><td>abendProgram</td></tr>
 *   <tr><td>CBACT02C</td><td>9910-DISPLAY-IO-STATUS</td><td>161</td><td>displayIoStatus</td></tr>
 *   <tr><td>CBACT03C</td><td>(unnamed mainline)</td><td>70</td>
 *       <td>readCardCrossReferenceFile</td></tr>
 *   <tr><td>CBACT03C</td><td>0000-XREFFILE-OPEN</td><td>118</td><td>openXrefFile</td></tr>
 *   <tr><td>CBACT03C</td><td>1000-XREFFILE-GET-NEXT</td><td>92</td><td>xrefFileGetNext</td></tr>
 *   <tr><td>CBACT03C</td><td>9000-XREFFILE-CLOSE</td><td>136</td><td>closeXrefFile</td></tr>
 *   <tr><td>CBACT03C</td><td>9999-ABEND-PROGRAM</td><td>154</td><td>abendProgram</td></tr>
 *   <tr><td>CBACT03C</td><td>9910-DISPLAY-IO-STATUS</td><td>161</td><td>displayIoStatus</td></tr>
 *   <tr><td>CBCUS01C</td><td>(unnamed mainline)</td><td>70</td><td>readCustomerFile</td></tr>
 *   <tr><td>CBCUS01C</td><td>0000-CUSTFILE-OPEN</td><td>118</td><td>openCustFile</td></tr>
 *   <tr><td>CBCUS01C</td><td>1000-CUSTFILE-GET-NEXT</td><td>92</td><td>custFileGetNext</td></tr>
 *   <tr><td>CBCUS01C</td><td>9000-CUSTFILE-CLOSE</td><td>136</td><td>closeCustFile</td></tr>
 *   <tr><td>CBCUS01C</td><td>Z-ABEND-PROGRAM</td><td>154</td><td>abendProgram</td></tr>
 *   <tr><td>CBCUS01C</td><td>Z-DISPLAY-IO-STATUS</td><td>161</td><td>displayIoStatus</td></tr>
 * </table>
 *
 * <p><strong>Source anomalies recorded rather than propagated.</strong> Four are relevant here and each
 * belongs in the decision log. First, {@code CBCUS01C} names its status-display paragraph
 * {@code Z-DISPLAY-IO-STATUS} at line 161 where the three {@code CBACT} members name theirs
 * {@code 9910-DISPLAY-IO-STATUS}; second, the same member names its abend paragraph
 * {@code Z-ABEND-PROGRAM} at line 154 rather than {@code 9999-ABEND-PROGRAM}. Both Java methods carry one
 * name and the traceability matrix records both spellings. Third, statuses {@code 22} and {@code 35} are
 * compared in no source member, so nothing in this class references them or depends on them. Fourth, the
 * account display paragraph labels the expiration date with a letter missing; the operator-facing label is
 * reproduced exactly as the member emits it, because an operator matching on that line would not find a
 * corrected one, while the corresponding Java property on the entity is spelled correctly.
 *
 * <h2>The third reader reads the cross-reference cluster</h2>
 *
 * <p><strong>{@code CBACT03C} reads the card cross-reference file, and this class now translates it as
 * such.</strong> Six facts about the member settle it, each read directly from it: its header states that
 * its function is to read and print the account cross-reference data file; line 29 selects the
 * {@code XREFFILE} DD; line 32 declares {@code RECORD KEY IS FD-XREF-CARD-NUM}; lines 38-40 split a
 * fifty-byte record into a sixteen-character card-number key plus thirty-four bytes of remainder; line 45
 * includes the card cross-reference copybook; and its three failure literals at lines 110, 129 and 147 all
 * name {@code XREFFILE}. Exactly one job member invokes it - {@code app/jcl/READXREF.jcl} step
 * {@code STEP05}, against {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}.
 *
 * <p><strong>An earlier revision of this class bound that reader group to the transaction category balance
 * cluster instead, on the strength of planning material that described the member as a category-balance
 * listing driver.</strong> That description is a documentation error, not a design decision, and the
 * binding it produced was a parity defect of the worst kind: it compiled, it passed tests written under the
 * same misunderstanding, and it would have reported a healthy file while never opening the one the member
 * names. The error was plausible only because of a width coincidence - the cross-reference record and the
 * category-balance record are both fifty bytes - which is exactly why it had to be settled by reading the
 * member rather than by reading about it. The binding is corrected here: the third reader group serves the
 * cross-reference cluster, under the member's own DD name, its own record key and its own three failure
 * literals, reproduced verbatim rather than composed.
 *
 * <p><strong>The category balance still needs an ordered sequential pass, and it gets one that cites a job
 * stream rather than a member.</strong> A census across all ten legacy batch programs finds the category
 * balance cluster referenced by two of them, the posting run and the accrual run, and both reach it
 * transactionally by key; no application program lists it sequentially. The only sequential pass over it in
 * the estate is {@code app/jcl/PRTCATBL.jcl}, whose three steps invoke the no-op allocation utility, the
 * cataloged copy wrapper {@code app/proc/REPROC.prc} with control member {@code app/ctl/REPROCT.ctl}, and
 * the external sort - not one of them an application program. The pass below therefore carries no program
 * name, emits no {@code START OF EXECUTION OF PROGRAM} banner, and appears in no row of the traceability
 * table above, because it translates no paragraph. Its failure literals follow the estate's own
 * {@code ERROR <gerund> <DD>} phrasing on the {@code TCATBALF} DD - named by {@code app/jcl/POSTTRAN.jcl}
 * line 41, {@code app/jcl/INTCALC.jcl} line 27 and the unload step of {@code app/jcl/PRTCATBL.jcl}, over
 * the cluster {@code app/jcl/TCATBALF.jcl} defines with a seventeen-byte key at offset zero and a record
 * size of fifty - because there is no member literal to reproduce and borrowing {@code CBACT03C}'s would
 * name the wrong dataset in an operator's log.
 *
 * <p>Both corrections belong in {@code docs/decision-log.md}, and one consequence is worth stating plainly:
 * a paragraph of a member has exactly one Java owner in this module. The cross-reference read is owned
 * here, and the batch tier's probe job delegates to it rather than carrying a second translation of the
 * same five paragraphs.
 *
 * <p><strong>The composite key must not be confused with its namesake.</strong> Two copybooks in the
 * estate name a key group identically while describing different things: the category balance key is
 * seventeen bytes and leads with an eleven-digit account identifier, while the category type key is six
 * bytes and is not a prefix of it. They are unrelated types here, share no base type, and the sort this
 * class applies names the three identity attributes of the balance record in key order.
 *
 * <p><strong>Diagnostics.</strong> The estate's display statements become SLF4J calls. Operator-relevant
 * failure literals, counts and status context are retained; record content is not. Every whole-record
 * display becomes a non-sensitive record-type label plus a process-scoped HMAC correlation token. The
 * account display paragraph likewise emits only that token and the account-status label: identifiers,
 * balances, limits, dates and group values are withheld at every log level. The reduction is deliberate,
 * applies uniformly to account, card, cross-reference, category-balance and customer records, and prevents
 * a debug-level change from reopening a sensitive-data channel.
 *
 * <p><strong>Shape of the implementation.</strong> Stateless singleton: counts, cursors and statuses live
 * in a per-invocation cursor object or in locals, never in an instance or static field. Each reader's
 * paragraphs are named methods of their own so every row of the table above cites a distinct method where
 * the legacy had a distinct paragraph, and the skeleton they share is factored into private helpers of
 * this class rather than into a new type. The batch step template of the {@code batch.step} package hosts
 * a similar skeleton for the chunk-oriented steps; the two coexist deliberately and neither imports the
 * other, and this service is the one the traceability matrix cites for these four members. The category
 * balance pass keeps the same shape without appearing in the matrix, because a job step is not a paragraph.
 *
 * <p>Migrated from the AWS CardDemo mainframe estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@Service
public final class FileMaintenanceService {

    /**
     * Diagnostic channel, replacing the display statements of the four members. The logger name is the
     * fully qualified class name, so the {@code com.carddemo.service} category configured by the module's
     * logging configuration resolves it.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(FileMaintenanceService.class);

    /** Legacy member name of the account reader; eight characters, the width of the abend culprit field. */
    private static final String PROGRAM_CBACT01C = "CBACT01C";

    /** Legacy member name of the card reader. */
    private static final String PROGRAM_CBACT02C = "CBACT02C";

    /** Legacy member name of the card cross-reference reader. */
    private static final String PROGRAM_CBACT03C = "CBACT03C";

    /** Legacy member name of the customer reader. */
    private static final String PROGRAM_CBCUS01C = "CBCUS01C";

    /**
     * DD name of the account cluster, {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}, whose key is 11 bytes
     * wide at offset 0.
     */
    private static final String DD_ACCTFILE = "ACCTFILE";

    /**
     * DD name of the card cluster, {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}, whose key is 16 bytes wide at
     * offset 0.
     */
    private static final String DD_CARDFILE = "CARDFILE";

    /**
     * DD name of the card cross-reference cluster, {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}, whose key is
     * 16 bytes wide at offset 0. This is the DD {@code CBACT03C} itself selects, at its line 29.
     */
    private static final String DD_XREFFILE = "XREFFILE";

    /**
     * DD name of the category balance cluster, {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS}, whose composite
     * key is 17 bytes wide at offset 0.
     *
     * <p>Named by {@code app/jcl/POSTTRAN.jcl} line 41 and {@code app/jcl/INTCALC.jcl} line 27, and by the
     * unload step of {@code app/jcl/PRTCATBL.jcl}. No application program reads this cluster sequentially,
     * which is why the pass below that serves it cites a job stream rather than a member.
     */
    private static final String DD_TCATBALF = "TCATBALF";

    /**
     * DD name of the customer cluster, {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}, whose key is 9 bytes wide
     * at offset 0.
     */
    private static final String DD_CUSTFILE = "CUSTFILE";

    /** Legacy gerund naming the operation an open diagnostic reports. */
    private static final String OPERATION_OPEN = "OPENING";

    /** Legacy gerund naming the operation a read diagnostic reports. */
    private static final String OPERATION_READ = "READING";

    /** Legacy gerund naming the operation a close diagnostic reports. */
    private static final String OPERATION_CLOSE = "CLOSING";

    /** Opening banner each member displays as its first statement, parameterised on the member name. */
    private static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM {}";

    /** Closing banner each member displays after its close paragraph returns. */
    private static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM {}";

    /**
     * Legacy step name of the only sequential pass the estate makes over the category balance cluster.
     *
     * <p>A step name and not a member name, because {@code app/jcl/PRTCATBL.jcl} step {@code STEP05R} invokes
     * a cataloged copy wrapper rather than an application program. Naming a program here would assert an
     * antecedent that does not exist.
     */
    private static final String LEGACY_UNLOAD_STEP = "STEP05R";

    /**
     * Opening banner of the category balance pass, naming the job step and the DD rather than a program.
     *
     * <p>Deliberately not the {@code START OF EXECUTION OF PROGRAM} banner every member displays: no program
     * executes here, and reusing that banner would put a member's own diagnostic into a log line no member
     * produced.
     */
    private static final String START_OF_UNLOAD = "{} BEGINNING SEQUENTIAL UNLOAD OF {}";

    /** Closing banner of the category balance pass. */
    private static final String END_OF_UNLOAD = "{} COMPLETED SEQUENTIAL UNLOAD OF {}";

    /** Failure literal of the account open paragraph, {@code CBACT01C} line 144. */
    private static final String FAILURE_OPENING_ACCTFILE = "ERROR OPENING ACCTFILE";

    /** Failure literal of the account read paragraph, {@code CBACT01C} line 110. */
    private static final String FAILURE_READING_ACCTFILE = "ERROR READING ACCOUNT FILE";

    /** Failure literal of the account close paragraph, {@code CBACT01C} line 162. */
    private static final String FAILURE_CLOSING_ACCTFILE = "ERROR CLOSING ACCOUNT FILE";

    /** Failure literal of the card open paragraph, {@code CBACT02C} line 129. */
    private static final String FAILURE_OPENING_CARDFILE = "ERROR OPENING CARDFILE";

    /** Failure literal of the card read paragraph, {@code CBACT02C} line 110. */
    private static final String FAILURE_READING_CARDFILE = "ERROR READING CARDFILE";

    /** Failure literal of the card close paragraph, {@code CBACT02C} line 147. */
    private static final String FAILURE_CLOSING_CARDFILE = "ERROR CLOSING CARDFILE";

    /** Failure literal of the cross-reference open paragraph, {@code CBACT03C} line 129. */
    private static final String FAILURE_OPENING_XREFFILE = "ERROR OPENING XREFFILE";

    /** Failure literal of the cross-reference read paragraph, {@code CBACT03C} line 110. */
    private static final String FAILURE_READING_XREFFILE = "ERROR READING XREFFILE";

    /** Failure literal of the cross-reference close paragraph, {@code CBACT03C} line 147. */
    private static final String FAILURE_CLOSING_XREFFILE = "ERROR CLOSING XREFFILE";

    /**
     * Failure literal of the category balance open arm.
     *
     * <p>No application program reads this cluster sequentially, so there is no member whose literal could
     * be reproduced. The estate's own {@code ERROR <gerund> <DD>} phrasing is applied to the {@code TCATBALF}
     * DD instead, which is what an operator reading the log would recognise. It is deliberately <em>not</em>
     * borrowed from {@code CBACT03C}: that member names {@code XREFFILE}, and a literal naming the wrong
     * dataset is worse than one composed to the estate's pattern.
     */
    private static final String FAILURE_OPENING_TCATBALF = "ERROR OPENING TCATBALF";

    /** Failure literal of the category balance read arm, composed to the estate's phrasing. */
    private static final String FAILURE_READING_TCATBALF = "ERROR READING TCATBALF";

    /** Failure literal of the category balance close arm, composed to the estate's phrasing. */
    private static final String FAILURE_CLOSING_TCATBALF = "ERROR CLOSING TCATBALF";

    /** Failure literal of the customer open paragraph, {@code CBCUS01C} line 129. */
    private static final String FAILURE_OPENING_CUSTFILE = "ERROR OPENING CUSTFILE";

    /** Failure literal of the customer read paragraph, {@code CBCUS01C} line 110. */
    private static final String FAILURE_READING_CUSTFILE = "ERROR READING CUSTOMER FILE";

    /** Failure literal of the customer close paragraph, {@code CBCUS01C} line 147. */
    private static final String FAILURE_CLOSING_CUSTFILE = "ERROR CLOSING CUSTOMER FILE";

    /**
     * Raw status this class reports when a repository operation fails: the permanent-error code, one of
     * the nine statuses the estate actually compares. No path here references a status the source never
     * compares.
     */
    private static final String DATA_ACCESS_FAILURE_STATUS = FileStatus.PERMANENT_ERROR.getCode();

    /** Width of the legacy four-character status display field: one digit followed by three. */
    private static final int IO_STATUS_DISPLAY_LENGTH = 4;

    /** First status byte of the implementor-defined class, whose second byte is a binary value. */
    private static final char IMPLEMENTOR_DEFINED_CLASS = '9';

    /** Mask isolating the low-order byte the legacy halfword redefinition receives the second byte into. */
    private static final int LOW_ORDER_BYTE_MASK = 0xFF;

    /** Divisor selecting the hundreds digit of the three-digit binary rendering. */
    private static final int HUNDREDS = 100;

    /** Divisor selecting the tens digit of the three-digit binary rendering. */
    private static final int TENS = 10;

    /**
     * Stand-in for a status the legacy four-character field could not have received, because its source
     * was a fixed two-byte group. Reached only through the public status display when a caller supplies a
     * malformed value, and never by the readers below.
     */
    private static final String UNFORMATTABLE_IO_STATUS = "????";

    /** Marker standing in for a diagnostic value the caller did not supply. */
    private static final String NOT_SUPPLIED = "(none)";

    /** Marker standing in for a status outside the declared raw vocabulary, which is a legitimate outcome. */
    private static final String OUTSIDE_VOCABULARY = "(outside declared vocabulary)";

    /**
     * Security-preserving replacement for a legacy whole-record display. The record type is a
     * non-sensitive label and the reference is a process-scoped HMAC token.
     */
    private static final String RECORD_IMAGE = "recordType={} recordRef={}";

    /**
     * Security-preserving account-display replacement. The status is a non-sensitive state label; every
     * identifier, balance, limit, date and group value is withheld.
     */
    private static final String ACCOUNT_RECORD_DETAIL = "recordType=account recordRef={} status={}";

    /** Template of the open diagnostic, reporting how many records the cluster holds when it is opened. */
    private static final String OPENED_RESOURCE =
            "opened program={} resource={} recordsAvailable={}";

    /** Template carrying a bounded failure-type chain and none of the exception's messages or frames. */
    private static final String DATA_ACCESS_FAILED =
            "data access failed program={} operation={} resource={} fileStatus={} failureChain={}";

    /** Template of the coarse-variable arming trace emitted before every open, read and close. */
    private static final String ARMED_APPL_RESULT =
            "armed program={} resource={} operation={} applResult={}";

    /** Template of the member's own failure literal together with both levels of the status model. */
    private static final String OPERATION_FAILED =
            "{} program={} operation={} resource={} fileStatus={} applResult={}";

    /** Template of the status line the status-display paragraph emits. */
    private static final String IO_STATUS_LINE =
            "{}{} fileStatus={} statusName={} operation={} resource={}";

    /** Template of the completion diagnostic each reader emits before returning its summary. */
    private static final String READER_COMPLETED =
            "completed program={} resource={} recordsRead={} terminalFileStatus={}";

    private static final int KEYSET_PAGE_SIZE = BoundedKeysetIterator.DEFAULT_PAGE_SIZE;

    private static final int TRAN_CAT_BAL_ACCOUNT_KEY_LENGTH = 11;

    private static final int TRAN_CAT_BAL_TYPE_KEY_LENGTH = 2;

    private final AccountRepository accountRepository;

    private final AccountScanRepository accountScanRepository;

    private final CardRepository cardRepository;

    private final CardScanRepository cardScanRepository;

    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    private final CardCrossReferenceScanRepository cardCrossReferenceScanRepository;

    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    private final CustomerRepository customerRepository;

    private final AbendService abendService;

    /**
     * Constructor injection only: the four repositories that replace the four indexed clusters the members
     * read, the fifth that serves the job stream's category-balance unload, and the abend service that owns
     * the estate's single terminal path. Every collaborator is required, because a reader with a missing
     * repository could not fail in any way an operator would recognise.
     *
     * @param accountRepository                    persistence entry point for the account cluster
     * @param accountScanRepository                bounded sequential-read view of the account cluster
     * @param cardRepository                       persistence entry point for the card cluster
     * @param cardScanRepository                   bounded sequential-read view of the card cluster
     * @param cardCrossReferenceRepository         persistence entry point for the cross-reference cluster,
     *                                             which is the cluster {@code CBACT03C} reads
     * @param cardCrossReferenceScanRepository     bounded sequential-read view of that cluster
     * @param transactionCategoryBalanceRepository persistence entry point for the category balance cluster,
     *                                             read by a job stream's unload rather than by any member
     * @param customerRepository                   persistence entry point for the customer cluster
     * @param abendService                         the estate's abend path, which logs and then raises
     * @throws NullPointerException if any collaborator is {@code null}, which is a wiring error
     */
    public FileMaintenanceService(final AccountRepository accountRepository,
            final AccountScanRepository accountScanRepository,
            final CardRepository cardRepository,
            final CardScanRepository cardScanRepository,
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final CardCrossReferenceScanRepository cardCrossReferenceScanRepository,
            final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            final CustomerRepository customerRepository,
            final AbendService abendService) {
        this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository");
        this.accountScanRepository = Objects.requireNonNull(
                accountScanRepository, "accountScanRepository");
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository");
        this.cardScanRepository =
                Objects.requireNonNull(cardScanRepository, "cardScanRepository");
        this.cardCrossReferenceRepository = Objects.requireNonNull(
                cardCrossReferenceRepository, "cardCrossReferenceRepository");
        this.cardCrossReferenceScanRepository = Objects.requireNonNull(
                cardCrossReferenceScanRepository, "cardCrossReferenceScanRepository");
        this.transactionCategoryBalanceRepository = Objects.requireNonNull(
                transactionCategoryBalanceRepository, "transactionCategoryBalanceRepository");
        this.customerRepository = Objects.requireNonNull(customerRepository, "customerRepository");
        this.abendService = Objects.requireNonNull(abendService, "abendService");
    }

    // ------------------------------------------------------------------------------------------------
    // CBACT01C - account master reader, six paragraphs, the status-model exemplar
    // ------------------------------------------------------------------------------------------------

    /**
     * Reads the account cluster in ascending key order, emitting the same operator diagnostics the legacy
     * member emitted, and reports what it read.
     *
     * <p>Translates the unnamed mainline of {@code app/cbl/CBACT01C.cbl} at lines 70-87: opening banner,
     * open paragraph, a loop that performs the read paragraph until end of file and emits the record image
     * after each successful read, close paragraph, closing banner, return. The member guards the loop body
     * with a redundant end-of-file test that the loop condition already guarantees; the guard on the record
     * image after the read is not redundant and is preserved, because the read that reaches end of file
     * must not emit an image.
     *
     * @return the member name, the DD name, the number of records read and the status that ended the loop
     * @throws AbendException if the open, a read or the close reports a status the member treats as an error
     */
    public FileReadSummary readAccountFile() {
        return readAccountFile(Thread.currentThread()::isInterrupted);
    }

    /**
     * Reads the account cluster while observing cooperative cancellation between records.
     *
     * @param stopRequested live cancellation probe
     * @return the completed read summary
     */
    public FileReadSummary readAccountFile(final BooleanSupplier stopRequested) {
        BatchCancellation.checkpoint(stopRequested);
        LOGGER.info(START_OF_EXECUTION, PROGRAM_CBACT01C);
        SequentialCursor<Account> cursor = openAcctFile();
        while (!cursor.atEndOfFile()) {
            BatchCancellation.checkpoint(stopRequested);
            acctFileGetNext(cursor);
            if (!cursor.atEndOfFile()) {
                logRecordImage("account", cursor.currentRecord().getAcctId());
            }
        }
        closeAcctFile(cursor);
        LOGGER.info(END_OF_EXECUTION, PROGRAM_CBACT01C);
        return completed(cursor);
    }

    /**
     * Opens the account cluster. Translates {@code 0000-ACCTFILE-OPEN} at line 133: arm the coarse variable
     * with the pre-operation sentinel, open for input, normalise the resulting status with no end-of-file
     * arm, and abend on anything other than success.
     *
     * @return an open cursor positioned before the first record
     * @throws AbendException if the open reports anything other than success
     */
    private SequentialCursor<Account> openAcctFile() {
        return openSequentialCursor(PROGRAM_CBACT01C, DD_ACCTFILE, FAILURE_OPENING_ACCTFILE,
                this.accountRepository::count,
                () -> new BoundedKeysetIterator<>("", KEYSET_PAGE_SIZE,
                        (cursor, size) -> this.accountScanRepository
                                .findByAcctIdGreaterThanOrderByAcctIdAsc(
                                        cursor, Limit.of(size.intValue())),
                        Account::getAcctId, Comparator.naturalOrder()));
    }

    /**
     * Reads the next account record. Translates {@code 1000-ACCTFILE-GET-NEXT} at line 92, including the
     * three-way normalisation the read arm alone performs and the display paragraph it performs on success.
     *
     * <p>Returns nothing and mutates the cursor, exactly as the legacy paragraph reads into working storage
     * and sets the end-of-file indicator rather than returning a value.
     *
     * @param cursor the open cursor to advance
     * @throws AbendException if the read reports a status that is neither success nor end of file
     */
    private void acctFileGetNext(final SequentialCursor<Account> cursor) {
        advance(cursor, FAILURE_READING_ACCTFILE);
        if (cursor.hasCurrentRecord()) {
            displayAcctRecord(cursor.currentRecord());
        }
    }

    /**
     * Security-preserving translation of {@code 1100-DISPLAY-ACCT-RECORD} at line 118.
     *
     * <p>The source emits eleven labelled values, including identifiers, three balances, two limits and
     * three dates. None may enter a centralized log. The paragraph remains a distinct method for
     * traceability and emits only a process-scoped account correlation token plus the non-sensitive
     * active-status label.
     *
     * @param account the record just read
     */
    private void displayAcctRecord(final Account account) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        LOGGER.debug(ACCOUNT_RECORD_DETAIL, SensitiveLogRedactor.redact(account.getAcctId()),
                account.getAcctActiveStatus());
    }

    private static void logRecordImage(final String recordType, final String sensitiveRecordKey) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        LOGGER.debug(RECORD_IMAGE, recordType, SensitiveLogRedactor.redact(sensitiveRecordKey));
    }

    /**
     * Closes the account cluster. Translates {@code 9000-ACCTFILE-CLOSE} at line 151, which arms the coarse
     * variable with the pre-operation sentinel by adding eight to zero rather than by a move - a difference
     * of expression and not of meaning - and then normalises with no end-of-file arm.
     *
     * @param cursor the cursor to release
     * @throws AbendException if the close reports anything other than success
     */
    private void closeAcctFile(final SequentialCursor<Account> cursor) {
        closeSequentialCursor(cursor, FAILURE_CLOSING_ACCTFILE);
    }

    // ------------------------------------------------------------------------------------------------
    // CBACT02C - card master reader, five paragraphs
    // ------------------------------------------------------------------------------------------------

    /**
     * Reads the card cluster in ascending key order and reports what it read. Translates the unnamed
     * mainline of {@code app/cbl/CBACT02C.cbl} at lines 70-87.
     *
     * <p>This member has no separate record-display paragraph: the display inside its read paragraph is
     * commented out at line 96, so the only image it emits comes from the mainline at line 78. That absence
     * is why this reader has five paragraphs where the account reader has six, and it is reproduced rather
     * than filled in.
     *
     * @return the member name, the DD name, the number of records read and the status that ended the loop
     * @throws AbendException if the open, a read or the close reports a status the member treats as an error
     */
    public FileReadSummary readCardFile() {
        return readCardFile(Thread.currentThread()::isInterrupted);
    }

    /**
     * Reads the card cluster while observing cooperative cancellation between records.
     *
     * @param stopRequested live cancellation probe
     * @return the completed read summary
     */
    public FileReadSummary readCardFile(final BooleanSupplier stopRequested) {
        BatchCancellation.checkpoint(stopRequested);
        LOGGER.info(START_OF_EXECUTION, PROGRAM_CBACT02C);
        SequentialCursor<Card> cursor = openCardFile();
        while (!cursor.atEndOfFile()) {
            BatchCancellation.checkpoint(stopRequested);
            cardFileGetNext(cursor);
            if (!cursor.atEndOfFile()) {
                logRecordImage("card", cursor.currentRecord().getCardNum());
            }
        }
        closeCardFile(cursor);
        LOGGER.info(END_OF_EXECUTION, PROGRAM_CBACT02C);
        return completed(cursor);
    }

    /**
     * Opens the card cluster. Translates {@code 0000-CARDFILE-OPEN} at line 118.
     *
     * @return an open cursor positioned before the first record
     * @throws AbendException if the open reports anything other than success
     */
    private SequentialCursor<Card> openCardFile() {
        return openSequentialCursor(PROGRAM_CBACT02C, DD_CARDFILE, FAILURE_OPENING_CARDFILE,
                this.cardRepository::count,
                () -> new BoundedKeysetIterator<>("", KEYSET_PAGE_SIZE,
                        (cursor, size) -> this.cardScanRepository
                                .findByCardNumGreaterThanOrderByCardNumAsc(
                                        cursor, Limit.of(size.intValue())),
                        Card::getCardNum, Comparator.naturalOrder()));
    }

    /**
     * Reads the next card record. Translates {@code 1000-CARDFILE-GET-NEXT} at line 92, whose success arm
     * performs no display because the member comments that display out at line 96.
     *
     * @param cursor the open cursor to advance
     * @throws AbendException if the read reports a status that is neither success nor end of file
     */
    private void cardFileGetNext(final SequentialCursor<Card> cursor) {
        advance(cursor, FAILURE_READING_CARDFILE);
    }

    /**
     * Closes the card cluster. Translates {@code 9000-CARDFILE-CLOSE} at line 136.
     *
     * @param cursor the cursor to release
     * @throws AbendException if the close reports anything other than success
     */
    private void closeCardFile(final SequentialCursor<Card> cursor) {
        closeSequentialCursor(cursor, FAILURE_CLOSING_CARDFILE);
    }

    // ------------------------------------------------------------------------------------------------
    // CBACT03C - card cross-reference reader, five paragraphs
    // ------------------------------------------------------------------------------------------------

    /**
     * Reads the card cross-reference cluster in ascending key order and reports what it read. Translates
     * the unnamed mainline of {@code app/cbl/CBACT03C.cbl} at lines 70-87.
     *
     * <p><strong>This is the file the member actually reads, and the attribution matters.</strong> Line 29
     * selects the {@code XREFFILE} DD, line 32 declares the record key as the cross-reference card number,
     * lines 38-40 split a fifty-byte record into a sixteen-character key plus thirty-four bytes of
     * remainder, line 45 includes the card cross-reference copybook, and every failure literal in the
     * member names {@code XREFFILE}. One job member invokes it, {@code app/jcl/READXREF.jcl} step
     * {@code STEP05}, against {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}. Earlier planning material
     * described this member as a category-balance listing driver; that is a documentation error rather than
     * a design choice, and binding this reader to the category balance would compile, would pass a test
     * written under the same misunderstanding, and would report a healthy file while never opening the one
     * the member names. The width coincidence that made the error plausible - both records are fifty bytes -
     * is exactly why it had to be settled by reading the member rather than by reading about it.
     *
     * <p>The record reaches the diagnostic channel <strong>twice</strong> per successful read, once from the
     * read paragraph's own emission at line 96 and once from the mainline's at line 78. The duplication is
     * in the source and is reproduced rather than tidied away; the sibling card reader has that same inner
     * emission commented out at its line 96, which is precisely why one member displays twice and the other
     * once.
     *
     * @return the member name, the DD name, the number of records read and the status that ended the loop
     * @throws AbendException if the open, a read or the close reports a status the member treats as an error
     */
    public FileReadSummary readCardCrossReferenceFile() {
        return readCardCrossReferenceFile(Thread.currentThread()::isInterrupted);
    }

    /**
     * Reads the cross-reference cluster while observing cooperative cancellation between records.
     *
     * @param stopRequested live cancellation probe
     * @return the completed read summary
     */
    public FileReadSummary readCardCrossReferenceFile(final BooleanSupplier stopRequested) {
        BatchCancellation.checkpoint(stopRequested);
        LOGGER.info(START_OF_EXECUTION, PROGRAM_CBACT03C);
        SequentialCursor<CardCrossReference> cursor = openXrefFile();
        while (!cursor.atEndOfFile()) {
            BatchCancellation.checkpoint(stopRequested);
            xrefFileGetNext(cursor);
            if (!cursor.atEndOfFile()) {
                logRecordImage("card-cross-reference", cursor.currentRecord().getXrefCardNum());
            }
        }
        closeXrefFile(cursor);
        LOGGER.info(END_OF_EXECUTION, PROGRAM_CBACT03C);
        return completed(cursor);
    }

    /**
     * Opens the cross-reference cluster. Translates {@code 0000-XREFFILE-OPEN} at line 118.
     *
     * @return an open cursor positioned before the first record
     * @throws AbendException if the open reports anything other than success
     */
    private SequentialCursor<CardCrossReference> openXrefFile() {
        return openSequentialCursor(PROGRAM_CBACT03C, DD_XREFFILE, FAILURE_OPENING_XREFFILE,
                this.cardCrossReferenceRepository::count,
                () -> new BoundedKeysetIterator<>("", KEYSET_PAGE_SIZE,
                        (cursor, size) -> this.cardCrossReferenceScanRepository
                                .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(
                                        cursor, Limit.of(size.intValue())),
                        CardCrossReference::getXrefCardNum, Comparator.naturalOrder()));
    }

    /**
     * Reads the next cross-reference record. Translates {@code 1000-XREFFILE-GET-NEXT} at line 92,
     * including the record display its success arm performs at line 96.
     *
     * @param cursor the open cursor to advance
     * @throws AbendException if the read reports a status that is neither success nor end of file
     */
    private void xrefFileGetNext(final SequentialCursor<CardCrossReference> cursor) {
        advance(cursor, FAILURE_READING_XREFFILE);
        if (cursor.hasCurrentRecord()) {
            logRecordImage("card-cross-reference", cursor.currentRecord().getXrefCardNum());
        }
    }

    /**
     * Closes the cross-reference cluster. Translates {@code 9000-XREFFILE-CLOSE} at line 136.
     *
     * @param cursor the cursor to release
     * @throws AbendException if the close reports anything other than success
     */
    private void closeXrefFile(final SequentialCursor<CardCrossReference> cursor) {
        closeSequentialCursor(cursor, FAILURE_CLOSING_XREFFILE);
    }

    // ------------------------------------------------------------------------------------------------
    // The category balance pass - a job stream's unload, with no program antecedent
    // ------------------------------------------------------------------------------------------------

    /**
     * Reads the transaction category balance cluster in ascending composite-key order and reports what it
     * read.
     *
     * <p><strong>This pass translates no COBOL member, and saying so is the point.</strong> A census across
     * all ten legacy batch programs finds the category balance cluster referenced by exactly two of them -
     * the posting run and the accrual run - and both reach it transactionally, by key, rather than listing
     * it. The only sequential pass over it in the whole estate is a job stream's: {@code app/jcl/PRTCATBL.jcl}
     * step {@code STEP05R} invokes the cataloged copy wrapper {@code app/proc/REPROC.prc} with its control
     * member {@code app/ctl/REPROCT.ctl}, and step {@code STEP10R} invokes the external sort. So this method
     * exists because a job stream needs an ordered sequential pass over the cluster, and it is deliberately
     * <em>not</em> attributed to a paragraph of any member - not to {@code CBACT03C}, which reads the
     * cross-reference cluster above, and not to anything else.
     *
     * <p>What it does borrow, and legitimately, is this class's discipline: the same open, read-to-end and
     * close shape, the same two-level status model in which end of file is a normal outcome and never an
     * error, and the same emit-then-abend ordering on a failure. That discipline is a property of the
     * estate's sequential I/O rather than of any one member, which is why it applies here without a member
     * to cite.
     *
     * <p>Because there is no member, there is no execution banner naming one: a banner reading
     * "start of execution of program" would name a program that never ran. The pass reports the job step it
     * serves instead, and the summary it returns carries that step name in place of a member name.
     *
     * <p>Ordering is the cluster's own composite key - account identifier, then type code, then category
     * code - which is both the sequential order of an indexed read and the order the job's sort
     * specification declares.
     *
     * @return the legacy step name, the DD name, the number of records read and the status that ended the
     *         loop
     * @throws AbendException if the open, a read or the close reports a status treated as an error
     */
    public FileReadSummary readTransactionCategoryBalanceFile() {
        return readTransactionCategoryBalanceFile(Thread.currentThread()::isInterrupted);
    }

    /**
     * Reads the category-balance cluster while observing cooperative cancellation between records.
     *
     * @param stopRequested live cancellation probe
     * @return the completed read summary
     */
    public FileReadSummary readTransactionCategoryBalanceFile(
            final BooleanSupplier stopRequested) {
        BatchCancellation.checkpoint(stopRequested);
        LOGGER.info(START_OF_UNLOAD, LEGACY_UNLOAD_STEP, DD_TCATBALF);
        SequentialCursor<TransactionCategoryBalance> cursor = openTranCatBalFile();
        while (!cursor.atEndOfFile()) {
            BatchCancellation.checkpoint(stopRequested);
            tranCatBalFileGetNext(cursor);
        }
        closeTranCatBalFile(cursor);
        LOGGER.info(END_OF_UNLOAD, LEGACY_UNLOAD_STEP, DD_TCATBALF);
        return completed(cursor);
    }

    /**
     * Opens the category balance cluster for the job stream's unload.
     *
     * @return an open cursor positioned before the first record
     * @throws AbendException if the open reports anything other than success
     */
    private SequentialCursor<TransactionCategoryBalance> openTranCatBalFile() {
        return openSequentialCursor(LEGACY_UNLOAD_STEP, DD_TCATBALF, FAILURE_OPENING_TCATBALF,
                this.transactionCategoryBalanceRepository::count,
                () -> new BoundedKeysetIterator<>("", KEYSET_PAGE_SIZE,
                        this::loadCategoryBalancePage,
                        FileMaintenanceService::categoryBalanceKey,
                        Comparator.naturalOrder()));
    }

    private List<TransactionCategoryBalance> loadCategoryBalancePage(
            final String cursor, final Integer pageSize) {
        final String accountId = keyPart(cursor, 0, TRAN_CAT_BAL_ACCOUNT_KEY_LENGTH);
        final int typeOffset = TRAN_CAT_BAL_ACCOUNT_KEY_LENGTH;
        final String typeCode = keyPart(cursor, typeOffset, TRAN_CAT_BAL_TYPE_KEY_LENGTH);
        final int categoryOffset = typeOffset + TRAN_CAT_BAL_TYPE_KEY_LENGTH;
        final String categoryCode = cursor.length() <= categoryOffset
                ? ""
                : cursor.substring(categoryOffset);
        return this.transactionCategoryBalanceRepository.findAfterKey(
                accountId, typeCode, categoryCode,
                PageRequest.of(0, pageSize.intValue()));
    }

    private static String keyPart(final String key, final int offset, final int width) {
        if (key.length() <= offset) {
            return "";
        }
        return key.substring(offset, Math.min(key.length(), offset + width));
    }

    private static String categoryBalanceKey(final TransactionCategoryBalance balance) {
        return balance.getTrancatAcctId() + balance.getTrancatTypeCd() + balance.getTrancatCd();
    }

    /**
     * Reads the next category balance record.
     *
     * <p>Emits the record image once, not twice. The double emission of the cross-reference reader above is
     * a property of {@code CBACT03C}'s own paragraph structure; a copy utility emits no record image at all,
     * so one diagnostic line per record is already more than the step it stands for produced and a second
     * would be an invention.
     *
     * @param cursor the open cursor to advance
     * @throws AbendException if the read reports a status that is neither success nor end of file
     */
    private void tranCatBalFileGetNext(final SequentialCursor<TransactionCategoryBalance> cursor) {
        advance(cursor, FAILURE_READING_TCATBALF);
        if (cursor.hasCurrentRecord()) {
            final TransactionCategoryBalance record = cursor.currentRecord();
            logRecordImage("transaction-category-balance",
                    record.getTrancatAcctId() + "|" + record.getTrancatTypeCd() + "|"
                            + record.getTrancatCd());
        }
    }

    /**
     * Closes the category balance cluster.
     *
     * @param cursor the cursor to release
     * @throws AbendException if the close reports anything other than success
     */
    private void closeTranCatBalFile(final SequentialCursor<TransactionCategoryBalance> cursor) {
        closeSequentialCursor(cursor, FAILURE_CLOSING_TCATBALF);
    }

    // ------------------------------------------------------------------------------------------------
    // CBCUS01C - customer master reader, five paragraphs, with the renamed abend and status paragraphs
    // ------------------------------------------------------------------------------------------------

    /**
     * Reads the customer cluster in ascending key order and reports what it read. Translates the unnamed
     * mainline of {@code app/cbl/CBCUS01C.cbl} at lines 70-87, whose read paragraph displays the record at
     * line 96 and whose mainline displays it again at line 78.
     *
     * <p>Both displays are reproduced as diagnostics carrying one process-scoped customer correlation
     * token. The identifier itself and every personal field remain withheld.
     *
     * @return the member name, the DD name, the number of records read and the status that ended the loop
     * @throws AbendException if the open, a read or the close reports a status the member treats as an error
     */
    public FileReadSummary readCustomerFile() {
        return readCustomerFile(Thread.currentThread()::isInterrupted);
    }

    /**
     * Reads the customer cluster while observing cooperative cancellation between records.
     *
     * @param stopRequested live cancellation probe
     * @return the completed read summary
     */
    public FileReadSummary readCustomerFile(final BooleanSupplier stopRequested) {
        BatchCancellation.checkpoint(stopRequested);
        LOGGER.info(START_OF_EXECUTION, PROGRAM_CBCUS01C);
        SequentialCursor<Customer> cursor = openCustFile();
        while (!cursor.atEndOfFile()) {
            BatchCancellation.checkpoint(stopRequested);
            custFileGetNext(cursor);
            if (!cursor.atEndOfFile()) {
                logRecordImage("customer", cursor.currentRecord().getCustId());
            }
        }
        closeCustFile(cursor);
        LOGGER.info(END_OF_EXECUTION, PROGRAM_CBCUS01C);
        return completed(cursor);
    }

    /**
     * Opens the customer cluster. Translates {@code 0000-CUSTFILE-OPEN} at line 118.
     *
     * @return an open cursor positioned before the first record
     * @throws AbendException if the open reports anything other than success
     */
    private SequentialCursor<Customer> openCustFile() {
        return openSequentialCursor(PROGRAM_CBCUS01C, DD_CUSTFILE, FAILURE_OPENING_CUSTFILE,
                this.customerRepository::count,
                () -> new BoundedKeysetIterator<>("", KEYSET_PAGE_SIZE,
                        (cursor, size) -> this.customerRepository
                                .findByCustIdGreaterThanOrderByCustIdAsc(
                                        cursor, Limit.of(size.intValue())),
                        Customer::getCustId, Comparator.naturalOrder()));
    }

    /**
     * Reads the next customer record. Translates {@code 1000-CUSTFILE-GET-NEXT} at line 92, including the
     * record display its success arm performs at line 96, reduced to a correlation token.
     *
     * @param cursor the open cursor to advance
     * @throws AbendException if the read reports a status that is neither success nor end of file
     */
    private void custFileGetNext(final SequentialCursor<Customer> cursor) {
        advance(cursor, FAILURE_READING_CUSTFILE);
        if (cursor.hasCurrentRecord()) {
            logRecordImage("customer", cursor.currentRecord().getCustId());
        }
    }

    /**
     * Closes the customer cluster. Translates {@code 9000-CUSTFILE-CLOSE} at line 136.
     *
     * @param cursor the cursor to release
     * @throws AbendException if the close reports anything other than success
     */
    private void closeCustFile(final SequentialCursor<Customer> cursor) {
        closeSequentialCursor(cursor, FAILURE_CLOSING_CUSTFILE);
    }

    // ------------------------------------------------------------------------------------------------
    // The two paragraphs every member shares: one Java method each, both legacy spellings recorded
    // ------------------------------------------------------------------------------------------------

    /**
     * Emits the raw two-byte file status as the legacy status line. This one method is the Java home of
     * {@code 9910-DISPLAY-IO-STATUS} in {@code CBACT01C} at line 176, in {@code CBACT02C} at line 161 and in
     * {@code CBACT03C} at line 161, and of {@code Z-DISPLAY-IO-STATUS} in {@code CBCUS01C} at line 161 - the
     * same paragraph under a different name, which is a source anomaly recorded rather than propagated.
     *
     * <p>The paragraph renders the status into a four-character display field before displaying it, and the
     * rendering has two arms. When the status is not numeric, or its first byte is the implementor-defined
     * class, the first byte is placed verbatim and the second byte's binary value fills the remaining three
     * digits - the legacy moves that byte into the low-order half of a halfword and then into a three-digit
     * field. Otherwise the field is zero-filled and the two status characters occupy its last two positions.
     * Both arms are reproduced, and the display literal precedes the rendered field with no separator exactly
     * as the legacy display concatenated them.
     *
     * <p>It is public because it carries an operator-facing contract that four members share and because the
     * ordering it participates in - status first, abend second - is verified against it. It emits and
     * returns; it never raises, so a caller that has not yet decided to abend may use it.
     *
     * @param rawFileStatus the raw two-character status to render, which may be outside the declared
     *                      vocabulary or malformed without preventing the diagnostic
     * @param operation     the legacy gerund naming the operation that reported the status
     * @param resourceName  the legacy DD name of the resource that reported it
     */
    public void displayIoStatus(final String rawFileStatus, final String operation,
            final String resourceName) {
        LOGGER.error(IO_STATUS_LINE,
                FileStatusException.DISPLAY_PREFIX,
                formatIoStatus(rawFileStatus),
                orMarker(rawFileStatus),
                statusMnemonic(rawFileStatus),
                orMarker(operation),
                orMarker(resourceName));
    }

    /**
     * Abends the run. This one method is the Java home of {@code 9999-ABEND-PROGRAM} in {@code CBACT01C} at
     * line 169, in {@code CBACT02C} at line 154 and in {@code CBACT03C} at line 154, and of
     * {@code Z-ABEND-PROGRAM} in {@code CBCUS01C} at line 154 - the same paragraph under a different name,
     * which is the second of that member's two naming anomalies.
     *
     * <p>The paragraph displays its banner, zeroes a timing value, moves the batch abend code and issues the
     * Language Environment abort. The banner, the code and the raise all belong to the injected abend
     * service, which is why this method delegates rather than reimplementing them; the timing value has no
     * equivalent, because nothing in a Spring runtime consumes it. By the time this method is reached the
     * failure literal and the raw status have already been emitted, so the ordering the legacy guaranteed -
     * diagnostic first, termination second - holds here too.
     *
     * @param programName   the legacy member name, which becomes the abend culprit
     * @param reason        the member's own failure literal, which becomes the abend reason
     * @param rawFileStatus the raw two-character status that caused the failure
     * @param operation     the legacy gerund naming the operation that failed
     * @param resourceName  the legacy DD name of the resource that failed
     * @throws AbendException always, raised by the abend service after it has logged
     */
    private void abendProgram(final String programName, final String reason,
            final String rawFileStatus, final String operation, final String resourceName) {
        this.abendService.abendBatch(programName, reason, rawFileStatus, operation, resourceName);
    }

    // ------------------------------------------------------------------------------------------------
    // The skeleton the four members share, factored into this class rather than into a new type
    // ------------------------------------------------------------------------------------------------

    /**
     * The open paragraph of all four members: arm the coarse variable with the pre-operation sentinel, open
     * the resource for input, normalise with no end-of-file arm, and abend on anything other than success.
     *
     * <p>Opening a key-sequenced cluster for input verifies that it exists and can be read. Its relational
     * equivalent is a query that touches the table and returns without reading it row by row, which is why
     * the probe is separate from the ordered retrieval: the retrieval is deferred to the first read, so a
     * retrieval failure is reported by the read arm and an accessibility failure by the open arm, exactly as
     * the two arms divide on the mainframe. Reporting both through the open would put a read failure on the
     * wrong paragraph.
     *
     * @param <T>                the record type the cluster holds
     * @param programName        the legacy member name
     * @param resourceName       the legacy DD name
     * @param failureLiteral     the member's own display literal for a failed open
     * @param reachabilityProbe  the query that verifies the resource can be read
     * @param keyOrderedRead     the ordered retrieval, invoked on the first read rather than here
     * @return an open cursor positioned before the first record
     * @throws AbendException if the open reports anything other than success
     */
    private <T> SequentialCursor<T> openSequentialCursor(final String programName,
            final String resourceName, final String failureLiteral,
            final LongSupplier reachabilityProbe, final Supplier<Iterator<T>> keyOrderedRead) {
        SequentialCursor<T> cursor = new SequentialCursor<>(programName, resourceName, keyOrderedRead);
        cursor.arm(OPERATION_OPEN);
        String rawFileStatus = FileStatusException.STATUS_SUCCESS;
        try {
            // The probe is the open itself and must run at every log level, so its result is bound to a
            // local before it is reported rather than evaluated inside the reporting call.
            long recordsAvailable = reachabilityProbe.getAsLong();
            LOGGER.debug(OPENED_RESOURCE, programName, resourceName, recordsAvailable);
        } catch (DataAccessException failure) {
            rawFileStatus = DATA_ACCESS_FAILURE_STATUS;
            logDataAccessFailure(cursor, OPERATION_OPEN, failure);
        }
        if (!cursor.normaliseAndRecord(rawFileStatus, false).isAok()) {
            abendOnFailedOperation(cursor, failureLiteral, OPERATION_OPEN);
        }
        cursor.markOpened();
        return cursor;
    }

    /**
     * The read paragraph of all four members: arm the coarse variable, read the next record, normalise with
     * the end-of-file arm recognised, and take one of three branches.
     *
     * <p>This is the method the whole class exists for, so its three branches are worth stating plainly.
     * Success accepts the record and increments the count. End of file sets the indicator that ends the
     * mainline loop and is a normal completion, never an error. Anything else - and that includes the
     * pre-operation sentinel, because the legacy tests only for the success and end-of-file condition names
     * and treats every other value as a failure - emits the failure literal and the status and abends. The
     * switch covers every constant of the coarse enum with no default, so a constant added later cannot slip
     * through unhandled.
     *
     * @param <T>            the record type the cluster holds
     * @param cursor         the open cursor to advance
     * @param failureLiteral the member's own display literal for a failed read
     * @throws AbendException if the read reports a status that is neither success nor end of file
     */
    private <T> void advance(final SequentialCursor<T> cursor, final String failureLiteral) {
        cursor.arm(OPERATION_READ);
        String rawFileStatus;
        T record = null;
        try {
            record = cursor.nextRecord();
            rawFileStatus = record == null
                    ? FileStatusException.STATUS_END_OF_FILE
                    : FileStatusException.STATUS_SUCCESS;
        } catch (DataAccessException failure) {
            rawFileStatus = DATA_ACCESS_FAILURE_STATUS;
            logDataAccessFailure(cursor, OPERATION_READ, failure);
        }
        switch (cursor.normaliseAndRecord(rawFileStatus, true)) {
            case AOK -> cursor.acceptRecord(record);
            case EOF -> cursor.markEndOfFile();
            case ERROR, PENDING -> abendOnFailedOperation(cursor, failureLiteral, OPERATION_READ);
        }
    }

    /**
     * The close paragraph of all four members: arm the coarse variable with the pre-operation sentinel,
     * release the resource, normalise with no end-of-file arm, and abend on anything other than success.
     *
     * <p>The account member arms the sentinel by adding eight to zero and clears it by subtracting the
     * variable from itself where the other three move literals; the arithmetic differs, the meaning does not,
     * and the difference is not reproduced because it is not observable.
     *
     * <p>The error arm of a close is defensive in the legacy too: a close that follows a successful open and
     * a loop which either reached end of file or abended has nothing left to fail on. It is preserved because
     * the paragraph structure is the contract, and it shares every line of its error handling with the open
     * and read arms rather than duplicating them.
     *
     * @param cursor         the cursor to release
     * @param failureLiteral the member's own display literal for a failed close
     * @throws AbendException if the close reports anything other than success
     */
    private void closeSequentialCursor(final SequentialCursor<?> cursor, final String failureLiteral) {
        cursor.arm(OPERATION_CLOSE);
        String rawFileStatus = cursor.release();
        if (!cursor.normaliseAndRecord(rawFileStatus, false).isAok()) {
            abendOnFailedOperation(cursor, failureLiteral, OPERATION_CLOSE);
        }
    }

    /**
     * The error arm shared by every open, read and close: emit the member's own failure literal together with
     * both levels of the status model, emit the status line, abend.
     *
     * <p>Both levels appear in the one record deliberately. An operator reading a legacy joblog saw the raw
     * two-byte status; an engineer reading this translation needs to see which coarse value the raw status
     * normalised to, because that is what the member branched on.
     *
     * @param cursor         the cursor whose most recent operation failed
     * @param failureLiteral the member's own display literal for the failing operation
     * @param operation      the legacy gerund naming that operation
     * @throws AbendException always
     */
    private void abendOnFailedOperation(final SequentialCursor<?> cursor, final String failureLiteral,
            final String operation) {
        String rawFileStatus = cursor.lastFileStatus();
        LOGGER.error(OPERATION_FAILED, failureLiteral, cursor.programName(), operation,
                cursor.resourceName(), orMarker(rawFileStatus), cursor.applResult().applResult());
        displayIoStatus(rawFileStatus, operation, cursor.resourceName());
        abendProgram(cursor.programName(), failureLiteral, rawFileStatus, operation,
                cursor.resourceName());
    }

    /**
     * Emits the completion diagnostic and returns the summary. The legacy members report nothing beyond their
     * closing banner, so this record exists for the callers that replace the job step: it names what was read
     * and the status that ended the loop, which is how a reader proves it terminated at end of file rather
     * than by any other route.
     *
     * @param cursor the cursor whose loop has ended and whose resource has been released
     * @return the summary of the completed read
     */
    private static FileReadSummary completed(final SequentialCursor<?> cursor) {
        FileReadSummary summary = cursor.toSummary();
        LOGGER.info(READER_COMPLETED, summary.programName(), summary.resourceName(),
                summary.recordsRead(), summary.terminalFileStatus());
        return summary;
    }

    /**
     * Normalises a raw two-byte file status into the coarse result the members branch on: success becomes the
     * all-clear value, end of file becomes the end-of-file value when the calling arm recognises one, and
     * every other value - declared or not, including one the runtime reports that the legacy never tested -
     * becomes the error value. The raw status is not consumed here; the caller keeps it for the diagnostic.
     *
     * <p>The flag is what keeps the translation faithful. The read arm passes it set, because its paragraph
     * has three branches; the open and close arms pass it clear, because theirs have two, and a status of end
     * of file reported by an open or a close is therefore an error in the legacy and an error here.
     *
     * @param rawFileStatus        the raw two-character status, which may be {@code null} or malformed
     * @param endOfFileRecognised  whether the calling arm has an end-of-file branch
     * @return the coarse result
     */
    private static ApplResult normalise(final String rawFileStatus, final boolean endOfFileRecognised) {
        if (FileStatusException.STATUS_SUCCESS.equals(rawFileStatus)) {
            return ApplResult.AOK;
        }
        if (endOfFileRecognised && FileStatusException.STATUS_END_OF_FILE.equals(rawFileStatus)) {
            return ApplResult.EOF;
        }
        return ApplResult.ERROR;
    }

    /**
     * Renders a raw status into the legacy four-character display field. See the status-display method for
     * the two arms and why both exist.
     *
     * <p>Digits are tested against the ASCII range rather than by a Unicode-aware predicate, and the three
     * decimal digits are assembled arithmetically rather than formatted, so neither the test nor the
     * rendering can vary with a default locale.
     *
     * @param rawFileStatus the raw two-character status, possibly {@code null} or of another length
     * @return four characters, or a marker when the value could not have reached the legacy field at all
     */
    private static String formatIoStatus(final String rawFileStatus) {
        if (rawFileStatus == null || rawFileStatus.length() != FileStatusException.CODE_LENGTH) {
            return UNFORMATTABLE_IO_STATUS;
        }
        char firstByte = rawFileStatus.charAt(0);
        char secondByte = rawFileStatus.charAt(1);
        StringBuilder display = new StringBuilder(IO_STATUS_DISPLAY_LENGTH);
        if (isAsciiDigit(firstByte) && isAsciiDigit(secondByte)
                && firstByte != IMPLEMENTOR_DEFINED_CLASS) {
            display.append('0').append('0').append(firstByte).append(secondByte);
            return display.toString();
        }
        int binaryValue = secondByte & LOW_ORDER_BYTE_MASK;
        display.append(firstByte)
                .append(digitAt(binaryValue / HUNDREDS))
                .append(digitAt(binaryValue / TENS % TENS))
                .append(digitAt(binaryValue % TENS));
        return display.toString();
    }

    /**
     * @param candidate the character to test
     * @return whether the character is one of the ten ASCII digits, independently of any locale
     */
    private static boolean isAsciiDigit(final char candidate) {
        return candidate >= '0' && candidate <= '9';
    }

    /**
     * @param value a single decimal digit
     * @return the ASCII character for that digit, assembled without a formatter so no locale applies
     */
    private static char digitAt(final int value) {
        return (char) ('0' + value);
    }

    /**
     * @param rawFileStatus the raw status to name
     * @return the declared name of the status, or a marker when it was not supplied or falls outside the
     *         declared vocabulary, which a live dataset can legitimately return
     */
    private static String statusMnemonic(final String rawFileStatus) {
        return FileStatus.fromCode(rawFileStatus)
                .map(FileStatus::name)
                .orElse(rawFileStatus == null ? NOT_SUPPLIED : OUTSIDE_VOCABULARY);
    }

    /**
     * @param value a diagnostic value that may be absent
     * @return the value, or a marker in its place, so that a diagnostic never renders an empty field
     */
    private static String orMarker(final String value) {
        if (value == null || value.isBlank()) {
            return NOT_SUPPLIED;
        }
        return value;
    }

    /**
     * Emits a bounded chain of failure type names. The mainframe had no throwable, and exception
     * messages can carry rejected values, connection details or SQL text, so neither the failure object
     * nor any of its messages or frames is handed to the appender.
     *
     * @param cursor    the cursor whose operation failed
     * @param operation the legacy gerund naming that operation
     * @param failure   the failure to record
     */
    private static void logDataAccessFailure(final SequentialCursor<?> cursor, final String operation,
            final DataAccessException failure) {
        LOGGER.error(DATA_ACCESS_FAILED, cursor.programName(), operation, cursor.resourceName(),
                DATA_ACCESS_FAILURE_STATUS, FailureDiagnostics.failureChainOf(failure));
    }

    // ------------------------------------------------------------------------------------------------
    // Nested types: the returned summary, the coarse status model, and the per-invocation cursor
    // ------------------------------------------------------------------------------------------------

    /**
     * What a completed reader reports. The legacy members returned nothing beyond a completion code, so this
     * exists for the caller that replaces the job step.
     *
     * <p>The terminal status is the one value that distinguishes a normal completion from every other route
     * out of a read loop: a reader that returns at all reports end of file, because a status the member
     * treats as an error abends instead of returning. A caller that wants that assertion rather than the
     * literal can ask for it directly.
     *
     * @param programName        the legacy member name whose paragraphs were run
     * @param resourceName       the legacy DD name that was read
     * @param recordsRead        how many records the read loop accepted
     * @param terminalFileStatus the raw two-character status that ended the read loop
     */
    public record FileReadSummary(String programName, String resourceName, long recordsRead,
            String terminalFileStatus) {

        /**
         * @throws NullPointerException     if any name or the terminal status is {@code null}
         * @throws IllegalArgumentException if the record count is negative
         */
        public FileReadSummary {
            Objects.requireNonNull(programName, "programName");
            Objects.requireNonNull(resourceName, "resourceName");
            Objects.requireNonNull(terminalFileStatus, "terminalFileStatus");
            if (recordsRead < 0) {
                throw new IllegalArgumentException(
                        "A record count cannot be negative, but " + recordsRead + " was supplied.");
            }
        }

        /**
         * @return whether the read loop ended at end of file, which is the only route by which a reader
         *         returns rather than abends
         */
        public boolean endedAtEndOfFile() {
            return FileStatusException.STATUS_END_OF_FILE.equals(this.terminalFileStatus);
        }
    }

    /**
     * The coarse result the members branch on, and the reason this class exists.
     *
     * <p>The legacy declares one binary working-storage variable and two condition names over it: one bound
     * to zero for all clear and one bound to sixteen for end of file. Twelve is the error value and carries
     * no condition name of its own, so the members reach it by elimination; eight is moved into the variable
     * immediately before every open and close so that a path which sets no result is not mistaken for
     * success. All four values are declared here, in their numeric order, because all four are reachable and
     * because the read arm's branch treats the pre-operation sentinel exactly as the legacy does - as
     * something that is neither all clear nor end of file, and therefore a failure.
     *
     * <p>It is nested and private on purpose. The raw two-byte vocabulary is a domain concern and lives in
     * the domain enum this class consumes; the coarse outcome is a translation concern and belongs to the
     * layer that performs the translation, so declaring it as a type of its own in either package would put
     * it in the wrong place and risk a future caller branching on a raw code the members never tested.
     */
    private enum ApplResult {

        /** All clear: the operation succeeded. Bound to the legacy all-OK condition name. */
        AOK(0),

        /** Pre-operation sentinel, armed before every open, read and close. Bound to no condition name. */
        PENDING(8),

        /** The error value. Bound to no condition name; the members reach it by elimination. */
        ERROR(12),

        /** End of file: no next logical record. Bound to the legacy end-of-file condition name. */
        EOF(16);

        private final int coarseValue;

        ApplResult(final int legacyCoarseValue) {
            this.coarseValue = legacyCoarseValue;
        }

        /**
         * @return the value the legacy moved into its coarse variable, carried so that a diagnostic can show
         *         both levels of the status model rather than only the raw code
         */
        int applResult() {
            return this.coarseValue;
        }

        /**
         * @return whether this is the all-clear value, which is the only condition the open and close arms
         *         accept and the first the read arm tests
         */
        boolean isAok() {
            return this == AOK;
        }
    }

    /**
     * One sequential read in progress: the Java counterpart of a COBOL file connector together with the
     * coarse status variable that accompanies it.
     *
     * <p>It is created per invocation and referred to only by a local of the reader that created it, which is
     * what keeps the service itself stateless: the count, the position, the current record and both levels of
     * the status live here and never in a field of the service or in static state.
     *
     * <p>The ordered retrieval is held as a supplier and performed on the first advance rather than at
     * construction, so that a retrieval failure is reported by the read arm and an accessibility failure by
     * the open arm, which is how the two arms divide on the mainframe.
     *
     * @param <T> the record type the cluster holds
     */
    private static final class SequentialCursor<T> {

        private final String programName;

        private final String resourceName;

        private final Supplier<Iterator<T>> keyOrderedRead;

        private Iterator<T> records;

        private ApplResult applResult = ApplResult.PENDING;

        private String lastFileStatus;

        private boolean open;

        private boolean endOfFile;

        private long recordsRead;

        private T currentRecord;

        SequentialCursor(final String legacyProgramName, final String legacyResourceName,
                final Supplier<Iterator<T>> orderedRead) {
            this.programName = Objects.requireNonNull(legacyProgramName, "legacyProgramName");
            this.resourceName = Objects.requireNonNull(legacyResourceName, "legacyResourceName");
            this.keyOrderedRead = Objects.requireNonNull(orderedRead, "orderedRead");
        }

        String programName() {
            return this.programName;
        }

        String resourceName() {
            return this.resourceName;
        }

        /**
         * @return the coarse result of the most recent operation, or the pre-operation sentinel when the
         *         operation has been armed but has not yet reported
         */
        ApplResult applResult() {
            return this.applResult;
        }

        /**
         * @return the raw status of the most recent operation, or {@code null} when the current operation has
         *         been armed and has not yet reported one
         */
        String lastFileStatus() {
            return this.lastFileStatus;
        }

        boolean atEndOfFile() {
            return this.endOfFile;
        }

        boolean hasCurrentRecord() {
            return this.currentRecord != null;
        }

        T currentRecord() {
            return this.currentRecord;
        }

        /**
         * Arms the coarse variable with the pre-operation sentinel and discards the previous raw status, so
         * that an operation which reports nothing cannot be read as a success carried over from the last one.
         *
         * @param operation the legacy gerund naming the operation about to be attempted
         */
        void arm(final String operation) {
            this.applResult = ApplResult.PENDING;
            this.lastFileStatus = null;
            LOGGER.trace(ARMED_APPL_RESULT, this.programName, this.resourceName, operation,
                    this.applResult.applResult());
        }

        /**
         * Records the raw status an operation reported and normalises it into the coarse result, keeping both
         * levels available to the diagnostic.
         *
         * @param rawFileStatus       the raw status the operation reported
         * @param endOfFileRecognised whether the calling arm has an end-of-file branch
         * @return the coarse result the caller must branch on
         */
        ApplResult normaliseAndRecord(final String rawFileStatus, final boolean endOfFileRecognised) {
            this.lastFileStatus = rawFileStatus;
            this.applResult = normalise(rawFileStatus, endOfFileRecognised);
            return this.applResult;
        }

        /** Marks the resource open, which the close arm requires in order to report success. */
        void markOpened() {
            this.open = true;
        }

        /**
         * Advances the underlying retrieval, performing it first if this is the first advance.
         *
         * @return the next record, or {@code null} when no next logical record exists
         * @throws DataAccessException if the ordered retrieval fails, which the read arm turns into the
         *                             permanent-error status
         */
        T nextRecord() {
            if (this.records == null) {
                this.records = this.keyOrderedRead.get();
            }
            if (!this.records.hasNext()) {
                return null;
            }
            return this.records.next();
        }

        /**
         * Accepts a record the read arm reported as successfully read.
         *
         * @param record the record just read
         */
        void acceptRecord(final T record) {
            this.currentRecord = record;
            this.recordsRead++;
        }

        /**
         * Sets the indicator that ends the mainline loop. End of file is a normal completion and is never
         * routed to the error arm.
         */
        void markEndOfFile() {
            this.endOfFile = true;
            this.currentRecord = null;
        }

        /**
         * Releases the resource.
         *
         * <p>Nothing the provider holds survives the ordered retrieval, so releasing amounts to marking the
         * connector closed and dropping the current record. The one status other than success this can report
         * is the defensive arm the legacy close paragraph also carries: a close of something that was never
         * opened. The retrieval's iterator is left as it stands, because the cursor becomes unreachable when
         * the reader that owns it returns.
         *
         * @return the raw status of the release
         */
        String release() {
            if (!this.open) {
                return DATA_ACCESS_FAILURE_STATUS;
            }
            this.open = false;
            this.currentRecord = null;
            return FileStatusException.STATUS_SUCCESS;
        }

        /**
         * @return what this cursor read, with the raw status that ended its loop
         */
        FileReadSummary toSummary() {
            String terminalFileStatus = this.endOfFile
                    ? FileStatusException.STATUS_END_OF_FILE
                    : FileStatusException.STATUS_SUCCESS;
            return new FileReadSummary(this.programName, this.resourceName, this.recordsRead,
                    terminalFileStatus);
        }
    }
}
