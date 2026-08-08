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

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.PfKeyTranslator;

/**
 * The account-view transaction {@code CAVW}: one read-only turn that resolves an account identifier to an
 * account and its customer and hands the screen state back for rendering.
 *
 * <p><strong>Provenance.</strong> Legacy authority {@code app/cbl/COACTVWC.cbl}, 941 lines, transaction
 * {@code CAVW}, read at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} whose members carry
 * the upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No line of COBOL is
 * transcribed here; the member, its paragraph names, its field names, its line numbers and its exact
 * message literals are cited, which is what keeps the traceability matrix verifiable against a tree this
 * module never reads at runtime.
 *
 * <p><strong>Paragraph count: 35 units, 34 distinct names, 36 methods.</strong> Thirty-five paragraph
 * labels are physically declared in the member's procedure division between lines 262 and 916, and only
 * thirty-four of them are distinct because one name is declared twice. <strong>Thirty-five</strong> is what
 * the action plan records and what the traceability matrix carries rows for, and this class names
 * thirty-six methods - one more than the labels it answers for, because the duplicated label collapses to
 * one method while a statement-level helper is broken out; a method is not a unit and the extra one owes no
 * row.
 *
 * <p>Two further paragraphs are reached from this member and are not units of it. The
 * {@code COPY 'CSSTRPFY'} directive at line 913 is a directive rather than a paragraph, and the two
 * paragraphs it inserts, at lines 17 and 80 of {@code app/cpy/CSSTRPFY.cpy}, are units of that copybook,
 * which the matrix gives a section and two rows of its own. The copybook is included by five members, so
 * counting its paragraphs against each of them would report ten units for two and the frozen 544-row total
 * would no longer hold. An earlier revision of this class published a measured thirty-eight by adding the
 * directive's Area A slot and the two inserted paragraphs to its own count.
 *
 * <p><strong>Anomaly: one paragraph name, two declarations.</strong> {@code 0000-MAIN-EXIT} is declared
 * twice, at line 408 and again at line 411, each with the same no-op body. Both declarations map to the
 * single method {@code mainExit}. Both source rows are recorded against that one method in
 * {@code docs/traceability-matrix.md} rather than one being dropped, so the 544-row count stays honest, and
 * the duplication is raised as an entry in {@code docs/decision-log.md}. Neither document is written from
 * here; both are owned elsewhere.
 *
 * <p><strong>Family membership decides the abend wiring.</strong> {@code COACTVWC} is one of the five
 * programs - with {@code COACTUPC}, {@code COCRDLIC}, {@code COCRDSLC} and {@code COCRDUPC} - that include
 * the attention-key copybook and are the only ones that register a CICS abend handler. The registration
 * sits at lines 264 to 266 and the handler itself, {@code ABEND-ROUTINE}, ends in an abend with code
 * {@code 9999} at line 934. That path is wired here, in {@code abendRoutine}, because the legacy wired it
 * here; the twelve online programs that register no handler must not acquire one.
 *
 * <p><strong>Read-only, and structurally so.</strong> This is a view transaction. It performs no write of
 * any kind: no save, no delete, no flush, no modifying query, no raw SQL. The single entry point is
 * annotated read-only and nothing else. The version attribute exists on the account entity but is never
 * read for a comparison and never incremented here, so no optimistic-lock conflict can arise from this
 * class. That the relational store gives this transaction READ COMMITTED isolation is a documented
 * <em>strict improvement</em> over the legacy posture, where every application file was defined
 * uncommitted-read with no recovery and no journal: a reviewer should read the stronger isolation as an
 * upgrade and not as a behavioural regression, and it is recorded as such in the decision log even though
 * this particular service never writes.
 *
 * <p><strong>Three reads, in the legacy order, with no association between them.</strong> The customer is
 * reached through the card cross-reference, exactly as the legacy reaches it: the cross-reference is read
 * by account identifier through the {@code CXACAIX} alternate-index path at line 728, the account master
 * by the same identifier at line 777, and the customer master by the identifier the cross-reference
 * yielded at line 827. This module declares no JPA association anywhere, so the join is three explicit
 * repository calls and never a mapped relationship, a fetch graph or a join fetch. Each miss keeps its own
 * outcome message and its own raised field flag; the three are never collapsed into one generic
 * not-found.
 *
 * <p>The cross-reference alternate key is non-unique, so a single row must be selected from a possibly
 * multi-row access path. The selection is not made here: the repository declares
 * {@code findFirstByXrefAcctIdOrderByXrefCardNumAsc}, which pushes "the first row in ascending base-key
 * order" into the query, the card number being this cluster's base key and therefore the order a legacy
 * keyed read of the path resolves in. This service asks for that one row and reads its absence as the
 * legacy not-found response. Selecting in the service instead - materialising every row of the account
 * and keeping the minimum - is the regression {@code DL-164} in {@code docs/decision-log.md} records and
 * undoes, because it made the result depend on plan shape rather than on a declared order and repeated
 * one rule across five services. The semantic is the one the action plan describes: first row wins, and
 * nothing found is a screen message rather than an exception.
 *
 * <p><strong>The card file is declared and never read.</strong> Lines 186 to 191 declare a card file name
 * and a card-by-account path name, and lines 151 to 183 declare the card list, card detail and card update
 * program and map names. Not one of those literals is referenced by any statement in the procedure
 * division. This transaction therefore has no card access path at all: the card number that reaches the
 * screen comes from the cross-reference row at line 740, never from the card table. No card repository is
 * injected, because injecting one would add a collaborator the legacy never had, and the two unreferenced
 * file-name literals are published below as the documented finding they are.
 *
 * <p><strong>Anomalies preserved rather than corrected.</strong> Beyond the duplicated label, seven
 * findings in this member are reproduced faithfully and documented, never silently fixed:
 *
 * <ul>
 *   <li>The two guards at lines 704 and 713 test condition names whose only assignments are commented out,
 *       at lines 792 and 842. They are condition names on the 75-character message field, so each is a
 *       live value comparison that is always false because the text actually written is different. The
 *       consequence is observable and is preserved: an account-master miss does <em>not</em> take the early
 *       exit, so the customer read still happens.</li>
 *   <li>The guard at lines 387 to 392 cannot execute. Every arm of the dispatch above it ends the task, so
 *       the fall-through never happens. It is not a paragraph of its own and owes no method; its effect is
 *       performed anyway by {@code COMMON-RETURN} at line 395.</li>
 *   <li>The cursor decision at lines 546 to 552 has three arms with identical bodies, so the cursor lands
 *       on the account-identifier filter unconditionally. That field is the screen's only input, so the
 *       three not-found outcomes are distinguished by message text and by which field flag is raised - the
 *       cross-reference and account misses raise the account filter flag, the customer miss raises the
 *       customer filter flag at line 841 - and never by a different cursor field. Inventing three cursor
 *       positions would be a parity defect, not an improvement.</li>
 *   <li>Seven condition names are declared and never assigned: the output-informing message, the exit
 *       message, both account-number edit messages, the cross-reference not-found message, the
 *       cross-reference read-error message and the placeholder message. They contribute no code here.</li>
 *   <li>The non-numeric edit at lines 671 to 673 moves a literal that differs from the never-assigned
 *       condition name intended for it - the moved text has a double space and a hyphenated "non-zero".
 *       The moved literal is the one reproduced. The stray characters at columns 73 and 74 of line 672 sit
 *       in the identification area and are ignored by the compiler, so they are not a defect in the
 *       code.</li>
 *   <li>The cross-field edit at lines 640 to 642 overwrites the message without the guard the individual
 *       edits use, so the blank-input text that actually reaches the screen is the shorter one set there,
 *       replacing the one set at line 658. Both assignments are reproduced in that order.</li>
 *   <li>The comment at line 725 describes reading the card file, while the statement beneath it reads the
 *       cross-reference path. The statement governs.</li>
 * </ul>
 *
 * <p><strong>Field-name spelling.</strong> The legacy account layout misspells its expiration-date field
 * as {@code ACCT-EXPIRAION-DATE}. The Java property is spelled correctly as {@code acctExpirationDate}
 * while the byte layout it maps to is unchanged, so the record image stays compatible and only the Java
 * identifier is corrected. The same is true of the card layout's equivalent field, which this transaction
 * does not read.
 *
 * <p><strong>What this class deliberately does not do.</strong> It applies no range check to the credit
 * score: the stored value is displayed exactly as held, because the 300-to-850 rule is screen-level input
 * validation owned by the account-update path and 21 of the 50 reference customers carry a score below
 * that floor, the lowest being three digits of {@code 001}. It performs no field-level error decoration
 * and writes no asterisk marker, both of which belong to the presentation layer. It scales no monetary
 * value, uses no binary floating-point type anywhere, and slices no fixed-width record image: layout
 * knowledge belongs to the record mappers in the utility layer. It converts none of the stored
 * 26-character timestamp strings into a temporal type. It reaches no card list or card detail behaviour.
 * It decodes no attention key itself and declares no route table, delegating both to the utility-layer key
 * translator and to the navigation service. Rendering - flattening this result into fixed-width screen
 * fields, and formatting the national identifier as the legacy formats it at lines 496 to 504 - belongs to
 * the presentation DTO layer and is absent here.
 *
 * <p><strong>Regulated values pass through untouched and are never logged.</strong> The national
 * identifier and the government-issued identifier are held as protected values rather than as cleartext,
 * both are nullable, and the national identifier is absent in all 50 reference rows. This service neither
 * decrypts nor reformats nor logs either of them, and tolerates their absence without failing. The
 * account group identifier is ten spaces in all 50 reference rows and is never trimmed and never treated
 * as absent.
 *
 * <p><strong>Statelessness.</strong> The bean is a stateless singleton with no mutable field. The working
 * storage the legacy program declares is modelled by a per-invocation local holder, so two concurrent
 * turns cannot observe each other's state.
 *
 * <p>The turn is deliberately non-transactional. Each repository read owns its ordinary repository
 * transaction, so a caught store failure cannot leave an enclosing read-only transaction
 * rollback-only and replace the legacy screen result at proxy exit.
 */
@Service
public final class AccountViewService {

    /**
     * Diagnostic channel for this class, replacing the console-display statements that were the legacy
     * system's only instrumentation.
     *
     * <p>Nothing regulated is ever written here: no customer record as a whole, no national identifier, no
     * government-issued identifier, no card number. What is logged is the raw file status, the resource
     * name, the outcome of a read and the route chosen - values this class or the migration vocabulary
     * declares rather than values a client supplied.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AccountViewService.class);

    /** Transaction identifier of this screen: {@code LIT-THISTRANID} at lines 145 to 146. */
    public static final String TRANSACTION_ID = "CAVW";

    /** Legacy member name, used as the abend culprit: {@code LIT-THISPGM} at lines 143 to 144. */
    public static final String PROGRAM_NAME = "COACTVWC";

    /**
     * Mapset name of this screen, from {@code LIT-THISMAPSET} at lines 147 to 148.
     *
     * <p>The legacy literal is eight characters wide and holds a trailing space, while the navigation
     * field it is moved into at line 346 is seven wide, so the value that survives into carried state is
     * the seven-character form reproduced here.
     */
    public static final String MAPSET_NAME = "COACTVW";

    /** Map name of this screen: {@code LIT-THISMAP} at lines 149 to 150. */
    public static final String MAP_NAME = "CACTVWA";

    /** Member name of the user main menu, this screen's exit default: {@code LIT-MENUPGM}, lines 168-169. */
    public static final String MENU_PROGRAM_NAME = "COMEN01C";

    /** Transaction identifier of the user main menu: {@code LIT-MENUTRANID} at lines 170 to 171. */
    public static final String MENU_TRANSACTION_ID = "CM00";

    /** Account master resource name: {@code LIT-ACCTFILENAME} at lines 184 to 185. */
    public static final String ACCOUNT_FILE_NAME = "ACCTDAT";

    /** Customer master resource name: {@code LIT-CUSTFILENAME} at lines 188 to 189. */
    public static final String CUSTOMER_FILE_NAME = "CUSTDAT";

    /**
     * Resource name of the account access path over the card cross-reference:
     * {@code LIT-CARDXREFNAME-ACCT-PATH} at lines 192 to 193.
     *
     * <p>This is the alternate-index path name the read at line 728 names, not the name of the base
     * cluster beneath it, which the CICS resource definitions register as {@code CARDXREF}.
     */
    public static final String CARD_XREF_ACCOUNT_PATH_NAME = "CXACAIX";

    /**
     * Card master resource name as declared at lines 186 to 187 - and referenced by no statement in the
     * procedure division.
     *
     * <p>Published as the documented finding it is rather than dropped, so a reader of the traceability
     * matrix can see that the literal exists and that this transaction never uses it.
     */
    public static final String UNREFERENCED_CARD_FILE_NAME = "CARDDAT";

    /**
     * Card-by-account access path name as declared at lines 190 to 191 - and referenced by no statement in
     * the procedure division, for the reason given on {@link #UNREFERENCED_CARD_FILE_NAME}.
     */
    public static final String UNREFERENCED_CARD_ACCOUNT_PATH_NAME = "CARDAIX";

    /**
     * Screen field the cursor is placed on, which is the account-identifier filter and only ever that.
     *
     * <p>All three arms of the cursor decision at lines 546 to 552 position the cursor identically, and
     * this is the screen's sole input field.
     */
    public static final String ACCOUNT_ID_SCREEN_FIELD_ID = "ACCTSID";

    /** Declared width of the account-identifier filter, from its {@code PIC X(11)} clause. */
    public static final int ACCOUNT_ID_WIDTH = 11;

    /** Declared width of the customer identifier, from its {@code PIC X(09)} clause. */
    public static final int CUSTOMER_ID_WIDTH = 9;

    /** Declared width of the screen message field, from {@code WS-RETURN-MSG PIC X(75)} at line 117. */
    public static final int RETURN_MESSAGE_WIDTH = 75;

