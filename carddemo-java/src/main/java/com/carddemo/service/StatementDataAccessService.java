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

import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.util.AccountRecordMapper;
import com.carddemo.util.CardXrefRecordMapper;
import com.carddemo.util.CustomerRecordMapper;
import com.carddemo.util.FixedWidthFieldReader;
import com.carddemo.util.StatementWorkRecordMapper;

/**
 * The statement feature's file-handling subprogram, translated one paragraph at a time from
 * {@code [app/cbl/CBSTM03B.CBL]} - 230 lines, 14 paragraphs, four files, six declared operations.
 *
 * <p>Legacy provenance: AWS CardDemo z/OS estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The authority member carries the estate's rare
 * uppercase {@code .CBL} extension and CRLF line endings; it and {@code [app/cbl/CBSTM03A.CBL]} are the
 * only two such members and together they are the whole statement-generation feature. No COBOL text is
 * transcribed anywhere in this module - member names, paragraph names, line numbers, DD names, field
 * names, widths and raw status codes are cited, and nothing else.
 *
 * <p><strong>This class replaces 13 static call sites.</strong> Every one of them is a
 * {@code CALL 'CBSTM03B' USING LK-M03B-AREA} inside {@code [app/cbl/CBSTM03A.CBL]}, at L351, L377, L401,
 * L734, L746, L769, L787, L805, L835, L860, L877, L893 and L909. All 13 collapse into calls to
     * {@link #execute(StatementFileRequest, StatementTransactionSource)} from the statement generator,
     * which is the sole consumer. This class injects <strong>no other service</strong>: the three
     * persistent reference repositories only. The transaction file arrives as the frozen source
     * materialised by the statement job, never as a second query of the live transaction master.
 *
 * <h2>The parameter object, and why the caller owns the state</h2>
 *
 * <p>The legacy entry point is {@code PROCEDURE DIVISION USING LK-M03B-AREA} at
 * {@code [app/cbl/CBSTM03B.CBL:L114]} - one group item, mutated in place, declared at L100-L112. Here it
 * becomes the immutable pair {@link StatementFileRequest} and {@link StatementFileResponse}, so this
 * service holds no mutable state of any kind and is safe to share as a singleton.
 *
 * <p>The caller blanks the payload field before every read call - observed at
 * {@code [app/cbl/CBSTM03A.CBL:L350, L376, L400, L745, L834]}. That remains the caller's obligation and
 * is <em>not</em> performed here, stated so the two translations agree. Note also that the caller resets
 * the return code before most calls but <em>not</em> before its two transaction-file reads
 * ({@code [app/cbl/CBSTM03A.CBL:L744-L746, L832-L835]}), which is why the value carried on entry is live
 * and must survive a fall-through untouched.
 *
 * <h2>The return code is raw, and stays raw</h2>
 *
 * <p>{@code LK-M03B-RC} is {@code PIC X(02)} and this service publishes the file's own two-character
 * status into it verbatim. Nothing here normalises it into a coarser outcome, throws on it, or maps it to
 * a tri-state: the caller performs the interpretation, exactly as
 * {@code [app/cbl/CBSTM03A.CBL:L353-L361]} does when it branches on {@code '00'}, on {@code '10'} and on
 * everything else. The codes this service publishes are all sourced from {@code FileStatus} so the
 * vocabulary has a single definition, and {@link StatementFileResponse#status()} offers the typed view
 * without ever replacing the raw one.
 *
 * <h2>Access mode is asymmetric, and the asymmetry is the contract</h2>
 *
 * <p>Four {@code SELECT} statements, two access modes
 * ({@code [app/cbl/CBSTM03B.CBL:L31-L53]}). The transaction and cross-reference files are
 * {@code ACCESS MODE SEQUENTIAL} and accept open, sequential read and close only. The customer and
 * account files are {@code ACCESS MODE RANDOM} and accept open, keyed read and close only. Neither pair
 * offers the other's read: there is no keyed read on a sequential file and no sequential scan on a random
 * one, because the legacy declares none.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>It raises no abend and throws nothing on a bad status; it composes no statement text and no HTML;
 * it runs no state machine; it performs no decimal arithmetic; it issues no native query; and it performs
 * no fixed-width slicing of its own - record layout knowledge lives in {@code com.carddemo.util}, and
 * every width, justification and truncation semantic below is delegated there. The MVS control-block
 * addressing constructs of the statement generator are not migrated anywhere in this module and have no
 * counterpart in {@code [app/cbl/CBSTM03B.CBL]} to begin with.
 */
@Service
public final class StatementDataAccessService {

    private static final Logger LOG = LoggerFactory.getLogger(StatementDataAccessService.class);

    /** Declared width of {@code LK-M03B-DD}, {@code PIC X(08)} at {@code [app/cbl/CBSTM03B.CBL:L101]}. */
    public static final int DD_NAME_WIDTH = 8;

    /**
     * Declared width of {@code LK-M03B-OPER}, {@code PIC X(01)} at
     * {@code [app/cbl/CBSTM03B.CBL:L102]}.
     */
    public static final int OPERATION_WIDTH = 1;

    /** Declared width of {@code LK-M03B-RC}, {@code PIC X(02)} at {@code [app/cbl/CBSTM03B.CBL:L109]}. */
    public static final int RETURN_CODE_WIDTH = 2;

    /** Declared width of {@code LK-M03B-KEY}, {@code PIC X(25)} at {@code [app/cbl/CBSTM03B.CBL:L110]}. */
    public static final int KEY_WIDTH = 25;

    /**
     * Declared width of {@code LK-M03B-FLDT}, {@code PIC X(1000)} at
     * {@code [app/cbl/CBSTM03B.CBL:L112]}. Every record image this service returns is placed
     * left-justified in a field of exactly this width and space-padded, which is what a COBOL
     * {@code MOVE} into the field does and therefore what the caller's own receiving {@code MOVE}
     * expects.
     */
    public static final int PAYLOAD_WIDTH = 1000;

    /**
     * DD name selecting the transaction file, matched at {@code [app/cbl/CBSTM03B.CBL:L119]}. Exactly
     * {@link #DD_NAME_WIDTH} characters, so it compares equal to a normalised request field without
     * padding.
     */
    public static final String DD_TRNXFILE = "TRNXFILE";

    /** DD name selecting the card cross-reference file, matched at {@code [app/cbl/CBSTM03B.CBL:L121]}. */
    public static final String DD_XREFFILE = "XREFFILE";

    /** DD name selecting the customer file, matched at {@code [app/cbl/CBSTM03B.CBL:L123]}. */
    public static final String DD_CUSTFILE = "CUSTFILE";

    /** DD name selecting the account file, matched at {@code [app/cbl/CBSTM03B.CBL:L125]}. */
    public static final String DD_ACCTFILE = "ACCTFILE";

    /**
     * Operation code opening a file for input, condition name {@code M03B-OPEN} at
     * {@code [app/cbl/CBSTM03B.CBL:L103]}. Tested first by all four handlers.
     */
    public static final String OPERATION_OPEN = "O";

    /**
     * Operation code closing a file, condition name {@code M03B-CLOSE} at
     * {@code [app/cbl/CBSTM03B.CBL:L104]}. Tested third and last by all four handlers.
     */
    public static final String OPERATION_CLOSE = "C";

