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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.api.dto.FieldErrorDecorator;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.ScreenWorkArea;
import com.carddemo.domain.Card;
import com.carddemo.domain.enums.CardStatus;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import com.carddemo.util.CobolStringUtils;
import com.carddemo.util.PfKeyTranslator;

/**
 * One turn of the card-update transaction {@code CCUP}, translated paragraph for paragraph from
 * {@code app/cbl/COCRDUPC.cbl}.
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy authority {@code app/cbl/COCRDUPC.cbl}, transaction {@code CCUP}, <b>1,560 lines</b>, read at
 * checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} and carrying the upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19 in its trailer comment at lines 1558 to 1560.
 * Two further authorities are consulted rather than translated here: {@code app/cpy/CVCRD01Y.cpy}, the
 * screen work area included at line 268, whose sixteen attention-key condition names and three business
 * keys are carried by {@code ScreenWorkArea}; and {@code app/cpy/CSSTRPFY.cpy}, the attention-key store
 * included with quoted syntax at line 1528, whose two paragraphs are credited to
 * {@code PfKeyTranslator}. No legacy source text is transcribed - member names, transaction ids,
 * paragraph names, line numbers, field names, widths, file names and the operator message literals are
 * cited, and nothing else.
 *
 * <h2>Paragraph count: 48 measured against 45 in the action plan</h2>
 *
 * <p>A mechanical scan of Area A - column 7 not a comment marker, column 8 not blank - from the
 * {@code PROCEDURE DIVISION} header at line 366 to end of file returns <b>48</b> labels. They decompose
 * exactly: <b>45</b> paragraph labels declared in the member, which is the figure the action plan
 * records and which this class implements as 45 named methods; <b>2</b> paragraphs textually injected by
 * the single procedural {@code COPY 'CSSTRPFY'} directive at line 1528, namely the key-store paragraph
 * at copybook line 17 and its exit at copybook line 80, which are credited to {@code PfKeyTranslator}
 * and deliberately not duplicated here; and <b>1</b> {@code PROCEDURE DIVISION.} header line, which a
 * scanner counts as an Area-A label because it is one, though it is not a paragraph. The two figures are
 * therefore both right about different things: 45 paragraphs are declared, 47 paragraph bodies exist
 * once the copybook is expanded, and 48 Area-A labels are present. This member is one of the five
 * includers of that copybook.
 *
 * <h2>Family membership, and why the abend path is wired here</h2>
 *
 * <p>{@code COCRDUPC} belongs to the five-program family - {@code COACTUPC}, {@code COACTVWC},
 * {@code COCRDLIC}, {@code COCRDSLC}, {@code COCRDUPC} - that alone includes the attention-key copybook
 * and alone registers a CICS abend handler. The registration is at lines 370 to 372 and the terminal
 * abend is at line <b>1550</b>. The abend path is wired here because the legacy wired it here, and
 * nowhere else in the online tier is it wired at all.
 *
 * <h2>The three counter-intuitive behaviours, and the lines that prove them</h2>
 *
 * <p><strong>One - the embossed name is upper-folded in place, twice, and the ordering is
 * contractual.</strong> The fold is an {@code INSPECT ... CONVERTING} over the record field itself, not
 * a copy taken for display. It runs at line <b>1357</b>, <em>before</em> the old-value capture at line
 * <b>1360</b>, so the value carried forward for comparison is already folded. It runs a second time at
 * line <b>1499</b>, at the head of the change-detection paragraph, <em>before</em> the six-field
 * comparison at lines <b>1503 to 1508</b>. Because both sides of that comparison are folded, an edit
 * that differs from the stored value <em>only in letter case is not detected as a change</em>. That is
 * reproduced deliberately. Folding for display only, or folding after the capture, or folding one side,
 * each flips the outcome.
 *
 * <p>A third comparison behaves the same way and is reproduced the same way: the new-versus-old card-data
 * test at lines <b>680 to 681</b> folds both operands before comparing, so it too treats a case-only edit
 * as no change.
 *
 * <p><strong>What "in place" means, and what it does not.</strong> The record both folds act on is
 * {@code CARD-RECORD}, an {@code 01} level in <em>working storage</em> that each read fills with
 * {@code INTO(CARD-RECORD)} at lines 1386 and 1432. The rewrite at lines 1477 to 1483 writes a
 * <em>different</em> structure, {@code CARD-UPDATE-RECORD}, assembled separately at lines 1461 to 1475
 * from the submitted values - and line 1466 moves the submitted name into it <em>unfolded</em>. Neither
 * fold therefore ever reaches the file. Their Java counterpart is this turn's own state, and the persisted
 * row is deliberately not mutated: mutating a managed entity would flush the fold to the database on
 * commit and store a value the legacy never stores. A name typed in lower case is stored in lower case -
 * which is coherent rather than lax, because a difference that is <em>only</em> one of letter case is
 * never a change and so is never written at all.
 *
 * <p><strong>Two - the fold is a 26-character ASCII table, never a locale operation.</strong> Both
 * fold sites convert from the 26-character lower table declared at lines 262 to 263 to the 26-character
 * upper table declared at lines 260 to 261. {@code String.toUpperCase} is forbidden here in both its
 * forms: the no-argument form is locale-sensitive, both forms are Unicode-aware, and either can
 * transform a character the legacy table leaves untouched or change the length of a value that is
 * written back into a fixed 50-byte field. Every fold delegates to {@code CobolStringUtils}, which owns
 * the table and whose own documentation already credits these two sites to it.
 *
 * <p><strong>Three - the backward jump at line 1518 is a retry loop.</strong> This member owns one of
 * the nine backward {@code GO TO} statements in the estate. Line <b>1518</b> jumps to the
 * write-processing exit at line <b>1494</b> from inside the change-detection paragraph, bypassing that
 * paragraph's own exit at line 1521 and so re-entering the write path. It is reproduced as an explicit
 * loop, with no delay, no backoff and no attempt count, bounded by the source's own terminating
 * condition. See {@link #processCardUpdate(CardUpdateScreenInput)} and the write-path method for the
 * full mechanism.
 *
 * <h2>Two further parity traps</h2>
 *
 * <p><strong>Embedded spaces pass the alphabetic check.</strong> The name edit runs from line
 * <b>806</b> to its exit at line <b>841</b>, and its check at line <b>824</b> is the blank-and-trim
 * idiom: it converts the 52-character alphabetic table to spaces and then asserts the trimmed remainder
 * is empty. A character that was already a space survives as a space and is likewise trimmed, so a value
 * carrying a space passes. Writing {@code chars().allMatch(Character::isLetter)} instead is forbidden -
 * it would reject {@code "MARY ANN"}, and it would reject the live fixture value {@code "Aniya Von"}
 * that every one of the fifty rows of {@code app/data/ASCII/carddata.txt} resembles.
 *
 * <p><strong>The verification code is a bounded string, not a number.</strong> The screen field is
 * {@code PIC X(03)} at line 107 with an unsigned numeric redefinition at lines 108 to 109, and the write
 * path round-trips one through the other at lines 1464 to 1465. A value of {@code "007"} therefore stays
 * three characters and is never normalised to {@code 7}. It is also excluded from every log statement in
 * this class.
 *
 * <h2>Where the legacy read keys, and the account path</h2>
 *
 * <p>Both live reads key on the card number: the fetch at lines 1382 to 1390 and the read-for-update at
 * lines 1427 to 1436 each supply {@code RIDFLD(WS-CARD-RID-CARDNUM)} against the base file named at
 * lines 251 to 252. The account-keyed {@code RIDFLD} move is <em>commented out</em> at line 1379 and
 * again at line 1424. The non-unique account path is nonetheless <em>declared</em> at line <b>254</b>,
 * and this class consumes it for the one resolution the screen needs and the legacy left unwired: an
 * account supplied without a card number. That resolution goes through the repository's non-unique
 * finder, whose name already carries the take-the-first-row semantic the alternate index requires, and
 * an absent result is the legacy not-found response rather than an error. There is no JPA association
 * anywhere, so every further resolution is another explicit repository call.
 *
 * <h2>Concurrency</h2>
 *
 * <p>The entity carries a row version and the persistence provider checks it when the update is flushed.
 * This class catches that failure and translates it to {@code OptimisticLockConflictException}; the
 * conflict is recoverable and non-abending and never reaches the abend service. No pessimistic mode is
 * used anywhere - there is no lock hint and no lock-mode reference in this class. The row version plus
 * the database's read-committed isolation is a <em>strict improvement</em> over the legacy baseline of
 * uncommitted read integrity with no recovery and no journalling, and is recorded as such in the
 * decision log so a reviewer does not read the stronger isolation as a regression. The legacy's own
 * before-and-after image comparison is still reproduced in full, because it is what drives the
 * user-facing change-detection message and it answers a different question: it catches a change made
 * between <em>presenting</em> the screen and <em>confirming</em> it, a window a row version cannot see
 * into.
 *
 * <h2>Shape and thread safety</h2>
 *
 * <p>A stateless singleton with no mutable field. The working storage the legacy declares at lines 36 to
 * 99 becomes a per-call object created inside the entry point, so two concurrent turns are wholly
 * independent and the loop state of the retry path is a local variable rather than a counter field. All
 * collaborators arrive through the constructor.
 *
 * <p><strong>This class is deliberately NOT {@code final}, and it must stay that way.</strong> Every other
 * service in this package is final, so the omission looks like an oversight and invites a reviewer to
 * "restore" the modifier - which would stop the application from starting. The entry point is
 * {@code @Transactional}, this class implements no interface, and so the container proxies it by generating
 * a subclass; a final class cannot be subclassed and the bean definition fails outright with
 * {@code Cannot subclass final class}. It is the only service in the package that declares a transaction,
 * which is exactly why it is the only one that cannot be final. The entry point is non-final for the same
 * reason: a final method would not be overridden by the proxy and the transaction would silently not apply.
 * Immutability is instead guaranteed by the five final collaborator fields and the absence of any mutable
 * state, which is what the paragraph above describes and what actually matters here.
 */
