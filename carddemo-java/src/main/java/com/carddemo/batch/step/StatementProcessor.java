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
package com.carddemo.batch.step;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.Transaction;
import com.carddemo.service.StatementDataAccessService;
import com.carddemo.service.StatementGenerationService;
import com.carddemo.service.StatementGenerationService.StatementRun;
import com.carddemo.service.StatementTransactionSource;
import com.carddemo.util.StatementHtmlTemplates;
import com.carddemo.util.StatementTextTemplates;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

/**
 * The per-card statement assembly stage of the statement job: it drives one whole run of the legacy
 * statement generator and hands on both of its record streams, each proved to its own contracted
 * width.
 *
 * <p>Legacy provenance: AWS CardDemo z/OS estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The authority members are
 * {@code [app/cbl/CBSTM03A.CBL]} - 924 lines, 25 paragraphs, dual output - its file-handling
 * subprogram {@code [app/cbl/CBSTM03B.CBL]}, the statement work area
 * {@code [app/cpy/COSTM01.CPY]}, and the job stream {@code [app/jcl/CREASTMT.JCL]}. Those members
 * are read as reference and never copied: this file cites member names, paragraph names, line
 * numbers, data-definition names, record widths and raw status codes, and transcribes no source
 * text.
 *
 * <h2>What this stage is, and what it deliberately is not</h2>
 *
 * <p>It is the batch adapter around {@link StatementGenerationService}. That service is the module's
 * translation of the legacy generator and it owns everything that makes the translation faithful:
 * the explicit phase enum, the {@code while} loop over the ordered {@code switch} that re-enters
 * after every state change, the thirteen data-access call sites, the bounded card table, the two
 * banners, and the emission order of every literal. This stage is where the batch framework meets
 * that service, and it adds exactly four things the service cannot add for itself - the item
 * contract, the postcondition proofs, the meters, and the summary event.
 *
 * <p><strong>It is not a second state machine.</strong> No phase enum is declared here, no phase is
 * driven from here, and no phase is called from the phase before it. Folding the phases into nested
 * calls is the one refactor that cannot reproduce dispatcher re-entry, so the structure stays in the
 * service and this stage delegates a whole run in a single call.
 *
 * <p>It is not a transport boundary either. Emitting the returned records to a destination is the
 * writer's work, and the destination is the job configuration's decision, so nothing here opens a
 * file, names a dataset, resolves a path or touches an object store.
 *
 * <h2>The dispatcher's clause order is not the run's execution order</h2>
 *
 * <p>{@code WS-FL-DD} is the eight-character phase selector initialised at
 * {@code [app/cbl/CBSTM03A.CBL:L67]} to the transaction-file phase. The dispatcher at
 * {@code [app/cbl/CBSTM03A.CBL:L296]} tests it through six clauses in this <em>source</em> order,
 * which is contractual because a COBOL selection is evaluated top down and stops at its first
 * match: the transaction-file phase at {@code L299}, the cross-reference-file phase at {@code L302},
 * the customer-file phase at {@code L305}, the account-file phase at {@code L308}, the
 * transaction-read phase at {@code L311}, and the catch-all that transfers to the program exit at
 * {@code L313}.
 *
 * <p>The order in which a run actually visits them is <strong>different</strong>, because each phase
 * sets a new selector value and jumps backward to the dispatcher, which re-branches on the new
 * value. The transaction-file phase opens the work file, reads its first record and moves the
 * transaction-read selector at {@code L760} before jumping back at {@code L761}; the read phase
 * tabulates every remaining record through its own inner loop and its exit moves the
 * cross-reference selector at {@code L851} before jumping back at {@code L852}; the
 * cross-reference open moves the customer selector at {@code L779} and jumps back at {@code L780};
 * the customer open moves the account selector at {@code L797} and jumps back at {@code L798}; and
 * the account open transfers straight to the mainline at {@code L815}, which runs to completion and
 * falls out of its bottom into the program exit at {@code L341}.
 *
 * <p>A completed run therefore enters the dispatcher exactly
 * {@link #EXPECTED_DISPATCH_ENTRY_COUNT} times, in the execution order
 * {@link #EXPECTED_DISPATCH_SEQUENCE} publishes, and
 * {@link #process(StatementTransactionSource)} proves that trace on every item. Proving it here rather
 * than trusting it is the point: re-entry is the behaviour a
 * reader most needs to be able to confirm, a nested-call refactor of the service would still
 * compile and would still produce records, and this assertion is what would fail if one were ever
 * made.
 *
 * <h2>The item is the frozen projected source, not a launch parameter</h2>
 *
 * <p>The legacy step at {@code [app/jcl/CREASTMT.JCL:L79]} carries <strong>no parameter of any
 * kind</strong> - no date, no range, no selector. Its inputs are four data definitions and its
 * outputs are two, and the only thing distinguishing one run from another is the content of the
 * transaction work file that the three preceding steps build. The item is therefore a frozen
 * {@link StatementTransactionSource} over those projected records. Passing the source explicitly is
 * what prevents the generator from silently observing whatever the live transaction repository happens
 * to hold after the sort step.
 *
 * <p><strong>No date parameter is invented.</strong> The sibling report job does carry one, and the
 * temptation to give this job the same shape for symmetry is exactly the feature expansion the
 * migration constraints forbid.
 *
 * <h2>What the parent job owns, and this stage must not restate</h2>
 *
 * <p>{@code CreateStatementJobConfig} owns the job stream's four steps, its three condition-code
 * gates at {@code [app/jcl/CREASTMT.JCL:L56, L66, L79]}, the transient work resource the first
 * three steps define, load and clear, and the sort specification at
 * {@code [app/jcl/CREASTMT.JCL:L53]} - card number then transaction identifier, both as character
 * data, both ascending - together with the reprojection at {@code L54} that keeps 328 of the
 * record's 350 bytes and truncates the processing timestamp by two.
 *
 * <p>This file consequently <strong>declares and imports no comparator, no comparator registry, no
 * comparison helper and no field offset</strong>, and performs no sort, no reordering and no
 * reprojection. That is not fastidiousness. The same physical card-number field is typed as zoned
 * decimal by another job of the same estate and as character by this one, so a comparator shared
 * between the two would hand one of them the other's semantics without anything failing to compile.
 * The ordering the run depends on is established once by the job's private comparator and preserved by
 * the transient work resource's key sequence; the data-access service consumes that frozen order and
 * performs no second query or sort.
 *
 * <p>Condition-code transitions, transient resource setup and destination selection are likewise the
 * job configuration's, not this stage's.
 *
 * <h2>Dual output at two widths, and the record-length conflict this stage resolves</h2>
 *
 * <p>The plain statement record is 80 characters at {@code [app/cbl/CBSTM03A.CBL:L45]} and the HTML
 * statement record is 100 at {@code [app/cbl/CBSTM03A.CBL:L47]}; both files are written in the same
 * run. Every record of both streams is measured on its <strong>encoded byte array</strong> before it
 * is handed on, and the two widths come from the two layout authorities -
 * {@link StatementTextTemplates#STATEMENT_RECORD_LENGTH} and
 * {@link StatementHtmlTemplates#HTML_RECORD_LENGTH} - rather than being restated here.
 *
 * <p><strong>The job stream contradicts itself about the HTML width, and this is the resolution.</strong>
 * The step that clears the previous run's output declares the HTML data definition with a record
 * length of 80 at {@code [app/jcl/CREASTMT.JCL:L69]}, while the step that creates it declares 100 at
 * {@code [app/jcl/CREASTMT.JCL:L94]}. The declaration that governs is the one agreeing with the
 * program's own file description and with the data definition under which the file is actually
 * written: <strong>100</strong>. The 80 belongs to a deletion step that never writes a byte, and
 * treating it as authoritative would truncate every HTML record by twenty characters. This stage
 * never emits an 80-byte HTML record, and the conflict is recorded in the decision log.
 *
 * <p>Purity is proved before width, and the order is not interchangeable. An encoder asked to emit a
 * character the charset cannot represent substitutes a single replacement byte for it, which keeps
 * the byte count right and makes the content wrong, so a width measured without proving purity first
 * can be satisfied by a record that has already been corrupted. Neither proof uses a character count:
 * a character count and a byte count coincide only for pure US-ASCII, which is the very property
 * being established.
 *
 * <p>No line terminator is added, checked for or assumed anywhere on this path. A logical record is
 * its content and nothing else; whether and how records are separated on a destination is the
 * writer's concern, which is what keeps a golden-fixture comparison byte-exact on any host.
 *
 * <h2>Templates only, and no engine of any kind</h2>
 *
 * <p>Every literal in both streams comes from {@link StatementTextTemplates} and
 * {@link StatementHtmlTemplates}, emitted in source order by the service. This stage introduces no
 * templating engine, no markup builder, no pretty-printer, no whitespace normaliser and no escaping
 * pass, and it neither rewrites nor reorders a record it is handed. The legacy emits fixed-width
 * literal constants and byte parity is achievable only by doing the same, which is why the pair of
 * adjacent spaces inside the table tag at {@code [app/cbl/CBSTM03A.CBL:L157]} and the malformed
 * paragraph tag both survive exactly as the legacy emits them.
 *
 * <h2>Bounded grouping is a layout fact, never a tuning value</h2>
 *
 * <p>The card table occurs 51 times at {@code [app/cbl/CBSTM03A.CBL:L226]} and its nested
 * transaction table occurs 10 times at {@code L228}, so the hard bound is
 * {@value StatementGenerationService#MAX_CARD_ENTRIES} cards by
 * {@value StatementGenerationService#MAX_TRANSACTIONS_PER_CARD} transactions per card. A run
 * presenting more distinct card numbers, or more transactions on one card, overruns its subscript on
 * the mainframe; the service detects the overrun and reports it through the same diagnose-then-abend
 * path as a failed file operation rather than growing a buffer that would accept input the source
 * cannot. This stage does not relax that bound, does not re-implement it, and proves on every item
 * that the run it received respected it.
 *
 * <p>Those two counts are <strong>fixed source-layout facts</strong>. Nothing here derives a chunk
 * size, a thread count, a timeout, a heap figure, a retry or skip limit, a backoff, a connection
 * count or a throughput claim from them, or declares any such figure at all.
 *
 * <h2>State, instrumentation, diagnostics and sequencing</h2>
 *
 * <p>The class holds <strong>no mutable field</strong>: no counter, no accumulated list, no current
 * card and no phase. Every value of a run lives in the holder the service creates for that run and
 * in the immutable result it returns, so this instance is safe to register once and reuse, and it
 * cannot become the place where one execution's total leaks into the next.
 *
 * <p>Instrumentation is coordinated rather than duplicated. A whole legacy program lifecycle is
 * timed where a step is timed - by {@link AbstractCobolStep} for a tasklet step and by the job
 * configuration's own step-level meters otherwise - and the timer here does not replace that. It
 * measures the delegated generation together with its postcondition proofs, which for this feature is
 * one item and therefore very nearly the whole step, and it exists so that a failed generation
 * appears on the metrics endpoint as its own series rather than as a hidden contributor to the
 * successful one.
 *
 * <p>Diagnostics go through the logging facade only, never a console stream and never a stack-trace
 * print. <strong>No record content is logged</strong>, in whole or in part: a statement carries a
 * customer's assembled name and address from {@link Customer}, an account identifier and balance
 * from {@link Account}, a card number reached through {@link CardCrossReference}, and per-transaction
 * descriptions and amounts from {@link Transaction}. The summary event therefore carries counts, the
 * resource name and the observed phase count, and nothing else.
 *
 * <p>Nothing is caught for translation. The service diagnoses a technical failure - naming the
 * failing operation, the resource and the raw two-character file status - and abends after doing so,
 * and that ordering must not be disturbed by an intervening handler here. End of file is normal
 * control flow inside the service and never surfaces as a failure. The only exception handling on
 * this path re-tags the timer and rethrows the very same exception.
 *
 * <p>Execution is strictly sequential and deterministic. No task executor, partitioning, parallel
 * stream or asynchronous emission may be introduced: the run's card grouping, its statement order and
 * its per-card transaction order are all order-dependent, and interleaving them destroys the ordering
 * the output is defined by.
 *
 * <h2>Why the two regulated-field operations are injected</h2>
 *
 * <p>{@link StatementGenerationService#generate(UnaryOperator, UnaryOperator)} takes two operations
 * because the legacy customer file held its two regulated identifiers in the clear and this module
 * protects them at rest: composing a customer record image needs the cleartext recovered, and reading
 * that image back into an entity needs it sealed again. The two are halves of one policy, that policy
 * belongs to the composition root rather than to a batch stage, and this stage holds no key and no
 * cryptographic collaborator. They are therefore constructor parameters supplied by the job
 * configuration, which is the layer permitted to read wiring.
 *
 * <p>Passing the identity operation for either is not a shortcut and will not work: the image
 * composer rejects a stored envelope for overflowing a nine-byte field, and the customer entity
 * rejects cleartext outright.
 *
 * @see StatementGenerationService
 * @see StatementDataAccessService
 * @see StatementTextTemplates
 * @see StatementHtmlTemplates
 * @see AbstractCobolStep
 * @since 1.0.0
 */