    /**
     * Operation code reading the next record of a sequential file, condition name {@code M03B-READ} at
     * {@code [app/cbl/CBSTM03B.CBL:L105]}. Tested by the transaction and cross-reference handlers only.
     */
    public static final String OPERATION_READ = "R";

    /**
     * Operation code reading one record of a random file by key, condition name {@code M03B-READ-K} at
     * {@code [app/cbl/CBSTM03B.CBL:L106]}. Tested by the customer and account handlers only.
     */
    public static final String OPERATION_READ_KEYED = "K";

    /**
     * Operation code declared as condition name {@code M03B-WRITE} at
     * {@code [app/cbl/CBSTM03B.CBL:L107]} and <strong>tested by no handler in the legacy program</strong>.
     *
     * <p>It is part of the parameter contract and is therefore declared, but it carries no behaviour
     * whatsoever and no write path exists. A request naming it takes the fall-through of
     * {@link #execute(StatementFileRequest, StatementTransactionSource)} described on that method,
     * exactly as any other operation the handlers do not test would. Inventing a write path here would
     * be feature expansion; see the decision log.
     */
    public static final String OPERATION_WRITE = "W";

    /**
     * Operation code declared as condition name {@code M03B-REWRITE} at
     * {@code [app/cbl/CBSTM03B.CBL:L108]} and <strong>tested by no handler in the legacy
     * program</strong>. Dead for exactly the same reason as {@link #OPERATION_WRITE}, and treated
     * identically.
     */
    public static final String OPERATION_REWRITE = "Z";

    /**
     * Declared width of {@code FD-CUST-ID}, {@code PIC X(09)} at {@code [app/cbl/CBSTM03B.CBL:L72]} -
     * the receiving field of the customer keyed read's {@code MOVE} at
     * {@code [app/cbl/CBSTM03B.CBL:L189]}. Alphanumeric, so the sliced key is left-justified and
     * space-padded into it.
     */
    private static final int FD_CUST_ID_WIDTH = 9;

    /**
     * Declared width of {@code FD-ACCT-ID}, {@code PIC 9(11)} at {@code [app/cbl/CBSTM03B.CBL:L77]} -
     * the receiving field of the account keyed read's {@code MOVE} at
     * {@code [app/cbl/CBSTM03B.CBL:L214]}. <strong>Numeric, not alphanumeric</strong>, so the sliced key
     * is right-justified and zero-padded into it; see {@link #accountFileProc(StatementFileRequest)}.
     *
     * <p><strong>Source anomaly, not reproduced.</strong> The name {@code FD-ACCT-DATA} is declared twice
     * in the same program with two different widths: 318 bytes at {@code [app/cbl/CBSTM03B.CBL:L63]},
     * where it is really the remainder of the <em>transaction</em> file's record and has evidently been
     * misnamed by copy and paste, and 289 bytes at {@code [app/cbl/CBSTM03B.CBL:L78]}, where it is the
     * account file's own remainder. Only the second is consistent with the 300-byte account record, since
     * 11 + 289 = 300, and that is the form this class relies on - the key width declared here plus the
     * remainder the account record mapper owns. The inconsistency is recorded as a decision-log entry and
     * is deliberately not carried into the Java layout.
     */
    private static final int FD_ACCT_ID_WIDTH = 11;

    /**
     * Ordinal position of the first record of a sequential file, the position an open establishes.
     * Mirrors {@code OPEN INPUT} leaving the file positioned at its first record.
     */
    private static final int FIRST_RECORD_POSITION = 0;

    /** One record per page request, so a page index is an ordinal record position. */
    private static final int SINGLE_RECORD_PAGE_SIZE = 1;

    /** Diagnostic layout name for the DD-name field of the parameter object. */
    private static final String FIELD_DD = "LK-M03B-DD";

    /** Diagnostic layout name for the operation field of the parameter object. */
    private static final String FIELD_OPER = "LK-M03B-OPER";

    /** Diagnostic layout name for the return-code field of the parameter object. */
    private static final String FIELD_RC = "LK-M03B-RC";

    /** Diagnostic layout name for the key field of the parameter object. */
    private static final String FIELD_KEY = "LK-M03B-KEY";

    /** Diagnostic layout name for the payload field of the parameter object. */
    private static final String FIELD_FLDT = "LK-M03B-FLDT";

    /** Diagnostic layout name for the customer file's record key. */
    private static final String FIELD_FD_CUST_ID = "FD-CUST-ID";

    /** Diagnostic layout name for the account file's record key. */
    private static final String FIELD_FD_ACCT_ID = "FD-ACCT-ID";

    /**
     * Key order of the cross-reference file, whose {@code RECORD KEY IS FD-XREF-CARD-NUM} at
     * {@code [app/cbl/CBSTM03B.CBL:L40]} is the card number alone.
     */
    private static final Sort CROSS_REFERENCE_KEY_ORDER = Sort.by(Sort.Direction.ASC, "xrefCardNum");

    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    private final CustomerRepository customerRepository;

    private final AccountRepository accountRepository;

    /**
     * Binds the three persistent repositories behind the reference-file reads.
     *
     * @param cardCrossReferenceRepository stands in for the cross-reference file; must not be
     *                                     {@code null}
     * @param customerRepository           stands in for the customer file; must not be {@code null}
     * @param accountRepository            stands in for the account file; must not be {@code null}
     * @throws NullPointerException if any repository is {@code null}
     */
    public StatementDataAccessService(
                                      final CardCrossReferenceRepository cardCrossReferenceRepository,
                                      final CustomerRepository customerRepository,
                                      final AccountRepository accountRepository) {
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.customerRepository =
                Objects.requireNonNull(customerRepository, "customerRepository must not be null");
        this.accountRepository =
                Objects.requireNonNull(accountRepository, "accountRepository must not be null");
    }