@Service
public class CardUpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(CardUpdateService.class);

    // ==============================================================================================
    // Literals and constants, lines 218 to 263
    // ==============================================================================================

    /** {@code LIT-THISPGM}, lines 219 to 220: the member name this program names as the abend culprit. */
    public static final String LEGACY_PROGRAM_NAME = "COCRDUPC";

    /** {@code LIT-THISTRANID}, lines 221 to 222: the transaction re-armed on every terminal return. */
    public static final String LEGACY_TRANSACTION_ID = "CCUP";

    /** {@code LIT-THISMAPSET}, lines 223 to 224, carried with its declared trailing blank. */
    public static final String LEGACY_MAPSET_NAME = "COCRDUP ";

    /** {@code LIT-THISMAP}, lines 225 to 226. */
    public static final String LEGACY_MAP_NAME = "CCRDUPA";

    /** {@code LIT-CCLISTPGM}, lines 227 to 228: the card-list program this screen is reached from. */
    public static final String LEGACY_CARD_LIST_PROGRAM = "COCRDLIC";

    /** {@code LIT-CCLISTMAPSET}, lines 231 to 232: the mapset the arrival tests compare against. */
    public static final String LEGACY_CARD_LIST_MAPSET = "COCRDLI";

    /** {@code LIT-MENUPGM}, lines 235 to 236: the menu program a fresh entry arrives from. */
    public static final String LEGACY_MENU_PROGRAM = "COMEN01C";

    /** {@code LIT-MENUTRANID}, lines 237 to 238: the transaction a caller-less exit returns to. */
    public static final String LEGACY_MENU_TRANSACTION_ID = "CM00";

    /**
     * {@code LIT-CARDFILENAME}, lines 251 to 252, carried with its declared trailing blank.
     *
     * <p>The base file both live reads key on by card number.
     */
    public static final String LEGACY_CARD_FILE_NAME = "CARDDAT ";

    /**
     * {@code LIT-CARDFILENAME-ACCT-PATH}, lines 253 to <b>254</b>, carried with its declared trailing
     * blank.
     *
     * <p>The non-unique account path over the card file. Declared by the legacy and left unwired by it,
     * because the account-keyed record-identification move is commented out at lines 1379 and 1424. It
     * is named here because it is the resource the account-only resolution reads, and because the abend
     * diagnostic must be able to name whichever of the two resources failed.
     */
    public static final String LEGACY_CARD_ACCOUNT_PATH_NAME = "CARDAIX ";

    /** Width of {@code CC-ACCT-ID}, {@code PIC X(11)} at line 34 of {@code app/cpy/CVCRD01Y.cpy}. */
    public static final int ACCOUNT_ID_WIDTH = 11;

    /** Width of {@code CC-CARD-NUM}, {@code PIC X(16)} at line 37 of {@code app/cpy/CVCRD01Y.cpy}. */
    public static final int CARD_NUMBER_WIDTH = 16;

    /** Width of {@code CARD-NAME-CHECK}, {@code PIC X(50)} at lines 87 to 88. */
    public static final int EMBOSSED_NAME_WIDTH = 50;

    /** Width of {@code CARD-CVV-CD-X}, {@code PIC X(03)} at line 107. */
    public static final int VERIFICATION_CODE_WIDTH = 3;

    /** Width of {@code CCUP-NEW-EXPYEAR}, {@code PIC X(4)} at line 310. */
    public static final int EXPIRY_YEAR_WIDTH = 4;

    /** Width of {@code CCUP-NEW-EXPMON}, {@code PIC X(2)} at line 311. */
    public static final int EXPIRY_MONTH_WIDTH = 2;

    /** Width of {@code CCUP-NEW-EXPDAY}, {@code PIC X(2)} at line 312. */
    public static final int EXPIRY_DAY_WIDTH = 2;

    /** Width of {@code CCUP-NEW-CRDSTCD}, {@code PIC X(1)} at line 313. */
    public static final int ACTIVE_STATUS_WIDTH = 1;

    /** Lowest month {@code VALID-MONTH} accepts, {@code VALUES 1 THRU 12} at line 95. */
    public static final int EXPIRY_MONTH_MINIMUM = 1;

    /** Highest month {@code VALID-MONTH} accepts, line 95. */
    public static final int EXPIRY_MONTH_MAXIMUM = 12;

    /** Lowest year {@code VALID-YEAR} accepts, {@code VALUES 1950 THRU 2099} at line 99. */
    public static final int EXPIRY_YEAR_MINIMUM = 1950;

    /** Highest year {@code VALID-YEAR} accepts, line 99. */
    public static final int EXPIRY_YEAR_MAXIMUM = 2099;

    /** {@code ABEND-CODE} the unexpected-data arm moves at line 1021. */
    public static final String ABEND_CODE_UNEXPECTED_DATA = "0001";

    /** {@code ABEND-MSG} the unexpected-data arm moves at lines 1023 to 1024. */
    public static final String ABEND_REASON_UNEXPECTED_DATA = "UNEXPECTED DATA SCENARIO";

    /** {@code ABCODE} of the terminal {@code EXEC CICS ABEND} at lines 1550 to 1552. */
    public static final String ABEND_CODE_TERMINAL = "9999";

    // ----------------------------------------------------------------------------------------------
    // WS-INFO-MSG, PIC X(40), condition names at lines 156 to 171. Reproduced verbatim: these are
    // operator-visible text and are part of the external contract.
    // ----------------------------------------------------------------------------------------------

    /** {@code FOUND-CARDS-FOR-ACCOUNT}, lines 160 to 161. */
    public static final String INFO_DETAILS_SHOWN = "Details of selected card shown above";

    /** {@code PROMPT-FOR-SEARCH-KEYS}, lines 162 to 163. */
    public static final String INFO_PROMPT_FOR_SEARCH_KEYS = "Please enter Account and Card Number";

    /** {@code PROMPT-FOR-CHANGES}, lines 164 to 165, including its trailing full stop. */
    public static final String INFO_PROMPT_FOR_CHANGES = "Update card details presented above.";

    /** {@code PROMPT-FOR-CONFIRMATION}, lines 166 to 167, with no space after the full stop. */
    public static final String INFO_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    /** {@code CONFIRM-UPDATE-SUCCESS}, lines 168 to 169. */
    public static final String INFO_UPDATE_COMMITTED = "Changes committed to database";

    /** {@code INFORM-FAILURE}, lines 170 to 171. */
    public static final String INFO_UPDATE_FAILED = "Changes unsuccessful. Please try again";

    // ----------------------------------------------------------------------------------------------
    // WS-RETURN-MSG, PIC X(75), condition names at lines 173 to 214, plus the two inline literals the
    // filter edits move directly. Reproduced verbatim, irregular spacing included.
    //
    // Seven of the twenty declared condition names are DECLARED AND NEVER SET anywhere in the member,
    // so no constant is declared for them and no path can raise them:
    //   WS-EXIT-MESSAGE                lines 175 to 176 - the exit arm raises nothing
    //   SEARCHED-ACCT-ZEROES           lines 189 to 190 - the account edit moves an inline literal
    //   SEARCHED-ACCT-NOT-NUMERIC      lines 191 to 192 - same, and its text duplicates the previous
    //   SEARCHED-CARD-NOT-NUMERIC      lines 193 to 194 - the card edit moves an inline literal
    //   DID-NOT-FIND-ACCT-IN-CARDXREF  lines 201 to 202 - the read raises the other not-found text
    //   XREF-READ-ERROR                lines 211 to 212 - the read raises the assembled file-error text
    //   CODING-TO-BE-DONE              lines 213 to 214 - a development placeholder
    // Declaring a constant for a text no path emits would invite a caller to emit it, which would be an
    // added behaviour. The finding is a decision-log entry instead.
    // ----------------------------------------------------------------------------------------------

    /** {@code WS-PROMPT-FOR-ACCT}, lines 177 to 178. */
    public static final String MSG_ACCOUNT_NOT_PROVIDED = "Account number not provided";

    /** {@code WS-PROMPT-FOR-CARD}, lines 179 to 180. */
    public static final String MSG_CARD_NOT_PROVIDED = "Card number not provided";

    /** {@code WS-PROMPT-FOR-NAME}, lines 181 to 182. */
    public static final String MSG_NAME_NOT_PROVIDED = "Card name not provided";

    /** {@code WS-NAME-MUST-BE-ALPHA}, lines 183 to 184. */
    public static final String MSG_NAME_MUST_BE_ALPHA =
            "Card name can only contain alphabets and spaces";

    /** {@code NO-SEARCH-CRITERIA-RECEIVED}, lines 185 to 186. */
    public static final String MSG_NO_INPUT_RECEIVED = "No input received";

    /** {@code NO-CHANGES-DETECTED}, lines 187 to 188, including its trailing full stop. */
    public static final String MSG_NO_CHANGE_DETECTED =
            "No change detected with respect to values fetched.";

    /** {@code CARD-STATUS-MUST-BE-YES-NO}, lines 195 to 196. */
    public static final String MSG_STATUS_MUST_BE_YES_NO = "Card Active Status must be Y or N";

    /** {@code CARD-EXPIRY-MONTH-NOT-VALID}, lines 197 to 198. */
    public static final String MSG_EXPIRY_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";

    /** {@code CARD-EXPIRY-YEAR-NOT-VALID}, lines 199 to 200. */
    public static final String MSG_EXPIRY_YEAR_NOT_VALID = "Invalid card expiry year";

    /** {@code DID-NOT-FIND-ACCTCARD-COMBO}, lines 203 to 204. */
    public static final String MSG_NO_CARD_FOR_SEARCH = "Did not find cards for this search condition";

    /**
     * {@code COULD-NOT-LOCK-FOR-UPDATE}, lines 205 to 206.
     *
     * <p>Declared here rather than reused, and the difference is deliberate. The shared conflict
     * exception's lock-not-acquired arm resolves to <em>"Could not lock account record for update"</em>,
     * which is the account-update program's wording. This program's own literal is one word shorter. The
     * two other write-path texts <em>are</em> reused from that exception because they are byte-identical
     * to this program's literals at lines 208 and 210. The divergence is a decision-log entry, not a
     * defect to correct in either direction.
     */
    public static final String MSG_COULD_NOT_LOCK_FOR_UPDATE = "Could not lock record for update";

    /** The inline literal the account filter edit moves at line 745. */
    public static final String MSG_ACCOUNT_FILTER_ELEVEN_DIGITS =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** The inline literal the card filter edit moves at line 789. */
    public static final String MSG_CARD_FILTER_SIXTEEN_DIGITS =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * Width of {@code WS-RETURN-MSG}, {@code PIC X(75)} at line 173.
     *
     * <p>Load bearing for one message only: the assembled file-error text of lines 133 to 152 totals 80
     * characters across its eight parts, so a {@code MOVE} into this field discards the final five. That
     * truncation is reproduced rather than avoided.
     */
    public static final int RETURN_MESSAGE_WIDTH = 75;

    // ----------------------------------------------------------------------------------------------
    // WS-FILE-ERROR-MESSAGE, lines 133 to 152: eight parts, three of them literal.
    // ----------------------------------------------------------------------------------------------

    /** {@code FILLER PIC X(12) VALUE 'File Error: '}, lines 134 to 135. */
    private static final String FILE_ERROR_PREFIX = "File Error: ";

    /** {@code FILLER PIC X(4) VALUE ' on '}, lines 138 to 139. */
    private static final String FILE_ERROR_ON = " on ";

    /** {@code FILLER PIC X(15) VALUE ' returned RESP '}, lines 142 to 144. */
    private static final String FILE_ERROR_RESP = " returned RESP ";

    /** {@code FILLER PIC X(7) VALUE ',RESP2 '}, lines 147 to 148. */
    private static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** {@code ERROR-OPNAME PIC X(8)}, lines 136 to 137. */
    private static final int FILE_ERROR_OPNAME_WIDTH = 8;

    /** {@code ERROR-FILE PIC X(9)}, lines 140 to 141. */
    private static final int FILE_ERROR_FILE_WIDTH = 9;

    /**
     * {@code ERROR-RESP} and {@code ERROR-RESP2}, both {@code PIC X(10)}, lines 145 and 149.
     *
     * <p>The structure declares an eighth part after these, a five-character all-blank filler at lines 151
     * to 152, which the seventy-five-character destination at line 173 cannot hold. It is therefore never
     * seen and is not modelled.
     */
    private static final int FILE_ERROR_RESP_WIDTH = 10;

    /** The operation name the fetch reports, moved at line 1407. */
    private static final String OPERATION_READ = "READ";

    /**
     * The operation name the read-for-update reports.
     *
     * <p>The legacy never sets it: the lock-failure branch at lines 1443 to 1449 raises its own message
     * without touching the operation field, so a diagnostic there would carry whatever the previous fetch
     * left. Naming the operation makes the two reads distinguishable in a log without changing any
     * operator-visible text.
     */
    private static final String OPERATION_READ_UPDATE = "READUPD";

    /** The operation name the rewrite reports. */
    private static final String OPERATION_REWRITE = "REWRITE";

    /**
     * The raw two-character file status the abend diagnostic names when the card file read fails.
     *
     * <p>The online tier signals through a CICS response code rather than a {@code FILE STATUS} field,
     * so there is no status byte to forward. This is the status the batch tier's normalisation assigns
     * to the same outcome - a read that neither succeeded nor reached end of file - and naming it keeps
     * the diagnostic one shape across both tiers.
     */
    private static final String RAW_STATUS_READ_FAILURE = "30";

    /** The record type name the not-found failure carries. */
    private static final String RECORD_TYPE_CARD = "Card";

    /** The entity name the conflict exception carries; deliberately not the customer record. */
    private static final String ENTITY_NAME_CARD = "Card";

    // ----------------------------------------------------------------------------------------------
    // Screen field identities. The response property name and the legacy BMS field name are carried
    // as a pair so a reader can trace either direction, and the decorator records both.
    // ----------------------------------------------------------------------------------------------

    /** Request and response property name of the account filter. */
    public static final String FIELD_ACCOUNT_ID = "accountId";

    /** {@code ACCTSID} on map {@code CCRDUPA}. */
    public static final String BMS_ACCOUNT_ID = "ACCTSID";

    /** Request and response property name of the card filter. */
    public static final String FIELD_CARD_NUMBER = "cardNumber";

    /** {@code CARDSID} on map {@code CCRDUPA}. */
    public static final String BMS_CARD_NUMBER = "CARDSID";

    /** Request and response property name of the embossed name. */
    public static final String FIELD_EMBOSSED_NAME = "embossedName";

    /** {@code CRDNAME} on map {@code CCRDUPA}. */
    public static final String BMS_EMBOSSED_NAME = "CRDNAME";

    /** Request and response property name of the active status. */
    public static final String FIELD_ACTIVE_STATUS = "activeStatus";

    /** {@code CRDSTCD} on map {@code CCRDUPA}. */
    public static final String BMS_ACTIVE_STATUS = "CRDSTCD";

    /** Request and response property name of the expiry month. */
    public static final String FIELD_EXPIRY_MONTH = "expiryMonth";

    /** {@code EXPMON} on map {@code CCRDUPA}. */
    public static final String BMS_EXPIRY_MONTH = "EXPMON";

    /** Request and response property name of the expiry year. */
    public static final String FIELD_EXPIRY_YEAR = "expiryYear";

    /** {@code EXPYEAR} on map {@code CCRDUPA}. */
    public static final String BMS_EXPIRY_YEAR = "EXPYEAR";

    /**
     * Request and response property name of the expiry day.
     *
     * <p>Received at line 621 without the marker-to-low-values normalisation the other six fields get,
     * and written back from the <em>old</em> value at line 1123 even on the changes-made arm, because
     * the screen does not let an operator change it. Named here so the response can carry it; it is
     * never validated and never decorated.
     */
    public static final String FIELD_EXPIRY_DAY = "expiryDay";

    /** The marker character the terminal transmits for a field an operator cleared, lines 589 to 634. */
    private static final String CLEARED_FIELD_MARKER = "*";

    /** The character the decoration writes into a blank field, lines 1249, 1259, 1270, 1281, 1294, 1305. */
    public static final String BLANK_FIELD_MARKER = "*";

    /** The separator the expiration date is assembled with at lines 1468 and 1470. */
    private static final String EXPIRATION_DATE_SEPARATOR = "-";

    /**
     * {@code WS-CURDATE-MM-DD-YY} as the header carries it, lines 1064 to 1068.
     *
     * <p>Pinned to the root locale. A locale-sensitive formatter would render the same instant differently
     * under a locale carrying a non-Gregorian calendar or non-ASCII digits, and the header field is a fixed
     * eight characters.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/uu", Locale.ROOT);

    /** {@code WS-CURTIME-HH-MM-SS} as the header carries it, lines 1070 to 1074. */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    // ==============================================================================================
    // Collaborators
    // ==============================================================================================

    private final CardRepository cardRepository;

    private final AbendService abendService;

    private final MessageCatalogService messageCatalogService;

    private final NavigationService navigationService;

    private final Clock clock;

    /**
     * Creates the service with every collaborator the translated paragraphs need.
     *
     * @param cardRepository the card file, replacing the base cluster read at lines 1382 and 1427, the
     *                       account path declared at line 254 and the rewrite at lines 1477 to 1483;
     *                       mandatory
     * @param abendService the abend routine's terminal path at lines 1539 to 1552; mandatory
     * @param messageCatalogService the common message catalog, source of the fixed-width invalid-key
     *                              text an unmapped attention key produces, and of the two screen
     *                              titles the header carries; mandatory
     * @param navigationService the dispatch graph replacing the transfer at lines 473 to 476 and the
     *                          re-arm at lines 554 to 558; mandatory
     * @param clock the clock the header's date and time read, replacing
     *              {@code FUNCTION CURRENT-DATE} at lines 1055 and 1062; mandatory
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public CardUpdateService(final CardRepository cardRepository,
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
    // Level-88 condition names as enums with predicate methods
    // ==============================================================================================

    /**
     * The seven states of {@code CCUP-CHANGE-ACTION}, {@code PIC X(1)} at line 276 with its condition
     * names at lines 278 to 290.
     *
     * <p>This is the screen's state machine and it drives four of the five arms of the main dispatch,
     * the action decision, the outbound field values, the informational message and the field
     * protection. The two grouped condition names are predicates rather than constants, because the
     * legacy declares them over sets of values: changes-made covers five states at lines 282 to 284 and
     * changes-failed covers two at line 288.
     *
     * <p>The not-fetched state is declared over both low values and spaces at lines 278 to 280, which is
     * why a turn that carries no state at all resolves to it.
     */
    public enum ChangeAction {

        /** {@code CCUP-DETAILS-NOT-FETCHED}, low values or spaces, lines 278 to 280. */
        DETAILS_NOT_FETCHED(' '),

        /** {@code CCUP-SHOW-DETAILS}, {@code 'S'}, line 281. */
        SHOW_DETAILS('S'),

        /** {@code CCUP-CHANGES-NOT-OK}, {@code 'E'}, line 285. */
        CHANGES_NOT_OK('E'),

        /** {@code CCUP-CHANGES-OK-NOT-CONFIRMED}, {@code 'N'}, line 286. */
        CHANGES_OK_NOT_CONFIRMED('N'),

        /** {@code CCUP-CHANGES-OKAYED-AND-DONE}, {@code 'C'}, line 287. */
        CHANGES_OKAYED_AND_DONE('C'),

        /** {@code CCUP-CHANGES-OKAYED-LOCK-ERROR}, {@code 'L'}, line 289. */
        CHANGES_OKAYED_LOCK_ERROR('L'),

        /** {@code CCUP-CHANGES-OKAYED-BUT-FAILED}, {@code 'F'}, line 290. */
        CHANGES_OKAYED_BUT_FAILED('F');

        private final char code;

        ChangeAction(final char code) {
            this.code = code;
        }

        /**
         * Returns the single character the legacy field holds for this state.
         *
         * @return the declared code; a space for the not-fetched state, whose declaration also admits
         *         low values
         */
        public char getCode() {
            return this.code;
        }

        /**
         * {@code CCUP-DETAILS-NOT-FETCHED}, lines 278 to 280.
         *
         * @return {@code true} when no card has been fetched for this conversation
         */
        public boolean detailsNotFetched() {
            return this == DETAILS_NOT_FETCHED;
        }

        /**
         * {@code CCUP-SHOW-DETAILS}, line 281.
         *
         * @return {@code true} when a fetched card is on display
         */
        public boolean showDetails() {
            return this == SHOW_DETAILS;
        }

        /**
         * {@code CCUP-CHANGES-MADE}, declared over five values at lines 282 to 284.
         *
         * @return {@code true} for any state in which the operator has submitted changes
         */
        public boolean changesMade() {
            return this == CHANGES_NOT_OK
                    || this == CHANGES_OK_NOT_CONFIRMED
                    || this == CHANGES_OKAYED_AND_DONE
                    || this == CHANGES_OKAYED_LOCK_ERROR
                    || this == CHANGES_OKAYED_BUT_FAILED;
        }

        /**
         * {@code CCUP-CHANGES-NOT-OK}, line 285.
         *
         * @return {@code true} when the submitted changes failed their edits
         */
        public boolean changesNotOk() {
            return this == CHANGES_NOT_OK;
        }

        /**
         * {@code CCUP-CHANGES-OK-NOT-CONFIRMED}, line 286.
         *
         * @return {@code true} when the changes passed their edits and await confirmation
         */
        public boolean changesOkNotConfirmed() {
            return this == CHANGES_OK_NOT_CONFIRMED;
        }

        /**
         * {@code CCUP-CHANGES-OKAYED-AND-DONE}, line 287.
         *
         * @return {@code true} when the write completed
         */
        public boolean changesOkayedAndDone() {
            return this == CHANGES_OKAYED_AND_DONE;
        }

        /**
         * {@code CCUP-CHANGES-FAILED}, declared over two values at line 288.
         *
         * @return {@code true} when the write was attempted and did not complete
         */
        public boolean changesFailed() {
            return this == CHANGES_OKAYED_LOCK_ERROR || this == CHANGES_OKAYED_BUT_FAILED;
        }

        /**
         * {@code CCUP-CHANGES-OKAYED-LOCK-ERROR}, line 289.
         *
         * @return {@code true} when the record could not be locked for update
         */
        public boolean changesOkayedLockError() {
            return this == CHANGES_OKAYED_LOCK_ERROR;
        }

        /**
         * {@code CCUP-CHANGES-OKAYED-BUT-FAILED}, line 290.
         *
         * @return {@code true} when the record was locked and the write still failed
         */
        public boolean changesOkayedButFailed() {
            return this == CHANGES_OKAYED_BUT_FAILED;
        }
    }

    /**
     * The three states every {@code WS-EDIT-*-FLAG} holds, declared at lines 57 to 80.
     *
     * <p>Six flags share this shape - account filter, card filter, name, status, expiry month and
     * expiry year - and every one of them declares the same three condition names over the same three
     * values. They are mutually exclusive, which is what lets the screen distinguish a field an operator
     * left empty from one they filled in wrongly: the blank state writes a marker into the field and
     * colours it, the not-OK state only colours it.
     *
     * <p>The initial state is blank, not not-OK, because {@code INITIALIZE WS-MISC-STORAGE} at line 375
     * sets every alphanumeric flag to spaces and the blank condition name is declared over a space.
     */
    public enum EditFlag {

        /** The {@code -NOT-OK} condition name, {@code VALUE '0'}: supplied and failed its edit. */
        NOT_OK('0'),

        /** The {@code -ISVALID} condition name, {@code VALUE '1'}: supplied and accepted. */
        IS_VALID('1'),

        /** The {@code -BLANK} condition name, {@code VALUE ' '}: not supplied, and the initial state. */
        BLANK(' ');

        private final char code;

        EditFlag(final char code) {
            this.code = code;
        }

        /**
         * Returns the single character the legacy flag holds for this state.
         *
         * @return the declared code
         */
        public char getCode() {
            return this.code;
        }

        /**
         * @return {@code true} for the {@code -NOT-OK} state
         */
        public boolean notOk() {
            return this == NOT_OK;
        }

        /**
         * @return {@code true} for the {@code -ISVALID} state
         */
        public boolean valid() {
            return this == IS_VALID;
        }

        /**
         * @return {@code true} for the {@code -BLANK} state
         */
        public boolean blank() {
            return this == BLANK;
        }

        /**
         * Reports whether this state causes the field to be decorated at all.
         *
         * <p>Both failing states colour the field; only the blank state additionally writes the marker.
         * The two colour tests appear as separate {@code IF} statements per field at lines 1243 to 1307.
         *
         * @return {@code true} for either failing state
         */
        public boolean decorated() {
            return this != IS_VALID;
        }

        /**
         * Maps this state onto the decorator's two-state flag.
         *
         * @return the decorator flag for a failing state
         * @throws IllegalStateException if called for the accepted state, which is never decorated
         */
        public FieldErrorDecorator.FlagState decorationFlag() {
            return switch (this) {
                case BLANK -> FieldErrorDecorator.FlagState.BLANK;
                case NOT_OK -> FieldErrorDecorator.FlagState.NOT_OK;
                case IS_VALID -> throw new IllegalStateException(
                        "an accepted field is never decorated; guard with decorated() first");
            };
        }

        /**
         * Maps this state onto the field-error state the response carries.
         *
         * @return MISSING for a field not supplied, INVALID for one supplied wrongly
         * @throws IllegalStateException if called for the accepted state
         */
        public ValidationException.FieldState fieldState() {
            return switch (this) {
                case BLANK -> ValidationException.FieldState.MISSING;
                case NOT_OK -> ValidationException.FieldState.INVALID;
                case IS_VALID -> throw new IllegalStateException(
                        "an accepted field has no error state; guard with decorated() first");
            };
        }
    }

    /**
     * The three states of {@code WS-INPUT-FLAG}, {@code PIC X(1)} at line 53 with its condition names at
     * lines 54 to 56.
     */
    public enum InputState {

        /** {@code INPUT-OK}, {@code VALUE '0'}, line 54. */
        OK,

        /** {@code INPUT-ERROR}, {@code VALUE '1'}, line 55. */
        ERROR,

        /** {@code INPUT-PENDING}, {@code VALUE LOW-VALUES}, line 56: the initial state. */
        PENDING;

        /**
         * @return {@code true} for the error state, the condition the edits and the action decision test
         */
        public boolean inputError() {
            return this == ERROR;
        }

        /**
         * @return {@code true} for the accepted state
         */
        public boolean inputOk() {
            return this == OK;
        }

        /**
         * @return {@code true} for the initial state
         */
        public boolean inputPending() {
            return this == PENDING;
        }
    }

    /**
     * The two states of {@code WS-PFK-FLAG}, {@code PIC X(1)} at line 84 with its condition names at
     * lines 85 to 86.
     *
     * <p>This is the <em>permitted-at-this-point</em> test at lines 413 to 424, not the decode. A key
     * that decodes successfully but is not permitted in the current state is silently treated as the
     * enter key at lines 422 to 424 and produces no message. A key that does not decode at all is a
     * different outcome entirely and is handled where the decode happens.
     */
    public enum AttentionKeyState {

        /** {@code PFK-VALID}, {@code VALUE '0'}, line 85. */
        VALID,

        /** {@code PFK-INVALID}, {@code VALUE '1'}, line 86: the state line 413 sets before testing. */
        INVALID;

        /**
         * @return {@code true} when the decoded key is permitted in the current state
         */
        public boolean permitted() {
            return this == VALID;
        }
    }

    /**
     * The outcome of the write path, paragraph {@code 9200-WRITE-PROCESSING} at line 1420.
     *
     * <p>Four terminal outcomes and one initial state. The three failing outcomes are exactly the three
     * arms the action decision evaluates at lines 992 to 1001, in that clause order.
     */
    public enum WriteOutcome {

        /** The write path did not run on this turn. */
        NOT_ATTEMPTED,

        /** The read for update did not succeed, lines 1441 to 1449. */
        LOCK_NOT_ACQUIRED,

        /** The record differed from the carried image, lines 1503 to 1518. */
        RECORD_CHANGED_BEFORE_UPDATE,

        /** The record was read for update and the rewrite failed, lines 1488 to 1492. */
        UPDATE_FAILED_AFTER_LOCK,

        /** The rewrite completed, lines 1477 to 1489. */
        COMMITTED;

        /**
         * @return {@code true} when the rewrite completed
         */
        public boolean committed() {
            return this == COMMITTED;
        }

        /**
         * Maps a failing outcome onto the shared conflict exception's arm.
         *
         * @return the conflict arm for this outcome
         * @throws IllegalStateException if called for an outcome that is not a conflict
         */
        public OptimisticLockConflictException.ConflictKind conflictKind() {
            return switch (this) {
                case LOCK_NOT_ACQUIRED ->
                        OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED;
                case RECORD_CHANGED_BEFORE_UPDATE ->
                        OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE;
                case UPDATE_FAILED_AFTER_LOCK ->
                        OptimisticLockConflictException.ConflictKind.UPDATE_FAILED_AFTER_LOCK;
                case NOT_ATTEMPTED, COMMITTED -> throw new IllegalStateException(
                        "outcome " + this + " is not a conflict and has no conflict arm");
            };
        }
    }

    /**
     * Which groups of fields the terminal left open, the first evaluation of
     * {@code 3300-SETUP-SCREEN-ATTRS} at lines 1172 to 1208.
     *
     * <p>The legacy expresses this by moving an unprotected or a protected attribute byte into each
     * field's attribute position. Four clauses resolve to three distinct outcomes, because the catch-all
     * at lines 1200 to 1207 repeats the first clause's assignment exactly.
     *
     * <p>The expiry-day attribute assignment is commented out in every clause - lines 1178, 1185, 1197
     * and 1205 - which is why that field never appears here.
     */
    public enum FieldProtection {

        /** Search keys open, card data protected: lines 1173 to 1180, repeated at lines 1200 to 1207. */
        SEARCH_KEYS_OPEN,

        /** Search keys protected, card data open: lines 1181 to 1190. */
        CARD_DATA_OPEN,

        /** Everything protected: lines 1191 to 1199. */
        ALL_PROTECTED
    }

    // ==============================================================================================
    // The screen contract, as values
    // ==============================================================================================

    /**
     * The fetched image of the card as it stood when the screen was built: {@code CCUP-OLD-DETAILS},
     * lines 291 to 301.
     *
     * <p>The legacy carries this group in the communication area returned with the screen - moved out at
     * line 550 and sliced back in at lines 398 to 400 - so that the confirming turn can compare the
     * record it locks against what the operator was actually shown. Re-deriving it on the confirming
     * turn would detect nothing, because the whole point of the comparison is to catch a change made
     * <em>after</em> the screen was displayed. It therefore arrives as part of the request here, exactly
     * as it arrived in the communication area there.
     *
     * <p>Every component may be {@code null}, which is the analogue of the low values the group holds
     * before a card has been fetched. The expiry date arrives already split into its three parts,
     * matching the group's own sub-structure at lines 297 to 300 and the substring moves at lines 1361
     * to 1366.
     *
     * <p>The embossed name arrives <b>already upper-folded</b>, because the capture at line 1360 reads
     * the field the fold at line 1357 has already rewritten. Supplying an unfolded value here would
     * change the comparison outcome, so the read path folds before capturing and this component
     * documents that it did.
     *
     * @param accountId {@code CCUP-OLD-ACCTID}, width 11, line 292
     * @param cardNumber {@code CCUP-OLD-CARDID}, width 16, line 293
     * @param verificationCode {@code CCUP-OLD-CVV-CD}, width 3, line 294; a bounded string so a leading
     *                         zero survives, and excluded from every log statement
     * @param embossedName {@code CCUP-OLD-CRDNAME}, width 50, line 296, already folded
     * @param expiryYear {@code CCUP-OLD-EXPYEAR}, width 4, line 298
     * @param expiryMonth {@code CCUP-OLD-EXPMON}, width 2, line 299
     * @param expiryDay {@code CCUP-OLD-EXPDAY}, width 2, line 300
     * @param activeStatus {@code CCUP-OLD-CRDSTCD}, width 1, line 301
     */
    public record CarriedCardImage(String accountId,
                                   String cardNumber,
                                   String verificationCode,
                                   String embossedName,
                                   String expiryYear,
                                   String expiryMonth,
                                   String expiryDay,
                                   String activeStatus) {

        /**
         * The group as it stands before a card has been fetched: every component low values.
         *
         * @return an image with every component absent, never {@code null}
         */
        public static CarriedCardImage empty() {
            return new CarriedCardImage(null, null, null, null, null, null, null, null);
        }

        /**
         * Reports whether this image carries no fetched card at all.
         *
         * @return {@code true} when the card number is absent or blank
         */
        public boolean absent() {
            return this.cardNumber == null || this.cardNumber.isBlank();
        }

        /**
         * Renders the image without any of the three business keys, so an instance can be logged.
         *
         * <p>The verification code is withheld unconditionally and is not partially masked: a
         * three-character value is trivially recovered from any transformation of itself. The card number
         * and the account identifier are withheld for the same reason the navigation state withholds
         * them - and withholding them here matters more, not less, because this record is the one that
         * travels inside a request whose own rendering redacts them. Leaving them in clear on the nested
         * value would defeat the outer redaction entirely.
         *
         * @return a diagnostic rendering carrying no sensitive component
         */
        @Override
        public String toString() {
            return "CarriedCardImage[accountId=***REDACTED***"
                    + ", cardNumber=***REDACTED***"
                    + ", verificationCode=***REDACTED***"
                    + ", embossedName=" + this.embossedName
                    + ", expiryYear=" + this.expiryYear
                    + ", expiryMonth=" + this.expiryMonth
                    + ", expiryDay=" + this.expiryDay
                    + ", activeStatus=" + this.activeStatus + ']';
        }
    }

    /**
     * One inbound turn of the card-update screen: the seven transmitted fields of the input map
     * {@code CCRDUPAI}, the raw attention identifier, and the state the client echoes in place of the
     * legacy communication area.
     *
     * <p>Every field is carried verbatim and every field may be {@code null}, because a 3270 field the
     * terminal did not transmit arrives as low values rather than as spaces and an omitted request
     * component is the same state. Bounding a value to its declared screen width is the receive
     * paragraph's job at lines 578 to 636, not the caller's.
     *
     * <p>The attention identifier arrives <b>raw and undecoded</b>, because the decode - including the
     * fold of the upper twelve program-function keys onto the lower twelve - belongs to the module's key
     * translator and is credited to it. A value the translator does not recognise is the unmapped-key
     * case and produces the fixed-width invalid-key message; the translator declares no catch-all
     * because the copybook it translates declares none across its twenty-eight clauses.
     *
     * @param accountId {@code ACCTSIDI}, the account filter, normalised at lines 589 to 596
     * @param cardNumber {@code CARDSIDI}, the card filter, normalised at lines 598 to 605
     * @param embossedName {@code CRDNAMEI}, normalised at lines 607 to 612
     * @param activeStatus {@code CRDSTCDI}, normalised at lines 614 to 619
     * @param expiryMonth {@code EXPMONI}, normalised at lines 623 to 628
     * @param expiryYear {@code EXPYEARI}, normalised at lines 630 to 635
     * @param expiryDay {@code EXPDAYI}, moved unconditionally at line 621 with <em>no</em> marker
     *                  normalisation, which is why it is the one received field that keeps a transmitted
     *                  marker character
     * @param attentionKeyIdentifier the raw attention identifier the terminal reported, decoded here
     *                               through the module's key translator; {@code null} is the same
     *                               outcome as an unrecognised value
     * @param navigationContext the echoed navigation state, or {@code null} for a turn carrying none,
     *                          which is the analogue of a zero-length communication area at line 388
     * @param changeAction the echoed screen state, or {@code null} for the not-fetched state
     * @param carriedImage the fetched card image the previous turn returned, or {@code null} when none
     *                     was carried
     */
    public record CardUpdateScreenInput(String accountId,
                                        String cardNumber,
                                        String embossedName,
                                        String activeStatus,
                                        String expiryMonth,
                                        String expiryYear,
                                        String expiryDay,
                                        String attentionKeyIdentifier,
                                        NavigationContext navigationContext,
                                        ChangeAction changeAction,
                                        CarriedCardImage carriedImage) {

        /**
         * Reports whether this turn carries no navigation state at all.
         *
         * <p>The analogue of {@code EIBCALEN IS EQUAL TO 0} at line 388, the first half of the condition
         * that resets the conversation.
         *
         * @return {@code true} when no navigation state was echoed
         */
        public boolean carriesNoNavigationState() {
            return this.navigationContext == null;
        }

        /**
         * Renders the turn without the two identifiers that may not reach a log.
         *
         * @return a diagnostic rendering carrying no sensitive component
         */
        @Override
        public String toString() {
            return "CardUpdateScreenInput[accountId=***REDACTED***"
                    + ", cardNumber=***REDACTED***"
                    + ", embossedName=" + this.embossedName
                    + ", activeStatus=" + this.activeStatus
                    + ", expiryMonth=" + this.expiryMonth
                    + ", expiryYear=" + this.expiryYear
                    + ", expiryDay=" + this.expiryDay
                    + ", attentionKeyIdentifier=" + this.attentionKeyIdentifier
                    + ", navigationContext=" + this.navigationContext
                    + ", changeAction=" + this.changeAction
                    + ", carriedImage=" + this.carriedImage + ']';
        }
    }

    /**
     * The screen header, populated by {@code 3100-SCREEN-INIT} at lines 1052 to 1076.
     *
     * <p>The two titles come from the shared title copybook through the message catalog; the transaction
     * and program names are this member's own literals at lines 1059 to 1060; the date and time are the
     * formatted current-date fields of lines 1064 to 1074.
     *
     * @param title01 {@code TITLE01O}, line 1057
     * @param title02 {@code TITLE02O}, line 1058
     * @param transactionName {@code TRNNAMEO}, line 1059
     * @param programName {@code PGMNAMEO}, line 1060
     * @param currentDate {@code CURDATEO}, line 1068, assembled from the month, day and last two digits
     *                    of the year at lines 1064 to 1066
     * @param currentTime {@code CURTIMEO}, line 1074, assembled from the hours, minutes and seconds at
     *                    lines 1070 to 1072
     */
    public record ScreenHeader(String title01,
                               String title02,
                               String transactionName,
                               String programName,
                               String currentDate,
                               String currentTime) {
    }

    /**
     * The seven screen fields as the turn leaves them, populated by {@code 3200-SETUP-SCREEN-VARS} at
     * lines 1082 to 1134 and decorated by {@code 3300-SETUP-SCREEN-ATTRS} at lines 1168 to 1318.
     *
     * <p>A component holding the marker character is a field the decoration blanked and marked, which the
     * legacy does by moving that character into the output field at lines 1249, 1259, 1270, 1281, 1294
     * and 1305.
     *
     * @param accountId {@code ACCTSIDO}
     * @param cardNumber {@code CARDSIDO}
     * @param embossedName {@code CRDNAMEO}
     * @param activeStatus {@code CRDSTCDO}
     * @param expiryMonth {@code EXPMONO}
     * @param expiryYear {@code EXPYEARO}
     * @param expiryDay {@code EXPDAYO}, always the <em>old</em> value even on the changes-made arm, per
     *                  the deliberate substitution at line 1123 whose commented-out alternative sits
     *                  directly above it at line 1122
     * @param infoMessage {@code INFOMSGO}, line 1161
     * @param errorMessage {@code ERRMSGO}, line 1163
     * @param fieldProtection which field group the terminal left open
     * @param confirmationKeysHighlighted {@code FKEYSCA} brightened at lines 1315 to 1317, which the
     *                                    legacy does only while the confirmation prompt is showing
     */
    public record ScreenFields(String accountId,
                               String cardNumber,
                               String embossedName,
                               String activeStatus,
                               String expiryMonth,
                               String expiryYear,
                               String expiryDay,
                               String infoMessage,
                               String errorMessage,
                               FieldProtection fieldProtection,
                               boolean confirmationKeysHighlighted) {
    }

    /**
     * The card as the turn leaves it.
     *
     * <p>Deliberately excludes the verification code. The legacy carries that value only inside the
     * carried image, for the sole purpose of the six-field comparison at line 1503; it is not a field of
     * this screen, it is never displayed, and it may not reach a log or a response.
     *
     * @param cardNumber the sixteen-character key
     * @param accountId the eleven-character account identifier
     * @param embossedName the fifty-character embossed name, upper folded
     * @param expirationDate the ten-character expiration date, assembled at lines 1467 to 1474
     * @param activeStatus the one-character active status
     * @param version the row version the persistence provider checked, or zero when no row was read
     */
    public record CardProjection(String cardNumber,
                                 String accountId,
                                 String embossedName,
                                 String expirationDate,
                                 String activeStatus,
                                 long version) {

        /**
         * Projects the working copy of a row: the stored values, with the embossed name as the turn's own
         * working storage holds it.
         *
         * <p>The name is supplied separately because the two folds act on the working copy and never on
         * the stored row. Projecting the row's own name would show an unfolded value where the legacy
         * shows a folded one, and mutating the row to make them agree would persist a fold the legacy
         * never persists.
         *
         * @param card the row to project; must not be {@code null}
         * @param workingEmbossedName the embossed name as this turn's working storage holds it, or
         *                            {@code null} to take the row's own value
         * @return the projection, never {@code null}
         * @throws NullPointerException if {@code card} is {@code null}
         */
        public static CardProjection of(final Card card, final String workingEmbossedName) {
            Objects.requireNonNull(card, "card must not be null");
            return new CardProjection(card.getCardNum(), card.getCardAcctId(),
                    (workingEmbossedName == null) ? card.getCardEmbossedName() : workingEmbossedName,
                    card.getCardExpirationDate(), card.getCardActiveStatus(), card.getVersion());
        }

        /**
         * Renders the projection without the card number.
         *
         * @return a diagnostic rendering carrying no sensitive component
         */
        @Override
        public String toString() {
            return "CardProjection[cardNumber=***REDACTED***"
                    + ", accountId=" + this.accountId
                    + ", embossedName=" + this.embossedName
                    + ", expirationDate=" + this.expirationDate
                    + ", activeStatus=" + this.activeStatus
                    + ", version=" + this.version + ']';
        }
    }

    /**
     * The outcome of one turn of the card-update screen.
     *
     * <p>Carries no transport concern of any kind: no status code, no response entity, no header. The
     * route is a value the caller acts on, exactly as the legacy's transferred program name was.
     *
     * @param route the resolved destination, which for a turn that stays on this screen is the
     *              card-update route itself
     * @param navigationContext the state to echo back, with the re-enter gate set as the turn left it
     * @param reArmedTransactionId the transaction the terminal return re-arms at lines 554 to 558
     * @param workArea the screen work area as the turn leaves it, carrying the decoded key, the
     *                 declarative next-program fields of lines 570 to 572 and 1326 to 1327, and the
     *                 error message moved at lines 547 and 569
     * @param changeAction the screen state the next turn will echo
     * @param card the card as the turn leaves it, or {@code null} when none was fetched
     * @param carriedImage the fetched image to echo back, refreshed when the write path found the record
     *                     changed, per lines 1512 to 1517
     * @param message the summary message, first error wins; empty when no message was raised
     * @param infoMessage the informational message the header line carries
     * @param focusField the field the cursor is positioned on, resolved in the clause order of lines
     *                   1211 to 1235
     * @param errorFlag {@code true} when the turn raised an error: the analogue of
     *                  {@code INPUT-ERROR}, <em>or</em> an attention identifier that did not decode
     * @param attentionKeyUnmapped {@code true} when the raw attention identifier did not decode, which
     *                             the key translator reports as no key at all because the copybook it
     *                             translates declares no catch-all across its twenty-eight clauses.
     *                             Carried separately because the legacy has no such error: it coerces an
     *                             undecodable key to the enter key at lines 422 to 424 and says nothing.
     *                             Overloading the input flag with it would let the edit driver's
     *                             {@code SET INPUT-OK} at line 643 silently discard the report
     * @param reEntry {@code true} when the turn ran as a re-submission, which is the gate that makes
     *                decoration conditional
     * @param changeDetected {@code true} when the write path found the stored record no longer matched
     *                       the carried image
     * @param writeOutcome the write path's outcome, or the not-attempted state when it did not run
     * @param fieldErrors the per-field detail, MISSING for a field not supplied and INVALID for one
     *                    supplied wrongly; unmodifiable, never {@code null}, and empty on a first entry
     * @param decoration the decoration the screen applied, one entry per marked field in the order the
     *                   legacy marked them
     * @param header the screen header
     * @param screen the seven screen fields as the turn leaves them
     */
    public record CardUpdateResult(NavigationService.Route route,
                                   NavigationContext navigationContext,
                                   String reArmedTransactionId,
                                   ScreenWorkArea workArea,
                                   ChangeAction changeAction,
                                   CardProjection card,
                                   CarriedCardImage carriedImage,
                                   String message,
                                   String infoMessage,
                                   String focusField,
                                   boolean errorFlag,
                                   boolean attentionKeyUnmapped,
                                   boolean reEntry,
                                   boolean changeDetected,
                                   WriteOutcome writeOutcome,
                                   List<ValidationException.FieldError> fieldErrors,
                                   FieldErrorDecorator decoration,
                                   ScreenHeader header,
                                   ScreenFields screen) {

        /**
         * Normalises the field-error list so a consumer never has to null-check it.
         */
        public CardUpdateResult {
            fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
        }

        /**
         * Reports whether this turn committed a change to the card file.
         *
         * @return {@code true} only when no error was raised and the rewrite completed
         */
        public boolean updateCommitted() {
            return !this.errorFlag && this.writeOutcome.committed();
        }

        /**
         * Reports whether this turn is asking the operator to confirm validated changes.
         *
         * @return {@code true} while the confirmation prompt is showing
         */
        public boolean awaitingConfirmation() {
            return this.changeAction.changesOkNotConfirmed();
        }
    }

    // ==============================================================================================
    // Working storage, lines 36 to 99 and 274 to 321
    // ==============================================================================================

    /**
     * The working storage of one turn.
     *
     * <p>Created inside the entry point and never retained, which is what keeps the service a stateless
     * singleton: the loop state of the retry path, the six edit flags and the accumulating decoration are
     * all fields of <em>this</em> object rather than of the service, so two concurrent turns cannot see
     * each other's state.
     *
     * <p>Field initial values reproduce {@code INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA} at
     * lines 374 to 376 followed by {@code SET WS-RETURN-MSG-OFF TO TRUE} at line 384. That matters for
     * the six edit flags in particular: {@code INITIALIZE} sets an alphanumeric field to spaces and the
     * blank condition name is declared over a space, so every flag starts <em>blank</em> rather than
     * not-OK, and the cursor-positioning evaluation at lines 1211 to 1235 reads those blank states on a
     * turn that runs no edits at all.
     */
    private static final class TurnState {

        /** {@code WS-TRANID}, line 45, set from this program's own literal at line 380. */
        private String transactionId = LEGACY_TRANSACTION_ID;

        /** {@code WS-INPUT-FLAG}, line 53. */
        private InputState inputState = InputState.PENDING;

        /** {@code WS-EDIT-ACCT-FLAG}, line 57. */
        private EditFlag accountFilterFlag = EditFlag.BLANK;

        /** {@code WS-EDIT-CARD-FLAG}, line 61. */
        private EditFlag cardFilterFlag = EditFlag.BLANK;

        /** {@code WS-EDIT-CARDNAME-FLAG}, line 65. */
        private EditFlag cardNameFlag = EditFlag.BLANK;

        /** {@code WS-EDIT-CARDSTATUS-FLAG}, line 69. */
        private EditFlag cardStatusFlag = EditFlag.BLANK;

        /** {@code WS-EDIT-CARDEXPMON-FLAG}, line 73. */
        private EditFlag expiryMonthFlag = EditFlag.BLANK;

        /** {@code WS-EDIT-CARDEXPYEAR-FLAG}, line 77. */
        private EditFlag expiryYearFlag = EditFlag.BLANK;

        /** {@code WS-PFK-FLAG}, line 84. */
        private AttentionKeyState attentionKeyState = AttentionKeyState.VALID;

        /** {@code WS-INFO-MSG}, line 157; its off condition covers spaces and low values at lines 158 to 159. */
        private String infoMessage = "";

        /** {@code WS-RETURN-MSG}, line 173, set off at line 384. */
        private String returnMessage = "";

        /** {@code CC-ACCT-ID} of the work area, line 34 of {@code app/cpy/CVCRD01Y.cpy}. */
        private String workAreaAccountId;

        /** {@code CC-CARD-NUM} of the work area, line 37 of {@code app/cpy/CVCRD01Y.cpy}. */
        private String workAreaCardNumber;

        /** {@code CCUP-CHANGE-ACTION}, line 276. */
        private ChangeAction changeAction = ChangeAction.DETAILS_NOT_FETCHED;

        /** {@code CCUP-OLD-DETAILS}, lines 291 to 301. */
        private CarriedCardImage carriedImage = CarriedCardImage.empty();

        /** {@code CCUP-NEW-ACCTID}, line 304. */
        private String newAccountId;

        /** {@code CCUP-NEW-CARDID}, line 305. */
        private String newCardNumber;

        /** {@code CCUP-NEW-CRDNAME}, line 308. */
        private String newEmbossedName;

        /** {@code CCUP-NEW-EXPYEAR}, line 310. */
        private String newExpiryYear;

        /** {@code CCUP-NEW-EXPMON}, line 311. */
        private String newExpiryMonth;

        /** {@code CCUP-NEW-EXPDAY}, line 312. */
        private String newExpiryDay;

        /** {@code CCUP-NEW-CRDSTCD}, line 313. */
        private String newActiveStatus;

        /** {@code WS-CARD-RID-CARDNUM}, line 129, moved at lines 1380 and 1425. */
        private String recordIdentificationCardNumber;

        /**
         * {@code CARD-RECORD} of the copybook included at line 353, as the read left it - with its
         * embossed name already upper folded when the read path folded it.
         */
        private Card cardRecord;

        /** The embossed name of the read record after the in-place fold; see the read and check paths. */
        private String foldedRecordEmbossedName;

        /** The outcome of {@code 9200-WRITE-PROCESSING}. */
        private WriteOutcome writeOutcome = WriteOutcome.NOT_ATTEMPTED;

        /**
         * Whether the change check advanced the carried image on this pass.
         *
         * <p>The progress condition of the retry loop. Lines 1512 to 1517 copy the locked record into the
         * carried image before the backward jump at line 1518, so a pass that jumped has necessarily made
         * the next comparison agree, and the loop cannot iterate a third time.
         */
        private boolean carriedImageRefreshed;

        /** The state echoed back to the client, replacing {@code CARDDEMO-COMMAREA}. */
        private NavigationContext navigationContext = NavigationContext.empty();

        /** The destination the turn resolved. */
        private NavigationService.Route route = NavigationService.Route.CARD_UPDATE;

        /** {@code CCARD-AID} of the work area, decoded; {@code null} when the identifier was unmapped. */
        private KeyAction keyAction;

        /** Whether the raw attention identifier failed to decode, the unmapped-key case. */
        private boolean attentionKeyUnmapped;

        /** {@code CDEMO-PGM-REENTER}: the gate that makes decoration conditional. */
        private boolean reEntry;

        /** The accumulating decoration, one entry per marked field in legacy marking order. */
        private FieldErrorDecorator decoration = FieldErrorDecorator.none();

        /** The per-field detail, in the order the decoration recorded it. */
        private final List<ValidationException.FieldError> fieldErrors = new ArrayList<>();

        /** The field the cursor is positioned on, resolved at lines 1211 to 1235. */
        private String focusField = FIELD_ACCOUNT_ID;

        /** Which field group the terminal left open, resolved at lines 1172 to 1208. */
        private FieldProtection fieldProtection = FieldProtection.SEARCH_KEYS_OPEN;

        /** {@code FKEYSCA} brightened at lines 1315 to 1317. */
        private boolean confirmationKeysHighlighted;

        /** The header, populated at lines 1052 to 1076. */
        private ScreenHeader header = new ScreenHeader("", "", LEGACY_TRANSACTION_ID,
                LEGACY_PROGRAM_NAME, "", "");

        /** The outbound fields, populated at lines 1082 to 1134 and decorated at lines 1168 to 1318. */
        private ScreenFields screen;

        /** Whether a dispatch arm has already ended the turn by transferring control. */
        private boolean turnEnded;

        /** {@code ERROR-OPNAME}, line 136, moved at line 1407. */
        private String errorOperationName = "";

        /** {@code ERROR-FILE}, line 140, moved at line 1408. */
        private String errorResourceName = "";

        /** {@code ERROR-RESP}, line 145, moved at line 1409. */
        private String errorResponseCode = "";

        /** {@code ERROR-RESP2}, line 149, moved at line 1410. */
        private String errorReasonCode = "";

        /** The raw two-character status the abend diagnostic names, when a file operation recorded one. */
        private String rawFileStatus;

        /**
         * {@code WS-RETURN-MSG-OFF}, line 174: the gate every summary assignment is wrapped in, and the
         * reason the first error wins the summary while all field flags are still set independently.
         *
         * @return {@code true} while no summary message has been raised
         */
        private boolean returnMessageOff() {
            return this.returnMessage == null || this.returnMessage.isBlank();
        }

        /**
         * {@code WS-NO-INFO-MESSAGE}, lines 158 to 159, declared over spaces and low values.
         *
         * @return {@code true} while no informational message has been raised
         */
        private boolean noInfoMessage() {
            return this.infoMessage == null || this.infoMessage.isBlank();
        }

        /**
         * {@code NO-CHANGES-DETECTED}, lines 187 to 188.
         *
         * <p>A condition name over the summary message field, so it is sticky: once the message is set it
         * stays set for the rest of the turn. Several tests depend on that stickiness.
         *
         * @return {@code true} when the no-change message has been raised
         */
        private boolean noChangesDetected() {
            return MSG_NO_CHANGE_DETECTED.equals(this.returnMessage);
        }

        /**
         * {@code DATA-WAS-CHANGED-BEFORE-UPDATE}, lines 207 to 208.
         *
         * <p>Also a condition name over the summary message field, and its stickiness is what terminates
         * the retry loop: the second pass finds the comparison satisfied but this test still true, so the
         * guard at lines 1455 to 1456 leaves the write path without writing.
         *
         * @return {@code true} when the concurrent-change message has been raised
         */
        private boolean dataWasChangedBeforeUpdate() {
            return OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE
                    .equals(this.returnMessage);
        }

        /**
         * {@code COULD-NOT-LOCK-FOR-UPDATE}, lines 205 to 206.
         *
         * @return {@code true} when the lock-failure message has been raised
         */
        private boolean couldNotLockForUpdate() {
            return MSG_COULD_NOT_LOCK_FOR_UPDATE.equals(this.returnMessage);
        }

        /**
         * {@code LOCKED-BUT-UPDATE-FAILED}, lines 209 to 210.
         *
         * @return {@code true} when the write-failure message has been raised
         */
        private boolean lockedButUpdateFailed() {
            return OptimisticLockConflictException.MSG_LOCKED_BUT_UPDATE_FAILED
                    .equals(this.returnMessage);
        }

        /**
         * {@code FOUND-CARDS-FOR-ACCOUNT}, lines 160 to 161: a condition name over the informational
         * message field, which is how the read path reports success to its callers.
         *
         * @return {@code true} when a card was found
         */
        private boolean foundCardsForAccount() {
            return INFO_DETAILS_SHOWN.equals(this.infoMessage);
        }

        /**
         * Raises the summary message only when none has been raised, reproducing the
         * {@code IF WS-RETURN-MSG-OFF} gate that wraps every one of the fifteen assignments.
         *
         * @param message the text to raise
         */
        private void raiseSummaryMessage(final String message) {
            if (returnMessageOff()) {
                this.returnMessage = message;
            }
        }
    }

    // ==============================================================================================
    // PROCEDURE DIVISION, line 366
    // ==============================================================================================

    /**
     * Runs one turn of the card-update screen.
     *
     * <p>This is the procedure division. It establishes the working storage the legacy declares at lines
     * 36 to 99 and 274 to 321, runs the main paragraph at line 367, and performs the terminal
     * {@code EXEC CICS RETURN TRANSID} of lines 554 to 558 by re-arming the transaction.
     *
     * <p><strong>The transaction boundary is here because the write path needs one.</strong> The row
     * version the entity carries is checked when the update is flushed, and this method is where that
     * failure is caught and translated. A conflict is recoverable: it is reported through the returned
     * value and, on the paths that must propagate it, raised as the module's conflict exception - never as
     * an abend.
     *
     * <p>Nothing is retained between calls. The retry loop's state is a local variable of the write
     * method, not a field, so two concurrent turns are wholly independent.
     *
     * @param input the transmitted screen, the raw attention identifier and the echoed state; must not be
     *              {@code null}
     * @return the outcome of the turn, never {@code null}
     * @throws NullPointerException if {@code input} is {@code null}
     * @throws com.carddemo.exception.AbendException if the action decision reaches its catch-all arm at
     *         lines 1019 to 1026, which is the one path on which this transaction abends
     */
    @Transactional
    public CardUpdateResult processCardUpdate(final CardUpdateScreenInput input) {
        Objects.requireNonNull(input, "input must not be null");

        final TurnState state = new TurnState();
        mainPara(state, input);

        // EXEC CICS RETURN TRANSID(LIT-THISTRANID) at lines 554 to 558. The transfer arm has already
        // ended the turn, and re-arming is idempotent, so this reproduces the unconditional return
        // without overriding a transfer.
        commonReturn(state);
        mainParaExit();

        LOG.debug("Card-update turn complete: route={} changeAction={} writeOutcome={} errorFlag={}"
                        + " reEntry={} changeDetected={} fieldErrors={} dispatched={}",
                state.route.getRouteValue(), state.changeAction, state.writeOutcome,
                state.inputState.inputError(), state.reEntry, state.dataWasChangedBeforeUpdate(),
                state.fieldErrors.size(), state.turnEnded);

        return toResult(state);
    }

    // ==============================================================================================
    // 0000-MAIN, line 367
    // ==============================================================================================

    /**
     * The main paragraph at line 367.
     *
     * <p>Registers the abend handler at lines 370 to 372 - a registration this translation expresses by
     * the abend path existing at all, since it is wired only in this family; clears the working storage at
     * lines 374 to 376; stores this program's transaction id at line 380 and clears the summary message at
     * line 384; decides at lines 388 to 401 whether the turn resets the conversation or takes the state
     * the client echoed; decodes and gates the attention key at lines 406 to 424; and then runs the
     * five-arm dispatch at lines 429 to 543.
     *
     * <p><strong>Clause order is the contract.</strong> The dispatch is an {@code EVALUATE TRUE}, which
     * stops at the first arm whose condition holds, so the arms are tested here in exactly the order the
     * source declares them. Several arms overlap - a turn can satisfy both the third and the fifth - and
     * reordering them would change which one runs.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen
     */
    private void mainPara(final TurnState state, final CardUpdateScreenInput input) {
        // MOVE LIT-THISTRANID TO WS-TRANID, line 380.
        state.transactionId = LEGACY_TRANSACTION_ID;

        // SET WS-RETURN-MSG-OFF TO TRUE, line 384.
        state.returnMessage = "";

        // Lines 388 to 401. A turn carrying no state at all, or one arriving fresh from the menu, resets
        // the conversation; otherwise the echoed state is taken as it was returned.
        final boolean arrivingFromMenu =
                LEGACY_MENU_PROGRAM.equals(navigationContextFromProgram(input))
                        && !echoedReEntry(input);
        if (input.carriesNoNavigationState() || arrivingFromMenu) {
            state.navigationContext = NavigationContext.empty().withFirstEntry();
            state.reEntry = false;
            state.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
            state.carriedImage = CarriedCardImage.empty();
        } else {
            state.navigationContext = input.navigationContext();
            state.reEntry = echoedReEntry(input);
            state.changeAction = (input.changeAction() == null)
                    ? ChangeAction.DETAILS_NOT_FETCHED
                    : input.changeAction();
            state.carriedImage = (input.carriedImage() == null)
                    ? CarriedCardImage.empty()
                    : input.carriedImage();
        }

        // PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT, lines 406 to 407. The two paragraphs of
        // app/cpy/CSSTRPFY.cpy are credited to the module's key translator and are not duplicated here;
        // this member is one of that copybook's five includers. The copybook declares twenty-eight
        // clauses and no catch-all, which is why an unrecognised identifier yields no key at all.
        storePfKey(state, input);

        // Lines 413 to 424. Whether the decoded key is permitted at this point, which is a separate
        // question from whether it decoded. A permitted-key failure is silent and coerces to the enter
        // key; a decode failure produces the fixed-width invalid-key message.
        gateAttentionKey(state);

        // EVALUATE TRUE, lines 429 to 543, in declared clause order.
        if (exitArmApplies(state)) {
            dispatchExit(state);
            return;
        }
        if (arrivalFromCardListApplies(state, input)) {
            dispatchArrivalFromCardList(state, input);
            return;
        }
        if (freshEntryApplies(state, input)) {
            dispatchFreshEntry(state);
            return;
        }
        if (state.changeAction.changesOkayedAndDone() || state.changeAction.changesFailed()) {
            dispatchCompletedOrFailed(state);
            return;
        }
        dispatchDefault(state, input);
    }

    /**
     * Reads the originating-program field of the echoed state without dereferencing an absent state.
     *
     * @param input the transmitted screen
     * @return the originating program name, or {@code null} when no state was echoed
     */
    private static String navigationContextFromProgram(final CardUpdateScreenInput input) {
        return (input.navigationContext() == null) ? null : input.navigationContext().fromProgram();
    }

    /**
     * Reads the re-enter gate of the echoed state without dereferencing an absent state.
     *
     * @param input the transmitted screen
     * @return {@code true} when the echoed state reports a re-entry
     */
    private static boolean echoedReEntry(final CardUpdateScreenInput input) {
        return input.navigationContext() != null && input.navigationContext().reEntry();
    }

    /**
     * The call site of {@code PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT} at lines 406 to 407 -
     * an inline block of {@code 0000-MAIN}, not a paragraph of this member.
     *
     * <p>The two paragraphs it performs live in {@code app/cpy/CSSTRPFY.cpy} at lines 17 and 80 and are
     * translated by, and credited to, the module's key translator. The copybook's selection has
     * twenty-eight clauses and <em>no</em> catch-all, so an identifier it does not recognise leaves the
     * attention field untouched; the translator expresses that as no key at all rather than inventing an
     * unknown constant.
     *
     * <p>An unmapped identifier is the one case that raises the fixed-width common invalid-key message.
     * That message is fifty characters wide and space padded, and it is used exactly as the catalogue
     * hands it over - never trimmed - because the legacy field it is moved into is fixed width.
     *
     * <p>The message travels the same first-error-wins channel as every other message in this member, so
     * the unconditional no-change assignment at lines 680 to 683 can still overwrite it - the legacy makes
     * that assignment unconditional and this translation does not second-guess it. The <em>report</em>
     * of the decode failure therefore lives on its own flag, which nothing overwrites, and the returned
     * error flag is the union of that flag and the legacy's input flag.
     *
     * <p>The upper twelve program-function keys fold onto the lower twelve inside the translator, per the
     * copybook's own clauses at its lines 54 to 77, so the seventeenth key is not a distinct action from
     * the fifth.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen, carrying the raw attention identifier
     */
    private void storePfKey(final TurnState state, final CardUpdateScreenInput input) {
        final Optional<KeyAction> decoded =
                PfKeyTranslator.translate(input.attentionKeyIdentifier());
        if (decoded.isEmpty()) {
            // The decode failure is reported on its own flag and NOT by setting INPUT-ERROR. The edit
            // driver's SET INPUT-OK TO TRUE at line 643 is unconditional, so a report parked on the
            // input flag would be silently discarded before the turn ends. The returned error flag is
            // the union of the input flag and this one, so the report reaches the caller either way.
            state.attentionKeyUnmapped = true;
            state.keyAction = null;
            state.raiseSummaryMessage(this.messageCatalogService.invalidKeyMessage());
            LOG.debug("Attention identifier did not decode: rule=csstrpfy-no-catch-all"
                    + " identifierPresent={}", input.attentionKeyIdentifier() != null);
            return;
        }
        state.attentionKeyUnmapped = false;
        state.keyAction = decoded.get();
    }

    /**
     * The permitted-key gate at lines 413 to 424 - an inline block of {@code 0000-MAIN}.
     *
     * <p>Line 413 assumes the key is not permitted; lines 414 to 420 admit exactly four cases, two of
     * them state dependent; lines 422 to 424 then rewrite a key that was not admitted as the enter key.
     * That rewrite is <em>silent</em>: the legacy raises no message for a key that decoded but is not
     * permitted here, which is a different outcome from a key that did not decode at all.
     *
     * @param state the turn's working storage
     */
    private void gateAttentionKey(final TurnState state) {
        // SET PFK-INVALID TO TRUE, line 413.
        state.attentionKeyState = AttentionKeyState.INVALID;

        final boolean permitted = state.keyAction == KeyAction.ENTER
                || state.keyAction == KeyAction.PFK03
                || (state.keyAction == KeyAction.PFK05
                        && state.changeAction.changesOkNotConfirmed())
                || (state.keyAction == KeyAction.PFK12
                        && !state.changeAction.detailsNotFetched());
        if (permitted) {
            state.attentionKeyState = AttentionKeyState.VALID;
        }

        // IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE, lines 422 to 424.
        if (!state.attentionKeyState.permitted()) {
            state.keyAction = KeyAction.ENTER;
        }
    }

    /**
     * Arm one of the dispatch, lines 435 to 439: whether this turn leaves the screen.
     *
     * @param state the turn's working storage
     * @return {@code true} when the exit key was pressed, or when a completed or failed write is
     *         returning to the card-list screen
     */
    private static boolean exitArmApplies(final TurnState state) {
        if (state.keyAction == KeyAction.PFK03) {
            return true;
        }
        final boolean cameFromCardList =
                LEGACY_CARD_LIST_MAPSET.equals(trimmedOrNull(state.navigationContext.lastMapset()));
        return cameFromCardList
                && (state.changeAction.changesOkayedAndDone() || state.changeAction.changesFailed());
    }

    /**
     * Arm one's body, lines 440 to 476.
     *
     * <p>Forces the exit key at line 440; resolves the destination transaction and program from the
     * originating fields, defaulting to the menu, at lines 442 to 454; records this program as the
     * originator at lines 456 to 457; clears the two business keys when the caller was the card-list
     * screen at lines 459 to 462; records this program's mapset and map at lines 466 to 467; commits at
     * lines 469 to 471; and transfers control at lines 473 to 476.
     *
     * <p><strong>Line 464 sets the user type to the standard-user code.</strong> In the shipped
     * navigation graph the value is already that code, because the card-update screen is reachable only
     * through the user menu, so the assignment is redundant there. It is reproduced because the source
     * makes it, and it is safe to reproduce because the echoed identity is reconciled against the
     * authenticated principal before it is trusted anywhere - which is a property of the navigation
     * state's own contract, not of this class. The redundancy is a decision-log entry.
     *
     * @param state the turn's working storage
     */
    private void dispatchExit(final TurnState state) {
        // SET CCARD-AID-PFK03 TO TRUE, line 440.
        state.keyAction = KeyAction.PFK03;

        final NavigationContext echoed = state.navigationContext;

        // Lines 442 to 447 and 449 to 454: default both destination fields to the menu when the
        // originating fields are blank. Resolving the route through the module's dispatch graph applies
        // the same rule, and raises the abend the legacy transfer would have raised for a name it
        // cannot resolve.
        state.route = this.navigationService.resolveBackNavigation(carriedState(echoed),
                NavigationService.Route.USER_MENU);

        final String destinationTransactionId = isBlank(echoed.fromTransactionId())
                ? LEGACY_MENU_TRANSACTION_ID
                : echoed.fromTransactionId();

        final boolean cameFromCardList =
                LEGACY_CARD_LIST_MAPSET.equals(trimmedOrNull(echoed.lastMapset()));

        state.navigationContext = new NavigationContext(
                // MOVE LIT-THISTRANID TO CDEMO-FROM-TRANID, line 456.
                LEGACY_TRANSACTION_ID,
                // MOVE LIT-THISPGM TO CDEMO-FROM-PROGRAM, line 457.
                LEGACY_PROGRAM_NAME,
                destinationTransactionId,
                state.route.getLegacyProgramName(),
                echoed.userId(),
                // SET CDEMO-USRTYP-USER TO TRUE, line 464.
                UserType.USER.getCode(),
                // SET CDEMO-PGM-ENTER TO TRUE, line 465.
                NavigationContext.ProgramContext.ENTER,
                echoed.customerId(),
                echoed.customerFirstName(),
                echoed.customerMiddleName(),
                echoed.customerLastName(),
                // MOVE ZEROS TO CDEMO-ACCT-ID CDEMO-CARD-NUM, lines 459 to 462.
                cameFromCardList ? zeroFill(ACCOUNT_ID_WIDTH) : echoed.accountId(),
                echoed.accountStatus(),
                cameFromCardList ? zeroFill(CARD_NUMBER_WIDTH) : echoed.cardNumber(),
                // MOVE LIT-THISMAP TO CDEMO-LAST-MAP, line 467.
                LEGACY_MAP_NAME,
                // MOVE LIT-THISMAPSET TO CDEMO-LAST-MAPSET, line 466.
                LEGACY_MAPSET_NAME.trim());

        state.reEntry = false;
        state.turnEnded = true;

        // No message is raised on this arm. The exit text is declared as a condition name at lines 175 to
        // 176 and is never set anywhere in the member, so raising it here would be an added behaviour.

        // The screen is NOT re-sent on this arm: the legacy transfers control at lines 469 to 475 and
        // never performs the send paragraph here. The send is performed all the same, but only so the
        // outbound fields are populated and the caller has a complete, non-null result to render or log.
        //
        // Because it is a borrowed call rather than one this arm makes, its side effects must not outlive
        // it. The send paragraph records THIS screen as the next destination - correct for every arm that
        // stays here, wrong for the one arm that leaves - so the transfer destination this arm resolved is
        // reasserted afterwards. Without that, pressing the exit key would report the card-update screen
        // as the next route and the transfer would be silently undone.
        final NavigationService.Route transferDestination = state.route;
        sendMap(state);
        state.route = transferDestination;

        LOG.debug("Card-update turn transferred control: rule=exit-arm route={} fromCardList={}",
                state.route.getRouteValue(), cameFromCardList);
    }

    /**
     * Arm two of the dispatch, lines 482 to 485: whether this turn arrived from the card-list screen with
     * the filter keys already resolved.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen
     * @return {@code true} on a first entry from the card-list program, or on that program's cancel key
     */
    private static boolean arrivalFromCardListApplies(final TurnState state,
            final CardUpdateScreenInput input) {
        final boolean fromCardList =
                LEGACY_CARD_LIST_PROGRAM.equals(trimmedOrNull(navigationContextFromProgram(input)));
        if (!fromCardList) {
            return false;
        }
        return state.navigationContext.firstEntry() || state.keyAction == KeyAction.PFK12;
    }

    /**
     * Arm two's body, lines 486 to 497.
     *
     * <p>Sets the re-enter gate and accepts both filter keys as already validated at lines 486 to 489 -
     * the card-list screen resolved them, so this screen does not re-edit them; takes both keys from the
     * echoed state at lines 490 to 491; fetches the card at lines 492 to 493; and presents it at lines 494
     * to 496.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen
     */
    private void dispatchArrivalFromCardList(final TurnState state,
            final CardUpdateScreenInput input) {
        // SET CDEMO-PGM-REENTER TO TRUE, line 486.
        state.reEntry = true;
        state.navigationContext = state.navigationContext.withReEntry();

        // SET INPUT-OK, FLG-ACCTFILTER-ISVALID, FLG-CARDFILTER-ISVALID, lines 487 to 489.
        state.inputState = InputState.OK;
        state.accountFilterFlag = EditFlag.IS_VALID;
        state.cardFilterFlag = EditFlag.IS_VALID;

        // MOVE CDEMO-ACCT-ID TO CC-ACCT-ID-N and CDEMO-CARD-NUM TO CC-CARD-NUM-N, lines 490 to 491.
        state.workAreaAccountId = state.navigationContext.accountId();
        state.workAreaCardNumber = state.navigationContext.cardNumber();
        state.newAccountId = state.workAreaAccountId;
        state.newCardNumber = state.workAreaCardNumber;

        readData(state);

        // SET CCUP-SHOW-DETAILS TO TRUE, line 494.
        if (state.foundCardsForAccount()) {
            state.changeAction = ChangeAction.SHOW_DETAILS;
        }

        sendMap(state);
        state.turnEnded = true;
    }

    /**
     * Arm three of the dispatch, lines 502 to 505: whether this is a fresh entry that must ask for the
     * filter keys.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen
     * @return {@code true} on a first entry with nothing fetched, or on arrival from the menu program
     */
    private static boolean freshEntryApplies(final TurnState state,
            final CardUpdateScreenInput input) {
        if (state.changeAction.detailsNotFetched() && state.navigationContext.firstEntry()) {
            return true;
        }
        return LEGACY_MENU_PROGRAM.equals(trimmedOrNull(navigationContextFromProgram(input)))
                && !echoedReEntry(input);
    }

    /**
     * Arm three's body, lines 506 to 511.
     *
     * <p>Clears this program's own work area at line 506, presents the empty screen at lines 507 to 508,
     * and arms the next turn as a re-entry that still has nothing fetched at lines 509 to 510.
     *
     * @param state the turn's working storage
     */
    private void dispatchFreshEntry(final TurnState state) {
        // INITIALIZE WS-THIS-PROGCOMMAREA, line 506.
        clearProgramWorkArea(state);

        sendMap(state);

        // SET CDEMO-PGM-REENTER TO TRUE and SET CCUP-DETAILS-NOT-FETCHED TO TRUE, lines 509 to 510.
        state.reEntry = true;
        state.navigationContext = state.navigationContext.withReEntry();
        state.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
        state.turnEnded = true;
    }

    /**
     * Arm four's body, lines 519 to 528, for the arm declared at lines 517 to 518.
     *
     * <p>Reached when the previous turn's write completed or failed and the caller was <em>not</em> the
     * card-list screen - the card-list case having already been taken by arm one. Clears this program's
     * work area, the miscellaneous storage and both business keys at lines 519 to 522; presents the empty
     * screen at lines 524 to 525; and arms the next turn as a re-entry with nothing fetched at lines 526
     * to 527.
     *
     * @param state the turn's working storage
     */
    private void dispatchCompletedOrFailed(final TurnState state) {
        // INITIALIZE WS-THIS-PROGCOMMAREA WS-MISC-STORAGE CDEMO-ACCT-ID CDEMO-CARD-NUM, lines 519 to 522.
        clearProgramWorkArea(state);
        clearMiscellaneousStorage(state);
        state.navigationContext = withClearedBusinessKeys(state.navigationContext);

        // SET CDEMO-PGM-ENTER TO TRUE, line 523.
        state.navigationContext = state.navigationContext.withFirstEntry();
        state.reEntry = false;

        sendMap(state);

        // SET CDEMO-PGM-REENTER TO TRUE and SET CCUP-DETAILS-NOT-FETCHED TO TRUE, lines 526 to 527.
        state.reEntry = true;
        state.navigationContext = state.navigationContext.withReEntry();
        state.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
        state.turnEnded = true;
    }

    /**
     * Arm five's body, the catch-all at lines 535 to 542.
     *
     * <p>The card has been presented and the operator has submitted something, so the turn edits the
     * inputs, decides what to do and re-presents the screen.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen
     */
    private void dispatchDefault(final TurnState state, final CardUpdateScreenInput input) {
        processInputs(state, input);
        decideAction(state);
        sendMap(state);
        state.turnEnded = true;
    }

    // ==============================================================================================
    // COMMON-RETURN, line 546
    // ==============================================================================================

    /**
     * The common return at line 546.
     *
     * <p>Moves the summary message into the work area's error slot at line 547, packs the shared and
     * program-specific communication areas at lines 549 to 552, and issues the terminal
     * {@code EXEC CICS RETURN TRANSID} at lines 554 to 558.
     *
     * <p>In the translation the packing is the returned value and the terminal return is the re-armed
     * transaction id, so this paragraph's whole effect is to settle those two things. It is idempotent,
     * which is why the entry point can call it unconditionally even on the arm that transferred control.
     *
     * @param state the turn's working storage
     */
    private void commonReturn(final TurnState state) {
        // MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG, line 547.
        state.transactionId = LEGACY_TRANSACTION_ID;
        if (!state.turnEnded || state.screen == null) {
            // No dispatch arm reached the send paragraph. The legacy cannot be in this state, because
            // every one of the five arms either sends or transfers; producing an empty but complete
            // screen keeps the returned value non-null rather than inventing a behaviour.
            state.screen = new ScreenFields(state.workAreaAccountId, state.workAreaCardNumber,
                    state.newEmbossedName, state.newActiveStatus, state.newExpiryMonth,
                    state.newExpiryYear, state.newExpiryDay, state.infoMessage, state.returnMessage,
                    state.fieldProtection, state.confirmationKeysHighlighted);
        }
    }

    // ==============================================================================================
    // 0000-MAIN-EXIT, line 560
    // ==============================================================================================

    /**
     * The main exit at line 560.
     *
     * <p>A bare {@code EXIT} at line 561. The legacy never reaches it, because the terminal return in the
     * preceding paragraph ends the task and the transfer arm never returns at all. It exists as a label
     * and is translated as a label: a named method with no effect, called so that the mapping from
     * paragraph to method is complete and verifiable rather than notional.
     */
    private static void mainParaExit() {
        // EXIT, line 561.
    }

    // ==============================================================================================
    // 1000-PROCESS-INPUTS, line 564
    // ==============================================================================================

    /**
     * The input-processing driver at line 564.
     *
     * <p>Receives the map at lines 565 to 566, edits it at lines 567 to 568, and then records the summary
     * message and this program's own declarative next-program fields into the work area at lines 569 to
     * 572. Those three name fields are declarative only: the legacy never dispatches on them, it
     * transfers on the communication area's destination fields instead.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen
     */
    private void processInputs(final TurnState state, final CardUpdateScreenInput input) {
        receiveMap(state, input);
        editMapInputs(state);
        processInputsExit();
    }

    /**
     * The input-processing exit at line 575. A bare {@code EXIT} at line 576.
     */
    private static void processInputsExit() {
        // EXIT, line 576.
    }

    // ==============================================================================================
    // 1100-RECEIVE-MAP, line 578
    // ==============================================================================================

    /**
     * The receive paragraph at line 578.
     *
     * <p>Receives the input map at lines 579 to 584, clears this program's new-value group at line 586,
     * and then normalises six of the seven transmitted fields at lines 589 to 635: a field holding the
     * marker character or holding spaces becomes low values, and anything else is taken as transmitted.
     *
     * <p><strong>The expiry day is the exception</strong> and it is deliberate. Line 621 moves it
     * unconditionally, with no marker test, so it is the one received field that keeps a transmitted
     * marker character. The screen protects that field anyway, and the send paragraph writes the old value
     * back into it at line 1123, so the asymmetry has no visible effect - but reproducing it matters,
     * because the day participates in the change comparison at lines 680 to 681.
     *
     * <p>The two filter fields are stored twice, into the work area and into the new-value group, exactly
     * as the source stores them at lines 591 to 595 and 600 to 604.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen
     */
    private void receiveMap(final TurnState state, final CardUpdateScreenInput input) {
        // INITIALIZE CCUP-NEW-DETAILS, line 586.
        state.newAccountId = null;
        state.newCardNumber = null;
        state.newEmbossedName = null;
        state.newExpiryYear = null;
        state.newExpiryMonth = null;
        state.newExpiryDay = null;
        state.newActiveStatus = null;

        // Lines 589 to 596.
        final String accountId = normaliseReceivedField(input.accountId());
        state.workAreaAccountId = accountId;
        state.newAccountId = accountId;

        // Lines 598 to 605.
        final String cardNumber = normaliseReceivedField(input.cardNumber());
        state.workAreaCardNumber = cardNumber;
        state.newCardNumber = cardNumber;

        // Lines 607 to 612.
        state.newEmbossedName = normaliseReceivedField(input.embossedName());

        // Lines 614 to 619.
        state.newActiveStatus = normaliseReceivedField(input.activeStatus());

        // MOVE EXPDAYI OF CCRDUPAI TO CCUP-NEW-EXPDAY, line 621: unconditional, no marker test.
        state.newExpiryDay = input.expiryDay();

        // Lines 623 to 628.
        state.newExpiryMonth = normaliseReceivedField(input.expiryMonth());

        // Lines 630 to 635.
        state.newExpiryYear = normaliseReceivedField(input.expiryYear());

        receiveMapExit();
    }

    /**
     * The receive exit at line 638. A bare {@code EXIT} at line 639.
     */
    private static void receiveMapExit() {
        // EXIT, line 639.
    }

    /**
     * The marker-to-low-values normalisation applied at lines 589 to 590, 598 to 599, 607 to 608, 614 to
     * 615, 623 to 624 and 630 to 631.
     *
     * @param transmitted the value as transmitted, possibly {@code null}
     * @return {@code null} - the analogue of low values - when the field held the marker character or
     *         nothing but spaces; otherwise the value unchanged
     */
    private static String normaliseReceivedField(final String transmitted) {
        if (transmitted == null || CLEARED_FIELD_MARKER.equals(transmitted.trim())
                || transmitted.isBlank()) {
            return null;
        }
        return transmitted;
    }

    // ==============================================================================================
    // 1200-EDIT-MAP-INPUTS, line 641
    // ==============================================================================================

    /**
     * The edit driver at line 641.
     *
     * <p>Two distinct paths, chosen at line 645 by whether a card has been fetched.
     *
     * <p><strong>Nothing fetched.</strong> Edit both filter keys at lines 647 to 651, blank the new-value
     * card-data group at line 653, raise the no-input message when both filters came back blank at lines
     * 656 to 659, and leave at line 661. The card-data fields are not edited at all on this path, because
     * there is nothing to compare them against.
     *
     * <p><strong>Card fetched.</strong> Accept both filters as already valid at lines 668 to 670, copy the
     * fetched values into the destination and screen work fields at lines 671 to 677, and then run the
     * change comparison at lines 680 to 681. If nothing changed, or if the changes were already validated
     * or already written, accept all four card-data fields and leave at lines 685 to 693. Otherwise mark
     * the state as not-OK at line 696 and run the four card-data edits in declared order at lines 698 to
     * 708, promoting the state to validated-awaiting-confirmation only when none of them failed, at lines
     * 710 to 714.
     *
     * <p><strong>The comparison at lines 680 to 681 folds both operands before comparing</strong>, over
     * the whole fifty-nine-character card-data group. A change differing only in letter case is therefore
     * not a change, here as at the write path's own comparison. The fold goes through the module's
     * character-table primitive; the locale-aware Java method is forbidden on both sides.
     *
     * @param state the turn's working storage
     */
    private void editMapInputs(final TurnState state) {
        // SET INPUT-OK TO TRUE, line 643.
        state.inputState = InputState.OK;

        if (state.changeAction.detailsNotFetched()) {
            editAccount(state);
            editCard(state);

            // MOVE LOW-VALUES TO CCUP-NEW-CARDDATA, line 653.
            state.newEmbossedName = null;
            state.newExpiryYear = null;
            state.newExpiryMonth = null;
            state.newExpiryDay = null;
            state.newActiveStatus = null;

            // Lines 656 to 659. Not gated on the summary being off: the source sets this condition name
            // unconditionally, so it overwrites whichever filter message was raised first.
            if (state.accountFilterFlag.blank() && state.cardFilterFlag.blank()) {
                state.returnMessage = MSG_NO_INPUT_RECEIVED;
            }

            // GO TO 1200-EDIT-MAP-INPUTS-EXIT, line 661.
            editMapInputsExit();
            return;
        }

        // Lines 668 to 670.
        state.infoMessage = INFO_DETAILS_SHOWN;
        state.accountFilterFlag = EditFlag.IS_VALID;
        state.cardFilterFlag = EditFlag.IS_VALID;

        // Lines 671 to 677: the fetched values become the destination keys and the screen work fields.
        state.workAreaAccountId = state.carriedImage.accountId();
        state.workAreaCardNumber = state.carriedImage.cardNumber();

        // Lines 680 to 681: fold both sides, then compare the whole group.
        if (cardDataGroupUnchanged(state)) {
            state.returnMessage = MSG_NO_CHANGE_DETECTED;
        }

        // Lines 685 to 693.
        if (state.noChangesDetected()
                || state.changeAction.changesOkNotConfirmed()
                || state.changeAction.changesOkayedAndDone()) {
            state.cardNameFlag = EditFlag.IS_VALID;
            state.cardStatusFlag = EditFlag.IS_VALID;
            state.expiryMonthFlag = EditFlag.IS_VALID;
            state.expiryYearFlag = EditFlag.IS_VALID;
            editMapInputsExit();
            return;
        }

        // SET CCUP-CHANGES-NOT-OK TO TRUE, line 696.
        state.changeAction = ChangeAction.CHANGES_NOT_OK;

        // Lines 698 to 708, in declared order.
        editName(state);
        editCardStatus(state);
        editExpiryMonth(state);
        editExpiryYear(state);

        // Lines 710 to 714.
        if (!state.inputState.inputError()) {
            state.changeAction = ChangeAction.CHANGES_OK_NOT_CONFIRMED;
        }

        editMapInputsExit();
    }

    /**
     * The edit-driver exit at line 717. A bare {@code EXIT} at line 718.
     */
    private static void editMapInputsExit() {
        // EXIT, line 718.
    }

    /**
     * The change comparison of lines 680 to 681, component by component.
     *
     * <p>The source compares two groups of identical shape - {@code CCUP-NEW-CARDDATA} at lines 307 to
     * 313 against {@code CCUP-OLD-CARDDATA} at lines 295 to 301 - each a fifty-character name, a
     * four-character year, a two-character month, a two-character day and a one-character status.
     * Comparing the five components pairwise at their declared widths is the same comparison as comparing
     * the concatenation, and it keeps the field boundaries visible.
     *
     * <p><strong>Both operands are folded first</strong>, which is what makes a case-only edit compare
     * equal. The fold is the module's character-table primitive, never the locale-aware library method.
     *
     * <p>An absent component is <em>not</em> equal to a blank one, because the source distinguishes them:
     * {@code INITIALIZE} leaves spaces, whereas the receive paragraph and line 653 move low values.
     *
     * @param state the turn's working storage
     * @return {@code true} when every component of the submitted group matches the fetched group
     */
    private static boolean cardDataGroupUnchanged(final TurnState state) {
        final CarriedCardImage fetched = state.carriedImage;
        return sameFoldedFixedField(state.newEmbossedName, fetched.embossedName(),
                        EMBOSSED_NAME_WIDTH)
                && sameFoldedFixedField(state.newExpiryYear, fetched.expiryYear(), EXPIRY_YEAR_WIDTH)
                && sameFoldedFixedField(state.newExpiryMonth, fetched.expiryMonth(),
                        EXPIRY_MONTH_WIDTH)
                && sameFoldedFixedField(state.newExpiryDay, fetched.expiryDay(), EXPIRY_DAY_WIDTH)
                && sameFoldedFixedField(state.newActiveStatus, fetched.activeStatus(),
                        ACTIVE_STATUS_WIDTH);
    }

    /**
     * Compares two values as the fixed-width fields they are moved into, after folding both.
     *
     * <p>Folding both sides before comparing is the behaviour of lines 680 to 681 and of lines 1499 to
     * 1508, and it is the reason a case-only edit is not treated as a change.
     *
     * @param left the first value, or {@code null} for low values
     * @param right the second value, or {@code null} for low values
     * @param width the declared field width both values are compared across
     * @return {@code true} when the two fields hold the same characters once folded
     */
    private static boolean sameFoldedFixedField(final String left, final String right,
            final int width) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return sameFixedField(CobolStringUtils.asciiUpperFold(left),
                CobolStringUtils.asciiUpperFold(right), width);
    }

    /**
     * Compares two non-null values across a fixed field width, position by position.
     *
     * <p>A position beyond the end of a value reads as a space, which is how a shorter value sits inside a
     * fixed alphanumeric field, and positions beyond the width are not read at all, which is how a longer
     * value is truncated by the move.
     *
     * @param left the first value; must not be {@code null}
     * @param right the second value; must not be {@code null}
     * @param width the declared field width
     * @return {@code true} when every position within the width agrees
     */
    private static boolean sameFixedField(final String left, final String right, final int width) {
        for (int position = 0; position < width; position++) {
            if (fixedFieldCharAt(left, position) != fixedFieldCharAt(right, position)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads one position of a value as it sits inside a fixed alphanumeric field.
     *
     * @param value the value; must not be {@code null}
     * @param position the zero-based position
     * @return the character at that position, or a space when the value is shorter
     */
    private static char fixedFieldCharAt(final String value, final int position) {
        return (position < value.length()) ? value.charAt(position) : ' ';
    }

    // ==============================================================================================
    // 1210-EDIT-ACCOUNT, line 721
    // ==============================================================================================

    /**
     * The account-filter edit at line 721.
     *
     * <p>Assumes not-OK at line 722; treats low values, spaces or an all-zero numeric value as not
     * supplied at lines 725 to 736, clearing both destination fields and leaving; otherwise requires the
     * whole eleven-character field to be numeric at lines 740 to 750, and accepts it at lines 751 to 755.
     *
     * <p>Both failure branches raise their message through the first-error-wins gate at lines 730 and 743
     * while setting the field flag unconditionally, which is exactly why one summary message coexists with
     * several marked fields.
     *
     * @param state the turn's working storage
     */
    private void editAccount(final TurnState state) {
        // SET FLG-ACCTFILTER-NOT-OK TO TRUE, line 722.
        state.accountFilterFlag = EditFlag.NOT_OK;

        // Lines 725 to 727.
        if (notSuppliedFixedField(state.workAreaAccountId, ACCOUNT_ID_WIDTH)) {
            state.inputState = InputState.ERROR;
            state.accountFilterFlag = EditFlag.BLANK;
            state.raiseSummaryMessage(MSG_ACCOUNT_NOT_PROVIDED);
            // MOVE ZEROES TO CDEMO-ACCT-ID and LOW-VALUES TO CCUP-NEW-ACCTID, lines 733 to 734.
            state.newAccountId = null;
            // GO TO 1210-EDIT-ACCOUNT-EXIT, line 735.
            editAccountExit();
            return;
        }

        // IF CC-ACCT-ID IS NOT NUMERIC, line 740.
        if (!numericFixedField(state.workAreaAccountId, ACCOUNT_ID_WIDTH)) {
            state.inputState = InputState.ERROR;
            state.accountFilterFlag = EditFlag.NOT_OK;
            state.raiseSummaryMessage(MSG_ACCOUNT_FILTER_ELEVEN_DIGITS);
            state.newAccountId = null;
            // GO TO 1210-EDIT-ACCOUNT-EXIT, line 750.
            editAccountExit();
            return;
        }

        // Lines 752 to 754.
        state.newAccountId = state.workAreaAccountId;
        state.accountFilterFlag = EditFlag.IS_VALID;
        editAccountExit();
    }

    /**
     * The account-filter exit at line 758. A bare {@code EXIT} at line 759.
     */
    private static void editAccountExit() {
        // EXIT, line 759.
    }

    // ==============================================================================================
    // 1220-EDIT-CARD, line 762
    // ==============================================================================================

    /**
     * The card-filter edit at line 762.
     *
     * <p>The same shape as the account edit, over the sixteen-character field: assumes not-OK at line 765,
     * treats low values, spaces or an all-zero numeric value as not supplied at lines 768 to 780, requires
     * the whole field to be numeric at lines 784 to 794, and accepts it at lines 795 to 799.
     *
     * @param state the turn's working storage
     */
    private void editCard(final TurnState state) {
        // SET FLG-CARDFILTER-NOT-OK TO TRUE, line 765.
        state.cardFilterFlag = EditFlag.NOT_OK;

        // Lines 768 to 770.
        if (notSuppliedFixedField(state.workAreaCardNumber, CARD_NUMBER_WIDTH)) {
            state.inputState = InputState.ERROR;
            state.cardFilterFlag = EditFlag.BLANK;
            state.raiseSummaryMessage(MSG_CARD_NOT_PROVIDED);
            // MOVE ZEROES TO CDEMO-CARD-NUM CCUP-NEW-CARDID, lines 777 to 778.
            state.newCardNumber = null;
            // GO TO 1220-EDIT-CARD-EXIT, line 779.
            editCardExit();
            return;
        }

        // IF CC-CARD-NUM IS NOT NUMERIC, line 784.
        if (!numericFixedField(state.workAreaCardNumber, CARD_NUMBER_WIDTH)) {
            state.inputState = InputState.ERROR;
            state.cardFilterFlag = EditFlag.NOT_OK;
            state.raiseSummaryMessage(MSG_CARD_FILTER_SIXTEEN_DIGITS);
            state.newCardNumber = null;
            // GO TO 1220-EDIT-CARD-EXIT, line 794.
            editCardExit();
            return;
        }

        // Lines 796 to 798.
        state.newCardNumber = state.workAreaCardNumber;
        state.cardFilterFlag = EditFlag.IS_VALID;
        editCardExit();
    }

    /**
     * The card-filter exit at line 802. A bare {@code EXIT} at line 803.
     */
    private static void editCardExit() {
        // EXIT, line 803.
    }

    // ==============================================================================================
    // 1230-EDIT-NAME, line 806, exit at line 841
    // ==============================================================================================

    /**
     * The embossed-name edit at line <b>806</b>, with its exit at line <b>841</b>.
     *
     * <p>Assumes not-OK at line 808; treats low values, spaces or an all-zero value as not supplied at
     * lines 811 to 820; then applies the character check at line <b>824</b>; then accepts at line 839.
     *
     * <p><strong>Embedded spaces pass.</strong> The check is not a character-class test. Lines 823 to 826
     * copy the name into a fifty-character work field and convert the fifty-two-character alphabetic table
     * to spaces in place; line 828 then asserts that the trimmed remainder has length zero. A character
     * that was <em>already</em> a space survives the conversion as a space and is trimmed away with the
     * blanked letters, so a name carrying spaces satisfies the assertion. The faithful predicate is
     * therefore "every character is a letter <em>or a space</em>", and the source's own comment at line 822
     * says exactly that.
     *
     * <p>Writing {@code chars().allMatch(Character::isLetter)} instead would reject {@code "MARY ANN"} and
     * would reject the live fixture value {@code "Aniya Von"} - and every one of the fifty rows of
     * {@code app/data/ASCII/carddata.txt} carries an embedded space, so it would reject the entire seeded
     * population. The predicate is delegated to the module's character-table primitive, whose own
     * documentation credits this site to it.
     *
     * @param state the turn's working storage
     */
    private void editName(final TurnState state) {
        // SET FLG-CARDNAME-NOT-OK TO TRUE, line 808.
        state.cardNameFlag = EditFlag.NOT_OK;

        // Lines 811 to 813.
        if (notSuppliedFixedField(state.newEmbossedName, EMBOSSED_NAME_WIDTH)) {
            state.inputState = InputState.ERROR;
            state.cardNameFlag = EditFlag.BLANK;
            state.raiseSummaryMessage(MSG_NAME_NOT_PROVIDED);
            // GO TO 1230-EDIT-NAME-EXIT, line 819.
            editNameExit();
            return;
        }

        // Lines 823 to 837: the blank-and-trim alphabetic idiom, at line 824.
        if (!CobolStringUtils.isAlphaOrSpace(state.newEmbossedName)) {
            state.inputState = InputState.ERROR;
            state.cardNameFlag = EditFlag.NOT_OK;
            state.raiseSummaryMessage(MSG_NAME_MUST_BE_ALPHA);
            // GO TO 1230-EDIT-NAME-EXIT, line 836.
            editNameExit();
            return;
        }

        // SET FLG-CARDNAME-ISVALID TO TRUE, line 839.
        state.cardNameFlag = EditFlag.IS_VALID;
        editNameExit();
    }

    /**
     * The embossed-name exit at line <b>841</b>. A bare {@code EXIT} at line 842.
     */
    private static void editNameExit() {
        // EXIT, line 842.
    }

    // ==============================================================================================
    // 1240-EDIT-CARDSTATUS, line 845
    // ==============================================================================================

    /**
     * The active-status edit at line 845.
     *
     * <p>Assumes not-OK at line 847; treats low values, spaces or a zero character as not supplied at lines
     * 850 to 859; then admits exactly the two characters the yes-or-no condition name declares at line 91,
     * accepting at lines 863 to 864 and failing at lines 865 to 872.
     *
     * <p>Both branches raise the same text, which the source declares once at lines 195 to 196 and sets at
     * lines 856 and 869. The value is validated against the two literal characters and <em>not</em> parsed
     * into anything: the stored column is one character wide and the card-status enum's own codes are those
     * two characters.
     *
     * @param state the turn's working storage
     */
    private void editCardStatus(final TurnState state) {
        // SET FLG-CARDSTATUS-NOT-OK TO TRUE, line 847.
        state.cardStatusFlag = EditFlag.NOT_OK;

        // Lines 850 to 852.
        if (notSuppliedFixedField(state.newActiveStatus, ACTIVE_STATUS_WIDTH)) {
            state.inputState = InputState.ERROR;
            state.cardStatusFlag = EditFlag.BLANK;
            state.raiseSummaryMessage(MSG_STATUS_MUST_BE_YES_NO);
            // GO TO 1240-EDIT-CARDSTATUS-EXIT, line 858.
            editCardStatusExit();
            return;
        }

        // MOVE CCUP-NEW-CRDSTCD TO FLG-YES-NO-CHECK, line 861; IF FLG-YES-NO-VALID, line 863. The two
        // accepted characters are the card-status enum's own codes, so the screen edit and the stored
        // column agree by construction rather than by coincidence.
        final String submitted = state.newActiveStatus.trim();
        if (CardStatus.fromCode(submitted).isPresent()) {
            state.cardStatusFlag = EditFlag.IS_VALID;
            editCardStatusExit();
            return;
        }

        state.inputState = InputState.ERROR;
        state.cardStatusFlag = EditFlag.NOT_OK;
        state.raiseSummaryMessage(MSG_STATUS_MUST_BE_YES_NO);
        // GO TO 1240-EDIT-CARDSTATUS-EXIT, line 871.
        editCardStatusExit();
    }

    /**
     * The active-status exit at line 874. A bare {@code EXIT} at line 875.
     */
    private static void editCardStatusExit() {
        // EXIT, line 875.
    }

    // ==============================================================================================
    // 1250-EDIT-EXPIRY-MON, line 877
    // ==============================================================================================

    /**
     * The expiry-month edit at line 877.
     *
     * <p>Assumes not-OK at line 880; treats low values, spaces or a zero value as not supplied at lines 883
     * to 892; then moves the value through the two-character work field whose numeric redefinition at lines
     * 92 to 95 declares the accepted range, accepting at lines 898 to 899 and failing at lines 900 to 907.
     *
     * <p>The range test reads the <em>numeric redefinition</em> of an alphanumeric field, so a value that is
     * not two digits does not satisfy it. Both conditions are therefore checked: the field must be numeric
     * across its declared width, and its value must fall in the declared range.
     *
     * @param state the turn's working storage
     */
    private void editExpiryMonth(final TurnState state) {
        // SET FLG-CARDEXPMON-NOT-OK TO TRUE, line 880.
        state.expiryMonthFlag = EditFlag.NOT_OK;

        // Lines 883 to 885.
        if (notSuppliedFixedField(state.newExpiryMonth, EXPIRY_MONTH_WIDTH)) {
            state.inputState = InputState.ERROR;
            state.expiryMonthFlag = EditFlag.BLANK;
            state.raiseSummaryMessage(MSG_EXPIRY_MONTH_NOT_VALID);
            // GO TO 1250-EDIT-EXPIRY-MON-EXIT, line 891.
            editExpiryMonthExit();
            return;
        }

        // MOVE CCUP-NEW-EXPMON TO CARD-MONTH-CHECK, line 896; IF VALID-MONTH, line 898.
        if (withinFixedFieldRange(state.newExpiryMonth, EXPIRY_MONTH_WIDTH, EXPIRY_MONTH_MINIMUM,
                EXPIRY_MONTH_MAXIMUM)) {
            state.expiryMonthFlag = EditFlag.IS_VALID;
            editExpiryMonthExit();
            return;
        }

        state.inputState = InputState.ERROR;
        state.expiryMonthFlag = EditFlag.NOT_OK;
        state.raiseSummaryMessage(MSG_EXPIRY_MONTH_NOT_VALID);
        // GO TO 1250-EDIT-EXPIRY-MON-EXIT, line 906.
        editExpiryMonthExit();
    }

    /**
     * The expiry-month exit at line 910. A bare {@code EXIT} at line 911.
     */
    private static void editExpiryMonthExit() {
        // EXIT, line 911.
    }

    // ==============================================================================================
    // 1260-EDIT-EXPIRY-YEAR, line 913
    // ==============================================================================================

    /**
     * The expiry-year edit at line 913.
     *
     * <p>The same shape as the month edit over the four-character field, with one structural difference
     * that is reproduced rather than tidied: this paragraph tests for a value <em>before</em> assuming
     * not-OK. The not-supplied test is at lines 916 to 925 and the {@code SET FLG-CARDEXPYEAR-NOT-OK}
     * comes afterwards at line 930, whereas every sibling edit sets its assumption first. The effect is
     * identical because the not-supplied branch sets the blank flag and leaves, but the ordering is
     * different and is kept.
     *
     * <p>The accepted range is declared by the numeric redefinition at lines 96 to 99.
     *
     * <p>The comment at lines 927 to 928 reads "Must be 1 to 12", copied from the month edit above. It is
     * stale; the code governs and the range is the declared one. Recorded as a source anomaly.
     *
     * @param state the turn's working storage
     */
    private void editExpiryYear(final TurnState state) {
        // Lines 916 to 918: tested before the not-OK assumption, unlike every sibling edit.
        if (notSuppliedFixedField(state.newExpiryYear, EXPIRY_YEAR_WIDTH)) {
            state.inputState = InputState.ERROR;
            state.expiryYearFlag = EditFlag.BLANK;
            state.raiseSummaryMessage(MSG_EXPIRY_YEAR_NOT_VALID);
            // GO TO 1260-EDIT-EXPIRY-YEAR-EXIT, line 924.
            editExpiryYearExit();
            return;
        }

        // SET FLG-CARDEXPYEAR-NOT-OK TO TRUE, line 930.
        state.expiryYearFlag = EditFlag.NOT_OK;

        // MOVE CCUP-NEW-EXPYEAR TO CARD-YEAR-CHECK, line 932; IF VALID-YEAR, line 934.
        if (withinFixedFieldRange(state.newExpiryYear, EXPIRY_YEAR_WIDTH, EXPIRY_YEAR_MINIMUM,
                EXPIRY_YEAR_MAXIMUM)) {
            state.expiryYearFlag = EditFlag.IS_VALID;
            editExpiryYearExit();
            return;
        }

        state.inputState = InputState.ERROR;
        state.expiryYearFlag = EditFlag.NOT_OK;
        state.raiseSummaryMessage(MSG_EXPIRY_YEAR_NOT_VALID);
        // GO TO 1260-EDIT-EXPIRY-YEAR-EXIT, line 942.
        editExpiryYearExit();
    }

    /**
     * The expiry-year exit at line 945. A bare {@code EXIT} at line 946.
     */
    private static void editExpiryYearExit() {
        // EXIT, line 946.
    }

    // ----------------------------------------------------------------------------------------------
    // Fixed-field predicates shared by the six edits
    //
    // These are SCREEN-FIELD predicates, not record-layout ones. They read a transmitted field across its
    // declared width, which is what this member's own edit paragraphs do; no record image is sliced here,
    // no byte offset appears, and String.substring is not used anywhere in this class. Record-layout
    // slicing belongs to the module's mapper layer and stays there.
    //
    // The module's string utility is NOT delegated to for either predicate, and the reason is behavioural
    // rather than stylistic. Its unsupplied-lexeme predicate treats only spaces as unsupplied, while these
    // edits test the field's numeric redefinition against the figurative zero constant, so an all-zero
    // field is unsupplied here and is not there. Its numeric predicate scans a lexeme of whatever length it
    // is given, while the COBOL class test reads every position of the DECLARED field, so a value shorter
    // than its field fails the class test because the move left spaces behind it. Delegating would change
    // behaviour in both directions. The alphabetic fold and the alphabetic-or-space predicate ARE delegated,
    // because for those the utility's semantics are exactly the source's.
    // ----------------------------------------------------------------------------------------------

    /**
     * The not-supplied test every edit opens with: low values, or spaces, or all zero characters.
     *
     * <p>The three conditions appear together at lines 725 to 727, 768 to 770, 811 to 813, 850 to 852, 883
     * to 885 and 916 to 918. The all-zeros arm is the one most easily lost in translation: comparing an
     * alphanumeric field against the figurative zero constant compares it against that many zero
     * characters, so a status of {@code "0"}, a month of {@code "00"} and a year of {@code "0000"} are each
     * "not supplied" rather than "invalid".
     *
     * @param value the transmitted value, or {@code null} for low values
     * @param width the declared field width
     * @return {@code true} when the field holds nothing the edit can work with
     */
    private static boolean notSuppliedFixedField(final String value, final int width) {
        if (value == null || value.isBlank()) {
            return true;
        }
        for (int position = 0; position < width; position++) {
            if (fixedFieldCharAt(value, position) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code IS NOT NUMERIC} test of lines 740 and 784, inverted.
     *
     * <p>The class test reads the whole declared width, so a value shorter than the field fails: the move
     * that put it there left spaces in the remaining positions, and a space is not a digit. That is the
     * behaviour an operator sees when they key eight digits into an eleven-character filter.
     *
     * @param value the transmitted value; may be {@code null}
     * @param width the declared field width
     * @return {@code true} when every position of the field holds a digit
     */
    private static boolean numericFixedField(final String value, final int width) {
        if (value == null) {
            // Unreachable through the four call sites, each of which tests the not-supplied condition
            // first and an absent value satisfies that. Kept because the method is a general fixed-field
            // primitive and a class test on low values is false, not an error.
            return false;
        }
        for (int position = 0; position < width; position++) {
            final char character = fixedFieldCharAt(value, position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The range tests of lines 898 and 934, reading the numeric redefinition of an alphanumeric field.
     *
     * <p>A value that is not numeric across the declared width cannot satisfy a range declared over the
     * numeric redefinition, so the class test is applied first and the range only to a numeric field. The
     * value is accumulated digit by digit rather than parsed, so no locale or radix assumption enters.
     *
     * @param value the transmitted value; may be {@code null}
     * @param width the declared field width
     * @param minimum the lowest accepted value, inclusive
     * @param maximum the highest accepted value, inclusive
     * @return {@code true} when the field is numeric and its value falls inside the declared range
     */
    private static boolean withinFixedFieldRange(final String value, final int width,
            final int minimum, final int maximum) {
        if (!numericFixedField(value, width)) {
            return false;
        }
        int accumulated = 0;
        for (int position = 0; position < width; position++) {
            accumulated = (accumulated * 10) + (fixedFieldCharAt(value, position) - '0');
        }
        return accumulated >= minimum && accumulated <= maximum;
    }

    // ==============================================================================================
    // 2000-DECIDE-ACTION, line 948
    // ==============================================================================================

    /**
     * The action decision at line 948.
     *
     * <p>An {@code EVALUATE TRUE} of eight clauses at lines 949 to 1027. The subject is the literal true
     * value and every clause is an independent boolean, so this is a cascade rather than a selection on a
     * value, and the cascade order is reproduced exactly: the evaluation stops at the first clause that
     * holds, and several of these clauses overlap. The two confirmation clauses at lines 988 and 1006 are
     * the clearest case - they test the same state and differ only in also requiring the save key, so
     * swapping them would make the save unreachable.
     *
     * <p>Clause by clause: nothing fetched, or the cancel key, re-fetches the card when both filters are
     * valid, at lines 954 to 966; a card on display with no error and a change promotes to
     * awaiting-confirmation at lines 971 to 977; a failed edit does nothing at lines 982 to 983;
     * awaiting-confirmation plus the save key runs the write path and maps its outcome at lines 988 to
     * 1001; awaiting-confirmation without the save key does nothing at lines 1006 to 1007; a completed
     * write returns to the display state and clears the keys when there is no caller to return to, at
     * lines 1011 to 1018; and anything else abends at lines 1019 to 1026.
     *
     * @param state the turn's working storage
     */
    private void decideAction(final TurnState state) {
        // Clauses one and two, lines 954 and 958, sharing one body at lines 959 to 966.
        if (state.changeAction.detailsNotFetched() || state.keyAction == KeyAction.PFK12) {
            if (state.accountFilterFlag.valid() && state.cardFilterFlag.valid()) {
                readData(state);
                if (state.foundCardsForAccount()) {
                    state.changeAction = ChangeAction.SHOW_DETAILS;
                }
            }
            decideActionExit();
            return;
        }

        // Clause three, lines 971 to 977. Its BODY is unreachable, in the legacy as here, and the clause
        // is reproduced with it. The display state only survives the edit driver when that driver took its
        // early exit at lines 685 to 693, and with the display state the condition it exits on is the
        // no-change one - which is precisely what this body tests for the absence of. A submission that
        // did change something has already been rewritten to the not-OK or the awaiting-confirmation state
        // at lines 696 and 713, so it reaches a later clause instead.
        if (state.changeAction.showDetails()) {
            if (!state.inputState.inputError() && !state.noChangesDetected()) {
                state.changeAction = ChangeAction.CHANGES_OK_NOT_CONFIRMED;
            }
            decideActionExit();
            return;
        }

        // Clause four, lines 982 to 983: CONTINUE.
        if (state.changeAction.changesNotOk()) {
            decideActionExit();
            return;
        }

        // Clause five, lines 988 to 1001.
        if (state.changeAction.changesOkNotConfirmed() && state.keyAction == KeyAction.PFK05) {
            writeProcessing(state);
            mapWriteOutcomeToState(state);
            decideActionExit();
            return;
        }

        // Clause six, lines 1006 to 1007: CONTINUE.
        if (state.changeAction.changesOkNotConfirmed()) {
            decideActionExit();
            return;
        }

        // Clause seven, lines 1011 to 1018. UNREACHABLE, and unreachable in the legacy too: arm four of
        // the dispatch at lines 517 to 518 names the completed and failed states and takes the turn before
        // the catch-all arm that performs this paragraph ever runs, and nothing between the two can set
        // the completed state. The redundancy is the source's own and is reproduced rather than removed.
        if (state.changeAction.changesOkayedAndDone()) {
            state.changeAction = ChangeAction.SHOW_DETAILS;
            if (isBlank(state.navigationContext.fromTransactionId())) {
                state.navigationContext = withClearedBusinessKeys(state.navigationContext);
            }
            decideActionExit();
            return;
        }

        // WHEN OTHER, lines 1019 to 1026: the legacy's defensive handler for a corrupt state byte.
        //
        // UNREACHABLE IN THIS TRANSLATION, and reachable in the legacy. There the state is one byte of a
        // client-echoed communication area, so any of the two hundred and fifty-six values could arrive
        // and six of the seven declared ones would fall through to here. In the translation the echoed
        // state is a typed enum, an absent one is normalised to the not-fetched state at the top of the
        // entry point, and the six clauses above together with arm four of the dispatch at lines 517 to
        // 518 - whose condition name spans TWO state values, line 288 - account for all seven constants.
        // So no value can arrive here. That is a strictly safer position than the legacy's, not a gap.
        //
        // The arm and the paragraph it performs are reproduced rather than removed: the traceability
        // matrix maps every paragraph of this member to a named method, and deleting the abend handler
        // because the type system made it redundant would erase the record that the legacy had one.
        abendRoutine(state, ABEND_CODE_UNEXPECTED_DATA, ABEND_REASON_UNEXPECTED_DATA);
    }

    /**
     * The inner evaluation of clause five, lines 992 to 1001.
     *
     * <p>Four clauses testing the summary message the write path left, in declared order, mapping each to
     * a screen state. The three named conditions are mutually exclusive because they are condition names
     * over one field, so the order is safe - but it is reproduced anyway, because the catch-all is what
     * makes a successful write successful and it must stay last.
     *
     * <p>The concurrent-change clause at lines 997 to 998 returns the screen to the display state rather
     * than to a failed state: the operator is shown the refreshed record and may resubmit. That is why the
     * conflict is recoverable, and why it must never reach the abend path.
     *
     * @param state the turn's working storage
     */
    private static void mapWriteOutcomeToState(final TurnState state) {
        if (state.couldNotLockForUpdate()) {
            state.changeAction = ChangeAction.CHANGES_OKAYED_LOCK_ERROR;
            return;
        }
        if (state.lockedButUpdateFailed()) {
            state.changeAction = ChangeAction.CHANGES_OKAYED_BUT_FAILED;
            return;
        }
        if (state.dataWasChangedBeforeUpdate()) {
            state.changeAction = ChangeAction.SHOW_DETAILS;
            return;
        }
        state.changeAction = ChangeAction.CHANGES_OKAYED_AND_DONE;
    }

    /**
     * The action-decision exit at line 1029. A bare {@code EXIT} at line 1030.
     */
    private static void decideActionExit() {
        // EXIT, line 1030.
    }

    // ==============================================================================================
    // 3000-SEND-MAP, line 1035
    // ==============================================================================================

    /**
     * The send driver at line 1035.
     *
     * <p>Runs the five presentation paragraphs in declared order at lines 1036 to 1045: initialise the
     * header, populate the fields, choose the informational message, apply the attributes, send. The order
     * matters in one place - the attribute paragraph reads the informational message at lines 1309 to 1313
     * and again at lines 1315 to 1317, so it has to run after the paragraph that sets it.
     *
     * @param state the turn's working storage
     */
    private void sendMap(final TurnState state) {
        screenInit(state);
        setupScreenVars(state);
        setupInfoMsg(state);
        setupScreenAttrs(state);
        sendScreen(state);
        sendMapExit();
    }

    /**
     * The send-driver exit at line 1048. A bare {@code EXIT} at line 1049.
     */
    private static void sendMapExit() {
        // EXIT, line 1049.
    }

    // ==============================================================================================
    // 3100-SCREEN-INIT, line 1052
    // ==============================================================================================

    /**
     * The header paragraph at line 1052.
     *
     * <p>Clears the outbound map at line 1053, then fills the header: the two shared titles at lines 1057
     * to 1058, this program's transaction and member names at lines 1059 to 1060, and the formatted date
     * and time at lines 1064 to 1074.
     *
     * <p>The current date is read <em>twice</em>, at lines 1055 and 1062, into the same work area. The
     * first read is redundant - nothing consumes it before the second overwrites it. Reproduced as a single
     * read, because two reads of the same clock at the same instant are indistinguishable and a second read
     * would only widen the window in which the two could disagree. Recorded as a source anomaly.
     *
     * @param state the turn's working storage
     */
    private void screenInit(final TurnState state) {
        final LocalDateTime now = LocalDateTime.now(this.clock);
        state.header = new ScreenHeader(
                this.messageCatalogService.screenTitle01(),
                this.messageCatalogService.screenTitle02(),
                LEGACY_TRANSACTION_ID,
                LEGACY_PROGRAM_NAME,
                HEADER_DATE_FORMAT.format(now),
                HEADER_TIME_FORMAT.format(now));
        screenInitExit();
    }

    /**
     * The header exit at line 1078. A bare {@code EXIT} at line 1079.
     */
    private static void screenInitExit() {
        // EXIT, line 1079.
    }

    // ==============================================================================================
    // 3200-SETUP-SCREEN-VARS, line 1082
    // ==============================================================================================

    /**
     * The field-population paragraph at line 1082.
     *
     * <p>On a first entry it populates nothing at all - lines 1084 to 1085 skip the whole body - which is
     * how the screen comes up empty. Otherwise it writes the two filter fields at lines 1087 to 1097,
     * blanking each when its numeric view is zero, and then chooses which card-data values to show from a
     * four-clause evaluation at lines 1099 to 1130.
     *
     * <p>The evaluation is a cascade on the screen state, in declared order: nothing fetched blanks every
     * card field; the display state shows the fetched values; the changes-made state shows the submitted
     * values; the catch-all shows the fetched values.
     *
     * <p><strong>The expiry day is the deliberate exception on the changes-made arm.</strong> Line 1122
     * carries the commented-out assignment of the submitted day and line 1123 assigns the <em>fetched</em>
     * day instead, under a comment at lines 1118 to 1121 saying that fields the operator is not allowed to
     * change keep their fetched values. Both lines are reproduced as they stand: the submitted day is
     * discarded and the fetched day is shown.
     *
     * <p>Line 1101 to 1102 assigns the name field twice in one statement, the same target listed twice.
     * Harmless and recorded as a source anomaly.
     *
     * @param state the turn's working storage
     */
    private void setupScreenVars(final TurnState state) {
        if (state.navigationContext.firstEntry()) {
            // Lines 1084 to 1085: CONTINUE. The screen keeps the cleared map of line 1053.
            state.screen = new ScreenFields(null, null, null, null, null, null, null,
                    state.infoMessage, state.returnMessage, state.fieldProtection,
                    state.confirmationKeysHighlighted);
            setupScreenVarsExit();
            return;
        }

        // Lines 1087 to 1097: a zero numeric view blanks the field.
        final String accountField = zeroValuedFixedField(state.workAreaAccountId, ACCOUNT_ID_WIDTH)
                ? null
                : state.workAreaAccountId;
        final String cardField = zeroValuedFixedField(state.workAreaCardNumber, CARD_NUMBER_WIDTH)
                ? null
                : state.workAreaCardNumber;

        final String embossedName;
        final String activeStatus;
        final String expiryMonth;
        final String expiryYear;
        final String expiryDay;

        // Lines 1099 to 1130, in declared clause order.
        if (state.changeAction.detailsNotFetched()) {
            embossedName = null;
            activeStatus = null;
            expiryMonth = null;
            expiryYear = null;
            expiryDay = null;
        } else if (state.changeAction.showDetails()) {
            embossedName = state.carriedImage.embossedName();
            activeStatus = state.carriedImage.activeStatus();
            expiryMonth = state.carriedImage.expiryMonth();
            expiryYear = state.carriedImage.expiryYear();
            expiryDay = state.carriedImage.expiryDay();
        } else if (state.changeAction.changesMade()) {
            embossedName = state.newEmbossedName;
            activeStatus = state.newActiveStatus;
            expiryMonth = state.newExpiryMonth;
            expiryYear = state.newExpiryYear;
            // MOVE CCUP-OLD-EXPDAY TO EXPDAYO, line 1123, replacing the commented-out line 1122.
            expiryDay = state.carriedImage.expiryDay();
        } else {
            // WHEN OTHER, lines 1126 to 1130. STRUCTURALLY REQUIRED AND UNREACHABLE. The three clauses
            // above partition all seven states the change-action enum declares, so no value can arrive
            // here - but the five locals are final and Java demands they be definitely assigned, so the
            // clause cannot be deleted. It is NOT merged into the display clause whose body it duplicates,
            // even though the two are provably interchangeable, because the source declares them as
            // separate clauses in this order and clause order is reproduced rather than optimised.
            embossedName = state.carriedImage.embossedName();
            activeStatus = state.carriedImage.activeStatus();
            expiryMonth = state.carriedImage.expiryMonth();
            expiryYear = state.carriedImage.expiryYear();
            expiryDay = state.carriedImage.expiryDay();
        }

        state.screen = new ScreenFields(accountField, cardField, embossedName, activeStatus,
                expiryMonth, expiryYear, expiryDay, state.infoMessage, state.returnMessage,
                state.fieldProtection, state.confirmationKeysHighlighted);
        setupScreenVarsExit();
    }

    /**
     * The field-population exit at line 1135. A bare {@code EXIT} at line 1136.
     */
    private static void setupScreenVarsExit() {
        // EXIT, line 1136.
    }

    /**
     * The {@code = 0} tests of lines 1087 and 1093, which read the numeric redefinition of the filter
     * field and blank the outbound field when it holds zero.
     *
     * @param value the value; may be {@code null}
     * @param width the declared field width
     * @return {@code true} when the field is absent, blank, or numerically zero across its width
     */
    private static boolean zeroValuedFixedField(final String value, final int width) {
        return notSuppliedFixedField(value, width);
    }

    // ==============================================================================================
    // 3250-SETUP-INFOMSG, line 1138
    // ==============================================================================================

    /**
     * The informational-message paragraph at line 1138.
     *
     * <p>A nine-clause cascade at lines 1140 to 1159, in declared order, then the two message fields are
     * moved into the outbound map at lines 1161 and 1163.
     *
     * <p>Clause order carries real weight here. The first clause tests the entry gate and the second tests
     * the not-fetched state; both resolve to the same prompt, so a turn satisfying both is unaffected - but
     * the third clause, the display state, is reachable only because the first two do not hold, and the
     * final clause is a catch-all in the shape of a condition name over an empty message field.
     *
     * @param state the turn's working storage
     */
    private void setupInfoMsg(final TurnState state) {
        if (state.navigationContext.firstEntry()) {
            state.infoMessage = INFO_PROMPT_FOR_SEARCH_KEYS;
        } else if (state.changeAction.detailsNotFetched()) {
            state.infoMessage = INFO_PROMPT_FOR_SEARCH_KEYS;
        } else if (state.changeAction.showDetails()) {
            state.infoMessage = INFO_DETAILS_SHOWN;
        } else if (state.changeAction.changesNotOk()) {
            state.infoMessage = INFO_PROMPT_FOR_CHANGES;
        } else if (state.changeAction.changesOkNotConfirmed()) {
            state.infoMessage = INFO_PROMPT_FOR_CONFIRMATION;
        } else if (state.changeAction.changesOkayedAndDone()) {
            state.infoMessage = INFO_UPDATE_COMMITTED;
        } else if (state.changeAction.changesOkayedLockError()) {
            state.infoMessage = INFO_UPDATE_FAILED;
        } else if (state.changeAction.changesOkayedButFailed()) {
            state.infoMessage = INFO_UPDATE_FAILED;
        } else if (state.noInfoMessage()) {
            // Lines 1157 to 1159. UNREACHABLE: the seven preceding state clauses name all seven values
            // the change-action enum declares, so this condition name over an empty message field can
            // never be tested. Reproduced because the source declares it, and because a state byte in a
            // one-character field genuinely could have arrived here on the mainframe.
            state.infoMessage = INFO_PROMPT_FOR_SEARCH_KEYS;
        }

        // MOVE WS-INFO-MSG TO INFOMSGO and WS-RETURN-MSG TO ERRMSGO, lines 1161 and 1163.
        state.screen = new ScreenFields(state.screen.accountId(), state.screen.cardNumber(),
                state.screen.embossedName(), state.screen.activeStatus(),
                state.screen.expiryMonth(), state.screen.expiryYear(), state.screen.expiryDay(),
                state.infoMessage, state.returnMessage, state.fieldProtection,
                state.confirmationKeysHighlighted);
        setupInfoMsgExit();
    }

    /**
     * The informational-message exit at line 1165. A bare {@code EXIT} at line 1166.
     */
    private static void setupInfoMsgExit() {
        // EXIT, line 1166.
    }

    // ==============================================================================================
    // 3300-SETUP-SCREEN-ATTRS, line 1168, exit at line 1319
    // ==============================================================================================

    /**
     * The attribute paragraph at line 1168 - the longest paragraph in the member, and the one that carries
     * the whole error-decoration contract.
     *
     * <p>Three blocks in order: which field group the terminal leaves open, at lines 1172 to 1208; where
     * the cursor goes, at lines 1211 to 1235; and which fields are coloured and marked, at lines 1238 to
     * 1317.
     *
     * <p><strong>Two decoration states, not one boolean.</strong> Each of the six validated fields is
     * handled by <em>two</em> consecutive tests. The first colours the field when its flag is not-OK; the
     * second, for the blank flag, colours it <em>and</em> writes the marker character into it. Those are
     * different operator experiences and the response contract exposes them separately, as INVALID and
     * MISSING. Collapsing them would tell an operator to correct a value they never entered, or to supply
     * one they already did.
     *
     * <p><strong>Decoration is gated, and the two gates differ.</strong> The two filter fields are
     * decorated only on a re-entry, at lines 1248 and 1258. The four card-data fields are decorated only
     * while the screen state is changes-not-OK, at lines 1264, 1269, 1275, 1280, 1288, 1293, 1299 and
     * 1304. So a first entry carries no field detail at all even when every field is empty, and a turn
     * whose changes were accepted carries none either. That is the re-enter gate the migration requirement
     * names, expressed structurally.
     *
     * <p>Note the asymmetry the source has and this reproduces: the <em>not-OK</em> colour test for the
     * two filter fields at lines 1243 and 1253 is <em>not</em> gated on re-entry, while the blank test at
     * lines 1247 and 1257 is. A filter that was supplied and failed is therefore coloured on a first
     * entry too - which is unreachable in practice, because a first entry runs no filter edit.
     *
     * <p>Marking order is the source's order, because it is the order an operator saw the fields marked:
     * account filter, card filter, name, status, expiry month, expiry year.
     *
     * <p>The expiry-day attribute is set unconditionally to the darkened value at line 1285, which is how
     * that field is present but never readable. It is not a validated field and is never decorated.
     *
     * @param state the turn's working storage
     */
    private void setupScreenAttrs(final TurnState state) {
        // Block one, lines 1172 to 1208: four clauses resolving to three outcomes, in declared order.
        if (state.changeAction.detailsNotFetched()) {
            state.fieldProtection = FieldProtection.SEARCH_KEYS_OPEN;
        } else if (state.changeAction.showDetails() || state.changeAction.changesNotOk()) {
            state.fieldProtection = FieldProtection.CARD_DATA_OPEN;
        } else if (state.changeAction.changesOkNotConfirmed()
                || state.changeAction.changesOkayedAndDone()) {
            state.fieldProtection = FieldProtection.ALL_PROTECTED;
        } else {
            // WHEN OTHER, lines 1200 to 1207: identical to the first clause.
            state.fieldProtection = FieldProtection.SEARCH_KEYS_OPEN;
        }

        // Block two, lines 1211 to 1235: nine clauses in declared order, first match wins.
        state.focusField = resolveFocusField(state);

        // Block three, lines 1238 to 1317, in the source's own field order.
        if (state.reEntry && state.accountFilterFlag.decorated()) {
            markField(state, FIELD_ACCOUNT_ID, BMS_ACCOUNT_ID, state.accountFilterFlag,
                    state.accountFilterFlag.blank()
                            ? MSG_ACCOUNT_NOT_PROVIDED
                            : MSG_ACCOUNT_FILTER_ELEVEN_DIGITS);
        }
        if (state.reEntry && state.cardFilterFlag.decorated()) {
            markField(state, FIELD_CARD_NUMBER, BMS_CARD_NUMBER, state.cardFilterFlag,
                    state.cardFilterFlag.blank()
                            ? MSG_CARD_NOT_PROVIDED
                            : MSG_CARD_FILTER_SIXTEEN_DIGITS);
        }
        if (state.changeAction.changesNotOk() && state.cardNameFlag.decorated()) {
            markField(state, FIELD_EMBOSSED_NAME, BMS_EMBOSSED_NAME, state.cardNameFlag,
                    state.cardNameFlag.blank() ? MSG_NAME_NOT_PROVIDED : MSG_NAME_MUST_BE_ALPHA);
        }
        if (state.changeAction.changesNotOk() && state.cardStatusFlag.decorated()) {
            markField(state, FIELD_ACTIVE_STATUS, BMS_ACTIVE_STATUS, state.cardStatusFlag,
                    MSG_STATUS_MUST_BE_YES_NO);
        }
        if (state.changeAction.changesNotOk() && state.expiryMonthFlag.decorated()) {
            markField(state, FIELD_EXPIRY_MONTH, BMS_EXPIRY_MONTH, state.expiryMonthFlag,
                    MSG_EXPIRY_MONTH_NOT_VALID);
        }
        if (state.changeAction.changesNotOk() && state.expiryYearFlag.decorated()) {
            markField(state, FIELD_EXPIRY_YEAR, BMS_EXPIRY_YEAR, state.expiryYearFlag,
                    MSG_EXPIRY_YEAR_NOT_VALID);
        }

        // Lines 1315 to 1317: the function-key line is brightened only while the confirmation prompt is
        // showing, which the source expresses as a condition name over the informational message.
        state.confirmationKeysHighlighted = INFO_PROMPT_FOR_CONFIRMATION.equals(state.infoMessage);

        state.screen = applyBlankFieldMarkers(state);
        setupScreenAttrsExit();
    }

    /**
     * The attribute exit at line 1319. A bare {@code EXIT} at line 1320.
     */
    private static void setupScreenAttrsExit() {
        // EXIT, line 1320.
    }

    /**
     * The cursor-positioning evaluation of lines 1211 to 1235.
     *
     * <p>Nine clauses, first match wins. The first clause is the only one that is not a flag test: it
     * positions on the name field when a card has just been found or when nothing changed, which is the
     * field an operator would edit next. The remaining clauses walk the six validation flags in the same
     * order the edits run, pairing each flag's two failing states, and the catch-all falls back to the
     * account filter.
     *
     * <p>Because every flag begins blank, a turn that ran no edits at all reaches the third clause and
     * positions on the account filter - by way of the blank account flag, not by way of the catch-all.
     *
     * @param state the turn's working storage
     * @return the property name of the field the cursor is positioned on
     */
    private static String resolveFocusField(final TurnState state) {
        if (state.foundCardsForAccount() || state.noChangesDetected()) {
            return FIELD_EMBOSSED_NAME;
        }
        if (state.accountFilterFlag.decorated()) {
            return FIELD_ACCOUNT_ID;
        }
        if (state.cardFilterFlag.decorated()) {
            return FIELD_CARD_NUMBER;
        }
        if (state.cardNameFlag.decorated()) {
            return FIELD_EMBOSSED_NAME;
        }
        if (state.cardStatusFlag.decorated()) {
            return FIELD_ACTIVE_STATUS;
        }
        if (state.expiryMonthFlag.decorated()) {
            return FIELD_EXPIRY_MONTH;
        }
        if (state.expiryYearFlag.decorated()) {
            return FIELD_EXPIRY_YEAR;
        }
        // WHEN OTHER, lines 1233 to 1234.
        return FIELD_ACCOUNT_ID;
    }

    /**
     * Records one marked field, in both the decoration and the per-field detail.
     *
     * <p>The decoration is the direct analogue of the legacy attribute assignment; the per-field detail is
     * what the response carries. Both are appended in call order, which is the order the source marks the
     * fields.
     *
     * @param state the turn's working storage
     * @param field the request and response property name
     * @param bmsFieldId the legacy screen field name
     * @param flag the field's validation flag, which must be a failing state
     * @param message the text the field's own edit raises for this failure
     */
    private static void markField(final TurnState state, final String field,
            final String bmsFieldId, final EditFlag flag, final String message) {
        state.decoration = state.decoration.mark(field, bmsFieldId, flag.decorationFlag());
        state.fieldErrors.add(
                new ValidationException.FieldError(field, bmsFieldId, flag.fieldState(), message));
    }

    /**
     * Writes the marker character into every field whose flag is blank, per lines 1249, 1259, 1270, 1281,
     * 1294 and 1305, under the same two gates the colouring uses.
     *
     * <p>The marker replaces the field's value, which is what the legacy move does. On the next turn the
     * receive paragraph normalises that character straight back to low values at lines 589 to 634, which is
     * how a marked field comes back empty rather than holding a marker.
     *
     * @param state the turn's working storage
     * @return the outbound fields with the markers applied
     */
    private static ScreenFields applyBlankFieldMarkers(final TurnState state) {
        final ScreenFields current = state.screen;
        final boolean cardDataGate = state.changeAction.changesNotOk();
        return new ScreenFields(
                (state.reEntry && state.accountFilterFlag.blank())
                        ? BLANK_FIELD_MARKER : current.accountId(),
                (state.reEntry && state.cardFilterFlag.blank())
                        ? BLANK_FIELD_MARKER : current.cardNumber(),
                (cardDataGate && state.cardNameFlag.blank())
                        ? BLANK_FIELD_MARKER : current.embossedName(),
                (cardDataGate && state.cardStatusFlag.blank())
                        ? BLANK_FIELD_MARKER : current.activeStatus(),
                (cardDataGate && state.expiryMonthFlag.blank())
                        ? BLANK_FIELD_MARKER : current.expiryMonth(),
                (cardDataGate && state.expiryYearFlag.blank())
                        ? BLANK_FIELD_MARKER : current.expiryYear(),
                current.expiryDay(),
                state.infoMessage,
                state.returnMessage,
                state.fieldProtection,
                state.confirmationKeysHighlighted);
    }

    // ==============================================================================================
    // 3400-SEND-SCREEN, line 1324
    // ==============================================================================================

    /**
     * The send paragraph at line 1324.
     *
     * <p>Records this program's mapset and map as the next ones at lines 1326 to 1327 and issues the send
     * at lines 1329 to 1336. In the translation the send <em>is</em> the returned value, so this paragraph
     * settles the two declarative name fields and nothing else; there is no server-side forwarding and no
     * terminal write.
     *
     * @param state the turn's working storage
     */
    private void sendScreen(final TurnState state) {
        state.route = NavigationService.Route.CARD_UPDATE;
        sendScreenExit();
    }

    /**
     * The send exit at line 1338. A bare {@code EXIT} at line 1339.
     */
    private static void sendScreenExit() {
        // EXIT, line 1339.
    }

    // ==============================================================================================
    // 9000-READ-DATA, line 1343
    // ==============================================================================================

    /**
     * The fetch paragraph at line 1343.
     *
     * <p>Clears the carried image at line 1345, seeds it with the two filter keys at lines 1346 to 1347,
     * fetches the record at lines 1349 to 1350, and - only when a record was found - fills the rest of the
     * image at lines 1354 to 1367.
     *
     * <p><strong>Fold site one, and the ordering that makes it matter.</strong> Line <b>1357</b> converts
     * the record's embossed name to upper case <em>in the record field itself</em>. Line <b>1360</b> then
     * copies that field into the carried image. The image therefore holds an <em>already folded</em> value,
     * and every later comparison against it compares folded against folded - which is exactly why a
     * case-only edit is not a change. Folding a copy for display, or folding after the copy, would leave an
     * unfolded value in the image and flip the outcome.
     *
     * <p>The fold is the module's 26-character ASCII table primitive. The locale-aware library method is
     * forbidden in both its forms: it is Unicode-aware, it can transform characters the table leaves alone,
     * and it can change the length of a value written back into a fixed fifty-byte field.
     *
     * <p><strong>"In place" means the working copy, and the distinction is load bearing.</strong> The
     * record the source folds is {@code CARD-RECORD}, an {@code 01} level in working storage that the read
     * at lines 1382 to 1390 fills with {@code INTO(CARD-RECORD)}. The rewrite at lines 1477 to 1483 writes
     * a <em>different</em> structure, {@code CARD-UPDATE-RECORD}, built separately at lines 1461 to 1475.
     * The fold therefore never reaches the file. Its Java counterpart is this turn's own state, and the
     * persisted row is deliberately <em>not</em> mutated: touching a managed entity would flush the fold to
     * the database on commit, storing a value the legacy never stores. Decision-log entry.
     *
     * <p>The three date components are taken by splitting on the separator the write path itself assembles
     * the field with at lines 1467 to 1474, rather than by the offset slices the source uses at lines 1361
     * to 1366. For the ten-character layout those produce identical results, and the separator is the
     * layout's own delimiter rather than a positional assumption. Offset knowledge belongs to the record
     * mappers, not here.
     *
     * @param state the turn's working storage
     */
    private void readData(final TurnState state) {
        // INITIALIZE CCUP-OLD-DETAILS and seed the two keys, lines 1345 to 1347.
        state.carriedImage = new CarriedCardImage(state.workAreaAccountId, state.workAreaCardNumber,
                null, null, null, null, null, null);

        getCardByAcctCard(state);

        if (state.foundCardsForAccount() && state.cardRecord != null) {
            final Card record = state.cardRecord;

            // INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER, lines 1356 to 1358. In
            // place, on the working-storage copy - which is this turn's own state, NOT the stored row.
            state.foldedRecordEmbossedName =
                    CobolStringUtils.asciiUpperFold(record.getCardEmbossedName());

            final String[] dateParts = splitExpirationDate(record.getCardExpirationDate());

            // Lines 1354 and 1360 to 1367. The name copied here is the folded one.
            state.carriedImage = new CarriedCardImage(
                    state.workAreaAccountId,
                    state.workAreaCardNumber,
                    record.getCardCvvCd(),
                    state.foldedRecordEmbossedName,
                    dateParts[0],
                    dateParts[1],
                    dateParts[2],
                    record.getCardActiveStatus());
        }

        readDataExit();
    }

    /**
     * The fetch exit at line 1372. A bare {@code EXIT} at line 1373.
     */
    private static void readDataExit() {
        // EXIT, line 1373.
    }

    /**
     * Splits the ten-character expiration date into year, month and day.
     *
     * <p>The source takes three offset slices at lines 1361 to 1366 of a field the write path assembles at
     * lines 1467 to 1474 as year, separator, month, separator, day. Splitting on that separator reads the
     * same three components without importing offset knowledge, which belongs to the record mappers.
     *
     * <p>A value that does not carry the three components yields three absent components, which is the
     * state the carried image holds before a record has been fetched. The source would have sliced whatever
     * bytes were there; producing absent components instead keeps a malformed stored value from being
     * silently compared as though it were well formed.
     *
     * @param expirationDate the stored expiration date, possibly {@code null}
     * @return always a three-element array of year, month and day, any of which may be {@code null}
     */
    private static String[] splitExpirationDate(final String expirationDate) {
        if (expirationDate == null) {
            return new String[] {null, null, null};
        }
        final String[] parts = expirationDate.split(EXPIRATION_DATE_SEPARATOR, -1);
        if (parts.length != 3) {
            return new String[] {null, null, null};
        }
        return new String[] {parts[0], parts[1], parts[2]};
    }

    // ==============================================================================================
    // 9100-GETCARD-BYACCTCARD, line 1376
    // ==============================================================================================

    /**
     * The card read at line 1376.
     *
     * <p>Moves the card number into the record-identification field at line 1380 and reads the base file
     * named at lines 251 to 252 at lines 1382 to 1390, then dispatches on the response at lines 1392 to
     * 1412: found sets the informational message that every caller tests as its success flag; not-found
     * raises the no-card message and marks <em>both</em> filter fields not-OK; anything else raises the
     * assembled file-error text.
     *
     * <p><strong>The account path.</strong> The record-identification move for the account key is commented
     * out at line 1379, so the legacy always keys on the card number - and when the card number is absent
     * it reads with a blank key and can only get not-found. The non-unique account path it declared at line
     * <b>254</b> for exactly this purpose was left unwired. This translation wires it: when no card number
     * is available, the account is resolved through the repository's non-unique account finder, whose name
     * carries the take-the-first-row semantic the alternate index requires. An absent result is the
     * not-found outcome, unchanged. The state is genuinely reachable - the arrival arm at lines 482 to 491
     * takes both keys from the echoed state without editing them - and the divergence is a decision-log
     * entry rather than a silent improvement.
     *
     * <p><strong>An inverted gate, reproduced.</strong> On the catch-all arm the
     * {@code IF WS-RETURN-MSG-OFF} test at lines 1404 to 1406 wraps a <em>flag</em> assignment, while the
     * message itself is moved <em>unconditionally</em> at line 1411. Every other message site in this
     * member is the other way round. So this one arm can overwrite a message another edit raised first,
     * and it only marks the account filter when no message had been raised. Both behaviours are
     * reproduced and both are recorded as source anomalies.
     *
     * @param state the turn's working storage
     */
    private void getCardByAcctCard(final TurnState state) {
        // MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM, line 1380.
        state.recordIdentificationCardNumber = state.workAreaCardNumber;

        final Optional<Card> found = readCardRecord(state, OPERATION_READ);

        if (found.isPresent()) {
            // WHEN DFHRESP(NORMAL), lines 1393 to 1394.
            state.cardRecord = found.get();
            state.infoMessage = INFO_DETAILS_SHOWN;
            getCardByAcctCardExit();
            return;
        }

        if (state.rawFileStatus == null) {
            // WHEN DFHRESP(NOTFND), lines 1395 to 1401.
            state.rawFileStatus = RecordNotFoundException.STATUS_RECORD_NOT_FOUND;
            state.inputState = InputState.ERROR;
            state.accountFilterFlag = EditFlag.NOT_OK;
            state.cardFilterFlag = EditFlag.NOT_OK;
            state.raiseSummaryMessage(MSG_NO_CARD_FOR_SEARCH);
            getCardByAcctCardExit();
            return;
        }

        // WHEN OTHER, lines 1402 to 1411.
        state.inputState = InputState.ERROR;
        if (state.returnMessageOff()) {
            // The gate wraps the flag, not the message. Reproduced exactly.
            state.accountFilterFlag = EditFlag.NOT_OK;
        }
        state.errorOperationName = OPERATION_READ;
        state.errorResourceName = resolveReadResourceName(state);
        state.errorResponseCode = state.rawFileStatus;
        state.errorReasonCode = "";
        // MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG, line 1411: unconditional.
        state.returnMessage = assembleFileErrorMessage(state);
        getCardByAcctCardExit();
    }

    /**
     * The card-file exit at line 1415. A bare {@code EXIT} at line 1416.
     */
    private static void getCardByAcctCardExit() {
        // EXIT, line 1416.
    }

    /**
     * Performs the read the paragraph above dispatches on, choosing between the two access paths.
     *
     * <p>Keyed access by card number is the legacy's live path and uses the inherited primary-key finder.
     * The account path is the declared non-unique alternate index; its finder returns at most one row
     * because the index admits duplicates and the screen wants the first of them.
     *
     * <p>Records the raw file status and the operation on failure, so the diagnostic and any subsequent
     * abend can name both. The two read sites are distinguishable there even though the source leaves the
     * operation field at whatever the previous read set.
     *
     * @param state the turn's working storage
     * @param operationName the operation to name in a diagnostic: the fetch at line 1382, or the
     *                      read-for-update at line 1427
     * @return the row that was read, or an empty result for the not-found and error outcomes
     */
    private Optional<Card> readCardRecord(final TurnState state, final String operationName) {
        state.rawFileStatus = null;
        try {
            if (!isBlank(state.recordIdentificationCardNumber)) {
                return this.cardRepository.findById(state.recordIdentificationCardNumber.trim());
            }
            if (!isBlank(state.workAreaAccountId)) {
                return this.cardRepository
                        .findFirstByCardAcctIdOrderByCardNumAsc(state.workAreaAccountId.trim());
            }
            return Optional.empty();
        } catch (final DataAccessException failure) {
            // The catch-all arm at lines 1402 to 1411: a read that neither succeeded nor found nothing.
            state.rawFileStatus = RAW_STATUS_READ_FAILURE;
            state.errorOperationName = operationName;
            LOG.error("Card file read failed: operation={} resource={} fileStatus={}",
                    operationName, resolveReadResourceName(state).trim(), RAW_STATUS_READ_FAILURE,
                    failure);
            return Optional.empty();
        }
    }

    /**
     * Names the resource a read used, so a diagnostic can say which of the two it was.
     *
     * @param state the turn's working storage
     * @return the base file name for keyed access, or the account path name for the alternate index
     */
    private static String resolveReadResourceName(final TurnState state) {
        return isBlank(state.recordIdentificationCardNumber)
                ? LEGACY_CARD_ACCOUNT_PATH_NAME
                : LEGACY_CARD_FILE_NAME;
    }

    /**
     * Assembles {@code WS-FILE-ERROR-MESSAGE} of lines 133 to 152 as the destination field receives it.
     *
     * <p>The structure declares eight parts totalling eighty characters, but the destination at line 173
     * is seventy-five, so the trailing five-character filler at lines 151 to 152 never survives the move.
     * The seven parts assembled here total exactly the destination width, so the truncation is reproduced
     * by construction rather than by cutting a longer string.
     *
     * @param state the turn's working storage
     * @return the assembled text, exactly as wide as the destination field
     */
    private static String assembleFileErrorMessage(final TurnState state) {
        final StringBuilder assembled = new StringBuilder(RETURN_MESSAGE_WIDTH);
        assembled.append(FILE_ERROR_PREFIX)
                .append(fixedFieldText(state.errorOperationName, FILE_ERROR_OPNAME_WIDTH))
                .append(FILE_ERROR_ON)
                .append(fixedFieldText(state.errorResourceName, FILE_ERROR_FILE_WIDTH))
                .append(FILE_ERROR_RESP)
                .append(fixedFieldText(state.errorResponseCode, FILE_ERROR_RESP_WIDTH))
                .append(FILE_ERROR_RESP2)
                .append(fixedFieldText(state.errorReasonCode, FILE_ERROR_RESP_WIDTH));
        return assembled.toString();
    }

    /**
     * Renders a value as the fixed field it is moved into: left justified, space padded, bounded.
     *
     * @param value the value, treated as spaces when absent
     * @param width the declared field width
     * @return exactly {@code width} characters
     */
    private static String fixedFieldText(final String value, final int width) {
        final String source = (value == null) ? "" : value;
        final StringBuilder rendered = new StringBuilder(width);
        for (int position = 0; position < width; position++) {
            rendered.append(fixedFieldCharAt(source, position));
        }
        return rendered.toString();
    }

    // ==============================================================================================
    // 9200-WRITE-PROCESSING, line 1420, exit at line 1494
    // ==============================================================================================

    /**
     * The write path at line 1420, with its exit at line <b>1494</b>.
     *
     * <h3>The backward jump, and the loop it forms</h3>
     *
     * <p>This member owns one of the nine backward {@code GO TO} statements in the estate. Line
     * <b>1518</b>, inside the change-detection paragraph, jumps to this paragraph's exit at line
     * <b>1494</b> - a line that sits <em>behind</em> the jump in source order, bypassing the change
     * paragraph's own exit at line 1521. That is the shape of a loop and it is reproduced as an explicit
     * loop here: {@code while}, not recursion, not a framework retry, with <em>no</em> delay, <em>no</em>
     * backoff and <em>no</em> attempt count. It is control flow, not a resilience policy.
     *
     * <p>The loop is bounded by the source's own condition, and two independent facts bound it. First, the
     * landing paragraph is the terminating paragraph of the enclosing performed range opened at lines 990
     * to 991, so reaching it satisfies that range's return and control leaves the write path. Second, and
     * sufficient on its own, the change paragraph copies the locked record into the carried image at lines
     * 1512 to 1517 <em>before</em> it jumps, so a further pass could not detect a change a second time.
     * Neither fact is an invented limit; both are read off the source.
     *
     * <h3>The path itself</h3>
     *
     * <p>Moves the card number into the record-identification field at line 1425 and reads the record for
     * update at lines 1427 to 1436. A read that did not succeed raises the lock message and leaves at lines
     * 1441 to 1449 - the forward jump at line 1448. Otherwise the change check runs at lines 1453 to 1454
     * and its outcome is tested at lines 1455 to 1457. A record that still matches is then rewritten: the
     * update image is built at lines 1461 to 1475 and written at lines 1477 to 1483, and a write that did
     * not succeed raises the failure message at lines 1488 to 1492.
     *
     * <h3>Two things this does not do</h3>
     *
     * <p><strong>No pessimistic lock.</strong> There is no lock hint and no lock mode anywhere. The row
     * version the entity carries is checked when the update is flushed, and that check is caught here and
     * translated. A version conflict is recoverable and non-abending, and never reaches the abend service.
     *
     * <p><strong>The verification code is preserved, not rewritten.</strong> Line 1464 sources it from
     * {@code CCUP-NEW-CVV-CD}, a field declared at line 306 and <em>never written anywhere in the
     * member</em> - the receive paragraph clears the whole new-value group at line 586 and populates six
     * fields, none of them this one. A byte-faithful reproduction would therefore blank a stored sensitive
     * value on every successful update, which the program's own comparison at line 1503 contradicts by
     * requiring that value to be unchanged. The stored code is kept. That is a documented divergence from a
     * source defect rather than a silent correction, and it is a decision-log entry.
     *
     * @param state the turn's working storage
     * @throws OptimisticLockConflictException when the row version check fails on flush; the transaction
     *         must roll back at that point, so the outcome is raised rather than returned, carrying the
     *         legacy's own operator text
     */
    private void writeProcessing(final TurnState state) {
        boolean reEnterWritePath = true;
        while (reEnterWritePath) {
            reEnterWritePath = false;

            // MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM, line 1425.
            state.recordIdentificationCardNumber = state.workAreaCardNumber;

            // EXEC CICS READ ... UPDATE, lines 1427 to 1436.
            final Optional<Card> locked = readCardRecord(state, OPERATION_READ_UPDATE);

            // Lines 1441 to 1449.
            if (locked.isEmpty()) {
                state.inputState = InputState.ERROR;
                state.raiseSummaryMessage(MSG_COULD_NOT_LOCK_FOR_UPDATE);
                state.writeOutcome = WriteOutcome.LOCK_NOT_ACQUIRED;
                // GO TO 9200-WRITE-PROCESSING-EXIT, line 1448.
                writeProcessingExit();
                return;
            }

            final Card lockedRecord = locked.get();
            state.cardRecord = lockedRecord;

            // PERFORM 9300-CHECK-CHANGE-IN-REC THRU ...-EXIT, lines 1453 to 1454.
            final boolean jumpedToWriteExit = checkChangeInRec(state, lockedRecord);
            if (jumpedToWriteExit) {
                // GO TO 9200-WRITE-PROCESSING-EXIT, line 1518.
                state.writeOutcome = WriteOutcome.RECORD_CHANGED_BEFORE_UPDATE;
                reEnterWritePath = writePathReEntersAfterJump(state);
                continue;
            }

            // Lines 1455 to 1457, on the path where the change paragraph fell through its own exit. The
            // message condition is sticky, so a pass that had already reported a change leaves here.
            //
            // UNREACHABLE, and it is the source's own redundant guard rather than an oversight in this
            // translation: the only way the message can already be set is a previous pass of this loop,
            // and a previous pass refreshed the carried image before jumping, which ends the loop. The
            // guard is what would catch the jump re-entering, so it is reproduced exactly - removing it
            // would remove the evidence that the source bounded its own backward jump twice.
            if (state.dataWasChangedBeforeUpdate()) {
                state.writeOutcome = WriteOutcome.RECORD_CHANGED_BEFORE_UPDATE;
                writeProcessingExit();
                return;
            }

            rewriteCardRecord(state, lockedRecord);
            writeProcessingExit();
            return;
        }
    }

    /**
     * The write path's exit at line <b>1494</b>. A bare {@code EXIT} at line 1495.
     *
     * <p>Also the landing paragraph of the backward jump at line 1518, and the terminating paragraph of the
     * enclosing performed range - which together are why that jump ends the write path rather than
     * restarting it.
     */
    private static void writeProcessingExit() {
        // EXIT, line 1495.
    }

    /**
     * Whether control re-enters the write path after the backward jump at line 1518.
     *
     * <p>Re-entry would require the jump to have made no progress. It always makes progress: lines 1512 to
     * 1517 copy the locked record into the carried image before jumping, so the comparison a further pass
     * would run has already been made to agree. Reading the progress flag rather than counting attempts is
     * what keeps this a reproduction of the source's own bound instead of an invented retry limit.
     *
     * @param state the turn's working storage
     * @return {@code true} only if the jump left the carried image unrefreshed
     */
    private static boolean writePathReEntersAfterJump(final TurnState state) {
        final boolean refreshed = state.carriedImageRefreshed;
        state.carriedImageRefreshed = false;
        return !refreshed;
    }

    /**
     * Builds and writes the update image, lines 1461 to 1492.
     *
     * <p>The image is assembled at lines 1461 to 1475: the card number at line 1462, the account
     * identifier at line 1463, the verification code at lines 1464 to 1465, the embossed name at line 1466,
     * the expiration date concatenated from its three parts with the separator at lines 1467 to 1474, and
     * the active status at line 1475. The record's trailing filler is left cleared by the initialisation at
     * line 1461, which is filler in the stored layout too, so nothing is lost by it.
     *
     * <p>The card number is <em>not</em> reassigned. Line 1462 moves the submitted value into the key
     * position, and that value is by construction the key the read used, since the read keyed on it.
     * Reassigning the identifier of a managed row is not permitted and would achieve nothing.
     *
     * <p>The write is flushed immediately so the row-version check happens where it can be translated,
     * rather than at the end of the transaction where the failure would surface outside this method.
     *
     * @param state the turn's working storage
     * @param lockedRecord the row read for update
     * @throws OptimisticLockConflictException when the row version check fails on flush
     */
    private void rewriteCardRecord(final TurnState state, final Card lockedRecord) {
        // MOVE CC-ACCT-ID-N TO CARD-UPDATE-ACCT-ID, line 1463.
        if (!isBlank(state.workAreaAccountId)) {
            lockedRecord.setCardAcctId(state.workAreaAccountId.trim());
        }

        // Lines 1464 to 1465 source the verification code from a field the member never writes. The
        // stored value is kept; see this method's owning paragraph for the full reasoning.

        // MOVE CCUP-NEW-CRDNAME TO CARD-UPDATE-EMBOSSED-NAME, line 1466: the submitted text, VERBATIM
        // and NOT folded. The two folds in this member act on the working-storage copy of the fetched
        // record and never on the value being written, so a name typed in lower case is stored in lower
        // case. That is coherent rather than accidental: because the change comparison folds both sides,
        // a difference that is only one of letter case is never a change and so is never written at all.
        lockedRecord.setCardEmbossedName(state.newEmbossedName);

        // STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON '-' CCUP-NEW-EXPDAY DELIMITED BY SIZE INTO
        // CARD-UPDATE-EXPIRAION-DATE, lines 1467 to 1474.
        lockedRecord.setCardExpirationDate(state.newExpiryYear + EXPIRATION_DATE_SEPARATOR
                + state.newExpiryMonth + EXPIRATION_DATE_SEPARATOR + state.newExpiryDay);

        // MOVE CCUP-NEW-CRDSTCD TO CARD-UPDATE-ACTIVE-STATUS, line 1475.
        lockedRecord.setCardActiveStatus(state.newActiveStatus);

        try {
            // EXEC CICS REWRITE, lines 1477 to 1483, flushed so the version check lands here.
            this.cardRepository.saveAndFlush(lockedRecord);
            state.writeOutcome = WriteOutcome.COMMITTED;
            // The working copy now holds what was written, so the projection reflects it.
            state.foldedRecordEmbossedName = state.newEmbossedName;
        } catch (final OptimisticLockingFailureException conflict) {
            // The row version disagreed. The transaction cannot continue, so this is raised rather than
            // returned - carrying the legacy's own text for the same outcome, and never abending.
            LOG.warn("Card update refused by row version: rule=optimistic-lock resource={} outcome={}",
                    LEGACY_CARD_FILE_NAME.trim(), WriteOutcome.UPDATE_FAILED_AFTER_LOCK, conflict);
            state.writeOutcome = WriteOutcome.UPDATE_FAILED_AFTER_LOCK;
            state.returnMessage = OptimisticLockConflictException.MSG_LOCKED_BUT_UPDATE_FAILED;
            throw new OptimisticLockConflictException(state.writeOutcome.conflictKind(),
                    ENTITY_NAME_CARD, lockedRecord.getCardNum(), conflict);
        } catch (final DataAccessException failure) {
            // Lines 1488 to 1492: a write that did not succeed for any other reason is reported on the
            // screen, exactly as the source reports it, and the turn continues.
            LOG.error("Card file rewrite failed: operation={} resource={} fileStatus={}",
                    OPERATION_REWRITE, LEGACY_CARD_FILE_NAME.trim(), RAW_STATUS_READ_FAILURE, failure);
            state.errorOperationName = OPERATION_REWRITE;
            state.errorResourceName = LEGACY_CARD_FILE_NAME;
            state.rawFileStatus = RAW_STATUS_READ_FAILURE;
            state.writeOutcome = WriteOutcome.UPDATE_FAILED_AFTER_LOCK;
            state.returnMessage = OptimisticLockConflictException.MSG_LOCKED_BUT_UPDATE_FAILED;
        }
    }

    // ==============================================================================================
    // 9300-CHECK-CHANGE-IN-REC, line 1498, exit at line 1521
    // ==============================================================================================

    /**
     * The change-detection paragraph at line <b>1498</b>, with its exit at line 1521.
     *
     * <p><strong>Fold site two.</strong> Lines <b>1499</b> to 1501 convert the locked record's embossed name
     * to upper case <em>in place</em> on the working-storage copy, and they do so at the <em>head</em> of
     * the paragraph - before the comparison at lines <b>1503 to 1508</b> reads it. The carried image's copy
     * of that name was itself folded before capture, at line 1357. Both sides of the comparison are
     * therefore folded, and the consequence is contractual: <b>a change that differs only in letter case is
     * not detected as a change.</b> Folding for display, or folding after the comparison, or folding one
     * side, each flips that outcome.
     *
     * <p>As at the first fold site, the stored row is not mutated. The value folded is the working-storage
     * record the read filled; the rewrite writes a separately built image. Persisting the fold would store
     * a value the legacy never stores.
     *
     * <p>The fold is the module's 26-character ASCII table primitive. The locale-aware library method is
     * forbidden in both its forms; it is Unicode-aware and can transform characters the table leaves alone.
     *
     * <p><strong>Six fields, all of which must agree</strong>, at lines 1503 to 1508: verification code,
     * embossed name, expiry year, expiry month, expiry day and active status. Only the name is compared
     * folded, because only the name is folded by the source; comparing the status folded would make a
     * lowercase status agree with an uppercase one, which the source's raw comparison at line 1508 does
     * not.
     *
     * <p>On any single difference, lines 1511 to 1517 report the concurrent change - unconditionally, not
     * through the first-error-wins gate, which is one of only two message assignments in the member that
     * is not gated - and refresh the carried image with the locked values, so the operator is shown what
     * the record now holds. Line 1518 then takes the backward jump.
     *
     * <p>Line 1519 reads {@code END-IF EXIT}, running the two tokens together on one line where every
     * sibling paragraph puts the {@code EXIT} on its own. Harmless, and recorded as a source anomaly.
     *
     * @param state the turn's working storage
     * @param lockedRecord the row read for update, whose embossed name this method folds in place
     * @return {@code true} when the paragraph took the backward jump at line 1518, and {@code false} when
     *         it fell through to its own exit at line 1521
     */
    private boolean checkChangeInRec(final TurnState state, final Card lockedRecord) {
        // INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER, lines 1499 to 1501. In place on
        // the working-storage copy, and before the comparison reads it. The stored row is not mutated:
        // the source folds CARD-RECORD, which the read filled with INTO, while the rewrite writes the
        // separately built CARD-UPDATE-RECORD, so the fold never reaches the file.
        final String foldedName = CobolStringUtils.asciiUpperFold(lockedRecord.getCardEmbossedName());
        state.foldedRecordEmbossedName = foldedName;

        final String[] storedDate = splitExpirationDate(lockedRecord.getCardExpirationDate());
        final CarriedCardImage carried = state.carriedImage;

        // Lines 1503 to 1508.
        final boolean unchanged = sameFixedFieldValue(lockedRecord.getCardCvvCd(),
                        carried.verificationCode(), VERIFICATION_CODE_WIDTH)
                && sameFoldedFixedField(foldedName, carried.embossedName(), EMBOSSED_NAME_WIDTH)
                && sameFixedFieldValue(storedDate[0], carried.expiryYear(), EXPIRY_YEAR_WIDTH)
                && sameFixedFieldValue(storedDate[1], carried.expiryMonth(), EXPIRY_MONTH_WIDTH)
                && sameFixedFieldValue(storedDate[2], carried.expiryDay(), EXPIRY_DAY_WIDTH)
                && sameFixedFieldValue(lockedRecord.getCardActiveStatus(), carried.activeStatus(),
                        ACTIVE_STATUS_WIDTH);

        if (unchanged) {
            // CONTINUE at line 1509, then fall through to this paragraph's own exit at line 1521.
            checkChangeInRecExit();
            return false;
        }

        // SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE, line 1511: not gated, unlike almost every other
        // message assignment in the member.
        state.returnMessage = OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE;

        // Lines 1512 to 1517: refresh the carried image with what the record now holds. This is the
        // progress that bounds the loop the jump forms.
        state.carriedImage = new CarriedCardImage(
                carried.accountId(),
                carried.cardNumber(),
                lockedRecord.getCardCvvCd(),
                foldedName,
                storedDate[0],
                storedDate[1],
                storedDate[2],
                lockedRecord.getCardActiveStatus());
        state.carriedImageRefreshed = true;

        LOG.debug("Card record changed before update: rule=before-after-image-compare resource={}"
                + " outcome={}", LEGACY_CARD_FILE_NAME.trim(),
                WriteOutcome.RECORD_CHANGED_BEFORE_UPDATE);

        // GO TO 9200-WRITE-PROCESSING-EXIT, line 1518: the backward jump.
        return true;
    }

    /**
     * The change-detection exit at line 1521. A bare {@code EXIT} at line 1522.
     *
     * <p>Reached only when the comparison agreed. The backward jump at line 1518 bypasses it, which is
     * exactly what makes that jump a jump rather than a return.
     */
    private static void checkChangeInRecExit() {
        // EXIT, line 1522.
    }

    /**
     * Compares two values as the fixed-width fields they sit in, without folding either.
     *
     * @param left the first value, or {@code null} for low values
     * @param right the second value, or {@code null} for low values
     * @param width the declared field width
     * @return {@code true} when the two fields hold the same characters
     */
    private static boolean sameFixedFieldValue(final String left, final String right,
            final int width) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return sameFixedField(left, right, width);
    }

    // ==============================================================================================
    // ABEND-ROUTINE, line 1531, exit at line 1554
    // ==============================================================================================

    /**
     * The abend routine at line 1531, whose terminal abend is at line <b>1550</b>.
     *
     * <p>Registered as this transaction's abend handler at lines 370 to 372 - a registration only the five
     * programs of this family make - and reached from exactly one place: the catch-all arm of the action
     * decision at lines 1019 to 1026.
     *
     * <p>Supplies the default diagnostic text when none was set at lines 1533 to 1535, names this member as
     * the culprit at line 1537, transmits the diagnostic to the terminal at lines 1539 to 1544, deregisters
     * itself at lines 1546 to 1548, and abends with code {@code 9999} at lines 1550 to 1552.
     *
     * <p><strong>Emit, then raise.</strong> The diagnostic is logged <em>before</em> control is handed to
     * the abend service, and it names the raw two-character file status and the resource - the base file or
     * the declared account path - on <em>every</em> abend rather than only on the ones a file operation
     * preceded. That ordering is the whole point of the legacy's send-before-abend sequence: a diagnostic
     * emitted after the abend would arrive after the thing it describes has already terminated. The abend
     * service then publishes its own file-status diagnostic and raises, so the ordering holds at both
     * tiers.
     *
     * <p>The verification code appears in no log statement here or anywhere else in this class.
     *
     * <p>The deregistration at lines 1546 to 1548 is {@code EXEC CICS HANDLE ABEND CANCEL}, one of the four
     * occurrences of that token in the estate. All four are handler deregistration; not one is the COBOL
     * {@code CANCEL} verb, and nothing here cancels a loaded program.
     *
     * @param state the turn's working storage
     * @param abendCode the code the caller set, line 1021
     * @param abendReason the diagnostic the caller set, lines 1023 to 1024
     * @throws com.carddemo.exception.AbendException always
     */
    private void abendRoutine(final TurnState state, final String abendCode,
            final String abendReason) {
        final String reason = isBlank(abendReason) ? ABEND_REASON_UNEXPECTED_DATA : abendReason;
        final String resourceName = resolveReadResourceName(state).trim();

        // Emit first, and emit unconditionally. The diagnostic names the raw two-character file status and
        // the resource - the base file or the declared account path - on every abend, not only on the ones
        // a file operation preceded. A shape that varied with that would be a shape one of whose branches
        // is never emitted, and the status is the first field a reader looks for; an absent status renders
        // as such, which is information rather than noise.
        LOG.error("Abending card-update transaction: culprit={} abendCode={} terminalAbendCode={}"
                        + " reason={} fileStatus={} operation={} resource={} changeAction={}",
                LEGACY_PROGRAM_NAME, abendCode, ABEND_CODE_TERMINAL, reason, state.rawFileStatus,
                state.errorOperationName, resourceName, state.changeAction);

        // The shared abend service publishes the module's own file-status diagnostic. It is called
        // unconditionally because it renders every absent argument with its own marker, so guarding it
        // would only add a branch that one abend path could never take.
        this.abendService.displayIoStatus(state.rawFileStatus, state.errorOperationName, resourceName);

        // Then raise. The abend service logs its own diagnostic and throws; the terminal message is the
        // text the legacy transmitted at lines 1539 to 1544.
        this.abendService.abendOnline(LEGACY_PROGRAM_NAME, reason, reason);

        // Unreachable: the call above always throws. Present so the paragraph's fall-through into its own
        // exit at line 1554 is expressed rather than implied.
        abendRoutineExit();
    }

    /**
     * The abend exit at line 1554. A bare {@code EXIT} at line 1555, which the legacy never reaches because
     * the abend at line 1550 terminates the task.
     */
    private static void abendRoutineExit() {
        // EXIT, line 1555.
    }

    // ==============================================================================================
    // Shared helpers
    // ==============================================================================================

    /**
     * {@code INITIALIZE WS-THIS-PROGCOMMAREA}, lines 506 and 519: clears this program's own work area.
     *
     * @param state the turn's working storage
     */
    private static void clearProgramWorkArea(final TurnState state) {
        state.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
        state.carriedImage = CarriedCardImage.empty();
        state.newAccountId = null;
        state.newCardNumber = null;
        state.newEmbossedName = null;
        state.newExpiryYear = null;
        state.newExpiryMonth = null;
        state.newExpiryDay = null;
        state.newActiveStatus = null;
        state.cardRecord = null;
        state.foldedRecordEmbossedName = null;
        state.writeOutcome = WriteOutcome.NOT_ATTEMPTED;
    }

    /**
     * {@code INITIALIZE WS-MISC-STORAGE}, line 520: clears the flags, the messages and the work-area keys.
     *
     * <p>Every alphanumeric flag returns to spaces, which is the blank state, not the not-OK state.
     *
     * @param state the turn's working storage
     */
    private static void clearMiscellaneousStorage(final TurnState state) {
        state.inputState = InputState.PENDING;
        state.accountFilterFlag = EditFlag.BLANK;
        state.cardFilterFlag = EditFlag.BLANK;
        state.cardNameFlag = EditFlag.BLANK;
        state.cardStatusFlag = EditFlag.BLANK;
        state.expiryMonthFlag = EditFlag.BLANK;
        state.expiryYearFlag = EditFlag.BLANK;
        state.infoMessage = "";
        state.returnMessage = "";
        state.workAreaAccountId = null;
        state.workAreaCardNumber = null;
        state.recordIdentificationCardNumber = null;
        state.errorOperationName = "";
        state.errorResourceName = "";
        state.errorResponseCode = "";
        state.errorReasonCode = "";
        state.rawFileStatus = null;
        state.decoration = FieldErrorDecorator.none();
        state.fieldErrors.clear();
    }

    /**
     * {@code MOVE ZEROES TO CDEMO-ACCT-ID CDEMO-CARD-NUM} with
     * {@code MOVE LOW-VALUES TO CDEMO-ACCT-STATUS}, lines 1015 to 1017 and 521 to 522.
     *
     * @param context the state to copy
     * @return a copy with both business keys zeroed and the account status cleared
     */
    private static NavigationContext withClearedBusinessKeys(final NavigationContext context) {
        return new NavigationContext(
                context.fromTransactionId(),
                context.fromProgram(),
                context.toTransactionId(),
                context.toProgram(),
                context.userId(),
                context.userType(),
                context.programContext(),
                context.customerId(),
                context.customerFirstName(),
                context.customerMiddleName(),
                context.customerLastName(),
                zeroFill(ACCOUNT_ID_WIDTH),
                null,
                zeroFill(CARD_NUMBER_WIDTH),
                context.lastMap(),
                context.lastMapset());
    }

    /**
     * Renders {@code MOVE ZEROES} into an unsigned numeric field of the given width.
     *
     * @param width the declared field width
     * @return that many zero characters
     */
    private static String zeroFill(final int width) {
        return "0".repeat(width);
    }

    /**
     * The {@code EQUAL LOW-VALUES OR EQUAL SPACES} pair the source writes at lines 442 to 443, 449 to 450
     * and 1013 to 1014.
     *
     * @param value the value to test
     * @return {@code true} when the value is absent or holds nothing but blanks
     */
    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    /**
     * Trims a value for comparison against a literal whose declared field is wider than the literal.
     *
     * <p>The mapset and program-name literals at lines 223 to 254 carry trailing blanks because their
     * fields are wider than their text, so a comparison has to disregard that padding - which is what a
     * COBOL alphanumeric comparison does by padding the shorter operand.
     *
     * @param value the value to trim
     * @return the trimmed value, or {@code null} when the value was absent or blank
     */
    private static String trimmedOrNull(final String value) {
        return isBlank(value) ? null : value.trim();
    }

    /**
     * Packs the turn's working storage into the returned value.
     *
     * <p>The analogue of the two moves at lines 549 to 552 that pack the shared and program-specific
     * communication areas for the terminal return.
     *
     * @param state the turn's working storage
     * @return the outcome of the turn, never {@code null}
     */
    private static CardUpdateResult toResult(final TurnState state) {
        final ScreenWorkArea workArea = new ScreenWorkArea(
                state.keyAction,
                // MOVE LIT-THISPGM TO CCARD-NEXT-PROG, line 570.
                LEGACY_PROGRAM_NAME,
                // MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET, lines 571 and 1326.
                LEGACY_MAPSET_NAME.trim(),
                // MOVE LIT-THISMAP TO CCARD-NEXT-MAP, lines 572 and 1327.
                LEGACY_MAP_NAME,
                // MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG, lines 547 and 569.
                state.returnMessage,
                state.returnMessage,
                state.workAreaAccountId,
                state.workAreaCardNumber,
                state.navigationContext.customerId());

        final NavigationContext echoed = state.reEntry
                ? state.navigationContext.withReEntry()
                : state.navigationContext;

        return new CardUpdateResult(
                state.route,
                echoed,
                state.transactionId,
                workArea,
                state.changeAction,
                (state.cardRecord == null)
                        ? null
                        : CardProjection.of(state.cardRecord, state.foldedRecordEmbossedName),
                state.carriedImage,
                state.returnMessage,
                state.infoMessage,
                state.focusField,
                // INPUT-ERROR, plus the translation-layer report of an attention identifier that did
                // not decode. The two are unioned here rather than merged into the input flag itself,
                // because SET INPUT-OK TO TRUE at line 643 would otherwise discard the report before
                // the turn ends - see the CardUpdateResult component Javadoc.
                state.inputState.inputError() || state.attentionKeyUnmapped,
                state.attentionKeyUnmapped,
                state.reEntry,
                state.dataWasChangedBeforeUpdate(),
                state.writeOutcome,
                List.copyOf(state.fieldErrors),
                state.decoration,
                state.header,
                state.screen);
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