public final class StatementProcessor implements ItemProcessor<StatementTransactionSource, StatementRun> {

    /**
     * Diagnostics for this stage.
     *
     * <p>Used for the per-run summary and for the postcondition breaches, and for nothing else. No
     * statement record, no HTML record and no field of a customer, account, card or transaction is
     * ever written to a log, in whole or in part.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(StatementProcessor.class);

    /**
     * Name of the legacy job stream whose statement step this stage replaces,
     * {@code [app/jcl/CREASTMT.JCL:L1]}.
     *
     * <p>Published so that the job configuration, the traceability matrix and the tests can name the
     * same origin without restating a literal in three places. It is an identifier for a job, never a
     * path or a dataset name, and nothing resolves a resource from it.
     */
    public static final String LEGACY_JOB = "CREASTMT";

    /**
     * The legacy batch program this stage's record content comes from, taken from
     * {@link StatementGenerationService#PROGRAM_NAME} rather than restated so the module names it in
     * exactly one place.
     */
    public static final String LEGACY_PROGRAM = StatementGenerationService.PROGRAM_NAME;

    /**
     * The legacy file-handling subprogram the generator reaches at its thirteen call sites, and the
     * member whose parameter object {@link StatementDataAccessService} ports.
     */
    public static final String LEGACY_SUBPROGRAM = "CBSTM03B";

