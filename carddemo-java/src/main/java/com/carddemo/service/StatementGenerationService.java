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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.util.AccountRecordMapper;
import com.carddemo.util.CardXrefRecordMapper;
import com.carddemo.util.CustomerRecordMapper;
import com.carddemo.util.StatementHtmlTemplates;
import com.carddemo.util.StatementTextTemplates;
import com.carddemo.util.StatementWorkRecordMapper;
import com.carddemo.util.ZonedDecimalCodec;

/**
 * The account statement generator, translated paragraph by paragraph from
 * {@code [app/cbl/CBSTM03A.CBL]} - 924 lines, 25 paragraphs, two output files at two different record
 * widths.
 *
 * <p>The authority member is one of only three CRLF-encoded sources in the estate - with {@code
 * [app/cbl/CBSTM03B.CBL]} and {@code [app/cbl/COACTUPC.cbl]} - and every line number cited here
 * comes from a carriage-return-tolerant read. No COBOL text is transcribed: member names, paragraph
 * names, line numbers, DD names, field names, record widths and raw status codes are cited, and
 * nothing else.
 *
 * <h2>This program is not a loop. It is a hand-rolled state machine</h2>
 *
 * <p>{@code WS-FL-DD} is an eight-character field initialised at {@code [app/cbl/CBSTM03A.CBL:L67]} to
 * the transaction-file phase. The dispatcher at {@code [app/cbl/CBSTM03A.CBL:L296-L314]} branches on
 * that value through six clauses; each phase then <em>sets a new value and jumps backward to the
 * dispatcher</em>, which re-branches. Six of the estate's nine backward jumps belong to this member:
 * {@code L761}, {@code L780}, {@code L798} and {@code L852} return to the dispatcher at
 * {@code L296}; {@code L815} returns to the mainline at {@code L316}; and {@code L840} returns to the
 * transaction-read paragraph at {@code L818}, forming that paragraph's own inner loop.
 *
 * <p>The translation is therefore an explicit state enum driven by a {@code while} loop over a
 * {@code switch}, and <strong>dispatcher re-entry after every state change is mandatory</strong>.
 * Nested one-pass method calls cannot reproduce re-entry into a dispatcher after a state change, which
 * is why the obvious tidy-up - folding each phase into the caller that triggered it - is precisely the
 * refactor that breaks the program. This is recorded in the decision log as the most consequential
 * structural decision in the batch tier.
 *
 * <p>The state variable lives in
 * {@link #generate(StatementTransactionSource, UnaryOperator, UnaryOperator)} as a local, never as a
 * field, so two runs sharing this singleton cannot observe one another's phase. Every other item of the
 * legacy {@code WORKING-STORAGE} lives in a per-invocation context object for the same reason.
 *
 * <h2>Thirteen call sites, one collaborator</h2>
 *
 * <p>All 13 call sites of CBSTM03B in the estate are in this member - at {@code L351},
 * {@code L377}, {@code L401}, {@code L734}, {@code L746}, {@code L769}, {@code L787}, {@code L805},
 * {@code L835}, {@code L860}, {@code L877}, {@code L893} and {@code L909} - and each becomes one call
 * on the injected {@code StatementDataAccessService} with a populated parameter object. The payload
 * field is blanked before every call: the legacy blanks it explicitly before its five reads
 * ({@code L350}, {@code L376}, {@code L400}, {@code L745}, {@code L834}), and blanking it before the
 * opens and closes as well is behaviour-preserving because no open or close consults it, while removing
 * any possibility of a stale image leaking into the next read.
 *
 * <p>Two status-test shapes appear and both are reproduced literally. The nine guard-form sites - the
 * four opens at {@code L736}, {@code L771}, {@code L789} and {@code L807}, the first transaction read
 * at {@code L748}, and the four closes at {@code L862}, {@code L879}, {@code L895} and {@code L911} -
 * accept <strong>either</strong> {@code 00} or {@code 04}. The four selection-form reads accept only
 * {@code 00}: the cross-reference read at {@code L353} treats {@code 10} as end of file, the two keyed
 * reads at {@code L379} and {@code L403} abend on anything but {@code 00}, and the transaction read at
 * {@code L837} loops on {@code 00}, leaves the phase on {@code 10} and abends otherwise. End of file is
 * never collapsed into error, because that inner loop depends entirely on the distinction. The two
 * declared-but-dead write and rewrite operations are never invoked.
 *
 * <p>This member contains no {@code APPL-RESULT} field - zero occurrences - so the coarse three-state
 * outcome that ten other batch members derive from a raw status has no counterpart here. The raw
 * two-character status is what this program tests, and {@code FileStatus} supplies the vocabulary so no
 * status literal appears in this file.
 *
 * <h2>Dual output at two widths, and two banners that are not interchangeable</h2>
 *
 * <p>The plain statement record is 80 characters ({@code [app/cbl/CBSTM03A.CBL:L45]}) and the HTML
 * statement record is 100 ({@code [app/cbl/CBSTM03A.CBL:L47]}); both files are written in the same run.
 * Both banners are 80 bytes but their padding splits differ - the start banner is 31/18/31 at
 * {@code L88} and the end banner is 32/16/32 at {@code L145} - so they are obtained separately from
 * {@code StatementTextTemplates} and are deliberately not unified.
 *
 * <p>Every literal comes from {@code StatementTextTemplates} (17 groups: 8 fixed constants and 9
 * builders, including the two thirteen-character trailing-minus masks) and
 * {@code StatementHtmlTemplates} (34 constants declared at {@code [app/cbl/CBSTM03A.CBL:L150-L211]},
 * two composed builders and three fit-to-width composers), emitted in source order. There is no
 * templating engine, no markup builder, no pretty-printer, no whitespace normalisation and no escaping
 * pass here: the legacy emits fixed-width literal constants and byte parity is achievable only by doing
 * the same. The pair of adjacent spaces inside the table tag at {@code L157} and the malformed paragraph
 * tag both survive exactly as the legacy emits them.
 *
 * <p>Three source oddities in the write order are preserved because they are present in the golden
 * output: the rule line is written twice in succession around the basic-details heading
 * ({@code L492} and {@code L494}), the rule line before the transaction table is written three times
 * across two paragraphs ({@code L435}, {@code L500} and {@code L502}), and the name-and-address writer
 * at {@code L558} concatenates with a delimiter of <em>two</em> adjacent spaces rather than one.
 *
 * <p>The 76-byte group at {@code [app/cbl/CBSTM03A.CBL:L217-L220]} carries an unclosed paragraph tag
 * and is <strong>populated but never written</strong>. Its population is the move of the assembled name
 * into its fifty-byte member, which the composed name-line builder performs; the group itself is never
 * assembled, never emitted and its missing close tag is never repaired.
 *
 * <h2>Capacity, decimals and strings</h2>
 *
 * <p>The card table occurs 51 times at {@code [app/cbl/CBSTM03A.CBL:L226]} and its nested transaction
 * table occurs 10 times at {@code L228}, so the hard bound is
 * {@value #MAX_CARD_ENTRIES} cards by {@value #MAX_TRANSACTIONS_PER_CARD} transactions per card. Each
 * stored entry is a sixteen-character card number, a sixteen-character transaction key and a
 * 318-character remainder; those three widths belong to the record mapper that owns the layout, and no
 * offset, slice or picture width appears in this file. The bound is legacy capacity, not a tuning
 * value, and this class declares no chunk size, thread count, timeout or performance figure of any
 * kind. The two similarly named counters of {@code [app/cbl/CBSTM03A.CBL:L231-L233]} are both real and
 * are kept distinct here as a named group holding a named per-card table.
 *
 * <p>The estate contains no rounding clause, so every store into a two-decimal field truncates toward
 * zero. All scaling goes through {@code ZonedDecimalCodec}, whose monetary mode is truncating; this
 * class never rescales a value itself, never names a half-even or half-up mode, never uses a binary
 * floating-point type and never renders an amount by its plain decimal text - the two thirteen-byte
 * trailing-minus masks belong to {@code StatementTextTemplates}. Character transfers reproduce the
 * source's {@code STRING ... DELIMITED BY} pointer semantics: the assembled name and the third address
 * line stop each operand at its first single space, which is why a two-word city contributes only its
 * first word exactly as it does on the mainframe.
 *
 * <h2>Where the load-bearing sites are</h2>
 *
 * <p>Every site a reader has to be able to find, gathered in one place so that the mapping back to the
 * authority member can be checked without reading this class end to end. The 25 paragraphs are the
 * dispatcher at {@code L296}, the mainline at {@code L316}, the program exit at {@code L341}, the
 * cross-reference get-next at {@code L345}, the customer get at {@code L368}, the account get at
 * {@code L392}, the transaction get at {@code L416}, the statement creator at {@code L458}, the HTML
 * header writer at {@code L506} with its exit at {@code L554}, the HTML name-and-address writer at
 * {@code L558} with its exit at {@code L671}, the transaction writer at {@code L675}, the file-open
 * indirection at {@code L726}, the four open paragraphs at {@code L730}, {@code L765}, {@code L783}
 * and {@code L801}, the transaction read at {@code L818} with its phase exit at {@code L849}, the four
 * close paragraphs at {@code L856}, {@code L873}, {@code L889} and {@code L905}, and the abend at
 * {@code L921}.
 *
 * <p>The other sites that carry contract rather than structure: the unnamed entry sequence at
 * {@code L293-L294} that opens both output files and initialises both tables; the record widths at
 * {@code L45} and {@code L47}; the initial state at {@code L67}; the plain line groups at
 * {@code L85-L146} with the two banners at {@code L88} and {@code L145}; the HTML constants at
 * {@code L150-L211}; the populated-but-never-written group at {@code L217-L220}; the card table and
 * count group at {@code L225-L233}; the dispatcher's six clauses at {@code L296-L314}; the repeated
 * writes at {@code L435}, {@code L492}, {@code L494}, {@code L500} and {@code L502}; the file-open
 * indirection's alterable branch at {@code L726-L728}; the four backward jumps to the dispatcher at
 * {@code L761}, {@code L780}, {@code L798} and {@code L852}; the jump to the mainline at {@code L815};
 * the inner read loop's selection at {@code L837-L845} with its own backward jump at {@code L840}; and
 * the read phase's exit sequence at {@code L849-L853}.
 *
 * <h2>What is deliberately not migrated</h2>
 *
 * <p>The legacy carries MVS control-block addressing at
 * {@code [app/cbl/CBSTM03A.CBL:L235-L260, L266-L291]} - a pointer, a signed binary index, a
 * redefinition over it, and linkage structures describing the prefixed save area, the task control
 * block and the task input/output table with its entries. <strong>None of it is migrated.</strong> It
 * exists solely to discover DD names from operating-system control blocks and has no equivalent once
 * the data layer is relational, so no substitute is attempted and neither reflection nor any native
 * mechanism is used to emulate it. Recorded as a decision-log entry.
 *
 * <p>The job decides when to perform the 328-of-350-byte statement reprojection and supplies that frozen
 * projected snapshot to this service. The utility mapper owns the projection and parse mechanics,
 * including the two-byte processing-timestamp truncation, so this class consumes the projected payload
 * without re-deriving any offset. Also not this class's work: {@code FileStatusException}, which models
 * the error arm alone and rejects the success and
 * end-of-file codes, so this member's abend path goes straight to the abend service instead; and the
 * string and upper-folding primitives of {@code CobolStringUtils}, because this member performs no
 * inspection, no case fold and no alphabetic test.
 *
 * <p>There is no persistence and no commit point in this member, so nothing here is transactional and
 * no lock mode is named. Reading is delegated entirely to the injected data-access collaborator and
 * emitting to a destination belongs to the batch step: this class's contract is the ordered record
 * content.
 */
@Service
public final class StatementGenerationService {

    private static final Logger LOG = LoggerFactory.getLogger(StatementGenerationService.class);

    /**
     * Legacy member name, named as the culprit on every abend so a diagnostic points back at the
     * source paragraph rather than at the Java frame.
     */
    public static final String PROGRAM_NAME = "CBSTM03A";

    /**
     * Occurrence count of the card table at {@code [app/cbl/CBSTM03A.CBL:L226]}.
     *
     * <p>Legacy capacity, not a tuning value: the mainframe table is exactly this long and a run
     * presenting more distinct card numbers would overrun its subscript there. Published so a caller
     * can size its input rather than discover the bound by failing.
     */
    public static final int MAX_CARD_ENTRIES = 51;

    /**
     * Occurrence count of the nested transaction table at {@code [app/cbl/CBSTM03A.CBL:L228]}.
     *
     * <p>Legacy capacity, not a tuning value, for the same reason as {@link #MAX_CARD_ENTRIES}.
     */
    public static final int MAX_TRANSACTIONS_PER_CARD = 10;

    /**
     * The plain statement output file, {@code SELECT STMT-FILE ASSIGN TO STMTFILE} at
     * {@code [app/cbl/CBSTM03A.CBL:L39]}. Diagnostic use only; this class emits records rather than
     * writing to a destination.
     */
    private static final String DD_STMTFILE = "STMTFILE";

    /**
     * The HTML statement output file, {@code SELECT HTML-FILE ASSIGN TO HTMLFILE} at
     * {@code [app/cbl/CBSTM03A.CBL:L40]}. Diagnostic use only, as for {@link #DD_STMTFILE}.
     */
    private static final String DD_HTMLFILE = "HTMLFILE";

    /**
     * The two characters the source's zeroing of WS-M03B-RC leaves in the two-byte return-code field -
     * observed at {@code [app/cbl/CBSTM03A.CBL:L349, L375, L399, L733, L768, L786, L804, L859, L876,
     * L892, L908]}.
     *
     * <p>Sourced from the status vocabulary rather than written as a literal, because moving the figurative
     * zero into a {@code PIC X(02)} field yields the same two characters the success status carries. The
     * field is deliberately <em>not</em> reset before the two transaction-file reads at {@code L746} and
     * {@code L835}, so the value carried into those two calls is live; that asymmetry is reproduced.
     */
    private static final String RETURN_CODE_ZEROED = FileStatus.SUCCESS.getCode();