    /**
     * The request half of the parameter object, a faithful port of {@code 01 LK-M03B-AREA} declared at
     * {@code [app/cbl/CBSTM03B.CBL:L100-L112]}.
     *
     * <p>Each character component is normalised to its declared picture width by the canonical
     * constructor, because the legacy fields <em>are</em> fixed width and a comparison against a DD-name
     * literal only behaves like the source's {@code EVALUATE} if the field being compared is eight
     * characters wide. Normalisation is left-justified with space padding and right truncation, which is
     * what a COBOL {@code MOVE} into an alphanumeric field does, and it is delegated to
     * {@code com.carddemo.util} rather than performed here.
     *
     * <p>Two components have no counterpart in the legacy group item and are documented deviations
     * rather than inventions:
     *
     * <ul>
     *   <li>{@code sequentialPosition} externalises the file position that the COBOL runtime holds on the
     *       program's behalf. It has to live somewhere, and the one place it may not live is a field of
     *       this service, which would leak a cursor between concurrent statement runs. Carrying it in the
     *       parameter object keeps the service stateless and makes the position explicit at every call
     *       site. It is ignored on every path except the sequential read.</li>
     *   <li>{@code regulatedFieldRevealer} recovers the cleartext that a customer record image requires
     *       from the protected value the customer table stores. The legacy file held those identifiers in
     *       the clear, so no such function was needed; this module protects them at rest, so rendering the
     *       image needs one. The decision to expose a regulated identifier in a payload belongs to the
     *       caller and not to this service - which is also why this service injects no encryption
     *       collaborator. It is consulted on the customer keyed read alone.</li>
     * </ul>
     *
     * @param ddName                 {@code LK-M03B-DD}, {@code PIC X(08)}: selects which file the
     *                               operation acts on; normalised to {@link #DD_NAME_WIDTH} characters
     * @param operation              {@code LK-M03B-OPER}, {@code PIC X(01)}: the operation code;
     *                               normalised to {@link #OPERATION_WIDTH} characters. Any character is
     *                               representable, including one no handler tests, which is precisely
     *                               what makes the source's fall-through expressible
     * @param returnCode             {@code LK-M03B-RC}, {@code PIC X(02)}: the status carried
     *                               <em>on entry</em>. This is the value a fall-through leaves untouched
     *                               and hands back, so it is genuinely an input as well as an output
     * @param key                    {@code LK-M03B-KEY}, {@code PIC X(25)}: the key value, normalised to
     *                               {@link #KEY_WIDTH} characters
     * @param keyLength              {@code LK-M03B-KEY-LN}, {@code PIC S9(4)}: the runtime key length the
     *                               keyed reads slice the key field down to. The caller controls the
     *                               effective key width and this service neither ignores it nor
     *                               substitutes trimming for it
     * @param payload                {@code LK-M03B-FLDT}, {@code PIC X(1000)}: the record payload,
     *                               normalised to {@link #PAYLOAD_WIDTH} characters. The caller blanks it
     *                               before every read call
     * @param sequentialPosition     zero-based ordinal position of the next record a sequential read will
     *                               return; must not be negative
     * @param regulatedFieldRevealer recovers cleartext for the two regulated customer identifiers; never
     *                               {@code null} once constructed, defaulting to the identity function
     * @throws IllegalArgumentException if {@code sequentialPosition} is negative, or if any character
     *                                  component carries a character US-ASCII cannot represent
     */
    public record StatementFileRequest(String ddName,
                                       String operation,
                                       String returnCode,
                                       String key,
                                       int keyLength,
                                       String payload,
                                       int sequentialPosition,
                                       UnaryOperator<String> regulatedFieldRevealer) {

        public StatementFileRequest {
            if (sequentialPosition < 0) {
                throw new IllegalArgumentException(
                        "sequentialPosition must not be negative: " + sequentialPosition);
            }
            ddName = toAlphanumericField(FIELD_DD, ddName, DD_NAME_WIDTH);
            operation = toAlphanumericField(FIELD_OPER, operation, OPERATION_WIDTH);
            returnCode = toAlphanumericField(FIELD_RC, returnCode, RETURN_CODE_WIDTH);
            key = toAlphanumericField(FIELD_KEY, key, KEY_WIDTH);
            payload = toAlphanumericField(FIELD_FLDT, payload, PAYLOAD_WIDTH);
            regulatedFieldRevealer =
                    regulatedFieldRevealer == null ? UnaryOperator.identity() : regulatedFieldRevealer;
        }

        /**
         * Builds a request without a regulated-field revealer, for the three DD names that never need
         * one and for the customer file's open and close.
         *
         * <p>A customer keyed read built this way renders no usable image: the identity function hands
         * the stored protected value straight to the record mapper, which rejects it for exceeding its
         * field width. That is the intended outcome - a caller reading customer records states its reveal
         * policy explicitly through the eight-component form.
         *
         * @param ddName             see the eight-component form
         * @param operation          see the eight-component form
         * @param returnCode         see the eight-component form
         * @param key                see the eight-component form
         * @param keyLength          see the eight-component form
         * @param payload            see the eight-component form
         * @param sequentialPosition see the eight-component form
         */
        public StatementFileRequest(final String ddName,
                                    final String operation,
                                    final String returnCode,
                                    final String key,
                                    final int keyLength,
                                    final String payload,
                                    final int sequentialPosition) {
            this(ddName, operation, returnCode, key, keyLength, payload, sequentialPosition,
                    UnaryOperator.identity());
        }

        /**
         * Reports whether this request carries the given operation code, comparing the whole normalised
         * one-character field.
         *
         * <p>Named after the source's level-88 condition names, which is what the handlers test rather
         * than the raw byte, and used by each handler for its three consecutive checks.
         *
         * @param operationCode the operation code to compare against, one of the six declared constants
         * @return {@code true} when the request's operation field equals that code
         */
        public boolean hasOperation(final String operationCode) {
            return this.operation.equals(operationCode);
        }

        /**
         * Reports whether this request selects the given DD name, comparing the whole normalised
         * eight-character field.
         *
         * @param ddNameCandidate the DD name to compare against, one of the four declared constants
         * @return {@code true} when the request's DD-name field equals that name
         */
        public boolean hasDdName(final String ddNameCandidate) {
            return this.ddName.equals(ddNameCandidate);
        }
    }

    /**
     * The response half of the parameter object: the components of {@code 01 LK-M03B-AREA} that the legacy
     * subprogram leaves behind for its caller, returned immutably instead of mutated in place.
     *
     * <p>Like the request, each character component is held at its declared picture width, so a response is
     * self-consistent however it was built. "Un-normalised" below refers to the <em>status vocabulary</em>:
     * the two characters are whatever the file produced and are never mapped to a coarser outcome, mirrored
     * or reinterpreted. The width normalisation is the field's own {@code PIC X(02)} shape and nothing more.
     *
     * @param ddName             the DD-name field echoed back unchanged, so a caller correlating a
     *                           response with a request needs no separate bookkeeping. The legacy group
     *                           item likewise still holds it after the call returns
     * @param returnCode         {@code LK-M03B-RC}: the raw two-character file status, never interpreted
     *                           here. On a fall-through this is the request's own value, untouched
     * @param payload            {@code LK-M03B-FLDT}: the record image, left-justified and space-padded
     *                           to {@link #PAYLOAD_WIDTH} characters. Unchanged from the request except
     *                           on a successful read
     * @param sequentialPosition the ordinal position a subsequent sequential read should use. Advanced by
     *                           one on a successful sequential read, reset by an open or a close, and
     *                           otherwise unchanged; must not be negative
     * @throws IllegalArgumentException if {@code sequentialPosition} is negative, or if any character
     *                                  component carries a character US-ASCII cannot represent
     */
    public record StatementFileResponse(String ddName,
                                        String returnCode,
                                        String payload,
                                        int sequentialPosition) {

        public StatementFileResponse {
            if (sequentialPosition < 0) {
                throw new IllegalArgumentException(
                        "sequentialPosition must not be negative: " + sequentialPosition);
            }
            ddName = toAlphanumericField(FIELD_DD, ddName, DD_NAME_WIDTH);
            returnCode = toAlphanumericField(FIELD_RC, returnCode, RETURN_CODE_WIDTH);
            payload = toAlphanumericField(FIELD_FLDT, payload, PAYLOAD_WIDTH);
        }

        /**
         * Resolves the raw status to its typed constant, which is the only place the two-character
         * vocabulary is interpreted at all - and even here it is offered rather than imposed.
         *
         * <p>An empty result means the status falls outside the declared vocabulary, which is a
         * legitimate runtime outcome and not an error: callers that must reproduce the legacy branch
         * compare {@link #returnCode()} directly, exactly as
         * {@code [app/cbl/CBSTM03A.CBL:L353-L361]} does.
         *
         * @return the matching status constant, or an empty result when the code is not a declared one
         */
        public Optional<FileStatus> status() {
            return FileStatus.fromCode(this.returnCode);
        }
    }