    /**
     * Name of the legacy step that ran the statement program itself, which is this stage,
     * {@code [app/jcl/CREASTMT.JCL:L79]}. It is the last of the job's four steps and the third to
     * carry a condition-code gate.
     */
    public static final String LEGACY_STATEMENT_STEP = "STEP040";

    /**
     * Name of the legacy step that sorted and reprojected the transaction master into the work
     * resource, {@code [app/jcl/CREASTMT.JCL:L44]}. Its specification belongs to the job
     * configuration; it is named here only so a reader can find the ordering's origin.
     */
    public static final String LEGACY_SORT_STEP = "STEP010";

    /**
     * Name of the legacy step that loaded the sorted sequential result into the keyed work resource,
     * {@code [app/jcl/CREASTMT.JCL:L56]}. The first of the job's three condition-code gates.
     */
    public static final String LEGACY_LOAD_STEP = "STEP020";

    /**
     * Name of the legacy step that cleared the previous run's two output datasets,
     * {@code [app/jcl/CREASTMT.JCL:L66]}. The second condition-code gate, and the step whose
     * contradictory record-length declaration this class resolves.
     */
    public static final String LEGACY_CLEAR_STEP = "STEP030";

    /**
     * Data-definition name of the transaction work resource the frozen source represents, taken from
     * {@link StatementDataAccessService#DD_TRNXFILE}.
     *
     * <p>It is the first of the statement step's four inputs at {@code [app/jcl/CREASTMT.JCL:L83]}
     * and the resource the phase selector is initialised to at {@code [app/cbl/CBSTM03A.CBL:L67]}.
     */
    public static final String INPUT_DD_TRNXFILE = StatementDataAccessService.DD_TRNXFILE;

    /**
     * Data-definition name of the card cross-reference input, taken from
     * {@link StatementDataAccessService#DD_XREFFILE}. The mainline reads it sequentially, one record
     * per statement, at {@code [app/cbl/CBSTM03A.CBL:L345]}.
     */
    public static final String INPUT_DD_XREFFILE = StatementDataAccessService.DD_XREFFILE;

    /**
     * Data-definition name of the customer input, taken from
     * {@link StatementDataAccessService#DD_CUSTFILE}. Read by key at
     * {@code [app/cbl/CBSTM03A.CBL:L368]}.
     */
    public static final String INPUT_DD_CUSTFILE = StatementDataAccessService.DD_CUSTFILE;

    /**
     * Data-definition name of the account input, taken from
     * {@link StatementDataAccessService#DD_ACCTFILE}. Read by key at
     * {@code [app/cbl/CBSTM03A.CBL:L392]}.
     */
    public static final String INPUT_DD_ACCTFILE = StatementDataAccessService.DD_ACCTFILE;

    /**
     * Data-definition name of the plain statement output, {@code [app/jcl/CREASTMT.JCL:L87]} and
     * {@code SELECT STMT-FILE ASSIGN TO STMTFILE} at {@code [app/cbl/CBSTM03A.CBL:L39]}. Named for
     * diagnostics and meter tags; this stage writes to no destination.
     */
    public static final String OUTPUT_DD_STMTFILE = "STMTFILE";

    /**
     * Data-definition name of the HTML statement output, {@code [app/jcl/CREASTMT.JCL:L92]} and
     * {@code SELECT HTML-FILE ASSIGN TO HTMLFILE} at {@code [app/cbl/CBSTM03A.CBL:L40]}.
     */
    public static final String OUTPUT_DD_HTMLFILE = "HTMLFILE";