    /** Declared width of the informational field, from {@code WS-INFO-MSG PIC X(40)} at line 110. */
    public static final int INFO_MESSAGE_WIDTH = 40;

    /** Declared width of the long diagnostic field, from {@code WS-LONG-MSG PIC X(500)} at line 109. */
    public static final int LONG_MESSAGE_WIDTH = 500;

    /** Declared width of each response-code slot, from {@code ERROR-RESP PIC X(10)} at lines 98 to 103. */
    public static final int RESPONSE_CODE_WIDTH = 10;

    /** Declared width of the failing-operation slot, from {@code ERROR-OPNAME PIC X(8)} at lines 89-90. */
    public static final int OPERATION_NAME_WIDTH = 8;

    /** Declared width of the failing-resource slot, from {@code ERROR-FILE PIC X(9)} at lines 93 to 94. */
    public static final int ERROR_FILE_NAME_WIDTH = 9;

    /**
     * Abend code the unexpected-data arm places in the abend structure at line 377.
     *
     * <p>Distinct from the {@code 9999} the handler at line 934 actually abends with, and never used to
     * abend: the arm that sets it sends plain text and ends the task instead.
     */
    public static final String UNEXPECTED_DATA_SCENARIO_ABEND_CODE = "0001";

    /** Failing operation named by all three read-error arms, at lines 762, 812 and 861. */
    public static final String READ_OPERATION = "READ";

    /**
     * The message field in its off state: {@code WS-RETURN-MSG-OFF VALUE SPACES} at line 118, materialised
     * at the field's declared width.
     *
     * <p>The off state is a value and not a flag, because the condition name is declared on the message
     * field itself. Every guard the legacy writes as "if the message is off" is therefore a comparison
     * against this value, which is why it is published rather than hidden.
     */
    public static final String RETURN_MESSAGE_OFF = " ".repeat(RETURN_MESSAGE_WIDTH);

    /**
     * The informational field in its off state, from {@code WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES}
     * at lines 111 to 112, materialised at the field's declared width.
     *
     * <p>That condition name carries two values. Assigning it takes the first, so spaces is what an
     * assignment produces; a comparison must accept the low-value form as well, which
     * {@link #isNoInfoMessage(String)} does.
     */
    public static final String INFO_MESSAGE_OFF = " ".repeat(INFO_MESSAGE_WIDTH);

    /** The long diagnostic field in its initial state, at its declared width. */
    public static final String LONG_MESSAGE_OFF = " ".repeat(LONG_MESSAGE_WIDTH);

    /**
     * {@code WS-PROMPT-FOR-INPUT} from lines 113 to 114, at the informational field's declared width.
     *
     * <p>The trailing spaces are part of the screen contract and are never trimmed. The visible text is
     * 39 characters and the field is 40, so the width is reached by padding derived arithmetically rather
     * than by hand-counted whitespace.
     */
    public static final String PROMPT_FOR_INPUT_MESSAGE =
            fieldImage("Enter or update id of account to display", INFO_MESSAGE_WIDTH);

    /** {@code WS-PROMPT-FOR-ACCT} from lines 121 to 122, at the message field's declared width. */
    public static final String PROMPT_FOR_ACCOUNT_MESSAGE =
            fieldImage("Account number not provided", RETURN_MESSAGE_WIDTH);

    /**
     * {@code NO-SEARCH-CRITERIA-RECEIVED} from lines 123 to 124, at the message field's declared width.
     *
     * <p>This is the text a blank filter actually leaves on the screen: the cross-field edit at lines 640
     * to 642 assigns it with no guard, so it replaces {@link #PROMPT_FOR_ACCOUNT_MESSAGE} which the field
     * edit assigned moments earlier at line 658.
     */
    public static final String NO_SEARCH_CRITERIA_MESSAGE =
            fieldImage("No input received", RETURN_MESSAGE_WIDTH);

    /**
     * The text the non-numeric edit moves at lines 671 to 673, at the message field's declared width.
     *
     * <p>Reproduced exactly as moved, including the double space after the third word and the hyphen in
     * the fourth-from-last: it differs from the never-assigned condition name declared for the same
     * purpose at lines 127 to 128, and the moved literal is the one that reaches a screen.
     */
    public static final String ACCOUNT_FILTER_NOT_NUMERIC_MESSAGE =
            fieldImage("Account Filter must  be a non-zero 11 digit number", RETURN_MESSAGE_WIDTH);

    /** The text the unexpected-data arm moves at lines 379 to 380, at the message field's declared width. */
    public static final String UNEXPECTED_DATA_SCENARIO_MESSAGE =
            fieldImage("UNEXPECTED DATA SCENARIO", RETURN_MESSAGE_WIDTH);

    /**
     * {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} from lines 131 to 132, at the message field's declared width.
     *
     * <p>Published because the guard at line 704 compares the message field against exactly this value.
     * The only assignment of it, at line 792, is commented out, so the comparison is live and always
     * false - which is precisely why it must be reproduced as a comparison and not as a flag.
     */
    public static final String DID_NOT_FIND_ACCOUNT_IN_ACCTDAT_MESSAGE =
            fieldImage("Did not find this account in account master file", RETURN_MESSAGE_WIDTH);

    /**
     * {@code DID-NOT-FIND-CUST-IN-CUSTDAT} from lines 133 to 134, at the message field's declared width,
     * compared by the guard at line 713 for the reason given on
     * {@link #DID_NOT_FIND_ACCOUNT_IN_ACCTDAT_MESSAGE}. Its assignment at line 842 is commented out too.
     */
    public static final String DID_NOT_FIND_CUSTOMER_IN_CUSTDAT_MESSAGE =
            fieldImage("Did not find associated customer in master file", RETURN_MESSAGE_WIDTH);

    /** Leading segment of the cross-reference and account miss texts, at lines 748 and 797. */
    private static final String MISS_ACCOUNT_PREFIX = "Account:";

    /** Middle segment of the cross-reference and account miss texts, at lines 750 and 799. */
    private static final String MISS_ACCOUNT_MIDDLE = " not found in";

    /** Resource phrase of the cross-reference miss text, at line 751. Two spaces before the last word. */
    private static final String MISS_XREF_RESOURCE_PHRASE = " Cross ref file.  Resp:";

    /** Resource phrase of the account-master miss text, at line 800. */
    private static final String MISS_ACCOUNT_RESOURCE_PHRASE = " Acct Master file.Resp:";

    /** Reason phrase shared by the cross-reference and account miss texts, at lines 753 and 802. */
    private static final String MISS_ACCOUNT_REASON_PHRASE = " Reas:";

    /** Leading segment of the customer miss text, at line 847. */
    private static final String MISS_CUSTOMER_PREFIX = "CustId:";

    /** Middle segment of the customer miss text, at line 849. */
    private static final String MISS_CUSTOMER_MIDDLE = " not found";

    /** Resource phrase of the customer miss text, at line 850. One trailing space is part of it. */
    private static final String MISS_CUSTOMER_RESOURCE_PHRASE = " in customer master.Resp: ";

    /** Reason phrase of the customer miss text, at line 852. Upper case, unlike its two siblings. */
    private static final String MISS_CUSTOMER_REASON_PHRASE = " REAS:";

    /** First filler of the read-error structure, at lines 87 to 88. */
    private static final String FILE_ERROR_PREFIX = "File Error: ";

    /** Second filler of the read-error structure, at lines 91 to 92. */
    private static final String FILE_ERROR_ON = " on ";

    /** Third filler of the read-error structure, at lines 95 to 97. */
    private static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    /** Fourth filler of the read-error structure, at lines 100 to 101. */
    private static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** Trailing filler of the read-error structure, five spaces, at lines 104 to 105. */
    private static final String FILE_ERROR_TRAILER = "     ";

    /**
     * Reason recorded when the registered handler abends, at the abend structure's declared width of 50.
     *
     * <p>The legacy handler leaves the reason as spaces and lets the message field carry the explanation,
     * so this text is supplied by the migration to name the condition rather than reproduced from a
     * literal the member does not have.
     */
    private static final String ABEND_REASON_UNEXPECTED = "UNEXPECTED FAILURE IN ACCOUNT VIEW";

    /**
     * Terminal text the handler transmits before abending, from the default it substitutes at line 919.
     *
     * <p>The handler sends the abend structure to the terminal and only then abends, which is the ordering
     * this class reproduces: log first, delegate second.
     */
    private static final String ABEND_TERMINAL_MESSAGE = "UNEXPECTED ABEND OCCURRED.";

    /** Header date rendering, fixed to the root locale so no ambient locale can change the digits. */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/uu", Locale.ROOT);

    /** Header time rendering, fixed to the root locale for the reason given on the date format. */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * The lowest character value, standing in for the low-value state a fixed-width field holds after the
     * legacy initialising statement at lines 268 to 270.
     */
    private static final char LOW_VALUE = Character.MIN_VALUE;

    /** The space, which is padding in every fixed-width field this class handles. */
    private static final char SPACE = ' ';

    /** Lowest ASCII digit, for the numeric test the legacy performs on the filter at line 666. */
    private static final char ASCII_ZERO = '0';

    /** Highest ASCII digit, for the numeric test the legacy performs on the filter at line 666. */
    private static final char ASCII_NINE = '9';

    /**
     * The asterisk the screen sends back in the filter field, tested at line 628.
     *
     * <p>Recognising it is this class's business; writing it is not. The legacy writes it as a
     * field-level error marker at line 563, and that marker belongs to the presentation layer's field
     * decorator, so it is never written here.
     */
    private static final String FILTER_RESET_MARKER = "*";

    /** Fixed stand-in a diagnostic uses in place of an absent value. */
    private static final String ABSENT_VALUE_SUBSTITUTE = "(absent)";

    /** Fixed stand-in this result's rendering uses in place of a withheld value. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /** An all-absent screen input, used when a caller supplies none at all. */
    private static final ScreenInputState NO_SCREEN_INPUT =
            new ScreenInputState(null, null, null, null, null, null, null, null, null);

    private final AccountRepository accountRepository;

    private final CustomerRepository customerRepository;

    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    private final NavigationService navigationService;

    private final MessageCatalogService messageCatalogService;

    private final AbendService abendService;

    private final Clock clock;

