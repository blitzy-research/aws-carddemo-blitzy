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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.ScreenWorkArea;
import com.carddemo.domain.Card;
import com.carddemo.domain.enums.CardStatus;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import com.carddemo.util.CobolStringUtils;
import com.carddemo.util.PfKeyTranslator;

/**
 * The card-detail screen, transaction {@code CCDL}: one read-only turn that resolves a single card
 * from an account number and a card number and presents it, or explains why it could not.
 *
 * <p>Translated from {@code app/cbl/COCRDSLC.cbl}, 887 lines, at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} (2022-07-19). The screen work area comes from
 * {@code app/cpy/CVCRD01Y.cpy} and the attention-key store from {@code app/cpy/CSSTRPFY.cpy}. No
 * legacy source text is copied into this module; every claim below cites a member and a line.
 *
 * <h2>Paragraph count: 37 measured against 34 in the action plan</h2>
 *
 * <p>Both numbers are right and they count different things, so both are stated rather than one
 * being quietly preferred. <strong>34</strong> paragraph labels are declared literally in this
 * member's own procedure division, from {@code 0000-MAIN} at line 248 to {@code ABEND-ROUTINE} at
 * line 857, and that is the action plan's figure. The <strong>37</strong> of the file specification
 * additionally counts the in-line {@code COPY 'CSSTRPFY'} unit at lines 855 to 856 and the two
 * paragraphs that copybook expands into this same procedure division, {@code YYYY-STORE-PFKEY} at
 * line 17 of the copybook and {@code YYYY-STORE-PFKEY-EXIT} at line 80. 34 + 1 + 2 = 37.
 *
 * <p>This class supplies a named method for all 37, so the traceability matrix resolves under either
 * count. The three copybook-boundary methods <em>delegate</em> to {@code PfKeyTranslator} instead of
 * restating its 28-clause selection: that selection is shared by five members and the matrix credits
 * its two paragraphs to the translator, which is where the fold of the upper program-function keys
 * onto the lower twelve also lives.
 *
 * <h2>Family membership, and why the abend path is wired here</h2>
 *
 * <p>{@code COCRDSLC} belongs to the five-member family - account update, account view, card list,
 * card detail and card update - that alone includes the attention-key copybook and alone registers a
 * CICS abend handler. This member arms that handler at lines 250 to 252 and its handler ends in
 * {@code EXEC CICS ABEND ABCODE('9999')} at <strong>line 875</strong>. The path is therefore wired
 * here because the legacy wired it here, and not because an abend is expected. Every occurrence of
 * the {@code CANCEL} token in the estate is the CICS handler-deregistration option, never the COBOL
 * statement, so there is no cancellation behaviour to translate: the Java equivalent of
 * deregistering a handler is the absence of a {@code catch}.
 *
 * <h2>Two lookup paths, and only one of them is reachable in the legacy</h2>
 *
 * <p>The screen offers an account number and a card number, and the member declares a read for each.
 * Only the card-number read is reachable: {@code 9000-READ-DATA} at line 726 performs
 * {@code 9100-GETCARD-BYACCTCARD} and nothing else, so {@code 9150-GETCARD-BYACCT} at line 779 is
 * <strong>defined and never performed</strong> - an unreachable paragraph, verified by a census of
 * every {@code PERFORM} in the member. It is translated anyway, as
 * {@code getCardByAcct}, and deliberately left unwired; wiring it would add a
 * flow the legacy does not have. This mirrors how the migration treats the estate's one orphaned
 * batch program.
 *
 * <p>A second oddity sits on the reachable read. Despite its name,
 * {@code 9100-GETCARD-BYACCTCARD} keys on the card number alone: the move that would have supplied
 * the account half of the key is commented out at line 739, and the read at lines 742 to 750 keys
 * {@code RIDFLD(WS-CARD-RID-CARDNUM)} against base cluster {@code CARDDAT}. So it becomes the
 * inherited keyed finder, while the unreachable paragraph becomes the account-keyed finder over the
 * alternate index whose resource name the member declares at <strong>line 190</strong>.
 *
 * <p>That alternate index is non-unique, so its Java finder cannot be single-valued by key alone. The
 * declared repository method resolves the ambiguity at the query level - lowest card number first,
 * one row - which is the deterministic form of "take the first". An absent result is the analogue of
 * the legacy not-found response and is not an error.
 *
 * <p><strong>The two not-found outcomes stay distinct, because the source distinguishes them.</strong>
 * The card-number read faults <em>both</em> filter fields and sets its message only if no message has
 * been set yet, lines 755 to 761. The account read faults <em>only</em> the account filter and sets
 * its message <em>unconditionally</em>, lines 796 to 799. Collapsing them into one generic outcome
 * would lose a field flag and a message-precedence rule.
 *
 * <h2>Faithful over idiomatic</h2>
 *
 * <p>Where the two diverge the source wins and the divergence is a decision-log entry. Four
 * divergences are load bearing here.
 *
 * <p><strong>Neither not-found path throws.</strong> The source sets a flag and a message and
 * re-presents the screen; it does not abandon the turn. A caller therefore receives a result whose
 * error flag is raised, never a not-found exception, and this service consequently does not use the
 * no-argument constructor of the module's record-not-found exception.
 *
 * <p><strong>The alphabetic edit lets embedded spaces through.</strong> The legacy idiom blanks the
 * letters and then trims, so a space that was already there is trimmed exactly as a blanked letter
 * is. {@code MARY ANN} passes, and so does {@code Aniya Von}, the embossed name in the first row of
 * {@code app/data/ASCII/carddata.txt}. Any all-letters test is forbidden; the character-class
 * predicate in {@code CobolStringUtils} is used instead.
 *
 * <p><strong>The numeric edit is the COBOL class condition, not a numeric parse.</strong> Lines 665
 * and 706 test {@code IS NOT NUMERIC} on alphanumeric fields, which is true unless every character is
 * a digit. {@code Character#isDigit(char)} is Unicode-aware and would admit digits the class
 * condition rejects, so membership is tested against the ASCII range explicitly.
 *
 * <p><strong>The card verification code stays a bounded three-character string.</strong> A value of
 * {@code 007} is {@code "007"} and is never parsed to a number, never normalised, and
 * <strong>never logged</strong>: the projection that carries it redacts it when rendered, exactly as
 * the entity does.
 *
 * <h2>The exit-paragraph convention</h2>
 *
 * <p>Seventeen of the 34 own paragraphs are {@code EXIT} paragraphs - the common end point of a
 * {@code PERFORM x THRU x-EXIT} range, of which this member has ten, all of the trivial paired kind
 * that becomes a method with an early {@code return}. None of the member's nine forward
 * {@code GO TO} statements jumps backwards, so no loop has to be reconstructed. Each exit paragraph
 * still gets its own named method, because the traceability matrix maps paragraphs to methods one for
 * one; each such method records the completed range at trace level and returns, which is the honest
 * translation of a statement whose only effect is to be a reachable end point.
 *
 * <h2>Boundaries</h2>
 *
 * <p>Read-only by contract: no write, no flush, no version manipulation, no optimistic-lock
 * conflict. No embossed-name folding - that belongs to the card-update service, which folds in place
 * before capturing its old image. No paging and no page size - that belongs to the card-list service.
 * No decimal scaling, no floating-point type, and no conversion of a stored timestamp string. Routes
 * are resolved through {@code NavigationService}; this class declares no route table. Rendering and
 * field-level decoration belong to the response layer, so what leaves here is a value and never a
 * transport object.
 *
 * <p>Stateless and immutable: the working storage the member declares at lines 36 to 158 lives in a
 * per-turn {@code TurnState}, so one container-managed instance is safely shared and two concurrent
 * turns are wholly independent.
 *
 * <p>No user-specified rules were supplied for this engagement - the project's rules document
 * contains only a statement to that effect - so this class is held to enterprise-standard best
 * practice instead: pinned dependencies, a zero-warning build, one-way layering, no code generation
 * or reflection, no secret in source, structured logging with no hardcoded performance figure, the
 * licence header above, and full paragraph-level auditability.
 *
 * <p>This type is deliberately <em>not</em> {@code final}. The {@code @Transactional} methods
 * declared below are advised through a CGLIB subclass proxy, and a final class cannot be
 * subclassed, so declaring this type final makes the application context fail to start with
 * {@code Cannot subclass final class}. The proxy is what applies the declared transaction
 * semantics, so the modifier and the annotation cannot both be present. The sibling services that
 * carry transactional methods are non-final for the same reason, and extension is not invited: the
 * constructor is the only way to build one, every field is final, and no method is designed to be
 * overridden.
 */
@Service
public class CardDetailService {

    private static final Logger LOG = LoggerFactory.getLogger(CardDetailService.class);

    // ==============================================================================================
    // WS-LITERALS, lines 162 to 190
    // ==============================================================================================

    /** {@code LIT-THISPGM} at line 164: this member's own name, and the abend culprit. */
    private static final String LIT_THISPGM = "COCRDSLC";

    /** {@code LIT-THISTRANID} at line 166: the transaction the terminal return re-arms. */
    private static final String LIT_THISTRANID = "CCDL";

    /**
     * {@code LIT-THISMAPSET} at line 168, declared {@code PIC X(8)} with a trailing space. The space
     * is part of the value and is preserved: it is what the member moves into the seven-character
     * next-mapset field, where it is truncated away.
     */
    private static final String LIT_THISMAPSET = "COCRDSL ";

    /** {@code LIT-THISMAP} at line 170. */
    private static final String LIT_THISMAP = "CCRDSLA";

    /**
     * {@code LIT-CCLISTPGM} at line 172: the card-list member. Tested at line 340 to recognise a
     * hand-off whose selection criteria are already validated, and again at lines 506 and 528 to
     * decide whether the two filter fields arrive protected.
     */
    private static final String LIT_CCLISTPGM = "COCRDLIC";

    /** {@code LIT-CCLISTMAPSET} at line 176, compared against the last mapset at lines 505 and 527. */
    private static final String LIT_CCLISTMAPSET = "COCRDLI";

    /**
     * {@code LIT-CARDFILENAME} at line 188, the base cluster: bare here, padded where the legacy
     * field width applies. Reported in a diagnostic and composed into the file-error message.
     */
    private static final String RESOURCE_CARD_BASE_CLUSTER = "CARDDAT";

    /**
     * {@code LIT-CARDFILENAME-ACCT-PATH} at <strong>line 190</strong>, the account-keyed alternate
     * index. This is the resource the unreachable account read at line 779 names.
     */
    private static final String RESOURCE_CARD_ACCOUNT_PATH = "CARDAIX";

    /** {@code ERROR-OPNAME} as moved at lines 767 and 803: the only operation this member names. */
    private static final String OPERATION_READ = "READ";

    // ==============================================================================================
    // Message literals. Byte-exact; authored rather than padded, and never trimmed by a caller.
    // ==============================================================================================

    /**
     * The value of condition name {@code FOUND-CARDS-FOR-ACCOUNT} at lines 129 to 130. The three
     * leading spaces are part of the literal.
     *
     * <p>This is a condition name <em>on the information-message field</em>, not a separate flag, so
     * raising it writes this text and testing it compares against this text. The card fields at lines
     * 474 to 485 are populated only when the comparison holds, which is why the flag and the text
     * cannot be separated.
     */
    private static final String MSG_FOUND_CARDS_FOR_ACCOUNT = "   Displaying requested details";

    /** {@code WS-PROMPT-FOR-INPUT} at lines 131 to 132, raised at lines 460 and 491. */
    private static final String MSG_PROMPT_FOR_INPUT = "Please enter Account and Card Number";

    /** {@code WS-PROMPT-FOR-ACCT} at lines 138 to 139, raised at line 657 behind the message gate. */
    private static final String MSG_PROMPT_FOR_ACCOUNT = "Account number not provided";

    /** {@code WS-PROMPT-FOR-CARD} at lines 140 to 141, raised at line 697 behind the message gate. */
    private static final String MSG_PROMPT_FOR_CARD = "Card number not provided";

    /**
     * {@code NO-SEARCH-CRITERIA-RECEIVED} at lines 142 to 143, raised by the cross-field edit at line
     * 639 <em>without</em> the message gate, so it overwrites whichever per-field prompt was already
     * set. A turn that supplies neither filter therefore ends on this text and not on the account
     * prompt.
     */
    private static final String MSG_NO_SEARCH_CRITERIA_RECEIVED = "No input received";

    /**
     * {@code DID-NOT-FIND-ACCT-IN-CARDXREF} at lines 151 to 152, raised at line 799 by the account
     * read - unconditionally, unlike its card-number counterpart.
     */
    private static final String MSG_ACCOUNT_NOT_IN_CARD_DATABASE =
            "Did not find this account in cards database";

    /**
     * {@code DID-NOT-FIND-ACCTCARD-COMBO} at lines 153 to 154, raised at line 760 by the card-number
     * read behind the message gate.
     */
    private static final String MSG_NO_CARDS_FOR_SEARCH_CONDITION =
            "Did not find cards for this search condition";

    /** The literal moved at lines 669 to 671, which supersedes the unused condition name above it. */
    private static final String MSG_ACCOUNT_FILTER_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** The literal moved at lines 710 to 712. */
    private static final String MSG_CARD_FILTER_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** Moved at lines 377 to 378 by the catch-all arm, which sends plain text rather than abending. */
    private static final String MSG_UNEXPECTED_DATA_SCENARIO = "UNEXPECTED DATA SCENARIO";

    /** The default abend text at line 860, applied only when none was already set. */
    private static final String MSG_UNEXPECTED_ABEND = "UNEXPECTED ABEND OCCURRED.";

    /** {@code ABEND-CODE} as moved at line 375 by the catch-all arm. */
    private static final String ABEND_CODE_UNEXPECTED_DATA = "0001";

    /** The empty message state: {@code WS-RETURN-MSG-OFF} and {@code WS-NO-INFO-MESSAGE}. */
    private static final String NO_MESSAGE = "";

    // ==============================================================================================
    // WS-FILE-ERROR-MESSAGE, lines 102 to 121
    // ==============================================================================================

    /** Segment one, {@code PIC X(12)} at lines 103 to 104. */
    private static final String FILE_ERROR_PREFIX = "File Error: ";

    /** Segment three, {@code PIC X(4)} at lines 107 to 108. */
    private static final String FILE_ERROR_ON = " on ";

    /** Segment five, {@code PIC X(15)} at lines 111 to 113. */
    private static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    /** Segment seven, {@code PIC X(7)} at lines 116 to 117. */
    private static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** {@code ERROR-OPNAME}, {@code PIC X(8)} at lines 105 to 106. */
    private static final int ERROR_OPNAME_WIDTH = 8;

    /** {@code ERROR-FILE}, {@code PIC X(9)} at lines 109 to 110. */
    private static final int ERROR_FILE_WIDTH = 9;

    /** {@code ERROR-RESP} and {@code ERROR-RESP2}, each {@code PIC X(10)}. */
    private static final int ERROR_RESP_WIDTH = 10;