    /**
     * Fixed width, in encoded US-ASCII bytes, of every plain statement record this stage hands on.
     *
     * <p>Taken from {@link StatementTextTemplates#STATEMENT_RECORD_LENGTH} rather than restated,
     * because the layout has exactly one authority. The legacy file description at
     * {@code [app/cbl/CBSTM03A.CBL:L45]} and the output data definition at
     * {@code [app/jcl/CREASTMT.JCL:L89]} both declare the same figure, which is what makes the width
     * a contract rather than a convention.
     */
    public static final int STATEMENT_RECORD_LENGTH = StatementTextTemplates.STATEMENT_RECORD_LENGTH;

    /**
     * Fixed width, in encoded US-ASCII bytes, of every HTML statement record this stage hands on.
     *
     * <p>Taken from {@link StatementHtmlTemplates#HTML_RECORD_LENGTH}. The legacy file description at
     * {@code [app/cbl/CBSTM03A.CBL:L47]} and the creating data definition at
     * {@code [app/jcl/CREASTMT.JCL:L94]} agree on this figure; the clearing step's contradictory 80
     * at {@code [app/jcl/CREASTMT.JCL:L69]} is the resolved conflict described on this class and is
     * never emitted.
     */
    public static final int HTML_RECORD_LENGTH = StatementHtmlTemplates.HTML_RECORD_LENGTH;

    /**
     * Declared width of the data-definition field of the legacy parameter object, taken from
     * {@link StatementDataAccessService#DD_NAME_WIDTH}. The generator's phase selectors and file
     * requests remain bound to this width even though the batch item is now the frozen source itself.
     */
    public static final int DD_NAME_WIDTH = StatementDataAccessService.DD_NAME_WIDTH;

    /**
     * The eight-character phase selector the transaction-file phase moves at
     * {@code [app/cbl/CBSTM03A.CBL:L760]} before jumping back to the dispatcher at {@code L761}.
     *
     * <p>The only one of the six selector values that is not also a data-definition name, which is
     * why it is declared here as a legacy value while the other four are taken from
     * {@link StatementDataAccessService}. Exactly {@value #DD_NAME_WIDTH} characters, like the field
     * that holds it.
     */
    public static final String PHASE_SELECTOR_READTRNX = "READTRNX";

    /**
     * The phase selector values a completed run visits, in the order the run visits them.
     *
     * <p>Deliberately <strong>not</strong> the dispatcher's clause order. The clauses are tested in
     * the source order set out on this class - transaction file, cross-reference file, customer file,
     * account file, transaction read, catch-all - and a run visits them in the order below because
     * each phase sets a new selector and re-enters the dispatcher. Publishing the execution order
     * beside the documented clause order is what makes the difference between the two auditable
     * instead of merely asserted.
     *
     * <p>Five entries, not six: the sixth dispatcher entry is the catch-all clause, whose selector is
     * by definition any value the first five do not match, so it is verified as "none of these five"
     * rather than by naming an implementation-side constant.
     */
    public static final List<String> EXPECTED_DISPATCH_SEQUENCE = List.of(INPUT_DD_TRNXFILE,
            PHASE_SELECTOR_READTRNX, INPUT_DD_XREFFILE, INPUT_DD_CUSTFILE, INPUT_DD_ACCTFILE);

    /**
     * How many times a completed run enters the dispatcher: the five phases of
     * {@link #EXPECTED_DISPATCH_SEQUENCE} plus the catch-all that ends the run.
     *
     * <p>Exactly this many, because the transaction-read phase loops within itself through the
     * backward jump at {@code [app/cbl/CBSTM03A.CBL:L840]} rather than through the dispatcher, and
     * because the account-file phase transfers to the mainline at {@code L815} instead of setting a
     * further selector. A figure greater than this would mean a phase had been re-entered; a smaller
     * one would mean a phase had been skipped or folded into its predecessor.
     */
    public static final int EXPECTED_DISPATCH_ENTRY_COUNT = EXPECTED_DISPATCH_SEQUENCE.size() + 1;

    /**
     * Highest code point US-ASCII can represent.
     *
     * <p>Used to prove purity before a width is measured, for the reason set out on this class: an
     * encoder substitutes one replacement byte for a character it cannot represent, which keeps the
     * byte count right and makes the content wrong.
     */
    private static final int HIGHEST_ASCII_CODE_POINT = 0x7F;

    /**
     * Name of the timer measuring one delegated statement generation together with its postcondition
     * proofs.
     *
     * <p>Narrower than the batch step template's lifecycle timer and not a replacement for it: a
     * migration baseline is stated in whole legacy program lifecycles, and this measures the
     * delegated run alone.
     */
    private static final String METRIC_GENERATION = "carddemo.batch.statement.generation";

    /** Name of the counter accumulating plain statement records handed on. */
    private static final String METRIC_STATEMENT_RECORDS = "carddemo.batch.statement.records";

    /** Name of the counter accumulating HTML statement records handed on. */
    private static final String METRIC_HTML_RECORDS = "carddemo.batch.statement.htmlRecords";

    /** Name of the counter accumulating completed statements, one per cross-reference record consumed. */
    private static final String METRIC_STATEMENTS = "carddemo.batch.statement.statements";

    /** Tag key naming the legacy dataset a meter refers to. */
    private static final String TAG_RESOURCE = "resource";

    /** Tag key separating a generation that completed from one that failed. */
    private static final String TAG_OUTCOME = "outcome";

    /** Tag value for a run that was generated and passed every postcondition proof. */
    private static final String OUTCOME_COMPLETED = "COMPLETED";

    /** Tag value for a generation or proof that failed, which is always propagated. */
    private static final String OUTCOME_FAILED = "FAILED";

    /** Wording naming the plain statement stream in a postcondition diagnostic. */
    private static final String STREAM_STATEMENT = "statement";

    /** Wording naming the HTML statement stream in a postcondition diagnostic. */
    private static final String STREAM_HTML = "HTML statement";

    /** The statement generator: the module's single translation of the legacy statement program. */
    private final StatementGenerationService statementGenerationService;

    /**
     * Recovers the cleartext of the two regulated customer identifiers so that a customer record
     * image can be composed. Supplied by the composition root; see the discussion on this class.
     */
    private final UnaryOperator<String> regulatedFieldRevealer;