    /**
     * Creates the service with every collaborator supplied through the constructor.
     *
     * <p>No card repository is among them, and the omission is deliberate: this transaction declares two
     * card resource names and references neither, so it has no card access path to model.
     *
     * @param accountRepository the account master, read by identifier at line 777
     * @param customerRepository the customer master, read by identifier at line 827
     * @param cardCrossReferenceRepository the account access path over the card cross-reference, read at
     *                                     line 728
     * @param navigationService the single authority for the routes this service returns
     * @param messageCatalogService the source of the shared screen texts, used for the unmapped-key
     *                              message and the two screen titles
     * @param abendService the abend raiser, wired here because this member registers a handler at lines
     *                     264 to 266
     * @param clock the clock the header date and time are read from, injected so tests can fix it
     */
    public AccountViewService(final AccountRepository accountRepository,
            final CustomerRepository customerRepository,
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final NavigationService navigationService,
            final MessageCatalogService messageCatalogService,
            final AbendService abendService,
            final Clock clock) {
        this.accountRepository =
                Objects.requireNonNull(accountRepository, "accountRepository must not be null");
        this.customerRepository =
                Objects.requireNonNull(customerRepository, "customerRepository must not be null");
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.navigationService =
                Objects.requireNonNull(navigationService, "navigationService must not be null");
        this.messageCatalogService = Objects.requireNonNull(messageCatalogService,
                "messageCatalogService must not be null");
        this.abendService = Objects.requireNonNull(abendService, "abendService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Handles one turn of transaction {@code CAVW}. Paragraph {@code 0000-MAIN}, line 262.
     *
     * <p>The paragraph's shape is reproduced statement group by statement group: the abend handler is
     * registered at lines 264 to 266 and appears here as the surrounding failure handling; the working
     * storage is initialised at lines 268 to 270 and appears as a fresh per-invocation holder; this
     * screen's transaction identifier is stored at line 274 and the message field switched off at line
     * 278; carried state is either discarded or adopted at lines 282 to 293; the attention key is mapped
     * at lines 299 to 300 and gated at lines 306 to 314; and the four-arm decision at lines 323 to 383
     * dispatches in that clause order.
     *
     * <p>The guard at lines 387 to 392 is absent by necessity rather than by choice: every arm of that
     * decision ends the task, so the fall-through it protects cannot be reached. What it would have done -
     * copy the message field into the screen's error slot - is performed unconditionally by
     * {@code COMMON-RETURN} at line 395, which every screen-returning arm reaches.
     *
     * @param attentionKeyIdentifier the raw terminal attention-key identifier exactly as received, or
     *                               {@code null} when the caller supplies none; translated by the
     *                               utility-layer key translator and never decoded here
     * @param input the received screen work area; {@code null} is accepted and read as an all-absent one
     * @param inboundContext the navigation state the client echoed back; {@code null} is accepted and read
     *                       as the zero-length communication area the legacy tests for at line 282
     * @return the route, carried state, projection, messages, focus field and flags for the caller to
     *         render; never {@code null}
     * @throws AbendException when the transaction cannot continue, either because the carried state
     *         nominates a destination that cannot be resolved or because an unexpected failure reaches the
     *         registered handler
     */
    public AccountViewResult viewAccount(final String attentionKeyIdentifier,
            final ScreenInputState input,
            final ScreenNavigationState inboundContext) {
        final ScreenInputState received = (input == null) ? NO_SCREEN_INPUT : input;
        final WorkingStorage state = new WorkingStorage();
        state.transactionId = TRANSACTION_ID;
        state.returnMessage = RETURN_MESSAGE_OFF;
        state.commareaAbsent = isNavigationStateAbsent(inboundContext);
        storePassedData(inboundContext, state);
        state.keyAction = yyyyStorePfkey(attentionKeyIdentifier, received, state);
        applyAttentionKeyGate(state);
        reportUnmappedAttentionKey(state);
        try {
            final AccountViewResult outcome = switch (resolveMainDispatch(state)) {
                case BACK_NAVIGATION -> transferToCallingProgram(state);
                case FIRST_ENTRY -> gatherSelectionCriteria(state);
                case RE_ENTRY -> processReceivedSelection(received, state);
                case UNEXPECTED -> reportUnexpectedDataScenario(state);
            };
            return mainExit(outcome);
        } catch (final AbendException alreadyAbending) {
            throw alreadyAbending;
        } catch (final RuntimeException unexpectedFailure) {
            throw abendRoutine(unexpectedFailure, state);
        }
    }

    /**
     * Adopts or discards the carried navigation state. Statement group at lines 282 to 293.
     *
     * <p>Two conditions discard it: a zero-length communication area, and arrival from the user main menu
     * on a turn that is not a re-entry. Anything else adopts what the client echoed.
     *
     * <p>Discarding is not the same as leaving: this member reinitialises the state and carries on into
     * the decision below, where the initialised context reads as a first entry and the screen is sent.
     * That differs from the canonical absent-context rule the navigation service publishes, under which an
     * online turn with no state returns to sign-on, and the difference is the member's own - this screen
     * simply starts a fresh conversation instead.
     */
    private void storePassedData(final ScreenNavigationState inboundContext, final WorkingStorage state) {
        final boolean arrivedFromMenuOnFirstEntry = inboundContext != null
                && MENU_PROGRAM_NAME.equals(stripFieldPadding(inboundContext.fromProgram()))
                && !inboundContext.reEntry();
        if (state.commareaAbsent || arrivedFromMenuOnFirstEntry) {
            state.commarea = ScreenNavigationState.empty();
            return;
        }
        state.commarea = inboundContext;
    }

    /**
     * Maps the raw attention-key identifier to the action it denotes. Paragraph
     * {@code YYYY-STORE-PFKEY}, supplied by {@code app/cpy/CSSTRPFY.cpy} line 17 and expanded into this
     * member by the directive at line 913.
     *
     * <p>The 28-arm construct in that copybook folds the high program-function keys onto their low twins,
     * so keys 13 through 24 are indistinguishable from keys 1 through 12 and are not distinct actions.
     * That fold is implemented once, in the utility-layer translator, and is neither repeated nor
     * second-guessed here.
     *
     * <p>The construct has no fallback arm, so an unrecognised identifier caused no assignment and left
     * the action field holding whatever it already held. This method reproduces that exactly: an
     * unrecognised identifier yields the action the received work area still carries, which may itself be
     * absent - the low-value state the initialising statement leaves behind. No placeholder action is
     * invented, because the action vocabulary declares none.
     */
    private KeyAction yyyyStorePfkey(final String attentionKeyIdentifier,
            final ScreenInputState received,
            final WorkingStorage state) {
        if (attentionKeyIdentifier == null) {
            return yyyyStorePfkeyExit(received.keyAction());
        }
        final Optional<KeyAction> mapped = PfKeyTranslator.translate(attentionKeyIdentifier);
        if (mapped.isPresent()) {
            return yyyyStorePfkeyExit(mapped.get());
        }
        state.attentionKeyUnmapped = !isFieldBlank(attentionKeyIdentifier);
        return yyyyStorePfkeyExit(received.keyAction());
    }

    /**
     * Terminator of the key-mapping range: paragraph {@code YYYY-STORE-PFKEY-EXIT},
     * {@code app/cpy/CSSTRPFY.cpy} line 80, whose body is a no-op.
     *
     * <p>Its only role in the legacy is to be the end of the performed range, so the Java form carries the
     * range's value through unchanged. Every exit paragraph below follows the same shape, which is what
     * lets a forward jump to an exit label translate into a return of that terminator.
     */
    private static KeyAction yyyyStorePfkeyExit(final KeyAction keyAction) {
        return keyAction;
    }

    /**
     * Gates the mapped key against the two this screen accepts. Statement group at lines 306 to 314.
     *
     * <p>The flag is set invalid first, raised to valid only for the enter key or the third
     * program-function key, and an invalid flag then <em>rewrites the action to the enter key</em>. That
     * coercion is the whole behaviour: on this screen the fourth program-function key, the clear key and
     * both program-attention keys are processed exactly as if enter had been pressed, and no message says
     * otherwise. It is reproduced rather than tidied away, and it is why the sixteenth program-function
     * key behaves identically to the fourth.
     */
    private void applyAttentionKeyGate(final WorkingStorage state) {
        state.pfKeyFlag = KeyValidity.INVALID;
        if (state.keyAction == KeyAction.ENTER || navigationService.isBackNavigationKey(state.keyAction)) {
            state.pfKeyFlag = KeyValidity.VALID;
        }
        if (state.pfKeyFlag.isInvalid()) {
            state.keyAction = KeyAction.ENTER;
        }
    }

    /**
     * Reports an attention-key identifier the translator could not map - a documented divergence from the
     * legacy, and the only one on this path.
     *
     * <p>The legacy coerces an unmapped identifier to the enter key in silence, because a 3270 terminal
     * could only ever send one of the 28 identifiers the mapping construct knows. A client of a machine
     * interface can send anything, and silently treating an unrecognised value as enter would leave it no
     * way to learn that its input was not understood. This method therefore raises the error flag and
     * places the shared invalid-key text - at its own contractual width of 50 characters, untrimmed and
     * not re-padded - while leaving the coercion above untouched, so routing and presentation are exactly
     * what the legacy produces.
     *
     * <p>Three consequences are deliberate. A recognised but inactive key gets no message at all, so the
     * high and low program-function keys stay indistinguishable. Because the assignment observes the
     * legacy's own first-writer-wins guard, an unmapped identifier's message takes precedence over a later
     * guarded validation message on the same turn. And <strong>the legacy input flag is left alone</strong>:
     * the edit paragraph sets it in order at line 624 and would clear anything raised here, so this
     * condition is carried separately and combined into the published error flag by the result assembly
     * instead. That keeps the legacy flag's semantics exactly as the source writes them while still telling
     * a client its key was not understood. The divergence is recorded in {@code docs/decision-log.md}.
     */
    private void reportUnmappedAttentionKey(final WorkingStorage state) {
        if (!state.attentionKeyUnmapped) {
            return;
        }
        if (isReturnMessageOff(state.returnMessage)) {
            state.returnMessage = messageCatalogService.invalidKeyMessage();
        }
        LOG.debug("Account view received an unmapped attention-key identifier: transaction={}",
                TRANSACTION_ID);
    }

    /**
     * Selects the arm of the four-way decision at lines 323 to 383, in the source's clause order.
     *
     * <p>The construct tests conditions rather than a single subject, so the order is the contract: the
     * third program-function key is tested first, then the enter context, then the re-enter context, and
     * anything else falls to the final arm. The last arm is reachable in the legacy because the context
     * field is a single digit that admits eight further values; the two-state context this migration
     * carries cannot express one, so the arm is retained and documented rather than deleted.
     */
    private MainDispatch resolveMainDispatch(final WorkingStorage state) {
        if (navigationService.isBackNavigationKey(state.keyAction)) {
            return MainDispatch.BACK_NAVIGATION;
        }
        if (state.commarea.firstEntry()) {
            return MainDispatch.FIRST_ENTRY;
        }
        if (state.commarea.reEntry()) {
            return MainDispatch.RE_ENTRY;
        }
        return MainDispatch.UNEXPECTED;
    }

    /**
     * Returns to the calling program, or to the user main menu when nothing called this screen. First arm
     * of the decision, lines 324 to 352.
     *
     * <p>Order matters and is preserved. The destination transaction and program are computed from the
     * originating pair at lines 328 to 339, each defaulting to the user main menu when its field is blank,
     * and only afterwards is the originating pair overwritten with this screen's own identity at lines 341
     * to 342. Computing them the other way round would send every exit back to this screen.
     *
     * <p>One assignment is faithfully reproduced although it is almost certainly a legacy defect: line 344
     * sets the carried user type to the standard-user code unconditionally, so an administrator who leaves
     * this screen is carried onward as a standard user. It is preserved because it is observable, and
     * noted here so a reader does not mistake it for a translation error.
     *
     * <p>The route is resolved from the state as it arrived, before the overwrite, through the navigation
     * service - which raises an abend when the originating field names a destination that cannot be
     * resolved, reproducing what the legacy transfer would have done with an unresolvable program name.
     */
    private AccountViewResult transferToCallingProgram(final WorkingStorage state) {
        final ScreenNavigationState inbound = state.commarea;
        final String destinationTransactionId = isFieldBlank(inbound.fromTransactionId())
                ? MENU_TRANSACTION_ID
                : inbound.fromTransactionId();
        final String destinationProgram = isFieldBlank(inbound.fromProgram())
                ? MENU_PROGRAM_NAME
                : inbound.fromProgram();
        final NavigationService.Route target =
                navigationService.resolveBackNavigation(carriedState(inbound), NavigationService.Route.USER_MENU);
        state.commarea = new ScreenNavigationState(
                TRANSACTION_ID,
                PROGRAM_NAME,
                destinationTransactionId,
                destinationProgram,
                inbound.userId(),
                UserType.USER.getCode(),
                ScreenNavigationState.ProgramContext.ENTER,
                inbound.customerId(),
                inbound.customerFirstName(),
                inbound.customerMiddleName(),
                inbound.customerLastName(),
                inbound.accountId(),
                inbound.accountStatus(),
                inbound.cardNumber(),
                MAP_NAME,
                MAPSET_NAME);
        state.presentation = Presentation.TRANSFER;
        LOG.debug("Account view exiting: rule=back-navigation route={}", target.getRouteValue());
        return buildResult(target.getRouteValue(), state);
    }

    /**
     * Gathers selection criteria on a turn arriving from another context. Second arm of the decision,
     * lines 353 to 360: send the screen, then take the common return.
     */
    private AccountViewResult gatherSelectionCriteria(final WorkingStorage state) {
        sendMap(state);
        return commonReturn(state);
    }

    /**
     * Processes a re-entered screen. Third arm of the decision, lines 361 to 374.
     *
     * <p>Inputs are edited first. On an input error the screen is re-sent without reading anything, which
     * is what keeps a rejected filter from reaching the database; otherwise the three reads run and the
     * screen is sent with whatever they produced. Both branches take the common return.
     */
    private AccountViewResult processReceivedSelection(final ScreenInputState received,
            final WorkingStorage state) {
        processInputs(received, state);
        if (state.inputFlag.isError()) {
            sendMap(state);
            return commonReturn(state);
        }
        readAcct(state);
        sendMap(state);
        return commonReturn(state);
    }

    /**
     * Reports a carried context that is neither an entry nor a re-entry. Final arm of the decision, lines
     * 375 to 382.
     *
     * <p>The arm fills the abend structure at lines 376 to 378 - culprit, the code {@code 0001}, a blank
     * reason - and then does not abend: it sends plain text and ends the task, so nothing ever transmits or
     * reads what it filled in. The three assignments are therefore recorded as a diagnostic rather than
     * stored in a field nothing would read, which keeps them observable without inventing a consumer the
     * source does not have.
     *
     * <p>Reaching this arm requires a context state the two-state carried context cannot represent, as
     * {@link #resolveMainDispatch(WorkingStorage)} explains. It is kept because the paragraph it performs
     * is real and owes a method, and because deleting an arm the source declares would be a silent
     * behaviour change if the carried representation ever widened.
     */
    private AccountViewResult reportUnexpectedDataScenario(final WorkingStorage state) {
        state.returnMessage = UNEXPECTED_DATA_SCENARIO_MESSAGE;
        LOG.error("Account view reached the unexpected-data arm: culprit={} abendCode={} reason={}",
                PROGRAM_NAME,
                UNEXPECTED_DATA_SCENARIO_ABEND_CODE,
                fieldImage("", AbendException.REASON_LENGTH));
        return sendPlainText(state);
    }

    /**
     * Re-arms the conversation and hands the turn back. Paragraph {@code COMMON-RETURN}, line 394.
     *
     * <p>The message field is copied into the screen's error slot at line 395, the carried state is
     * rebuilt at lines 397 to 400, and the task returns with this screen's own transaction re-armed at
     * lines 402 to 406. The re-arm is why the route is this screen: the next turn comes back here.
     */
    private AccountViewResult commonReturn(final WorkingStorage state) {
        state.errorMessage = state.returnMessage;
        return buildResult(NavigationService.Route.ACCOUNT_VIEW.getRouteValue(), state);
    }

    /**
     * Terminator of the entry paragraph: {@code 0000-MAIN-EXIT}, whose body is a no-op.
     *
     * <p><strong>This one method carries two source paragraphs.</strong> The label is declared twice, at
     * line 408 and again at line 411, with identical bodies - the second declaration is unreachable and is
     * very likely an editing accident. Collapsing them into one method is the only sound translation, and
     * both source rows are recorded against this method in {@code docs/traceability-matrix.md} so the
     * paragraph count stays honest rather than quietly losing one. The duplication is raised as a
     * decision-log entry.
     */
    private static AccountViewResult mainExit(final AccountViewResult outcome) {
        return outcome;
    }

    /**
     * Assembles and sends the screen. Paragraph {@code 1000-SEND-MAP}, line 416.
     *
     * <p>Four performed ranges in this order: initialise the map, populate its variables, set its
     * attributes, transmit it. The order is load bearing - the attribute step reads flags the variable step
     * leaves behind, and the transmit step is what flips the carried context to re-entry.
     */
    private void sendMap(final WorkingStorage state) {
        state.screenHeader = screenInit();
        setupScreenVars(state);
        setupScreenAttrs(state);
        sendScreen(state);
        sendMapExit();
    }

    /** Terminator of the send-map range: {@code 1000-SEND-MAP-EXIT}, line 427, a no-op. */
    private static void sendMapExit() {
        // EXIT. The paragraph exists to end the performed range and does nothing else.
    }

    /**
     * Initialises the screen. Paragraph {@code 1100-SCREEN-INIT}, line 431.
     *
     * <p>The output map is blanked at line 432 and six header fields are then populated: the two screen
     * titles from the shared catalogue at lines 436 to 437, this screen's transaction identifier and
     * member name at lines 438 to 439, and the current date and time at lines 447 and 453. The blanking is
     * implicit here because a fresh header record carries no prior turn's values.
     *
     * <p>The date and time are read from the injected clock rather than from the platform's default, which
     * is what lets a test fix them, and are rendered at the two edited widths the shared date copybook
     * declares - two digits, separator, two digits, separator, two digits, eight characters each. The
     * rendering is pinned to the root locale so no ambient locale can substitute a different digit set for
     * a field the screen contract fixes at eight characters. The current date is read twice in the source,
     * at lines 434 and 441; one read is enough and the second is a redundant statement rather than a
     * behaviour, so a single reading is taken and both statements map here.
     */
    private ScreenHeader screenInit() {
        final LocalDateTime taken = LocalDateTime.now(clock);
        final ScreenHeader header = new ScreenHeader(
                TRANSACTION_ID,
                PROGRAM_NAME,
                messageCatalogService.screenTitle01(),
                messageCatalogService.screenTitle02(),
                HEADER_DATE_FORMAT.format(taken),
                HEADER_TIME_FORMAT.format(taken));
        screenInitExit();
        return header;
    }

    /** Terminator of the screen-initialisation range: {@code 1100-SCREEN-INIT-EXIT}, line 457, a no-op. */
    private static void screenInitExit() {
        // EXIT. No body in the source, and none is invented here.
    }

    /**
     * Populates the screen's variables. Paragraph {@code 1200-SETUP-SCREEN-VARS}, line 460.
     *
     * <p>A zero-length communication area takes the whole population step out of play and leaves only the
     * prompt, at lines 462 to 463. Otherwise the filter field is echoed - blanked when the filter flag is
     * blank at line 466, and carrying the received identifier otherwise at line 468 - and two guarded
     * groups follow.
     *
     * <p><strong>Both guards are reproduced exactly as written, including the one that looks wrong.</strong>
     * The account group at lines 471 to 491 is guarded by <em>either</em> master having been found, not by
     * the account master having been found, so an account miss whose customer read then succeeds still
     * presents the account field group. The customer group at lines 493 to 523 is guarded by the customer
     * master alone. Which entities are present is carried in the result together with both found flags, so
     * a renderer can reproduce the legacy's own presentation decision rather than infer one.
     *
     * <p>The ten account fields moved at lines 473 to 490 and the seventeen customer fields moved at lines
     * 494 to 522 are not copied field by field here: the entities carry them, and flattening them into
     * fixed-width screen slots is the presentation layer's work. Two of those moves deserve naming. The
     * national identifier is assembled at lines 496 to 504 into a hyphenated three-two-four form; that
     * assembly is not performed here, because the stored value is a protected value rather than nine
     * cleartext digits, because slicing a field into positions is record-layout work that belongs to the
     * utility layer, and because the value is absent in every reference row. The credit score moved at
     * lines 505 to 506 is carried exactly as stored, with no range test of any kind.
     *
     * <p>Finally the informational field is defaulted to the prompt when it is off, at lines 528 to 530,
     * and the message and informational fields are placed on the screen at lines 532 and 534.
     */
    private void setupScreenVars(final WorkingStorage state) {
        if (state.commareaAbsent) {
            state.infoMessage = PROMPT_FOR_INPUT_MESSAGE;
        } else {
            state.echoedAccountId =
                    state.accountFilterFlag.isBlank() ? null : state.accountIdFilter;
            if (state.foundAcctInMaster || state.foundCustInMaster) {
                state.accountFieldsPresented = true;
            }
            if (state.foundCustInMaster) {
                state.customerFieldsPresented = true;
            }
        }
        if (isNoInfoMessage(state.infoMessage)) {
            state.infoMessage = PROMPT_FOR_INPUT_MESSAGE;
        }
        state.errorMessage = state.returnMessage;
        setupScreenVarsExit();
    }

    /** Terminator of the variable-population range: {@code 1200-SETUP-SCREEN-VARS-EXIT}, line 537. */
    private static void setupScreenVarsExit() {
        // EXIT. No body in the source.
    }

    /**
     * Sets the screen's attributes. Paragraph {@code 1300-SETUP-SCREEN-ATTRS}, line 541.
     *
     * <p>The filter field is unprotected at line 543, the cursor is positioned by the decision at lines 546
     * to 552, the field colour is defaulted at line 555 and reddened when the filter flag is not in order
     * at lines 557 to 559, an asterisk marker and a second reddening apply when the flag is blank
     * <em>and</em> the turn is a re-entry at lines 561 to 565, and the informational field is darkened when
     * it holds nothing at lines 567 to 571.
     *
     * <p><strong>The cursor decision has three arms and one behaviour.</strong> All three move the same
     * value into the same field, so the cursor lands on the filter field unconditionally - which is
     * unsurprising, since it is the screen's only input. The arms are preserved in their source order all
     * the same, because a switch that discards two arms is a switch that has lost the evidence that they
     * were identical.
     *
     * <p>Colour bytes and the asterisk marker are not produced here. They are field-level error decoration,
     * which belongs to the presentation layer's decorator; what this method establishes is the state that
     * decorator reads - which flag is raised, and whether the turn is a re-entry - so the two-state
     * distinction the legacy draws between a field that is blank and a field that is merely wrong survives
     * into the response contract.
     */
    private void setupScreenAttrs(final WorkingStorage state) {
        state.focusScreenFieldId = switch (resolveCursorArm(state)) {
            case FILTER_NOT_OK -> ACCOUNT_ID_SCREEN_FIELD_ID;
            case FILTER_BLANK -> ACCOUNT_ID_SCREEN_FIELD_ID;
            case OTHER -> ACCOUNT_ID_SCREEN_FIELD_ID;
        };
        state.filterInError = state.accountFilterFlag.isNotOk();
        state.filterMissingOnReEntry =
                state.accountFilterFlag.isBlank() && state.commarea.reEntry();
        state.informationalFieldDarkened = isNoInfoMessage(state.infoMessage);
        setupScreenAttrsExit();
    }

    /**
     * Selects the arm of the cursor decision at lines 546 to 552, in the source's clause order: filter not
     * in order first, filter blank second, anything else last.
     */
    private static CursorArm resolveCursorArm(final WorkingStorage state) {
        if (state.accountFilterFlag.isNotOk()) {
            return CursorArm.FILTER_NOT_OK;
        }
        if (state.accountFilterFlag.isBlank()) {
            return CursorArm.FILTER_BLANK;
        }
        return CursorArm.OTHER;
    }

    /** Terminator of the attribute range: {@code 1300-SETUP-SCREEN-ATTRS-EXIT}, line 574. */
    private static void setupScreenAttrsExit() {
        // EXIT. No body in the source.
    }

    /**
     * Transmits the screen. Paragraph {@code 1400-SEND-SCREEN}, line 577.
     *
     * <p>The next mapset and map are recorded at lines 579 to 580 and the carried context is switched to
     * re-entry at line 581 - which is the statement that makes the <em>next</em> turn take the re-entry arm
     * of the main decision, and therefore the statement that makes field-level error presentation
     * conditional across this family of screens. The transmission itself at lines 583 to 590 has no
     * counterpart: a machine interface returns a payload instead, so what the caller receives is the
     * assembled result.
     */
    private void sendScreen(final WorkingStorage state) {
        state.nextMapset = MAPSET_NAME;
        state.nextMap = MAP_NAME;
        state.commarea = state.commarea.withReEntry();
        state.presentation = Presentation.MAP;
        sendScreenExit();
    }

    /** Terminator of the transmission range: {@code 1400-SEND-SCREEN-EXIT}, line 592. */
    private static void sendScreenExit() {
        // EXIT. No body in the source.
    }

    /**
     * Receives and edits the screen's inputs. Paragraph {@code 2000-PROCESS-INPUTS}, line 596.
     *
     * <p>Receive, then edit, then record four values at lines 601 to 604: the message field is copied into
     * the screen's error slot, and the next program, mapset and map are set to this screen's own so the
     * conversation stays here. The next-program field is declarative in the legacy - nothing dispatches on
     * it - and it is carried for the same reason: it is state the client echoes.
     */
    private void processInputs(final ScreenInputState received, final WorkingStorage state) {
        receiveMap(received, state);
        editMapInputs(state);
        state.errorMessage = state.returnMessage;
        state.nextProgram = PROGRAM_NAME;
        state.nextMapset = MAPSET_NAME;
        state.nextMap = MAP_NAME;
        processInputsExit();
    }

    /** Terminator of the input-processing range: {@code 2000-PROCESS-INPUTS-EXIT}, line 607. */
    private static void processInputsExit() {
        // EXIT. No body in the source.
    }

    /**
     * Receives the screen. Paragraph {@code 2100-RECEIVE-MAP}, line 610.
     *
     * <p>The legacy statement reads the terminal's input into the input map and records a response code it
     * then never tests - a receive failure is silently ignored on this screen. A machine interface has
     * already received its payload by the time this class is reached, so what remains of the paragraph is
     * the part that matters: taking the filter field at its declared eleven-character width, so a shorter
     * or longer value cannot change the edits that follow. Nothing is trimmed, folded or normalised.
     */
    private static void receiveMap(final ScreenInputState received, final WorkingStorage state) {
        state.receivedAccountIdField = (received.accountId() == null)
                ? null
                : fieldImage(received.accountId(), ACCOUNT_ID_WIDTH);
        receiveMapExit();
    }

    /** Terminator of the receive range: {@code 2100-RECEIVE-MAP-EXIT}, line 619. */
    private static void receiveMapExit() {
        // EXIT. No body in the source.
    }

    /**
     * Edits the received inputs. Paragraph {@code 2200-EDIT-MAP-INPUTS}, line 622.
     *
     * <p>Both flags are set in order at lines 624 to 625, the filter field is normalised at lines 628 to
     * 633 - an asterisk or an all-space field becomes the low-value state, anything else is taken as
     * received - the field edit runs at lines 636 to 637, and the cross-field edit at lines 640 to 642
     * replaces the message when the filter came through blank.
     *
     * <p>That last assignment carries no message-is-off guard, unlike every other assignment in this
     * member, so it overwrites the text the field edit set moments earlier at line 658. The shorter text is
     * therefore what a blank filter actually shows. Both assignments are performed, in that order,
     * because the order is the observable behaviour.
     *
     * <p>Recognising the asterisk is not the same as writing one: the legacy writes it as an error marker
     * at line 563, and that marker is the presentation layer's to add.
     */
    private static void editMapInputs(final WorkingStorage state) {
        state.inputFlag = InputFlag.OK;
        state.accountFilterFlag = FilterFlag.VALID;
        final String receivedField = state.receivedAccountIdField;
        if (FILTER_RESET_MARKER.equals(stripFieldPadding(receivedField))
                || isAllSpaces(receivedField)) {
            state.accountIdFilter = null;
        } else {
            state.accountIdFilter = receivedField;
        }
        editAccount(state);
        if (state.accountFilterFlag.isBlank()) {
            state.returnMessage = NO_SEARCH_CRITERIA_MESSAGE;
        }
        editMapInputsExit();
    }

    /** Terminator of the edit range: {@code 2200-EDIT-MAP-INPUTS-EXIT}, line 645. */
    private static void editMapInputsExit() {
        // EXIT. No body in the source.
    }

    /**
     * Edits the account-identifier filter. Paragraph {@code 2210-EDIT-ACCOUNT}, line 649.
     *
     * <p>Three outcomes in source order. The flag is set out of order first at line 650 so that every path
     * which does not explicitly succeed leaves it that way. A filter that is low values or all spaces is
     * blank: the error flag is raised, the filter flag is set blank, the prompt text is set <em>only if the
     * message field is still off</em> at lines 657 to 659, the carried account identifier is zeroed at line
     * 660, and the range exits. A filter that is not wholly numeric or is all zeros is wrong: the error
     * flag is raised, the filter flag is set out of order, the non-numeric text is set under the same guard
     * at lines 670 to 674, the carried identifier is zeroed at line 675, and the range exits. Anything else
     * is valid: the filter is carried at line 678 and the flag is set valid at line 679.
     *
     * <p>Both forward jumps, at lines 661 and 676, are jumps to the range's exit label and become returns
     * of that terminator. Neither of the nine backward jumps in the estate is in this member, so no loop is
     * reconstructed anywhere in this class.
     *
     * <p>The numeric test is written against the ASCII digit range over the whole field rather than against
     * a library digit predicate, because the library predicate also accepts digit code points a
     * single-byte fixed-width field cannot hold, which the legacy numeric test would never have accepted.
     * The zero test is applied to the field's value, so a field of eleven zero digits is rejected exactly
     * as the source rejects it.
     */
    private static void editAccount(final WorkingStorage state) {
        state.accountFilterFlag = FilterFlag.NOT_OK;
        if (isFieldBlank(state.accountIdFilter)) {
            state.inputFlag = InputFlag.ERROR;
            state.accountFilterFlag = FilterFlag.BLANK;
            if (isReturnMessageOff(state.returnMessage)) {
                state.returnMessage = PROMPT_FOR_ACCOUNT_MESSAGE;
            }
            state.commarea = withCarriedAccountId(state.commarea, zeroFilled(ACCOUNT_ID_WIDTH));
            editAccountExit();
            return;
        }
        if (!isAllAsciiDigits(state.accountIdFilter) || isAllZeroDigits(state.accountIdFilter)) {
            state.inputFlag = InputFlag.ERROR;
            state.accountFilterFlag = FilterFlag.NOT_OK;
            if (isReturnMessageOff(state.returnMessage)) {
                state.returnMessage = ACCOUNT_FILTER_NOT_NUMERIC_MESSAGE;
            }
            state.commarea = withCarriedAccountId(state.commarea, zeroFilled(ACCOUNT_ID_WIDTH));
            editAccountExit();
            return;
        }
        state.commarea = withCarriedAccountId(state.commarea, state.accountIdFilter);
        state.accountFilterFlag = FilterFlag.VALID;
        editAccountExit();
    }

    /** Terminator of the account-edit range: {@code 2210-EDIT-ACCOUNT-EXIT}, line 683. */
    private static void editAccountExit() {
        // EXIT. No body in the source; it is the target of the two forward jumps above.
    }

    /**
     * Resolves the account and its customer. Paragraph {@code 9000-READ-ACCT}, line 687.
     *
     * <p>Three reads in a fixed order, each guarded, and the guards are where the interesting behaviour
     * lives. The informational field is switched off at line 689 and the validated filter becomes the read
     * key at line 691. The cross-reference is read first; if it left the filter flag out of order the range
     * exits at lines 697 to 699, which is the one guard that genuinely stops the sequence. The account
     * master is read next.
     *
     * <p><strong>The two guards after it cannot stop anything, and that is preserved.</strong> Lines 704
     * and 713 test condition names declared on the message field, and the only statements that would have
     * assigned them - lines 792 and 842 - are commented out. Each test is therefore a live comparison
     * against a literal the code never writes, so it is always false. The consequence is observable and is
     * reproduced rather than tidied: an account-master miss falls through, the customer identifier the
     * cross-reference yielded becomes the next read key at line 708, and the customer read happens anyway.
     * A customer found on that path then satisfies the presentation guard at lines 471 to 472, so the
     * account field group is presented for an account that was never found. Both comparisons are written
     * out below exactly as the source writes them, because writing them as unreachable Java, or omitting
     * them, would erase the evidence that the legacy intended an early exit and did not get one.
     */
    private void readAcct(final WorkingStorage state) {
        state.infoMessage = INFO_MESSAGE_OFF;
        state.readAccountKey = state.commarea.accountId();
        getCardXrefByAcct(state);
        if (state.accountFilterFlag.isNotOk()) {
            readAcctExit();
            return;
        }
        getAcctDataByAcct(state);
        if (DID_NOT_FIND_ACCOUNT_IN_ACCTDAT_MESSAGE.equals(state.returnMessage)) {
            readAcctExit();
            return;
        }
        state.readCustomerKey = state.commarea.customerId();
        getCustDataByCust(state);
        if (DID_NOT_FIND_CUSTOMER_IN_CUSTDAT_MESSAGE.equals(state.returnMessage)) {
            readAcctExit();
            return;
        }
        readAcctExit();
    }

    /** Terminator of the resolution range: {@code 9000-READ-ACCT-EXIT}, line 720. */
    private static void readAcctExit() {
        // EXIT. No body in the source; it is the target of the three forward jumps above.
    }

    /**
     * Reads the cross-reference by account identifier. Paragraph {@code 9200-GETCARDXREF-BYACCT}, line 723.
     *
     * <p>The legacy statement at lines 727 to 735 reads the account access path over the card
     * cross-reference, keyed on the eleven-character account identifier. The comment above it describes
     * reading the card file; the statement reads the cross-reference path, and the statement governs.
     *
     * <p>The alternate key is non-unique, so the access path can hold several rows for one account and the
     * legacy read simply returns the first. That selection is expressed by the repository's own
     * first-row-ordered finder, which pushes it into the query, and an absent result is the analogue of the
     * legacy not-found response. A caller therefore never sees a collection and never has to remember to
     * take an element from it.
     *
     * <p>The three-arm decision at lines 737 to 769 is reproduced in its clause order, with the final arm
     * as the default. Found carries the customer identifier and the card number out of the row at lines 739
     * to 740 - which is the <em>only</em> way a card number reaches this screen, since the card table has no
     * access path in this transaction. Not found raises the error flag and the account filter flag and,
     * only if the message field is still off, composes the miss text. The default arm does the same and
     * composes the read-error text instead; it does not abend, because the legacy does not abend here.
     */
    private void getCardXrefByAcct(final WorkingStorage state) {
        state.operationInFlight = READ_OPERATION;
        state.resourceInFlight = CARD_XREF_ACCOUNT_PATH_NAME;
        final ReadOutcome outcome = readCrossReferenceRow(state);
        switch (outcome) {
            case FOUND -> state.commarea = withCarriedCustomerAndCard(state.commarea,
                    state.cardCrossReference.getXrefCustId(),
                    state.cardCrossReference.getXrefCardNum());
            case NOT_FOUND -> {
                state.rawFileStatus = RecordNotFoundException.STATUS_RECORD_NOT_FOUND;
                state.inputFlag = InputFlag.ERROR;
                state.accountFilterFlag = FilterFlag.NOT_OK;
                if (isReturnMessageOff(state.returnMessage)) {
                    state.errorResp = fieldImage(state.rawFileStatus, RESPONSE_CODE_WIDTH);
                    state.errorResp2 = fieldImage("", RESPONSE_CODE_WIDTH);
                    state.returnMessage = crossReferenceMissMessage(state);
                }
                LOG.debug("Account view found no cross-reference row: resource={} fileStatus={}",
                        CARD_XREF_ACCOUNT_PATH_NAME, state.rawFileStatus);
            }
            default -> {
                state.inputFlag = InputFlag.ERROR;
                state.accountFilterFlag = FilterFlag.NOT_OK;
                state.errorOpName = fieldImage(READ_OPERATION, OPERATION_NAME_WIDTH);
                state.errorFile = fieldImage(CARD_XREF_ACCOUNT_PATH_NAME, ERROR_FILE_NAME_WIDTH);
                state.returnMessage = fileErrorMessage(state);
            }
        }
        getCardXrefByAcctExit();
    }

    /**
     * Performs the read statement at lines 727 to 735 and classifies its outcome into one of the three the
     * decision above declares.
     *
     * <p>A statement-level helper, not a paragraph: it owes no traceability row of its own and the paragraph
     * that calls it owns the row. It exists so that the decision reads as the decision the source writes,
     * with the response classification separated from the response handling.
     *
     * <p>Absence is not failure. The repository reports a missing row as an absent result and a failing read
     * by raising, which is what lets the two legacy arms stay distinct instead of collapsing into one.
     */
    private ReadOutcome readCrossReferenceRow(final WorkingStorage state) {
        try {
            // One keyed READ of the alternate-index path, expressed as the repository's ordered-first
            // finder: bounded to one row and ordered on the base key, which is the row that read returns.
            final Optional<CardCrossReference> located = cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(state.readAccountKey);
            state.cardCrossReference = located.orElse(null);
            return located.isPresent() ? ReadOutcome.FOUND : ReadOutcome.NOT_FOUND;
        } catch (final DataAccessException readFailure) {
            logReadFailure(CARD_XREF_ACCOUNT_PATH_NAME, readFailure);
            return ReadOutcome.READ_ERROR;
        }
    }

    /** Terminator of the cross-reference read range: {@code 9200-GETCARDXREF-BYACCT-EXIT}, line 771. */
    private static void getCardXrefByAcctExit() {
        // EXIT. No body in the source.
    }

    /**
     * Reads the account master by account identifier. Paragraph {@code 9300-GETACCTDATA-BYACCT}, line 774.
     *
     * <p>The statement at lines 776 to 784 reads the account master on the same key the cross-reference
     * read used, which is why the key is prepared once at line 691 and not twice. Identity is the
     * eleven-character business key itself; no surrogate identifier exists anywhere in this module, so the
     * key that keyed the legacy cluster keys the table.
     *
     * <p>The three-arm decision at lines 786 to 819 keeps its clause order, with the final arm as the
     * default. Found raises the account-master-found flag at line 788, which is half of the presentation
     * guard the variable step applies. Not found raises the error flag and the account filter flag and
     * composes its own miss text under the message-is-off guard - and note what it does <em>not</em> do:
     * the statement that would have set the condition name the caller tests at line 704 is commented out at
     * line 792, which is what makes that caller's guard permanently false. The default arm composes the
     * read-error text and does not abend.
     */
    private void getAcctDataByAcct(final WorkingStorage state) {
        state.operationInFlight = READ_OPERATION;
        state.resourceInFlight = ACCOUNT_FILE_NAME;
        final ReadOutcome outcome = readAccountRow(state);
        switch (outcome) {
            case FOUND -> state.foundAcctInMaster = true;
            case NOT_FOUND -> {
                state.rawFileStatus = RecordNotFoundException.STATUS_RECORD_NOT_FOUND;
                state.inputFlag = InputFlag.ERROR;
                state.accountFilterFlag = FilterFlag.NOT_OK;
                if (isReturnMessageOff(state.returnMessage)) {
                    state.errorResp = fieldImage(state.rawFileStatus, RESPONSE_CODE_WIDTH);
                    state.errorResp2 = fieldImage("", RESPONSE_CODE_WIDTH);
                    state.returnMessage = accountMasterMissMessage(state);
                }
                LOG.debug("Account view found no account-master row: resource={} fileStatus={}",
                        ACCOUNT_FILE_NAME, state.rawFileStatus);
            }
            default -> {
                state.inputFlag = InputFlag.ERROR;
                state.accountFilterFlag = FilterFlag.NOT_OK;
                state.errorOpName = fieldImage(READ_OPERATION, OPERATION_NAME_WIDTH);
                state.errorFile = fieldImage(ACCOUNT_FILE_NAME, ERROR_FILE_NAME_WIDTH);
                state.returnMessage = fileErrorMessage(state);
            }
        }
        getAcctDataByAcctExit();
    }

    /**
     * Performs the read statement at lines 776 to 784 and classifies its outcome, on the same terms as its
     * cross-reference counterpart: a statement-level helper that owes no traceability row.
     *
     * <p>Identity is the eleven-character account key itself, so the read is the inherited keyed lookup and
     * needs no declared finder.
     */
    private ReadOutcome readAccountRow(final WorkingStorage state) {
        try {
            final Optional<Account> located = accountRepository.findById(state.readAccountKey);
            state.account = located.orElse(null);
            return located.isPresent() ? ReadOutcome.FOUND : ReadOutcome.NOT_FOUND;
        } catch (final DataAccessException readFailure) {
            logReadFailure(ACCOUNT_FILE_NAME, readFailure);
            return ReadOutcome.READ_ERROR;
        }
    }

    /** Terminator of the account-master read range: {@code 9300-GETACCTDATA-BYACCT-EXIT}, line 821. */
    private static void getAcctDataByAcctExit() {
        // EXIT. No body in the source.
    }

    /**
     * Reads the customer master by customer identifier. Paragraph {@code 9400-GETCUSTDATA-BYCUST}, line
     * 825.
     *
     * <p>The statement at lines 826 to 834 reads the customer master on the nine-character identifier the
     * cross-reference row yielded, which is the third and last of the three explicit calls that stand in
     * for a mapped relationship this module does not declare.
     *
     * <p>The three-arm decision at lines 836 to 868 keeps its clause order, with the final arm as the
     * default, and differs from its two siblings in three ways that are all reproduced. Not found raises
     * the <em>customer</em> filter flag at line 841 rather than the account one, which is what
     * distinguishes this miss from the other two on a screen that has only one cursor position. Its two
     * response-code slots are filled at lines 843 to 844 <em>before</em> the message-is-off guard rather
     * than inside it, so they are set even on a turn whose text is suppressed. And its reason phrase is
     * upper case where the other two are not.
     */
    private void getCustDataByCust(final WorkingStorage state) {
        state.operationInFlight = READ_OPERATION;
        state.resourceInFlight = CUSTOMER_FILE_NAME;
        final ReadOutcome outcome = readCustomerRow(state);
        switch (outcome) {
            case FOUND -> state.foundCustInMaster = true;
            case NOT_FOUND -> {
                state.rawFileStatus = RecordNotFoundException.STATUS_RECORD_NOT_FOUND;
                state.inputFlag = InputFlag.ERROR;
                state.customerFilterFlag = FilterFlag.NOT_OK;
                state.errorResp = fieldImage(state.rawFileStatus, RESPONSE_CODE_WIDTH);
                state.errorResp2 = fieldImage("", RESPONSE_CODE_WIDTH);
                if (isReturnMessageOff(state.returnMessage)) {
                    state.returnMessage = customerMasterMissMessage(state);
                }
                LOG.debug("Account view found no customer-master row: resource={} fileStatus={}",
                        CUSTOMER_FILE_NAME, state.rawFileStatus);
            }
            default -> {
                state.inputFlag = InputFlag.ERROR;
                state.customerFilterFlag = FilterFlag.NOT_OK;
                state.errorOpName = fieldImage(READ_OPERATION, OPERATION_NAME_WIDTH);
                state.errorFile = fieldImage(CUSTOMER_FILE_NAME, ERROR_FILE_NAME_WIDTH);
                state.returnMessage = fileErrorMessage(state);
            }
        }
        getCustDataByCustExit();
    }

    /**
     * Performs the read statement at lines 826 to 834 and classifies its outcome, on the same terms as its
     * two counterparts: a statement-level helper that owes no traceability row.
     *
     * <p>The key is the nine-character customer identifier the cross-reference row yielded, which is why an
     * absent identifier here means the cross-reference read did not run or did not find a row - and why the
     * key is prepared immediately before the call rather than at the top of the resolution.
     */
    private ReadOutcome readCustomerRow(final WorkingStorage state) {
        try {
            final Optional<Customer> located = customerRepository.findById(state.readCustomerKey);
            state.customer = located.orElse(null);
            return located.isPresent() ? ReadOutcome.FOUND : ReadOutcome.NOT_FOUND;
        } catch (final DataAccessException readFailure) {
            logReadFailure(CUSTOMER_FILE_NAME, readFailure);
            return ReadOutcome.READ_ERROR;
        }
    }

    /** Terminator of the customer-master read range: {@code 9400-GETCUSTDATA-BYCUST-EXIT}, line 870. */
    private static void getCustDataByCustExit() {
        // EXIT. No body in the source.
    }

    /**
     * Ends the turn with plain text instead of the screen. Paragraph {@code SEND-PLAIN-TEXT}, line 877, whose
     * own comment warns that it is not for production use.
     *
     * <p>The statements at lines 878 to 886 transmit the 75-character message field and then return
     * <em>without</em> re-arming a transaction, so the conversation ends rather than continuing. That is why
     * this path resolves no route: there is no next turn for a route to name, and the result reports the
     * absence rather than substituting this screen's own destination.
     */
    private AccountViewResult sendPlainText(final WorkingStorage state) {
        state.presentation = Presentation.PLAIN_TEXT;
        state.errorMessage = state.returnMessage;
        final AccountViewResult outcome = buildResult(null, state);
        sendPlainTextExit();
        return outcome;
    }

    /** Terminator of the plain-text range: {@code SEND-PLAIN-TEXT-EXIT}, line 888. */
    private static void sendPlainTextExit() {
        // EXIT. No body in the source.
    }

    /**
     * Ends the turn with the long diagnostic field instead of the screen. Paragraph {@code SEND-LONG-TEXT},
     * line 896, whose own comment states it is for debugging and not for regular use.
     *
     * <p><strong>This paragraph has no live caller.</strong> Both statements that would have performed it
     * are commented out - at line 768 in the cross-reference read-error arm and at line 818 in the
     * account-master arm, with a third at line 867 in the customer arm - as is the statement that would have
     * filled the field it transmits. It is retained as a defined-but-unwired unit for the same reason the
     * action plan retains the batch program that no job stream invokes: a paragraph that exists owes a
     * method, and deleting it would make the traceability count dishonest. It is visible to tests rather
     * than published, which is what "exercised by tests rather than by a live path" means in practice.
     *
     * <p>The statements at lines 897 to 905 transmit the 500-character field and return without re-arming,
     * so like its plain-text sibling this path resolves no route.
     */
    AccountViewResult sendLongText(final WorkingStorage state) {
        state.presentation = Presentation.LONG_TEXT;
        final AccountViewResult outcome = buildResult(null, state);
        sendLongTextExit();
        return outcome;
    }

    /** Terminator of the long-text range: {@code SEND-LONG-TEXT-EXIT}, line 907. */
    private static void sendLongTextExit() {
        // EXIT. No body in the source.
    }

    /**
     * The registered abend handler. Paragraph {@code ABEND-ROUTINE}, line 916, reached through the
     * registration at lines 264 to 266.
     *
     * <p><strong>Emit first, then raise.</strong> The legacy defaults its terminal message at lines 918 to
     * 920, names this member as the culprit at line 922, <em>transmits</em> the abend structure at lines 924
     * to 928, deregisters itself at lines 930 to 932, and only then abends with code {@code 9999} at line
     * 934. The ordering is the contract, so this method logs before it delegates and never the other way
     * round: a diagnostic written after the raise would be written by whatever caught the raise, if
     * anything did.
     *
     * <p>What the diagnostic says is bounded deliberately. The raw two-character file status is included
     * when one is in play - the reads that miss record theirs - along with the resource name of the read in
     * flight, the culprit and the shape of the failure chain. It never includes a customer record, the
     * national identifier, the government-issued identifier, a card number or the text of the failure's
     * own message, because a message this module did not author may carry values it did not author either;
     * the shared failure renderer publishes the chain of type names and withholds the rest.
     *
     * <p>Deregistration has no counterpart to write, but it has a consequence that is honoured: an abend
     * already in flight must not be handled a second time, which is why the caller rethrows an abend
     * unchanged instead of routing it back through here. The four occurrences of the deregistration keyword
     * in the estate are all this CICS construct and never the COBOL statement of the same name, so there is
     * no cancel behaviour to translate.
     *
     * <p><strong>The default message the handler appears to supply is never supplied.</strong> Its test at
     * line 918 compares the message field against the low value, while that field's declaration gives it
     * spaces and the initialising statement does not reach the abend structure, so the comparison never
     * matches and the blank field is what is transmitted. The test is reproduced as written rather than
     * loosened into a blank test, because loosening it would substitute a text the legacy never sends. The
     * substitution text is published all the same, and it happens to be the very literal this module's own
     * abend default carries, so the two agree by coincidence rather than by design.
     *
     * <p>The method returns the failure it was given so its caller can throw something on every path. That
     * return is defensive rather than expected: the abend service raises, so reaching the return would mean
     * the raiser had stopped raising, and swallowing the original failure at that point would be worse than
     * any alternative.
     */
    private RuntimeException abendRoutine(final RuntimeException failure, final WorkingStorage state) {
        final String terminalMessage = isAllLowValues(state.abendMessage)
                ? ABEND_TERMINAL_MESSAGE
                : state.abendMessage;
        LOG.error("ABENDING PROGRAM abendCode={} culprit={} reason={} fileStatus={} operation={}"
                        + " resource={} failure={}",
                AbendException.ONLINE_ABEND_CODE,
                PROGRAM_NAME,
                ABEND_REASON_UNEXPECTED,
                orAbsent(state.rawFileStatus),
                orAbsent(state.operationInFlight),
                orAbsent(state.resourceInFlight),
                FailureDiagnostics.failureChainOf(failure));
        abendService.abendOnline(PROGRAM_NAME, ABEND_REASON_UNEXPECTED, terminalMessage);
        return failure;
    }

    /**
     * Records a read that failed for a reason other than a missing row - the default arm of all three read
     * decisions.
     *
     * <p>Emitted at error level because the legacy composes a screen text naming the failing operation and
     * resource, which is a diagnostic in everything but destination. No two-character file status
     * accompanies it: the legacy slot holds a CICS response pair, which the relational store has no
     * analogue for, so nothing is fabricated to fill it. The failure itself is rendered by the shared
     * renderer, which publishes the chain of type names and withholds every message in it.
     */
    private static void logReadFailure(final String resourceName, final DataAccessException failure) {
        LOG.error("Account view read failed: operation={} resource={} failure={}",
                READ_OPERATION, resourceName, FailureDiagnostics.failureChainOf(failure));
    }

    /**
     * Composes the cross-reference miss text assembled at lines 747 to 757.
     *
     * <p>Seven segments concatenated in that order: the eight-character prefix, the eleven-character account
     * identifier, the thirteen-character middle, the twenty-three-character resource phrase, the ten-character
     * response slot, the six-character reason phrase and the second ten-character response slot. Eighty-one
     * characters into a seventy-five-character field, so the legacy assembly overflows and the last six
     * characters never reach the screen. The overflow is reproduced rather than avoided: the field width is
     * applied by the same helper every message here passes through, so the truncation is arithmetic and
     * cannot drift.
     *
     * <p>The two response slots hold a CICS response and reason pair in the legacy, which the relational
     * store has no counterpart for. The first carries the migration's own two-character not-found status
     * instead, which is the value that slot's purpose calls for, and the second is left at the width and
     * blank value its declaration gives it rather than being filled with an invented reason code.
     */
    private static String crossReferenceMissMessage(final WorkingStorage state) {
        return fieldImage(MISS_ACCOUNT_PREFIX
                + fieldImage(state.readAccountKey, ACCOUNT_ID_WIDTH)
                + MISS_ACCOUNT_MIDDLE
                + MISS_XREF_RESOURCE_PHRASE
                + state.errorResp
                + MISS_ACCOUNT_REASON_PHRASE
                + state.errorResp2, RETURN_MESSAGE_WIDTH);
    }

    /**
     * Composes the account-master miss text assembled at lines 796 to 806, which differs from its
     * cross-reference sibling in one segment only - the resource phrase - and overflows the same
     * seventy-five-character field from the same eighty-one characters.
     */
    private static String accountMasterMissMessage(final WorkingStorage state) {
        return fieldImage(MISS_ACCOUNT_PREFIX
                + fieldImage(state.readAccountKey, ACCOUNT_ID_WIDTH)
                + MISS_ACCOUNT_MIDDLE
                + MISS_ACCOUNT_RESOURCE_PHRASE
                + state.errorResp
                + MISS_ACCOUNT_REASON_PHRASE
                + state.errorResp2, RETURN_MESSAGE_WIDTH);
    }

    /**
     * Composes the customer-master miss text assembled at lines 846 to 856.
     *
     * <p>Seven segments again, but every one of the fixed ones differs from its account counterparts and the
     * identifier is nine characters rather than eleven: seventy-eight characters into the same
     * seventy-five-character field, so this text loses three rather than six. The reason phrase is upper
     * case here alone.
     */
    private static String customerMasterMissMessage(final WorkingStorage state) {
        return fieldImage(MISS_CUSTOMER_PREFIX
                + fieldImage(state.readCustomerKey, CUSTOMER_ID_WIDTH)
                + MISS_CUSTOMER_MIDDLE
                + MISS_CUSTOMER_RESOURCE_PHRASE
                + state.errorResp
                + MISS_CUSTOMER_REASON_PHRASE
                + state.errorResp2, RETURN_MESSAGE_WIDTH);
    }

    /**
     * Composes the read-error text from the fixed structure declared at lines 86 to 105 and moved into the
     * message field by all three default arms.
     *
     * <p>Nine parts: a twelve-character prefix, the eight-character operation, a four-character joiner, the
     * nine-character resource, a fifteen-character phrase, the ten-character response slot, a
     * seven-character phrase, the second ten-character response slot and a five-space trailer. Eighty
     * characters moved into a seventy-five-character field, so the move truncates on the right and the
     * trailer is what disappears. Both response slots stay blank on this path, for the reason given on the
     * cross-reference miss text.
     */
    private static String fileErrorMessage(final WorkingStorage state) {
        return fieldImage(FILE_ERROR_PREFIX
                + state.errorOpName
                + FILE_ERROR_ON
                + state.errorFile
                + FILE_ERROR_RETURNED_RESP
                + state.errorResp
                + FILE_ERROR_RESP2
                + state.errorResp2
                + FILE_ERROR_TRAILER, RETURN_MESSAGE_WIDTH);
    }

    /**
     * Assembles the result this turn hands back.
     *
     * <p>Every value here is one the legacy places somewhere observable: the route replaces a transfer or a
     * re-arm, the carried state replaces the communication area, the header and the two message fields
     * replace map fields, the focus field replaces the cursor position, the error flag is the input flag and
     * the re-entry flag is the context the next turn will read. The two entities are carried rather than
     * flattened, because flattening them into fixed-width screen slots is the presentation layer's work and
     * because this module declares no association that could make either of them a lazy proxy - so there is
     * nothing here that can fail to initialise once the transaction has closed.
     *
     * <p>The published error flag is the legacy input flag <em>or</em> the migration-only unmapped-key
     * condition. Combining them here rather than in the flag itself is what lets the legacy flag keep its
     * own semantics - the edit paragraph sets it in order at line 624 and would otherwise clear a condition
     * the legacy has no counterpart for.
     *
     * @param route the resolved destination, or {@code null} on the two paths that end the conversation
     *              without re-arming anything
     */
    private static AccountViewResult buildResult(final String route, final WorkingStorage state) {
        return new AccountViewResult(
                route,
                state.commarea,
                state.screenHeader,
                state.presentation,
                state.account,
                state.customer,
                state.echoedAccountId,
                state.errorMessage,
                state.infoMessage,
                state.focusScreenFieldId,
                state.inputFlag.isError() || state.attentionKeyUnmapped,
                state.commarea.reEntry(),
                state.accountFilterFlag,
                state.customerFilterFlag,
                state.foundAcctInMaster,
                state.foundCustInMaster,
                state.accountFieldsPresented,
                state.customerFieldsPresented,
                state.filterInError,
                state.filterMissingOnReEntry,
                state.informationalFieldDarkened,
                state.longMessage);
    }

    /**
     * Renders a value at a fixed field width: space padded when it is shorter, truncated on the right when
     * it is longer, and a field of spaces when it is absent.
     *
     * <p>This is the one place a width is applied, which is what makes every message in this class reach its
     * declared width arithmetically rather than by counted whitespace, and what makes the four legacy
     * overflows reproduce themselves instead of needing to be described. It performs no offset extraction
     * and takes no substring: a record image is sliced by the record mappers in the utility layer and never
     * here. Screen field widths, by contrast, are this screen's own contract.
     *
     * @param value the value to render, or {@code null} for an absent one
     * @param width the field's declared width, which must not be negative
     * @return a string of exactly {@code width} characters
     */
    private static String fieldImage(final String value, final int width) {
        final char[] field = new char[width];
        for (int position = 0; position < width; position++) {
            field[position] = SPACE;
        }
        if (value != null) {
            final int transferred = Math.min(value.length(), width);
            value.getChars(0, transferred, field, 0);
        }
        return new String(field);
    }

    /** Renders a field of zero digits at the given width: the effect of moving zeros into a numeric field. */
    private static String zeroFilled(final int width) {
        final char[] field = new char[width];
        for (int position = 0; position < width; position++) {
            field[position] = ASCII_ZERO;
        }
        return new String(field);
    }

    /**
     * Reports whether a fixed-width field is blank in the legacy sense: absent, empty, wholly spaces or
     * wholly low values.
     *
     * <p>This is deliberately narrower than the conventional emptiness test. A fixed-width field holds
     * padding, and padding is a space or a low value and nothing else, so a field containing a tab or a line
     * feed is <em>not</em> blank - it holds data this screen did not expect. Widening the test would accept
     * values the legacy comparison rejects.
     */
    private static boolean isFieldBlank(final String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int position = 0; position < value.length(); position++) {
            final char character = value.charAt(position);
            if (character != SPACE && character != LOW_VALUE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a field is wholly spaces, which is the narrower of the two halves of the blank test.
     *
     * <p>Needed separately because the normalisation at lines 628 to 629 tests the received field against
     * spaces alone, while the edit at lines 653 to 654 tests against spaces or low values. An absent field
     * is not spaces.
     */
    private static boolean isAllSpaces(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) != SPACE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether every character of a field is an ASCII digit - the numeric test the edit applies at
     * line 666, negated by its caller.
     *
     * <p>Written against the ASCII range rather than the library digit predicate on purpose: that predicate
     * also accepts digit code points outside the ASCII range, which a single-byte fixed-width field cannot
     * hold and which the legacy numeric test would never have read as numeric. An absent or empty field is
     * not numeric.
     */
    private static boolean isAllAsciiDigits(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int position = 0; position < value.length(); position++) {
            final char character = value.charAt(position);
            if (character < ASCII_ZERO || character > ASCII_NINE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a numeric field holds nothing but zero digits - the zero test the edit applies at line
     * 667, on a field its caller has already established is numeric.
     *
     * <p>Expressed as a digit test rather than by parsing, so an eleven-digit identifier needs no numeric
     * type wide enough to hold it and no arithmetic is introduced where the source performs none.
     */
    private static boolean isAllZeroDigits(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) != ASCII_ZERO) {
                return false;
            }
        }
        return true;
    }

    /**
     * Strips the padding from a fixed-width field so its content can be compared, returning {@code null} for
     * a field that holds only padding.
     *
     * <p>Padding is a trailing space or low value and nothing else, for the reason given on the blank test.
     * Nothing is stripped from the front, and no other character is touched - which is why this is safe to
     * apply to a program name and is applied to no value whose padding is data. The account group identifier
     * is ten spaces in every reference row and is never passed through here, because for that field the
     * spaces <em>are</em> the value.
     */
    private static String stripFieldPadding(final String value) {
        if (value == null) {
            return null;
        }
        int end = value.length();
        while (end > 0) {
            final char character = value.charAt(end - 1);
            if (character != SPACE && character != LOW_VALUE) {
                break;
            }
            end--;
        }
        if (end == 0) {
            return null;
        }
        final char[] content = new char[end];
        value.getChars(0, end, content, 0);
        return new String(content);
    }

    /**
     * Reports whether the message field is in its off state - the condition name declared on that field at
     * line 118, tested at lines 657, 670, 744, 793 and 845.
     *
     * <p>It is a value test and not a flag test, which is what gives the legacy its first-writer-wins
     * behaviour: once any path has written a text, every later guarded path leaves it alone.
     */
    private static boolean isReturnMessageOff(final String returnMessage) {
        return isFieldBlank(returnMessage);
    }

    /**
     * Reports whether the informational field holds nothing - the condition name declared at lines 111 to
     * 112 and tested at lines 528 and 567.
     *
     * <p>That name carries two values, spaces and low values, so both satisfy it. Assigning it takes the
     * first, which is why the off constant published above is the space form.
     */
    private static boolean isNoInfoMessage(final String infoMessage) {
        return isFieldBlank(infoMessage);
    }

    /** Renders a value for a diagnostic, substituting a fixed stand-in for an absent one. */
    private static String orAbsent(final String value) {
        return (value == null) ? ABSENT_VALUE_SUBSTITUTE : value;
    }

    /**
     * Records the account identifier into the carried state, reproducing the three assignments at lines 660,
     * 675 and 678.
     *
     * <p>The legacy writes straight into the communication area, which is a field of its working storage. The
     * carried state here is immutable, so the write becomes a rebuild of it - which is why this helper
     * exists rather than a setter.
     */
    private static ScreenNavigationState withCarriedAccountId(final ScreenNavigationState context,
            final String accountId) {
        return rebuildCarriedState(context, accountId, context.customerId(), context.cardNumber());
    }

    /**
     * Records the customer identifier and the card number into the carried state, reproducing the two
     * assignments at lines 739 and 740.
     *
     * <p>The card number is written and never read again inside this member, and it is written all the same
     * because the carried state goes back to the client, which echoes it on the next turn. It is the only
     * route by which a card number reaches this transaction, the card table having no access path here.
     */
    private static ScreenNavigationState withCarriedCustomerAndCard(final ScreenNavigationState context,
            final String customerId, final String cardNumber) {
        return rebuildCarriedState(context, context.accountId(), customerId, cardNumber);
    }

    /**
     * Rebuilds the carried state with three of its sixteen fields replaced and the other thirteen carried
     * through unchanged.
     *
     * <p>One rebuild point rather than one per assignment, so a field can never be dropped by an edit to one
     * caller and preserved by another. The account status field is among the thirteen: this member declares
     * it through its copybook and assigns it nowhere, so it is carried and not written.
     */
    private static ScreenNavigationState rebuildCarriedState(final ScreenNavigationState context,
            final String accountId, final String customerId, final String cardNumber) {
        return new ScreenNavigationState(
                context.fromTransactionId(),
                context.fromProgram(),
                context.toTransactionId(),
                context.toProgram(),
                context.userId(),
                context.userType(),
                context.programContext(),
                customerId,
                context.customerFirstName(),
                context.customerMiddleName(),
                context.customerLastName(),
                accountId,
                context.accountStatus(),
                cardNumber,
                context.lastMap(),
                context.lastMapset());
    }

    /**
     * Reports whether a fixed-width field holds nothing but the low value - a narrower test than the blank
     * test above, and the one the abend handler applies at line 918.
     *
     * <p>The distinction matters exactly once and it matters a great deal. The handler tests its message
     * field against the low value, while that field's declaration in the abend copybook gives it spaces and
     * the initialising statement at lines 268 to 270 does not include the abend structure. The field
     * therefore holds spaces, the test does not match, and <strong>the default text the handler appears to
     * substitute is never substituted</strong> - what reaches the terminal is the blank field. Reproducing
     * the test as a blank test would silently substitute a message the legacy never sends, which is why the
     * two tests are separate methods rather than one.
     *
     * <p>An absent field is treated as the low-value state, since absence is how this class models it
     * everywhere else.
     */
    private static boolean isAllLowValues(final String value) {
        if (value == null) {
            return true;
        }
        if (value.isEmpty()) {
            return false;
        }
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) != LOW_VALUE) {
                return false;
            }
        }
        return true;
    }