    /**
     * Digit count of {@code WS-RESP-CD} and {@code WS-REAS-CD}, each {@code PIC S9(09) COMP} at lines
     * 41 to 44. A move from a binary integer into an alphanumeric field renders the full declared
     * digit count, which is why the rendered value is nine digits inside a ten-character field.
     */
    private static final int RESPONSE_CODE_DIGITS = 9;

    // ==============================================================================================
    // Field widths
    // ==============================================================================================

    /**
     * {@code WS-RETURN-MSG}, {@code PIC X(75)} at line 134.
     *
     * <p>The eight segments of the file-error message sum to exactly 12 + 8 + 4 + 9 + 15 + 10 + 7 + 10
     * = 75, so composing them fills this field precisely and the five-character trailing filler at
     * lines 120 to 121 falls outside it. The legacy truncation is therefore reproduced by construction
     * and no value is ever cut.
     */
    private static final int RETURN_MESSAGE_WIDTH = 75;

    /** {@code WS-INFO-MSG}, {@code PIC X(40)} at line 126, and {@code INFOMSGO} at the same width. */
    private static final int INFO_MESSAGE_WIDTH = 40;

    /**
     * {@code ERRMSGO} of the symbolic map, {@code PIC X(80)}. Wider than the work field it is fed
     * from, so the move at line 494 pads with five spaces rather than truncating.
     */
    private static final int ERROR_MESSAGE_FIELD_WIDTH = 80;

    /** {@code ACCTSID}, {@code PIC X(11)}. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code CARDSID}, {@code PIC X(16)}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code CRDNAME}, {@code PIC X(50)}. */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /** {@code CRDSTCD}, {@code PIC X(1)}. */
    private static final int CARD_STATUS_WIDTH = 1;

    /** {@code CARD-EXPIRAION-DATE-X}, {@code PIC X(10)} at line 84. */
    private static final int EXPIRY_DATE_WIDTH = 10;

    /** {@code CARD-CVV-CD-X}, {@code PIC X(03)} at line 76. */
    private static final int VERIFICATION_CODE_WIDTH = 3;

    /** {@code TRNNAME}, {@code PIC X(4)}. */
    private static final int TRANSACTION_NAME_WIDTH = 4;

    /** {@code PGMNAME}, {@code PIC X(8)}. */
    private static final int PROGRAM_NAME_WIDTH = 8;

    /** {@code CCARD-NEXT-MAPSET} and {@code CCARD-NEXT-MAP}, {@code PIC X(7)} at lines 23 and 24. */
    private static final int MAP_NAME_WIDTH = 7;

    /** Width of each two-digit part of the header date and time. */
    private static final int HEADER_PART_WIDTH = 2;

    // ==============================================================================================
    // Expiry-date component positions, from the redefinition at lines 85 to 90
    // ==============================================================================================

    /** {@code CARD-EXPIRY-YEAR}, {@code PIC X(4)} at line 86: positions 1 to 4. */
    private static final int EXPIRY_YEAR_FROM = 0;

    /** End of the year component, exclusive. */
    private static final int EXPIRY_YEAR_TO = 4;

    /** {@code CARD-EXPIRY-MONTH}, {@code PIC X(2)} at line 88: positions 6 to 7. */
    private static final int EXPIRY_MONTH_FROM = 5;

    /** End of the month component, exclusive. */
    private static final int EXPIRY_MONTH_TO = 7;

    /** {@code CARD-EXPIRY-DAY}, {@code PIC X(2)} at line 90: positions 9 to 10. */
    private static final int EXPIRY_DAY_FROM = 8;

    /** End of the day component, exclusive. */
    private static final int EXPIRY_DAY_TO = 10;

    /** First retained position of the two-digit header year, from the reference modification at 441. */
    private static final int HEADER_YEAR_FROM = 2;

    /** End of the two-digit header year, exclusive. */
    private static final int HEADER_YEAR_TO = 4;

    // ==============================================================================================
    // Screen field identifiers and separators
    // ==============================================================================================

    /** The account filter, the cursor target of three of the five arms at lines 515 to 524. */
    private static final String FIELD_ACCOUNT_ID = "ACCTSID";

    /** The card-number filter, the cursor target of the two card arms at lines 519 to 521. */
    private static final String FIELD_CARD_NUMBER = "CARDSID";

    /** The account-filter property name a response layer decorates. */
    private static final String PROPERTY_ACCOUNT_ID = "accountId";

    /** The card-number property name a response layer decorates. */
    private static final String PROPERTY_CARD_NUMBER = "cardNumber";

    /** Separator of the header date, {@code MM/DD/YY}, assembled at lines 439 to 443. */
    private static final String HEADER_DATE_SEPARATOR = "/";

    /** Separator of the header time, {@code HH:MM:SS}, assembled at lines 445 to 449. */
    private static final String HEADER_TIME_SEPARATOR = ":";

    /** The decoration marker the source writes into a blank filter field at lines 543 and 549. */
    private static final String DECORATION_MARKER = "*";

    private static final char SPACE = ' ';

    private static final char ASCII_ZERO = '0';

    private static final char ASCII_NINE = '9';

    // ==============================================================================================
    // File statuses. Raw two-character codes from the estate's observed status vocabulary.
    // ==============================================================================================

    /** The success status, reported by the normal arm of each response evaluation. */
    private static final String STATUS_SUCCESS = "00";

    /** The not-found status, reported by the not-found arm of each response evaluation. */
    private static final String STATUS_RECORD_NOT_FOUND = "23";

    /**
     * The permanent-error status, reported when a read raises rather than answering. Chosen from the
     * codes the estate actually compares rather than invented: a store that cannot answer a keyed read
     * at all is a permanent input-output failure, which is the only status in that vocabulary
     * describing it.
     */
    private static final String STATUS_PERMANENT_ERROR = "31";

    /**
     * The record-length status, reported when a read answers but the record it returns violates the
     * layout contract this service edits it against. This is the outcome the source's catch-all
     * response arms at lines 762 and 800 describe: neither success nor not-found.
     */
    private static final String STATUS_RECORD_LENGTH_MISMATCH = "04";

    /** The numeric form of {@link #STATUS_RECORD_LENGTH_MISMATCH}, for the message's response slot. */
    private static final int RESPONSE_RECORD_LENGTH_MISMATCH = 4;

    /**
     * The standard-user type code, {@code 'U'}, declared as condition name {@code CDEMO-USRTYP-USER} on
     * line 28 of {@code app/cpy/COCOM01Y.cpy}.
     *
     * <p>Written unconditionally on the back-navigation path at line 326, which downgrades an
     * administrator's echoed type. Reproduced because the source does it; harmless because authorisation
     * is enforced from the authenticated principal rather than from this echoed field.
     */
    private static final String USER_TYPE_CODE_STANDARD = "U";

    // ==============================================================================================
    // Collaborators. Constructor injection only; every field final; no mutable state anywhere.
    // ==============================================================================================

    /** The keyed and account-keyed reads of the card cluster and its alternate index. */
    private final CardRepository cardRepository;

    /** The abend path this member arms at lines 250 to 252 and reaches at line 875. */
    private final AbendService abendService;

    /** The common message catalogue, source of the invalid-key text at its contractual width. */
    private final MessageCatalogService messageCatalogService;

    /** The single authority for destinations; this class declares no route table. */
    private final NavigationService navigationService;