    /**
     * Seals those two identifiers back into the module's protected-value envelope when a composed
     * image is read into an entity.
     */
    private final UnaryOperator<String> regulatedFieldSealer;

    /** Registry the generation timer is recorded on, resolved once per stopped sample. */
    private final MeterRegistry meterRegistry;

    /** Counter of plain statement records handed on, registered once so no meter is built per item. */
    private final Counter statementRecordCounter;

    /** Counter of HTML statement records handed on, registered once for the same reason. */
    private final Counter htmlRecordCounter;

    /** Counter of completed statements, registered once for the same reason. */
    private final Counter statementCounter;

    /**
     * Constructor injection of every collaborator, which is what replaces the legacy program's static
     * file and subprogram linkage. No setter and no field injection exists, so a partially built
     * instance is not representable, and the instance that results holds no mutable state.
     *
     * <p>Deliberately <strong>not</strong> taken here: the four repositories, the file-handling
     * collaborator, the abort service and the two template classes. The first three belong to
     * {@link StatementGenerationService} and {@link StatementDataAccessService}, and the templates
     * expose static members because a fixed-width layout has no per-instance state to carry. Taking
     * any of them would give the module two owners for one decision. No cryptographic collaborator is
     * taken either, which is why the two regulated-field operations arrive as operations rather than
     * as a key or a service.
     *
     * @param statementGenerationService the statement generator this stage delegates a whole run to
     * @param regulatedFieldRevealer     recovers the cleartext of the two regulated customer
     *                                   identifiers; must not be {@code null} and must not return
     *                                   {@code null}
     * @param regulatedFieldSealer       seals those two identifiers back into the module's
     *                                   protected-value envelope; must not be {@code null} and must
     *                                   not return {@code null}
     * @param meterRegistry              the registry the generation timer and the three counters are
     *                                   recorded on
     * @throws NullPointerException if any collaborator is absent
     */
    public StatementProcessor(final StatementGenerationService statementGenerationService,
            final UnaryOperator<String> regulatedFieldRevealer,
            final UnaryOperator<String> regulatedFieldSealer, final MeterRegistry meterRegistry) {
        this.statementGenerationService = Objects.requireNonNull(statementGenerationService,
                LEGACY_JOB + " " + LEGACY_STATEMENT_STEP + " requires a statement generation service");
        this.regulatedFieldRevealer = Objects.requireNonNull(regulatedFieldRevealer, LEGACY_JOB + " "
                + LEGACY_STATEMENT_STEP + " requires a revealing operation for the two regulated"
                + " customer identifiers; the identity operation is not a substitute, because the"
                + " record image composer rejects a stored envelope");
        this.regulatedFieldSealer = Objects.requireNonNull(regulatedFieldSealer, LEGACY_JOB + " "
                + LEGACY_STATEMENT_STEP + " requires a sealing operation for the two regulated"
                + " customer identifiers; the identity operation is not a substitute, because the"
                + " customer entity rejects cleartext");
        this.meterRegistry = Objects.requireNonNull(meterRegistry,
                LEGACY_JOB + " " + LEGACY_STATEMENT_STEP + " requires a meter registry");
        this.statementRecordCounter = Counter.builder(METRIC_STATEMENT_RECORDS)
                .description("Plain statement records handed on from the statement assembly stage")
                .baseUnit("records")
                .tag(TAG_RESOURCE, OUTPUT_DD_STMTFILE)
                .register(meterRegistry);
        this.htmlRecordCounter = Counter.builder(METRIC_HTML_RECORDS)
                .description("HTML statement records handed on from the statement assembly stage")
                .baseUnit("records")
                .tag(TAG_RESOURCE, OUTPUT_DD_HTMLFILE)
                .register(meterRegistry);
        this.statementCounter = Counter.builder(METRIC_STATEMENTS)
                .description("Statements completed by the statement assembly stage, one per card"
                        + " cross-reference record consumed")
                .baseUnit("statements")
                .tag(TAG_RESOURCE, INPUT_DD_XREFFILE)
                .register(meterRegistry);
    }