    /**
     * The outcome of one turn of this transaction: everything the legacy left somewhere a terminal or the
     * next turn could observe, and nothing else.
     *
     * <p>It carries no response entity, no status code and no rendered screen. Flattening these values into
     * fixed-width screen fields, formatting the national identifier as the legacy formats it, colouring a
     * field in error and writing the asterisk marker all belong to the presentation layer.
     *
     * <p>Two entities are carried rather than copied field by field. That is safe because this module
     * declares no association anywhere, so neither is a lazy proxy and neither can fail to initialise after
     * its repository call has completed. Both may be absent, and absence is not an error: it is what a miss
     * looks like.
     */
    public record AccountViewResult(
            /* The destination, replacing a transfer or a re-arm. Absent on the two paths that end the
             * conversation without re-arming: the plain-text and long-text exits. */
            String route,

            /* The carried state the client echoes on the next turn, replacing the communication area. */
            ScreenNavigationState navigationContext,

            /* The six header fields the initialisation paragraph populates. Absent on the transfer path,
             * which sends no screen. */
            ScreenHeader screenHeader,

            /* What this turn produced: a screen, plain text, the long diagnostic field, or a transfer. */
            Presentation presentation,

            /* The account row, or absent when the account master held none. */
            Account account,

            /* The customer row, or absent when the customer master held none. */
            Customer customer,

            /* The filter field as echoed back, blanked when the filter flag is blank, at its declared
             * eleven-character width. */
            String accountIdFilter,

            /* The 75-character message field the screen shows, never trimmed. */
            String errorMessage,

            /* The 40-character informational field, a separate slot from the message above. */
            String infoMessage,

            /* The field the cursor sits on: always the filter field, since all three arms of the legacy
             * cursor decision position it identically and it is the screen's only input. */
            String focusScreenFieldId,

            /* The input flag, raised by every rejected edit and every missed read. */
            boolean errorFlag,

            /* Whether the next turn will be a re-entry, which is what makes field-level error presentation
             * conditional across this family of screens. */
            boolean reEnter,

            /* The account filter flag: raised out of order by a rejected filter and by the cross-reference
             * and account-master misses. */
            FilterFlag accountFilterFlag,

            /* The customer filter flag, raised only by the customer-master miss - which is how that miss is
             * told apart from the other two on a screen with one cursor position. */
            FilterFlag customerFilterFlag,

            /* Whether the account master returned a row. */
            boolean accountFoundInMaster,

            /* Whether the customer master returned a row. */
            boolean customerFoundInMaster,

            /* Whether the legacy would have presented the account field group: true when either master
             * returned a row, which is the guard as written and not as one might expect it. */
            boolean accountFieldsPresented,

            /* Whether the legacy would have presented the customer field group: the customer master
             * alone. */
            boolean customerFieldsPresented,

            /* Whether the filter field is in error, which the presentation layer renders as a colour
             * change. */
            boolean filterInError,

            /* Whether the filter is missing on a re-entry, which the presentation layer renders as the
             * asterisk marker as well as a colour change - the two-state distinction between a field that is
             * absent and a field that is merely wrong. */
            boolean filterMissingOnReEntry,

            /* Whether the informational field holds nothing, which the presentation layer renders by
             * darkening it. */
            boolean informationalFieldDarkened,

            /* The 500-character long diagnostic field, transmitted only by the unwired long-text exit. */
            String longMessage) {

        /**
         * Returns the destination with absence made explicit, since two paths resolve none.
         *
         * @return the route, or an empty result on a path that re-arms nothing
         */
        public Optional<String> resolvedRoute() {
            return Optional.ofNullable(route);
        }

        /**
         * Returns the account row with absence made explicit.
         *
         * @return the account, or an empty result when the account master held none
         */
        public Optional<Account> resolvedAccount() {
            return Optional.ofNullable(account);
        }

        /**
         * Returns the customer row with absence made explicit.
         *
         * @return the customer, or an empty result when the customer master held none
         */
        public Optional<Customer> resolvedCustomer() {
            return Optional.ofNullable(customer);
        }

        /**
         * Renders this result for a diagnostic with every value that identifies a person or an account
         * withheld.
         *
         * <p>The account identifier is replaced by a fixed stand-in and the two entities are reported as
         * present or absent rather than rendered, because between them they carry a national identifier, a
         * government-issued identifier, a date of birth, an address, two telephone numbers, five monetary
         * amounts and a credit score. The carried state renders itself, and it withholds its own identifiers.
         * The flags, the route, the presentation and the focus field are this module's own values and are
         * rendered as they are.
         */
        @Override
        public String toString() {
            return "AccountViewResult["
                    + "route=" + route
                    + ", presentation=" + presentation
                    + ", navigationContext=" + navigationContext
                    + ", account=" + (account == null ? ABSENT_VALUE_SUBSTITUTE : REDACTION_PLACEHOLDER)
                    + ", customer=" + (customer == null ? ABSENT_VALUE_SUBSTITUTE : REDACTION_PLACEHOLDER)
                    + ", accountIdFilter=" + REDACTION_PLACEHOLDER
                    + ", focusScreenFieldId=" + focusScreenFieldId
                    + ", errorFlag=" + errorFlag
                    + ", reEnter=" + reEnter
                    + ", accountFilterFlag=" + accountFilterFlag
                    + ", customerFilterFlag=" + customerFilterFlag
                    + ", accountFoundInMaster=" + accountFoundInMaster
                    + ", customerFoundInMaster=" + customerFoundInMaster
                    + ", accountFieldsPresented=" + accountFieldsPresented
                    + ", customerFieldsPresented=" + customerFieldsPresented
                    + ", filterInError=" + filterInError
                    + ", filterMissingOnReEntry=" + filterMissingOnReEntry
                    + ", informationalFieldDarkened=" + informationalFieldDarkened
                    + "]";
        }
    }