    /**
     * What one file handler produces before its exit paragraphs run: the status the file's own status
     * field would hold, the payload as the read left it, and the file position as the read left it.
     *
     * <p>Private and deliberately minimal. It is <strong>not</strong> a coarse outcome model and must
     * never become one - the tri-state that the batch programs derive from a status belongs to the batch
     * tier, and introducing it here would collapse the end-of-file and error cases the statement
     * generator distinguishes.
     *
     * @param fileStatus         the raw two-character status the executed input-output verb produced, or
     *                           {@code null} when <strong>no verb executed at all</strong>. The null case
     *                           is the fall-through of the source's three unguarded checks, and it is the
     *                           reason this component is nullable rather than defaulted
     * @param payload            the payload as it stands after the handler, already at
     *                           {@link #PAYLOAD_WIDTH}
     * @param sequentialPosition the file position as it stands after the handler
     */
    private record FileAction(String fileStatus, String payload, int sequentialPosition) {

        /**
         * The handler took no action: no verb executed, so the file's status field was never written and
         * the payload and position stand exactly as the caller supplied them.
         *
         * @param request the request whose payload and position pass through untouched
         * @return an action carrying no status
         */
        static FileAction noAction(final StatementFileRequest request) {
            return new FileAction(null, request.payload(), request.sequentialPosition());
        }

        /**
         * A verb executed and produced a status without changing the payload - an open or a close.
         *
         * @param request  the request whose payload passes through untouched
         * @param status   the raw two-character status the verb produced
         * @param position the file position the verb leaves behind
         * @return an action carrying that status
         */
        static FileAction status(final StatementFileRequest request, final String status,
                                 final int position) {
            return new FileAction(status, request.payload(), position);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Paragraph 1 of 14 - 0000-START [app/cbl/CBSTM03B.CBL:L116]
    // ------------------------------------------------------------------------------------------------

    /**
     * The single entry point, translating {@code 0000-START} at {@code [app/cbl/CBSTM03B.CBL:L116]} and
     * the DD-name selection it contains at {@code [app/cbl/CBSTM03B.CBL:L118-L128]}.
     *
     * <p>The selection is reproduced clause for clause and in source order, because the legacy
     * {@code EVALUATE} is evaluated top down and stops at its first match. Its {@code WHEN OTHER} arm
     * transfers straight to the program exit, so <strong>an unrecognised DD name produces no file action
     * at all</strong>: no status is set, no payload is touched and no repository is consulted. That arm is
     * translated here as the {@code default} clause, and it is the one place a {@code default} appears in
     * this class - see the fall-through discussion below for why the handlers must not have one.
     *
     * <p><strong>The handlers deliberately do not signal an unsupported operation.</strong> Each of the
     * four is written in the source as three independent, consecutive {@code IF} tests with no
     * {@code ELSE} and no catch-all. An operation code that none of a handler's three tests matches -
     * including the two declared-but-dead codes {@link #OPERATION_WRITE} and {@link #OPERATION_REWRITE},
     * a keyed read aimed at a sequential file, a sequential read aimed at a random file, or any character
     * the contract never declared - therefore falls through every test and reaches the exit having done
     * nothing. The status the caller receives is the status it supplied on entry, unchanged. No exception
     * is thrown, no synthetic error code is minted and no log level above debug is used, because none of
     * that happens in the legacy either. This is preserved on purpose and is recorded in the decision log;
     * it is the single most consequential fidelity decision in this translation, and the one a
     * well-meaning tidy-up is most likely to destroy.
     *
     * <p>The stale status is carried entirely by {@code request}. Nothing about it is remembered by this
     * service between calls: there is no instance field and no static field holding a status, a cursor or
     * a payload, so two statement runs sharing this singleton cannot observe one another. That is a
     * deliberate divergence from the legacy, where the status published on a fall-through comes from the
     * subprogram's own working storage and so persists between calls; reproducing that literally would
     * require shared mutable state and would be a concurrency defect rather than fidelity. The divergence
     * is recorded in the decision log.
     *
     * @param request the parameter object, carrying the DD name, the operation, the status on entry, the
     *                key and its runtime length, the payload and the sequential position; must not be
     *                {@code null}
     * @param transactionSource the frozen projected transaction-work source for this statement run;
     *                          must not be {@code null}
     * @return the parameter object's written-back components: the raw two-character status, the payload
     *         and the sequential position
     * @throws NullPointerException     if {@code request} or {@code transactionSource} is {@code null}
     * @throws IllegalArgumentException if a keyed read supplies a runtime key length that lies outside the
     *                                 key field, which is the bounds-checked analogue of the source's
     *                                 reference modification at {@code [app/cbl/CBSTM03B.CBL:L189, L214]}
     */
    public StatementFileResponse execute(final StatementFileRequest request,
            final StatementTransactionSource transactionSource) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(transactionSource, "transactionSource must not be null");
        return switch (request.ddName()) {
            case DD_TRNXFILE -> performTransactionFileRange(request, transactionSource);
            case DD_XREFFILE -> performCrossReferenceFileRange(request);
            case DD_CUSTFILE -> performCustomerFileRange(request);
            case DD_ACCTFILE -> performAccountFileRange(request);
            default -> goBack(request);
        };
    }

    // ------------------------------------------------------------------------------------------------
    // Paragraph 2 of 14 - 9999-GOBACK [app/cbl/CBSTM03B.CBL:L130]
    // ------------------------------------------------------------------------------------------------

    /**
     * {@code 9999-GOBACK} at {@code [app/cbl/CBSTM03B.CBL:L130]}, whose body is the single
     * {@code GOBACK} that returns control to the caller.
     *
     * <p>Reached only from the DD-name selection's {@code WHEN OTHER} arm. Because a {@code GOBACK}
     * writes nothing, every component of the parameter object is handed back exactly as it arrived - the
     * status included. A caller that named a file this subprogram does not handle therefore observes no
     * change whatsoever, which is the behaviour of the source and not an oversight here.
     *
     * @param request the request whose components pass through untouched
     * @return the response echoing the request's status, payload and sequential position
     */
    private static StatementFileResponse goBack(final StatementFileRequest request) {
        LOG.debug("Statement file request named an unhandled DD name; returning without any file action");
        return new StatementFileResponse(request.ddName(), request.returnCode(), request.payload(),
                request.sequentialPosition());
    }

    // ------------------------------------------------------------------------------------------------
    // Paragraphs 3, 4 and 5 of 14 - the transaction file
    // 1000-TRNXFILE-PROC [L133] -> 1900-EXIT [L151] -> 1999-EXIT [L154]
    // ------------------------------------------------------------------------------------------------