    /** Raw status meaning the operation succeeded, compared at every status test in the member. */
    private static final String STATUS_SUCCESS = FileStatus.SUCCESS.getCode();

    /**
     * Raw status meaning the record length differed from the declared one. Accepted alongside
     * {@link #STATUS_SUCCESS} at the nine guard-form sites and at no other.
     */
    private static final String STATUS_RECORD_LENGTH_MISMATCH = FileStatus.RECORD_LENGTH_MISMATCH.getCode();

    /**
     * Integer digit positions of every amount receiving field this program declares.
     *
     * <p>{@code WS-TOTAL-AMT} and {@code WS-TRN-AMT} are {@code PIC S9(9)V99} at
     * {@code app/cbl/CBSTM03A.CBL} lines 65 and 68, and the three edited statement fields
     * {@code ST-CURR-BAL}, {@code ST-TRANAMT} and {@code ST-TOTAL-TRAMT} carry nine integer positions at
     * lines 113, 137 and 142. They are all the same width, and it is <em>narrower than the account
     * balance</em>, which is a ten-integer-digit field: the move at line 484 therefore drops the account
     * balance's high-order digit, and the store is what reproduces that.
     */
    private static final int AMOUNT_FIELD_INTEGER_DIGITS =
            StatementTextTemplates.AMOUNT_MASK_INTEGER_DIGITS;

    /** Legacy name of the transaction accumulator, line 65. */
    private static final String FIELD_WS_TOTAL_AMT = "WS-TOTAL-AMT";

    /** Legacy name of the accumulator's move target, line 68. */
    private static final String FIELD_WS_TRN_AMT = "WS-TRN-AMT";

    /** Legacy name of the edited balance field, line 113. */
    private static final String FIELD_ST_CURR_BAL = "ST-CURR-BAL";

    /** Legacy name of the edited transaction-amount field, line 137. */
    private static final String FIELD_ST_TRANAMT = "ST-TRANAMT";

    /** Legacy name of the edited total field, line 142. */
    private static final String FIELD_ST_TOTAL_TRAMT = "ST-TOTAL-TRAMT";

    /** {@code END-OF-FILE} at {@code [app/cbl/CBSTM03A.CBL:L70]} in its initial, not-yet-exhausted state. */
    private static final String NOT_AT_END_OF_FILE = "N";

    /** {@code END-OF-FILE} once the cross-reference read reports exhaustion at {@code [app/cbl/CBSTM03A.CBL:L357]}. */
    private static final String AT_END_OF_FILE = "Y";

    /**
     * Zero-based index at which a record begins inside the payload field.
     *
     * <p>The counterpart of the payload field carried into a record area, which starts at the field's first
     * byte. Not a field offset: every field offset and width inside a record belongs to the record
     * mapper that owns the layout, and none appears in this file.
     */
    private static final int PAYLOAD_RECORD_START = 0;

    /** The key field carries nothing on an open, a close or a sequential read; the request normalises it to width. */
    private static final String NO_KEY = "";

    /** The runtime key length is meaningful only on a keyed read; the other operations leave it at zero. */
    private static final int NO_KEY_LENGTH = 0;

    /**
     * The blanked payload field - WS-M03B-FLDT cleared to spaces. The request normalises it to the
     * declared payload width, so an empty value here becomes a field of spaces exactly as the legacy
     * move does.
     */
    private static final String BLANK_PAYLOAD = "";

    /** Ordinal position an open establishes on a sequentially accessed file: its first record. */
    private static final int FIRST_RECORD_POSITION = 0;

    /**
     * The delimiter of the six {@code STRING ... DELIMITED BY ' '} operands at
     * {@code [app/cbl/CBSTM03A.CBL:L462-L468]} and {@code [L472-L480]}, and the one-character literal
     * those two statements interleave between operands.
     *
     * <p>A <em>single</em> space. The HTML composers use a delimiter of two adjacent spaces instead,
     * and that one belongs to {@code StatementHtmlTemplates}; the two are not interchangeable.
     */
    private static final String SINGLE_SPACE = " ";

    /**
     * Label of the account-identifier line of the HTML basic-details block, from the literal at
     * {@code [app/cbl/CBSTM03A.CBL:L614]} with its leading paragraph tag removed.
     *
     * <p>Twenty-one characters, which is the twenty-character label of the plain statement line plus one
     * trailing space. The two are genuinely different literals and the trailing space is visible in the
     * golden HTML fixture.
     *
     * <p>Declared here rather than obtained from {@code StatementHtmlTemplates} because that class
     * parameterises the label of a basic-details line rather than publishing it, and because
     * {@code StatementTextTemplates} holds its own narrower labels privately. This is the only literal
     * text in this file, it is forced by the composer's declared signature, and it is recorded as a
     * decision-log entry.
     */
    private static final String HTML_LABEL_ACCOUNT_ID = "Account ID         : ";

    /**
     * Label of the current-balance line of the HTML basic-details block, from the literal at
     * {@code [app/cbl/CBSTM03A.CBL:L621]}. Twenty-one characters, as for
     * {@link #HTML_LABEL_ACCOUNT_ID}.
     */
    private static final String HTML_LABEL_CURRENT_BALANCE = "Current Balance    : ";

    /**
     * Label of the credit-score line of the HTML basic-details block, from the literal at
     * {@code [app/cbl/CBSTM03A.CBL:L628]}. Twenty-one characters, as for
     * {@link #HTML_LABEL_ACCOUNT_ID}.
     */
    private static final String HTML_LABEL_FICO_SCORE = "FICO Score         : ";

    /** Diagnostic wording of the failing operation on an open, matching the legacy display literals. */
    private static final String OPERATION_OPEN = "OPEN";

    /** Diagnostic wording of the failing operation on a read. */
    private static final String OPERATION_READ = "READ";

    /** Diagnostic wording of the failing operation on a close. */
    private static final String OPERATION_CLOSE = "CLOSE";

    /** The pad character of an alphanumeric field, which is what a COBOL move supplies. */
    private static final char SPACE = ' ';

    private final StatementDataAccessService statementDataAccessService;

    private final AbendService abendService;

    /**
     * Binds the two collaborators that replace this member's static linkage: the file-handling
     * subprogram reached by its 13 call sites, and the Language Environment abort routine reached by
     * The Language Environment abend call at {@code [app/cbl/CBSTM03A.CBL:L923]}.
     *
     * @param statementDataAccessService stands in for the file-handling subprogram; must not be
     *                                   {@code null}
     * @param abendService               stands in for the abort routine; must not be {@code null}
     * @throws NullPointerException if either collaborator is {@code null}
     */
    public StatementGenerationService(final StatementDataAccessService statementDataAccessService,
                                      final AbendService abendService) {
        this.statementDataAccessService = Objects.requireNonNull(statementDataAccessService,
                "statementDataAccessService must not be null");
        this.abendService = Objects.requireNonNull(abendService, "abendService must not be null");
    }

    // ================================================================================================
    // Entry point - the unnamed code between PROCEDURE DIVISION [L262] and the dispatcher [L296]
    // ================================================================================================

    /**
     * Runs one statement generation, emitting every record of both output files to the given sink as it
     * is produced, and returns the run's tallies.
     *
     * <p>Reproduces the whole of {@code [app/cbl/CBSTM03A.CBL]} from its procedure entry to its
     * {@code GOBACK}: the unnamed entry sequence, the dispatcher and every phase the dispatcher reaches.
     * Nothing is written to a file, an object store or a queue - where the emitted records go is the
     * sink's business and the batch step's work, and this class exists to get the bytes right.
     *
     * <p><strong>&#9733; Records leave through the sink, not through the return value.</strong> A legacy
     * {@code WRITE} hands one record to an open dataset and the program's working storage never holds the
     * file; the number of records one run emits is bounded only by the number of cross-reference records
     * it consumes, which is unbounded. This method therefore retains no record at all: each is handed to
     * {@link StatementOutputSink} at the moment the source writes it, and what comes back is seven
     * tallies. The working set is one record, exactly as the legacy program's was.
     *
     * <p><strong>Why two functions rather than none.</strong> The legacy customer file held its two
     * regulated identifiers in the clear, so its keyed read needed no help. This module protects them at
     * rest, so composing the 500-byte customer image needs the cleartext recovered, and reading that
     * image back into an entity needs it sealed again. The two operations are the halves of one policy,
     * that policy belongs to the caller and not to this service, and this service holds no key and no
     * encryption collaborator of its own. Passing the identity function for either is not a shortcut: the
     * image composer rejects a stored envelope for overflowing a nine-byte field, and the entity rejects
     * cleartext outright.
     *
     * <p>The run's sequential walk of the cross-reference cluster is obtained from the file-handling
     * collaborator rather than passed in, because that cluster has no snapshot: the legacy member reads it
     * directly, one record at a time, in key order. The transaction work resource does have one - the
     * job's sort and projection steps produce it - which is why exactly one of the two sequential inputs
     * is a parameter here.
     *
     * @param transactionSource      the frozen projected transaction-work snapshot materialised by the
     *                               preceding statement-job steps; must not be {@code null}
     * @param regulatedFieldRevealer recovers the cleartext of the two regulated customer identifiers, so
     *                               that the collaborator can compose a customer record image; must not
     *                               be {@code null} and must not return {@code null}
     * @param regulatedFieldSealer   seals those two identifiers back into the module's protected-value
     *                               envelope when the composed image is read into an entity; must not be
     *                               {@code null} and must not return {@code null}
     * @param outputSink             receives every 80-byte statement record, every 100-byte markup
     *                               record, every per-line transaction summary and every dispatcher
     *                               entry, in emission order, as the run produces them; must not be
     *                               {@code null}
     * @return the run's tallies: how many records of each stream it emitted, how many summaries and
     *         dispatcher entries it emitted, and the three counts the legacy program accumulates
     * @throws NullPointerException     if the source, either operation or the sink is {@code null}
     * @throws AbendException           if any file operation fails, reproducing
     *                                  {@code 9999-ABEND-PROGRAM} at {@code [app/cbl/CBSTM03A.CBL:L921]}
     * @throws IllegalArgumentException if a record image the collaborator returns does not satisfy the
     *                                  layout its mapper declares, or if a composed line does not reach
     *                                  its declared record width
     */
    public StatementRun generate(final StatementTransactionSource transactionSource,
                                 final UnaryOperator<String> regulatedFieldRevealer,
                                 final UnaryOperator<String> regulatedFieldSealer,
                                 final StatementOutputSink outputSink) {
        Objects.requireNonNull(transactionSource, "transactionSource must not be null");
        Objects.requireNonNull(regulatedFieldRevealer, "regulatedFieldRevealer must not be null");
        Objects.requireNonNull(regulatedFieldSealer, "regulatedFieldSealer must not be null");
        Objects.requireNonNull(outputSink, "outputSink must not be null");
        // The cross-reference cluster is read live from its own file, with no sort step and no work
        // resource between it and this member, so the run acquires its own sequential walk of it here
        // rather than being handed a snapshot as it is for the transaction work resource. One walk per
        // run: the collaborator holds no position, so two concurrent runs cannot share one.
        final StatementCrossReferenceSource crossReferenceSource = Objects.requireNonNull(
                this.statementDataAccessService.openCrossReferenceSource(),
                "the file handler reported no cross-reference source for this run");
        final StatementRunContext context =
                new StatementRunContext(transactionSource, crossReferenceSource,
                        regulatedFieldRevealer, regulatedFieldSealer, outputSink);
        openOutputFilesAndInitialiseTables(context);
        startDispatcher(context);
        return new StatementRun(context.statementRecordsEmitted, context.htmlRecordsEmitted,
                context.transactionSummariesEmitted, context.dispatcherEntries, context.crCnt,
                context.wsTrnTblCntr.total(context.crCnt), context.statementsWritten);
    }

    /**
     * The unnamed entry sequence at {@code [app/cbl/CBSTM03A.CBL:L293-L294]}: opening both output files
     * and initialising the card table and the transaction-count group.
     *
     * <p>Not a paragraph, which is why it does not appear in this member's count of 25. Opening an output
     * file becomes starting a fresh, empty record stream, and the two table initialisations are the fresh
     * tables the run context builds - a COBOL {@code INITIALIZE} over these two groups sets every card
     * number to spaces and every counter to zero, which is the state a newly constructed table is in.
     *
     * <p><strong>What precedes it in the source is deliberately absent.</strong> Lines
     * {@code L266-L291} set addressability over the prefixed save area, the task control block and the
     * task input/output table, walk the table's entries and display each DD name with the state of its
     * unit control block. That exists only to discover DD names from operating-system control blocks; it
     * has no equivalent once the data layer is relational, and no substitute is attempted here.
     *
     * @param context the run's working storage
     */
    private static void openOutputFilesAndInitialiseTables(final StatementRunContext context) {
        for (int cardSubscript = 1; cardSubscript <= MAX_CARD_ENTRIES; cardSubscript++) {
            context.wsTrnxTable.putCardNumber(cardSubscript, "");
            context.wsTrnTblCntr.putCount(cardSubscript, 0);
        }
        LOG.debug("Statement run opened output for {} and {}; card table initialised to {} entries of {}",
                DD_STMTFILE, DD_HTMLFILE, MAX_CARD_ENTRIES, MAX_TRANSACTIONS_PER_CARD);
    }

    // ================================================================================================
    // Paragraph 1 of 25 - 0000-START [app/cbl/CBSTM03A.CBL:L296]
    // ================================================================================================