    /**
     * The six header fields the initialisation paragraph populates at lines 436 to 453.
     *
     * <p>The two dates are rendered at the edited widths the shared date copybook declares - two digits, a
     * separator, two digits, a separator, two digits - which is eight characters each.
     */
    public record ScreenHeader(
            /* This screen's transaction identifier, placed on the map at line 438. */
            String transactionName,

            /* This screen's member name, placed on the map at line 439. */
            String programName,

            /* First shared screen title, from the message catalogue, placed at line 436. */
            String title01,

            /* Second shared screen title, from the message catalogue, placed at line 437. */
            String title02,

            /* Current date as month, day and two-digit year, placed at line 447. */
            String currentDate,

            /* Current time as hours, minutes and seconds, placed at line 453. */
            String currentTime) {
    }

    /**
     * The three states of a filter flag, from the condition names declared on the account filter at lines 58
     * to 61 and on the customer filter at lines 62 to 65.
     *
     * <p>Both legacy fields declare the same three values, so one type serves both. The initial state after
     * the initialising statement is a space, which is exactly the blank value, so a fresh turn starts blank
     * rather than in some fourth unnamed state.
     */
    public enum FilterFlag {

        /** Not in order: the value {@code 0}, set by a rejected edge and by a missed read. */
        NOT_OK('0'),