    /** Stands in for {@code FUNCTION CURRENT-DATE} at lines 430 and 437. */
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param cardRepository        the card cluster gateway; must not be {@code null}
     * @param abendService          the abend path; must not be {@code null}
     * @param messageCatalogService the common message catalogue; must not be {@code null}
     * @param navigationService     the destination authority; must not be {@code null}
     * @param clock                 the clock the screen header reads; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardDetailService(final CardRepository cardRepository,
            final AbendService abendService,
            final MessageCatalogService messageCatalogService,
            final NavigationService navigationService,
            final Clock clock) {
        this.cardRepository =
                Objects.requireNonNull(cardRepository, "cardRepository must not be null");
        this.abendService = Objects.requireNonNull(abendService, "abendService must not be null");
        this.messageCatalogService =
                Objects.requireNonNull(messageCatalogService, "messageCatalogService must not be null");
        this.navigationService =
                Objects.requireNonNull(navigationService, "navigationService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ==============================================================================================
    // The level-88 flag groups, as enums with predicate methods
    // ==============================================================================================

    /**
     * {@code WS-INPUT-FLAG} and its three condition names at lines 51 to 54.
     *
     * <p>Three states and not a boolean, because the source distinguishes "not yet decided" from
     * "decided good": the flag is initialised to low values by the {@code INITIALIZE} at line 255 and
     * the edits raise it explicitly. A {@code SET x TO TRUE} becomes an assignment of the matching
     * constant.
     */
    private enum InputState {

        /** {@code INPUT-PENDING}, the initialised state. */
        PENDING,

        /** {@code INPUT-OK}, raised at lines 341 and 610. */
        OK,

        /** {@code INPUT-ERROR}, raised by every edit and read failure. */
        ERROR;

        /** @return {@code true} for the state the tests at lines 360 and 386 branch on */
        boolean isError() {
            return this == ERROR;
        }
    }

    /**
     * {@code WS-EDIT-ACCT-FLAG} at lines 55 to 58 and {@code WS-EDIT-CARD-FLAG} at lines 59 to 62,
     * which declare the same three condition names over two fields.
     *
     * <p>One enum for both, because the two fields are structurally identical and the cursor and colour
     * decisions at lines 515 to 551 treat them symmetrically. The distinction between the faulted and
     * the blank state is the whole reason a two-state field error contract exists: blank means the
     * operator supplied nothing, faulted means what they supplied was wrong, and the source decorates
     * the two differently.
     *
     * <p>Package-private so the neighbouring test can judge the translation below on all three states.
     * No production call site passes the valid one - a field that passed its edits produces no entry at
     * all - so that arm would otherwise go unexercised.
     */
    enum FilterState {

        /** {@code FLG-*FILTER-NOT-OK}, value {@code '0'}: supplied and wrong. */
        NOT_OK,

        /** {@code FLG-*FILTER-ISVALID}, value {@code '1'}. */
        VALID,

        /** {@code FLG-*FILTER-BLANK}, value {@code ' '}: not supplied. */
        BLANK;

        /** @return {@code true} when the field was supplied and failed its edit */
        boolean isNotOk() {
            return this == NOT_OK;
        }

        /** @return {@code true} when the field was not supplied */
        boolean isBlank() {
            return this == BLANK;
        }

        /**
         * Maps the flag onto the field-error contract the response layer decorates.
         *
         * @return {@code MISSING} for the blank state, {@code INVALID} for the faulted state, and
         *         {@code null} for the valid state, which produces no entry at all
         */
        ValidationException.FieldState toFieldState() {
            return switch (this) {
                case BLANK -> ValidationException.FieldState.MISSING;
                case NOT_OK -> ValidationException.FieldState.INVALID;
                default -> null;
            };
        }
    }

    /**
     * {@code WS-PFK-FLAG} and its two condition names at lines 66 to 68.
     *
     * <p>The gate at lines 291 to 299 is pessimistic: it raises the invalid state first, clears it only
     * for the enter key and the third program-function key, and then <em>coerces</em> an invalid key to
     * the enter key at line 298 so the screen re-presents rather than the turn failing.
     */
    private enum PfKeyState {

        /** {@code PFK-VALID}, value {@code '0'}. */
        VALID,

        /** {@code PFK-INVALID}, value {@code '1'}. */
        INVALID;

        /** @return {@code true} for the state the coercion at line 297 tests */
        boolean isInvalid() {
            return this == INVALID;
        }
    }

    // ==============================================================================================
    // The screen contract, as values
    // ==============================================================================================

    /**
     * One inbound turn of the card-detail screen.
     *
     * <p>Two transmitted fields, the raw attention identifier, and the navigation state the client
     * echoes in place of the communication area. Every component may be {@code null}: a 3270 field the
     * terminal did not transmit arrives as low values rather than as spaces, and an omitted component
     * of a request body is the same state.
     *
     * <p>The attention identifier arrives <strong>raw</strong> - the terminal name such as
     * {@code DFHPF3} - because this member expands the attention-key copybook itself at line 855 and so
     * performs the decoding. Decoding is delegated to the module's key translator, which also folds the
     * upper program-function keys onto the lower twelve, so an identifier naming key 15 behaves exactly
     * as one naming key 3.
     *
     * @param accountIdFilter        {@code ACCTSIDI}, the eleven-character account filter
     * @param cardNumberFilter       {@code CARDSIDI}, the sixteen-character card filter
     * @param attentionKeyIdentifier the raw attention identifier, or {@code null} when none arrived
     * @param navigationContext      the echoed navigation state, or {@code null} for a turn carrying
     *                               none, which is the zero-length communication area of line 268
     */
    public record CardDetailScreenInput(String accountIdFilter,
                                        String cardNumberFilter,
                                        String attentionKeyIdentifier,
                                        NavigationContext navigationContext) {
    }

    /**
     * The resolved card, at the widths the record declares.
     *
     * <p>Present only when the read succeeded. The three expiry components are the redefinition at
     * lines 85 to 90 of a single ten-character field, and the day component is carried even though the
     * screen never shows it, because the record has it and dropping it would lose information the
     * layout supplies.
     *
     * <p>The legacy field name is misspelled {@code CARD-EXPIRAION-DATE}. The byte layout keeps that
     * spelling; the Java component below is spelled correctly, and the mismatch is a decision-log
     * entry rather than a silent correction on either side.
     *
     * <p><strong>The verification code is carried and redacted.</strong> It stays a bounded
     * three-character string so a value such as {@code 007} survives intact, and {@link #toString()}
     * replaces it so no rendering of this record can leak it. The screen never displays it; it is
     * present because the record carries it and because this service edits it.
     *
     * @param cardNumber         {@code CARD-NUM}, sixteen characters, the record key
     * @param accountId          {@code CARD-ACCT-ID}, eleven characters
     * @param embossedName       {@code CARD-EMBOSSED-NAME}, fifty characters, unfolded
     * @param verificationCode   {@code CARD-CVV-CD}, exactly three characters, never a number
     * @param expirationDate     the whole ten-character expiry field as stored
     * @param expiryYear         the four-character year component
     * @param expiryMonth        the two-character month component, the one the screen shows
     * @param expiryDay          the two-character day component, which the screen does not show
     * @param activeStatus       {@code CARD-ACTIVE-STATUS}, one character, as stored
     * @param status             the interpreted status, or {@code null} when the stored character is
     *                           outside the two the record declares
     */
    public record CardProjection(String cardNumber,
                                 String accountId,
                                 String embossedName,
                                 String verificationCode,
                                 String expirationDate,
                                 String expiryYear,
                                 String expiryMonth,
                                 String expiryDay,
                                 String activeStatus,
                                 CardStatus status) {

        /** Fixed stand-in for every withheld component; a constant, so nothing about a value survives. */
        private static final String REDACTED = "***REDACTED***";

        /**
         * Renders the projection with the card number and the verification code withheld.
         *
         * <p>A constant rather than a mask or a digest: a truncated card number is still cardholder
         * data and a digest of a three-character code is trivially reversible by enumeration.
         *
         * @return a diagnostic rendering carrying no cardholder identifier
         */
        @Override
        public String toString() {
            return "CardProjection[cardNumber=" + REDACTED
                    + ", accountId=" + REDACTED
                    + ", embossedName=" + REDACTED
                    + ", verificationCode=" + REDACTED
                    + ", expirationDate=" + expirationDate
                    + ", expiryYear=" + expiryYear
                    + ", expiryMonth=" + expiryMonth
                    + ", expiryDay=" + expiryDay
                    + ", activeStatus=" + activeStatus
                    + ", status=" + status
                    + "]";
        }
    }

    /**
     * The screen header, as {@code 1100-SCREEN-INIT} populates it at lines 427 to 451.
     *
     * @param title01         {@code CCDA-TITLE01}, moved at line 432 at its catalogue width
     * @param title02         {@code CCDA-TITLE02}, moved at line 433 at its catalogue width
     * @param transactionName {@code TRNNAMEO}, moved at line 434
     * @param programName     {@code PGMNAMEO}, moved at line 435
     * @param currentDate     {@code CURDATEO} as {@code MM/DD/YY}, assembled at lines 439 to 443
     * @param currentTime     {@code CURTIMEO} as {@code HH:MM:SS}, assembled at lines 445 to 449
     */
    public record ScreenHeader(String title01,
                               String title02,
                               String transactionName,
                               String programName,
                               String currentDate,
                               String currentTime) {
    }

    /**
     * The screen body as the turn leaves it, each component at its declared width.
     *
     * <p>This is what the operator sees echoed back, and it is not the same as the projection: the
     * four card components are populated only when the information message holds the found text, which
     * is the condition at line 474, and the two filter components carry the decoration marker rather
     * than a value when the source writes one at lines 543 and 549.
     *
     * @param accountIdFilter      {@code ACCTSIDO}, eleven characters
     * @param cardNumberFilter     {@code CARDSIDO}, sixteen characters
     * @param embossedName         {@code CRDNAMEO}, fifty characters, blank when no card was found
     * @param cardActiveStatus     {@code CRDSTCDO}, one character, blank when no card was found
     * @param expiryMonth          {@code EXPMONO}, two characters
     * @param expiryYear           {@code EXPYEARO}, four characters
     * @param infoMessage          {@code INFOMSGO}, forty characters
     * @param errorMessage         {@code ERRMSGO}, eighty characters - wider than the work field that
     *                             feeds it, so the move pads rather than truncates
     * @param accountIdProtected   {@code true} when the account filter arrives protected, which is the
     *                             hand-off case at lines 505 to 508
     * @param cardNumberProtected  {@code true} when the card filter arrives protected
     * @param accountIdHighlighted {@code true} when the account filter is recoloured at line 534 or 544
     * @param cardNumberHighlighted {@code true} when the card filter is recoloured at line 538 or 550
     * @param infoMessageDarkened  {@code true} for the darkened attribute at line 554, which the source
     *                             applies when there is no information message to show
     */
    public record ScreenFields(String accountIdFilter,
                               String cardNumberFilter,
                               String embossedName,
                               String cardActiveStatus,
                               String expiryMonth,
                               String expiryYear,
                               String infoMessage,
                               String errorMessage,
                               boolean accountIdProtected,
                               boolean cardNumberProtected,
                               boolean accountIdHighlighted,
                               boolean cardNumberHighlighted,
                               boolean infoMessageDarkened) {
    }

    /**
     * The outcome of one turn of the card-detail screen.
     *
     * <p>A value and not a transport object: it carries no status code and no response entity, because
     * the legacy outcome is a screen plus a communication area and mapping that onto a transport
     * belongs to the controller.
     *
     * @param route                the destination the turn leads to. This screen's own destination
     *                             whenever it re-presents itself, since the return at lines 402 to 406
     *                             re-arms this same transaction; the resolved destination on the
     *                             back-navigation path at lines 305 to 334. Never {@code null}
     * @param navigationContext    the navigation state handed back, standing in for the communication
     *                             area both the return and the transfer carry. Never {@code null}
     * @param workArea             the screen work area of {@code app/cpy/CVCRD01Y.cpy} as the turn
     *                             leaves it, including the decoded attention key and the next-map
     *                             fields the source sets at lines 565 to 566 and 588 to 590. Never
     *                             {@code null}
     * @param reArmedTransactionId the transaction the terminal return re-armed at line 403; empty on
     *                             the back-navigation path, which transfers control instead, and on the
     *                             two plain-text paths, which return without re-arming. Never
     *                             {@code null}
     * @param card                 the resolved card, or {@code null} when none was found. A card is
     *                             present exactly when the information message holds the found text
     * @param message              the summary message. When more than one check failed this is the
     *                             <strong>first</strong> failure's text for every gated assignment,
     *                             because the source guards those on the field still being empty -
     *                             except where the source itself overwrites, which is the cross-field
     *                             edit at line 639 and both file-error arms. Empty when there is
     *                             nothing to say. Never {@code null} and never trimmed
     * @param infoMessage          the information message, which is a separate field from the summary
     *                             and carries either the found text or the input prompt. Never
     *                             {@code null}
     * @param focusField           the field the cursor is positioned on, from the corresponding
     *                             {@code MOVE -1} in the arms at lines 515 to 524. Never {@code null}
     * @param errorFlag            the state of {@code WS-INPUT-FLAG}, combined with the unrecognised
     *                             attention-key condition. The explicit flag rather than an inference
     *                             from the message, because the back-navigation path sets no message at
     *                             all. The two signals are combined here and not earlier: the edit
     *                             paragraph's unconditional {@code SET INPUT-OK} at line 610 resets the
     *                             legacy flag on every re-submission, and an unrecognised key must
     *                             survive that reset while the legacy flag's own semantics must not
     *                             change
     * @param reEnterFlag          the state of the re-enter gate the send sets at line 567. Field-level
     *                             decoration is conditional on it across this whole family, which is
     *                             why it is reported rather than inferred
     * @param fieldErrors          one entry per faulted field, in the order the source checks them -
     *                             account then card - distinguishing a field that was not supplied from
     *                             one supplied wrongly. Unmodifiable and never {@code null}
     * @param header               the screen header
     * @param screen               the screen body as the turn leaves it
     */
    public record CardDetailResult(NavigationService.Route route,
                                   NavigationContext navigationContext,
                                   ScreenWorkArea workArea,
                                   String reArmedTransactionId,
                                   CardProjection card,
                                   String message,
                                   String infoMessage,
                                   String focusField,
                                   boolean errorFlag,
                                   boolean reEnterFlag,
                                   List<ValidationException.FieldError> fieldErrors,
                                   ScreenHeader header,
                                   ScreenFields screen) {

        /**
         * Reports whether the turn presented a card.
         *
         * @return {@code true} when a card was resolved and no error was raised
         */
        public boolean cardPresented() {
            return this.card != null && !this.errorFlag;
        }
    }

    // ==============================================================================================
    // WORKING-STORAGE SECTION, lines 35 to 205 - one instance per turn
    // ==============================================================================================

    /**
     * The member's working storage, allocated per turn.
     *
     * <p>Mutable by design and never shared: this is what keeps the enclosing service a stateless
     * singleton. Every field names the legacy item it stands for, and every initial value is the one
     * the {@code INITIALIZE} at lines 254 to 256 establishes.
     *
     * <p><strong>Package-private rather than private, and only just.</strong> Two of the member's
     * paragraphs are unreachable in the source - the account-keyed read and the long-text send - so the
     * only way to exercise them is to drive them with a working storage of one's own. Widening this type
     * to the package makes that possible for the neighbouring test without widening it one step further:
     * nothing outside {@code com.carddemo.service} can name it, it appears in no public signature, and
     * every field below stays private. The alternative was to ship two translated paragraphs that no
     * test had ever run, which is a worse trade.
     */
    static final class TurnState {

        /** {@code WS-INPUT-FLAG}, low values after the initialise at line 255. */
        private InputState inputState = InputState.PENDING;

        /** {@code WS-EDIT-ACCT-FLAG}. */
        private FilterState accountFilterState = FilterState.BLANK;

        /** {@code WS-EDIT-CARD-FLAG}. */
        private FilterState cardFilterState = FilterState.BLANK;

        /** {@code WS-PFK-FLAG}, raised pessimistically at line 291. */
        private PfKeyState pfKeyState = PfKeyState.INVALID;

        /**
         * Raised when the attention identifier fell outside the copybook's 28-clause selection
         * altogether, as distinct from a key that was recognised and merely refused.
         *
         * <p><strong>Why this is a separate field rather than a use of {@code WS-INPUT-FLAG}.</strong>
         * This member emits no invalid-key text of its own - the canonical arm that does so lives in the
         * estate's other twelve online programs - so raising the legacy input flag here would be a state
         * the legacy never reaches, and it would then be erased anyway: the edit paragraph opens with an
         * unconditional {@code SET INPUT-OK} at line 610, which resets that flag on every re-submission.
         * Keeping the condition in its own field leaves the legacy flag's semantics exactly as the source
         * defines them, including that reset, while still reporting the unrecognised key to a caller.
         * The two are combined only at the boundary, where the outcome is assembled.
         */
        private boolean attentionKeyUnrecognised;

        /** {@code CCARD-AID} of the work area, assigned by the copybook expansion at line 855. */
        private KeyAction keyAction;

        /** {@code WS-RETURN-MSG}, cleared by the off state at line 264. */
        private String returnMessage = NO_MESSAGE;

        /** {@code WS-INFO-MSG}, cleared by the initialise at line 255. */
        private String infoMessage = NO_MESSAGE;

        /** {@code ACCTSIDI} of the input map, as the receive at lines 597 to 602 delivers it. */
        private String receivedAccountId = NO_MESSAGE;

        /** {@code CARDSIDI} of the input map, as the receive delivers it. */
        private String receivedCardNumber = NO_MESSAGE;

        /** {@code CC-ACCT-ID} of the work area, as the map-to-work move at lines 615 to 620 leaves it. */
        private String workAreaAccountId = NO_MESSAGE;

        /** {@code CC-CARD-NUM} of the work area, from the move at lines 622 to 627. */
        private String workAreaCardNumber = NO_MESSAGE;

        /** {@code CDEMO-ACCT-ID} of the communication area, zeroed or set by the account edit. */
        private String contextAccountId = NO_MESSAGE;

        /** {@code CDEMO-CARD-NUM} of the communication area. */
        private String contextCardNumber = NO_MESSAGE;

        /** {@code WS-CARD-RID-CARDNUM}, {@code PIC X(16)} at line 98, loaded at line 740. */
        private String cardRecordKey = NO_MESSAGE;

        /** {@code WS-CARD-RID-ACCT-ID}, {@code PIC 9(11)} at line 99, the alternate-index key. */
        private String cardAccountKey = NO_MESSAGE;

        /** {@code CARD-RECORD} once a read has succeeded, otherwise absent. */
        private Card card;

        /** {@code ERROR-OPNAME}, moved at lines 767 and 803. */
        private String errorOperation = NO_MESSAGE;

        /** {@code ERROR-FILE}, moved at lines 768 and 804. */
        private String errorResource = NO_MESSAGE;

        /** The raw two-character file status the last read reported, for the diagnostic and the abend. */
        private String rawFileStatus = NO_MESSAGE;

        /** {@code WS-RESP-CD}, {@code PIC S9(09) COMP} at line 41. */
        private int responseCode;

        /** {@code WS-REAS-CD}, {@code PIC S9(09) COMP} at line 43. */
        private int reasonCode;

        /** {@code CARDDEMO-COMMAREA}, the state the turn hands back. */
        private NavigationContext context = NavigationContext.empty();

        /** The destination, always resolved through the navigation authority. */
        private NavigationService.Route route = NavigationService.Route.CARD_DETAIL;

        /** The transaction the terminal return re-armed at line 403, empty when the turn transferred. */
        private String reArmedTransactionId = NO_MESSAGE;

        /** {@code CCARD-NEXT-PROG}, set at line 588. */
        private String nextProgram = NO_MESSAGE;

        /** {@code CCARD-NEXT-MAPSET}, set at lines 565 and 589. */
        private String nextMapset = NO_MESSAGE;

        /** {@code CCARD-NEXT-MAP}, set at lines 566 and 590. */
        private String nextMap = NO_MESSAGE;

        /** The cursor position, from the arms at lines 515 to 524. */
        private String focusField = FIELD_ACCOUNT_ID;

        /** The re-enter gate the send raises at line 567. */
        private boolean reEnter;

        /** {@code true} once a send or a plain-text send has ended the turn's presentation. */
        private boolean screenSent;

        /** {@code ABEND-CODE} of the abend context, set at line 375 and at the handler. */
        private String abendCode = NO_MESSAGE;

        /** {@code ABEND-MSG} of the abend context, defaulted at line 860. */
        private String abendMessage = NO_MESSAGE;

        /** The field errors, in the order the source checks the two fields. */
        private final List<ValidationException.FieldError> fieldErrors = new ArrayList<>();

        // -- Screen body -------------------------------------------------------------------------

        private String accountIdField = NO_MESSAGE;

        private String cardNumberField = NO_MESSAGE;

        private String embossedNameField = NO_MESSAGE;

        private String cardStatusField = NO_MESSAGE;

        private String expiryMonthField = NO_MESSAGE;

        private String expiryYearField = NO_MESSAGE;

        private String infoMessageField = NO_MESSAGE;

        private String errorMessageField = NO_MESSAGE;

        private boolean accountIdProtected;

        private boolean cardNumberProtected;

        private boolean accountIdHighlighted;

        private boolean cardNumberHighlighted;

        private boolean infoMessageDarkened;

        // -- Screen header -----------------------------------------------------------------------

        private String title01 = NO_MESSAGE;

        private String title02 = NO_MESSAGE;

        private String transactionName = NO_MESSAGE;

        private String programName = NO_MESSAGE;

        private String currentDate = NO_MESSAGE;

        private String currentTime = NO_MESSAGE;

        /**
         * Raises the summary message only if none has been set, which is the
         * {@code IF WS-RETURN-MSG-OFF} gate the source places on every per-field and not-found
         * assignment. First failure wins for the summary; the field flags are set independently and
         * all of them are set.
         *
         * @param text the message to raise
         */
        private void raiseMessageIfUnset(final String text) {
            if (this.returnMessage.isEmpty()) {
                this.returnMessage = text;
            }
        }

        /**
         * Raises {@code FOUND-CARDS-FOR-ACCOUNT}, which is a condition name on the information-message
         * field and so writes its text rather than setting a separate flag.
         */
        private void setFoundCardsForAccount() {
            this.infoMessage = MSG_FOUND_CARDS_FOR_ACCOUNT;
        }

        /**
         * Tests {@code FOUND-CARDS-FOR-ACCOUNT}, which compares the information-message field against
         * the condition-name value.
         *
         * @return {@code true} when the found text is in the information-message field
         */
        private boolean foundCardsForAccount() {
            return MSG_FOUND_CARDS_FOR_ACCOUNT.equals(this.infoMessage);
        }

        /**
         * Tests {@code WS-NO-INFO-MESSAGE} at lines 127 to 128, whose declared values are spaces and
         * low values.
         *
         * @return {@code true} when the information-message field is empty
         */
        private boolean noInfoMessage() {
            return this.infoMessage.isEmpty();
        }

        /**
         * Records a faulted field once, in check order.
         *
         * @param property   the property name a response layer decorates
         * @param bmsFieldId the screen field identifier
         * @param state      the flag state to translate
         * @param text       the text describing the fault
         */
        private void recordFieldError(final String property, final String bmsFieldId,
                final FilterState state, final String text) {
            final ValidationException.FieldState fieldState = state.toFieldState();
            if (fieldState == null) {
                return;
            }
            this.fieldErrors.add(
                    new ValidationException.FieldError(property, bmsFieldId, fieldState, text));
        }
    }

    // ==============================================================================================
    // Entry point: the procedure division at line 247
    // ==============================================================================================

    /**
     * Runs one turn of the card-detail screen.
     *
     * <p>Read-only: nothing here writes, flushes or touches the entity version. The transaction is
     * declared read-only so the provider can skip dirty checking and a write attempted by mistake fails
     * rather than succeeding quietly.
     *
     * <p>The abend handler this member arms at lines 250 to 252 is in force for the whole turn, so an
     * unexpected failure transfers to {@code abendRoutine} exactly as the legacy
     * transfers to its handler label. A read that <em>answers</em> unusably does not go there: it takes
     * the catch-all response arm and produces the file-error message, which is what the source does.
     *
     * @param input the transmitted screen, the raw attention identifier and the echoed navigation
     *              state; must not be {@code null}
     * @return the outcome of the turn, never {@code null}
     * @throws NullPointerException if {@code input} is {@code null}
     */
    @Transactional(readOnly = true)
    public CardDetailResult processCardDetail(final CardDetailScreenInput input) {
        Objects.requireNonNull(input, "input must not be null");

        final TurnState state = new TurnState();
        try {
            mainPara(state, input);
        } catch (final RuntimeException failure) {
            // EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE) at lines 250 to 252 is armed before any
            // other statement, so it covers everything the turn does. The handler always raises, so
            // nothing below this block runs on this path and no failure is swallowed.
            abendRoutine(state, failure);
        }

        // No card number, account number or verification code appears in this line, by construction:
        // whether a card was found is a boolean and the focused field is a screen field identifier.
        LOG.debug("Card-detail turn complete: route={} errorFlag={} reEnter={} presented={}"
                        + " cardFound={} fieldErrors={} focusField={}",
                state.route.getRouteValue(),
                state.inputState.isError() || state.attentionKeyUnrecognised, state.reEnter,
                state.screenSent, state.card != null, state.fieldErrors.size(), state.focusField);
        return toResult(state);
    }

    /**
     * Assembles the turn's outcome from its working storage.
     *
     * <p>Package-private for the reason set out on {@code TurnState}: a paragraph driven directly needs
     * some way to become an assertable value, and this is it. It appears in no public signature.
     *
     * @param state the turn's working storage
     * @return the outcome, never {@code null}
     */
    CardDetailResult toResult(final TurnState state) {
        return new CardDetailResult(
                state.route,
                state.context,
                new ScreenWorkArea(
                        state.keyAction,
                        state.nextProgram,
                        state.nextMapset,
                        state.nextMap,
                        state.errorMessageField,
                        state.returnMessage,
                        state.workAreaAccountId,
                        state.workAreaCardNumber,
                        NO_MESSAGE),
                state.reArmedTransactionId,
                projectionOf(state),
                state.returnMessage,
                state.infoMessage,
                state.focusField,
                state.inputState.isError() || state.attentionKeyUnrecognised,
                state.reEnter,
                List.copyOf(state.fieldErrors),
                new ScreenHeader(state.title01, state.title02, state.transactionName,
                        state.programName, state.currentDate, state.currentTime),
                new ScreenFields(state.accountIdField, state.cardNumberField,
                        state.embossedNameField, state.cardStatusField, state.expiryMonthField,
                        state.expiryYearField, state.infoMessageField, state.errorMessageField,
                        state.accountIdProtected, state.cardNumberProtected,
                        state.accountIdHighlighted, state.cardNumberHighlighted,
                        state.infoMessageDarkened));
    }

    /**
     * Projects the resolved card, splitting the ten-character expiry field into the three components
     * the redefinition at lines 85 to 90 declares.
     *
     * @param state the turn's working storage
     * @return the projection, or {@code null} when no card was resolved
     */
    private static CardProjection projectionOf(final TurnState state) {
        final Card resolved = state.card;
        if (resolved == null) {
            return null;
        }
        final String expiry = boundedField(resolved.getCardExpirationDate(), EXPIRY_DATE_WIDTH);
        final String activeStatus = boundedField(resolved.getCardActiveStatus(), CARD_STATUS_WIDTH);
        return new CardProjection(
                boundedField(resolved.getCardNum(), CARD_NUMBER_WIDTH),
                boundedField(resolved.getCardAcctId(), ACCOUNT_ID_WIDTH),
                boundedField(resolved.getCardEmbossedName(), EMBOSSED_NAME_WIDTH),
                boundedField(resolved.getCardCvvCd(), VERIFICATION_CODE_WIDTH),
                expiry,
                fieldSlice(expiry, EXPIRY_YEAR_FROM, EXPIRY_YEAR_TO),
                fieldSlice(expiry, EXPIRY_MONTH_FROM, EXPIRY_MONTH_TO),
                fieldSlice(expiry, EXPIRY_DAY_FROM, EXPIRY_DAY_TO),
                activeStatus,
                CardStatus.fromCode(activeStatus).orElse(null));
    }

    // ==============================================================================================
    // 0000-MAIN, line 248
    // ==============================================================================================

    /**
     * {@code 0000-MAIN} at line 248: the main paragraph.
     *
     * <p>Establishes the transaction context at line 260, clears the summary message at line 264,
     * decides whether the echoed navigation state is usable at lines 268 to 279, decodes the attention
     * key at lines 284 to 285, applies the attention-key gate at lines 291 to 299, and then dispatches.
     *
     * <p><strong>The state test at lines 268 to 270 has two arms joined by {@code OR}, and the second
     * is easy to miss.</strong> The state is discarded not only when the communication area is
     * zero-length but also when it names the user menu as the originating program <em>and</em> the
     * re-enter gate is down - that is, on a fresh arrival from the menu. Honouring only the first arm
     * would carry a stale account and card selection into a screen the operator has just entered.
     *
     * <p><strong>The attention-key gate is pessimistic and then coerces.</strong> Line 291 raises the
     * invalid state, lines 292 to 295 clear it for the enter key and the third program-function key
     * only, and lines 297 to 299 rewrite an invalid key as the enter key so the screen re-presents
     * rather than the turn failing. The source emits no message for a key it merely refuses, so neither
     * does this - only a key the copybook's selection does not recognise at all produces the catalogue's
     * invalid-key text, which is set by the decoding step.
     *
     * <p><strong>The dispatch is an {@code EVALUATE TRUE} at lines 304 to 381 and its arms are
     * independent conditions, so it becomes an if-else cascade rather than a {@code switch}.</strong>
     * Clause order is preserved exactly, because COBOL stops at the first arm whose condition holds:
     * back-navigation, then a hand-off from the card-list screen whose criteria are already validated,
     * then any other first entry, then a re-submission, then the catch-all.
     *
     * <p>The test after the cascade at lines 386 to 391 is retained for fidelity. It is unreachable in
     * the shipped estate because every arm ends the turn - three transfer to the common return, one
     * transfers control, and the catch-all returns after sending plain text - and it is reproduced
     * because removing it would remove a guard the source has.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen, the raw attention identifier and the echoed state
     */
    private void mainPara(final TurnState state, final CardDetailScreenInput input) {
        // MOVE LIT-THISTRANID TO WS-TRANID at line 260, and the two header identifiers the screen
        // initialisation moves at lines 434 and 435.
        state.transactionName = boundedField(LIT_THISTRANID, TRANSACTION_NAME_WIDTH);
        state.programName = boundedField(LIT_THISPGM, PROGRAM_NAME_WIDTH);

        // SET WS-RETURN-MSG-OFF TO TRUE at line 264.
        state.returnMessage = NO_MESSAGE;

        final NavigationContext inbound = input.navigationContext();
        if (isNavigationStateAbsent(inbound) || arrivedFreshFromMenu(inbound)) {
            // INITIALIZE CARDDEMO-COMMAREA and WS-THIS-PROGCOMMAREA at lines 271 to 272.
            state.context = NavigationContext.empty();
        } else {
            // MOVE DFHCOMMAREA(1:LENGTH OF CARDDEMO-COMMAREA) TO CARDDEMO-COMMAREA at lines 274 to
            // 278. The trailing program-private area holds only the originating program and
            // transaction, both of which the echoed state already carries.
            state.context = inbound;
        }

        // The two selection fields and the re-enter gate are read from the communication area, not
        // reset by this member: the attribute step at lines 541 to 551 tests the INBOUND gate, which
        // the previous turn's send raised at line 567, and the variable step at lines 462 and 468
        // tests the INBOUND selection. Seeding them here is what keeps that reading right.
        state.contextAccountId = boundedField(state.context.accountId(), ACCOUNT_ID_WIDTH);
        state.contextCardNumber = boundedField(state.context.cardNumber(), CARD_NUMBER_WIDTH);
        state.reEnter = state.context.reEntry();

        // PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT at lines 284 to 285.
        expandStorePfKeyCopybook(state, input.attentionKeyIdentifier());

        // SET PFK-INVALID TO TRUE at line 291.
        state.pfKeyState = PfKeyState.INVALID;
        // IF CCARD-AID-ENTER OR CCARD-AID-PFK03 ... SET PFK-VALID TO TRUE at lines 292 to 295.
        if (state.keyAction == KeyAction.ENTER || state.keyAction == KeyAction.PFK03) {
            state.pfKeyState = PfKeyState.VALID;
        }
        // IF PFK-INVALID ... SET CCARD-AID-ENTER TO TRUE at lines 297 to 299.
        if (state.pfKeyState.isInvalid()) {
            state.keyAction = KeyAction.ENTER;
        }

        if (state.keyAction == KeyAction.PFK03) {
            // WHEN CCARD-AID-PFK03 at line 305: transfer to the calling screen or the main menu.
            returnToCallerOrMenu(state);
            return;
        }
        if (state.context.firstEntry() && arrivedFromCardList(state.context)) {
            // WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM = LIT-CCLISTPGM at lines 339 to 340. The
            // selection criteria were validated by the list screen, so no edit runs here.
            // SET INPUT-OK TO TRUE at line 341.
            state.inputState = InputState.OK;
            state.accountFilterState = FilterState.VALID;
            state.cardFilterState = FilterState.VALID;
            // MOVE CDEMO-ACCT-ID TO CC-ACCT-ID-N and CDEMO-CARD-NUM TO CC-CARD-NUM-N, lines 342 to 343.
            state.workAreaAccountId = boundedField(state.context.accountId(), ACCOUNT_ID_WIDTH);
            state.workAreaCardNumber = boundedField(state.context.cardNumber(), CARD_NUMBER_WIDTH);
            state.contextAccountId = state.workAreaAccountId;
            state.contextCardNumber = state.workAreaCardNumber;
            readData(state);
            sendMap(state);
            commonReturn(state);
            return;
        }
        if (state.context.firstEntry()) {
            // WHEN CDEMO-PGM-ENTER at line 349: any other first entry gathers criteria.
            sendMap(state);
            commonReturn(state);
            return;
        }
        if (state.context.reEntry()) {
            // WHEN CDEMO-PGM-REENTER at line 357.
            processInputs(state, input);
            if (state.inputState.isError()) {
                // IF INPUT-ERROR at line 360.
                sendMap(state);
                commonReturn(state);
                return;
            }
            readData(state);
            sendMap(state);
            commonReturn(state);
            return;
        }

        // WHEN OTHER at line 373. Unreachable in the shipped estate: CDEMO-PGM-CONTEXT is PIC 9(01)
        // with condition names for 0 and 1 only, the initialise at line 271 leaves it 0, and nothing
        // in the estate stores any other digit. Reproduced in full rather than dropped, because it is
        // an arm the source has.
        state.abendCode = ABEND_CODE_UNEXPECTED_DATA;
        state.abendMessage = NO_MESSAGE;
        state.returnMessage = MSG_UNEXPECTED_DATA_SCENARIO;
        sendPlainText(state);

        // IF INPUT-ERROR at lines 386 to 391.
        if (state.inputState.isError()) {
            state.errorMessageField = boundedField(state.returnMessage, ERROR_MESSAGE_FIELD_WIDTH);
            sendMap(state);
            commonReturn(state);
        }
        mainParaExit(state);
    }

    /**
     * The second arm of the state test at lines 269 to 270: a fresh arrival from the user menu, whose
     * echoed state must be discarded rather than trusted.
     *
     * <p>The originating program is compared against the menu member's name, which is resolved from the
     * navigation authority rather than restated here, so this class still declares no route table.
     *
     * @param inbound the echoed navigation state, possibly {@code null}
     * @return {@code true} when the state names the user menu as originator and the re-enter gate is
     *         down
     */
    private static boolean arrivedFreshFromMenu(final NavigationContext inbound) {
        return inbound != null
                && NavigationService.Route.USER_MENU.getLegacyProgramName()
                        .equals(trimmedProgramName(inbound.fromProgram()))
                && !inbound.reEntry();
    }

    /**
     * The second half of the condition at line 340: the turn arrived from the card-list screen.
     *
     * @param context the navigation state
     * @return {@code true} when the originating program is the card-list member
     */
    private static boolean arrivedFromCardList(final NavigationContext context) {
        return LIT_CCLISTPGM.equals(trimmedProgramName(context.fromProgram()));
    }

    // ==============================================================================================
    // COMMON-RETURN, line 394
    // ==============================================================================================

    /**
     * {@code COMMON-RETURN} at line 394: the terminal return every screen path reaches.
     *
     * <p>Moves the summary message into the work area's message field at line 395, repacks the
     * communication area at lines 397 to 400, and issues
     * {@code EXEC CICS RETURN TRANSID(LIT-THISTRANID)} at lines 402 to 406, which re-arms this same
     * transaction for the operator's next keystroke. Re-arming is what the returned transaction
     * identifier records; it is idempotent, so a path that has already ended the turn is unaffected.
     *
     * @param state the turn's working storage
     */
    private void commonReturn(final TurnState state) {
        // MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG at line 395. The work-area message field is 75
        // characters, the same width as the sending field, so nothing is lost.
        state.errorMessageField = boundedField(state.returnMessage, ERROR_MESSAGE_FIELD_WIDTH);

        // MOVE CARDDEMO-COMMAREA TO WS-COMMAREA and append WS-THIS-PROGCOMMAREA, lines 397 to 400.
        // The repack carries the selection the two edits stored into CDEMO-ACCT-ID and
        // CDEMO-CARD-NUM at lines 659, 673, 676, 700, 714 and 717. No routing field is written on
        // this path: the source sets those only on the back-navigation arm.
        state.context = contextWith(state.context,
                state.context.fromTransactionId(),
                state.context.fromProgram(),
                state.context.toTransactionId(),
                state.context.toProgram(),
                state.context.userType(),
                state.contextAccountId,
                state.contextCardNumber,
                state.context.lastMap(),
                state.context.lastMapset());

        // EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA) at lines 402 to 406: the
        // same transaction is re-armed, so the destination stays this screen.
        state.reArmedTransactionId = LIT_THISTRANID;
        state.route = NavigationService.Route.CARD_DETAIL;
    }

    /**
     * The back-navigation arm at lines 305 to 334: the third program-function key transfers control to
     * the calling screen, or to the user main menu when nothing called this one.
     *
     * <p>Both halves of the legacy rule are honoured. The destination is the originating program when
     * that field names one and the user main menu otherwise, resolved through the navigation authority
     * so no route table is declared here; the blank test is the legacy one, spaces or low values rather
     * than any white space. The originating fields are then rewritten to name <em>this</em> screen at
     * lines 323 to 324, the re-enter gate is lowered at line 327, and the last map and mapset are
     * recorded at lines 328 to 329.
     *
     * <p><strong>One legacy behaviour is preserved that a reviewer may mistake for a defect, because it
     * is one.</strong> Line 326 sets the user type to the standard-user code unconditionally, so an
     * administrator who leaves this screen has their echoed type downgraded. It is reproduced because
     * the source does it and because the type is echoed state rather than the authenticated identity -
     * authorisation is enforced from the authenticated principal, not from this field - and it is
     * recorded as a decision-log entry rather than silently corrected.
     *
     * <p>No message is set on this path. The exit text declared at lines 136 to 137 is never raised
     * anywhere in the member, so raising it here would be an addition.
     *
     * @param state the turn's working storage
     */
    private void returnToCallerOrMenu(final TurnState state) {
        final NavigationService.Route destination =
                navigationService.resolveBackNavigation(carriedState(state.context),
                        NavigationService.Route.USER_MENU);

        // MOVE LIT-THISTRANID TO CDEMO-FROM-TRANID and LIT-THISPGM TO CDEMO-FROM-PROGRAM, 323 to 324.
        // SET CDEMO-USRTYP-USER TO TRUE at line 326, then SET CDEMO-PGM-ENTER TO TRUE at line 327.
        state.context = contextWith(state.context,
                LIT_THISTRANID,
                LIT_THISPGM,
                destination.getLegacyTransactionId(),
                destination.getLegacyProgramName(),
                USER_TYPE_CODE_STANDARD,
                state.contextAccountId,
                state.contextCardNumber,
                boundedField(LIT_THISMAP, MAP_NAME_WIDTH),
                boundedField(LIT_THISMAPSET, MAP_NAME_WIDTH))
                .withFirstEntry();

        // EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) at lines 331 to 334. Control transfers, so this
        // turn does not re-arm its own transaction and the re-armed identifier stays empty.
        state.route = destination;
        state.reArmedTransactionId = NO_MESSAGE;
        LOG.debug("Back-navigation from card detail: rule=attention-key-dispatch destination={}",
                destination.getRouteValue());
    }

    /**
     * {@code 0000-MAIN-EXIT} at line 408.
     *
     * @param state the turn's working storage
     */
    private void mainParaExit(final TurnState state) {
        paragraphExit(state, "0000-MAIN-EXIT", 408);
    }

    // ==============================================================================================
    // 1000-SEND-MAP, line 412
    // ==============================================================================================

    /**
     * {@code 1000-SEND-MAP} at line 412: the four-step presentation range.
     *
     * <p>Performs screen initialisation, variable population, attribute setup and the send itself, in
     * that order, at lines 413 to 420. The order matters: the attribute step reads the filter flags the
     * variable step does not touch, and the send step is what raises the re-enter gate.
     *
     * @param state the turn's working storage
     */
    private void sendMap(final TurnState state) {
        screenInit(state);
        setupScreenVars(state);
        setupScreenAttrs(state);
        sendScreen(state);
        sendMapExit(state);
    }

    /**
     * {@code 1000-SEND-MAP-EXIT} at line 423.
     *
     * @param state the turn's working storage
     */
    private void sendMapExit(final TurnState state) {
        paragraphExit(state, "1000-SEND-MAP-EXIT", 423);
    }

    // ==============================================================================================
    // 1100-SCREEN-INIT, line 427
    // ==============================================================================================

    /**
     * {@code 1100-SCREEN-INIT} at line 427: clears the outbound map and fills the header.
     *
     * <p>Line 428 moves low values over the whole outbound map, then lines 432 to 435 move the two
     * catalogue titles and the transaction and program identifiers, and lines 439 to 449 assemble the
     * date as {@code MM/DD/YY} and the time as {@code HH:MM:SS}. The two-digit year is the last two
     * characters of the four-character year, exactly as the reference modification at line 441 takes it.
     *
     * <p>The source reads the current date twice, at lines 430 and 437, and uses only the second
     * reading. One reading is taken here: a second would be a redundant call on the same clock and
     * could differ from the first across a second boundary, which the legacy could equally suffer. The
     * duplicate statement is noted rather than reproduced.
     *
     * @param state the turn's working storage
     */
    private void screenInit(final TurnState state) {
        // MOVE LOW-VALUES TO CCRDSLAO at line 428: every outbound field starts blank, and the two
        // steps that follow write only what the source writes.
        state.embossedNameField = blankField(EMBOSSED_NAME_WIDTH);
        state.cardStatusField = blankField(CARD_STATUS_WIDTH);
        state.expiryMonthField = blankField(HEADER_PART_WIDTH);
        state.expiryYearField = blankField(EXPIRY_YEAR_TO - EXPIRY_YEAR_FROM);
        state.accountIdField = blankField(ACCOUNT_ID_WIDTH);
        state.cardNumberField = blankField(CARD_NUMBER_WIDTH);

        // MOVE CCDA-TITLE01 and CCDA-TITLE02 at lines 432 to 433, at their contractual widths.
        state.title01 = messageCatalogService.screenTitle01();
        state.title02 = messageCatalogService.screenTitle02();

        // MOVE LIT-THISTRANID and LIT-THISPGM at lines 434 to 435.
        state.transactionName = boundedField(LIT_THISTRANID, TRANSACTION_NAME_WIDTH);
        state.programName = boundedField(LIT_THISPGM, PROGRAM_NAME_WIDTH);

        // MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA at line 437.
        final LocalDateTime now = LocalDateTime.now(clock);
        final String fullYear = numericField(now.getYear(), EXPIRY_YEAR_TO - EXPIRY_YEAR_FROM);

        // Lines 439 to 443: MM/DD/YY with the year taken as WS-CURDATE-YEAR(3:2).
        state.currentDate = numericField(now.getMonthValue(), HEADER_PART_WIDTH)
                + HEADER_DATE_SEPARATOR
                + numericField(now.getDayOfMonth(), HEADER_PART_WIDTH)
                + HEADER_DATE_SEPARATOR
                + fieldSlice(fullYear, HEADER_YEAR_FROM, HEADER_YEAR_TO);

        // Lines 445 to 449: HH:MM:SS.
        state.currentTime = numericField(now.getHour(), HEADER_PART_WIDTH)
                + HEADER_TIME_SEPARATOR
                + numericField(now.getMinute(), HEADER_PART_WIDTH)
                + HEADER_TIME_SEPARATOR
                + numericField(now.getSecond(), HEADER_PART_WIDTH);

        screenInitExit(state);
    }

    /**
     * {@code 1100-SCREEN-INIT-EXIT} at line 453.
     *
     * @param state the turn's working storage
     */
    private void screenInitExit(final TurnState state) {
        paragraphExit(state, "1100-SCREEN-INIT-EXIT", 453);
    }

    // ==============================================================================================
    // 1200-SETUP-SCREEN-VARS, line 457
    // ==============================================================================================

    /**
     * {@code 1200-SETUP-SCREEN-VARS} at line 457: fills the screen body.
     *
     * <p>A turn carrying no communication area shows only the input prompt, line 460. Otherwise each
     * filter field is shown blank when its stored selection is zero and shown as the work-area value
     * otherwise, lines 462 to 472 - so a zero selection presents as an empty field rather than as
     * eleven or sixteen zeros.
     *
     * <p>The four card fields at lines 474 to 485 are populated only when the found condition holds,
     * and that condition is a comparison of the information-message field against its condition-name
     * text, so a turn that found nothing leaves them blank. Only the month and the year of the expiry
     * date reach the screen; the day component is not shown, though the redefinition declares it.
     *
     * <p>Lines 490 to 492 then default the information message to the input prompt when it is still
     * empty, which is why a turn that neither found a card nor set a prompt still shows one.
     *
     * @param state the turn's working storage
     */
    private void setupScreenVars(final TurnState state) {
        // IF EIBCALEN = 0 at line 459.
        if (isNavigationStateAbsent(state.context)) {
            // SET WS-PROMPT-FOR-INPUT TO TRUE at line 460.
            state.infoMessage = MSG_PROMPT_FOR_INPUT;
        } else {
            // IF CDEMO-ACCT-ID = 0 at line 462: MOVE LOW-VALUES, else MOVE CC-ACCT-ID.
            state.accountIdField = isZeroOrUnsupplied(state.contextAccountId)
                    ? blankField(ACCOUNT_ID_WIDTH)
                    : boundedField(state.workAreaAccountId, ACCOUNT_ID_WIDTH);

            // IF CDEMO-CARD-NUM = 0 at line 468.
            state.cardNumberField = isZeroOrUnsupplied(state.contextCardNumber)
                    ? blankField(CARD_NUMBER_WIDTH)
                    : boundedField(state.workAreaCardNumber, CARD_NUMBER_WIDTH);

            // IF FOUND-CARDS-FOR-ACCOUNT at line 474.
            if (state.foundCardsForAccount() && state.card != null) {
                final String expiry =
                        boundedField(state.card.getCardExpirationDate(), EXPIRY_DATE_WIDTH);
                // MOVE CARD-EMBOSSED-NAME TO CRDNAMEO at lines 475 to 476.
                state.embossedNameField =
                        boundedField(state.card.getCardEmbossedName(), EMBOSSED_NAME_WIDTH);
                // MOVE CARD-EXPIRY-MONTH TO EXPMONO at line 480, and the year at line 482. The
                // legacy field name carries the misspelling CARD-EXPIRAION-DATE; the byte layout is
                // unchanged and only the Java property is spelled correctly.
                state.expiryMonthField = fieldSlice(expiry, EXPIRY_MONTH_FROM, EXPIRY_MONTH_TO);
                state.expiryYearField = fieldSlice(expiry, EXPIRY_YEAR_FROM, EXPIRY_YEAR_TO);
                // MOVE CARD-ACTIVE-STATUS TO CRDSTCDO at line 484.
                state.cardStatusField =
                        boundedField(state.card.getCardActiveStatus(), CARD_STATUS_WIDTH);
            }
        }

        // IF WS-NO-INFO-MESSAGE ... SET WS-PROMPT-FOR-INPUT TO TRUE at lines 490 to 492.
        if (state.noInfoMessage()) {
            state.infoMessage = MSG_PROMPT_FOR_INPUT;
        }

        // MOVE WS-RETURN-MSG TO ERRMSGO at line 494. The receiving field is eighty characters against
        // a seventy-five-character sender, so the move pads and never truncates.
        state.errorMessageField = boundedField(state.returnMessage, ERROR_MESSAGE_FIELD_WIDTH);

        // MOVE WS-INFO-MSG TO INFOMSGO at line 496.
        state.infoMessageField = boundedField(state.infoMessage, INFO_MESSAGE_WIDTH);

        setupScreenVarsExit(state);
    }

    /**
     * {@code 1200-SETUP-SCREEN-VARS-EXIT} at line 499.
     *
     * @param state the turn's working storage
     */
    private void setupScreenVarsExit(final TurnState state) {
        paragraphExit(state, "1200-SETUP-SCREEN-VARS-EXIT", 499);
    }

    // ==============================================================================================
    // 1300-SETUP-SCREEN-ATTRS, line 502
    // ==============================================================================================

    /**
     * {@code 1300-SETUP-SCREEN-ATTRS} at line 502: protection, cursor and colour.
     *
     * <p>Both filter fields arrive protected when the previous screen was the card list, lines 505 to
     * 512, because the list screen has already chosen them; otherwise both are unprotected and
     * modified. The same condition removes their highlight at lines 527 to 531.
     *
     * <p><strong>The cursor decision at lines 515 to 524 is an {@code EVALUATE TRUE} whose arm order is
     * the contract.</strong> Two arms share a body and are listed first, so a faulted <em>or</em> blank
     * account filter takes the cursor even when the card filter is also at fault; only if neither
     * account arm holds do the two card arms get a chance; and the catch-all returns the cursor to the
     * account filter. Reordering would move the cursor on a turn where both fields are wrong.
     *
     * <p>Lines 541 to 551 write the decoration marker into a blank filter field and highlight it -
     * <strong>but only while the re-enter gate is up</strong>. That gate is what makes field-level
     * decoration conditional across this whole family: a first entry shows an empty field, and only a
     * re-submission shows the marker. The two conditions are kept separate here for the same reason the
     * source keeps them separate.
     *
     * @param state the turn's working storage
     */
    private void setupScreenAttrs(final TurnState state) {
        // IF CDEMO-LAST-MAPSET = LIT-CCLISTMAPSET AND CDEMO-FROM-PROGRAM = LIT-CCLISTPGM, 505 to 512.
        final boolean handedOverByCardList = arrivedFromCardList(state.context)
                && LIT_CCLISTMAPSET.equals(trimmedProgramName(state.context.lastMapset()));
        state.accountIdProtected = handedOverByCardList;
        state.cardNumberProtected = handedOverByCardList;

        // EVALUATE TRUE at lines 515 to 524. Arm order preserved; the catch-all is the last arm.
        if (state.accountFilterState.isNotOk() || state.accountFilterState.isBlank()) {
            // MOVE -1 TO ACCTSIDL at line 518.
            state.focusField = FIELD_ACCOUNT_ID;
        } else if (state.cardFilterState.isNotOk() || state.cardFilterState.isBlank()) {
            // MOVE -1 TO CARDSIDL at line 521.
            state.focusField = FIELD_CARD_NUMBER;
        } else {
            // WHEN OTHER at line 522.
            state.focusField = FIELD_ACCOUNT_ID;
        }

        // MOVE DFHDFCOL to both colour attributes at lines 529 to 530 when the list screen handed over.
        if (handedOverByCardList) {
            state.accountIdHighlighted = false;
            state.cardNumberHighlighted = false;
        }

        // IF FLG-ACCTFILTER-NOT-OK ... MOVE DFHRED at lines 533 to 535.
        if (state.accountFilterState.isNotOk()) {
            state.accountIdHighlighted = true;
        }

        // IF FLG-CARDFILTER-NOT-OK ... MOVE DFHRED at lines 537 to 539.
        if (state.cardFilterState.isNotOk()) {
            state.cardNumberHighlighted = true;
        }

        // IF FLG-ACCTFILTER-BLANK AND CDEMO-PGM-REENTER at lines 541 to 545.
        if (state.accountFilterState.isBlank() && state.reEnter) {
            state.accountIdField = boundedField(DECORATION_MARKER, ACCOUNT_ID_WIDTH);
            state.accountIdHighlighted = true;
        }

        // IF FLG-CARDFILTER-BLANK AND CDEMO-PGM-REENTER at lines 547 to 551.
        if (state.cardFilterState.isBlank() && state.reEnter) {
            state.cardNumberField = boundedField(DECORATION_MARKER, CARD_NUMBER_WIDTH);
            state.cardNumberHighlighted = true;
        }

        // IF WS-NO-INFO-MESSAGE ... DFHBMDAR else DFHNEUTR at lines 553 to 557.
        state.infoMessageDarkened = state.noInfoMessage();

        setupScreenAttrsExit(state);
    }

    /**
     * {@code 1300-SETUP-SCREEN-ATTRS-EXIT} at line 559.
     *
     * @param state the turn's working storage
     */
    private void setupScreenAttrsExit(final TurnState state) {
        paragraphExit(state, "1300-SETUP-SCREEN-ATTRS-EXIT", 559);
    }

    // ==============================================================================================
    // 1400-SEND-SCREEN, line 563
    // ==============================================================================================

    /**
     * {@code 1400-SEND-SCREEN} at line 563: transmits the map and raises the re-enter gate.
     *
     * <p>Records the next mapset and map at lines 565 to 566, then
     * {@code SET CDEMO-PGM-REENTER TO TRUE} at line 567 - which is the single place the gate goes up, so
     * every path that presents a screen arms the next turn to be treated as a re-submission. The send
     * itself at lines 569 to 576 has no equivalent beyond marking the turn as having presented, because
     * the outbound map is the returned value.
     *
     * @param state the turn's working storage
     */
    private void sendScreen(final TurnState state) {
        // MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET and LIT-THISMAP TO CCARD-NEXT-MAP, 565 to 566. The
        // mapset literal is eight characters and the work-area field is seven, so it truncates.
        state.nextMapset = boundedField(LIT_THISMAPSET, MAP_NAME_WIDTH);
        state.nextMap = boundedField(LIT_THISMAP, MAP_NAME_WIDTH);

        // SET CDEMO-PGM-REENTER TO TRUE at line 567.
        state.reEnter = true;
        state.context = state.context.withReEntry();

        // EXEC CICS SEND MAP ... CURSOR ERASE FREEKB at lines 569 to 576.
        state.screenSent = true;

        sendScreenExit(state);
    }

    /**
     * {@code 1400-SEND-SCREEN-EXIT} at line 578.
     *
     * @param state the turn's working storage
     */
    private void sendScreenExit(final TurnState state) {
        paragraphExit(state, "1400-SEND-SCREEN-EXIT", 578);
    }

    // ==============================================================================================
    // 2000-PROCESS-INPUTS, line 582
    // ==============================================================================================

    /**
     * {@code 2000-PROCESS-INPUTS} at line 582: receives the screen, edits it, and records where the
     * next turn should go.
     *
     * <p>Lines 587 to 590 move the summary message and this member's own program, mapset and map names
     * into the work area, so a re-submission that fails still carries a complete work area back.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen
     */
    private void processInputs(final TurnState state, final CardDetailScreenInput input) {
        receiveMap(state, input);
        editMapInputs(state);

        // MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG at line 587, then the three names at lines 588 to 590.
        state.errorMessageField = boundedField(state.returnMessage, ERROR_MESSAGE_FIELD_WIDTH);
        state.nextProgram = boundedField(LIT_THISPGM, PROGRAM_NAME_WIDTH);
        state.nextMapset = boundedField(LIT_THISMAPSET, MAP_NAME_WIDTH);
        state.nextMap = boundedField(LIT_THISMAP, MAP_NAME_WIDTH);

        processInputsExit(state);
    }

    /**
     * {@code 2000-PROCESS-INPUTS-EXIT} at line 593.
     *
     * @param state the turn's working storage
     */
    private void processInputsExit(final TurnState state) {
        paragraphExit(state, "2000-PROCESS-INPUTS-EXIT", 593);
    }

    // ==============================================================================================
    // 2100-RECEIVE-MAP, line 596
    // ==============================================================================================

    /**
     * {@code 2100-RECEIVE-MAP} at line 596: {@code EXEC CICS RECEIVE MAP INTO(CCRDSLAI)}.
     *
     * <p>The receive is what bounds each transmitted value to its declared field width, so that is done
     * here rather than being left to a caller: the account filter is eleven characters and the card
     * filter is sixteen. A value the terminal did not transmit arrives as low values, which is
     * represented as a blank field of the declared width, and a value longer than the field is
     * truncated on the right exactly as a move into it would be.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen
     */
    private void receiveMap(final TurnState state, final CardDetailScreenInput input) {
        state.receivedAccountId = boundedField(input.accountIdFilter(), ACCOUNT_ID_WIDTH);
        state.receivedCardNumber = boundedField(input.cardNumberFilter(), CARD_NUMBER_WIDTH);
        receiveMapExit(state);
    }

    /**
     * {@code 2100-RECEIVE-MAP-EXIT} at line 605.
     *
     * @param state the turn's working storage
     */
    private void receiveMapExit(final TurnState state) {
        paragraphExit(state, "2100-RECEIVE-MAP-EXIT", 605);
    }

    // ==============================================================================================
    // 2200-EDIT-MAP-INPUTS, line 608
    // ==============================================================================================

    /**
     * {@code 2200-EDIT-MAP-INPUTS} at line 608: the whole input edit.
     *
     * <p>Opens optimistically at lines 610 to 612 - input good, both filters valid - then maps each
     * transmitted field into the work area, treating the decoration marker and an all-space field alike
     * as "nothing supplied" at lines 615 to 627. That mapping is why a field the previous turn decorated
     * with a marker is not then read back as a literal asterisk.
     *
     * <p>Both per-field edits run at lines 630 to 634. <strong>Both always run</strong>: the account
     * edit does not short-circuit the card edit, so every faulted field raises its own flag and every
     * flag reaches the error list. Only the summary message is first-past-the-post, because the source
     * gates each assignment on that field still being empty.
     *
     * <p>The cross-field edit at lines 637 to 640 is the exception to that gate: when both filters are
     * blank it raises its text <strong>unconditionally</strong>, overwriting the account prompt the
     * per-field edit has already set. A turn that supplies neither filter therefore ends on
     * {@code No input received} and not on {@code Account number not provided}, and reproducing the gate
     * here would produce the wrong text.
     *
     * @param state the turn's working storage
     */
    private void editMapInputs(final TurnState state) {
        // SET INPUT-OK, FLG-CARDFILTER-ISVALID and FLG-ACCTFILTER-ISVALID at lines 610 to 612.
        state.inputState = InputState.OK;
        state.cardFilterState = FilterState.VALID;
        state.accountFilterState = FilterState.VALID;

        // IF ACCTSIDI = '*' OR SPACES ... MOVE LOW-VALUES TO CC-ACCT-ID, lines 615 to 620.
        state.workAreaAccountId =
                CobolStringUtils.isUnsuppliedNumericLexeme(state.receivedAccountId)
                        ? blankField(ACCOUNT_ID_WIDTH)
                        : state.receivedAccountId;

        // IF CARDSIDI = '*' OR SPACES ... MOVE LOW-VALUES TO CC-CARD-NUM, lines 622 to 627.
        state.workAreaCardNumber =
                CobolStringUtils.isUnsuppliedNumericLexeme(state.receivedCardNumber)
                        ? blankField(CARD_NUMBER_WIDTH)
                        : state.receivedCardNumber;

        // PERFORM 2210-EDIT-ACCOUNT at line 630 and 2220-EDIT-CARD at line 633.
        editAccount(state);
        editCard(state);

        // IF FLG-ACCTFILTER-BLANK AND FLG-CARDFILTER-BLANK at lines 637 to 640. Ungated by design.
        if (state.accountFilterState.isBlank() && state.cardFilterState.isBlank()) {
            state.returnMessage = MSG_NO_SEARCH_CRITERIA_RECEIVED;
        }

        editMapInputsExit(state);
    }

    /**
     * {@code 2200-EDIT-MAP-INPUTS-EXIT} at line 643.
     *
     * @param state the turn's working storage
     */
    private void editMapInputsExit(final TurnState state) {
        paragraphExit(state, "2200-EDIT-MAP-INPUTS-EXIT", 643);
    }

    // ==============================================================================================
    // 2210-EDIT-ACCOUNT, line 647
    // ==============================================================================================

    /**
     * {@code 2210-EDIT-ACCOUNT} at line 647: the account filter must be an eleven-digit non-zero
     * number, or absent.
     *
     * <p>Pessimistic opening at line 648, then two guarded exits and a success arm. The two
     * {@code GO TO 2210-EDIT-ACCOUNT-EXIT} statements at lines 660 and 674 jump forward to the range's
     * end point, so each becomes an early {@code return}; neither of this member's jumps goes backwards,
     * so there is no loop to rebuild.
     *
     * <p><strong>An all-zero field counts as not supplied, not as invalid.</strong> The first test at
     * lines 651 to 653 reads {@code CC-ACCT-ID = LOW-VALUES OR SPACES OR CC-ACCT-ID-N = ZEROS}, so a
     * field of eleven zeros takes the blank arm and yields {@code MISSING} rather than
     * {@code INVALID} - which is also why the message speaks of a "non zero" number.
     *
     * <p><strong>The numeric test is the COBOL class condition, not a parse.</strong>
     * {@code IF CC-ACCT-ID IS NOT NUMERIC} at line 665 is true unless every one of the eleven positions
     * holds a digit, so a five-digit entry left-justified in the field fails on its trailing spaces -
     * exactly what the message says. Membership is tested against the ASCII digit range explicitly,
     * because the platform's own digit predicate is Unicode-aware and would accept digits the class
     * condition rejects.
     *
     * <p>The message the failure arm actually moves is the literal at lines 669 to 671, not the
     * condition name declared at lines 146 to 147 whose text differs. The declared name is never set
     * anywhere in the member, so the literal governs and the unused name is left unused.
     *
     * @param state the turn's working storage
     */
    private void editAccount(final TurnState state) {
        // SET FLG-ACCTFILTER-NOT-OK TO TRUE at line 648.
        state.accountFilterState = FilterState.NOT_OK;

        if (isZeroOrUnsupplied(state.workAreaAccountId)) {
            // Lines 654 to 660.
            state.inputState = InputState.ERROR;
            state.accountFilterState = FilterState.BLANK;
            state.raiseMessageIfUnset(MSG_PROMPT_FOR_ACCOUNT);
            // MOVE ZEROES TO CDEMO-ACCT-ID at line 659.
            state.contextAccountId = blankField(ACCOUNT_ID_WIDTH);
            state.recordFieldError(PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                    state.accountFilterState, MSG_PROMPT_FOR_ACCOUNT);
            editAccountExit(state);
            return;
        }

        if (!isAllAsciiDigits(state.workAreaAccountId)) {
            // Lines 666 to 674.
            state.inputState = InputState.ERROR;
            state.accountFilterState = FilterState.NOT_OK;
            state.raiseMessageIfUnset(MSG_ACCOUNT_FILTER_NOT_NUMERIC);
            // MOVE ZERO TO CDEMO-ACCT-ID at line 673.
            state.contextAccountId = blankField(ACCOUNT_ID_WIDTH);
            state.recordFieldError(PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                    state.accountFilterState, MSG_ACCOUNT_FILTER_NOT_NUMERIC);
            editAccountExit(state);
            return;
        }

        // MOVE CC-ACCT-ID TO CDEMO-ACCT-ID and SET FLG-ACCTFILTER-ISVALID, lines 676 to 677.
        state.contextAccountId = state.workAreaAccountId;
        state.accountFilterState = FilterState.VALID;
        editAccountExit(state);
    }

    /**
     * {@code 2210-EDIT-ACCOUNT-EXIT} at line 681: the end point both forward jumps target.
     *
     * @param state the turn's working storage
     */
    private void editAccountExit(final TurnState state) {
        paragraphExit(state, "2210-EDIT-ACCOUNT-EXIT", 681);
    }

    // ==============================================================================================
    // 2220-EDIT-CARD, line 685
    // ==============================================================================================

    /**
     * {@code 2220-EDIT-CARD} at line 685: the card filter must be a sixteen-digit non-zero number, or
     * absent.
     *
     * <p>Structurally identical to the account edit - pessimistic opening at line 688, blank arm at
     * lines 691 to 702, non-numeric arm at lines 706 to 715, success arm at lines 716 to 719 - with the
     * width and the message changed. The two forward jumps at lines 701 and 715 become early returns.
     *
     * <p>The success arm differs from the account edit's in one detail worth keeping: it moves the
     * <em>numeric</em> redefinition at line 717 rather than the character field, which the account edit
     * does at line 676. Since the arm is reached only when every position is a digit, the two are the
     * same sixteen characters, and the character form is carried so the value's leading zeros survive.
     *
     * @param state the turn's working storage
     */
    private void editCard(final TurnState state) {
        // SET FLG-CARDFILTER-NOT-OK TO TRUE at line 688.
        state.cardFilterState = FilterState.NOT_OK;

        if (isZeroOrUnsupplied(state.workAreaCardNumber)) {
            // Lines 694 to 701.
            state.inputState = InputState.ERROR;
            state.cardFilterState = FilterState.BLANK;
            state.raiseMessageIfUnset(MSG_PROMPT_FOR_CARD);
            // MOVE ZEROES TO CDEMO-CARD-NUM at line 700.
            state.contextCardNumber = blankField(CARD_NUMBER_WIDTH);
            state.recordFieldError(PROPERTY_CARD_NUMBER, FIELD_CARD_NUMBER,
                    state.cardFilterState, MSG_PROMPT_FOR_CARD);
            editCardExit(state);
            return;
        }

        if (!isAllAsciiDigits(state.workAreaCardNumber)) {
            // Lines 707 to 715.
            state.inputState = InputState.ERROR;
            state.cardFilterState = FilterState.NOT_OK;
            state.raiseMessageIfUnset(MSG_CARD_FILTER_NOT_NUMERIC);
            // MOVE ZERO TO CDEMO-CARD-NUM at line 714.
            state.contextCardNumber = blankField(CARD_NUMBER_WIDTH);
            state.recordFieldError(PROPERTY_CARD_NUMBER, FIELD_CARD_NUMBER,
                    state.cardFilterState, MSG_CARD_FILTER_NOT_NUMERIC);
            editCardExit(state);
            return;
        }

        // MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM and SET FLG-CARDFILTER-ISVALID, lines 717 to 718.
        state.contextCardNumber = state.workAreaCardNumber;
        state.cardFilterState = FilterState.VALID;
        editCardExit(state);
    }

    /**
     * {@code 2220-EDIT-CARD-EXIT} at line 722: the end point both forward jumps target.
     *
     * @param state the turn's working storage
     */
    private void editCardExit(final TurnState state) {
        paragraphExit(state, "2220-EDIT-CARD-EXIT", 722);
    }

    // ==============================================================================================
    // 9000-READ-DATA, line 726
    // ==============================================================================================

    /**
     * {@code 9000-READ-DATA} at line 726: the read driver.
     *
     * <p>Performs the card-number read at lines 728 to 729 and <strong>nothing else</strong>. The
     * account-keyed read declared at line 779 is not reached from here or from anywhere, which is why it
     * is translated but left unwired.
     *
     * @param state the turn's working storage
     */
    private void readData(final TurnState state) {
        getCardByAcctCard(state);
        readDataExit(state);
    }

    /**
     * {@code 9000-READ-DATA-EXIT} at line 732.
     *
     * @param state the turn's working storage
     */
    private void readDataExit(final TurnState state) {
        paragraphExit(state, "9000-READ-DATA-EXIT", 732);
    }

    // ==============================================================================================
    // 9100-GETCARD-BYACCTCARD, line 736
    // ==============================================================================================

    /**
     * {@code 9100-GETCARD-BYACCTCARD} at line 736: reads the card cluster by card number.
     *
     * <p><strong>Despite the name, the account number takes no part in this read.</strong> The move that
     * would have supplied the account half of the key is commented out at line 739, only the card number
     * is loaded at line 740, and the read at lines 742 to 750 keys
     * {@code RIDFLD(WS-CARD-RID-CARDNUM)} against base cluster {@code CARDDAT}. So this becomes the
     * inherited keyed finder over the primary key, and an absent result is the not-found response.
     *
     * <p>The response evaluation at lines 752 to 772 has three arms and their order is preserved.
     * <strong>Its not-found arm faults both filter fields</strong>, lines 757 to 758, and gates its
     * message on the summary field still being empty, line 759 - both of which differ from the
     * account-keyed read's not-found arm, and neither difference may be smoothed away.
     *
     * <p>The catch-all arm at lines 762 to 771 is reached when the store answers but the record it
     * returns cannot be used: it fails the layout edits this service holds. That is precisely a response
     * that is neither success nor not-found. Note two asymmetries the source has and this keeps: the
     * account flag is raised <em>inside</em> the message gate at lines 764 to 766, so it is set only
     * while no message has been raised yet; and the file-error text is then moved
     * <em>unconditionally</em> at line 771, so it overwrites whatever the summary already held. The
     * catch-all is the one arm that is not first-past-the-post.
     *
     * @param state the turn's working storage
     */
    private void getCardByAcctCard(final TurnState state) {
        // MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM at line 740.
        state.cardRecordKey = state.workAreaCardNumber;
        state.errorResource = RESOURCE_CARD_BASE_CLUSTER;
        state.errorOperation = OPERATION_READ;

        // EXEC CICS READ FILE(LIT-CARDFILENAME) at lines 742 to 750. A failure to answer at all is not
        // one of the three arms below: it transfers to the handler armed at line 250.
        final Optional<Card> found = cardRepository.findById(state.cardRecordKey);

        if (found.isEmpty()) {
            // WHEN DFHRESP(NOTFND) at lines 755 to 761.
            state.rawFileStatus = STATUS_RECORD_NOT_FOUND;
            state.inputState = InputState.ERROR;
            state.accountFilterState = FilterState.NOT_OK;
            state.cardFilterState = FilterState.NOT_OK;
            state.raiseMessageIfUnset(MSG_NO_CARDS_FOR_SEARCH_CONDITION);
            state.recordFieldError(PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                    state.accountFilterState, MSG_NO_CARDS_FOR_SEARCH_CONDITION);
            state.recordFieldError(PROPERTY_CARD_NUMBER, FIELD_CARD_NUMBER,
                    state.cardFilterState, MSG_NO_CARDS_FOR_SEARCH_CONDITION);
            LOG.debug("Card-number read found nothing: resource={} status={}",
                    RESOURCE_CARD_BASE_CLUSTER, STATUS_RECORD_NOT_FOUND);
            getCardByAcctCardExit(state);
            return;
        }

        final Card candidate = found.get();
        if (violatesRecordLayout(candidate)) {
            // WHEN OTHER at lines 762 to 771.
            state.inputState = InputState.ERROR;
            if (state.returnMessage.isEmpty()) {
                state.accountFilterState = FilterState.NOT_OK;
            }
            recordFileError(state, RESOURCE_CARD_BASE_CLUSTER);
            getCardByAcctCardExit(state);
            return;
        }

        // WHEN DFHRESP(NORMAL) at lines 753 to 754.
        state.rawFileStatus = STATUS_SUCCESS;
        state.card = candidate;
        state.setFoundCardsForAccount();
        getCardByAcctCardExit(state);
    }

    /**
     * {@code 9100-GETCARD-BYACCTCARD-EXIT} at line 775.
     *
     * @param state the turn's working storage
     */
    private void getCardByAcctCardExit(final TurnState state) {
        paragraphExit(state, "9100-GETCARD-BYACCTCARD-EXIT", 775);
    }

    // ==============================================================================================
    // 9150-GETCARD-BYACCT, line 779 - translated, and deliberately not wired
    // ==============================================================================================

    /**
     * {@code 9150-GETCARD-BYACCT} at line 779: reads the card cluster through the account-keyed
     * alternate index whose resource name the member declares at <strong>line 190</strong>.
     *
     * <p><strong>No {@code PERFORM} in the member reaches this paragraph.</strong> A census of every
     * {@code PERFORM} statement in {@code app/cbl/COCRDSLC.cbl} finds the paragraph name only in its own
     * two labels, and the read driver at line 726 performs the card-number read alone. It is translated
     * because the paragraph exists and the traceability matrix must resolve it, and it is left unwired
     * because wiring it would add a flow the legacy does not have. Package-private rather than public
     * for the same reason: it is exercisable without becoming part of this service's contract.
     *
     * <p><strong>The alternate index is non-unique, so first-match selection is the contract.</strong>
     * The legacy direct read of a duplicate-bearing path returns one record, not a set, and never raises
     * a too-many-results condition. The declared repository finder reproduces that by resolving the
     * ambiguity in the query - lowest card number first, one row - which is the deterministic form of
     * "take the first" and is strictly better than picking an element out of an unordered collection. An
     * absent result is the analogue of the legacy not-found response and is not an error.
     *
     * <p><strong>This not-found arm is deliberately different from the card-number read's.</strong> At
     * lines 796 to 799 it faults <em>only</em> the account filter and it sets its message
     * <em>unconditionally</em> - there is no {@code IF WS-RETURN-MSG-OFF} gate here, unlike line 759. Its
     * catch-all at lines 800 to 807 likewise raises the account flag ungated, where the card-number
     * read raises it inside the gate. Both asymmetries are reproduced rather than harmonised.
     *
     * @param state     the turn's working storage
     * @param accountId the eleven-character account identifier to key on
     */
    void getCardByAcct(final TurnState state, final String accountId) {
        // MOVE the account identifier into WS-CARD-RID-ACCT-ID, the alternate-index key.
        state.cardAccountKey = boundedField(accountId, ACCOUNT_ID_WIDTH);
        state.errorResource = RESOURCE_CARD_ACCOUNT_PATH;
        state.errorOperation = OPERATION_READ;

        // EXEC CICS READ FILE(LIT-CARDFILENAME-ACCT-PATH) RIDFLD(WS-CARD-RID-ACCT-ID), 783 to 791.
        final Optional<Card> found =
                cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(state.cardAccountKey);

        if (found.isEmpty()) {
            // WHEN DFHRESP(NOTFND) at lines 796 to 799. Ungated message, single field faulted.
            state.rawFileStatus = STATUS_RECORD_NOT_FOUND;
            state.inputState = InputState.ERROR;
            state.accountFilterState = FilterState.NOT_OK;
            state.returnMessage = MSG_ACCOUNT_NOT_IN_CARD_DATABASE;
            state.recordFieldError(PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                    state.accountFilterState, MSG_ACCOUNT_NOT_IN_CARD_DATABASE);
            LOG.debug("Account-keyed read found nothing: resource={} status={}",
                    RESOURCE_CARD_ACCOUNT_PATH, STATUS_RECORD_NOT_FOUND);
            getCardByAcctExit(state);
            return;
        }

        final Card candidate = found.get();
        if (violatesRecordLayout(candidate)) {
            // WHEN OTHER at lines 800 to 807. The account flag is raised ungated here.
            state.inputState = InputState.ERROR;
            state.accountFilterState = FilterState.NOT_OK;
            recordFileError(state, RESOURCE_CARD_ACCOUNT_PATH);
            getCardByAcctExit(state);
            return;
        }

        // WHEN DFHRESP(NORMAL) at lines 794 to 795.
        state.rawFileStatus = STATUS_SUCCESS;
        state.card = candidate;
        state.setFoundCardsForAccount();
        getCardByAcctExit(state);
    }

    /**
     * {@code 9150-GETCARD-BYACCT-EXIT} at line 810.
     *
     * @param state the turn's working storage
     */
    private void getCardByAcctExit(final TurnState state) {
        paragraphExit(state, "9150-GETCARD-BYACCT-EXIT", 810);
    }

    // ==============================================================================================
    // SEND-LONG-TEXT, line 820 - translated, and unreferenced in the source
    // ==============================================================================================

    /**
     * {@code SEND-LONG-TEXT} at line 820: transmits the five-hundred-character diagnostic field and
     * returns.
     *
     * <p>The source comments at lines 815 to 819 describe it as a debugging aid that should not be used
     * in the ordinary course, and the source takes its own advice twice over: no {@code PERFORM} reaches
     * this paragraph, and the field it sends, declared at line 125, is never written anywhere in the
     * member. Translated for completeness and left unreferenced, on the same footing as the account-keyed
     * read.
     *
     * @param state    the turn's working storage
     * @param longText the diagnostic text to transmit
     */
    void sendLongText(final TurnState state, final String longText) {
        // EXEC CICS SEND TEXT FROM(WS-LONG-MSG) ERASE FREEKB at lines 821 to 826.
        state.returnMessage = (longText == null) ? NO_MESSAGE : longText;
        state.screenSent = true;
        // EXEC CICS RETURN at lines 828 to 829: no transaction identifier, so nothing is re-armed.
        state.reArmedTransactionId = NO_MESSAGE;
        sendLongTextExit(state);
    }

    /**
     * {@code SEND-LONG-TEXT-EXIT} at line 831.
     *
     * @param state the turn's working storage
     */
    private void sendLongTextExit(final TurnState state) {
        paragraphExit(state, "SEND-LONG-TEXT-EXIT", 831);
    }

    // ==============================================================================================
    // SEND-PLAIN-TEXT, line 838
    // ==============================================================================================

    /**
     * {@code SEND-PLAIN-TEXT} at line 838: transmits the summary message as unformatted text and
     * returns without re-arming.
     *
     * <p>Reached only from the catch-all dispatch arm at line 379. The plain
     * {@code EXEC CICS RETURN} at lines 846 to 847 carries no transaction identifier, so unlike the
     * common return it ends the conversation rather than re-arming it - which is why the re-armed
     * identifier is cleared here.
     *
     * @param state the turn's working storage
     */
    private void sendPlainText(final TurnState state) {
        // EXEC CICS SEND TEXT FROM(WS-RETURN-MSG) ERASE FREEKB at lines 839 to 844.
        state.errorMessageField = boundedField(state.returnMessage, ERROR_MESSAGE_FIELD_WIDTH);
        state.screenSent = true;
        // EXEC CICS RETURN at lines 846 to 847.
        state.reArmedTransactionId = NO_MESSAGE;
        LOG.warn("Card-detail dispatch reached its catch-all arm: culprit={} abendCode={} message={}",
                LIT_THISPGM, state.abendCode, state.returnMessage);
        sendPlainTextExit(state);
    }

    /**
     * {@code SEND-PLAIN-TEXT-EXIT} at line 849.
     *
     * @param state the turn's working storage
     */
    private void sendPlainTextExit(final TurnState state) {
        paragraphExit(state, "SEND-PLAIN-TEXT-EXIT", 849);
    }

    // ==============================================================================================
    // COPY 'CSSTRPFY' at lines 855 to 856 - the copybook-expansion boundary
    // ==============================================================================================

    /**
     * The in-line {@code COPY 'CSSTRPFY'} unit at lines 855 to 856, which expands the shared
     * attention-key store into this member's procedure division.
     *
     * <p>This member is one of the copybook's five includers. The expansion is represented as its own
     * method because the traceability matrix counts it, and it <strong>delegates</strong> rather than
     * duplicating: the copybook's 28-clause selection, and with it the fold of program-function keys 13
     * to 24 back onto keys 1 to 12, belongs to the module's key translator and is credited to it.
     *
     * <p>An identifier the selection does not recognise assigns nothing, because the copybook declares
     * no catch-all clause. That is why the translator answers with an empty result rather than an unknown
     * constant, and it is the one case in which this member raises the error flag and shows the
     * catalogue's invalid-key text at its full contractual width, untrimmed.
     *
     * @param state                  the turn's working storage
     * @param attentionKeyIdentifier the raw attention identifier, or {@code null} when none arrived
     */
    private void expandStorePfKeyCopybook(final TurnState state,
            final String attentionKeyIdentifier) {
        yyyyStorePfKey(state, attentionKeyIdentifier);
        yyyyStorePfKeyExit(state);
    }

    /**
     * {@code YYYY-STORE-PFKEY}, line 17 of {@code app/cpy/CSSTRPFY.cpy}: maps the attention identifier
     * onto the work area's attention field.
     *
     * <p>The mapping itself is the key translator's, so this method calls it and stores what comes back.
     * Keys 13 to 24 are therefore not distinct here and must not be treated as such - an identifier
     * naming key 15 arrives as the third program-function action and behaves exactly as key 3.
     *
     * <p>An unrecognised identifier leaves the field unassigned, matching a selection with no catch-all,
     * and raises the error flag with the invalid-key message. The message is taken from the catalogue at
     * its full fifty-character width and is <strong>never trimmed</strong>: the trailing spaces are part
     * of the screen contract.
     *
     * @param state                  the turn's working storage
     * @param attentionKeyIdentifier the raw attention identifier, or {@code null} when none arrived
     */
    private void yyyyStorePfKey(final TurnState state, final String attentionKeyIdentifier) {
        final Optional<KeyAction> decoded = (attentionKeyIdentifier == null)
                ? Optional.<KeyAction>empty()
                : PfKeyTranslator.translate(attentionKeyIdentifier);

        if (decoded.isEmpty()) {
            state.keyAction = null;
            state.attentionKeyUnrecognised = true;
            state.inputState = InputState.ERROR;
            state.returnMessage = messageCatalogService.invalidKeyMessage();
            LOG.debug("Attention identifier outside the recognised vocabulary: member={}",
                    LIT_THISPGM);
            return;
        }
        state.keyAction = decoded.get();
    }

    /**
     * {@code YYYY-STORE-PFKEY-EXIT}, line 80 of {@code app/cpy/CSSTRPFY.cpy}.
     *
     * @param state the turn's working storage
     */
    private void yyyyStorePfKeyExit(final TurnState state) {
        paragraphExit(state, "YYYY-STORE-PFKEY-EXIT", 80);
    }

    // ==============================================================================================
    // ABEND-ROUTINE, line 857
    // ==============================================================================================

    /**
     * {@code ABEND-ROUTINE} at line 857: the handler armed at lines 250 to 252, ending in
     * {@code EXEC CICS ABEND ABCODE('9999')} at <strong>line 875</strong>.
     *
     * <p><strong>Emit, then raise - in that order, and never the other way round.</strong> The legacy
     * routine transmits its context to the terminal at lines 865 to 869, deregisters the handler at lines
     * 871 to 873, and only then abends. The diagnostic reached the operator whether or not anything
     * survived the abend, so it is written here, before the delegate is called, rather than from a
     * {@code catch} that has already unwound past the point of failure. The diagnostic names the raw
     * two-character file status and the resource, which is what an operator needs to know which cluster
     * refused the read.
     *
     * <p>Deregistration needs no counterpart: every occurrence of the {@code CANCEL} token in the estate
     * is that CICS option and never the COBOL statement, and the Java equivalent of removing a handler is
     * the absence of a {@code catch}.
     *
     * <p>The default text at line 860 is applied only when none was already set, exactly as the source
     * guards it. The card verification code is not part of the diagnostic and never will be.
     *
     * @param state   the turn's working storage
     * @param failure the failure that transferred control here; carried into the log, not into the text
     */
    private void abendRoutine(final TurnState state, final Throwable failure) {
        // IF ABEND-MSG EQUAL LOW-VALUES ... at lines 859 to 861.
        if (state.abendMessage.isEmpty()) {
            state.abendMessage = MSG_UNEXPECTED_ABEND;
        }
        // A read that raised rather than answering is a permanent input-output failure, which is the
        // only status in the estate's observed vocabulary that describes it.
        final String rawStatus =
                state.rawFileStatus.isEmpty() ? STATUS_PERMANENT_ERROR : state.rawFileStatus;
        final String resource =
                state.errorResource.isEmpty() ? RESOURCE_CARD_BASE_CLUSTER : state.errorResource;

        // EMIT FIRST. MOVE LIT-THISPGM TO ABEND-CULPRIT at line 863, then the terminal send at 865.
        LOG.error("ABENDING TRANSACTION {}: culprit={} fileStatus={} resource={} operation={}"
                        + " reason={}",
                LIT_THISTRANID, LIT_THISPGM, rawStatus, resource, OPERATION_READ,
                state.abendMessage, failure);

        // THEN RAISE. EXEC CICS ABEND ABCODE('9999') at lines 875 to 877; the abend service owns the
        // code and the context layout, so neither is restated here.
        state.abendCode = ABEND_CODE_UNEXPECTED_DATA;
        abendService.abendOnline(LIT_THISPGM, state.abendMessage, state.abendMessage);
    }

    // ==============================================================================================
    // Private helpers. Not paragraphs: the mechanics the paragraphs above are written in terms of.
    // ==============================================================================================

    /**
     * The common end point of a {@code PERFORM x THRU x-EXIT} range.
     *
     * <p>Every {@code EXIT} paragraph of the member routes here. The COBOL statement's only effect is to
     * be a reachable end point, so its translation records that the range completed and returns, which
     * keeps paragraph-level auditability observable at run time instead of only at review time.
     *
     * @param state         the turn's working storage
     * @param paragraphName the legacy paragraph name
     * @param sourceLine    the line the paragraph is declared on
     */
    private static void paragraphExit(final TurnState state, final String paragraphName,
            final int sourceLine) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Paragraph range complete: member={} paragraph={} line={} inputState={}",
                    LIT_THISPGM, paragraphName, sourceLine, state.inputState);
        }
    }

    /**
     * Records the catch-all response arm's diagnostic fields and composes the file-error message.
     *
     * <p>Shared by both read paragraphs because both compose the identical message from the identical
     * fields, differing only in the resource they name. The composition is byte-exact: the eight declared
     * segments sum to exactly the width of the field they are moved into, so the five-character trailing
     * filler falls outside it and nothing is truncated.
     *
     * <p>The relational store reports no CICS response pair, so the two numeric slots carry a stated
     * convention rather than an invented pair of CICS constants: the first holds the numeric form of the
     * two-character file status and the second holds zero.
     *
     * @param state        the turn's working storage
     * @param resourceName the cluster or path the failed read named
     */
    private static void recordFileError(final TurnState state, final String resourceName) {
        state.rawFileStatus = STATUS_RECORD_LENGTH_MISMATCH;
        state.errorOperation = OPERATION_READ;
        state.errorResource = resourceName;
        state.responseCode = RESPONSE_RECORD_LENGTH_MISMATCH;
        state.reasonCode = 0;
        // MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG at lines 771 and 807 - ungated, so it overwrites.
        state.returnMessage = composeFileErrorMessage(state);
        LOG.error("Card read returned an unusable record: fileStatus={} operation={} resource={}",
                state.rawFileStatus, state.errorOperation, resourceName);
    }

    /**
     * Composes {@code WS-FILE-ERROR-MESSAGE}, lines 102 to 121, at exactly the width of the field it is
     * moved into.
     *
     * @param state the turn's working storage
     * @return the composed message, exactly {@value #RETURN_MESSAGE_WIDTH} characters
     */
    private static String composeFileErrorMessage(final TurnState state) {
        return FILE_ERROR_PREFIX
                + boundedField(state.errorOperation, ERROR_OPNAME_WIDTH)
                + FILE_ERROR_ON
                + boundedField(state.errorResource, ERROR_FILE_WIDTH)
                + FILE_ERROR_RETURNED_RESP
                + renderResponseCode(state.responseCode)
                + FILE_ERROR_RESP2
                + renderResponseCode(state.reasonCode);
    }

    /**
     * Renders a binary response code into its ten-character alphanumeric slot.
     *
     * <p>A move from {@code PIC S9(09) COMP} into {@code PIC X(10)} renders the sender's full declared
     * digit count and then left-justifies it in the receiver, so the result is nine digits followed by
     * one space. The sign is not rendered, because the receiving item is alphanumeric.
     *
     * @param value the response code
     * @return exactly {@value #ERROR_RESP_WIDTH} characters
     */
    private static String renderResponseCode(final int value) {
        final String digits = CobolStringUtils.rightJustifyZeroFill(
                Integer.toString(Math.abs(value)), RESPONSE_CODE_DIGITS);
        return boundedField(digits, ERROR_RESP_WIDTH);
    }

    /**
     * Reports whether a retrieved record violates the layout the member edits it against, which is the
     * catch-all response arm's trigger.
     *
     * <p>Four checks, each derived from a declaration rather than invented. The card number must be
     * sixteen digits, which is the rule the card-filter edit at line 706 enforces on the same field. The
     * verification code must be three digits, from the numeric redefinition at lines 77 to 78. The expiry
     * components must be four, two and two digits, from the redefinition at lines 85 to 90. The active
     * status must be one of the two characters the status vocabulary declares.
     *
     * <p><strong>The verification code is never parsed to a number here.</strong> Its digits are tested
     * as characters, so a value of {@code 007} stays {@code "007"}, is never normalised, and is never
     * named in the failure diagnostic.
     *
     * @param candidate the retrieved record
     * @return {@code true} when the record cannot be presented
     */
    private static boolean violatesRecordLayout(final Card candidate) {
        final String cardNumber = boundedField(candidate.getCardNum(), CARD_NUMBER_WIDTH);
        final String verificationCode =
                boundedField(candidate.getCardCvvCd(), VERIFICATION_CODE_WIDTH);
        final String expiry = boundedField(candidate.getCardExpirationDate(), EXPIRY_DATE_WIDTH);
        final String activeStatus =
                boundedField(candidate.getCardActiveStatus(), CARD_STATUS_WIDTH);

        return !isAllAsciiDigits(cardNumber)
                || !isAllAsciiDigits(verificationCode)
                || !isAllAsciiDigits(fieldSlice(expiry, EXPIRY_YEAR_FROM, EXPIRY_YEAR_TO))
                || !isAllAsciiDigits(fieldSlice(expiry, EXPIRY_MONTH_FROM, EXPIRY_MONTH_TO))
                || !isAllAsciiDigits(fieldSlice(expiry, EXPIRY_DAY_FROM, EXPIRY_DAY_TO))
                || CardStatus.fromCode(activeStatus).isEmpty();
    }

    /**
     * The COBOL {@code IS NUMERIC} class condition on an alphanumeric item: true only when every
     * position holds a digit.
     *
     * <p>Membership is the ASCII range and nothing wider. {@code Character.isDigit} is Unicode-aware and
     * would accept decimal digits from other scripts, which the class condition rejects, so it is not
     * used - and using it would also make the outcome depend on the data's script rather than on the
     * layout. An empty value is not numeric, matching a class condition that has no position to satisfy.
     *
     * @param value the value to test; may be {@code null}, which is not numeric
     * @return {@code true} when the value is non-empty and every character is an ASCII digit
     */
    private static boolean isAllAsciiDigits(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < ASCII_ZERO || character > ASCII_NINE) {
                return false;
            }
        }
        return true;
    }

    /**
     * The composed "not supplied" test both filter edits open with, at lines 651 to 653 and 691 to 693:
     * low values, all spaces, or an all-zero value.
     *
     * <p>The third arm is the numeric redefinition compared against zeros, which is why a field of
     * eleven or sixteen zeros yields {@code MISSING} rather than {@code INVALID}, and why the message
     * speaks of a non-zero number. The decoration marker is included by the shared predicate, which is
     * harmless and correct: the map-to-work move at lines 615 and 622 has already turned a marker into
     * low values before either edit sees it.
     *
     * @param value the work-area value; may be {@code null}
     * @return {@code true} when the field carries no usable selection
     */
    private static boolean isZeroOrUnsupplied(final String value) {
        if (CobolStringUtils.isUnsuppliedNumericLexeme(value)) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != ASCII_ZERO) {
                return false;
            }
        }
        return true;
    }

    /**
     * A {@code MOVE} into {@code PIC X(width)}: left-justified, space-padded on the right, and truncated
     * on the right when the sender is longer.
     *
     * <p>An absent value is the low-values state a terminal leaves in a field it did not transmit, and is
     * rendered as a blank field of the declared width.
     *
     * @param value the sending value; may be {@code null}
     * @param width the receiving width in character positions
     * @return exactly {@code width} characters
     */
    private static String boundedField(final String value, final int width) {
        if (value == null) {
            return blankField(width);
        }
        if (value.length() == width) {
            return value;
        }
        if (value.length() > width) {
            final StringBuilder truncated = new StringBuilder(width);
            for (int index = 0; index < width; index++) {
                truncated.append(value.charAt(index));
            }
            return truncated.toString();
        }
        final StringBuilder padded = new StringBuilder(width);
        padded.append(value);
        while (padded.length() < width) {
            padded.append(SPACE);
        }
        return padded.toString();
    }

    /**
     * A positional field accessor, which is what a COBOL {@code REDEFINES} over a group gives: the
     * characters from one position up to but excluding another.
     *
     * <p>Used only to split screen-derived fields that the source itself splits by redefinition - the
     * expiry components at lines 85 to 90 and the two-digit header year at line 441. Fixed-width
     * <em>record image</em> decomposition is a different concern and belongs to the record-mapper layer,
     * which is why nothing here reads a record image.
     *
     * @param value             the value to read from; may be shorter than requested
     * @param fromIndex         the first position, zero-based and inclusive
     * @param toIndexExclusive  one past the last position
     * @return the characters in range, space-padded when the value is too short to supply them
     */
    private static String fieldSlice(final String value, final int fromIndex,
            final int toIndexExclusive) {
        final int width = toIndexExclusive - fromIndex;
        if (value == null) {
            return blankField(width);
        }
        final StringBuilder slice = new StringBuilder(width);
        for (int index = fromIndex; index < toIndexExclusive; index++) {
            slice.append(index < value.length() ? value.charAt(index) : SPACE);
        }
        return slice.toString();
    }

    /**
     * A blank field, which is what {@code INITIALIZE} writes into an alphanumeric item.
     *
     * @param width the field width in character positions
     * @return exactly {@code width} spaces
     */
    private static String blankField(final int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    /**
     * Renders a whole number into a zero-filled numeric field of the given width, which is what a move
     * into {@code PIC 9(width)} produces.
     *
     * @param value the value; its sign is not rendered, matching a move into an unsigned item
     * @param width the field width in character positions
     * @return exactly {@code width} characters
     */
    private static String numericField(final int value, final int width) {
        return CobolStringUtils.rightJustifyZeroFill(Integer.toString(Math.abs(value)), width);
    }

    /**
     * Reads a fixed-width program or mapset name as a comparable token.
     *
     * <p>The legacy comparison is between two fixed-width items, so trailing spaces are not significant
     * on either side; leading and trailing padding is therefore removed before comparing, while an absent
     * value compares as empty rather than raising.
     *
     * @param value the fixed-width name; may be {@code null}
     * @return the name without its field padding, never {@code null}
     */
    private static String trimmedProgramName(final String value) {
        return (value == null) ? NO_MESSAGE : value.trim();
    }

    /**
     * Rebuilds the navigation state with the fields this member writes, carrying every other field
     * through unchanged.
     *
     * <p>One helper rather than several, because the two call sites that rewrite state - the back
     * navigation at lines 309 to 329 and the repack at lines 397 to 400 - write the same set of fields
     * and differ only in the values. The identity fields are never written here: an echoed identity is
     * reconciled against the authenticated principal elsewhere, and rewriting it in a screen service
     * would put that reconciliation out of reach.
     *
     * @param base              the state to carry forward
     * @param fromTransactionId {@code CDEMO-FROM-TRANID}
     * @param fromProgram       {@code CDEMO-FROM-PROGRAM}
     * @param toTransactionId   {@code CDEMO-TO-TRANID}
     * @param toProgram         {@code CDEMO-TO-PROGRAM}
     * @param userTypeCode      {@code CDEMO-USER-TYPE}
     * @param accountId         {@code CDEMO-ACCT-ID}
     * @param cardNumber        {@code CDEMO-CARD-NUM}
     * @param lastMap           {@code CDEMO-LAST-MAP}
     * @param lastMapset        {@code CDEMO-LAST-MAPSET}
     * @return the rebuilt state, never {@code null}
     */
    private static NavigationContext contextWith(final NavigationContext base,
            final String fromTransactionId,
            final String fromProgram,
            final String toTransactionId,
            final String toProgram,
            final String userTypeCode,
            final String accountId,
            final String cardNumber,
            final String lastMap,
            final String lastMapset) {
        return new NavigationContext(
                fromTransactionId,
                fromProgram,
                toTransactionId,
                toProgram,
                base.userId(),
                userTypeCode,
                base.programContext(),
                base.customerId(),
                base.customerFirstName(),
                base.customerMiddleName(),
                base.customerLastName(),
                accountId,
                base.accountStatus(),
                cardNumber,
                lastMap,
                lastMapset);
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
    private static boolean isNavigationStateAbsent(final NavigationContext context) {
        return context == null || NavigationContext.empty().equals(context);
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
    private static ConversationState carriedState(final NavigationContext context) {
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