    /**
     * Generates one whole run of statements from the frozen transaction-work snapshot supplied by the
     * job, and hands on both record streams with every record proved to its contracted width.
     *
     * <p>Five things happen, in this order, and the order is deliberate:
     *
     * <ol>
     *   <li>the source is required. One item is one whole frozen run, so a null here would mean the
     *       framework contract had been breached rather than that an input was empty - an empty
     *       snapshot is a valid source whose first read reports end of file;</li>
     *   <li>the whole run is delegated to {@link StatementGenerationService}, which drives the
     *       explicit phase enum through its {@code while} loop over the ordered {@code switch},
     *       re-enters the dispatcher after every state change, performs all thirteen data-access
     *       operations, tabulates the transactions into the bounded card table, and assembles both
     *       streams from the two template authorities in source order. <strong>No phase is driven
     *       from here</strong>;</li>
     *   <li>the observed dispatch trace is proved against
     *       {@link #EXPECTED_DISPATCH_SEQUENCE} and {@link #EXPECTED_DISPATCH_ENTRY_COUNT}. This is
     *       the re-entry proof: a service refactored into nested one-pass calls would still produce
     *       records and would fail here;</li>
     *   <li>the bounded grouping is proved: the run may not report more distinct cards than the card
     *       table holds, nor more tabulated transactions than the table's two dimensions multiplied
     *       together allow, nor a transaction count inconsistent with the cards it tabulated. The
     *       service already refuses to exceed the bound while tabulating; this proves the result it
     *       returned is consistent with having done so;</li>
     *   <li>every record of both streams is proved pure US-ASCII and then measured on its encoded byte
     *       array - {@value #STATEMENT_RECORD_LENGTH} bytes for the plain stream and
     *       {@value #HTML_RECORD_LENGTH} for the HTML stream. A breach of either is a defect in a
     *       layout authority rather than bad input, so it is diagnosed and raised rather than
     *       skipped.</li>
     * </ol>
     *
     * <p>An empty run is a legitimate outcome and is <strong>not</strong> converted into a failure: a
     * cross-reference file with no records still produces the two empty streams the legacy program
     * produces for it, and the dispatch trace is unchanged because the phases run before the mainline
     * discovers there is nothing to read.
     *
     * <p><strong>Never returns null.</strong> A null return instructs the batch framework to filter
     * the item, and since one item is the whole run, a filtered item would be a pair of statement
     * files the legacy job produced and this one silently did not. The method has exactly two
     * outcomes: the result, or a thrown exception.
     *
     * <p>The generation and all four proofs are timed together on one sample, tagged with the outcome,
     * so a failure is visible on the metrics endpoint as its own series. That timer supplements the
     * whole-step timing owned by {@link AbstractCobolStep} and the job configuration and does not
     * stand in for it.
     *
     * <p>Nothing is caught for translation. The delegate diagnoses a technical failure - naming the
     * failing operation, the resource and the raw two-character file status - and abends after doing
     * so, and that ordering must not be disturbed by an intervening handler here. The only exception
     * handling on this path re-tags the timer and rethrows the very same exception.
     *
     * @param  transactionSource the frozen, projected transaction-work snapshot materialised by the
     *                           job's preceding steps; never {@code null}
     * @return the run's two ordered record streams, its per-card transaction summaries, its observed
     *         dispatch trace and its three counts; never {@code null}, because a null return would
     *         filter the only item and silently produce no statements
     * @throws NullPointerException     if {@code transactionSource} is {@code null}, or if the delegate
     *                                  reports no result at all
     * @throws IllegalStateException    if the observed dispatch trace is not the expected one, if the
     *                                  run's counts are inconsistent with the legacy table's two
     *                                  dimensions, or if any record of either stream is not exactly its
     *                                  contracted number of encoded bytes or carries a character
     *                                  US-ASCII cannot represent
     */
    @Override
    public StatementRun process(final StatementTransactionSource transactionSource) {
        Objects.requireNonNull(transactionSource, () -> LEGACY_JOB + " "
                + LEGACY_STATEMENT_STEP + " received no frozen " + INPUT_DD_TRNXFILE
                + " transaction source");

        final Timer.Sample sample = Timer.start(this.meterRegistry);
        final StatementRun run;
        try {
            run = Objects.requireNonNull(
                    this.statementGenerationService.generate(transactionSource,
                            this.regulatedFieldRevealer, this.regulatedFieldSealer),
                    () -> LEGACY_PROGRAM + " reported no result for the " + INPUT_DD_TRNXFILE
                            + " work resource");
            // The four proofs sit inside the timed region and inside this guard on purpose: they are
            // part of what this stage promises, so a run that fails one of them must be reported as a
            // failed generation rather than as a completed one.
            requireExpectedDispatchSequence(run.dispatchedPhases());
            requireBoundedGrouping(run);
            requireRecordWidths(run.statementRecords(), STREAM_STATEMENT, STATEMENT_RECORD_LENGTH,
                    OUTPUT_DD_STMTFILE);
            requireRecordWidths(run.htmlRecords(), STREAM_HTML, HTML_RECORD_LENGTH,
                    OUTPUT_DD_HTMLFILE);
        } catch (RuntimeException failure) {
            stopSample(sample, OUTCOME_FAILED);
            throw failure;
        }
        stopSample(sample, OUTCOME_COMPLETED);

        this.statementRecordCounter.increment(run.statementRecords().size());
        this.htmlRecordCounter.increment(run.htmlRecords().size());
        this.statementCounter.increment(run.statementsWritten());
        // Counts, resource names and the observed phase count only. Every record of both streams
        // carries customer, account, card or transaction content, and none of it is logged.
        LOGGER.info("{} {} ({} via {}) produced {} statement(s) from {} card(s) and {} transaction(s)"
                        + " on {}: {} record(s) of {} bytes for {}, {} record(s) of {} bytes for {},"
                        + " {} transaction summary(ies), {} dispatcher entry(ies)",
                LEGACY_JOB, LEGACY_STATEMENT_STEP, LEGACY_PROGRAM, LEGACY_SUBPROGRAM,
                run.statementsWritten(), run.cardsTabulated(), run.transactionsTabulated(),
                INPUT_DD_TRNXFILE, run.statementRecords().size(), STATEMENT_RECORD_LENGTH,
                OUTPUT_DD_STMTFILE, run.htmlRecords().size(), HTML_RECORD_LENGTH,
                OUTPUT_DD_HTMLFILE, run.transactionSummaries().size(),
                run.dispatchedPhases().size());
        return run;
    }

    /**
     * Proves that the run entered the dispatcher the expected number of times and visited the expected
     * phases in the expected execution order.
     *
     * <p>Three properties are established, and together they are the re-entry proof:
     *
     * <ul>
     *   <li>the trace is exactly {@link #EXPECTED_DISPATCH_ENTRY_COUNT} entries long. More would mean
     *       a phase had been re-entered; fewer would mean one had been skipped or folded into its
     *       predecessor, which is what a nested-call refactor of the service would produce;</li>
     *   <li>its leading entries are the selectors {@link #EXPECTED_DISPATCH_SEQUENCE} publishes, in
     *       that order. That order is the run's <em>execution</em> order and is deliberately not the
     *       dispatcher's clause order, so this assertion is what keeps the documented difference
     *       between the two honest;</li>
     *   <li>its final entry is none of those five selectors, which is how the catch-all clause
     *       identifies itself: the source's clause matches any <em>other</em> value, so it is verified
     *       by exclusion rather than by naming a constant that belongs to the service's own private
     *       alphabet.</li>
     * </ul>
     *
     * <p>Nothing is repaired, reordered or tolerated. A trace that disagrees is a structural change to
     * the state machine, not bad input, so it ends the run.
     *
     * @param  dispatchedPhases the selector value observed at each dispatcher entry, in order
     * @throws IllegalStateException if the trace is not the expected one
     */
    private static void requireExpectedDispatchSequence(final List<String> dispatchedPhases) {
        if (dispatchedPhases.size() != EXPECTED_DISPATCH_ENTRY_COUNT) {
            LOGGER.error("{} {}: the dispatcher was entered {} time(s), expected {}", LEGACY_JOB,
                    LEGACY_STATEMENT_STEP, dispatchedPhases.size(), EXPECTED_DISPATCH_ENTRY_COUNT);
            throw new IllegalStateException(dispatchSequenceFailure(dispatchedPhases,
                    "the dispatcher was entered " + dispatchedPhases.size() + " time(s) rather than "
                            + EXPECTED_DISPATCH_ENTRY_COUNT));
        }

        for (int position = 0; position < EXPECTED_DISPATCH_SEQUENCE.size(); position++) {
            final String expected = EXPECTED_DISPATCH_SEQUENCE.get(position);
            final String observed = dispatchedPhases.get(position);
            if (!expected.equals(observed)) {
                LOGGER.error("{} {}: dispatcher entry {} observed phase {}, expected {}", LEGACY_JOB,
                        LEGACY_STATEMENT_STEP, position, observed, expected);
                throw new IllegalStateException(dispatchSequenceFailure(dispatchedPhases,
                        "dispatcher entry " + position + " observed phase '" + observed
                                + "' rather than '" + expected + "'"));
            }
        }

        final String terminal = dispatchedPhases.get(EXPECTED_DISPATCH_ENTRY_COUNT - 1);
        if (EXPECTED_DISPATCH_SEQUENCE.contains(terminal)) {
            LOGGER.error("{} {}: the run ended on phase {}, which is a phase selector rather than the"
                    + " catch-all clause", LEGACY_JOB, LEGACY_STATEMENT_STEP, terminal);
            throw new IllegalStateException(dispatchSequenceFailure(dispatchedPhases,
                    "the final dispatcher entry was the phase selector '" + terminal
                            + "' rather than the catch-all clause that ends the run"));
        }
    }