        /** Valid: the value {@code 1}, set only when an edit succeeds. */
        VALID('1'),

        /** Blank: the space, which is both a declared value and the state initialisation leaves. */
        BLANK(' ');

        private final char code;

        FilterFlag(final char code) {
            this.code = code;
        }

        /**
         * Returns the single character the legacy field holds for this state.
         *
         * @return the declared value of this state
         */
        public char getCode() {
            return code;
        }

        /**
         * Reports the not-in-order condition name.
         *
         * @return {@code true} for the out-of-order state
         */
        public boolean isNotOk() {
            return this == NOT_OK;
        }

        /**
         * Reports the valid condition name.
         *
         * @return {@code true} for the valid state
         */
        public boolean isValid() {
            return this == VALID;
        }

        /**
         * Reports the blank condition name.
         *
         * @return {@code true} for the blank state
         */
        public boolean isBlank() {
            return this == BLANK;
        }
    }

    /**
     * The states of the input flag, from the condition names declared at lines 50 to 53.
     *
     * <p>The pending name is declared for the low value, while the initialising statement leaves a space, so
     * two different characters describe the same not-yet-decided state. Both are modelled by one constant
     * because the distinction is unobservable: the pending name is referenced by no statement in the member,
     * and it is in any case declared twice - once here and once on the key flag at line 57 - which would make
     * an unqualified reference to it ambiguous.
     */
    public enum InputFlag {