    /**
     * Runs {@code PERFORM 1000-TRNXFILE-PROC THRU 1999-EXIT}, the range invoked at
     * {@code [app/cbl/CBSTM03B.CBL:L120]}.
     *
     * <p>The range spans three paragraphs, so control does not simply return from the first: it falls
     * through the intermediate exit at {@code [app/cbl/CBSTM03B.CBL:L151]}, which publishes the file's
     * status, and then through the terminal exit at {@code [app/cbl/CBSTM03B.CBL:L154]}. This method is
     * therefore an explicit ordered cascade of all three steps rather than a single call with an early
     * return, and the publication step runs on <em>every</em> path out of the handler - including the path
     * that performed no operation at all.
     *
     * @param request the parameter object
     * @return the response the range leaves behind
     */
    private StatementFileResponse performTransactionFileRange(final StatementFileRequest request,
            final StatementTransactionSource transactionSource) {
        final FileAction action = transactionFileProc(request, transactionSource);
        final String returnCode = transactionFileExit(request, action);
        return transactionFileTerminalExit(new StatementFileResponse(request.ddName(), returnCode,
                action.payload(), action.sequentialPosition()));
    }

    /**
     * {@code 1000-TRNXFILE-PROC} at {@code [app/cbl/CBSTM03B.CBL:L133]}.
     *
     * <p>Three independent, consecutive tests in source order - open at
     * {@code [app/cbl/CBSTM03B.CBL:L135]}, sequential read at {@code [app/cbl/CBSTM03B.CBL:L140]}, close
     * at {@code [app/cbl/CBSTM03B.CBL:L146]} - each transferring to the intermediate exit when it matches.
     * There is no {@code ELSE} and no catch-all in the source and there is none here: <strong>an operation
     * this paragraph does not test falls through all three and performs nothing</strong>, so the status the
     * caller supplied survives. A keyed read is one such operation, because the file is declared
     * {@code ACCESS MODE SEQUENTIAL} at {@code [app/cbl/CBSTM03B.CBL:L33]} and this paragraph consequently
     * offers open, sequential read and close only.
     *
     * @param request the parameter object
     * @return what the executed verb produced, or an action carrying no status when none executed
     */
    private FileAction transactionFileProc(final StatementFileRequest request,
            final StatementTransactionSource transactionSource) {
        if (request.hasOperation(OPERATION_OPEN)) {
            return openInput(request);
        }
        if (request.hasOperation(OPERATION_READ)) {
            return readNextTransactionRecord(request, transactionSource);
        }
        if (request.hasOperation(OPERATION_CLOSE)) {
            return closeFile(request);
        }
        return FileAction.noAction(request);
    }

    /**
     * {@code 1900-EXIT} at {@code [app/cbl/CBSTM03B.CBL:L151]}, the intermediate exit the range falls
     * through. Its whole body is one move of the transaction file's own status field into the
     * return-code field.
     *
     * @param request the parameter object, supplying the status to leave in place when no verb executed
     * @param action  what the preceding paragraph produced
     * @return the raw two-character status to publish
     */
    private static String transactionFileExit(final StatementFileRequest request, final FileAction action) {
        return publishFileStatus(DD_TRNXFILE, request, action);
    }

    /**
     * {@code 1999-EXIT} at {@code [app/cbl/CBSTM03B.CBL:L154]}, the terminal paragraph of the range. Its
     * body is a bare {@code EXIT}, which transfers control to the end of the range and writes nothing, so
     * this step hands the response on unchanged.
     *
     * @param response the response assembled by the preceding steps
     * @return that same response
     */
    private static StatementFileResponse transactionFileTerminalExit(final StatementFileResponse response) {
        return response;
    }

    // ------------------------------------------------------------------------------------------------
    // Paragraphs 6, 7 and 8 of 14 - the card cross-reference file
    // 2000-XREFFILE-PROC [L157] -> 2900-EXIT [L175] -> 2999-EXIT [L178]
    // ------------------------------------------------------------------------------------------------

    /**
     * Runs {@code PERFORM 2000-XREFFILE-PROC THRU 2999-EXIT}, the range invoked at
     * {@code [app/cbl/CBSTM03B.CBL:L122]}.
     *
     * <p>Three paragraphs, one intermediate status-publishing exit at
     * {@code [app/cbl/CBSTM03B.CBL:L175]} and one terminal exit at {@code [app/cbl/CBSTM03B.CBL:L178]},
     * cascaded in that order for the same reason as the transaction range.
     *
     * @param request the parameter object
     * @return the response the range leaves behind
     */
    private StatementFileResponse performCrossReferenceFileRange(final StatementFileRequest request) {
        final FileAction action = crossReferenceFileProc(request);
        final String returnCode = crossReferenceFileExit(request, action);
        return crossReferenceFileTerminalExit(new StatementFileResponse(request.ddName(), returnCode,
                action.payload(), action.sequentialPosition()));
    }

    /**
     * {@code 2000-XREFFILE-PROC} at {@code [app/cbl/CBSTM03B.CBL:L157]}.
     *
     * <p>The same three unguarded consecutive tests in the same source order - open at
     * {@code [app/cbl/CBSTM03B.CBL:L159]}, sequential read at {@code [app/cbl/CBSTM03B.CBL:L164]}, close
     * at {@code [app/cbl/CBSTM03B.CBL:L170]} - with no {@code ELSE} and no catch-all, so an untested
     * operation falls through and leaves the caller's status in place. The file is declared
     * {@code ACCESS MODE SEQUENTIAL} at {@code [app/cbl/CBSTM03B.CBL:L39]}, so no keyed read is offered.
     *
     * @param request the parameter object
     * @return what the executed verb produced, or an action carrying no status when none executed
     */
    private FileAction crossReferenceFileProc(final StatementFileRequest request) {
        if (request.hasOperation(OPERATION_OPEN)) {
            return openInput(request);
        }
        if (request.hasOperation(OPERATION_READ)) {
            return readNextCrossReferenceRecord(request);
        }
        if (request.hasOperation(OPERATION_CLOSE)) {
            return closeFile(request);
        }
        return FileAction.noAction(request);
    }

    /**
     * {@code 2900-EXIT} at {@code [app/cbl/CBSTM03B.CBL:L175]} - one move of the cross-reference file's
     * own status field into the return-code field.
     *
     * @param request the parameter object, supplying the status to leave in place when no verb executed
     * @param action  what the preceding paragraph produced
     * @return the raw two-character status to publish
     */
    private static String crossReferenceFileExit(final StatementFileRequest request,
                                                 final FileAction action) {
        return publishFileStatus(DD_XREFFILE, request, action);
    }

    /**
     * {@code 2999-EXIT} at {@code [app/cbl/CBSTM03B.CBL:L178]} - a bare {@code EXIT} that writes nothing.
     *
     * @param response the response assembled by the preceding steps
     * @return that same response
     */
    private static StatementFileResponse crossReferenceFileTerminalExit(
            final StatementFileResponse response) {
        return response;
    }

    // ------------------------------------------------------------------------------------------------
    // Paragraphs 9, 10 and 11 of 14 - the customer file
    // 3000-CUSTFILE-PROC [L181] -> 3900-EXIT [L200] -> 3999-EXIT [L203]
    // ------------------------------------------------------------------------------------------------