    /**
     * The dispatcher of {@code 0000-START} at {@code [app/cbl/CBSTM03A.CBL:L296]}, whose selection at
     * {@code L298-L314} branches on {@code WS-FL-DD} through six clauses.
     *
     * <p>The six clauses appear below in exact source order - transaction-file open, cross-reference-file
     * open, customer-file open, account-file open, transaction read, and the catch-all that transfers to
     * the program exit. Order is contractual because a COBOL selection is evaluated top down and stops at
     * its first match.
     *
     * <p><strong>Re-entry is the whole point.</strong> Each phase returns the phase that its own
     * {@code MOVE ... TO WS-FL-DD} established, the loop then re-enters this selection, and the selection
     * branches again on the new value. That is what the source's four backward jumps to this paragraph -
     * {@code L761}, {@code L780}, {@code L798} and {@code L852} - actually do. Calling each phase from
     * the phase that preceded it would run the same code in the same order once and could never re-enter
     * a dispatcher after a state change, so the structure here is not a stylistic choice.
     *
     * <p>The state variable is a local of this method. It is deliberately not a field: a field would let
     * one run observe another's phase, and this service is a shared singleton.
     *
     * <p>The account-file clause is the one that leaves the loop's own alphabet. Its phase transfers to
     * the mainline at {@code L815} without changing {@code WS-FL-DD}, the mainline runs to completion and
     * then falls through into the program exit at {@code L341}; the terminal phase stands for that
     * fall-through and for the catch-all clause alike, so the program exit runs exactly once however the
     * run finishes.
     *
     * @param context the run's working storage
     */
    private void startDispatcher(final StatementRunContext context) {
        // WS-FL-DD PIC X(8) VALUE 'TRNXFILE' [app/cbl/CBSTM03A.CBL:L67] - the initial state.
        StatementPhase phase = StatementPhase.TRNXFILE;
        boolean dispatching = true;
        while (dispatching) {
            context.recordDispatch(phase);
            switch (phase) {
                // The TRNXFILE arm [L299-L301]
                case TRNXFILE -> phase = fileOpen(context, StatementPhase.TRNXFILE);
                // The XREFFILE arm [L302-L304]
                case XREFFILE -> phase = fileOpen(context, StatementPhase.XREFFILE);
                // The CUSTFILE arm [L305-L307]
                case CUSTFILE -> phase = fileOpen(context, StatementPhase.CUSTFILE);
                // The ACCTFILE arm [L308-L310]
                case ACCTFILE -> phase = fileOpen(context, StatementPhase.ACCTFILE);
                // The READTRNX arm [L311-L312]
                case READTRNX -> phase = readTransactionPhase(context);
                // The catch-all arm [L313-L314] - leaves by way of 9999-GOBACK
                case TERMINATED -> {
                    goBack(context);
                    dispatching = false;
                }
            }
        }
    }

    // ================================================================================================
    // Paragraph 2 of 25 - 1000-MAINLINE [app/cbl/CBSTM03A.CBL:L316]
    // ================================================================================================

    /**
     * {@code 1000-MAINLINE} at {@code [app/cbl/CBSTM03A.CBL:L316]}, the statement loop, reached by the
     * backward jump at {@code L815}.
     *
     * <p><strong>The order of the six steps is contractual</strong> and is reproduced exactly as
     * {@code L319-L326} writes it: read the next cross-reference record, read its customer, read its
     * account, create the statement, reset the walk subscript and the running total, then walk the
     * tabulated transactions. The reset is its own step between the statement and the walk and is not
     * folded into either of them, because the statement heading is written before the total is cleared
     * and the walk relies on both.
     *
     * <p>The outer loop and the two nested guards are all written against the same end-of-file flag, so a
     * cross-reference read that reports exhaustion abandons the current iteration before any of the four
     * dependent steps runs, and the outer test then ends the loop.
     *
     * <p>Control falls out of the bottom of this paragraph into the program exit, so this method returns
     * to a dispatcher that will see the terminal phase.
     *
     * @param context the run's working storage
     */
    private void mainline(final StatementRunContext context) {
        while (!AT_END_OF_FILE.equals(context.endOfFile)) {
            if (NOT_AT_END_OF_FILE.equals(context.endOfFile)) {
                crossReferenceFileGetNext(context);
                if (NOT_AT_END_OF_FILE.equals(context.endOfFile)) {
                    customerFileGet(context);
                    accountFileGet(context);
                    createStatement(context);
                    // Reset step [L324-L325]: CR-JMP back to one and the running total back to zero. Kept as its
                    // own step in its own position, exactly where the source places it.
                    context.crJmp = 1;
                    context.wsTotalAmt = ZonedDecimalCodec.toMonetaryScale(BigDecimal.ZERO);
                    transactionFileGet(context);
                }
            }
        }
        transactionFileClose(context);
        crossReferenceFileClose(context);
        customerFileClose(context);
        accountFileClose(context);
        LOG.debug("Statement run closed output for {} and {} after {} statements", DD_STMTFILE,
                DD_HTMLFILE, context.statementsWritten);
    }

    // ================================================================================================
    // Paragraph 3 of 25 - 9999-GOBACK [app/cbl/CBSTM03A.CBL:L341]
    // ================================================================================================

    /**
     * {@code 9999-GOBACK} at {@code [app/cbl/CBSTM03A.CBL:L341]}, whose body is the single
     * {@code GOBACK} that returns control to the operating system.
     *
     * <p>Reached two ways in the source and one way here. The source arrives either by falling out of the
     * mainline or through the dispatcher's catch-all clause; here both arrive as the terminal phase, so
     * this method runs once per run and every record it reports has already reached the sink.
     *
     * @param context the run's working storage, read only to report what the run produced
     */
    private static void goBack(final StatementRunContext context) {
        LOG.info("Statement generation complete: {} statement records, {} HTML records, {} cards, "
                        + "{} transactions", context.statementRecordsEmitted,
                context.htmlRecordsEmitted, context.crCnt,
                context.wsTrnTblCntr.total(context.crCnt));
    }

    // ================================================================================================
    // Paragraph 4 of 25 - 1000-XREFFILE-GET-NEXT [app/cbl/CBSTM03A.CBL:L345]
    // ================================================================================================

    /**
     * {@code 1000-XREFFILE-GET-NEXT} at {@code [app/cbl/CBSTM03A.CBL:L345]} - the sequential read that
     * drives the statement loop, and the eighth of the member's 13 call sites in source order.
     *
     * <p>The selection at {@code L353-L362} has three clauses in this order: success continues, at-end
     * sets the end-of-file flag, and anything else is a diagnostic followed by an abend.
     * <strong>End of file is not an error here and must never be folded into one</strong> - it is how the
     * statement loop terminates.
     *
     * <p>The move at {@code L364} then runs <em>unconditionally</em>, on the at-end arm too. At end of
     * file the payload is the blanked field this method supplied, so the move lands a blank group that
     * the mainline's own guard discards without reading a single field of it. The raw image is therefore
     * recorded on both arms, and the parsed view - which a blank group has no counterpart for and needs
     * none - is materialised only on the arm whose fields the mainline goes on to read.
     *
     * @param context the run's working storage
     */
    private void crossReferenceFileGetNext(final StatementRunContext context) {
        // L347-L350: DD name, sequential read, return code zeroed, payload blanked.
        context.m03bRc = RETURN_CODE_ZEROED;
        final StatementDataAccessService.StatementFileResponse response = callFileHandler(context,
                StatementDataAccessService.DD_XREFFILE, StatementDataAccessService.OPERATION_READ,
                NO_KEY, NO_KEY_LENGTH, context.xrefFilePosition);
        context.xrefFilePosition = response.sequentialPosition();
        switch (response.status().orElse(null)) {
            // The status-'00' arm [L354-L355], which does nothing
            case SUCCESS -> {
                // The read succeeded; the unconditional move below is all this arm does.
            }
            // The status-'10' arm [L356-L357]
            case END_OF_FILE -> context.endOfFile = AT_END_OF_FILE;
            // WHEN OTHER [L358-L361]
            case null, default -> {
                LOG.error("ERROR READING {} - RETURN CODE: {}",
                        StatementDataAccessService.DD_XREFFILE, context.m03bRc);
                throw abendProgram("ERROR READING XREFFILE", context.m03bRc, OPERATION_READ,
                        StatementDataAccessService.DD_XREFFILE);
            }
        }
        // L364 carries the payload field into the cross-reference record image, on both accepted arms.
        context.cardXrefRecordImage = context.m03bFldt;
        if (NOT_AT_END_OF_FILE.equals(context.endOfFile)) {
            context.cardXrefRecord = crossReferenceRecordFromPayload(context.cardXrefRecordImage);
        }
    }

    // ================================================================================================
    // Paragraph 5 of 25 - 2000-CUSTFILE-GET [app/cbl/CBSTM03A.CBL:L368]
    // ================================================================================================

    /**
     * {@code 2000-CUSTFILE-GET} at {@code [app/cbl/CBSTM03A.CBL:L368]} - the keyed read of the customer
     * the current cross-reference record names.
     *
     * <p>The key is the cross-reference record's customer identifier and the runtime key length is that
     * field's own length, computed at {@code L374} rather than assumed; the zeroing at {@code L373} that
     * the computation immediately overwrites has no observable effect and so leaves no trace here.
     *
     * <p>The selection at {@code L379-L386} has only two clauses: success continues and everything else
     * abends. There is no at-end arm, because a keyed read that finds nothing is a broken cross-reference
     * rather than an exhausted file.
     *
     * @param context the run's working storage
     */
    private void customerFileGet(final StatementRunContext context) {
        final String customerKey = context.cardXrefRecord.getXrefCustId();
        context.m03bRc = RETURN_CODE_ZEROED;
        final StatementDataAccessService.StatementFileResponse response = callFileHandler(context,
                StatementDataAccessService.DD_CUSTFILE, StatementDataAccessService.OPERATION_READ_KEYED,
                customerKey, customerKey.length(), FIRST_RECORD_POSITION);
        switch (response.status().orElse(null)) {
            // The status-'00' arm [L380-L381], which does nothing
            case SUCCESS -> {
                // The read succeeded; the move below is all this arm does.
            }
            // WHEN OTHER [L382-L385]
            case null, default -> {
                LOG.error("ERROR READING {} - RETURN CODE: {}",
                        StatementDataAccessService.DD_CUSTFILE, context.m03bRc);
                throw abendProgram("ERROR READING CUSTFILE", context.m03bRc, OPERATION_READ,
                        StatementDataAccessService.DD_CUSTFILE);
            }
        }
        // L388 carries the payload field into the customer record image.
        context.customerRecord = customerRecordFromPayload(context, context.m03bFldt);
    }

    // ================================================================================================
    // Paragraph 6 of 25 - 3000-ACCTFILE-GET [app/cbl/CBSTM03A.CBL:L392]
    // ================================================================================================

    /**
     * {@code 3000-ACCTFILE-GET} at {@code [app/cbl/CBSTM03A.CBL:L392]} - the keyed read of the account
     * the current cross-reference record names.
     *
     * <p>Identical in shape to the customer read, including the two-clause selection at
     * {@code L403-L410} with no at-end arm, and differing only in the key it presents: the
     * cross-reference record's account identifier, whose runtime length is computed at {@code L398}.
     *
     * @param context the run's working storage
     */
    private void accountFileGet(final StatementRunContext context) {
        final String accountKey = context.cardXrefRecord.getXrefAcctId();
        context.m03bRc = RETURN_CODE_ZEROED;
        final StatementDataAccessService.StatementFileResponse response = callFileHandler(context,
                StatementDataAccessService.DD_ACCTFILE, StatementDataAccessService.OPERATION_READ_KEYED,
                accountKey, accountKey.length(), FIRST_RECORD_POSITION);
        switch (response.status().orElse(null)) {
            // The status-'00' arm [L404-L405], which does nothing
            case SUCCESS -> {
                // The read succeeded; the move below is all this arm does.
            }
            // WHEN OTHER [L406-L409]
            case null, default -> {
                LOG.error("ERROR READING {} - RETURN CODE: {}",
                        StatementDataAccessService.DD_ACCTFILE, context.m03bRc);
                throw abendProgram("ERROR READING ACCTFILE", context.m03bRc, OPERATION_READ,
                        StatementDataAccessService.DD_ACCTFILE);
            }
        }
        // L412 carries the payload field into the account record image.
        context.accountRecord = accountRecordFromPayload(context.m03bFldt);
    }

    // ================================================================================================
    // Paragraph 7 of 25 - 4000-TRNXFILE-GET [app/cbl/CBSTM03A.CBL:L416]
    // ================================================================================================