        /** In order: the value {@code 0}, set at the start of the edit paragraph. */
        OK('0'),

        /** In error: the value {@code 1}, set by every rejected edit and every missed read. */
        ERROR('1'),

        /** Not yet decided: the declared low value, and equally the space initialisation leaves. */
        PENDING(Character.MIN_VALUE);

        private final char code;

        InputFlag(final char code) {
            this.code = code;
        }

        /**
         * Returns the single character the legacy field holds for this state.
         *
         * @return the declared value of this state
         */
        public char getCode() {
            return code;
        }

        /**
         * Reports the in-order condition name.
         *
         * @return {@code true} for the in-order state
         */
        public boolean isOk() {
            return this == OK;
        }

        /**
         * Reports the in-error condition name, which is the one the dispatch above tests.
         *
         * @return {@code true} for the in-error state
         */
        public boolean isError() {
            return this == ERROR;
        }

        /**
         * Reports the not-yet-decided state.
         *
         * @return {@code true} before any edit has run
         */
        public boolean isPending() {
            return this == PENDING;
        }
    }

    /**
     * The states of the attention-key flag, from the condition names declared at lines 54 to 57.
     *
     * <p>The flag is set out of order unconditionally at line 306 and raised to valid only for the two keys
     * this screen accepts, so its pending state exists in the declaration rather than in the flow.
     */
    public enum KeyValidity {