    /**
     * Renders a dispatch-trace failure, naming the observed trace and the documented clause order
     * beside it.
     *
     * <p>A trace is a sequence of selector values, so it carries no customer, account, card or
     * transaction content and is safe to name in full. Reporting the clause order alongside it is what
     * makes the message actionable: the two are genuinely different sequences, and a reader comparing
     * them can see at once whether re-entry was lost or a phase was reordered.
     *
     * @param  dispatchedPhases the observed trace
     * @param  breach           what specifically was wrong, in prose
     * @return the message to raise
     */
    private static String dispatchSequenceFailure(final List<String> dispatchedPhases,
            final String breach) {
        return LEGACY_JOB + " " + LEGACY_STATEMENT_STEP + " (" + LEGACY_PROGRAM + "): " + breach
                + ". The dispatcher must be re-entered after every state change, so a completed run"
                + " visits " + EXPECTED_DISPATCH_SEQUENCE + " and then its catch-all clause, in that"
                + " execution order - which is not the clause order the selection declares."
                + " Observed: " + dispatchedPhases;
    }

    /**
     * Proves that the run's counts are consistent with the legacy table bound having been respected.
     *
     * <p>Three properties are established against the two dimensions of the card table at
     * {@code [app/cbl/CBSTM03A.CBL:L226]} and {@code L228}:
     *
     * <ul>
     *   <li>the distinct cards tabulated lie within the table's own occurrence count. This is the
     *       first dimension;</li>
     *   <li>the transactions tabulated lie within the two dimensions multiplied together. This is the
     *       whole table;</li>
     *   <li>the transactions tabulated lie between the cards tabulated and the cards tabulated
     *       multiplied by the nested occurrence count. Both halves follow from how the read phase
     *       fills the table at {@code [app/cbl/CBSTM03A.CBL:L818-L830]}: a card enters the table only
     *       when a transaction for it is read, and the nested counter is set to one and a transaction
     *       stored in the same breath, so every tabulated card carries at least one transaction and no
     *       card can carry more than its nested table holds. This is the tightest bound the table's
     *       structure justifies, and it catches an off-by-one in the card break that the two looser
     *       bounds above would let through.</li>
     * </ul>
     *
     * <p>Negative counts are rejected throughout, because a count is a tally and a negative tally
     * would mean a counter had wrapped.
     *
     * <p><strong>The statements written are checked only for non-negativity, and deliberately not
     * against the cards tabulated.</strong> The mainline at {@code [app/cbl/CBSTM03A.CBL:L317-L329]}
     * creates a statement for <em>every</em> cross-reference record it consumes, before it walks the
     * tabulated transactions and whether or not that card appears in the table at all, so a card with
     * no transactions in the work file still produces a statement with an empty transaction section.
     * The statement count is therefore bounded by the cross-reference record count, which this stage
     * does not know and must not guess. Bounding it by the cards tabulated would reject the entirely
     * normal case of a work file narrower than the cross-reference file.
     *
     * <p>The bound is <strong>enforced</strong> while tabulating, by the service, which reports an
     * overrun through the same diagnose-then-abend path as a failed file operation. This method does
     * not duplicate that enforcement and does not relax it; it establishes that the result handed back
     * is consistent with it, so a future change that grew the table silently would fail here rather
     * than quietly accept input the mainframe cannot hold.
     *
     * @param  run the completed run
     * @throws IllegalStateException if any count is negative or inconsistent with the table's two
     *                               dimensions
     */
    private static void requireBoundedGrouping(final StatementRun run) {
        final int cards = run.cardsTabulated();
        final int transactions = run.transactionsTabulated();
        final int tableCapacity = StatementGenerationService.MAX_CARD_ENTRIES
                * StatementGenerationService.MAX_TRANSACTIONS_PER_CARD;

        requireCountWithin(cards, 0, StatementGenerationService.MAX_CARD_ENTRIES,
                "distinct card(s) tabulated", "the card table holds "
                        + StatementGenerationService.MAX_CARD_ENTRIES + " entry(ies)");
        requireCountWithin(transactions, 0, tableCapacity, "transaction(s) tabulated",
                "the card table holds " + StatementGenerationService.MAX_CARD_ENTRIES
                        + " entry(ies) of " + StatementGenerationService.MAX_TRANSACTIONS_PER_CARD
                        + " transaction(s)");
        requireCountWithin(transactions, cards,
                cards * StatementGenerationService.MAX_TRANSACTIONS_PER_CARD,
                "transaction(s) tabulated across " + cards + " card(s)",
                "a card enters the table only when a transaction for it is read, so each of the "
                        + cards + " tabulated card(s) carries at least one transaction and at most "
                        + StatementGenerationService.MAX_TRANSACTIONS_PER_CARD);
        requireCountWithin(run.statementsWritten(), 0, Integer.MAX_VALUE, "statement(s) written",
                "one statement is produced per card cross-reference record consumed, whether or not"
                        + " that card appears in the work file, so only non-negativity can be"
                        + " established here");
    }