    /**
     * Runs {@code PERFORM 3000-CUSTFILE-PROC THRU 3999-EXIT}, the range invoked at
     * {@code [app/cbl/CBSTM03B.CBL:L124]}.
     *
     * <p>Three paragraphs, one intermediate status-publishing exit at
     * {@code [app/cbl/CBSTM03B.CBL:L200]} and one terminal exit at {@code [app/cbl/CBSTM03B.CBL:L203]},
     * cascaded in that order.
     *
     * @param request the parameter object
     * @return the response the range leaves behind
     */
    private StatementFileResponse performCustomerFileRange(final StatementFileRequest request) {
        final FileAction action = customerFileProc(request);
        final String returnCode = customerFileExit(request, action);
        return customerFileTerminalExit(new StatementFileResponse(request.ddName(), returnCode,
                action.payload(), action.sequentialPosition()));
    }

    /**
     * {@code 3000-CUSTFILE-PROC} at {@code [app/cbl/CBSTM03B.CBL:L181]}.
     *
     * <p>Three unguarded consecutive tests in source order - open at
     * {@code [app/cbl/CBSTM03B.CBL:L183]}, <em>keyed</em> read at {@code [app/cbl/CBSTM03B.CBL:L188]},
     * close at {@code [app/cbl/CBSTM03B.CBL:L195]} - with no {@code ELSE} and no catch-all, so an untested
     * operation falls through and leaves the caller's status in place. The file is declared
     * {@code ACCESS MODE RANDOM} at {@code [app/cbl/CBSTM03B.CBL:L45]}, so the middle test is the keyed
     * read and <strong>no sequential read is offered</strong> - the mirror image of the two sequential
     * files, and the asymmetry is the contract.
     *
     * <p>The keyed read reproduces {@code [app/cbl/CBSTM03B.CBL:L189]} in two steps. First the key field
     * is reference-modified to the caller's runtime length, so the caller and not this service decides the
     * effective key width; a length that lies outside the key field is rejected by the slicing primitive,
     * which is the bounds-checked analogue of the source's own reference modification. Second the slice is
     * moved into the record key, which is declared {@code PIC X(09)} at
     * {@code [app/cbl/CBSTM03B.CBL:L72]} - alphanumeric, so the move is left-justified and space-padded.
     * Contrast the account file, whose key is numeric and pads the other way.
     *
     * @param request the parameter object
     * @return what the executed verb produced, or an action carrying no status when none executed
     */
    private FileAction customerFileProc(final StatementFileRequest request) {
        if (request.hasOperation(OPERATION_OPEN)) {
            return openInput(request);
        }
        if (request.hasOperation(OPERATION_READ_KEYED)) {
            final String recordKey =
                    toAlphanumericField(FIELD_FD_CUST_ID, slicedKey(request), FD_CUST_ID_WIDTH);
            return this.customerRepository.findById(recordKey)
                    .map((Customer customer) ->
                            keyedReadSucceeded(request, customerRecordImage(request, customer)))
                    .orElseGet(() -> recordNotFound(DD_CUSTFILE, request));
        }
        if (request.hasOperation(OPERATION_CLOSE)) {
            return closeFile(request);
        }
        return FileAction.noAction(request);
    }

    /**
     * {@code 3900-EXIT} at {@code [app/cbl/CBSTM03B.CBL:L200]} - one move of the customer file's own
     * status field into the return-code field.
     *
     * @param request the parameter object, supplying the status to leave in place when no verb executed
     * @param action  what the preceding paragraph produced
     * @return the raw two-character status to publish
     */
    private static String customerFileExit(final StatementFileRequest request, final FileAction action) {
        return publishFileStatus(DD_CUSTFILE, request, action);
    }

    /**
     * {@code 3999-EXIT} at {@code [app/cbl/CBSTM03B.CBL:L203]} - a bare {@code EXIT} that writes nothing.
     *
     * @param response the response assembled by the preceding steps
     * @return that same response
     */
    private static StatementFileResponse customerFileTerminalExit(final StatementFileResponse response) {
        return response;
    }

    // ------------------------------------------------------------------------------------------------
    // Paragraphs 12, 13 and 14 of 14 - the account file
    // 4000-ACCTFILE-PROC [L206] -> 4900-EXIT [L225] -> 4999-EXIT [L228]
    // ------------------------------------------------------------------------------------------------

    /**
     * Runs {@code PERFORM 4000-ACCTFILE-PROC THRU 4999-EXIT}, the range invoked at
     * {@code [app/cbl/CBSTM03B.CBL:L126]}.
     *
     * <p>Three paragraphs, one intermediate status-publishing exit at
     * {@code [app/cbl/CBSTM03B.CBL:L225]} and one terminal exit at {@code [app/cbl/CBSTM03B.CBL:L228]},
     * cascaded in that order.
     *
     * @param request the parameter object
     * @return the response the range leaves behind
     */
    private StatementFileResponse performAccountFileRange(final StatementFileRequest request) {
        final FileAction action = accountFileProc(request);
        final String returnCode = accountFileExit(request, action);
        return accountFileTerminalExit(new StatementFileResponse(request.ddName(), returnCode,
                action.payload(), action.sequentialPosition()));
    }

    /**
     * {@code 4000-ACCTFILE-PROC} at {@code [app/cbl/CBSTM03B.CBL:L206]}.
     *
     * <p>Three unguarded consecutive tests in source order - open at
     * {@code [app/cbl/CBSTM03B.CBL:L208]}, keyed read at {@code [app/cbl/CBSTM03B.CBL:L213]}, close at
     * {@code [app/cbl/CBSTM03B.CBL:L220]} - with no {@code ELSE} and no catch-all. The file is declared
     * {@code ACCESS MODE RANDOM} at {@code [app/cbl/CBSTM03B.CBL:L51]}, so no sequential read is offered.
     *
     * <p><strong>The account record key is numeric, and that changes how the key is padded.</strong>
     * {@code FD-ACCT-ID} is declared {@code PIC 9(11)} at {@code [app/cbl/CBSTM03B.CBL:L77]}, whereas the
     * customer file's key is {@code PIC X(09)}. Moving an alphanumeric slice into a numeric display field
     * aligns it on the assumed decimal point, so the value arrives <em>right-justified and
     * zero-padded</em>, and an over-wide value loses its high-order rather than its low-order characters.
     * Padding this key the way the customer key is padded would append spaces to the right of an
     * eleven-digit identifier and match no row at all, which is why the two moves are deliberately
     * different and each cites its own picture clause. The runtime key length is honoured first, exactly
     * as at {@code [app/cbl/CBSTM03B.CBL:L214]}.
     *
     * @param request the parameter object
     * @return what the executed verb produced, or an action carrying no status when none executed
     */
    private FileAction accountFileProc(final StatementFileRequest request) {
        if (request.hasOperation(OPERATION_OPEN)) {
            return openInput(request);
        }
        if (request.hasOperation(OPERATION_READ_KEYED)) {
            final String recordKey = toNumericField(FIELD_FD_ACCT_ID, slicedKey(request), FD_ACCT_ID_WIDTH);
            return this.accountRepository.findById(recordKey)
                    .map((Account account) ->
                            keyedReadSucceeded(request, AccountRecordMapper.toRecord(account)))
                    .orElseGet(() -> recordNotFound(DD_ACCTFILE, request));
        }
        if (request.hasOperation(OPERATION_CLOSE)) {
            return closeFile(request);
        }
        return FileAction.noAction(request);
    }