    /**
     * {@code 4000-TRNXFILE-GET} at {@code [app/cbl/CBSTM03A.CBL:L416]} - walks the tabulated
     * transactions of the current card, emits one detail line each, and closes the statement.
     *
     * <p>The outer walk at {@code L417-L419} advances the card subscript and stops on either of two
     * conditions: the subscript passing the tabulated card count, or the tabulated card number sorting
     * after the one the cross-reference record names. COBOL evaluates the two left to right and stops at
     * the first that holds, so the subscript can never address an entry beyond the count; the guard here
     * short-circuits identically and for the same reason.
     *
     * <p>The inner walk at {@code L422-L423} is bounded by the card's own stored counter and tests before
     * each iteration. Its three moves at {@code L421} and {@code L424-L427} rebuild the statement work
     * area's transaction record from the table entry - card number, key and remainder - which here is one
     * assignment because the stored entry <em>is</em> that record; the card-number move sits outside the
     * inner walk in the source and is equivalent, since every entry of one card slot was tabulated from a
     * record carrying that slot's card number.
     *
     * <p>The accumulation at {@code L429} adds each amount to the running total. Both operands are exact
     * decimals already at the monetary scale, so the sum is exact and nothing is rounded; the total is
     * carried through the codec on its way into the receiving field at {@code L433} so that the module's
     * one truncating scale policy applies here as everywhere else.
     *
     * <p>Then the statement closes with the third write of the rule line at {@code L435} - the other two
     * are in the statement-creating paragraph - the total line at {@code L436}, and the end banner at
     * {@code L437}, whose 32/16/32 padding is not the start banner's 31/18/31. The HTML tail at
     * {@code L439-L454} closes the table, the body and the document.
     *
     * @param context the run's working storage
     */
    private void transactionFileGet(final StatementRunContext context) {
        final String crossReferenceCardNumber = context.cardXrefRecord.getXrefCardNum();
        for (context.crJmp = 1; !cardWalkComplete(context, crossReferenceCardNumber);
                context.crJmp++) {
            if (crossReferenceCardNumber.equals(context.wsTrnxTable.cardNumber(context.crJmp))) {
                for (context.trJmp = 1;
                        context.trJmp <= context.wsTrnTblCntr.count(context.crJmp);
                        context.trJmp++) {
                    context.trnxRecord =
                            context.wsTrnxTable.transaction(context.crJmp, context.trJmp);
                    writeTransaction(context);
                    // L429: ADD TRNX-AMT TO WS-TOTAL-AMT, stored into the nine-integer-digit
                    // accumulator the program declares.
                    context.wsTotalAmt = ZonedDecimalCodec.storeIntoMonetary(
                            context.wsTotalAmt.add(context.trnxRecord.getTranAmt()),
                            AMOUNT_FIELD_INTEGER_DIGITS, FIELD_WS_TOTAL_AMT);
                }
            }
        }
        // L433-L434
        context.wsTrnAmt = ZonedDecimalCodec.storeIntoMonetary(context.wsTotalAmt,
                AMOUNT_FIELD_INTEGER_DIGITS, FIELD_WS_TRN_AMT);
        context.stTotalTrAmt = ZonedDecimalCodec.storeIntoMonetary(context.wsTrnAmt,
                AMOUNT_FIELD_INTEGER_DIGITS, FIELD_ST_TOTAL_TRAMT);
        // L435-L437: the rule line's third write, the total, then the 32/16/32 end banner.
        context.writeStatementRecord(StatementTextTemplates.ST_LINE12_RULE);
        context.writeStatementRecord(
                StatementTextTemplates.stLine14aTotalExpenditure(context.stTotalTrAmt));
        context.writeStatementRecord(StatementTextTemplates.ST_LINE15_END_BANNER);
        // L439-L454
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRS);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L10);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L75);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTDE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L78);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L79);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L80);
    }

    // ================================================================================================
    // Paragraph 8 of 25 - 5000-CREATE-STATEMENT [app/cbl/CBSTM03A.CBL:L458]
    // ================================================================================================

    /**
     * {@code 5000-CREATE-STATEMENT} at {@code [app/cbl/CBSTM03A.CBL:L458]} - assembles the statement's
     * substituted values and writes its heading in both formats.
     *
     * <p>The statement-line reset at {@code L459} runs first, so the two assembling character transfers
     * below arrive at blank fields; the start banner at {@code L460} carries the 31/18/31 padding that
     * the end banner does not share, and the HTML heading range at {@code L461} is performed through its
     * own exit paragraph.
     *
     * <p><strong>The two character transfers stop each operand at its first single space.</strong> The
     * name at {@code L462-L469} interleaves one space between three name operands and the third address
     * line at {@code L472-L481} does the same across four address operands, and in both cases an operand
     * contributes only the characters preceding its first space. A two-word city therefore contributes
     * only its first word, exactly as on the mainframe; trimming instead, or splitting on runs of
     * whitespace, would put the second word into the record and break byte parity. The HTML composers use
     * a delimiter of two adjacent spaces instead, and that one belongs to the templates class.
     *
     * <p>Each assembled or moved value is then held at the width of the field it lands in, because two
     * different consumers read the same field: the plain line builders and the HTML composers. Holding
     * the field once at the move is what keeps them consistent, and every width named here is published
     * by the templates class that owns the line.
     *
     * <p>The write sequence at {@code L488-L502} closes the paragraph, and two of its writes are
     * duplicates that must survive: the rule line is written at {@code L492}, the basic-details heading at
     * {@code L493}, and <em>the same rule line again</em> at {@code L494}; and the transaction-table rule
     * line is written at {@code L500} and again at {@code L502} around the column headings, which with the
     * write at {@code L435} makes three. Both repetitions are present in the golden output.
     *
     * @param context the run's working storage
     */
    private void createStatement(final StatementRunContext context) {
        context.initializeStatementLines();
        context.writeStatementRecord(StatementTextTemplates.ST_LINE0_START_BANNER);
        // A run statistic rather than a legacy field: the source counts no statements, and the caller
        // needs the count to report what the run produced.
        context.statementsWritten++;
        writeHtmlHeader(context);
        writeHtmlHeaderExit();
        final Customer customer = context.customerRecord;
        // L462-L469
        context.stName = movedInto(transferUpTo(customer.getFirstName(), SINGLE_SPACE) + SINGLE_SPACE
                        + transferUpTo(customer.getMiddleName(), SINGLE_SPACE) + SINGLE_SPACE
                        + transferUpTo(customer.getLastName(), SINGLE_SPACE) + SINGLE_SPACE,
                StatementTextTemplates.ST_LINE1_NAME_WIDTH);
        // L470-L471
        context.stAdd1 = movedInto(customer.getAddrLine1(),
                StatementTextTemplates.ST_LINE2_ADDRESS_WIDTH);
        context.stAdd2 = movedInto(customer.getAddrLine2(),
                StatementTextTemplates.ST_LINE3_ADDRESS_WIDTH);
        // L472-L481
        context.stAdd3 = movedInto(transferUpTo(customer.getAddrLine3(), SINGLE_SPACE) + SINGLE_SPACE
                        + transferUpTo(customer.getAddrStateCd(), SINGLE_SPACE) + SINGLE_SPACE
                        + transferUpTo(customer.getAddrCountryCd(), SINGLE_SPACE) + SINGLE_SPACE
                        + transferUpTo(customer.getAddrZip(), SINGLE_SPACE) + SINGLE_SPACE,
                StatementTextTemplates.ST_LINE4_ADDRESS_WIDTH);
        // L483-L485
        context.stAcctId = movedInto(context.accountRecord.getAcctId(),
                StatementTextTemplates.ST_LINE7_ACCOUNT_ID_WIDTH);
        // L484 carries the account's current balance into the statement's balance mask. The source field
        // declares ten integer digits and the edited receiving field nine, so the store drops the
        // high-order digit exactly as the legacy store does.
        context.stCurrBal = ZonedDecimalCodec.storeIntoMonetary(
                context.accountRecord.getAcctCurrBal(),
                AMOUNT_FIELD_INTEGER_DIGITS, FIELD_ST_CURR_BAL);
        context.stFicoScore = movedInto(customer.getFicoCreditScore(),
                StatementTextTemplates.ST_LINE9_FICO_SCORE_WIDTH);
        // L486
        writeHtmlNameAddressBasics(context);
        writeHtmlNameAddressBasicsExit();
        // L488-L502
        context.writeStatementRecord(StatementTextTemplates.stLine1CustomerName(context.stName));
        context.writeStatementRecord(StatementTextTemplates.stLine2AddressLine1(context.stAdd1));
        context.writeStatementRecord(StatementTextTemplates.stLine3AddressLine2(context.stAdd2));
        context.writeStatementRecord(StatementTextTemplates.stLine4AddressLine3(context.stAdd3));
        context.writeStatementRecord(StatementTextTemplates.ST_LINE5_RULE);
        context.writeStatementRecord(StatementTextTemplates.ST_LINE6_BASIC_DETAILS_HEADING);
        // L494: the same rule line a second time. Deliberately not removed.
        context.writeStatementRecord(StatementTextTemplates.ST_LINE5_RULE);
        context.writeStatementRecord(StatementTextTemplates.stLine7AccountId(context.stAcctId));
        context.writeStatementRecord(StatementTextTemplates.stLine8CurrentBalance(context.stCurrBal));
        context.writeStatementRecord(StatementTextTemplates.stLine9FicoScore(context.stFicoScore));
        context.writeStatementRecord(StatementTextTemplates.ST_LINE10_RULE);
        context.writeStatementRecord(StatementTextTemplates.ST_LINE11_TRANSACTION_SUMMARY_HEADING);
        context.writeStatementRecord(StatementTextTemplates.ST_LINE12_RULE);
        context.writeStatementRecord(StatementTextTemplates.ST_LINE13_TRANSACTION_COLUMN_HEADINGS);
        // L502: the transaction-table rule line a second time here, and a third time at L435.
        context.writeStatementRecord(StatementTextTemplates.ST_LINE12_RULE);
    }

    // ================================================================================================
    // Paragraphs 9 and 10 of 25 - 5100-WRITE-HTML-HEADER [L506] THRU 5100-EXIT [L554]
    // ================================================================================================

    /**
     * {@code 5100-WRITE-HTML-HEADER} at {@code [app/cbl/CBSTM03A.CBL:L506]} - the document preamble and
     * the bank's own address block, 22 HTML records in source order.
     *
     * <p>Every record but one is an invariant template taken from the templates class and emitted in the
     * order the source sets and writes it. Two of those templates carry source oddities that are present
     * in the golden output and must survive: the table tag declared at {@code L157} contains two adjacent
     * spaces after the element name, and the estate's malformed unclosed paragraph tag is likewise
     * reproduced rather than repaired. Nothing here builds, escapes, normalises or pretty-prints markup.
     *
     * <p>The one composed record is the account heading at {@code L529-L530}, whose account identifier
     * occupies a twenty-byte field inside a fifty-nine-byte group that the write then pads to the record
     * width.
     *
     * @param context the run's working storage
     */
    private void writeHtmlHeader(final StatementRunContext context) {
        // L508-L527
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L01);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L02);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L03);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L04);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L05);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L06);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L07);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L08);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRS);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L10);
        // L529-L530
        context.writeHtmlRecord(
                StatementHtmlTemplates.accountNumberLine(context.accountRecord.getAcctId()));
        // L531-L552
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTDE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRS);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L15);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L16);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L17);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L18);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTDE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRS);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L22_35);
    }

    /**
     * {@code 5100-EXIT} at {@code [app/cbl/CBSTM03A.CBL:L554]}, whose body is the single {@code EXIT}
     * that terminates the range performed at {@code L461}.
     *
     * <p>A range terminator does no work by definition, so the trace below is the whole of its
     * observable behaviour. It is named and invoked rather than absorbed into its range so that every one
     * of this member's 25 paragraphs has a Java method the traceability matrix can point at.
     */
    private static void writeHtmlHeaderExit() {
        LOG.trace("5100-WRITE-HTML-HEADER THRU 5100-EXIT complete");
    }

    // ================================================================================================
    // Paragraphs 11 and 12 of 25 - 5200-WRITE-HTML-NMADBS [L558] THRU 5200-EXIT [L671]
    // ================================================================================================

    /**
     * {@code 5200-WRITE-HTML-NMADBS} at {@code [app/cbl/CBSTM03A.CBL:L558]} - the name, address and
     * basic-details blocks of the HTML statement, 33 records in source order.
     *
     * <p><strong>The name line's concatenation stops at the first pair of adjacent spaces.</strong> The
     * move at {@code L560} places the assembled name in a fifty-byte member and the transfer at
     * {@code L562-L567} then stops at the first pair of adjacent spaces, so the padding the move left is
     * dropped and the delimiter and closing tag follow it. A single-space split would stop after the
     * first forename and a trim would not reproduce the two delimiter spaces the record carries, so
     * neither substitution is available; the composer named below owns that behaviour.
     *
     * <p><strong>The seventy-six-byte group of {@code L217-L220} is populated but never written.</strong>
     * Its population is that fifty-byte move, which the composer performs on the way to the record it
     * does write. The group itself - opening tag plus name, with no closing tag - is never assembled,
     * never emitted, and its missing closing tag is never repaired.
     *
     * <p>The three address records at {@code L569-L592} use that same two-space delimiter over the
     * three address fields. The three basic-detail records at {@code L613-L633} use a delimiter that
     * appears nowhere in the data, so each transfers its whole field: a twenty-one-byte label - one space
     * wider than the plain statement's twenty-byte label, which is why the two are separate literals -
     * followed by the field the statement holds.
     *
     * <p>The balance is rendered by the unsuppressed thirteen-byte mask, the same one the plain
     * current-balance line uses and not the zero-suppressed mask the amounts use.
     *
     * @param context the run's working storage
     */
    private void writeHtmlNameAddressBasics(final StatementRunContext context) {
        // L560-L568
        context.writeHtmlRecord(StatementHtmlTemplates.customerNameLine(context.stName));
        // L569-L592
        context.writeHtmlRecord(StatementHtmlTemplates.addressWorkLine(context.stAdd1));
        context.writeHtmlRecord(StatementHtmlTemplates.addressWorkLine(context.stAdd2));
        context.writeHtmlRecord(StatementHtmlTemplates.addressWorkLine(context.stAdd3));
        // L594-L611
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTDE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRS);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L30_42);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L31);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTDE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRS);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L22_35);
        // L613-L633
        context.writeHtmlRecord(StatementHtmlTemplates.basicDetailsWorkLine(HTML_LABEL_ACCOUNT_ID,
                context.stAcctId));
        context.writeHtmlRecord(StatementHtmlTemplates.basicDetailsWorkLine(HTML_LABEL_CURRENT_BALANCE,
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(context.stCurrBal)));
        context.writeHtmlRecord(StatementHtmlTemplates.basicDetailsWorkLine(HTML_LABEL_FICO_SCORE,
                context.stFicoScore));
        // L634-L669
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTDE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRS);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L30_42);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L43);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTDE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRS);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L47);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L48);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTDE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L50);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L51);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTDE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L53);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L54);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTDE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRE);
    }

    /**
     * {@code 5200-EXIT} at {@code [app/cbl/CBSTM03A.CBL:L671]}, whose body is the single {@code EXIT}
     * that terminates the range performed at {@code L486}.
     *
     * <p>Named and invoked for the same reason as the header range's exit: a range terminator does no
     * work, and every one of the member's 25 paragraphs needs a method to be traceable to.
     */
    private static void writeHtmlNameAddressBasicsExit() {
        LOG.trace("5200-WRITE-HTML-NMADBS THRU 5200-EXIT complete");
    }

    // ================================================================================================
    // Paragraph 13 of 25 - 6000-WRITE-TRANS [app/cbl/CBSTM03A.CBL:L675]
    // ================================================================================================

    /**
     * {@code 6000-WRITE-TRANS} at {@code [app/cbl/CBSTM03A.CBL:L675]} - one transaction's detail line in
     * both formats, plus the summary the caller collects.
     *
     * <p>The three moves at {@code L676-L678} narrow the record's fields to the widths the detail line
     * declares. The description is the one that matters: the record carries a hundred characters and the
     * line's field is forty-nine, so the tail is lost on the mainframe and is lost identically here - and
     * because the HTML cell reads the same forty-nine-byte field rather than the record, the narrowing
     * has to happen before either format is composed.
     *
     * <p>The amount is rendered by the zero-suppressed thirteen-byte mask, which is not the mask the
     * balance uses, and never by an amount's plain decimal text.
     *
     * <p>The summary appended at the end is not a legacy field. It carries the tabulated record's own
     * values so a caller can report what a statement contained without re-reading anything, and it
     * neither influences nor is influenced by the two records written above.
     *
     * @param context the run's working storage
     */
    private void writeTransaction(final StatementRunContext context) {
        final Transaction transaction = context.trnxRecord;
        // L676-L678
        context.stTranId = movedInto(transaction.getTranId(),
                StatementTextTemplates.ST_LINE14_TRAN_ID_WIDTH);
        context.stTranDt = movedInto(transaction.getTranDesc(),
                StatementTextTemplates.ST_LINE14_TRAN_DETAIL_WIDTH);
        context.stTranAmt = ZonedDecimalCodec.storeIntoMonetary(transaction.getTranAmt(),
                AMOUNT_FIELD_INTEGER_DIGITS, FIELD_ST_TRANAMT);
        // L679
        context.writeStatementRecord(StatementTextTemplates.stLine14Transaction(context.stTranId,
                context.stTranDt, context.stTranAmt));
        // L681-L721
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRS);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L58);
        context.writeHtmlRecord(StatementHtmlTemplates.transactionWorkLine(context.stTranId));
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTDE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L61);
        context.writeHtmlRecord(StatementHtmlTemplates.transactionWorkLine(context.stTranDt));
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTDE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_L64);
        context.writeHtmlRecord(StatementHtmlTemplates.transactionWorkLine(
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(context.stTranAmt)));
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTDE);
        context.writeHtmlRecord(StatementHtmlTemplates.HTML_LTRE);
        context.emitTransactionSummary(new StatementLineSummary(transaction.getTranCardNum(),
                transaction.getTranId(), transaction.getTranTypeCd(), transaction.getTranCatCd(),
                transaction.getTranSource(), transaction.getTranDesc(), transaction.getTranAmt(),
                transaction.getMerchantId(), transaction.getMerchantName(),
                transaction.getMerchantCity(), transaction.getMerchantZip(),
                transaction.getTranOrigTs(), transaction.getTranProcTs()));
    }

    // ================================================================================================
    // Paragraph 14 of 25 - 8100-FILE-OPEN [app/cbl/CBSTM03A.CBL:L726]
    // ================================================================================================

    /**
     * {@code 8100-FILE-OPEN} at {@code [app/cbl/CBSTM03A.CBL:L726-L728]} - the run-time-alterable branch
     * whose destination each dispatcher clause rewrites before transferring to it.
     *
     * <p>The source alters this paragraph's single {@code GO TO} to one of four open paragraphs and then
     * jumps here, so the branch is a variable in all but name. Its four destinations are statically known
     * - the dispatcher's four open clauses name them one apiece - so they collapse into the selection
     * below and no run-time branch rewriting is needed or attempted.
     *
     * <p>The paragraph is nonetheless kept as its own named method rather than inlined into the
     * dispatcher, so that the traceability matrix has a method to point at, and the collapse changes
     * nothing about dispatcher re-entry: each destination still returns the phase it established and the
     * dispatcher still re-branches on it.
     *
     * <p>The two phases that never reach here are the read phase, which the dispatcher transfers directly
     * to its own paragraph, and the terminal phase, which ends the run. Reaching this method with either
     * would mean the dispatcher had routed a clause to the wrong destination, which is a broken control
     * state and is reported through the same abend path as a failed file operation.
     *
     * @param context       the run's working storage
     * @param alteredTarget the destination the dispatcher clause established
     * @return the phase the destination paragraph established
     */
    private StatementPhase fileOpen(final StatementRunContext context,
                                    final StatementPhase alteredTarget) {
        return switch (alteredTarget) {
            case TRNXFILE -> transactionFileOpen(context);
            case XREFFILE -> crossReferenceFileOpen(context);
            case CUSTFILE -> customerFileOpen(context);
            case ACCTFILE -> accountFileOpen(context);
            case READTRNX, TERMINATED -> {
                LOG.error("FILE OPEN BRANCH NOT ALTERED - phase {} has no open paragraph",
                        alteredTarget);
                throw abendProgram("FILE OPEN BRANCH NOT ALTERED", null, OPERATION_OPEN,
                        DD_STMTFILE);
            }
        };
    }

    // ================================================================================================
    // Paragraph 15 of 25 - 8100-TRNXFILE-OPEN [app/cbl/CBSTM03A.CBL:L730]
    // ================================================================================================

    /**
     * {@code 8100-TRNXFILE-OPEN} at {@code [app/cbl/CBSTM03A.CBL:L730]} - opens the transaction file,
     * primes the read phase with its first record, and hands control back to the dispatcher.
     *
     * <p>Two of the member's 13 call sites are here. The open at {@code L734} follows a zeroing of the
     * return code; the read at {@code L746} <strong>does not</strong>, so the value carried into that call
     * is whatever the open left, and that asymmetry is reproduced rather than tidied away.
     *
     * <p>Both guards at {@code L736} and {@code L748} accept either the success status or the
     * record-length-mismatch status - two accepted codes, not one. The read guard in particular means an
     * empty transaction file abends here rather than producing an empty run, because the at-end status is
     * not among the two it accepts.
     *
     * <p>The four moves at {@code L756-L759} then prime the read phase: the record image becomes the
     * tabulated record, its card number becomes the break-detection baseline, the card count starts at one
     * and the transaction count at zero. The state moves to the read phase at {@code L760} and the jump at
     * {@code L761} returns to the dispatcher.
     *
     * @param context the run's working storage
     * @return the read phase
     */
    private StatementPhase transactionFileOpen(final StatementRunContext context) {
        // L731-L734
        context.m03bRc = RETURN_CODE_ZEROED;
        StatementDataAccessService.StatementFileResponse response = callFileHandler(context,
                StatementDataAccessService.DD_TRNXFILE, StatementDataAccessService.OPERATION_OPEN,
                NO_KEY, NO_KEY_LENGTH, context.trnxFilePosition);
        context.trnxFilePosition = response.sequentialPosition();
        // L736-L742
        if (!isAcceptedByGuard(context.m03bRc)) {
            LOG.error("ERROR OPENING {} - RETURN CODE: {}", StatementDataAccessService.DD_TRNXFILE,
                    context.m03bRc);
            throw abendProgram("ERROR OPENING TRNXFILE", context.m03bRc, OPERATION_OPEN,
                    StatementDataAccessService.DD_TRNXFILE);
        }
        // L744-L746: the return code is deliberately not zeroed before this call.
        response = callFileHandler(context, StatementDataAccessService.DD_TRNXFILE,
                StatementDataAccessService.OPERATION_READ, NO_KEY, NO_KEY_LENGTH,
                context.trnxFilePosition);
        context.trnxFilePosition = response.sequentialPosition();
        // L748-L754
        if (!isAcceptedByGuard(context.m03bRc)) {
            LOG.error("ERROR READING {} - RETURN CODE: {}", StatementDataAccessService.DD_TRNXFILE,
                    context.m03bRc);
            throw abendProgram("ERROR READING TRNXFILE", context.m03bRc, OPERATION_READ,
                    StatementDataAccessService.DD_TRNXFILE);
        }
        // L756-L759
        context.trnxRecord = transactionRecordFromPayload(context.m03bFldt);
        context.wsSaveCard = context.trnxRecord.getTranCardNum();
        context.crCnt = 1;
        context.trCnt = 0;
        // L760-L761
        return StatementPhase.READTRNX;
    }

    // ================================================================================================
    // Paragraph 16 of 25 - 8200-XREFFILE-OPEN [app/cbl/CBSTM03A.CBL:L765]
    // ================================================================================================

    /**
     * {@code 8200-XREFFILE-OPEN} at {@code [app/cbl/CBSTM03A.CBL:L765]} - opens the cross-reference file
     * and hands control back to the dispatcher.
     *
     * <p>One call site, at {@code L769}, guarded at {@code L771} by the two-code test. The state moves to
     * the customer-file phase at {@code L779} and the jump at {@code L780} returns to the dispatcher.
     *
     * @param context the run's working storage
     * @return the customer-file phase
     */
    private StatementPhase crossReferenceFileOpen(final StatementRunContext context) {
        // L766-L769
        context.m03bRc = RETURN_CODE_ZEROED;
        final StatementDataAccessService.StatementFileResponse response = callFileHandler(context,
                StatementDataAccessService.DD_XREFFILE, StatementDataAccessService.OPERATION_OPEN,
                NO_KEY, NO_KEY_LENGTH, context.xrefFilePosition);
        context.xrefFilePosition = response.sequentialPosition();
        // L771-L777
        if (!isAcceptedByGuard(context.m03bRc)) {
            LOG.error("ERROR OPENING {} - RETURN CODE: {}", StatementDataAccessService.DD_XREFFILE,
                    context.m03bRc);
            throw abendProgram("ERROR OPENING XREFFILE", context.m03bRc, OPERATION_OPEN,
                    StatementDataAccessService.DD_XREFFILE);
        }
        // L779-L780
        return StatementPhase.CUSTFILE;
    }

    // ================================================================================================
    // Paragraph 17 of 25 - 8300-CUSTFILE-OPEN [app/cbl/CBSTM03A.CBL:L783]
    // ================================================================================================

    /**
     * {@code 8300-CUSTFILE-OPEN} at {@code [app/cbl/CBSTM03A.CBL:L783]} - opens the customer file and
     * hands control back to the dispatcher.
     *
     * <p>One call site, at {@code L787}, guarded at {@code L789}. The state moves to the account-file
     * phase at {@code L797} and the jump at {@code L798} returns to the dispatcher.
     *
     * @param context the run's working storage
     * @return the account-file phase
     */
    private StatementPhase customerFileOpen(final StatementRunContext context) {
        // L784-L787
        context.m03bRc = RETURN_CODE_ZEROED;
        callFileHandler(context, StatementDataAccessService.DD_CUSTFILE,
                StatementDataAccessService.OPERATION_OPEN, NO_KEY, NO_KEY_LENGTH,
                FIRST_RECORD_POSITION);
        // L789-L795
        if (!isAcceptedByGuard(context.m03bRc)) {
            LOG.error("ERROR OPENING {} - RETURN CODE: {}", StatementDataAccessService.DD_CUSTFILE,
                    context.m03bRc);
            throw abendProgram("ERROR OPENING CUSTFILE", context.m03bRc, OPERATION_OPEN,
                    StatementDataAccessService.DD_CUSTFILE);
        }
        // L797-L798
        return StatementPhase.ACCTFILE;
    }

    // ================================================================================================
    // Paragraph 18 of 25 - 8400-ACCTFILE-OPEN [app/cbl/CBSTM03A.CBL:L801]
    // ================================================================================================

    /**
     * {@code 8400-ACCTFILE-OPEN} at {@code [app/cbl/CBSTM03A.CBL:L801]} - opens the account file and
     * transfers to the mainline.
     *
     * <p>One call site, at {@code L805}, guarded at {@code L807}. This is the one open that does not set a
     * new phase: the jump at {@code L815} goes to the mainline instead, so the phase field still holds
     * this paragraph's own value while the mainline runs.
     *
     * <p>The mainline is therefore invoked from inside this arm, exactly as the backward jump does, and
     * because control falls out of the mainline's bottom into the program exit the phase returned is the
     * terminal one.
     *
     * @param context the run's working storage
     * @return the terminal phase, so the dispatcher's catch-all clause reaches the program exit
     */
    private StatementPhase accountFileOpen(final StatementRunContext context) {
        // L802-L805
        context.m03bRc = RETURN_CODE_ZEROED;
        callFileHandler(context, StatementDataAccessService.DD_ACCTFILE,
                StatementDataAccessService.OPERATION_OPEN, NO_KEY, NO_KEY_LENGTH,
                FIRST_RECORD_POSITION);
        // L807-L813
        if (!isAcceptedByGuard(context.m03bRc)) {
            LOG.error("ERROR OPENING {} - RETURN CODE: {}", StatementDataAccessService.DD_ACCTFILE,
                    context.m03bRc);
            throw abendProgram("ERROR OPENING ACCTFILE", context.m03bRc, OPERATION_OPEN,
                    StatementDataAccessService.DD_ACCTFILE);
        }
        // L815
        mainline(context);
        return StatementPhase.TERMINATED;
    }

    // ================================================================================================
    // Paragraphs 19 and 20 of 25 - 8500-READTRNX-READ [L818] and 8599-EXIT [L849]
    // ================================================================================================

    /**
     * {@code 8500-READTRNX-READ} at {@code [app/cbl/CBSTM03A.CBL:L818]} - tabulates the transaction file
     * into the card table, one record per iteration of its own inner loop.
     *
     * <p>The break detection at {@code L819-L825} compares the record's card number with the previous
     * record's: the same card advances the running transaction count, a different one stores that count
     * against the card just finished, advances the card count and restarts the transaction count at one.
     * The three moves at {@code L827-L829} then store the card number and the record, and {@code L830}
     * refreshes the baseline.
     *
     * <p><strong>The read at {@code L835} closes an inner loop, not the paragraph.</strong> The selection
     * at {@code L837-L847} has three arms in this order: on success the record image becomes the
     * tabulated record and the jump at {@code L840} re-enters this paragraph; on at-end the jump at
     * {@code L842} leaves for the phase exit; and on anything else a diagnostic is emitted and the run
     * abends. The success arm's jump is a genuine loop and the at-end arm's is a genuine phase exit, so
     * collapsing the two into one outcome - or treating at-end as an error - would break the tabulation.
     *
     * <p>The return code is not zeroed before this call, matching {@code L832-L835}, so the value carried
     * in is the one the previous read left.
     *
     * <p>The bound check has no counterpart in the source, which would overrun its subscript. The table is
     * fixed at the occurrence counts the source declares, and an input presenting more distinct cards or
     * more transactions on one card is reported through the abend path rather than allowed to corrupt
     * adjacent storage.
     *
     * @param context the run's working storage
     * @return the phase the exit paragraph establishes
     */
    private StatementPhase readTransactionPhase(final StatementRunContext context) {
        boolean reading = true;
        while (reading) {
            // L819-L825
            if (context.wsSaveCard.equals(context.trnxRecord.getTranCardNum())) {
                context.trCnt = context.trCnt + 1;
            } else {
                context.wsTrnTblCntr.putCount(context.crCnt, context.trCnt);
                context.crCnt = context.crCnt + 1;
                context.trCnt = 1;
            }
            requireTableCapacity(context);
            // L827-L830
            context.wsTrnxTable.putCardNumber(context.crCnt, context.trnxRecord.getTranCardNum());
            context.wsTrnxTable.putTransaction(context.crCnt, context.trCnt, context.trnxRecord);
            context.wsSaveCard = context.trnxRecord.getTranCardNum();
            // L832-L835: the return code is deliberately not zeroed before this call.
            final StatementDataAccessService.StatementFileResponse response = callFileHandler(context,
                    StatementDataAccessService.DD_TRNXFILE, StatementDataAccessService.OPERATION_READ,
                    NO_KEY, NO_KEY_LENGTH, context.trnxFilePosition);
            context.trnxFilePosition = response.sequentialPosition();
            switch (response.status().orElse(null)) {
                // The status-'00' arm [L838-L840] - move the record and loop back to this paragraph.
                case SUCCESS -> context.trnxRecord = transactionRecordFromPayload(context.m03bFldt);
                // The status-'10' arm [L841-L842] - leave for 8599-EXIT.
                case END_OF_FILE -> reading = false;
                // WHEN OTHER [L843-L846]
                case null, default -> {
                    LOG.error("ERROR READING {} - RETURN CODE: {}",
                            StatementDataAccessService.DD_TRNXFILE, context.m03bRc);
                    throw abendProgram("ERROR READING TRNXFILE", context.m03bRc, OPERATION_READ,
                            StatementDataAccessService.DD_TRNXFILE);
                }
            }
        }
        return readTransactionPhaseExit(context);
    }

    /**
     * {@code 8599-EXIT} at {@code [app/cbl/CBSTM03A.CBL:L849]} - the read phase's exit, which is itself a
     * state transition rather than a return.
     *
     * <p>It stores the last card's transaction count at {@code L850}, moves the state to the
     * cross-reference-file phase at {@code L851}, and the jump at {@code L852} returns to the dispatcher.
     * The read phase therefore does not end the run or hand control to a caller: it changes state and the
     * dispatcher branches again, which is exactly why the phases cannot be nested calls.
     *
     * @param context the run's working storage
     * @return the cross-reference-file phase
     */
    private static StatementPhase readTransactionPhaseExit(final StatementRunContext context) {
        // L850-L852
        context.wsTrnTblCntr.putCount(context.crCnt, context.trCnt);
        return StatementPhase.XREFFILE;
    }

    // ================================================================================================
    // Paragraphs 21 to 24 of 25 - the four close paragraphs [L856, L873, L889, L905]
    // ================================================================================================

    /**
     * {@code 9100-TRNXFILE-CLOSE} at {@code [app/cbl/CBSTM03A.CBL:L856]} - closes the transaction file.
     *
     * <p>One call site, at {@code L860}, preceded by a zeroing of the return code and guarded at
     * {@code L862} by the same two-code test the opens use.
     *
     * @param context the run's working storage
     */
    private void transactionFileClose(final StatementRunContext context) {
        closeFile(context, StatementDataAccessService.DD_TRNXFILE, "ERROR CLOSING TRNXFILE");
    }

    /**
     * {@code 9200-XREFFILE-CLOSE} at {@code [app/cbl/CBSTM03A.CBL:L873]} - closes the cross-reference
     * file. One call site, at {@code L877}, guarded at {@code L879}.
     *
     * @param context the run's working storage
     */
    private void crossReferenceFileClose(final StatementRunContext context) {
        closeFile(context, StatementDataAccessService.DD_XREFFILE, "ERROR CLOSING XREFFILE");
    }

    /**
     * {@code 9300-CUSTFILE-CLOSE} at {@code [app/cbl/CBSTM03A.CBL:L889]} - closes the customer file. One
     * call site, at {@code L893}, guarded at {@code L895}.
     *
     * @param context the run's working storage
     */
    private void customerFileClose(final StatementRunContext context) {
        closeFile(context, StatementDataAccessService.DD_CUSTFILE, "ERROR CLOSING CUSTFILE");
    }

    /**
     * {@code 9400-ACCTFILE-CLOSE} at {@code [app/cbl/CBSTM03A.CBL:L905]} - closes the account file. One
     * call site, at {@code L909}, guarded at {@code L911}.
     *
     * @param context the run's working storage
     */
    private void accountFileClose(final StatementRunContext context) {
        closeFile(context, StatementDataAccessService.DD_ACCTFILE, "ERROR CLOSING ACCTFILE");
    }

    // ================================================================================================
    // Paragraph 25 of 25 - 9999-ABEND-PROGRAM [app/cbl/CBSTM03A.CBL:L921]
    // ================================================================================================

    /**
     * {@code 9999-ABEND-PROGRAM} at {@code [app/cbl/CBSTM03A.CBL:L921]} - emits the abend diagnostic and
     * raises, in that order.
     *
     * <p>The source displays its own line at {@code L922} and only then calls the Language Environment
     * abort routine at {@code L923}, and every paragraph that reaches it has already displayed its own
     * message and the raw return code. That ordering is reproduced exactly: the caller logs the failure
     * and the status, this method logs the abend, and the abend service logs its diagnostic before
     * raising. Three emissions for the source's three displays, all before anything is thrown.
     *
     * <p>The declared return type is what lets every call site read as {@code throw abendProgram(...)},
     * which both documents the transfer of control and lets the compiler see that the site does not
     * continue. The abend service always raises, so the exception constructed below is a defensive
     * fallback rather than the normal path.
     *
     * @param reason         the legacy display literal describing why the run is failing, at most 50
     *                       characters
     * @param rawFileStatus  the raw two-character file status, or {@code null} when no file operation is
     *                       implicated
     * @param operation      the operation that failed
     * @param resourceName   the DD name of the file the operation names
     * @return the abend to raise, in the event the abend service returns rather than raising
     */
    private AbendException abendProgram(final String reason, final String rawFileStatus,
                                        final String operation, final String resourceName) {
        LOG.error("ABENDING PROGRAM {} - {} (operation {} on {}, file status {})", PROGRAM_NAME, reason,
                operation, resourceName, rawFileStatus);
        this.abendService.abendBatch(PROGRAM_NAME, reason, rawFileStatus, operation, resourceName);
        return new AbendException(AbendException.BATCH_ABEND_CODE, PROGRAM_NAME, reason,
                AbendException.DEFAULT_MESSAGE);
    }

    // ================================================================================================
    // Helpers - the verbs the paragraphs above share, and nothing more
    // ================================================================================================

    /**
     * One of the member's 13 calls to the file-handling subprogram, with the parameter object populated
     * and the mutable components written back into the run's storage.
     *
     * <p>The payload is blanked on every call. The legacy blanks it explicitly before each of its five
     * reads, and blanking it before the opens and closes as well changes nothing - no open or close
     * consults it - while removing any chance of a stale image reaching the next read.
     *
     * <p>The return code carried <em>in</em> is whatever the run's storage currently holds, which is what
     * makes the source's asymmetry expressible: eleven of the thirteen sites zero it first and the two
     * transaction-file reads deliberately do not. The revealing operation travels on every request
     * because the parameter object declares it; only the customer keyed read consults it.
     *
     * <p>The two declared-but-dead operations are never passed here, because no paragraph names them.
     *
     * @param context            the run's working storage
     * @param ddName             the DD name selecting the file
     * @param operation          the operation code
     * @param key                the key value, empty for every operation but a keyed read
     * @param keyLength          the runtime key length, zero for every operation but a keyed read
     * @param sequentialPosition the file position for a sequentially accessed file
     * @return the response, whose status and payload have already been written back into {@code context}
     */
    private StatementDataAccessService.StatementFileResponse callFileHandler(
            final StatementRunContext context, final String ddName, final String operation,
            final String key, final int keyLength, final int sequentialPosition) {
        final StatementDataAccessService.StatementFileRequest request =
                new StatementDataAccessService.StatementFileRequest(ddName, operation, context.m03bRc,
                        key, keyLength, BLANK_PAYLOAD, sequentialPosition,
                        context.regulatedFieldRevealer);
        final StatementDataAccessService.StatementFileResponse response = this.statementDataAccessService
                .execute(request, context.transactionSource, context.crossReferenceSource);
        context.m03bRc = response.returnCode();
        context.m03bFldt = response.payload();
        return response;
    }

    /**
     * The guard-form status test - WS-M03B-RC accepted at '00' or '04' - used at the four opens, the first
     * transaction-file read and the four closes - nine of the member's status tests and no others.
     *
     * <p><strong>Two codes are accepted, not one.</strong> The at-end status is deliberately absent, which
     * is why an empty transaction file abends at the priming read rather than yielding an empty run.
     *
     * @param returnCode the raw two-character status the call left behind
     * @return {@code true} when the operation is to be treated as successful
     */
    private static boolean isAcceptedByGuard(final String returnCode) {
        return STATUS_SUCCESS.equals(returnCode) || STATUS_RECORD_LENGTH_MISMATCH.equals(returnCode);
    }

    /**
     * The four close paragraphs' shared body: zero the return code, close, then apply the guard.
     *
     * <p>The four paragraphs are textually identical but for the DD name and the display literal, so the
     * body lives here once and each paragraph supplies its own two values. Each remains a named method of
     * its own, because each is a paragraph of the source.
     *
     * @param context      the run's working storage
     * @param ddName       the DD name of the file to close
     * @param failureReason the legacy display literal for a failed close
     */
    private void closeFile(final StatementRunContext context, final String ddName,
                           final String failureReason) {
        context.m03bRc = RETURN_CODE_ZEROED;
        callFileHandler(context, ddName, StatementDataAccessService.OPERATION_CLOSE, NO_KEY,
                NO_KEY_LENGTH, FIRST_RECORD_POSITION);
        if (!isAcceptedByGuard(context.m03bRc)) {
            LOG.error("{} - RETURN CODE: {}", failureReason, context.m03bRc);
            throw abendProgram(failureReason, context.m03bRc, OPERATION_CLOSE, ddName);
        }
    }

    /**
     * The card walk's termination test from {@code [app/cbl/CBSTM03A.CBL:L418-L419]}.
     *
     * <p>Two conditions, evaluated left to right and stopping at the first that holds, exactly as COBOL
     * evaluates them: the subscript passing the tabulated card count, or the tabulated card number sorting
     * after the one the cross-reference record names. The short circuit is load-bearing rather than
     * incidental - it is what keeps the second condition from addressing an entry past the count.
     *
     * @param context                    the run's working storage
     * @param crossReferenceCardNumber   the card number the current cross-reference record names
     * @return {@code true} when the walk is to stop
     */
    private static boolean cardWalkComplete(final StatementRunContext context,
                                            final String crossReferenceCardNumber) {
        return context.crJmp > context.crCnt
                || context.wsTrnxTable.cardNumber(context.crJmp)
                        .compareTo(crossReferenceCardNumber) > 0;
    }

    /**
     * Verifies that the two running subscripts still address real table entries before the tabulation
     * stores through them.
     *
     * <p>No counterpart in the source, which would simply overrun. The table is fixed at the occurrence
     * counts the source declares - legacy capacity, not a tuning value - and a run that exceeds either is
     * reported through the abend path instead of writing outside its bounds.
     *
     * @param context the run's working storage
     */
    private void requireTableCapacity(final StatementRunContext context) {
        if (!context.wsTrnxTable.holdsCard(context.crCnt)) {
            LOG.error("CARD TABLE FULL - {} entries declared, subscript {} requested",
                    MAX_CARD_ENTRIES, context.crCnt);
            throw abendProgram("CARD TABLE FULL", null, OPERATION_READ,
                    StatementDataAccessService.DD_TRNXFILE);
        }
        if (!context.wsTrnxTable.holdsTransaction(context.trCnt)) {
            LOG.error("TRANSACTION TABLE FULL - {} entries declared per card, subscript {} requested",
                    MAX_TRANSACTIONS_PER_CARD, context.trCnt);
            throw abendProgram("TRANSACTION TABLE FULL", null, OPERATION_READ,
                    StatementDataAccessService.DD_TRNXFILE);
        }
    }

    /**
     * A legacy store of an alphanumeric value into a fixed-width alphanumeric field: left
     * justified, padded on the right with spaces, truncated on the right when too long.
     *
     * <p>Every width this is called with is published by the templates class that owns the line the field
     * belongs to, so no picture width is restated here and the record layouts stay in one place. The
     * transfer is a verb translation rather than layout knowledge, which is why it lives here and not
     * there.
     *
     * @param value      the sending value
     * @param fieldWidth the receiving field's declared width
     * @return the field's contents after the move, exactly {@code fieldWidth} characters
     */
    private static String movedInto(final String value, final int fieldWidth) {
        final StringBuilder field = new StringBuilder(fieldWidth);
        for (int index = 0; index < fieldWidth; index++) {
            field.append(index < value.length() ? value.charAt(index) : SPACE);
        }
        return field.toString();
    }

    /**
     * A COBOL {@code STRING ... DELIMITED BY} transfer: the characters of an operand that precede its
     * first occurrence of the delimiter.
     *
     * <p>This is pointer semantics, not trimming and not splitting on whitespace. An operand whose first
     * character is the delimiter contributes nothing, an operand that never contains it contributes all of
     * itself, and an operand containing it contributes only its leading run - which is why a two-word city
     * reaches the record as one word.
     *
     * <p>Called with a single space, the delimiter the two assembling transfers use. The delimiter of two
     * adjacent spaces that the HTML composers use is applied by those composers rather than here, so the
     * two behaviours cannot be confused for one another.
     *
     * @param value     the sending operand
     * @param delimiter the delimiter at which the transfer stops
     * @return the transferred characters
     */
    private static String transferUpTo(final String value, final String delimiter) {
        final StringBuilder transferred = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            if (value.startsWith(delimiter, index)) {
                break;
            }
            transferred.append(value.charAt(index));
        }
        return transferred.toString();
    }

    /**
     * The payload field carried into TRNX-RECORD - the transaction record the payload carries.
     *
     * <p>The image is the card-first projected COSTM01 transaction-work record materialised by the
     * statement job's preceding steps. The mapper named here owns both that projected layout and the
     * canonical transaction layout, including the faithful two-byte processing-timestamp truncation, so
     * no offset appears in this service.
     *
     * @param payload the payload field the call left behind
     * @return the record the payload's leading bytes describe
     */
    private static Transaction transactionRecordFromPayload(final String payload) {
        return StatementWorkRecordMapper.fromRecord(
                payload.substring(PAYLOAD_RECORD_START,
                        PAYLOAD_RECORD_START + StatementWorkRecordMapper.RECORD_LENGTH));
    }

    /**
     * The payload field carried into CARD-XREF-RECORD - the cross-reference record the payload carries.
     *
     * @param payload the payload field the call left behind
     * @return the record the payload's leading bytes describe
     */
    private static CardCrossReference crossReferenceRecordFromPayload(final String payload) {
        return CardXrefRecordMapper.fromRecord(payload.getBytes(StandardCharsets.US_ASCII),
                PAYLOAD_RECORD_START, CardXrefRecordMapper.RECORD_LENGTH);
    }

    /**
     * The payload field carried into CUSTOMER-RECORD - the customer record the payload carries.
     *
     * <p>The caller's sealing operation is applied to the two regulated identifiers and to nothing else,
     * because the entity holds those two only inside the module's protected-value envelope while the
     * record image holds them in the clear. The statement itself renders neither of them.
     *
     * @param context the run's working storage, supplying the sealing operation
     * @param payload the payload field the call left behind
     * @return the record the payload's leading bytes describe
     */
    private static Customer customerRecordFromPayload(final StatementRunContext context,
                                                      final String payload) {
        return CustomerRecordMapper.fromRecord(payload.getBytes(StandardCharsets.US_ASCII),
                PAYLOAD_RECORD_START, context.regulatedFieldSealer);
    }

    /**
     * The payload field carried into ACCOUNT-RECORD - the account record the payload carries.
     *
     * @param payload the payload field the call left behind
     * @return the record the payload's leading bytes describe
     */
    private static Account accountRecordFromPayload(final String payload) {
        return AccountRecordMapper.fromRecord(payload.getBytes(StandardCharsets.US_ASCII),
                PAYLOAD_RECORD_START);
    }

    // ================================================================================================
    // The state machine's alphabet - WS-FL-DD [app/cbl/CBSTM03A.CBL:L67] and the six dispatcher clauses
    // ================================================================================================

    /**
     * The values {@code WS-FL-DD} takes, one constant per dispatcher clause at
     * {@code [app/cbl/CBSTM03A.CBL:L298-L314]}, declared in exact source order.
     *
     * <p>The first four constants are named after the DD names the legacy moves into the field, the
     * fifth after the phase selector it moves at {@code L760}, so each constant's own name
     * <em>is</em> the eight-character value the legacy field holds. That is what makes the observed
     * dispatch sequence a faithful trace of {@code WS-FL-DD} rather than a Java-side invention.
     *
     * <p>Nested and private on purpose. A state machine's alphabet is an implementation detail of the
     * machine, and publishing it would invite a caller to drive the phases itself - which is exactly the
     * nested-call refactor that cannot reproduce dispatcher re-entry.
     */
    private enum StatementPhase {

        /**
         * The transaction-file phase, the initial state taken from the field's own initial value at
         * {@code [app/cbl/CBSTM03A.CBL:L67]}. First dispatcher clause.
         */
        TRNXFILE,

        /** The cross-reference-file phase. Second dispatcher clause. */
        XREFFILE,

        /** The customer-file phase. Third dispatcher clause. */
        CUSTFILE,

        /** The account-file phase. Fourth dispatcher clause. */
        ACCTFILE,

        /**
         * The transaction-read phase. Fifth dispatcher clause, and the only one that does not transfer
         * to the file-open indirection.
         */
        READTRNX,

        /**
         * The catch-all clause, which transfers to the program exit and so ends the run.
         *
         * <p>Not a value the legacy ever moves into {@code WS-FL-DD}: the source's clause matches any
         * <em>other</em> value, and in a normal run it is never taken because the account-file phase
         * transfers to the mainline and the mainline falls through into the program exit. This constant
         * therefore stands for two things the legacy treats as one destination - that fall-through and
         * that clause - so the program exit is reached exactly once however the run finishes.
         */
        TERMINATED
    }

    // ================================================================================================
    // What one run produces
    // ================================================================================================

    /**
     * What one statement run yields once its records have been emitted: seven tallies and nothing else.
     *
     * <p><strong>Deliberately carries no record content.</strong> Every record of both streams, every
     * per-line summary and every dispatcher entry left the run through
     * {@link StatementOutputSink} at the moment it was produced. Returning them here as well would put
     * the whole output of an unbounded input on the heap and then copy it a second time, which is
     * precisely what the sink exists to avoid. A caller that needs the content observes it at the sink;
     * a caller that needs only to know what happened reads it here.
     *
     * <p>Seven primitive components, so the result of a run is the same size whether the run emitted one
     * record or a million. That property is the point of this type and is asserted rather than assumed.
     *
     * @param statementRecordsEmitted     how many plain statement records reached the sink, each exactly
     *                                    80 US-ASCII bytes as {@code [app/cbl/CBSTM03A.CBL:L45]}
     *                                    declares
     * @param htmlRecordsEmitted          how many markup statement records reached the sink, each exactly
     *                                    100 US-ASCII bytes as {@code [app/cbl/CBSTM03A.CBL:L47]}
     *                                    declares
     * @param transactionSummariesEmitted how many per-line transaction summaries reached the sink, one
     *                                    per emitted transaction line
     * @param dispatcherEntries           how many times the dispatcher was entered, including the entry
     *                                    for the clause that ends the run. Reported because dispatcher
     *                                    re-entry is the behaviour a reader most needs to be able to
     *                                    confirm; the sequence itself is observed at the sink
     * @param cardsTabulated              {@code CR-CNT} as the read phase left it: the number of distinct
     *                                    card numbers the transaction file presented
     * @param transactionsTabulated       the total of the per-card counters: the number of transaction
     *                                    records tabulated across every card
     * @param statementsWritten           how many statements the mainline produced, one per
     *                                    cross-reference record it consumed
     */
    public record StatementRun(int statementRecordsEmitted,
                               int htmlRecordsEmitted,
                               int transactionSummariesEmitted,
                               int dispatcherEntries,
                               int cardsTabulated,
                               int transactionsTabulated,
                               int statementsWritten) {
    }

    // ================================================================================================
    // WS-TRNX-TABLE [app/cbl/CBSTM03A.CBL:L225-L230] and WS-TRN-TBL-CNTR [L231-L233]
    // ================================================================================================

    /**
     * The card table of {@code [app/cbl/CBSTM03A.CBL:L225-L230]}: {@value #MAX_CARD_ENTRIES} entries,
     * each a sixteen-character card number and a nested table of {@value #MAX_TRANSACTIONS_PER_CARD}
     * transactions.
     *
     * <p>Each nested entry holds a sixteen-character transaction key and a 318-character remainder. Here
     * one entry is the tabulated transaction record itself, which carries exactly those two parts: the
     * key member is its transaction identifier and the remainder is every other field. All three widths
     * belong to the record mapper that owns the layout, so none is restated here.
     *
     * <p>Fixed length rather than growable, because the legacy bound is real: a run presenting more than
     * {@value #MAX_CARD_ENTRIES} distinct card numbers, or more than
     * {@value #MAX_TRANSACTIONS_PER_CARD} transactions on one card, overruns its subscript on the
     * mainframe. Here the overrun is detected and reported through the same abend path rather than
     * corrupting adjacent storage, which is the bounded-collection guard that replaces an unchecked
     * COBOL subscript.
     *
     * <p>Subscripts are one-based throughout, exactly as the source writes them, and are converted to
     * array indices in one place so the arithmetic cannot drift between call sites.
     */
    private static final class CardTransactionTable {

        /** {@code WS-CARD-NUM}, one sixteen-character card number per card entry. */
        private final String[] wsCardNum = new String[MAX_CARD_ENTRIES];

        /** {@code WS-TRAN-TBL}, the tabulated transactions of each card entry. */
        private final Transaction[][] wsTranTbl =
                new Transaction[MAX_CARD_ENTRIES][MAX_TRANSACTIONS_PER_CARD];

        /**
         * Reports whether a one-based card subscript lies inside the table.
         *
         * @param cardSubscript the one-based card subscript
         * @return {@code true} when the subscript addresses a real entry
         */
        private boolean holdsCard(final int cardSubscript) {
            return cardSubscript >= 1 && cardSubscript <= MAX_CARD_ENTRIES;
        }

        /**
         * Reports whether a one-based transaction subscript lies inside a card's nested table.
         *
         * @param transactionSubscript the one-based transaction subscript
         * @return {@code true} when the subscript addresses a real nested entry
         */
        private boolean holdsTransaction(final int transactionSubscript) {
            return transactionSubscript >= 1 && transactionSubscript <= MAX_TRANSACTIONS_PER_CARD;
        }

        /**
         * {@code MOVE ... TO WS-CARD-NUM (n)}.
         *
         * @param cardSubscript the one-based card subscript
         * @param cardNumber    the sixteen-character card number
         */
        private void putCardNumber(final int cardSubscript, final String cardNumber) {
            this.wsCardNum[cardSubscript - 1] = cardNumber;
        }

        /**
         * Reads {@code WS-CARD-NUM (n)}.
         *
         * @param cardSubscript the one-based card subscript
         * @return the stored card number, or {@code null} for an entry the table initialisation left
         *         blank and no read has filled
         */
        private String cardNumber(final int cardSubscript) {
            return this.wsCardNum[cardSubscript - 1];
        }

        /**
         * {@code MOVE ... TO WS-TRAN-NUM (c, t)} and {@code WS-TRAN-REST (c, t)} together, since one
         * tabulated record carries both the key and the remainder.
         *
         * @param cardSubscript        the one-based card subscript
         * @param transactionSubscript the one-based transaction subscript
         * @param transaction          the tabulated transaction record
         */
        private void putTransaction(final int cardSubscript, final int transactionSubscript,
                                    final Transaction transaction) {
            this.wsTranTbl[cardSubscript - 1][transactionSubscript - 1] = transaction;
        }

        /**
         * Reads the tabulated record addressed by a pair of one-based subscripts.
         *
         * @param cardSubscript        the one-based card subscript
         * @param transactionSubscript the one-based transaction subscript
         * @return the tabulated transaction record
         */
        private Transaction transaction(final int cardSubscript, final int transactionSubscript) {
            return this.wsTranTbl[cardSubscript - 1][transactionSubscript - 1];
        }
    }

    /**
     * The transaction-count group of {@code [app/cbl/CBSTM03A.CBL:L231-L233]}.
     *
     * <p>The source declares a group and, inside it, a table of per-card counters whose name differs
     * from the group's by two characters. <strong>Both names are real and neither is a typo</strong>, so
     * the group is modelled as this class and the table it contains as this class's own member - keeping
     * the two distinct instead of collapsing them into a bare array.
     */
    private static final class TransactionCountTable {

        /** {@code WS-TRCT}, the per-card transaction counter, one per card entry. */
        private final int[] wsTrct = new int[MAX_CARD_ENTRIES];

        /**
         * The transaction count carried into the nth WS-TRCT slot at {@code [app/cbl/CBSTM03A.CBL:L822]}
         * when a card break is detected and at {@code L850} when the read phase ends.
         *
         * @param cardSubscript the one-based card subscript
         * @param count         the running transaction count to store against that card
         */
        private void putCount(final int cardSubscript, final int count) {
            this.wsTrct[cardSubscript - 1] = count;
        }

        /**
         * Reads {@code WS-TRCT (n)}, the loop bound of the inner walk at
         * {@code [app/cbl/CBSTM03A.CBL:L423]}.
         *
         * @param cardSubscript the one-based card subscript
         * @return the transaction count stored against that card
         */
        private int count(final int cardSubscript) {
            return this.wsTrct[cardSubscript - 1];
        }

        /**
         * Totals the counters across the cards a run tabulated.
         *
         * @param cardCount the number of card entries the read phase filled
         * @return the total number of tabulated transactions
         */
        private int total(final int cardCount) {
            int total = 0;
            for (int cardSubscript = 1; cardSubscript <= cardCount; cardSubscript++) {
                total += count(cardSubscript);
            }
            return total;
        }
    }

    // ================================================================================================
    // The per-invocation working storage
    // ================================================================================================

    /**
     * One run's working storage: every mutable item the legacy declares between
     * {@code [app/cbl/CBSTM03A.CBL:L59]} and {@code L223}, created fresh per invocation.
     *
     * <p>This exists so that the service itself holds nothing mutable. A statement run carries a file
     * position, a card table, four counters, a running total and two accumulating output streams; every
     * one of them would corrupt a concurrent or a successive run if it lived on the singleton. The one
     * item deliberately absent is {@code WS-FL-DD}, which is the driving method's own local so that the
     * state variable cannot outlive the loop that drives it.
     *
     * <p>The MVS control-block items of {@code [app/cbl/CBSTM03A.CBL:L235-L260]} are absent because they
     * are not migrated at all; see this class's documentation.
     */
    private static final class StatementRunContext {

        // --- COMP-VARIABLES [app/cbl/CBSTM03A.CBL:L59-L63] ------------------------------------------

        /** {@code CR-CNT}: how many distinct card numbers the read phase has tabulated. */
        private int crCnt;

        /** {@code TR-CNT}: the running transaction count of the card currently being tabulated. */
        private int trCnt;

        /** {@code CR-JMP}: the card subscript of the walk at {@code [app/cbl/CBSTM03A.CBL:L417]}. */
        private int crJmp;

        /** {@code TR-JMP}: the transaction subscript of the inner walk at {@code [app/cbl/CBSTM03A.CBL:L422]}. */
        private int trJmp;

        // --- COMP3-VARIABLES [app/cbl/CBSTM03A.CBL:L64-L65] ----------------------------------------

        /**
         * {@code WS-TOTAL-AMT}: the statement's accumulated expenditure, a two-decimal field. Held as an
         * exact decimal at the monetary scale, never as a binary floating-point value.
         */
        private BigDecimal wsTotalAmt = ZonedDecimalCodec.toMonetaryScale(BigDecimal.ZERO);

        // --- MISC-VARIABLES [app/cbl/CBSTM03A.CBL:L66-L70], less WS-FL-DD --------------------------

        /** {@code WS-TRN-AMT}: the receiving field of the total's move at {@code [app/cbl/CBSTM03A.CBL:L433]}. */
        private BigDecimal wsTrnAmt = ZonedDecimalCodec.toMonetaryScale(BigDecimal.ZERO);

        /** {@code WS-SAVE-CARD}: the previous record's card number, which detects a card break. */
        private String wsSaveCard = "";

        /** {@code END-OF-FILE}: the mainline's loop control, initially not-yet-exhausted. */
        private String endOfFile = NOT_AT_END_OF_FILE;

        // --- WS-M03B-AREA [app/cbl/CBSTM03A.CBL:L71-L83], mutable components ----------------------

        /** {@code WS-M03B-RC}: the raw two-character status the last call left behind. */
        private String m03bRc = RETURN_CODE_ZEROED;

        /** {@code WS-M03B-FLDT}: the payload the last call left behind. */
        private String m03bFldt = BLANK_PAYLOAD;

        /**
         * The transaction file's sequential position, which the COBOL runtime would hold on the
         * program's behalf and which the collaborator externalises into its parameter object.
         *
         * <p>Two cursors are needed, not one, because the transaction and cross-reference files are both
         * read sequentially and the legacy holds a position for each.
         */
        private int trnxFilePosition = FIRST_RECORD_POSITION;

        /** The cross-reference file's sequential position; see {@link #trnxFilePosition}. */
        private int xrefFilePosition = FIRST_RECORD_POSITION;

        // --- The record areas the payload moves land in --------------------------------------------

        /** {@code TRNX-RECORD} of the statement work area, the record the read phase is tabulating. */
        private Transaction trnxRecord;

        /**
         * {@code CARD-XREF-RECORD}, as the unconditional move at {@code [app/cbl/CBSTM03A.CBL:L364]}
         * leaves it - the raw image, held whether or not the read succeeded.
         */
        private String cardXrefRecordImage = BLANK_PAYLOAD;

        /**
         * The parsed view of {@code CARD-XREF-RECORD}, materialised only on the arm where the mainline
         * goes on to read its fields.
         *
         * <p>The legacy move runs even at end of file, where it lands a blank group that the mainline's
         * own guard then discards unread. A blank group has no parsed counterpart and needs none, so the
         * image above records the move and this field records what the guard permits to be read.
         */
        private CardCrossReference cardXrefRecord;

        /** {@code CUSTOMER-RECORD}, the 500-byte variant this member includes at {@code [app/cbl/CBSTM03A.CBL:L55]}. */
        private Customer customerRecord;

        /** {@code ACCOUNT-RECORD}, included at {@code [app/cbl/CBSTM03A.CBL:L57]}. */
        private Account accountRecord;

        // --- The tables ---------------------------------------------------------------------------

        /** {@code WS-TRNX-TABLE} at {@code [app/cbl/CBSTM03A.CBL:L225]}. */
        private final CardTransactionTable wsTrnxTable = new CardTransactionTable();

        /** {@code WS-TRN-TBL-CNTR} at {@code [app/cbl/CBSTM03A.CBL:L231]}. */
        private final TransactionCountTable wsTrnTblCntr = new TransactionCountTable();

        // --- STATEMENT-LINES named items [app/cbl/CBSTM03A.CBL:L85-L146] --------------------------
        // Only the named items appear here. The literal FILLER members carry the fixed text and live in
        // the templates classes, which is also why the statement-lines reset below leaves them alone:
        // a COBOL INITIALIZE ignores FILLER items and resets only the named ones.

        /** {@code ST-NAME}, the assembled customer name in its 75-byte field. */
        private String stName = "";

        /** {@code ST-ADD1}, the first address line in its 50-byte field. */
        private String stAdd1 = "";

        /** {@code ST-ADD2}, the second address line in its 50-byte field. */
        private String stAdd2 = "";

        /** {@code ST-ADD3}, the assembled third address line in its 80-byte field. */
        private String stAdd3 = "";

        /** {@code ST-ACCT-ID}, the account identifier in its 20-byte alphanumeric field. */
        private String stAcctId = "";

        /** {@code ST-CURR-BAL}, the balance behind the unsuppressed thirteen-byte mask. */
        private BigDecimal stCurrBal = ZonedDecimalCodec.toMonetaryScale(BigDecimal.ZERO);

        /** {@code ST-FICO-SCORE}, the credit score in its 20-byte alphanumeric field. */
        private String stFicoScore = "";

        /** {@code ST-TRANID}, the current transaction's identifier in its 16-byte field. */
        private String stTranId = "";

        /** {@code ST-TRANDT}, the current transaction's description in its 49-byte field. */
        private String stTranDt = "";

        /** {@code ST-TRANAMT}, the current transaction's amount behind the zero-suppressed mask. */
        private BigDecimal stTranAmt = ZonedDecimalCodec.toMonetaryScale(BigDecimal.ZERO);

        /** {@code ST-TOTAL-TRAMT}, the statement total behind the zero-suppressed mask. */
        private BigDecimal stTotalTrAmt = ZonedDecimalCodec.toMonetaryScale(BigDecimal.ZERO);

        // --- The run's output ----------------------------------------------------------------------

        /**
         * Where the run's output goes, one item at a time, as it is produced.
         *
         * <p>The run holds a destination and not a collection. That is the whole of why the working set
         * is one record: an emitted record is unreachable from here the instant the sink has taken it.
         */
        private final StatementOutputSink outputSink;

        /** How many plain statement records this run has emitted. */
        private int statementRecordsEmitted;

        /** How many markup statement records this run has emitted. */
        private int htmlRecordsEmitted;

        /** How many per-line transaction summaries this run has emitted. */
        private int transactionSummariesEmitted;

        /** How many times the dispatcher has been entered, including the entry that ends the run. */
        private int dispatcherEntries;

        /** How many statements the mainline has produced. */
        private int statementsWritten;

        // --- The caller's protected-value policy ---------------------------------------------------

        /**
         * Recovers the cleartext of the two regulated customer identifiers, so that the collaborator can
         * compose a 500-byte customer image at all.
         */
        private final UnaryOperator<String> regulatedFieldRevealer;

        /** Seals those two identifiers again when the composed image is read back into an entity. */
        private final UnaryOperator<String> regulatedFieldSealer;

        /** Frozen projected transaction input supplied by the statement job for this invocation. */
        private final StatementTransactionSource transactionSource;

        /**
         * This run's sequential walk of the cross-reference cluster, one bounded page at a time.
         *
         * <p>Held here and nowhere else. The file-handling collaborator is a stateless singleton and a
         * file position is state, so the position lives in the run that owns it, exactly as
         * {@link #trnxFilePosition} and {@link #xrefFilePosition} do.
         */
        private final StatementCrossReferenceSource crossReferenceSource;

        /**
         * Creates one run's storage.
         *
         * @param transactionSource      the frozen transaction-work snapshot; must not be {@code null}
         * @param crossReferenceSource   this run's sequential cross-reference walk; must not be
         *                               {@code null}
         * @param regulatedFieldRevealer the caller's revealing operation; must not be {@code null}
         * @param regulatedFieldSealer   the caller's sealing operation; must not be {@code null}
         * @param outputSink             where this run emits its output; must not be {@code null}
         */
        private StatementRunContext(final StatementTransactionSource transactionSource,
                                    final StatementCrossReferenceSource crossReferenceSource,
                                    final UnaryOperator<String> regulatedFieldRevealer,
                                    final UnaryOperator<String> regulatedFieldSealer,
                                    final StatementOutputSink outputSink) {
            this.transactionSource = transactionSource;
            this.crossReferenceSource = crossReferenceSource;
            this.regulatedFieldRevealer = regulatedFieldRevealer;
            this.regulatedFieldSealer = regulatedFieldSealer;
            this.outputSink = outputSink;
        }

        /**
         * The clearing of STATEMENT-LINES at {@code [app/cbl/CBSTM03A.CBL:L459]}.
         *
         * <p>Resets every named item of the statement-line group to spaces or zero and leaves the literal
         * FILLER members untouched, which is precisely what a COBOL {@code INITIALIZE} does: it ignores
         * FILLER items. Here the fixed text lives in the templates classes and so is untouched by
         * construction, and this method resets the eleven substituted values.
         *
         * <p>The reset matters to byte parity beyond tidiness: the two assembling character transfers
         * that follow leave the tail of their receiving field alone, so the field has to arrive blank for
         * the untouched tail to be spaces.
         */
        private void initializeStatementLines() {
            this.stName = "";
            this.stAdd1 = "";
            this.stAdd2 = "";
            this.stAdd3 = "";
            this.stAcctId = "";
            this.stCurrBal = ZonedDecimalCodec.toMonetaryScale(BigDecimal.ZERO);
            this.stFicoScore = "";
            this.stTranId = "";
            this.stTranDt = "";
            this.stTranAmt = ZonedDecimalCodec.toMonetaryScale(BigDecimal.ZERO);
            this.stTotalTrAmt = ZonedDecimalCodec.toMonetaryScale(BigDecimal.ZERO);
        }

        /**
         * {@code WRITE FD-STMTFILE-REC} - hands one 80-byte plain statement record to the sink.
         *
         * <p>The record is not retained here. A {@code WRITE} on the mainframe hands a record to an open
         * dataset and the program's storage keeps nothing; the same is true of this method, and the
         * counter it bumps is the only trace the run keeps of it.
         *
         * @param record the complete record, already at its declared width by the builder that composed
         *               it
         */
        private void writeStatementRecord(final String record) {
            this.outputSink.statementRecord(record);
            this.statementRecordsEmitted++;
        }

        /**
         * {@code WRITE FD-HTMLFILE-REC} - hands one 100-byte HTML statement record to the sink.
         *
         * @param record the complete record, already at its declared width by the builder that composed
         *               it
         */
        private void writeHtmlRecord(final String record) {
            this.outputSink.htmlRecord(record);
            this.htmlRecordsEmitted++;
        }

        /**
         * Hands the sink one summary of the transaction line just emitted.
         *
         * @param summary the summary of the line just emitted
         */
        private void emitTransactionSummary(final StatementLineSummary summary) {
            this.outputSink.transactionSummary(summary);
            this.transactionSummariesEmitted++;
        }

        /**
         * Reports that the dispatcher was entered with a given phase, which is what makes re-entry after
         * a state change observable.
         *
         * @param phase the phase the dispatcher is about to branch on
         */
        private void recordDispatch(final StatementPhase phase) {
            this.outputSink.dispatchedPhase(phase.name());
            this.dispatcherEntries++;
        }
    }
}