    /**
     * Requires one of a run's counts to lie between an inclusive floor and an inclusive ceiling.
     *
     * @param  observed what the run reported
     * @param  floor    the lowest value the legacy layout can justify
     * @param  ceiling  the highest value the legacy layout can justify
     * @param  subject  what the count counts, worded for a diagnostic
     * @param  because  why those limits are the limits, worded for a diagnostic
     * @throws IllegalStateException if the count is negative or outside the two limits
     */
    private static void requireCountWithin(final int observed, final int floor, final int ceiling,
            final String subject, final String because) {
        if (observed < 0 || observed < floor || observed > ceiling) {
            LOGGER.error("{} {}: the run reported {} {}, which is outside {}..{}", LEGACY_JOB,
                    LEGACY_STATEMENT_STEP, observed, subject, floor, ceiling);
            throw new IllegalStateException(LEGACY_JOB + " " + LEGACY_STATEMENT_STEP + " ("
                    + LEGACY_PROGRAM + "): the run reported " + observed + " " + subject
                    + ", but " + because + ", so the value must lie between " + floor + " and "
                    + ceiling + " inclusive. The bound is legacy table capacity, not a tuning value,"
                    + " and it is never relaxed to accept input the source cannot hold");
        }
    }

    /**
     * Proves the contracted width of every record of one of the run's two streams.
     *
     * <p>Records are checked in emission order and the first breach stops the run, so the diagnostic
     * names the earliest record that is wrong rather than the last. The list itself cannot hold a null
     * element - the result record seals both streams with immutable copies, which reject one - so the
     * per-record null check exists only to keep this helper safe if it is ever called with a list
     * assembled elsewhere.
     *
     * @param  records      the stream's records in emission order
     * @param  stream       which stream this is, worded for a diagnostic
     * @param  recordLength the contracted width in encoded US-ASCII bytes
     * @param  resource     the data-definition name the stream is written under
     * @throws IllegalStateException if any record breaches purity or width
     */
    private static void requireRecordWidths(final List<String> records, final String stream,
            final int recordLength, final String resource) {
        for (int index = 0; index < records.size(); index++) {
            requireRecordWidth(records.get(index), index, stream, recordLength, resource);
        }
    }

    /**
     * Proves that one record is representable in US-ASCII and is exactly its contracted number of
     * encoded bytes.
     *
     * <p>Purity is proved first and the width is then taken from the <strong>encoded byte
     * array</strong>. Neither step is redundant and their order is not interchangeable, for the reason
     * set out on this class. The character scan reports the <em>position</em> of an offending character
     * and never the character itself, because a statement record carries a customer's name and address,
     * an account identifier and balance, and per-transaction descriptions and amounts.
     *
     * @param  record       the candidate record
     * @param  index        the record's position in its stream, named in any diagnostic
     * @param  stream       which stream this is, worded for a diagnostic
     * @param  recordLength the contracted width in encoded US-ASCII bytes
     * @param  resource     the data-definition name the stream is written under
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if the record is impure or mis-sized
     */
    private static void requireRecordWidth(final String record, final int index, final String stream,
            final int recordLength, final String resource) {
        Objects.requireNonNull(record, () -> LEGACY_JOB + " " + LEGACY_STATEMENT_STEP + ": " + stream
                + " record " + index + " is absent, but a fixed-length dataset has no representation"
                + " for an absent record");

        final char[] characters = record.toCharArray();
        for (int position = 0; position < characters.length; position++) {
            if (characters[position] > HIGHEST_ASCII_CODE_POINT) {
                LOGGER.error("{} {}: {} record {} carries a character outside US-ASCII at position {}",
                        LEGACY_JOB, LEGACY_STATEMENT_STEP, stream, index, position);
                throw new IllegalStateException(LEGACY_JOB + " " + LEGACY_STATEMENT_STEP + ": "
                        + stream + " record " + index + " carries a character outside US-ASCII at"
                        + " position " + position + ", which the fixed-width " + resource
                        + " record cannot represent");
            }
        }

        final int measuredWidth = record.getBytes(StandardCharsets.US_ASCII).length;
        if (measuredWidth != recordLength) {
            LOGGER.error("{} {}: {} record {} measures {} bytes, expected {}", LEGACY_JOB,
                    LEGACY_STATEMENT_STEP, stream, index, measuredWidth, recordLength);
            throw new IllegalStateException(LEGACY_JOB + " " + LEGACY_STATEMENT_STEP + ": " + stream
                    + " record " + index + " measures " + measuredWidth
                    + " encoded byte(s), but every record of the " + resource + " dataset is fixed at "
                    + recordLength + ". No record carries a line terminator: record framing on a"
                    + " destination belongs to the writer");
        }
    }

    /**
     * Stops the generation sample against the timer for the given outcome.
     *
     * <p>Exactly one call is made per invocation, on whichever of the two paths the invocation takes,
     * because a sample may only be stopped once. The timer is built at the point of use so that the
     * outcome tag is part of its identity, which is what makes a failed generation a distinct series
     * rather than a hidden contributor to the successful one. No bucket, percentile, service level or
     * expected value is configured: this stage measures and never asserts a performance figure.
     *
     * @param sample  the sample started before the generation
     * @param outcome {@link #OUTCOME_COMPLETED} or {@link #OUTCOME_FAILED}
     */
    private void stopSample(final Timer.Sample sample, final String outcome) {
        sample.stop(Timer.builder(METRIC_GENERATION)
                .description("Elapsed time of one statement generation and its fixed-width record,"
                        + " dispatch-trace and table-bound proofs")
                .tag(TAG_RESOURCE, INPUT_DD_TRNXFILE)
                .tag(TAG_OUTCOME, outcome)
                .register(this.meterRegistry));
    }
}