        /** Accepted: the value {@code 0}, for the enter key and the third program-function key alone. */
        VALID('0'),

        /** Not accepted: the value {@code 1}, which coerces the action to the enter key. */
        INVALID('1'),

        /** Not yet decided: the declared low value, overwritten before it is ever tested. */
        PENDING(Character.MIN_VALUE);

        private final char code;

        KeyValidity(final char code) {
            this.code = code;
        }

        /**
         * Returns the single character the legacy field holds for this state.
         *
         * @return the declared value of this state
         */
        public char getCode() {
            return code;
        }

        /**
         * Reports the accepted state.
         *
         * @return {@code true} when the key is one of the two this screen accepts
         */
        public boolean isValid() {
            return this == VALID;
        }

        /**
         * Reports the not-accepted state, which is the one that drives the coercion.
         *
         * @return {@code true} when the key is neither accepted key
         */
        public boolean isInvalid() {
            return this == INVALID;
        }

        /**
         * Reports the not-yet-decided state.
         *
         * @return {@code true} before the gate has run
         */
        public boolean isPending() {
            return this == PENDING;
        }
    }

    /**
     * The three outcomes of a read, from the three arms every read decision in this member declares.
     *
     * <p>Deliberately three rather than two. Collapsing a missing row and a failed read into one outcome
     * would erase the distinction the legacy draws between them - a miss composes one text and a failure
     * composes another naming the operation and the resource - and it is the same distinction the batch tier
     * depends on to tell end of file from error.
     */
    public enum ReadOutcome {

        /** A row was returned: the normal arm. */
        FOUND,

        /** No row matched: the not-found arm, which is a screen message and never an exception. */
        NOT_FOUND,

        /** The read failed for another reason: the final arm, which does not abend. */
        READ_ERROR;

        /**
         * Reports the normal outcome.
         *
         * @return {@code true} when a row was returned
         */
        public boolean isFound() {
            return this == FOUND;
        }

        /**
         * Reports the not-found outcome.
         *
         * @return {@code true} when no row matched
         */
        public boolean isNotFound() {
            return this == NOT_FOUND;
        }

        /**
         * Reports the failed outcome.
         *
         * @return {@code true} when the read failed for a reason other than a missing row
         */
        public boolean isReadError() {
            return this == READ_ERROR;
        }
    }

    /** What a turn produced, which is the choice the legacy makes between four terminal statements. */
    public enum Presentation {

        /** The screen was assembled and sent, and the conversation continues. */
        MAP,

        /** Plain text was sent and the conversation ended without a re-arm. */
        PLAIN_TEXT,

        /** The long diagnostic field was sent and the conversation ended: the unwired debugging exit. */
        LONG_TEXT,

        /** Control was handed to another screen, so this one sent nothing. */
        TRANSFER
    }

    /** The four arms of the main decision at lines 323 to 383, in the source's clause order. */
    private enum MainDispatch {

        /** The third program-function key: return to the calling screen. */
        BACK_NAVIGATION,

        /** Arriving from another context: gather selection criteria. */
        FIRST_ENTRY,

        /** Returning to this screen: process what was received. */
        RE_ENTRY,

        /** Neither context: the unexpected-data arm. */
        UNEXPECTED
    }

    /** The three arms of the cursor decision at lines 546 to 552, which all position the cursor alike. */
    private enum CursorArm {

        /** The filter flag is not in order. */
        FILTER_NOT_OK,

        /** The filter flag is blank. */
        FILTER_BLANK,

        /** Anything else. */
        OTHER
    }

    /**
     * The working storage of one turn.
     *
     * <p>The legacy declares its state in a working-storage section that persists for the life of a task,
     * and every paragraph reads and writes it. Reproducing that as method parameters would give several
     * paragraphs more than a dozen arguments each and would obscure exactly the thing the translation is
     * meant to preserve, so the section is modelled as one holder created per invocation instead. Because it
     * is a local of the entry method and never a field of the service, two concurrent turns cannot see each
     * other's state and the bean itself stays stateless.
     *
     * <p>Every initial value below is the one the legacy holds after its initialising statement at lines 268
     * to 270, which sets alphanumeric items to spaces and numeric items to zero. That is why both filter
     * flags start blank - a space is precisely the blank value they declare - and why the message and
     * informational fields start at their declared widths in spaces rather than absent.
     *
     * <p>Visible to tests in the same package and to nothing beyond it, so the unwired long-text exit can be
     * exercised without publishing either it or this holder.
     */
    static final class WorkingStorage {

        /**
         * This screen's transaction identifier, stored at line 274.
         *
         * <p>Written once and read by nothing, which is faithful rather than careless: the legacy field is
         * referenced exactly twice in the whole member, by its own declaration at line 44 and by that single
         * assignment. It is modelled so the assignment has somewhere to go and so a reader can see that the
         * legacy never consults it either.
         */
        private String transactionId;

        /** Whether the turn arrived with no carried state, tested at lines 282 and 462. */
        private boolean commareaAbsent;

        /** The carried state, which the legacy holds in working storage through its copybook inclusion. */
        private ScreenNavigationState commarea = ScreenNavigationState.empty();

        /** The mapped attention key; absent is the low-value state the mapping construct can leave. */
        private KeyAction keyAction;

        /** Whether the raw attention-key identifier matched no arm of the mapping construct. */
        private boolean attentionKeyUnmapped;

        /** The attention-key flag, set out of order at line 306 before anything tests it. */
        private KeyValidity pfKeyFlag = KeyValidity.PENDING;

        /** The input flag, not yet decided until the first edit runs. */
        private InputFlag inputFlag = InputFlag.PENDING;

        /** The account filter flag, blank because initialisation leaves a space. */
        private FilterFlag accountFilterFlag = FilterFlag.BLANK;

        /** The customer filter flag, blank for the same reason. */
        private FilterFlag customerFilterFlag = FilterFlag.BLANK;

        /** The filter field exactly as received, at its declared width. */
        private String receivedAccountIdField;

        /** The filter field after normalisation; absent is the low-value state line 630 writes. */
        private String accountIdFilter;

        /** The filter field as echoed back to the screen, blanked when the flag is blank. */
        private String echoedAccountId;

        /** The account key both the cross-reference and the account-master read use, prepared at line 691. */
        private String readAccountKey;

        /** The customer key the customer-master read uses, prepared at line 708. */
        private String readCustomerKey;

        /** The cross-reference row, or absent when the access path held none. */
        private CardCrossReference cardCrossReference;

        /** The account row, or absent when the account master held none. */
        private Account account;

        /** The customer row, or absent when the customer master held none. */
        private Customer customer;

        /** Whether the account master returned a row: the condition name at line 83. */
        private boolean foundAcctInMaster;

        /** Whether the customer master returned a row: the condition name at line 85. */
        private boolean foundCustInMaster;

        /** Whether the account field group would be presented: the guard at lines 471 to 472. */
        private boolean accountFieldsPresented;

        /** Whether the customer field group would be presented: the guard at line 493. */
        private boolean customerFieldsPresented;

        /** The message field, off at the start because line 278 switches it off. */
        private String returnMessage = RETURN_MESSAGE_OFF;

        /** The informational field, empty at the start. */
        private String infoMessage = INFO_MESSAGE_OFF;

        /** The long diagnostic field, which nothing in the live flow ever fills. */
        private String longMessage = LONG_MESSAGE_OFF;

        /** The screen's error slot, copied from the message field at lines 395, 532 and 601. */
        private String errorMessage = RETURN_MESSAGE_OFF;

        /**
         * The next program slot, set to this member's own name at line 602.
         *
         * <p>Declarative only: nothing in the estate dispatches on it. Like the two slots below it, it lives
         * in the screen work area rather than in the communication area, and the return at lines 402 to 406
         * carries only the communication area - so these three are written, never read, and never carried.
         * They are modelled because the assignments are real.
         */
        private String nextProgram;

        /** The next mapset slot, set at lines 579 and 603. Declarative only, as above. */
        private String nextMapset;

        /** The next map slot, set at lines 580 and 604. Declarative only, as above. */
        private String nextMap;

        /** The failing operation slot of the read-error structure, at its declared width. */
        private String errorOpName = " ".repeat(OPERATION_NAME_WIDTH);

        /** The failing resource slot of the read-error structure, at its declared width. */
        private String errorFile = " ".repeat(ERROR_FILE_NAME_WIDTH);

        /** The first response-code slot, blank until a read reports something for it. */
        private String errorResp = " ".repeat(RESPONSE_CODE_WIDTH);

        /** The second response-code slot, which nothing in this migration fills. */
        private String errorResp2 = " ".repeat(RESPONSE_CODE_WIDTH);

        /** The six header fields, absent until the screen is initialised. */
        private ScreenHeader screenHeader;

        /** What this turn produced, decided by whichever terminal statement it reaches. */
        private Presentation presentation = Presentation.MAP;

        /** The field the cursor sits on. */
        private String focusScreenFieldId = ACCOUNT_ID_SCREEN_FIELD_ID;

        /** Whether the filter field is in error, for the presentation layer's colour decision. */
        private boolean filterInError;

        /** Whether the filter is missing on a re-entry, for the presentation layer's marker decision. */
        private boolean filterMissingOnReEntry;

        /** Whether the informational field is empty, for the presentation layer's darkening decision. */
        private boolean informationalFieldDarkened = true;

        /**
         * The abend structure's message field, which the handler tests at line 918.
         *
         * <p>Initialised to spaces because that is what its declaration in the abend copybook gives it and
         * because the initialising statement at lines 268 to 270 does not reach that structure. No statement
         * in this member assigns it, so the handler's test against the low value never matches and its
         * default text is never substituted - which is exactly the point of modelling the field rather than
         * assuming the default.
         */
        private String abendMessage = " ".repeat(AbendException.MESSAGE_LENGTH);

        /** The raw two-character file status of the last read that reported one. */
        private String rawFileStatus;

        /** The operation of the read in flight, for the handler's diagnostic. */
        private String operationInFlight;

        /** The resource of the read in flight, for the handler's diagnostic. */
        private String resourceInFlight;

        /** Creates the holder with every field at the value the initialising statement leaves. */
        WorkingStorage() {
            // Field initialisers above carry the initialised state; no further work is required here.
        }
    }

    /**
     * Reports whether this turn carries no prior navigation state - the equivalent of the zero-length
     * communication-area test that opens the legacy program's main paragraph.
     *
     * <p>The test is on the <strong>whole</strong> echoed record and not on its four routing fields
     * alone, because a zero-length communication area describes a turn that carries nothing at all: no
     * signed-on user, no selection and no previous screen. Projecting the record down to the routing
     * fields before testing would call a turn stateless while it still carried an identity or a
     * selection, which is a different condition from the one the legacy branches on.
     *
     * @param context the echoed navigation record, which may be {@code null}
     * @return {@code true} when no navigation state was carried into this turn
     */
    private static boolean isNavigationStateAbsent(final ScreenNavigationState context) {
        return context == null || ScreenNavigationState.empty().equals(context);
    }

    /**
     * Projects the echoed navigation record onto the carried state the navigation rules read.
     *
     * <p>Back-navigation and nominated-destination resolution consult the originating and nominated
     * program names and nothing else, so the projection is loss-free for them: it carries the four
     * routing fields and the program-context flag and drops the identity and selection members, which no
     * routing rule reads. The mapping is the one the transport adapter applies, so a routing decision
     * does not depend on which side of the boundary the record was projected on.
     *
     * @param context the echoed navigation record, which may be {@code null}
     * @return the carried state the navigation rules read, never {@code null}
     */
    private static ConversationState carriedState(final ScreenNavigationState context) {
        if (context == null) {
            return ConversationState.empty();
        }
        return new ConversationState(
                context.fromTransactionId(),
                context.fromProgram(),
                context.toTransactionId(),
                context.toProgram(),
                context.reEntry()
                        ? ConversationState.EntryMode.RE_ENTRY
                        : ConversationState.EntryMode.FIRST_ENTRY);
    }
}