    /**
     * {@code 4900-EXIT} at {@code [app/cbl/CBSTM03B.CBL:L225]} - one move of the account file's own status
     * field into the return-code field.
     *
     * @param request the parameter object, supplying the status to leave in place when no verb executed
     * @param action  what the preceding paragraph produced
     * @return the raw two-character status to publish
     */
    private static String accountFileExit(final StatementFileRequest request, final FileAction action) {
        return publishFileStatus(DD_ACCTFILE, request, action);
    }

    /**
     * {@code 4999-EXIT} at {@code [app/cbl/CBSTM03B.CBL:L228]} - a bare {@code EXIT} that writes nothing.
     *
     * @param response the response assembled by the preceding steps
     * @return that same response
     */
    private static StatementFileResponse accountFileTerminalExit(final StatementFileResponse response) {
        return response;
    }

    // ------------------------------------------------------------------------------------------------
    // The input-output verbs. One private helper per COBOL verb, shared by the handlers that use it,
    // so that a verb's semantics are defined once and cannot drift between the four files.
    // ------------------------------------------------------------------------------------------------

    /**
     * {@code OPEN INPUT}, the verb at {@code [app/cbl/CBSTM03B.CBL:L136, L160, L184, L209]}.
     *
     * <p>Reports success and positions at the first record, which is what an open of an existing input
     * file does. The relational tables that replace these files are created by the schema migrations and
     * are therefore always present, so the legacy open-failure status has no reachable counterpart here -
     * consistent with the finding that the file-not-found code is compared nowhere in the estate.
     *
     * @param request the parameter object, whose payload passes through untouched
     * @return an action reporting success and a file positioned at its first record
     */
    private static FileAction openInput(final StatementFileRequest request) {
        LOG.debug("Opening statement input file for DD name {}", request.ddName());
        return FileAction.status(request, FileStatus.SUCCESS.getCode(), FIRST_RECORD_POSITION);
    }

    /**
     * {@code CLOSE}, the verb at {@code [app/cbl/CBSTM03B.CBL:L147, L171, L196, L221]}.
     *
     * <p>Reports success and discards the file position, so a subsequent open starts from the first record
     * again. No resource is released because none is held: the repositories are stateless and every read
     * below stands alone.
     *
     * @param request the parameter object, whose payload passes through untouched
     * @return an action reporting success and a discarded file position
     */
    private static FileAction closeFile(final StatementFileRequest request) {
        LOG.debug("Closing statement input file for DD name {}", request.ddName());
        return FileAction.status(request, FileStatus.SUCCESS.getCode(), FIRST_RECORD_POSITION);
    }

    /**
     * {@code READ TRNX-FILE INTO LK-M03B-FLDT} at {@code [app/cbl/CBSTM03B.CBL:L141]} - the sequential
     * read of the transaction file.
     *
     * <p>The statement job's first two steps have already sorted and projected the transaction input.
     * One frozen projected record is requested at the position the parameter object carries; exhausting
     * that source yields the at-end status and leaves both the payload and the position alone, which is
     * how the caller's read loop terminates at
     * {@code [app/cbl/CBSTM03A.CBL:L840-L841]}.
     *
     * <p>The payload receives the projected 350-byte COSTM01 image unchanged. Parsing belongs to the
     * statement generator through {@link StatementWorkRecordMapper}; this service performs no
     * fixed-width slicing.
     *
     * @param request the parameter object, carrying the position to read from
     * @param transactionSource the frozen projected source
     * @return an action carrying success and the record image, or the at-end status
     */
    private static FileAction readNextTransactionRecord(final StatementFileRequest request,
            final StatementTransactionSource transactionSource) {
        return transactionSource.readAt(request.sequentialPosition())
                .map(record -> sequentialReadSucceeded(request, record))
                .orElseGet(() -> atEnd(DD_TRNXFILE, request));
    }

    /**
     * {@code READ XREF-FILE INTO LK-M03B-FLDT} at {@code [app/cbl/CBSTM03B.CBL:L165]} - the sequential
     * read of the card cross-reference file.
     *
     * <p>Ordered by the record key the file declares, which for this file is the card number alone. The
     * payload receives the complete 50-byte cross-reference record image, trailing filler included, so the
     * caller's receiving move reproduces the legacy bytes exactly.
     *
     * @param request the parameter object, carrying the position to read from
     * @return an action carrying success and the record image, or the at-end status
     */
    private FileAction readNextCrossReferenceRecord(final StatementFileRequest request) {
        return this.cardCrossReferenceRepository
                .findAll(PageRequest.of(request.sequentialPosition(), SINGLE_RECORD_PAGE_SIZE,
                        CROSS_REFERENCE_KEY_ORDER))
                .stream()
                .findFirst()
                .map((CardCrossReference crossReference) ->
                        sequentialReadSucceeded(request, CardXrefRecordMapper.toRecord(crossReference)))
                .orElseGet(() -> atEnd(DD_XREFFILE, request));
    }

    /**
     * Renders the 500-byte customer record image that the keyed read at
     * {@code [app/cbl/CBSTM03B.CBL:L190]} places in the payload.
     *
     * <p>The legacy file held the two regulated identifiers in the clear, so its read needed no help. This
     * module protects them at rest, so composing the image needs the cleartext recovered - and the function
     * that recovers it is supplied by the caller through the parameter object, because whether a payload
     * may carry a regulated identifier is the caller's policy and not this service's. A caller that
     * supplied no such function gets a rejection from the record mapper rather than a payload full of
     * envelope text.
     *
     * @param request  the parameter object, supplying the revealing function
     * @param customer the customer the keyed read found
     * @return the complete 500-byte record image
     */
    private static String customerRecordImage(final StatementFileRequest request,
                                              final Customer customer) {
        return CustomerRecordMapper.toRecord(customer, request.regulatedFieldRevealer());
    }

    /**
     * A read succeeded on a sequentially accessed file: report success, place the record image in the
     * payload and advance the file position by one so the next read returns the next record.
     *
     * @param request     the parameter object
     * @param recordImage the record image the mapper composed
     * @return an action carrying success, the new payload and the advanced position
     */
    private static FileAction sequentialReadSucceeded(final StatementFileRequest request,
                                                      final String recordImage) {
        return new FileAction(FileStatus.SUCCESS.getCode(), toPayloadField(recordImage),
                request.sequentialPosition() + 1);
    }

    /**
     * A read succeeded on a randomly accessed file: report success and place the record image in the
     * payload, leaving the file position alone. A keyed read moves no sequential cursor, and the two random
     * files are never read sequentially in any case.
     *
     * @param request     the parameter object
     * @param recordImage the record image the mapper composed
     * @return an action carrying success and the new payload
     */
    private static FileAction keyedReadSucceeded(final StatementFileRequest request,
                                                 final String recordImage) {
        return new FileAction(FileStatus.SUCCESS.getCode(), toPayloadField(recordImage),
                request.sequentialPosition());
    }

    /**
     * A sequential read found no next record. Publishes the at-end status and changes nothing else, which
     * is how the caller's loops at {@code [app/cbl/CBSTM03A.CBL:L355-L356, L841]} detect exhaustion. This
     * is a normal, expected outcome and never an error.
     *
     * @param ddName  the DD name, for the diagnostic only
     * @param request the parameter object, whose payload and position pass through untouched
     * @return an action carrying the at-end status
     */
    private static FileAction atEnd(final String ddName, final StatementFileRequest request) {
        LOG.debug("Sequential read of DD name {} reached end of file at position {}", ddName,
                request.sequentialPosition());
        return FileAction.status(request, FileStatus.END_OF_FILE.getCode(),
                request.sequentialPosition());
    }

    /**
     * A keyed read found no record for the supplied key. Publishes the record-not-found status and changes
     * nothing else; the caller decides what that means, and for the statement generator it means an abend.
     * Neither the key nor the payload is logged, because both carry regulated data.
     *
     * @param ddName  the DD name, for the diagnostic only
     * @param request the parameter object, whose payload and position pass through untouched
     * @return an action carrying the record-not-found status
     */
    private static FileAction recordNotFound(final String ddName, final StatementFileRequest request) {
        LOG.debug("Keyed read of DD name {} found no record for a key of runtime length {}", ddName,
                request.keyLength());
        return FileAction.status(request, FileStatus.RECORD_NOT_FOUND.getCode(),
                request.sequentialPosition());
    }

    // ------------------------------------------------------------------------------------------------
    // Field semantics. Every width, justification, truncation and reference-modification operation is
    // delegated to com.carddemo.util, so no record-layout or picture-clause arithmetic is performed here.
    // ------------------------------------------------------------------------------------------------

    /**
     * The one place a status is published, shared by the four intermediate exit paragraphs.
     *
     * <p>It always runs and always yields a status, which is what makes the intermediate exit reachable on
     * every path out of a handler. What it yields depends on whether a verb executed: if one did, the
     * status that verb produced; if none did, <strong>the status the caller supplied on entry, returned
     * unchanged</strong>. The second case is the fall-through of the source's three unguarded tests, and
     * preserving it is deliberate - see
     * {@link #execute(StatementFileRequest, StatementTransactionSource)}.
     *
     * @param ddName  the DD name, for the diagnostic only
     * @param request the parameter object, supplying the status carried on entry
     * @param action  what the handler's paragraph produced
     * @return the raw two-character status to write into the return-code field
     */
    private static String publishFileStatus(final String ddName, final StatementFileRequest request,
                                            final FileAction action) {
        final String executedVerbStatus = action.fileStatus();
        if (executedVerbStatus == null) {
            LOG.debug("Operation {} is not handled for DD name {}; returning the status supplied on entry "
                    + "without performing any file action", request.operation(), ddName);
            return request.returnCode();
        }
        return executedVerbStatus;
    }

    /**
     * Reference-modifies the key field to the caller's runtime key length, reproducing
     * {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)} at {@code [app/cbl/CBSTM03B.CBL:L189, L214]}.
     *
     * <p>The caller controls the effective key width, so the length is honoured rather than ignored and
     * trimming is never substituted for it: a length of nine over a key field holding an eleven-digit value
     * yields the first nine characters, whatever the remaining two contain. A length that lies outside the
     * twenty-five-character field is rejected by the slicing primitive, which is the bounds-checked
     * analogue of the source's reference modification; this service adds no validation of its own and, in
     * particular, does not second-guess a length that disagrees with the key's content.
     *
     * @param request the parameter object, supplying the key field and the runtime length
     * @return the leading slice of the key field
     * @throws IllegalArgumentException if the runtime key length lies outside the key field
     */
    private static String slicedKey(final StatementFileRequest request) {
        return FixedWidthFieldReader.of(FIELD_KEY, request.key(), KEY_WIDTH)
                .field(FIELD_KEY, 0, request.keyLength());
    }

    /**
     * Places the record image in a field of the payload's declared width, reproducing a move into
     * {@code LK-M03B-FLDT}, {@code PIC X(1000)}. Left-justified and space-padded, so a 50-byte
     * cross-reference image and a 500-byte customer image both arrive in a field the caller can move out of
     * whole.
     *
     * @param recordImage the record image the mapper composed
     * @return the image in a field of exactly {@link #PAYLOAD_WIDTH} characters
     */
    private static String toPayloadField(final String recordImage) {
        return toAlphanumericField(FIELD_FLDT, recordImage, PAYLOAD_WIDTH);
    }

    /**
     * Reproduces a move into an alphanumeric field of the given width: left-justified, space-padded when
     * the value is short, and truncated on the right when it is long, which is what a move into
     * {@code PIC X(n)} does.
     *
     * @param fieldName the legacy field name, used only in diagnostics
     * @param value     the value to move; {@code null} is treated as an unset field and yields spaces
     * @param width     the field's declared width
     * @return the value in a field of exactly {@code width} characters
     * @throws IllegalArgumentException if the value carries a character US-ASCII cannot represent
     */
    private static String toAlphanumericField(final String fieldName, final String value, final int width) {
        return FixedWidthFieldReader.builder(fieldName, width)
                .putAlphanumeric(fieldName, 0, width, fitToWidth(fieldName, value, width, false))
                .build()
                .image();
    }

    /**
     * Reproduces a move into a numeric display field of the given width: right-justified, zero-padded when
     * the value is short, and truncated on the <em>left</em> when it is long, because a numeric move aligns
     * on the assumed decimal point and so discards high-order rather than low-order characters.
     *
     * <p>No digit check is performed, deliberately. The legacy move states no expectation about the
     * sending field's content either, and a check here would reject a key the legacy would have accepted.
     *
     * @param fieldName the legacy field name, used only in diagnostics
     * @param value     the value to move; {@code null} is treated as an unset field and yields zeros
     * @param width     the field's declared width
     * @return the value in a field of exactly {@code width} characters
     * @throws IllegalArgumentException if the value carries a character US-ASCII cannot represent
     */
    private static String toNumericField(final String fieldName, final String value, final int width) {
        return FixedWidthFieldReader.builder(fieldName, width)
                .putNumeric(fieldName, 0, width, fitToWidth(fieldName, value, width, true))
                .build()
                .image();
    }

    /**
     * Cuts an over-wide value down to a field's width, from whichever end the field's picture clause
     * dictates, and leaves a value that already fits alone. The cut itself is performed by the slicing
     * primitive so that no offset arithmetic is written here.
     *
     * @param fieldName        the legacy field name, used only in diagnostics
     * @param value            the value to fit; {@code null} is treated as an empty value
     * @param width            the field's declared width
     * @param retainLowOrder   {@code true} for a numeric field, which keeps the trailing characters and
     *                         discards the leading ones; {@code false} for an alphanumeric field, which
     *                         does the reverse
     * @return a value no wider than {@code width}
     * @throws IllegalArgumentException if the value carries a character US-ASCII cannot represent
     */
    private static String fitToWidth(final String fieldName, final String value, final int width,
                                     final boolean retainLowOrder) {
        final String source = value == null ? "" : value;
        final int encodedWidth = FixedWidthFieldReader.encodedLength(source);
        if (encodedWidth <= width) {
            return source;
        }
        final int retainedFrom = retainLowOrder ? encodedWidth - width : 0;
        return FixedWidthFieldReader.of(fieldName, source, encodedWidth)
                .field(fieldName, retainedFrom, width);
    }

}
