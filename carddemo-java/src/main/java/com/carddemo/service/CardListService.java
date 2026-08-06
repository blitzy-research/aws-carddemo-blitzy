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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import com.carddemo.domain.Card;
import com.carddemo.domain.enums.CardStatus;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import com.carddemo.util.CobolStringUtils;
import com.carddemo.util.PfKeyTranslator;

/**
 * The card-list screen: one turn of legacy transaction {@code CCLI}, translated from
 * {@code app/cbl/COCRDLIC.cbl} (1,459 lines). Provenance is checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} and upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, the trailer this member carries at its
 * line 1458.
 *
 * <p>The member belongs to the <strong>five-program family</strong> &mdash; {@code COACTUPC},
 * {@code COACTVWC}, {@code COCRDLIC}, {@code COCRDSLC}, {@code COCRDUPC} &mdash; which alone
 * includes the attention-key copybook {@code app/cpy/CSSTRPFY.cpy} and alone registers a CICS
 * abend handler. This member is an includer of the former but <em>not</em> one of the latter: its
 * abend-variable include is commented out at line 283, and a scan of the member finds no abend
 * statement and no call to the language-environment abort routine on any path. That posture governs
 * the whole failure design of this class and is developed below.
 *
 * <h2>Paragraph accounting: 42 measured against 39 in the action plan</h2>
 *
 * <p>The action plan records this member as having 39 paragraphs; the figure verified here is 42
 * translation units. Both numbers are right and they count different things, so the reconciliation
 * is stated rather than one number being quietly preferred:
 *
 * <ul>
 *   <li><strong>39</strong> named paragraph labels are declared in this member's own procedure
 *       division, from {@code 0000-MAIN} at line 298 to {@code SEND-LONG-TEXT-EXIT} at line 1452.
 *       That is the action plan's figure and it is exactly reproducible by scanning column 8.</li>
 *   <li><strong>1</strong> further unit is the {@code COPY 'CSSTRPFY'} expansion site at line 1416,
 *       which is a procedure-division statement in its own right and needs its own translation
 *       point because a copybook that contributes paragraphs is invoked, not declared.</li>
 *   <li><strong>2</strong> further units are the paragraphs that expansion delivers,
 *       {@code YYYY-STORE-PFKEY} at line 17 of {@code app/cpy/CSSTRPFY.cpy} and its exit at line
 *       80 of the same member.</li>
 * </ul>
 *
 * <p>39 + 1 + 2 = 42. Every one of the 42 has a named method: the 39 paragraphs and the expansion
 * site are the 40 methods declared here, and the 2 copybook paragraphs are credited to
 * {@code PfKeyTranslator} in the traceability matrix rather than duplicated here, because that
 * class is their single owner and this member is one of its five includers.
 *
 * <p>Seventeen of the 39 are the {@code NNNN-NAME-EXIT} half of the paired idiom and contain
 * nothing but {@code EXIT.}. Each becomes a named method with a documented empty body and is
 * invoked at each exit point of its partner, so a forward {@code GO TO} to an exit label becomes an
 * invocation followed by {@code return}. A body is deliberately not invented for them: the action
 * plan's treatment of the empty fee paragraph in {@code app/cbl/CBACT04C.cbl} establishes that a
 * documented no-op is the faithful translation of an empty COBOL paragraph, and filling one would
 * be behaviour this estate does not have.
 *
 * <p>The complete mapping, in source order, so that it is verifiable without leaving this file:
 *
 * <pre>
 *  1  0000-MAIN                    298   mainPara
 *  2  COMMON-RETURN                604   commonReturn
 *  3  0000-MAIN-EXIT               621   mainParaExit
 *  4  1000-SEND-MAP                624   sendMap
 *  5  1000-SEND-MAP-EXIT           639   sendMapExit
 *  6  1100-SCREEN-INIT             642   screenInit
 *  7  1100-SCREEN-INIT-EXIT        674   screenInitExit
 *  8  1200-SCREEN-ARRAY-INIT       678   screenArrayInit
 *  9  1200-SCREEN-ARRAY-INIT-EXIT  745   screenArrayInitExit
 * 10  1250-SETUP-ARRAY-ATTRIBS     748   setupArrayAttribs
 * 11  1250-SETUP-ARRAY-ATTRIBS-EXIT 834  setupArrayAttribsExit
 * 12  1300-SETUP-SCREEN-ATTRS      837   setupScreenAttrs
 * 13  1300-SETUP-SCREEN-ATTRS-EXIT 890   setupScreenAttrsExit
 * 14  1400-SETUP-MESSAGE           895   setupMessage
 * 15  1400-SETUP-MESSAGE-EXIT      933   setupMessageExit
 * 16  1500-SEND-SCREEN             938   sendScreen
 * 17  1500-SEND-SCREEN-EXIT        948   sendScreenExit
 * 18  2000-RECEIVE-MAP             951   receiveMap
 * 19  2000-RECEIVE-MAP-EXIT        959   receiveMapExit
 * 20  2100-RECEIVE-SCREEN          962   receiveScreen
 * 21  2100-RECEIVE-SCREEN-EXIT     981   receiveScreenExit
 * 22  2200-EDIT-INPUTS             985   editInputs
 * 23  2200-EDIT-INPUTS-EXIT        999   editInputsExit
 * 24  2210-EDIT-ACCOUNT           1003   editAccount
 * 25  2210-EDIT-ACCOUNT-EXIT      1032   editAccountExit
 * 26  2220-EDIT-CARD              1036   editCard
 * 27  2220-EDIT-CARD-EXIT         1069   editCardExit
 * 28  2250-EDIT-ARRAY             1073   editArray
 * 29  2250-EDIT-ARRAY-EXIT        1119   editArrayExit
 * 30  9000-READ-FORWARD           1123   readForward
 * 31  9000-READ-FORWARD-EXIT      1261   readForwardExit
 * 32  9100-READ-BACKWARDS         1264   readBackwards
 * 33  9100-READ-BACKWARDS-EXIT    1374   readBackwardsExit
 * 34  9500-FILTER-RECORDS         1382   filterRecords
 * 35  9500-FILTER-RECORDS-EXIT    1409   filterRecordsExit
 * 36  SEND-PLAIN-TEXT             1422   sendPlainText
 * 37  SEND-PLAIN-TEXT-EXIT        1433   sendPlainTextExit
 * 38  SEND-LONG-TEXT              1441   sendLongText
 * 39  SEND-LONG-TEXT-EXIT         1452   sendLongTextExit
 * 40  COPY 'CSSTRPFY' (site)      1416   storePfKeyExpansion
 * 41  YYYY-STORE-PFKEY      CSSTRPFY 17  credited to PfKeyTranslator
 * 42  YYYY-STORE-PFKEY-EXIT CSSTRPFY 80  credited to PfKeyTranslator
 * </pre>
 *
 * <h2>Two things here are counter-intuitive</h2>
 *
 * <p><strong>The page size is seven, not ten.</strong> See {@link #PAGE_SIZE}. Two independent
 * declarations in the source prove it and neither is a tuning value.
 *
 * <p><strong>The browse walks the base cluster by card number, not the account alternate
 * index.</strong> Lines 213 to 217 declare two file-name literals: the base cluster
 * {@value #LIT_CARD_FILE} and the account path {@value #LIT_CARD_FILE_ACCT_PATH}. The second is
 * declared and then <em>never referenced anywhere in the member</em> &mdash; those two lines are its
 * only occurrences. Every browse verb names the base cluster with the card number as its record
 * identifier: the browse starts at lines 1129 to 1136 and 1273 to 1280, reads at lines 1146 to
 * 1154, 1197 to 1205, 1294 to 1302 and 1322 to 1330, and ends at lines 1258 and 1376. The account
 * filter is applied <strong>after</strong> each read, by {@code 9500-FILTER-RECORDS} at line 1382.
 *
 * <p>This class therefore browses through the repository's <em>inherited</em> paged
 * {@code findAll} with an explicit sort on the card number, and filters afterwards. It does
 * <strong>not</strong> call the account finder that {@code CardRepository} declares: that finder
 * exists for the account-view, account-update, card-detail and card-update flows, and routing this
 * list through it would silently reorder the screen from card number to account identifier and
 * change which rows land on a page. The declared-but-unused alternate-index reference is recorded
 * as a decision-log entry precisely so that a later reviewer does not "optimise" the post-retrieval
 * filter into the query.
 *
 * <h2>What the browse actually guarantees, and two quirks that are contractual</h2>
 *
 * <p>The legacy read loop keeps reading past excluded records until seven have been accepted or the
 * data is exhausted, so a single fixed-size page read is not an equivalent translation. The
 * emulation walks the key sequence in chunks of one screen's worth and applies the filter to each
 * row in read order, which reproduces the record-at-a-time browse without introducing a granularity
 * figure of its own.
 *
 * <p><strong>Quirk one: whether a further page exists is decided by the next physical row, not the
 * next matching row.</strong> Having filled the seventh slot, the source performs one more read at
 * lines 1197 to 1205 and does <em>not</em> pass it through the filter. When that read succeeds the
 * next-page indicator is raised and the forward cursor is overwritten with that row's key at lines
 * 1212 to 1214, so the following page resumes at the eighth physical row rather than at the seventh
 * accepted one. With a filter active the indicator can therefore report a further page when no
 * further row actually matches, and the next turn will present an empty page. That is reproduced.
 *
 * <p><strong>Quirk two: the retained cursor is the card number alone.</strong> The source declares
 * its cursor as a 27-byte composite of a 16-character card number and an 11-digit account
 * identifier at lines 230 to 235, but all four moves of the account half are commented out &mdash;
 * at lines 448 to 449, 475 to 476, 490 to 491 and 506 to 507 &mdash; so only the card number is
 * ever used as the record identifier. The cursor carried here is consequently the card number and
 * nothing else, which also keeps it opaque: it is never split, parsed or re-cased, only compared
 * whole against a row's key to position the walk.
 *
 * <h2>Backward paging reads descending and presents ascending</h2>
 *
 * <p>{@code 9100-READ-BACKWARDS} at line 1264 sets its row counter to one past the screen maximum,
 * consumes the current page's own first row with an unstored read at lines 1294 to 1307, and then
 * fills slots downward from the seventh to the first. The read order is therefore descending while
 * the assembled page presents ascending, so the response list is the <strong>reverse of the read
 * order</strong> and never simply the descending read order. A partial backward page consequently
 * leaves the low slots empty and the rows at the bottom of the screen, which is why every row
 * carries its screen slot.
 *
 * <h2>Selection: a positional bitmap and a tally</h2>
 *
 * <p>{@code 2250-EDIT-ARRAY} begins at line 1073. The tally at lines 1079 to 1082 counts the two
 * accepted selection characters across all seven positions and line 1084 rejects a count above one.
 * The bitmap at lines 1088 to 1093 maps those two characters to a raised flag and every other
 * character to a cleared one, positionally, so it is translated as a positional list aligned to the
 * seven slots with empty positions surviving in place rather than as a set of selected rows. The
 * ordered evaluation at lines 1100 to 1114 then walks the positions and its clause order is
 * preserved exactly.
 *
 * <p>Where this class is deliberately <em>more</em> informative than the source: a count above one
 * names every offending row index in the reported error rather than silently taking the first. The
 * screen message is unchanged and the rejection is unchanged, so behaviour is identical.
 *
 * <h2>Failure handling, and why nothing here abends on input or output</h2>
 *
 * <p>Every unexpected browse response in the source is tolerated: the arms at lines 1246 to 1254
 * and 1361 to 1369 leave the read loop and compose a fixed-format file-error message which is then
 * presented on the screen. There is no abend on any of those paths. This class reproduces that, and
 * satisfies the mandated emit-then-raise ordering by logging the raw two-character status and the
 * resource name at error level <em>first</em> and only then delegating to {@code AbendService},
 * whose no-abend diagnostic entry point exists for exactly this case &mdash; a caller that has
 * emitted the status but has not decided to abend. The one path on which this member would genuinely
 * abend is the transfer-control dispatch, and an unresolvable destination there is logged and then
 * raised through {@code AbendService}, matching the abend the legacy transfer would have produced.
 *
 * <p>Optimistic-lock conflicts are not abends, and this service cannot produce one: it is read-only
 * by contract, performs no write of any kind and touches no version attribute.
 *
 * <h2>Source anomalies reproduced rather than corrected</h2>
 *
 * <ul>
 *   <li>Line 790 carries a stray orphan operand inside the fourth row's protect branch. It has no
 *       effect and no translation.</li>
 *   <li>The first row is protected with a different attribute constant from rows two to seven, at
 *       line 753 against lines 766, 777, 789, 801, 812 and 824.</li>
 *   <li>The decoration marker is written for the first row only, at lines 757 to 758, while rows
 *       two to seven position the cursor instead. Both rules are reproduced as they stand.</li>
 *   <li>Lines 439 to 440 duplicate the condition of lines 444 to 445 in the main evaluation. The
 *       duplicate shares the following statements and is behaviourally inert.</li>
 *   <li>Four dispatch sites perform the send paragraph through <em>itself</em> rather than through
 *       its exit label, at lines 436, 452, 480 and 580, while lines 495 and 511 name the exit label
 *       correctly. Because the exit paragraph does nothing, the two spellings are equivalent.</li>
 *   <li>The browse-start response is captured at line 1134 and never tested, so a failure to
 *       position is indistinguishable from an empty result. Reproduced.</li>
 *   <li>An end-of-file on the backward read falls into the unclassified arm at line 1361 and yields
 *       a file-error message instead of a clean "no previous pages" message. Reproduced.</li>
 *   <li>The two dispatch arms at lines 517 and 545 subscript the selection array with an index that
 *       is zero when nothing is selected, which the condition name at line 94 exists to guard and
 *       which the source does not use. Java cannot reproduce an out-of-range subscript safely, so
 *       the guard is applied and the omission is documented.</li>
 * </ul>
 *
 * <p>For context only: line 70 carries {@code USAGE COMP-3}, the estate's single packed-decimal
 * declaration, on a transient screen counter that is never persisted. No packed-decimal decoder
 * exists or is needed anywhere in this module and none is created here.
 *
 * <h2>Shape and guarantees</h2>
 *
 * <p>A stateless singleton. It declares no mutable field, caches no page and holds no cursor: all
 * per-turn state lives in a local object created on entry and discarded on exit, so two concurrent
 * turns are wholly independent. Every collaborator is injected through the constructor. The one
 * character-handling utility this class might have reached for is deliberately not used: both of its
 * candidate primitives accept a decoration marker or the fuller numeric grammar of another screen,
 * and either would widen this member's edits to admit input it rejects, so the two edits below are
 * expressed against this member's own tests instead.
 *
 * <p>The turn is deliberately non-transactional. Repository browse failures are therefore caught
 * after the repository call's own transaction has ended, preserving the returned legacy screen
 * outcome instead of risking a rollback-only exception at service exit.
 */
@Service
public final class CardListService {

    /**
     * Structured diagnostic channel. {@code COCRDLIC} declares no {@code DISPLAY} statement of its own,
     * so every record written here is new observability rather than a translated statement.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CardListService.class);

    // ==============================================================================================
    // Literals and constants: WS-CONSTANTS, app/cbl/COCRDLIC.cbl lines 176 to 217
    // ==============================================================================================

    /** {@code LIT-THISPGM}, line 180: this member's own name, used as the abend culprit. */
    private static final String LIT_THISPGM = "COCRDLIC";

    /** {@code LIT-THISTRANID}, line 182: the transaction re-armed on every normal return. */
    private static final String LIT_THISTRANID = "CCLI";

    /** {@code LIT-THISMAPSET}, line 184. */
    private static final String LIT_THISMAPSET = "COCRDLI";

    /** {@code LIT-THISMAP}, line 186. */
    private static final String LIT_THISMAP = "CCRDLIA";

    // ==============================================================================================
    // Header field widths and separators, from the moves at lines 647 to 664
    // ==============================================================================================

    /** {@code TRNNAME}, {@code PIC X(4)}: the width the transaction name is moved at, line 649. */
    private static final int TRANSACTION_NAME_WIDTH = 4;

    /** {@code PGMNAME}, {@code PIC X(8)}: the width the program name is moved at, line 650. */
    private static final int PROGRAM_NAME_WIDTH = 8;

    /** Width of each two-digit part of the header date and time, lines 654 to 662. */
    private static final int HEADER_PART_WIDTH = 2;

    /** Width of the four-digit year the two-digit header year is sliced out of. */
    private static final int HEADER_FULL_YEAR_WIDTH = 4;

    /**
     * Start of the two-digit header year, zero-based.
     *
     * <p>The reference modification at line 656 is {@code WS-CURDATE-YEAR(3:2)}, and COBOL positions are
     * one-based, so the third character is index two here.
     */
    private static final int HEADER_YEAR_FROM = 2;

    /** End of the two-digit header year, exclusive. */
    private static final int HEADER_YEAR_TO = 4;

    /** Separator of the header date, {@code MM/DD/YY}, assembled at lines 654 to 658. */
    private static final String HEADER_DATE_SEPARATOR = "/";

    /** Separator of the header time, {@code HH:MM:SS}, assembled at lines 660 to 664. */
    private static final String HEADER_TIME_SEPARATOR = ":";

    /**
     * The standard-user code, from the condition name {@code CDEMO-USRTYP-USER} of
     * {@code app/cpy/COCOM01Y.cpy}, which this member copies at line 227. Assigned unconditionally at
     * lines 320, 388, 466, 522 and 550, so an administrator arriving at this screen is recorded as a
     * standard user; that is the source's behaviour and it is reproduced. It is continuity state that the
     * client echoes and that is reconciled against the authenticated principal before being trusted, so
     * the assignment carries no authorisation consequence.
     */
    private static final String USER_TYPE_STANDARD = "U";

    /** {@code LIT-MENUPGM}, line 188: the destination of the exit key. */
    private static final String LIT_MENUPGM = "COMEN01C";

    /** {@code LIT-CARDDTLPGM}, line 196: the destination of a view selection. */
    private static final String LIT_CARDDTLPGM = "COCRDSLC";

    /** {@code LIT-CARDUPDPGM}, line 204: the destination of an update selection. */
    private static final String LIT_CARDUPDPGM = "COCRDUPC";

    /**
     * {@code LIT-CARD-FILE}, lines 213 to 214: the card base cluster, and the only resource this
     * member ever browses. The source literal is padded to eight characters; the logical name is
     * carried here because it is used for diagnostics rather than for a fixed-width record image.
     */
    private static final String LIT_CARD_FILE = "CARDDAT";

    /**
     * {@code LIT-CARD-FILE-ACCT-PATH}, lines 215 to 217: the account alternate index. Declared by
     * the source and referenced by nothing in it, which is the single most consequential fact about
     * this member's data access. It is retained here for the diagnostic that names the resource a
     * failing browse was walking, and to keep the traceability from the declaration to its Java
     * counterpart visible; no query is ever issued against it.
     */
    private static final String LIT_CARD_FILE_ACCT_PATH = "CARDAIX";

    /**
     * The number of card rows one screen carries: <strong>seven</strong>.
     *
     * <p>This is a <strong>legacy behavioural contract</strong>, not a performance setting and not a
     * tuning knob. It is not configurable, is exposed through no property, and must not be varied:
     * changing it changes which rows a page contains and therefore what the screen shows.
     *
     * <p>Proven twice, independently, in {@code app/cbl/COCRDLIC.cbl}:
     *
     * <ul>
     *   <li>Lines 250 to 260 declare the screen row table. The comment at line 250 states the
     *       arithmetic outright &mdash; 28 characters by 7 rows is 196 &mdash; line 253 declares a
     *       196-character all-rows field, line 254 redefines it, line 255 occurs the row seven
     *       times, and lines 258 to 260 give each row an 11-character account number, a
     *       16-character card number and a 1-character status, which is the 28.</li>
     *   <li>Lines 177 to 178 declare the maximum screen line count with the value 7 independently
     *       of the table, and line 1191 uses it as the loop bound that ends the forward read.</li>
     * </ul>
     *
     * <p>The repository imposes no page size, so this service supplies it on every page request.
     * The three list screens in the estate each have their own row count &mdash; this one is seven
     * while the transaction and user lists are ten &mdash; and no constant is shared between them,
     * because they are three independent screen shapes that happen to disagree.
     */
    private static final int PAGE_SIZE = 7;

    /**
     * The granularity of the emulated sequential browse: one screen's worth of rows per read.
     *
     * <p>Deliberately the same figure as {@link #PAGE_SIZE} and derived from it, so that no second
     * numeric knob enters this class. The source browses one record at a time; reading a screen's
     * worth per query preserves the walk order and the post-retrieval filtering exactly while
     * keeping the query count proportionate. It is an I/O granularity, not a limit: the walk
     * continues across as many reads as it takes to accept seven rows or exhaust the data.
     */
    private static final int BROWSE_CHUNK_SIZE = PAGE_SIZE;

    /** {@code CC-ACCT-ID} of {@code app/cpy/CVCRD01Y.cpy}: the account filter is 11 characters. */
    private static final int ACCOUNT_FILTER_WIDTH = 11;

    /** {@code CC-CARD-NUM} of {@code app/cpy/CVCRD01Y.cpy}: the card filter is 16 characters. */
    private static final int CARD_FILTER_WIDTH = 16;

    /**
     * {@code WS-ERROR-MSG}, line 117, and {@code CCARD-ERROR-MSG} of {@code app/cpy/CVCRD01Y.cpy}:
     * both 75 characters. The width matters because the composed file-error message is 80 bytes and
     * the move into this field truncates it.
     */
    private static final int ERROR_MESSAGE_WIDTH = 75;

    /** {@code WS-INFO-MSG}, line 112: the informational message field is 45 characters. */
    private static final int INFO_MESSAGE_WIDTH = 45;

    /** {@code WS-LONG-MSG}, line 111: the long diagnostic field is 500 characters. */
    private static final int LONG_MESSAGE_WIDTH = 500;

    /** The 3270 field name of the account filter, for the field-level error contract. */
    private static final String BMS_FIELD_ACCOUNT_FILTER = "ACCTSID";

    /** The 3270 field name of the card filter, for the field-level error contract. */
    private static final String BMS_FIELD_CARD_FILTER = "CARDSID";

    /** Stem of the seven 3270 selection field names, {@code CRDSEL1} through {@code CRDSEL7}. */
    private static final String BMS_FIELD_SELECTION_STEM = "CRDSEL";

    /** Property name reported for an account-filter failure. */
    private static final String PROPERTY_ACCOUNT_FILTER = "accountIdFilter";

    /** Property name reported for a card-filter failure. */
    private static final String PROPERTY_CARD_FILTER = "cardNumberFilter";

    /** Stem of the seven selection property names, {@code selection1} through {@code selection7}. */
    private static final String PROPERTY_SELECTION_STEM = "selection";

    /**
     * Fixed stand-in emitted in place of each withheld component by the renderings below.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a withheld
     * component &mdash; not its length, not a prefix or suffix, not a digest &mdash; survives into a
     * stringified instance. A partial mask was rejected deliberately: a truncated primary account
     * number is still cardholder data, and a browse cursor on this screen <em>is</em> a card number.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    // ==============================================================================================
    // Message literals. Every one is reproduced character for character from the source, because
    // operators and downstream tooling match on the text. None is trimmed, re-cased or reworded, and
    // the two that read oddly -- a missing space after a comma, a full stop mid-sentence -- are the
    // source's own wording and are not corrected.
    // ==============================================================================================

    /** {@code WS-ERROR-MSG-OFF}, line 118: the message field is blank when no message is set. */
    private static final String NO_MESSAGE = "";

    /** {@code WS-INFORM-REC-ACTIONS}, lines 115 to 116. */
    private static final String MSG_INFORM_REC_ACTIONS =
            "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    /** {@code WS-EXIT-MESSAGE}, lines 119 to 120. */
    private static final String MSG_EXIT = "PF03 PRESSED.EXITING";

    /** {@code WS-NO-RECORDS-FOUND}, lines 121 to 122. */
    private static final String MSG_NO_RECORDS_FOUND =
            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /** {@code WS-MORE-THAN-1-ACTION}, lines 123 to 124. */
    private static final String MSG_MORE_THAN_1_ACTION =
            "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

    /** {@code WS-INVALID-ACTION-CODE}, lines 125 to 126. */
    private static final String MSG_INVALID_ACTION_CODE = "INVALID ACTION CODE";

    /** The account-filter format message, lines 1021 to 1023. */
    private static final String MSG_ACCOUNT_FILTER_FORMAT =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** The card-filter format message, lines 1057 to 1059. */
    private static final String MSG_CARD_FILTER_FORMAT =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** Line 903: the backward key pressed while already on the first page. */
    private static final String MSG_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    /** Line 908: the forward key pressed after the last page has already been shown. */
    private static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    /** Lines 1219 and 1239: the browse reached the end of the cluster. */
    private static final String MSG_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    // ==============================================================================================
    // The file-error message, WS-FILE-ERROR-MESSAGE at lines 153 to 171. Eight fixed fragments and
    // three variable fields compose exactly 80 bytes; the move into the 75-character message field at
    // lines 1230, 1254, 1316 and 1369 truncates the trailing 5-byte filler, so the visible text is
    // the whole of the meaningful content. The fragments are reproduced with their declared widths.
    // ==============================================================================================

    /** {@code FILLER PIC X(12) VALUE 'File Error:'}, lines 154 to 155: 11 characters in 12. */
    private static final String FILE_ERROR_PREFIX = "File Error: ";

    /** {@code ERROR-OPNAME PIC X(8)}, lines 156 to 157: the failing operation. */
    private static final int FILE_ERROR_OPNAME_WIDTH = 8;

    /** {@code FILLER PIC X(4) VALUE ' on '}, lines 158 to 159. */
    private static final String FILE_ERROR_ON = " on ";

    /** {@code ERROR-FILE PIC X(9)}, lines 160 to 161: the resource being browsed. */
    private static final int FILE_ERROR_FILE_WIDTH = 9;

    /** {@code FILLER PIC X(15) VALUE ' returned RESP '}, lines 162 to 164. */
    private static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    /** {@code ERROR-RESP PIC X(10)}, lines 165 to 166. */
    private static final int FILE_ERROR_RESP_WIDTH = 10;

    /** {@code FILLER PIC X(7) VALUE ',RESP2 '}, lines 167 to 168. */
    private static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** {@code MOVE 'READ' TO ERROR-OPNAME}, lines 1226, 1250, 1312 and 1365. */
    private static final String FILE_ERROR_OPERATION_READ = "READ";

    // ==============================================================================================
    // Two-character file statuses. This member is a CICS program and branches on response conditions
    // rather than on a COBOL file status, so the conditions are mapped onto the estate's verified
    // two-character vocabulary for the diagnostic channel. All four codes below occur in the estate;
    // none is invented. The mapping is the platform substitution and is recorded in the decision log.
    // ==============================================================================================

    /** The normal condition of lines 1157, 1208, 1305 and 1333: success. */
    private static final String STATUS_NORMAL = "00";

    /** The duplicate-record condition of lines 1158, 1209, 1306 and 1334: duplicate alternate key. */
    private static final String STATUS_DUPLICATE = "02";

    /** The end-of-file condition of lines 1215 and 1233: end of file. */
    private static final String STATUS_END_OF_FILE = "10";

    /** The unclassified arm of lines 1222, 1246 and 1361: a permanent error. */
    private static final String STATUS_PERMANENT_ERROR = "31";

    // ==============================================================================================
    // Browse ordering. Neither direction is declared here as a sort object any more: the ordering is
    // part of the repository's derived query names, so the forward browse of lines 1146 to 1154 and the
    // backward browse of lines 1322 to 1330 each name their own ordered, bounded read. The key is the
    // card number in both directions because that is the base cluster's key, and a fixed-width
    // all-digit key orders identically whether compared as text or as a number.
    // ==============================================================================================

    // ==============================================================================================
    // Injected collaborators. Constructor injection only, and every one is final.
    // ==============================================================================================

    /** The card base cluster {@code CARDDAT}, walked by card number exactly as the source walks it. */
    private final CardRepository cardRepository;

    /** The common-message catalogue supplying the fifty-character invalid-key text. */
    private final MessageCatalogService messageCatalogService;

    /** The navigation rules replacing the transfer-control dispatch this member performs. */
    private final NavigationService navigationService;

    /** The terminal path taken once an unclassified file condition has been logged. */
    private final AbendService abendService;

    /**
     * Stands in for {@code FUNCTION CURRENT-DATE} at lines 645 and 652.
     *
     * <p>Injected rather than read from the system default so the header this screen stamps is
     * reproducible under test, which is the same arrangement the card-detail screen uses for the same
     * paragraph.
     */
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param cardRepository        the card persistence gateway, browsed through its inherited paged
     *                              {@code findAll} only; must not be {@code null}
     * @param messageCatalogService the common message catalogue, source of the invalid-key text and of
     *                              the two screen titles; must not be {@code null}
     * @param navigationService     the route resolver, sole owner of the destination table; must not
     *                              be {@code null}
     * @param abendService          the diagnostic and abend gateway; must not be {@code null}
     * @param clock                 the clock the screen header reads; must not be {@code null}
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public CardListService(final CardRepository cardRepository,
            final MessageCatalogService messageCatalogService,
            final NavigationService navigationService,
            final AbendService abendService,
            final Clock clock) {
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository must not be null");
        this.messageCatalogService =
                Objects.requireNonNull(messageCatalogService, "messageCatalogService must not be null");
        this.navigationService =
                Objects.requireNonNull(navigationService, "navigationService must not be null");
        this.abendService = Objects.requireNonNull(abendService, "abendService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ==============================================================================================
    // Condition names as enums. The level-88 groups of the source become enum constants with
    // predicate methods, and the assignments that raise them become enum assignments.
    // ==============================================================================================

    /**
     * The two selection characters the screen accepts, from the condition names at lines 77 to 79 of
     * {@code app/cbl/COCRDLIC.cbl}: the accepted group holds both, and the two narrower names
     * distinguish a view request from an update request.
     *
     * <p>There is deliberately no constant for "blank" and none for "anything else". The source keeps
     * those as separate condition names on the same field &mdash; blank at lines 80 to 82, and the
     * unclassified case as the trailing arm of the evaluation at line 1108 &mdash; and modelling them
     * as members here would make an absent selection look like a chosen action. Absence is reported
     * as an empty {@link Optional} instead.
     */
    public enum SelectionAction {

        /** {@code VIEW-REQUESTED-ON}, line 78: transfer to the card-detail screen. */
        VIEW('S'),

        /** {@code UPDATE-REQUESTED-ON}, line 79: transfer to the card-update screen. */
        UPDATE('U');

        /** The single character the screen selection field carries for this action. */
        private final char code;

        /**
         * Binds the constant to the character the screen selection field carries.
         *
         * @param code the single character the screen selection field carries
         */
        SelectionAction(final char code) {
            this.code = code;
        }

        /**
         * Returns the single character the screen field carries for this action.
         *
         * @return {@code 'S'} for a view request and {@code 'U'} for an update request
         */
        public char getCode() {
            return this.code;
        }

        /**
         * Reports whether this action requests the card-detail screen.
         *
         * @return {@code true} for {@link #VIEW}
         */
        public boolean isViewRequested() {
            return this == VIEW;
        }

        /**
         * Reports whether this action requests the card-update screen.
         *
         * @return {@code true} for {@link #UPDATE}
         */
        public boolean isUpdateRequested() {
            return this == UPDATE;
        }

        /**
         * Resolves a transmitted selection field to an action.
         *
         * <p>Matching is exact and single-character, as the source's comparison against a
         * one-character field is. No case folding is applied, because the condition names name upper
         * case letters only and accepting a lower-case selection would admit input the screen
         * rejects.
         *
         * @param selection the transmitted field, which may be {@code null}, blank or any other text
         * @return the action, or an empty {@link Optional} when the field is absent, blank, longer
         *         than one character, or holds any other character
         */
        public static Optional<SelectionAction> fromSelection(final String selection) {
            if (selection == null || selection.length() != 1) {
                return Optional.empty();
            }
            final char candidate = selection.charAt(0);
            for (final SelectionAction action : values()) {
                if (action.code == candidate) {
                    return Optional.of(action);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * The three states of each key filter's edit flag, from the condition names at lines 61 to 64
     * (account) and 65 to 68 (card) of {@code app/cbl/COCRDLIC.cbl}.
     *
     * <p>All three are needed and none is redundant: the source's screen-attribute evaluation at
     * lines 845 to 866 treats valid and not-OK alike when echoing the field back but treats blank
     * differently, and the post-retrieval filter at lines 1385 and 1396 applies a filter only when
     * its flag is valid, so a not-OK filter and a blank filter both leave the data unfiltered while
     * differing on the screen.
     */
    public enum FilterFlag {

        /** The filter was supplied but failed its edit; lines 62 and 66. */
        NOT_OK,

        /** The filter was supplied and passed its edit, so it participates in the browse filter. */
        VALID,

        /** The filter was not supplied; lines 64 and 68. */
        BLANK;

        /**
         * Reports whether the filter failed its edit.
         *
         * @return {@code true} for {@link #NOT_OK}
         */
        public boolean isNotOk() {
            return this == NOT_OK;
        }

        /**
         * Reports whether the filter is usable, which is what makes it participate in the filter.
         *
         * @return {@code true} for {@link #VALID}
         */
        public boolean isValid() {
            return this == VALID;
        }

        /**
         * Reports whether the filter was left empty.
         *
         * @return {@code true} for {@link #BLANK}
         */
        public boolean isBlank() {
            return this == BLANK;
        }
    }

    /**
     * The four outcomes the source's browse evaluations distinguish, at lines 1156 to 1255 for the
     * forward read and 1304 to 1370 for the backward one.
     *
     * <p>Kept as four rather than collapsed to success and failure because the source branches on all
     * four and the distinctions are load bearing: the duplicate condition is handled identically to
     * the normal one because the cluster's alternate index is non-unique, end of file is a normal
     * outcome that ends the walk cleanly on the forward path, and the unclassified arm is the only
     * one that composes a file-error message. Collapsing them would erase the end-of-file against
     * error distinction the read loops depend on.
     *
     * <p>Each constant carries the two-character status its condition maps onto, which is what
     * reaches the diagnostic channel.
     *
     * <p><strong>Where the source's {@code WHEN OTHER} clause went.</strong> Each of the four
     * {@code EVALUATE} statements this enum serves ends in a {@code WHEN OTHER} clause, and that clause
     * is translated to the {@code OTHER} constant below rather than to a Java {@code default} label.
     * The clause is not a catch-all in the source: it is a specific, reachable condition sited at three
     * named lines, and it is the only one that composes a file-error message. Normalisation happens
     * once, in the read method, which maps every raw status onto exactly one of these four constants,
     * so each of the four {@code switch} statements covers all four constants explicitly and in the
     * source's own clause order. A {@code default} label added on top of that exhaustive cover would be
     * unreachable, would present as a permanently uncovered branch, and would disguise a real condition
     * as a fallback. Clause order is preserved and the clause itself is preserved; only the Java
     * keyword differs, and the divergence is recorded rather than hidden.
     */
    private enum BrowseResponse {

        /** Lines 1157, 1208, 1305 and 1333. */
        NORMAL(STATUS_NORMAL),

        /** Lines 1158, 1209, 1306 and 1334. */
        DUPLICATE(STATUS_DUPLICATE),

        /** Lines 1215 and 1233. */
        END_OF_FILE(STATUS_END_OF_FILE),

        /**
         * Lines 1222, 1246 and 1361: the {@code WHEN OTHER} clause of each browse evaluation, carried as
         * a named constant rather than as a Java {@code default} label for the reasons given above.
         */
        OTHER(STATUS_PERMANENT_ERROR);

        /** The two-character file status this condition normalises onto. */
        private final String status;

        /**
         * Binds the condition to the file status it normalises onto.
         *
         * @param status the two-character file status this condition normalises onto
         */
        BrowseResponse(final String status) {
            this.status = status;
        }

        /**
         * The file status this condition normalises onto, which is what the caller branches on.
         *
         * @return the two-character status this condition maps onto
         */
        private String getStatus() {
            return this.status;
        }

        /**
         * Reports whether a record was delivered, which the source expresses by handling its normal
         * and duplicate arms with one shared body.
         *
         * @return {@code true} for {@link #NORMAL} and {@link #DUPLICATE}
         */
        private boolean recordDelivered() {
            return this == NORMAL || this == DUPLICATE;
        }
    }

    // ==============================================================================================
    // The published shapes: what one turn of the screen is given, and what it reports.
    // ==============================================================================================

    /**
     * One transmitted card-list screen, together with the private state the legacy carried across the
     * pseudo-conversation in its own communication area at lines 229 to 248 of
     * {@code app/cbl/COCRDLIC.cbl}.
     *
     * <p>That private area is why this shape carries more than a client would naturally send. The
     * source keeps the two boundary keys, the page number, the last-page indicator and the next-page
     * indicator in storage that survives between turns because CICS hands the area back to it; a
     * stateless service has no such storage, so the client echoes them. Nothing is defaulted on the
     * server's behalf: an absent value means the legacy field held its initial value, which is what
     * the first entry to the screen actually looks like.
     *
     * @param attentionKeyIdentifier the raw terminal attention-key identifier, exactly as
     *     transmitted, for example {@code DFHPF8}. Resolved through the utility-layer translator and
     *     never decoded here. {@code null} when the turn carries no key
     * @param workArea the shared screen work area of {@code app/cpy/CVCRD01Y.cpy}, which the source
     *     copies at line 221. Its account and card fields are the two filters the receive paragraph
     *     stages at lines 969 to 970, and its action field is the value the key store last wrote -
     *     which matters, because an unrecognised key leaves that field untouched. May be {@code null}
     *     on a first entry, which is read as an unfiltered screen with no retained action
     * @param selections the seven transmitted selection fields in screen order, as the receive
     *     paragraph stages them one by one at lines 972 to 978. Shorter input is padded with blanks,
     *     which is what the legacy's untransmitted field holds; longer input is rejected outright,
     *     because a screen with more than seven rows is not this screen
     * @param pageCursor the two retained boundary keys and the direction the attention key implies.
     *     Each key is the 16-character card number alone - see the class documentation on why the
     *     account half of the legacy composite is dead - and both are opaque here. May be
     *     {@code null} on a first entry
     * @param currentPageNumber {@code WS-CA-SCREEN-NUM}, line 237. Zero on a first entry, which is
     *     the value the source's own initialisation leaves and the value its test at line 1177 looks
     *     for before raising the number to one
     * @param lastPageAlreadyShown {@code WS-CA-LAST-PAGE-DISPLAYED}, lines 239 to 241, echoed so the
     *     message rule at lines 905 to 916 can distinguish a first arrival at the end of the data
     *     from a repeated request past it
     * @param nextPageIndicated {@code WS-CA-NEXT-PAGE-IND}, lines 242 to 244, echoed because the
     *     forward-paging arm at line 486 tests it <em>before</em> the browse recomputes it
     * @param navigationContext the navigation state echoed by the client. An absent state is the
     *     zero-length communication area of line 315, which this screen answers by initialising
     *     itself rather than by routing away
     */
    public record CardListScreenInput(String attentionKeyIdentifier,
                                      ScreenInputState workArea,
                                      List<String> selections,
                                      BrowseWindow.CursorRequest pageCursor,
                                      int currentPageNumber,
                                      boolean lastPageAlreadyShown,
                                      boolean nextPageIndicated,
                                      ScreenNavigationState navigationContext) {

        /**
         * Normalises the selection list to exactly seven immutable entries and rejects a longer one.
         *
         * <p>Padding a short list with blanks reproduces the legacy field that the terminal did not
         * transmit, which reads as low values and therefore as blank. Rejecting a long list is
         * deliberate rather than defensive trimming: silently discarding an eighth selection would
         * hide a caller that believes it is driving a different screen, and the discarded value could
         * be the one the operator actually chose.
         *
         * @throws IllegalArgumentException if more than seven selections are supplied
         */
        public CardListScreenInput {
            selections = normalisedSelections(selections);
        }

        /**
         * Normalises the supplied selections onto exactly seven entries, the width {@code PAGE_SIZE} declares,
         * so that every slot has a position whether the terminal transmitted anything for it or not.
         *
         * @param supplied the selections as transmitted, which may be null, short or over-long
         * @return exactly seven entries in slot order, never null and never holding a null
         */
        private static List<String> normalisedSelections(final List<String> supplied) {
            if (supplied == null) {
                return Collections.nCopies(PAGE_SIZE, NO_MESSAGE);
            }
            if (supplied.size() > PAGE_SIZE) {
                throw new IllegalArgumentException("The card-list screen carries exactly " + PAGE_SIZE
                        + " selection fields, but " + supplied.size() + " were supplied.");
            }
            final List<String> normalised = new ArrayList<>(PAGE_SIZE);
            for (final String selection : supplied) {
                normalised.add(selection == null ? NO_MESSAGE : selection);
            }
            while (normalised.size() < PAGE_SIZE) {
                normalised.add(NO_MESSAGE);
            }
            return Collections.unmodifiableList(normalised);
        }

        /**
         * Returns the transmitted selection for one screen slot.
         *
         * @param screenSlot the 1-based slot, between 1 and seven inclusive
         * @return the transmitted field, never {@code null} and possibly blank
         * @throws IndexOutOfBoundsException if the slot is outside the seven the screen has
         */
        public String selectionAt(final int screenSlot) {
            if (screenSlot < 1 || screenSlot > PAGE_SIZE) {
                throw new IndexOutOfBoundsException("Screen slot " + screenSlot
                        + " is outside the " + PAGE_SIZE + " rows this screen carries.");
            }
            return this.selections.get(screenSlot - 1);
        }

        /**
         * Renders the input with the work area and both boundary cursors withheld.
         *
         * <p>The work area carries an account identifier and a card number, and each boundary cursor
         * <em>is</em> a card number, so a generated rendering would put cardholder data into any log
         * line, exception message or test-failure report that touched an instance. Only the paging
         * state and the raw key identifier are rendered, neither of which names a record.
         *
         * @return a diagnostic rendering carrying no cardholder data
         */
        @Override
        public String toString() {
            return "CardListScreenInput[attentionKeyIdentifier=" + this.attentionKeyIdentifier
                    + ", workArea=" + REDACTION_PLACEHOLDER
                    + ", selections=" + this.selections
                    + ", pageCursor=" + REDACTION_PLACEHOLDER
                    + ", currentPageNumber=" + this.currentPageNumber
                    + ", lastPageAlreadyShown=" + this.lastPageAlreadyShown
                    + ", nextPageIndicated=" + this.nextPageIndicated
                    + ", navigationContext=" + this.navigationContext
                    + "]";
        }
    }

    /**
     * The screen header, as {@code 1100-SCREEN-INIT} stamps it at lines 642 to 664.
     *
     * <p>Declared here and produced by the turn rather than assembled above this layer, for the same
     * reason the card-detail screen declares its own: the header is what one translated paragraph writes,
     * and splitting one paragraph's output across two layers would leave neither able to be checked
     * against the source. The two clock readings the source takes, at lines 645 and 652, are taken once
     * here; the source uses only the second, so the duplicate statement is documented rather than
     * reproduced.
     *
     * @param title01         {@code CCDA-TITLE01}, moved at line 647 at its catalogue width
     * @param title02         {@code CCDA-TITLE02}, moved at line 648 at its catalogue width
     * @param transactionName {@code TRNNAMEO}, moved at line 649
     * @param programName     {@code PGMNAMEO}, moved at line 650
     * @param currentDate     {@code CURDATEO} as {@code MM/DD/YY}, assembled at lines 654 to 658
     * @param currentTime     {@code CURTIMEO} as {@code HH:MM:SS}, assembled at lines 660 to 664
     */
    public record ScreenHeader(String title01,
                               String title02,
                               String transactionName,
                               String programName,
                               String currentDate,
                               String currentTime) {
    }

    /**
     * One row of the seven-row screen table declared at lines 252 to 260 of
     * {@code app/cbl/COCRDLIC.cbl}, carrying the three fields the browse stores at lines 1165 to 1171
     * on the forward path and 1338 to 1344 on the backward one.
     *
     * <p><strong>The screen slot is a component, not a list index.</strong> The backward browse fills
     * slots downward from the seventh, so a partial backward page leaves the low slots empty and
     * places its rows at the bottom of the screen. A caller that only had the list position could not
     * reconstruct that, and the source's screen-population paragraph at line 678 works slot by slot,
     * skipping a slot whose row is empty.
     *
     * @param screenSlot the 1-based position this row occupies on the screen, between 1 and seven
     * @param accountId {@code WS-ROW-ACCTNO}, line 258, 11 characters, exactly as the record carries
     *     it and never re-padded
     * @param cardNumber {@code WS-ROW-CARD-NUM}, line 259, 16 characters, likewise verbatim
     * @param cardActiveStatus {@code WS-ROW-CARD-STATUS}, line 260, the single status character taken
     *     straight from the record. Kept as text rather than as an enum so that a value outside the
     *     two the estate defines still reaches the screen unchanged, which is what the source's move
     *     does; {@link #resolvedStatus()} offers the typed view for a caller that wants one
     * @param selection the selection field echoed back into this row, as the source echoes it at
     *     lines 683, 692, 701, 710, 719, 729 and 738
     */
    public record CardListRow(int screenSlot,
                              String accountId,
                              String cardNumber,
                              String cardActiveStatus,
                              String selection) {

        /**
         * Rejects a slot outside the seven the screen has.
         *
         * @throws IllegalArgumentException if the slot is not between 1 and seven inclusive
         */
        public CardListRow {
            if (screenSlot < 1 || screenSlot > PAGE_SIZE) {
                throw new IllegalArgumentException("Screen slot " + screenSlot + " is outside the "
                        + PAGE_SIZE + " rows the card-list screen carries.");
            }
        }

        /**
         * Returns the typed view of the status character.
         *
         * @return the resolved status, or an empty {@link Optional} when the character is absent or is
         *         not one the estate defines
         */
        public Optional<CardStatus> resolvedStatus() {
            return this.cardActiveStatus == null
                    ? Optional.empty()
                    : CardStatus.fromCode(this.cardActiveStatus);
        }

        /**
         * Returns the action this row's echoed selection requests.
         *
         * @return the requested action, or an empty {@link Optional} when the row carries no
         *         selection or carries a character the screen does not accept
         */
        public Optional<SelectionAction> requestedAction() {
            return SelectionAction.fromSelection(this.selection);
        }

        /**
         * Renders the row with both business keys withheld.
         *
         * <p>The card number is a primary account number and the account identifier resolves to a
         * cardholder, so neither may reach a diagnostic channel. A partial mask was rejected: a
         * truncated primary account number is still cardholder data.
         *
         * @return a diagnostic rendering carrying no cardholder data
         */
        @Override
        public String toString() {
            return "CardListRow[screenSlot=" + this.screenSlot
                    + ", accountId=" + REDACTION_PLACEHOLDER
                    + ", cardNumber=" + REDACTION_PLACEHOLDER
                    + ", cardActiveStatus=" + this.cardActiveStatus
                    + ", selection=" + this.selection
                    + "]";
        }
    }

    /**
     * The outcome of one turn of the card-list screen.
     *
     * <p>A value, not a transport: it carries no HTTP status, no response entity and no rendering
     * decision, because the screen contract this reproduces is a field contract and the presentation
     * layer above owns everything else.
     *
     * <p><strong>The field-level error report is deliberately in two parts</strong>, because the
     * source keeps it in two parts and neither part can express the other. The bitmap is
     * <em>positional</em>: {@code WS-EDIT-SELECT-ERROR-FLAGS} at lines 83 to 88 of
     * {@code app/cbl/COCRDLIC.cbl} is a seven-character field whose position <em>is</em> the row, and
     * the assignment at lines 1088 to 1093 writes a cleared flag at every position that carries no
     * selection, so an empty position is a reported fact rather than an absence. The field-error list
     * is <em>state bearing</em>: the decoration rule at lines 751 to 759 distinguishes a field that
     * was not supplied, which is highlighted <em>and</em> marked, from one supplied wrongly, which is
     * only highlighted, and that is the two-state contract the list carries. A set of selected rows
     * would lose the first and a bitmap alone would lose the second.
     *
     * @param route the destination this turn resolved, always supplied. A turn that re-presents the
     *     same screen resolves to the card list itself, which is what the source's terminal return at
     *     lines 615 to 619 does by re-arming its own transaction
     * @param navigationContext the navigation state as this turn leaves it, ready to be echoed back
     * @param reArmedTransactionId the transaction the source re-arms at line 616, so the client knows
     *     which transaction its next call continues
     * @param header the header this turn stamped, being the whole output of {@code 1100-SCREEN-INIT}
     *     apart from the page indicator, which the paging state already carries. Always supplied,
     *     because the source stamps the header on every transmitted screen
     * @param rows the accepted card rows, at most seven and possibly none. In screen order in both
     *     paging directions: on the backward path this is the <em>reverse</em> of the read order,
     *     because the source reads descending and fills slots upward from the seventh. Unmodifiable
     *     and never {@code null}
     * @param pageMetadata the paging state, carrying both boundary cursors and the direction walked
     * @param infoMessage the advisory line the message paragraph maintains at lines 912, 919 and 928,
     *     at most 45 characters, the width {@code WS-INFO-MSG} declares at line 112. Blank when the
     *     screen shows none
     * @param errorMessage the summary message, at most 75 characters, the width {@code WS-ERROR-MSG}
     *     declares at line 117, in the wording the source authored. Blank when the turn raised none
     * @param selectionErrorFlags the positional bitmap: exactly seven entries, one per screen slot, in
     *     slot order, with a cleared entry at every slot that carries no error. Unmodifiable and never
     *     {@code null}
     * @param fieldErrors the state-bearing field-level detail in slot order, each entry naming its
     *     screen slot through its property and 3270 field names and carrying whether the field was
     *     missing or invalid. Unmodifiable, never {@code null}, and empty on a first entry because the
     *     re-enter gate means field detail can only arise on a re-submission
     * @param focusField the 3270 field the screen puts the cursor on, or {@code null} when the turn
     *     nominates none
     * @param errorFlag whether the turn raised an error, stated explicitly rather than inferred from
     *     the message text. It is the {@code WS-INPUT-FLAG} of lines 56 to 60 and it is what the
     *     source's own dispatch branches on at line 419
     * @param reEntry whether this turn was a re-submission of the same screen, which is the gate the
     *     source's decoration applies and therefore the gate that makes field detail conditional
     * @param selectedRowIndex {@code I-SELECTED} of lines 92 to 94: the 1-based slot the operator
     *     chose, or zero when none was chosen. Zero is the source's own initial value from line 1097
     * @param lastPageAlreadyShown {@code WS-CA-LAST-PAGE-DISPLAYED} as this turn leaves it, lines 239
     *     to 241. Reported because it is retained paging state that the next turn's message rule at
     *     lines 905 to 916 reads, and it is the one of the three retained paging fields that the paging
     *     state cannot express: the page indicator is its displayed page number and whether a further
     *     page exists is its more-pages flag, but whether the operator has <em>already been told</em>
     *     they are at the end is a memory of a previous turn and is derivable from nothing in this one
     */
    public record CardListResult(NavigationService.Route route,
                                 ScreenNavigationState navigationContext,
                                 String reArmedTransactionId,
                                 ScreenHeader header,
                                 List<CardListRow> rows,
                                 BrowseWindow pageMetadata,
                                 String infoMessage,
                                 String errorMessage,
                                 List<Boolean> selectionErrorFlags,
                                 List<ValidationException.FieldError> fieldErrors,
                                 String focusField,
                                 boolean errorFlag,
                                 boolean reEntry,
                                 int selectedRowIndex,
                                 boolean lastPageAlreadyShown) {

        /**
         * Replaces the three lists with unmodifiable copies and leaves every other component exactly
         * as supplied.
         *
         * <p>A {@code null} list becomes empty, except the bitmap, which becomes seven cleared entries
         * so that its positional contract holds unconditionally. Nothing else is defaulted, reordered,
         * shortened, extended, case folded or stripped of surrounding spaces, which on a fixed-width
         * space-filled screen field are part of what was displayed.
         */
        public CardListResult {
            rows = rows == null ? List.of() : List.copyOf(rows);
            selectionErrorFlags = selectionErrorFlags == null
                    ? Collections.nCopies(PAGE_SIZE, Boolean.FALSE)
                    : List.copyOf(selectionErrorFlags);
            fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        }

        /**
         * Reports whether the browse produced at least one row.
         *
         * @return {@code true} when the page carries a row
         */
        public boolean recordsFound() {
            return !this.rows.isEmpty();
        }

        /**
         * Returns the row the operator selected, if any.
         *
         * <p>Applies the guard the source omits. Its two dispatch arms at lines 517 and 545 subscript
         * the selection array with an index that is zero whenever nothing was selected, which the
         * condition name at line 94 exists to prevent and which the source does not consult. A
         * zero-subscript reference is undefined in COBOL and unrepresentable in Java, so the guard is
         * applied here and the omission is documented rather than imitated.
         *
         * @return the selected row, or an empty {@link Optional} when no slot was chosen or the chosen
         *         slot carries no row
         */
        public Optional<CardListRow> selectedRow() {
            if (this.selectedRowIndex < 1 || this.selectedRowIndex > PAGE_SIZE) {
                return Optional.empty();
            }
            for (final CardListRow row : this.rows) {
                if (row.screenSlot() == this.selectedRowIndex) {
                    return Optional.of(row);
                }
            }
            return Optional.empty();
        }

        /**
         * Reports whether one screen slot carries a selection error.
         *
         * @param screenSlot the 1-based slot, between 1 and seven inclusive
         * @return {@code true} when that slot is flagged
         * @throws IndexOutOfBoundsException if the slot is outside the seven the screen has
         */
        public boolean selectionErrorAt(final int screenSlot) {
            if (screenSlot < 1 || screenSlot > PAGE_SIZE) {
                throw new IndexOutOfBoundsException("Screen slot " + screenSlot
                        + " is outside the " + PAGE_SIZE + " rows this screen carries.");
            }
            return Boolean.TRUE.equals(this.selectionErrorFlags.get(screenSlot - 1));
        }
    }

    /**
     * The working storage of one turn, created on entry and discarded on exit.
     *
     * <p>This is what keeps the service a stateless singleton. The source's working storage is
     * program storage that CICS re-establishes per task; the equivalent here is a local object, so two
     * concurrent turns share nothing and the bean itself declares no mutable field. Every member below
     * names the source field it stands for, so a reviewer can check the translation field by field.
     */
    private static final class TurnState {

        /** {@code WS-INPUT-FLAG}, lines 56 to 60, cleared by the edit entry at line 986. */
        private boolean inputError;

        /** {@code WS-EDIT-ACCT-FLAG}, lines 61 to 64, initialised blank at line 1004. */
        private FilterFlag accountFilterFlag = FilterFlag.BLANK;

        /** {@code WS-EDIT-CARD-FLAG}, lines 65 to 68, initialised blank at line 1039. */
        private FilterFlag cardFilterFlag = FilterFlag.BLANK;

        /**
         * {@code WS-EDIT-SELECT-COUNTER}, lines 69 to 71 &mdash; the estate's single packed-decimal
         * declaration, and a transient screen counter that is never persisted. It is an ordinary
         * integer here: nothing reads or writes it as bytes, so no decoder is involved and none exists
         * anywhere in this module.
         */
        private int selectCounter;

        /**
         * {@code WS-EDIT-SELECT-FLAGS} and its seven-occurrence redefinition, lines 72 to 82: the
         * transmitted selection character of each slot, positionally.
         */
        private final String[] selectionFlags = new String[PAGE_SIZE];

        /**
         * {@code WS-EDIT-SELECT-ERROR-FLAGS} and its redefinition, lines 83 to 88: the positional
         * error bitmap. A cleared entry at a slot is a reported fact, not an absence, which is why the
         * array is sized to the screen and never compacted.
         */
        private final boolean[] selectionErrorFlags = new boolean[PAGE_SIZE];

        /** {@code I}, lines 90 to 91: the tally target at line 1080 and the loop index at line 1099. */
        private int tally;

        /** {@code I-SELECTED}, lines 92 to 94, cleared at line 1097. */
        private int selectedIndex;

        /** {@code FLG-PROTECT-SELECT-ROWS}, lines 105 to 107, cleared at line 987. */
        private boolean protectSelectRows;

        /** {@code WS-INFO-MSG}, lines 111 to 116, cleared at line 669. */
        private String infoMessage = NO_MESSAGE;

        /** {@code WS-ERROR-MSG}, lines 117 to 126, cleared at line 311. */
        private String errorMessage = NO_MESSAGE;

        /** {@code WS-PFK-FLAG}, lines 127 to 129, raised at line 370 and lowered at line 375. */
        private boolean pfKeyInvalid;

        /**
         * Whether the transmitted attention-key identifier matched no arm of the key store, which the
         * source records nowhere at all: its key store simply assigns nothing and the action field keeps
         * its previous value. Carried as its own flag so that the reported outcome can say so, and kept
         * separate from the usable-key flag above because the two are different facts &mdash; a key can be
         * perfectly well mapped and still be meaningless on this screen.
         */
        private boolean pfKeyUnmapped;

        /**
         * {@code WS-CARD-RID-CARDNUM}, line 138: the record identifier the browse positions on. Only
         * the card-number half of the source's composite key, because all four moves of the account
         * half are commented out.
         */
        private String ridCardNumber = NO_MESSAGE;

        /** {@code WS-SCRN-COUNTER}, line 145: the slot the browse is filling. */
        private int screenCounter;

        /** {@code WS-FILTER-RECORD-FLAG}, lines 147 to 149, set by the filter at line 1383. */
        private boolean excludeThisRecord;

        /** {@code WS-RECORDS-TO-PROCESS-FLAG}, lines 150 to 152, raised to end a read loop. */
        private boolean readLoopExit;

        /** {@code ERROR-OPNAME}, lines 156 to 157: the operation named in the file-error message. */
        private String errorOperationName = NO_MESSAGE;

        /** {@code ERROR-FILE}, lines 160 to 161: the resource named in the file-error message. */
        private String errorResourceName = NO_MESSAGE;

        /**
         * {@code ERROR-RESP}, lines 165 to 166. Carries the two-character status the failing condition
         * maps onto, because the CICS response number the source moves here has no equivalent once the
         * data store is relational.
         */
        private String errorResponse = NO_MESSAGE;

        /**
         * {@code CARD-RECORD} of {@code app/cpy/CVACT02Y.cpy}: the single record area every read
         * overwrites. Modelled as a field rather than as a local because the source's end-of-file arm at
         * lines 1236 to 1237 reads the key out of this area <em>after</em> the read that failed, so what
         * it retains is the last record read successfully.
         */
        private Card currentRecord;

        /** {@code CC-ACCT-ID} of the shared work area: the account filter as transmitted. */
        private String screenAccountId = NO_MESSAGE;

        /** {@code CC-CARD-NUM} of the shared work area: the card filter as transmitted. */
        private String screenCardNumber = NO_MESSAGE;

        /** {@code WS-CA-FIRST-CARD-NUM}, line 234: the key a backward browse restarts from. */
        private String caFirstCardNumber = NO_MESSAGE;

        /** {@code WS-CA-LAST-CARD-NUM}, line 231: the key a forward browse restarts from. */
        private String caLastCardNumber = NO_MESSAGE;

        /** {@code TITLE01O}, line 647: the first catalogue title the header carries. */
        private String title01 = NO_MESSAGE;

        /** {@code TITLE02O}, line 648: the second catalogue title the header carries. */
        private String title02 = NO_MESSAGE;

        /** {@code TRNNAMEO}, line 649: this transaction's own name, at its map width. */
        private String transactionName = NO_MESSAGE;

        /** {@code PGMNAMEO}, line 650: this member's own name, at its map width. */
        private String programName = NO_MESSAGE;

        /** {@code CURDATEO}, line 658: the header date as {@code MM/DD/YY}. */
        private String currentDate = NO_MESSAGE;

        /** {@code CURTIMEO}, line 664: the header time as {@code HH:MM:SS}. */
        private String currentTime = NO_MESSAGE;

        /** {@code WS-CA-SCREEN-NUM}, lines 237 to 238: the page indicator the screen displays. */
        private int caScreenNumber;

        /** {@code WS-CA-LAST-PAGE-DISPLAYED}, lines 239 to 241. */
        private boolean caLastPageShown;

        /** {@code WS-CA-NEXT-PAGE-IND}, lines 242 to 244. */
        private boolean caNextPageExists;

        /**
         * {@code WS-SCREEN-ROWS}, lines 252 to 260: the seven row slots, cleared to low values at
         * lines 1124 and 1266. A {@code null} slot is an empty row, which is exactly what the
         * population paragraph at line 680 tests for before it writes a row to the map.
         */
        private final CardListRow[] screenRows = new CardListRow[PAGE_SIZE];

        /**
         * {@code CCARD-AID} of the shared work area: the mapped attention key. {@code null} when no
         * key has ever been mapped, which is the state the source's low-value initialisation leaves.
         */
        private KeyAction keyAction;

        /** The navigation state as the turn builds it, standing for {@code CARDDEMO-COMMAREA}. */
        private ScreenNavigationState navigationContext = ScreenNavigationState.empty();

        /** The destination the dispatch resolves, standing for the transfer-control target. */
        private NavigationService.Route route;

        /** The 3270 field the cursor is placed on, from lines 770 to 828 and 872 to 886. */
        private String focusField;

        /** The state-bearing field-level detail, accumulated in slot order. */
        private final List<ValidationException.FieldError> fieldErrors = new ArrayList<>();

        /** Which way the browse walked, needed to build the paging metadata. */
        private BrowseWindow.PagingDirection direction = BrowseWindow.PagingDirection.FORWARD;

        /** Whether this turn is a re-submission of the same screen: the decoration gate. */
        private boolean reEntry;

        /**
         * Whether the source's own program name occupies the originating-program field, which is the
         * test at lines 337, 358, 385, 460, 519 and 547 and the gate on receiving input at all.
         */
        private boolean fromThisProgram;

        /**
         * Creates the working storage of one turn, with every field at the value the source's own
         * {@code WORKING-STORAGE SECTION} gives it before the procedure division runs.
         *
         * <p>Declared rather than left implicit so that the only way to obtain this storage is through the
         * turn that owns it. Nothing is carried between turns: the service holds no instance of this type,
         * which is what keeps it a stateless singleton.
         */
        private TurnState() {
            // Every field is initialised at its declaration, mirroring the source's VALUE clauses.
        }

        /**
         * Reports whether the message field is blank, the {@code WS-ERROR-MSG-OFF} test of line 118.
         *
         * @return {@code true} when the message field holds nothing but spaces
         */
        private boolean errorMessageOff() {
            return this.errorMessage == null || this.errorMessage.isEmpty();
        }

        /**
         * Reports whether the message field holds the more-than-one-action text. The source tests this
         * as a condition name on the message field itself at line 1103, so the message content really
         * is the flag and reproducing it any other way would change which rows are decorated.
         *
         * @return {@code true} when the message field holds the more-than-one-action text
         */
        private boolean moreThanOneAction() {
            return MSG_MORE_THAN_1_ACTION.equals(this.errorMessage);
        }

        /**
         * Reports whether the advisory field is blank, the {@code WS-NO-INFO-MESSAGE} test of line 113.
         *
         * @return {@code true} when the advisory field holds nothing but spaces
         */
        private boolean noInfoMessage() {
            return this.infoMessage == null || this.infoMessage.isEmpty();
        }

        /**
         * Reports whether the message field holds the no-records text, tested at line 927.
         *
         * @return {@code true} when the message field holds the no-records text
         */
        private boolean noRecordsFound() {
            return MSG_NO_RECORDS_FOUND.equals(this.errorMessage);
        }

        /**
         * {@code CA-FIRST-PAGE}, line 238: the page indicator holds one.
         *
         * @return {@code true} when the page indicator holds one
         */
        private boolean onFirstPage() {
            return this.caScreenNumber == 1;
        }

        /**
         * Reports whether the mapped key is the one supplied, without unwrapping a null.
         *
         * @param candidate the mapped key to test for
         * @return {@code true} when a key was mapped and it is the one supplied
         */
        private boolean keyIs(final KeyAction candidate) {
            return this.keyAction == candidate;
        }
    }

    // ==============================================================================================
    // Entry point: the procedure division, app/cbl/COCRDLIC.cbl line 297
    // ==============================================================================================

    /**
     * Runs one turn of the card-list screen.
     *
     * <p>Read-only by contract, and enforced as such: no write, no delete, no flush and no modifying
     * query appears anywhere in this class, and the transaction is declared read-only so that the
     * persistence provider need not track changes it will never be asked to make. An optimistic-lock
     * conflict is consequently unreachable here.
     *
     * <p>The turn completes normally on every path the legacy completes normally on. The one failure
     * it can raise is the abend the legacy transfer-control statement would have raised for a
     * destination that cannot be resolved; a browse failure is reported through the returned value
     * instead, because that is what the source does.
     *
     * @param input the transmitted screen, the raw attention key and the echoed navigation and paging
     *              state; must not be {@code null}
     * @return the outcome of the turn, never {@code null}
     * @throws NullPointerException if {@code input} is {@code null}
     */
    public CardListResult processCardList(final CardListScreenInput input) {
        Objects.requireNonNull(input, "input must not be null");

        final TurnState state = new TurnState();
        mainPara(state, input);

        return new CardListResult(
                state.route,
                state.navigationContext,
                LIT_THISTRANID,
                assembleScreenHeader(state),
                assembleRows(state),
                assembleBrowseWindow(state),
                state.infoMessage,
                state.errorMessage,
                assembleSelectionErrorFlags(state),
                state.fieldErrors,
                state.focusField,
                state.inputError,
                state.reEntry,
                state.selectedIndex,
                state.caLastPageShown);
    }

    // ==============================================================================================
    // Paragraph 0000-MAIN, line 298
    // ==============================================================================================

    /**
     * {@code 0000-MAIN}, lines 298 to 602: the whole turn, in the source's own order.
     *
     * <p>The main evaluation at line 418 is an {@code EVALUATE TRUE}, which is a cascade of unrelated
     * compound conditions rather than a selection on one value, so it is rendered as an ordered chain
     * whose last arm is the source's trailing arm. That is the construct that preserves the contract:
     * the source stops at the first arm that holds, several of its arms overlap, and a Java
     * {@code switch} cannot express arms such as "the backward key and not the first page". The
     * evaluations that do select on a single value &mdash; the browse response evaluations &mdash; are
     * rendered as switches with a trailing default, and are found in the two browse paragraphs.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen and the echoed navigation state
     */
    private void mainPara(final TurnState state, final CardListScreenInput input) {
        // Lines 300 to 302 initialise the work areas. The turn state is created empty, so what remains
        // is to stage the two fields the shared work area transmits and the action it last held: an
        // unrecognised key leaves that field untouched, so it has to start from what arrived.
        final ScreenInputState workArea = input.workArea();
        if (workArea != null) {
            state.keyAction = workArea.keyAction();
        }

        // Line 307 stores this transaction's own identifier; it is reported as the re-armed
        // transaction. Line 311 clears the message field.
        state.errorMessage = NO_MESSAGE;

        // Lines 315 to 332: a zero-length communication area is a first entry, and this screen answers
        // it by initialising itself rather than by routing away, which is why the shared
        // absent-context route is deliberately not consulted here.
        if (isNavigationStateAbsent(input.navigationContext())) {
            state.navigationContext = firstEntryContext();
            state.caScreenNumber = 1;
            state.caLastPageShown = false;
        } else {
            state.navigationContext = input.navigationContext();
            state.caScreenNumber = input.currentPageNumber();
            state.caLastPageShown = input.lastPageAlreadyShown();
            state.caNextPageExists = input.nextPageIndicated();
            state.caFirstCardNumber = previousCursorOf(input);
            state.caLastCardNumber = nextCursorOf(input);
        }
        state.reEntry = state.navigationContext.reEntry();
        state.fromThisProgram = namesThisProgram(state.navigationContext.fromProgram());

        // Lines 336 to 343: arriving from another program forgets the retained paging state.
        if (state.navigationContext.firstEntry() && !state.fromThisProgram) {
            state.caFirstCardNumber = NO_MESSAGE;
            state.caLastCardNumber = NO_MESSAGE;
            state.caNextPageExists = false;
            state.navigationContext = state.navigationContext.withFirstEntry();
            state.reEntry = false;
            state.caScreenNumber = 1;
            state.caLastPageShown = false;
        }

        // Lines 349 to 350: map and store the attention key.
        storePfKeyExpansion(state, input.attentionKeyIdentifier());

        // Lines 357 to 362: input is received and edited only when this screen is submitting to itself.
        if (!isNavigationStateAbsent(input.navigationContext())
                && state.fromThisProgram) {
            receiveMap(state, input);
        }

        // Lines 370 to 380: only four keys are meaningful here. Any other mapped key is coerced to the
        // enter key and carries no message of its own, which is a different outcome from a key the
        // translator could not map at all.
        state.pfKeyInvalid = !(state.keyIs(KeyAction.ENTER)
                || state.keyIs(KeyAction.PFK03)
                || state.keyIs(KeyAction.PFK07)
                || state.keyIs(KeyAction.PFK08));
        if (state.pfKeyInvalid) {
            state.keyAction = KeyAction.ENTER;
        }

        // The documented improvement on an identifier the key store could not map at all, applied here so
        // that the edits above cannot clear it. See applyUnmappedKeyOutcome.
        applyUnmappedKeyOutcome(state);

        // Lines 384 to 406: the exit key transfers control to the menu, which ends the turn. Line 395
        // nominates this screen's own map rather than the menu's, which is why the menu map literal
        // declared at lines 193 to 194 is never referenced; that oddity is a screen-name field and has
        // no effect on where control goes, so it is recorded rather than corrected.
        if (state.keyIs(KeyAction.PFK03) && state.fromThisProgram) {
            state.errorMessage = MSG_EXIT;
            dispatchToProgram(state, LIT_MENUPGM);
            return;
        }

        // Lines 410 to 414: any key other than the forward key clears the last-page indicator.
        if (!state.keyIs(KeyAction.PFK08)) {
            state.caLastPageShown = false;
        }

        // Lines 418 to 583: the ordered dispatch, which always completes the turn.
        //
        // Lines 583 to 601 of the source are unreachable and are therefore not translated into a
        // reachable branch here. Every arm of the evaluation ends in a forward jump to the return
        // label or in a transfer of control, including the trailing arm at line 582, so control cannot
        // fall past the end of the evaluation to the error re-presentation at line 586 or to the
        // assignment at line 600. Writing them as reachable Java would misrepresent the source and
        // would leave code no test could ever exercise; the finding is recorded in the decision log.
        dispatchAction(state);
    }

    /**
     * The ordered dispatch of lines 418 to 583.
     *
     * <p>Arm order is the contract: the source evaluates top down and stops at the first arm that
     * holds, several arms overlap, and the trailing arm is a genuine catch-all. The chain below is
     * written in the source's order and each comment carries the line of the arm it reproduces.
     *
     * <p>Lines 439 to 440 duplicate the condition of lines 444 to 445. A COBOL arm with no statements
     * of its own shares the following arm's body, so the duplicate is inert; it is noted rather than
     * written twice.
     *
     * <p>Every arm completes the turn, either by reaching the terminal return or by transferring
     * control, so this method has no fall-through outcome and returns nothing.
     *
     * @param state the turn's working storage
     */
    private void dispatchAction(final TurnState state) {
        // Arm at line 419: an input error re-presents the screen, browsing forward first but only when
        // neither key filter is the thing at fault.
        if (state.inputError) {
            state.navigationContext = screenIdentityContext(state.navigationContext, LIT_THISPGM);
            if (!state.accountFilterFlag.isNotOk() && !state.cardFilterFlag.isNotOk()) {
                readForward(state);
            }
            sendMap(state);
            terminalReturn(state);
            return;
        }

        // Arms at lines 439 and 444: the backward key while already on the first page re-reads the
        // same page forward from its retained first key, and the message paragraph explains why.
        if (state.keyIs(KeyAction.PFK07) && state.onFirstPage()) {
            state.ridCardNumber = state.caFirstCardNumber;
            readForward(state);
            sendMap(state);
            terminalReturn(state);
            return;
        }

        // Arms at lines 458 to 460: the exit key when it did not come from this screen, or a
        // re-submission that came from another program. Both start the screen afresh.
        if (state.keyIs(KeyAction.PFK03) || (state.reEntry && !state.fromThisProgram)) {
            state.navigationContext = firstEntryContext();
            state.reEntry = false;
            state.caScreenNumber = 1;
            state.caLastPageShown = false;
            state.caFirstCardNumber = NO_MESSAGE;
            state.caLastCardNumber = NO_MESSAGE;
            state.caNextPageExists = false;
            state.ridCardNumber = state.caFirstCardNumber;
            readForward(state);
            sendMap(state);
            terminalReturn(state);
            return;
        }

        // Arm at line 486: page down, from the retained last key, raising the page number first.
        if (state.keyIs(KeyAction.PFK08) && state.caNextPageExists) {
            state.ridCardNumber = state.caLastCardNumber;
            state.caScreenNumber = state.caScreenNumber + 1;
            readForward(state);
            sendMap(state);
            terminalReturn(state);
            return;
        }

        // Arm at line 501: page up from any page but the first, lowering the page number first and
        // walking the key sequence backward.
        if (state.keyIs(KeyAction.PFK07) && !state.onFirstPage()) {
            state.ridCardNumber = state.caFirstCardNumber;
            state.caScreenNumber = state.caScreenNumber - 1;
            readBackwards(state);
            sendMap(state);
            terminalReturn(state);
            return;
        }

        // Arms at lines 517 and 545: the enter key with a selection transfers control to the card
        // detail or card update screen. The guard on the selected index is the one the source omits.
        final Optional<SelectionAction> selected = selectedAction(state);
        if (state.keyIs(KeyAction.ENTER) && selected.isPresent() && state.fromThisProgram) {
            final SelectionAction action = selected.get();
            if (action.isViewRequested()) {
                dispatchToSelectedCard(state, LIT_CARDDTLPGM);
                return;
            }
            dispatchToSelectedCard(state, LIT_CARDUPDPGM);
            return;
        }

        // Trailing arm at line 572: browse forward from the retained first key.
        state.ridCardNumber = state.caFirstCardNumber;
        readForward(state);
        sendMap(state);
        terminalReturn(state);
    }

    // ==============================================================================================
    // Paragraph COMMON-RETURN, line 604, and paragraph 0000-MAIN-EXIT, line 621
    // ==============================================================================================

    /**
     * {@code COMMON-RETURN}, lines 604 to 619: stamps this screen's identity on the navigation state
     * and re-arms this transaction, so the next call from the client continues the same conversation.
     *
     * <p>The source concatenates its own private area behind the shared one at lines 609 to 612 before
     * returning both. Here the private area is reported as the paging metadata and the retained
     * cursors instead of being appended to an opaque buffer, which is the same state carried in a form
     * the client can echo.
     *
     * @param state the turn's working storage
     */
    private void commonReturn(final TurnState state) {
        state.navigationContext = screenIdentityContext(state.navigationContext, LIT_THISPGM);
        state.route = this.navigationService.resolveNominatedDestination(carriedState(state.navigationContext),
                NavigationService.Route.CARD_LIST);
    }

    /**
     * {@code 0000-MAIN-EXIT}, lines 621 to 623: the {@code EXIT.} statement that closes the procedure.
     *
     * <p>A documented no-op, and deliberately so. Inventing a body would add behaviour the estate does
     * not have, which is the same reasoning the action plan applies to the empty fee paragraph of
     * {@code app/cbl/CBACT04C.cbl}.
     *
     * <p>Unreachable in the legacy: the terminal return in {@code COMMON-RETURN} ends the CICS task,
     * so control never falls through to this label. It is reached here because a Java method return is
     * not a task termination, and this is the point in the procedure text where it sits.
     */
    private void mainParaExit() {
        // The COBOL EXIT statement transfers control to the end of its paragraph and does nothing else,
        // so a faithful translation has nothing to do. Left empty on purpose: every one of the
        // seventeen exit paragraphs below is written the same way, and a body invented for any of them
        // would be behaviour this estate does not have.
    }

    /**
     * The terminal return pair: {@code COMMON-RETURN} followed by the procedure's closing label.
     *
     * <p>Factored because the source reaches the pair from seven separate arms, each through a forward
     * jump to the return label. Keeping it in one place is what makes those seven arms read as the
     * jumps they are.
     *
     * @param state the turn's working storage
     */
    private void terminalReturn(final TurnState state) {
        commonReturn(state);
        mainParaExit();
    }

    // ==============================================================================================
    // Paragraph 1000-SEND-MAP, line 624, and its exit at line 639
    // ==============================================================================================

    /**
     * {@code 1000-SEND-MAP}, lines 624 to 636: the six paragraphs that assemble and transmit the
     * screen, performed in the source's order.
     *
     * <p>The order matters and is preserved exactly. The row decoration at line 629 runs before the
     * screen attributes at line 631, so an attribute the later paragraph sets wins; and the message
     * paragraph at line 633 runs after both, so a message either of them left standing survives into
     * the transmitted screen.
     *
     * <p>Four of the source's own dispatch sites perform this paragraph through <em>itself</em> rather
     * than through its exit label, at lines 436, 452, 480 and 580, while lines 495 and 511 name the
     * exit label. Because the exit paragraph does nothing, the two spellings are equivalent and the
     * distinction is recorded rather than reproduced.
     *
     * @param state the turn's working storage
     */
    private void sendMap(final TurnState state) {
        screenInit(state);
        screenArrayInit(state);
        setupArrayAttribs(state);
        setupScreenAttrs(state);
        setupMessage(state);
        sendScreen(state);
        sendMapExit();
    }

    /** {@code 1000-SEND-MAP-EXIT}, lines 639 to 641: the {@code EXIT.} statement. A documented no-op. */
    private void sendMapExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // Paragraph 1100-SCREEN-INIT, line 642, and its exit at line 674
    // ==============================================================================================

    /**
     * {@code 1100-SCREEN-INIT}, lines 642 to 671: clears the outbound map and stamps the header.
     *
     * <p>Every move the paragraph makes is reproduced. The two catalogue titles at lines 647 to 648, the
     * transaction and program names at lines 649 to 650 and the date and time assembled at lines 652 to
     * 664 are stamped into the turn's header, the page indicator moved at line 667 is reported as the
     * displayed page number, and the advisory message cleared at lines 669 to 671 is cleared here.
     * Clearing the advisory is load bearing, because the message paragraph's own arm at line 917 tests
     * whether it is still blank.
     *
     * <p>The header is produced here rather than above this layer because it is the output of one
     * translated paragraph, and a paragraph whose output is split across two layers can be checked
     * against the source from neither. The card-detail screen translates the same paragraph the same way,
     * so the two card screens stamp their headers by one mechanism rather than two. The clock is injected
     * for the same reason it is there, which is that a rendered timestamp has to be reproducible under
     * test.
     *
     * <p>The source reads the current date twice, at lines 645 and 652, and uses only the second reading.
     * One reading is taken here: a second would be a redundant call on the same clock and could differ
     * from the first across a second boundary, which the legacy could equally suffer. The duplicate
     * statement is documented rather than reproduced.
     *
     * @param state the turn's working storage
     */
    private void screenInit(final TurnState state) {
        // MOVE CCDA-TITLE01 and CCDA-TITLE02 at lines 647 to 648, at their catalogue widths.
        state.title01 = this.messageCatalogService.screenTitle01();
        state.title02 = this.messageCatalogService.screenTitle02();

        // MOVE LIT-THISTRANID and LIT-THISPGM at lines 649 to 650.
        state.transactionName = headerField(LIT_THISTRANID, TRANSACTION_NAME_WIDTH);
        state.programName = headerField(LIT_THISPGM, PROGRAM_NAME_WIDTH);

        // MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA at line 652.
        final LocalDateTime now = LocalDateTime.now(this.clock);
        final String fullYear = headerNumber(now.getYear(), HEADER_FULL_YEAR_WIDTH);

        // Lines 654 to 658: MM/DD/YY, with the year taken as WS-CURDATE-YEAR(3:2) at line 656.
        state.currentDate = headerNumber(now.getMonthValue(), HEADER_PART_WIDTH)
                + HEADER_DATE_SEPARATOR
                + headerNumber(now.getDayOfMonth(), HEADER_PART_WIDTH)
                + HEADER_DATE_SEPARATOR
                + fullYear.substring(HEADER_YEAR_FROM, HEADER_YEAR_TO);

        // Lines 660 to 664: HH:MM:SS.
        state.currentTime = headerNumber(now.getHour(), HEADER_PART_WIDTH)
                + HEADER_TIME_SEPARATOR
                + headerNumber(now.getMinute(), HEADER_PART_WIDTH)
                + HEADER_TIME_SEPARATOR
                + headerNumber(now.getSecond(), HEADER_PART_WIDTH);

        state.infoMessage = NO_MESSAGE;
        screenInitExit();
    }

    /**
     * Renders a header literal at the width its map field declares, as a fixed-width move does.
     *
     * <p>A shorter value is space filled on the right and a longer one truncated on the right, which is
     * what a {@code MOVE} into an alphanumeric field of that width does. Neither case arises for the two
     * literals this screen moves - both are already exactly their field's width - so this bounds the
     * values rather than transforming them, and it is here so that a renamed literal cannot silently
     * change a header field's width.
     *
     * @param value the literal being moved, never {@code null} at any call site here
     * @param width the receiving field's declared width
     * @return the value at exactly {@code width} characters
     */
    private static String headerField(final String value, final int width) {
        if (value.length() == width) {
            return value;
        }
        if (value.length() > width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Renders one numeric header part zero filled on the left, as a move into {@code PIC 9(n)} does.
     *
     * @param value the reading taken from the clock, never negative at any call site here
     * @param width the receiving field's declared width
     * @return the value at exactly {@code width} digits
     */
    private static String headerNumber(final int value, final int width) {
        return CobolStringUtils.rightJustifyZeroFill(Integer.toString(Math.abs(value)), width);
    }

    /**
     * The header the turn stamped, gathered out of the working storage the init paragraph wrote.
     *
     * @param state the turn's working storage
     * @return the header, never {@code null}
     */
    private static ScreenHeader assembleScreenHeader(final TurnState state) {
        return new ScreenHeader(state.title01, state.title02, state.transactionName,
                state.programName, state.currentDate, state.currentTime);
    }

    /** {@code 1100-SCREEN-INIT-EXIT}, lines 674 to 676: the {@code EXIT.} statement. A documented no-op. */
    private void screenInitExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // Paragraph 1200-SCREEN-ARRAY-INIT, line 678, and its exit at line 745
    // ==============================================================================================

    /**
     * {@code 1200-SCREEN-ARRAY-INIT}, lines 678 to 742: writes each populated row to the map, slot by
     * slot, and skips a slot whose row is empty.
     *
     * <p>The source spells the same four moves out seven times, once per slot, and its own comment at
     * line 679 observes that the repetition could be collapsed. It is collapsed here into one loop over
     * the slots, which changes nothing: the seven expansions are identical apart from the slot index,
     * and each is guarded by the same emptiness test at lines 680, 689, 698, 707, 716, 726 and 735.
     *
     * <p>The emptiness test is why a row slot rather than a list position is the row's identity. A
     * partial page reached by walking backward leaves the low slots empty, and this paragraph writes
     * nothing to them, so the rows appear at the bottom of the screen.
     *
     * @param state the turn's working storage
     */
    private void screenArrayInit(final TurnState state) {
        for (int slot = 1; slot <= PAGE_SIZE; slot++) {
            final CardListRow row = state.screenRows[slot - 1];
            if (row == null) {
                continue;
            }
            // Lines 683, 692, 701, 710, 719, 729 and 738 echo the transmitted selection back into the
            // row, so the operator sees what they typed alongside the record it applied to.
            state.screenRows[slot - 1] = new CardListRow(
                    row.screenSlot(),
                    row.accountId(),
                    row.cardNumber(),
                    row.cardActiveStatus(),
                    state.selectionFlags[slot - 1] == null
                            ? NO_MESSAGE
                            : state.selectionFlags[slot - 1]);
        }
        screenArrayInitExit();
    }

    /**
     * {@code 1200-SCREEN-ARRAY-INIT-EXIT}, lines 745 to 747: the {@code EXIT.} statement. A documented
     * no-op.
     */
    private void screenArrayInitExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // Paragraph 1250-SETUP-ARRAY-ATTRIBS, line 748, and its exit at line 834
    // ==============================================================================================

    /**
     * {@code 1250-SETUP-ARRAY-ATTRIBS}, lines 748 to 831: decorates each selection field, which is
     * where the two-state field-level error contract comes from.
     *
     * <p>The rule per slot, from the first expansion at lines 751 to 762:
     *
     * <ul>
     *   <li>an empty row, or a screen whose rows are protected, protects the field and reports no
     *       error at all &mdash; a protected field cannot have been mistyped;</li>
     *   <li>otherwise, a raised error flag highlights the field, and the field is additionally
     *       <em>marked</em> when its selection is blank.</li>
     * </ul>
     *
     * <p>Highlighted and marked is the missing state; highlighted alone is the invalid state. That is
     * the whole of the mapping, and it is taken from the source rather than assumed.
     *
     * <p><strong>The marker is written for the first slot only.</strong> Lines 757 to 758 write it;
     * slots two to seven, at lines 768 to 771, 780 to 783, 792 to 795, 803 to 806, 815 to 818 and 826
     * to 829, position the cursor instead and have no marker branch at all. So the first slot can
     * report either state while slots two to seven can only ever report the invalid one. The
     * inconsistency is the source's and is reproduced, not smoothed over. The first slot likewise never
     * positions the cursor, and the protect attribute it uses at line 753 differs from the one slots
     * two to seven use.
     *
     * <p><strong>The missing state is nonetheless unreachable through this member's own edits</strong>,
     * and that is worth stating so a reader does not go looking for the path. The bitmap assignment at
     * lines 1088 to 1093 clears the flag at every slot that carries no selection, and the blank arm of
     * the row evaluation at line 1106 does nothing, so no blank slot ever carries a raised flag. Both
     * rules are reproduced as written and the unreachability is left as the emergent property it is,
     * rather than being hard-coded into a single-state contract that a change to either rule would
     * silently falsify.
     *
     * <p>A stray orphan operand sits at line 790 inside the fourth slot's protect branch. It has no
     * effect and no translation.
     *
     * @param state the turn's working storage
     */
    private void setupArrayAttribs(final TurnState state) {
        for (int slot = 1; slot <= PAGE_SIZE; slot++) {
            final boolean emptyRow = state.screenRows[slot - 1] == null;
            if (emptyRow || state.protectSelectRows) {
                continue;
            }
            if (!state.selectionErrorFlags[slot - 1]) {
                continue;
            }
            final String selection = state.selectionFlags[slot - 1];
            final boolean blankSelection = isBlankScreenField(selection);
            final boolean markerWritten = slot == 1 && blankSelection;
            state.fieldErrors.add(new ValidationException.FieldError(
                    PROPERTY_SELECTION_STEM + slot,
                    BMS_FIELD_SELECTION_STEM + slot,
                    markerWritten
                            ? ValidationException.FieldState.MISSING
                            : ValidationException.FieldState.INVALID,
                    state.errorMessage));
            if (slot != 1) {
                state.focusField = BMS_FIELD_SELECTION_STEM + slot;
            }
        }
        setupArrayAttribsExit();
    }

    /**
     * {@code 1250-SETUP-ARRAY-ATTRIBS-EXIT}, lines 834 to 836: the {@code EXIT.} statement. A
     * documented no-op.
     */
    private void setupArrayAttribsExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // Paragraph 1300-SETUP-SCREEN-ATTRS, line 837, and its exit at line 890
    // ==============================================================================================

    /**
     * {@code 1300-SETUP-SCREEN-ATTRS}, lines 837 to 887: echoes the two key filters back to the screen
     * and positions the cursor.
     *
     * <p>The two echo evaluations at lines 844 to 854 and 856 to 867 decide <em>what text</em> each
     * filter field shows and with which attribute, which is presentation state owned by the response
     * mapper. Both are {@code EVALUATE TRUE} cascades whose first two arms share one body: a filter
     * that is either valid or not-OK is echoed as the operator typed it, an absent identifier clears
     * the field, and anything else echoes the identifier the navigation state carries. Neither
     * evaluation affects the turn's outcome, so neither is reproduced here beyond this note.
     *
     * <p>What is reproduced is the cursor, at lines 872 to 886, because the field the screen focuses is
     * part of the reported outcome:
     *
     * <ul>
     *   <li>a not-OK account filter focuses the account field, lines 872 to 875;</li>
     *   <li>a not-OK card filter focuses the card field, lines 877 to 880;</li>
     *   <li>and when no input error stands at all, the account field is focused, lines 884 to 886.</li>
     * </ul>
     *
     * <p>The first two are written as separate tests in the source, not as alternatives, so both fire
     * when both filters are bad and two fields carry the focus marker. The account field is first in
     * the map, so it is the one the terminal focuses; that is why the account filter wins here.
     *
     * <p>This paragraph runs <em>after</em> the row decoration, so in principle it could overwrite the
     * focus that decoration set. It cannot in practice, and the reason is structural rather than
     * incidental: the array edit at lines 1075 to 1077 returns immediately when either filter has
     * already failed, so a slot error and a filter error cannot both stand, and the no-error arm cannot
     * fire while a slot error stands either. The ordering is preserved anyway, because relying on the
     * exclusion rather than on the order would break the moment the exclusion changed.
     *
     * @param state the turn's working storage
     */
    private void setupScreenAttrs(final TurnState state) {
        if (state.accountFilterFlag.isNotOk()) {
            state.focusField = BMS_FIELD_ACCOUNT_FILTER;
        }
        if (state.cardFilterFlag.isNotOk() && !state.accountFilterFlag.isNotOk()) {
            state.focusField = BMS_FIELD_CARD_FILTER;
        }
        if (!state.inputError) {
            state.focusField = BMS_FIELD_ACCOUNT_FILTER;
        }
        setupScreenAttrsExit();
    }

    /**
     * {@code 1300-SETUP-SCREEN-ATTRS-EXIT}, lines 890 to 892: the {@code EXIT.} statement. A documented
     * no-op.
     */
    private void setupScreenAttrsExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // Paragraph 1400-SETUP-MESSAGE, line 895, and its exit at line 933
    // ==============================================================================================

    /**
     * {@code 1400-SETUP-MESSAGE}, lines 895 to 930: settles which message the screen carries.
     *
     * <p>An {@code EVALUATE TRUE} cascade, so an ordered chain, and the order is the contract because
     * several arms overlap. Written in the source's order, arm by arm:
     *
     * <ol>
     *   <li>lines 898 to 900: a not-OK account or card filter leaves the message exactly as the edit
     *       composed it. Two arms sharing one body, and the body is to do nothing;</li>
     *   <li>lines 901 to 904: the backward key on the first page explains that there is nothing
     *       before it;</li>
     *   <li>lines 905 to 909: the forward key with no further page, on a screen that has already shown
     *       the last page, explains that there is nothing after it;</li>
     *   <li>lines 910 to 916: the forward key with no further page, reached for the first time, shows
     *       the advisory instead and records that the last page has now been shown;</li>
     *   <li>lines 917 to 919: no advisory yet, or a further page exists, shows the advisory. Two arms
     *       sharing one body;</li>
     *   <li>line 920: anything else clears the advisory.</li>
     * </ol>
     *
     * <p>The advisory is then copied to the screen at lines 926 to 930, but only when there is one
     * <em>and</em> the message is not the no-records text &mdash; the source suppresses the "type S for
     * detail" advice on an empty result, which would otherwise invite the operator to select a row that
     * is not there.
     *
     * @param state the turn's working storage
     */
    private void setupMessage(final TurnState state) {
        if (state.accountFilterFlag.isNotOk() || state.cardFilterFlag.isNotOk()) {
            // Arms one and two, lines 898 to 900: the source continues, so nothing happens. The message
            // the edit composed stands, and the advisory stays as the screen-init paragraph left it, which
            // is blank. Written as an arm of its own rather than folded into the trailing arm, because
            // folding it would clear a message on a path where the source clears nothing.
            LOG.trace("Card list message left as the key-filter edit composed it: program={} "
                    + "accountFilter={} cardFilter={}",
                    LIT_THISPGM, state.accountFilterFlag, state.cardFilterFlag);
        } else if (state.keyIs(KeyAction.PFK07) && state.onFirstPage()) {
            state.errorMessage = MSG_NO_PREVIOUS_PAGES;
        } else if (state.keyIs(KeyAction.PFK08) && !state.caNextPageExists && state.caLastPageShown) {
            state.errorMessage = MSG_NO_MORE_PAGES;
        } else if (state.keyIs(KeyAction.PFK08) && !state.caNextPageExists) {
            state.infoMessage = MSG_INFORM_REC_ACTIONS;
            state.caLastPageShown = true;
        } else if (state.noInfoMessage() || state.caNextPageExists) {
            state.infoMessage = MSG_INFORM_REC_ACTIONS;
        } else {
            state.infoMessage = NO_MESSAGE;
        }

        // Lines 926 to 930: the advisory reaches the screen only when it exists and the message is not
        // the no-records text.
        if (state.noRecordsFound()) {
            state.infoMessage = NO_MESSAGE;
        }
        setupMessageExit();
    }

    /**
     * {@code 1400-SETUP-MESSAGE-EXIT}, lines 933 to 935: the {@code EXIT.} statement. A documented
     * no-op.
     */
    private void setupMessageExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // Paragraph 1500-SEND-SCREEN, line 938, and its exit at line 948
    // ==============================================================================================

    /**
     * {@code 1500-SEND-SCREEN}, lines 938 to 946: transmits the assembled map.
     *
     * <p>The transmission itself has no analogue here, because the assembled screen leaves this layer
     * as the returned value rather than as a terminal write. What the paragraph does have is an
     * observable moment &mdash; the point at which the screen is final &mdash; and that is what is
     * recorded, at debug level and without a single record field, so a diagnostic reader can see the
     * turn complete and how many rows it settled on without any cardholder data reaching the log.
     *
     * <p>The source captures the transmission's response code at line 944 and never tests it, so
     * nothing branches on the outcome here either.
     *
     * @param state the turn's working storage
     */
    private void sendScreen(final TurnState state) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Card list screen assembled: program={} transaction={} rows={} pageSize={} "
                            + "page={} direction={} errorFlag={} nextPage={}",
                    LIT_THISPGM, LIT_THISTRANID, populatedRowCount(state), PAGE_SIZE,
                    state.caScreenNumber, state.direction, state.inputError, state.caNextPageExists);
        }
        sendScreenExit();
    }

    /**
     * {@code 1500-SEND-SCREEN-EXIT}, lines 948 to 950: the {@code EXIT.} statement. A documented no-op.
     */
    private void sendScreenExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // Paragraph 2000-RECEIVE-MAP, line 951, and its exit at line 959
    // ==============================================================================================

    /**
     * {@code 2000-RECEIVE-MAP}, lines 951 to 956: reads the transmitted screen and then edits it, in
     * that order.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen and the echoed navigation state
     */
    private void receiveMap(final TurnState state, final CardListScreenInput input) {
        receiveScreen(state, input);
        editInputs(state);
        receiveMapExit();
    }

    /** {@code 2000-RECEIVE-MAP-EXIT}, lines 959 to 961: the {@code EXIT.} statement. A documented no-op. */
    private void receiveMapExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // Paragraph 2100-RECEIVE-SCREEN, line 962, and its exit at line 981
    // ==============================================================================================

    /**
     * {@code 2100-RECEIVE-SCREEN}, lines 962 to 978: stages the transmitted fields into working
     * storage.
     *
     * <p>Two filters at lines 969 to 970 and the seven selection fields at lines 972 to 978, moved one
     * by one into the seven-occurrence table. Every value is staged exactly as transmitted: nothing is
     * trimmed, padded, re-cased or normalised, because on a fixed-width space-filled screen field the
     * surrounding spaces are part of what the operator sent and the edits that follow test for them.
     *
     * <p>The response code of the receive is captured at line 966 and never tested, so nothing branches
     * on it here either.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen and the echoed navigation state
     */
    private void receiveScreen(final TurnState state, final CardListScreenInput input) {
        final ScreenInputState workArea = input.workArea();
        state.screenAccountId = workArea == null || workArea.accountId() == null
                ? NO_MESSAGE
                : workArea.accountId();
        state.screenCardNumber = workArea == null || workArea.cardNumber() == null
                ? NO_MESSAGE
                : workArea.cardNumber();
        for (int slot = 1; slot <= PAGE_SIZE; slot++) {
            state.selectionFlags[slot - 1] = input.selectionAt(slot);
        }
        receiveScreenExit();
    }

    /**
     * {@code 2100-RECEIVE-SCREEN-EXIT}, lines 981 to 983: the {@code EXIT.} statement. A documented
     * no-op.
     */
    private void receiveScreenExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // Paragraph 2200-EDIT-INPUTS, line 985, and its exit at line 999
    // ==============================================================================================

    /**
     * {@code 2200-EDIT-INPUTS}, lines 985 to 996: clears the input flags and runs the three edits in
     * the source's order.
     *
     * <p>The order is load bearing rather than cosmetic. The account edit runs first and assigns its
     * message unconditionally; the card edit runs second and assigns its own message only when the
     * field is still blank, so an account failure keeps the message when both filters are bad; and the
     * array edit runs last and returns immediately when either of the first two has already failed, so
     * a filter error and a slot error can never both stand.
     *
     * @param state the turn's working storage
     */
    private void editInputs(final TurnState state) {
        state.inputError = false;
        state.protectSelectRows = false;
        editAccount(state);
        editCard(state);
        editArray(state);
        editInputsExit();
    }

    /** {@code 2200-EDIT-INPUTS-EXIT}, lines 999 to 1001: the {@code EXIT.} statement. A documented no-op. */
    private void editInputsExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // Paragraph 2210-EDIT-ACCOUNT, line 1003, and its exit at line 1032
    // ==============================================================================================

    /**
     * {@code 2210-EDIT-ACCOUNT}, lines 1003 to 1029: edits the account filter.
     *
     * <p>Three outcomes, in the source's order:
     *
     * <ol>
     *   <li>lines 1007 to 1013 &mdash; not supplied. The field holds low values, or all spaces, or its
     *       numeric redefinition reads as all zeros. All three are blank, and the third is easy to miss:
     *       an eleven-digit filter of zeros is treated as no filter at all. The navigation state's
     *       account identifier is zeroed and the edit ends;</li>
     *   <li>lines 1017 to 1025 &mdash; supplied but not the eleven digits the field holds. The error
     *       flag rises, the filter is marked not-OK, the row selections are protected so the operator
     *       cannot act on a list built from a bad filter, and the message is assigned
     *       unconditionally;</li>
     *   <li>lines 1026 to 1029 &mdash; supplied and well formed, so the filter is valid and will
     *       participate in the post-retrieval filter.</li>
     * </ol>
     *
     * <p>The class test is the COBOL numeric class condition on an eleven-character alphanumeric field,
     * so every one of the eleven positions must hold a digit and a shorter value fails because the
     * remainder is spaces. It is written out here rather than delegated: the character-handling utility
     * offers an unsupplied test that also accepts a decoration marker and a numeric test that accepts
     * the fuller argument grammar of another screen, and either would let this filter through input the
     * source rejects. Digits are matched as the character range rather than through a Unicode digit
     * predicate, because the class condition recognises the character set's own digits and a
     * locale-dependent predicate would accept characters the screen cannot send.
     *
     * @param state the turn's working storage
     */
    private void editAccount(final TurnState state) {
        state.accountFilterFlag = FilterFlag.BLANK;

        if (isUnsuppliedKeyFilter(state.screenAccountId, ACCOUNT_FILTER_WIDTH)) {
            state.navigationContext = withAccountIdentifier(state.navigationContext, NO_MESSAGE);
            editAccountExit();
            return;
        }

        if (!isFixedWidthAllDigits(state.screenAccountId, ACCOUNT_FILTER_WIDTH)) {
            state.inputError = true;
            state.accountFilterFlag = FilterFlag.NOT_OK;
            state.protectSelectRows = true;
            state.errorMessage = MSG_ACCOUNT_FILTER_FORMAT;
            state.navigationContext = withAccountIdentifier(state.navigationContext, NO_MESSAGE);
            editAccountExit();
            return;
        }

        state.navigationContext = withAccountIdentifier(state.navigationContext, state.screenAccountId);
        state.accountFilterFlag = FilterFlag.VALID;
        editAccountExit();
    }

    /**
     * {@code 2210-EDIT-ACCOUNT-EXIT}, lines 1032 to 1034: the {@code EXIT.} statement. A documented
     * no-op, and the target of the two forward jumps at lines 1012 and 1025.
     */
    private void editAccountExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // Paragraph 2220-EDIT-CARD, line 1036, and its exit at line 1069
    // ==============================================================================================

    /**
     * {@code 2220-EDIT-CARD}, lines 1036 to 1066: edits the card filter, with the same three outcomes
     * as the account filter at the sixteen-character width of its own field.
     *
     * <p>One difference is deliberate and is the reason the two edits are not shared. The account edit
     * assigns its message unconditionally, while lines 1056 to 1060 guard this one behind the message
     * field still being blank, so when both filters are bad the operator is told about the account
     * filter and not the card filter. Sharing the two edits would have to choose one behaviour and
     * would silently change the other.
     *
     * @param state the turn's working storage
     */
    private void editCard(final TurnState state) {
        state.cardFilterFlag = FilterFlag.BLANK;

        if (isUnsuppliedKeyFilter(state.screenCardNumber, CARD_FILTER_WIDTH)) {
            state.navigationContext = withCardNumber(state.navigationContext, NO_MESSAGE);
            editCardExit();
            return;
        }

        if (!isFixedWidthAllDigits(state.screenCardNumber, CARD_FILTER_WIDTH)) {
            state.inputError = true;
            state.cardFilterFlag = FilterFlag.NOT_OK;
            state.protectSelectRows = true;
            if (state.errorMessageOff()) {
                state.errorMessage = MSG_CARD_FILTER_FORMAT;
            }
            state.navigationContext = withCardNumber(state.navigationContext, NO_MESSAGE);
            editCardExit();
            return;
        }

        state.navigationContext = withCardNumber(state.navigationContext, state.screenCardNumber);
        state.cardFilterFlag = FilterFlag.VALID;
        editCardExit();
    }

    /**
     * {@code 2220-EDIT-CARD-EXIT}, lines 1069 to 1071: the {@code EXIT.} statement. A documented no-op,
     * and the target of the two forward jumps at lines 1047 and 1062.
     */
    private void editCardExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // Paragraph 2250-EDIT-ARRAY, line 1073, and its exit at line 1119
    // ==============================================================================================

    /**
     * {@code 2250-EDIT-ARRAY}, lines 1073 to 1116: edits the seven selection fields.
     *
     * <p>Lines 1075 to 1077 leave immediately when either key filter has already failed, which is what
     * makes a filter error and a slot error mutually exclusive throughout this class.
     *
     * <p><strong>The tally, lines 1079 to 1084.</strong> The source counts occurrences of both accepted
     * selection characters across the whole seven-character field in one statement and then rejects a
     * count above one. It is an explicit count here, over the slots in order. When the count exceeds
     * one the error flag rises and the message becomes the source's own text, unchanged.
     *
     * <p>Where this class is deliberately more informative than the source: it <strong>names every
     * offending slot</strong> rather than silently proceeding with one of them. The slots are named in
     * two places a caller can assert on &mdash; the positional bitmap raises a flag at each of them, and
     * the field-level detail carries one entry per offending slot naming its property and its 3270 field
     * &mdash; and they are also recorded in a structured diagnostic. The screen message and the
     * rejection are unchanged, so behaviour is identical; only the reporting is richer.
     *
     * <p><strong>The bitmap, lines 1088 to 1093.</strong> The source copies the selection field over the
     * error field and then rewrites it in place: each of the two accepted characters becomes a raised
     * flag and <em>every other character</em>, blanks included, becomes a cleared one. It is positional,
     * so it is translated as a positional array aligned to the seven slots. A cleared entry is a reported
     * fact, not an absence: it says that slot carried no selection. A set of selected slots would lose
     * exactly that, which is why none is used and why the array is never compacted.
     *
     * <p>The bitmap is also the reason the missing field state is unreachable through this member: it
     * clears the flag at every blank slot, and the blank arm below does nothing, so no blank slot ever
     * carries a raised flag for the decoration paragraph to mark.
     *
     * <p><strong>The row walk, lines 1099 to 1115.</strong> A three-way classification of one value, so
     * it is a {@code switch} over the three condition names the source tests, with its arms written in
     * the source's order and every constant covered. The accepted arm records the chosen slot and,
     * <em>only</em> when the message already holds the more-than-one text, raises that slot's flag; the
     * source tests the message content itself at line 1103, so the message really is the flag and
     * reproducing it any other way would change which slots are decorated. The blank arm does nothing.
     * The trailing arm raises the error flag and that slot's flag, and assigns the invalid-action message
     * only when the message field is still blank, so a more-than-one-action message already standing is
     * not overwritten.
     *
     * <p>The chosen slot is assigned on every accepted iteration, so with more than one selection it ends
     * as the last accepted slot rather than the first. That is the source's own outcome and is harmless,
     * because the error flag it raised sends the dispatch to its first arm before any selection is acted
     * on.
     *
     * @param state the turn's working storage
     */
    private void editArray(final TurnState state) {
        // Lines 1075 to 1077.
        if (state.inputError) {
            editArrayExit();
            return;
        }

        // Lines 1079 to 1082: the tally, and the slots it counted.
        final List<Integer> selectedSlots = new ArrayList<>(PAGE_SIZE);
        for (int slot = 1; slot <= PAGE_SIZE; slot++) {
            if (SelectionAction.fromSelection(state.selectionFlags[slot - 1]).isPresent()) {
                selectedSlots.add(slot);
            }
        }
        state.tally = selectedSlots.size();
        state.selectCounter = state.tally;

        // Lines 1084 to 1095: more than one action per page is refused.
        if (state.tally > 1) {
            state.inputError = true;
            state.errorMessage = MSG_MORE_THAN_1_ACTION;

            // Lines 1088 to 1093: the positional bitmap. Every slot is written, so the cleared entries
            // survive at their own positions.
            for (int slot = 1; slot <= PAGE_SIZE; slot++) {
                state.selectionErrorFlags[slot - 1] =
                        SelectionAction.fromSelection(state.selectionFlags[slot - 1]).isPresent();
            }

            LOG.warn("Card list refused more than one selection: program={} transaction={} rule={} "
                            + "selectionCount={} offendingSlots={} pageSize={}",
                    LIT_THISPGM, LIT_THISTRANID, "one-action-per-page", state.tally, selectedSlots,
                    PAGE_SIZE);
        }

        // Line 1097.
        state.selectedIndex = 0;

        // Lines 1099 to 1115.
        for (int slot = 1; slot <= PAGE_SIZE; slot++) {
            switch (classifySelection(state.selectionFlags[slot - 1])) {
                case ACCEPTED -> {
                    // Arm at line 1101.
                    state.selectedIndex = slot;
                    if (state.moreThanOneAction()) {
                        state.selectionErrorFlags[slot - 1] = true;
                    }
                }
                case BLANK -> {
                    // Arm at line 1106: the source continues, so nothing happens and the slot's cleared
                    // flag stands.
                }
                case INVALID -> {
                    // Trailing arm at line 1108.
                    state.inputError = true;
                    state.selectionErrorFlags[slot - 1] = true;
                    if (state.errorMessageOff()) {
                        state.errorMessage = MSG_INVALID_ACTION_CODE;
                    }
                }
            }
        }
        editArrayExit();
    }

    /**
     * {@code 2250-EDIT-ARRAY-EXIT}, lines 1119 to 1121: the {@code EXIT.} statement. A documented no-op,
     * and the target of the forward jump at line 1076.
     */
    private void editArrayExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    /**
     * The three condition names the row walk at lines 1100 to 1114 tests, in the order it tests them.
     *
     * <p>Named constants rather than a chain of string comparisons at the call site, because the source
     * names them too: the accepted group at line 77, the blank group at lines 80 to 82, and the trailing
     * arm at line 1108 which is everything else.
     */
    private enum SelectionClass {

        /** {@code SELECT-OK}, line 77: one of the two accepted characters. */
        ACCEPTED,

        /** {@code SELECT-BLANK}, lines 80 to 82: a space, or nothing transmitted at all. */
        BLANK,

        /** The trailing arm at line 1108: any other character. */
        INVALID
    }

    /**
     * Classifies one transmitted selection field.
     *
     * <p>Blankness is the source's own test and not the conventional Java one: the field is one
     * character wide, so a space or an untransmitted field is blank, and any other character &mdash;
     * including other white space, which the terminal cannot send into a one-character field but a JSON
     * client can &mdash; falls to the trailing arm exactly as the source's evaluation sends it there.
     *
     * @param selection the transmitted field, which may be {@code null}
     * @return which of the three arms the field takes
     */
    private static SelectionClass classifySelection(final String selection) {
        if (SelectionAction.fromSelection(selection).isPresent()) {
            return SelectionClass.ACCEPTED;
        }
        if (isBlankScreenField(selection)) {
            return SelectionClass.BLANK;
        }
        return SelectionClass.INVALID;
    }

    // ==============================================================================================
    // Paragraph 9000-READ-FORWARD, line 1123, and its exit at line 1261
    // ==============================================================================================

    /**
     * {@code 9000-READ-FORWARD}, lines 1123 to 1259: fills the page by walking the key sequence
     * forward.
     *
     * <p>The walk is over the <strong>base cluster ordered by card number</strong>, positioned at or
     * after the retained cursor, and the filter is applied to each row <em>after</em> it is read. That
     * is what the source does and it is the single most important thing not to change: the account
     * finder the repository declares would order the page by account identifier instead, and folding
     * the filter into the query would change which rows a page contains because the source keeps
     * reading past excluded rows until seven have been accepted.
     *
     * <p>The response evaluation at lines 1156 to 1255 selects on one value, so it is a {@code switch}
     * with every constant covered. Its normal and duplicate arms share one body exactly as the source's
     * do, because the cluster's alternate index is non-unique and a duplicate is not an error.
     *
     * <p><strong>Whether a further page exists is decided by the next physical row.</strong> Having
     * accepted the seventh row, the source stores that row's key as the forward cursor at lines 1194 to
     * 1195, then reads once more at lines 1197 to 1205 <em>without</em> filtering. When that read
     * succeeds it raises the next-page indicator and overwrites the cursor with that row's key at lines
     * 1212 to 1214, so the following page resumes at the eighth physical row rather than at the seventh
     * accepted one. With a filter active the indicator can therefore promise a page that turns out
     * empty. Reproduced exactly.
     *
     * <p>The browse-start response is captured at line 1134 and never tested, so a failure to position
     * is indistinguishable here from an empty result, just as it is there.
     *
     * @param state the turn's working storage
     */
    private void readForward(final TurnState state) {
        // Line 1124: every row slot is cleared, which is what leaves an unfilled slot empty.
        clearScreenRows(state);

        // Lines 1129 to 1136.
        final SequentialBrowse browse = startBrowse(state, false);

        // Lines 1140 to 1142.
        state.screenCounter = 0;
        state.caNextPageExists = true;
        state.readLoopExit = false;
        state.direction = BrowseWindow.PagingDirection.FORWARD;

        // Lines 1144 to 1256.
        while (!state.readLoopExit) {
            switch (readNext(state, browse, LIT_CARD_FILE)) {
                case NORMAL, DUPLICATE -> {
                    // Lines 1159 to 1187.
                    filterRecords(state);
                    if (!state.excludeThisRecord) {
                        state.screenCounter = state.screenCounter + 1;
                        storeRow(state, state.screenCounter);
                        if (state.screenCounter == 1) {
                            // Lines 1173 to 1181.
                            state.caFirstCardNumber = cardNumberOf(state.currentRecord);
                            if (state.caScreenNumber == 0) {
                                state.caScreenNumber = state.caScreenNumber + 1;
                            }
                        }
                    }
                    // Lines 1191 to 1232, outside the acceptance test, so it is reached on the read that
                    // accepts the seventh row.
                    if (state.screenCounter == PAGE_SIZE) {
                        state.readLoopExit = true;
                        state.caLastCardNumber = cardNumberOf(state.currentRecord);
                        probeForFurtherPage(state, browse);
                    }
                }
                case END_OF_FILE -> {
                    // Lines 1233 to 1245.
                    state.readLoopExit = true;
                    state.caNextPageExists = false;
                    state.caLastCardNumber = cardNumberOf(state.currentRecord);
                    if (state.errorMessageOff()) {
                        state.errorMessage = MSG_NO_MORE_RECORDS;
                    }
                    if (state.caScreenNumber == 1 && state.screenCounter == 0) {
                        state.errorMessage = MSG_NO_RECORDS_FOUND;
                    }
                }
                case OTHER -> {
                    // Lines 1246 to 1254.
                    state.readLoopExit = true;
                    composeFileErrorMessage(state, LIT_CARD_FILE, BrowseResponse.OTHER);
                }
            }
        }

        // Line 1258: the browse is ended, inside the paragraph rather than in its exit.
        LOG.trace("Card browse ended: program={} resource={} direction={} rowsAssembled={}",
                LIT_THISPGM, LIT_CARD_FILE, state.direction, populatedRowCount(state));
        readForwardExit();
    }

    /**
     * The unfiltered look-ahead of lines 1197 to 1231: one further read whose only purpose is to settle
     * whether a page follows this one.
     *
     * <p>Its response evaluation is the source's nested one and is reproduced arm for arm. The
     * unclassified arm composes the file-error message just as the outer one does, and the source raises
     * the loop-exit flag there again even though it is already raised, which is harmless and is left as
     * it stands.
     *
     * @param state the turn's working storage
     * @param browse the walk the page was filled from
     */
    private void probeForFurtherPage(final TurnState state, final SequentialBrowse browse) {
        switch (readNext(state, browse, LIT_CARD_FILE)) {
            case NORMAL, DUPLICATE -> {
                // Lines 1208 to 1214: the cursor moves to this row, which is the eighth physical row and
                // not the seventh accepted one.
                state.caNextPageExists = true;
                state.caLastCardNumber = cardNumberOf(state.currentRecord);
            }
            case END_OF_FILE -> {
                // Lines 1215 to 1221.
                state.caNextPageExists = false;
                if (state.errorMessageOff()) {
                    state.errorMessage = MSG_NO_MORE_RECORDS;
                }
            }
            case OTHER -> {
                // Lines 1222 to 1230.
                state.readLoopExit = true;
                composeFileErrorMessage(state, LIT_CARD_FILE, BrowseResponse.OTHER);
            }
        }
    }

    /**
     * {@code 9000-READ-FORWARD-EXIT}, lines 1261 to 1263: the {@code EXIT.} statement. A documented
     * no-op.
     *
     * <p>The browse is ended by the statement at line 1258, inside the paragraph proper, which is why
     * this exit has nothing to do &mdash; unlike the backward browse's exit, which carries the end-browse
     * statement itself.
     */
    private void readForwardExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // Paragraph 9100-READ-BACKWARDS, line 1264, and its exit at line 1374
    // ==============================================================================================

    /**
     * {@code 9100-READ-BACKWARDS}, lines 1264 to 1371: fills the page by walking the key sequence
     * backward.
     *
     * <p>The shape is not a mirror of the forward walk and the difference is the whole point:
     *
     * <ul>
     *   <li>line 1268 copies the retained first key over the last key, so both cursors start from the
     *       current page's own beginning;</li>
     *   <li>lines 1284 to 1286 set the slot counter to <em>one past</em> the screen maximum, and line
     *       1287 raises the next-page indicator unconditionally &mdash; arriving here means a later page
     *       exists, because that is where the operator came from;</li>
     *   <li>lines 1294 to 1307 read once and store nothing, only lowering the counter to the screen
     *       maximum. That read consumes the current page's own first row, which is why the assembled
     *       page is the seven accepted rows strictly before it;</li>
     *   <li>lines 1320 to 1371 then walk backward filling slots <strong>downward</strong> from the
     *       seventh to the first, lowering the counter after each accepted row and stopping when it
     *       reaches zero, at which point line 1350 stores the new first key.</li>
     * </ul>
     *
     * <p>So the read order is descending while the assembled page presents ascending: the response list
     * is the <strong>reverse of the read order</strong>, never the descending read order itself. A
     * partial page consequently leaves the low slots empty and its rows at the bottom of the screen,
     * which is exactly why each row carries its slot.
     *
     * <p><strong>An end of file here yields a file-error message, not a clean one.</strong> The
     * backward read's evaluation at lines 1304 and 1332 has only a normal-and-duplicate arm and a
     * trailing arm, with no end-of-file arm at all, so exhausting the data falls to the trailing arm and
     * composes the file-error text. That is a defect in the source and it is reproduced rather than
     * corrected, because the message is what the screen shows and correcting it would change the
     * contract.
     *
     * @param state the turn's working storage
     */
    private void readBackwards(final TurnState state) {
        // Lines 1266 to 1268.
        clearScreenRows(state);
        state.caLastCardNumber = state.caFirstCardNumber;

        // Lines 1273 to 1280.
        final SequentialBrowse browse = startBrowse(state, true);

        // Lines 1284 to 1288.
        state.screenCounter = PAGE_SIZE + 1;
        state.caNextPageExists = true;
        state.readLoopExit = false;
        state.direction = BrowseWindow.PagingDirection.BACKWARD;

        // Lines 1294 to 1318: the first read is consumed and not stored.
        switch (readNext(state, browse, LIT_CARD_FILE)) {
            case NORMAL, DUPLICATE -> state.screenCounter = state.screenCounter - 1;
            case END_OF_FILE, OTHER -> {
                // Lines 1308 to 1317: an end of file reaches this arm too, because the evaluation has no
                // arm of its own for it. The forward jump at line 1317 leaves the paragraph.
                state.readLoopExit = true;
                composeFileErrorMessage(state, LIT_CARD_FILE, BrowseResponse.OTHER);
                readBackwardsExit(state);
                return;
            }
        }

        // Lines 1320 to 1371.
        while (!state.readLoopExit) {
            switch (readNext(state, browse, LIT_CARD_FILE)) {
                case NORMAL, DUPLICATE -> {
                    // Lines 1335 to 1359.
                    filterRecords(state);
                    if (!state.excludeThisRecord) {
                        storeRow(state, state.screenCounter);
                        state.screenCounter = state.screenCounter - 1;
                        if (state.screenCounter == 0) {
                            state.readLoopExit = true;
                            state.caFirstCardNumber = cardNumberOf(state.currentRecord);
                        }
                    }
                }
                case END_OF_FILE, OTHER -> {
                    // Lines 1361 to 1369.
                    state.readLoopExit = true;
                    composeFileErrorMessage(state, LIT_CARD_FILE, BrowseResponse.OTHER);
                }
            }
        }

        readBackwardsExit(state);
    }

    /**
     * {@code 9100-READ-BACKWARDS-EXIT}, lines 1374 to 1379: the one exit paragraph in this member that
     * is not empty. It ends the browse at lines 1375 to 1377 before its {@code EXIT.} statement, which
     * is why it is reached explicitly from the paragraph's forward jump as well as from its end.
     *
     * <p>Ending the browse releases the position the walk held. The walk here is a sequence of finished
     * repository calls rather than a held cursor, so what this reproduces is the release itself, recorded
     * at trace level so that the paired start and end remain visible to a diagnostic reader.
     *
     * @param state the turn's working storage
     */
    private void readBackwardsExit(final TurnState state) {
        LOG.trace("Card browse ended: program={} resource={} direction={} rowsAssembled={}",
                LIT_THISPGM, LIT_CARD_FILE, state.direction, populatedRowCount(state));
    }

    // ==============================================================================================
    // Paragraph 9500-FILTER-RECORDS, line 1382, and its exit at line 1409
    // ==============================================================================================

    /**
     * {@code 9500-FILTER-RECORDS}, lines 1382 to 1405: decides whether the record just read belongs on
     * the page.
     *
     * <p><strong>This is the account filter, and it runs after the read.</strong> The source browses the
     * base cluster by card number and then asks this paragraph, once per record, whether to keep it.
     * Folding either test into the query would change which rows a page contains, because the read loop
     * keeps going until seven records have been <em>accepted</em>, and it would additionally reorder the
     * page if the account path were used to reach the rows.
     *
     * <p>Each filter applies only when its own flag is valid, at lines 1385 and 1396, so a filter the
     * edit rejected leaves the data unfiltered rather than excluding everything. Both comparisons are
     * whole-value and exact: the source compares fixed-width fields of equal size, and a valid filter is
     * by construction exactly as wide as the record field it is compared against, so nothing is trimmed,
     * padded or re-cased here.
     *
     * <p>The card comparison at line 1397 is against the numeric redefinition of the filter field, which
     * over sixteen digit characters is the same sequence of bytes as the field itself, so it is the same
     * comparison.
     *
     * @param state the turn's working storage
     */
    private void filterRecords(final TurnState state) {
        // Line 1383.
        state.excludeThisRecord = false;

        // Lines 1385 to 1394.
        if (state.accountFilterFlag.isValid()
                && !state.screenAccountId.equals(accountIdentifierOf(state.currentRecord))) {
            state.excludeThisRecord = true;
            filterRecordsExit();
            return;
        }

        // Lines 1396 to 1405.
        if (state.cardFilterFlag.isValid()
                && !state.screenCardNumber.equals(cardNumberOf(state.currentRecord))) {
            state.excludeThisRecord = true;
            filterRecordsExit();
            return;
        }

        filterRecordsExit();
    }

    /**
     * {@code 9500-FILTER-RECORDS-EXIT}, lines 1409 to 1411: the {@code EXIT.} statement. A documented
     * no-op, and the target of the two forward jumps at lines 1390 and 1401.
     */
    private void filterRecordsExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // The COPY 'CSSTRPFY' expansion site, line 1416
    // ==============================================================================================

    /**
     * The {@code COPY 'CSSTRPFY'} statement at line 1416, which is a procedure-division statement in its
     * own right: it pulls in the two paragraphs that map a raw terminal attention-key identifier to the
     * shared work area's action field, and the main paragraph invokes that range at lines 349 to 350.
     *
     * <p><strong>The two paragraphs it delivers are not duplicated here.</strong> The key-store paragraph
     * at line 17 of {@code app/cpy/CSSTRPFY.cpy} and its exit at line 80 belong to
     * {@link PfKeyTranslator}, which is their single owner and which the traceability matrix credits with
     * them; this member is one of the five includers. In particular the fold of program-function keys 13
     * through 24 back onto 1 through 12, written out arm by arm at lines 54 to 77 of that copybook, is
     * not re-implemented and the high keys are not treated as distinct: pressing key 19 arrives here as
     * the same action key 7 produces, which is what makes the backward-paging arm respond to both.
     *
     * <p><strong>An unmapped identifier is a real state, not an error in the translator.</strong> The
     * copybook's selection at line 21 has 28 arms across lines 22 to 77 and <em>no</em> catch-all, so an
     * identifier matching none of them causes no assignment at all and the work area's action field keeps
     * whatever the previous turn left in it. That is why the translator reports absence as an empty
     * result rather than through a synthetic constant, and it is why the retained value is deliberately
     * left standing here.
     *
     * <p>Alongside retaining it, an unmapped identifier raises the error flag and emits the common
     * invalid-key message at its contractual 50-character width, <strong>never trimmed</strong>. That is
     * a documented improvement on the source, which silently carries a stale action forward; the retained
     * value is still carried, so the dispatch behaves exactly as the source's would, and the operator is
     * additionally told that the key meant nothing.
     *
     * <p>A {@code null} or blank identifier is handled here rather than passed on, because the translator
     * treats an absent reference as a caller defect and raises on it. An absent identifier is the same
     * observable state as an unrecognised one &mdash; no arm matched, so nothing was assigned &mdash; and
     * is reported the same way.
     *
     * @param state the turn's working storage
     * @param attentionKeyIdentifier the raw terminal attention-key identifier, as transmitted
     */
    private void storePfKeyExpansion(final TurnState state, final String attentionKeyIdentifier) {
        final Optional<KeyAction> mapped = attentionKeyIdentifier == null
                || attentionKeyIdentifier.isBlank()
                        ? Optional.empty()
                        : PfKeyTranslator.translate(attentionKeyIdentifier);

        if (mapped.isPresent()) {
            state.keyAction = mapped.get();
            return;
        }

        // No arm matched, so nothing is assigned and the retained action stands, exactly as the copybook
        // leaves it. The consequence is recorded rather than acted on here: it is applied at the point the
        // source itself decides whether the key is usable, so that the edit paragraphs -- which clear the
        // error flag on entry -- cannot wipe it.
        state.pfKeyUnmapped = true;
        LOG.debug("Attention key matched no arm of the key store: program={} transaction={} "
                        + "retainedAction={}",
                LIT_THISPGM, LIT_THISTRANID, state.keyAction);
    }

    /**
     * The unmapped-key outcome, applied where lines 370 to 380 decide whether the key is usable.
     *
     * <p>This is a <strong>documented improvement</strong> on the source and the one place this class adds
     * an outcome the source does not have. The source carries a stale action forward silently when the
     * terminal sends an identifier its key store does not recognise; here the error flag is raised and the
     * common invalid-key message is emitted at its contractual
     * {@code MessageCatalogService.COMMON_MESSAGE_WIDTH} width, <strong>never trimmed</strong>, so the
     * operator is told the key meant nothing. The retained action is still carried, so the dispatch that
     * follows behaves exactly as the source's would and no navigation changes.
     *
     * <p>Applied after the edits rather than inside the key store, because the edit entry at line 986
     * clears the error flag and would otherwise erase this. The message is assigned only when the field is
     * still blank, so a filter or selection message the edits composed keeps precedence &mdash; the same
     * rule the source applies at lines 1056 and 1111.
     *
     * @param state the turn's working storage
     */
    private void applyUnmappedKeyOutcome(final TurnState state) {
        if (!state.pfKeyUnmapped) {
            return;
        }
        state.inputError = true;
        if (state.errorMessageOff()) {
            state.errorMessage = this.messageCatalogService.invalidKeyMessage();
        }
    }

    // ==============================================================================================
    // Paragraphs SEND-PLAIN-TEXT, line 1422, and SEND-LONG-TEXT, line 1441, with their exits
    // ==============================================================================================

    /**
     * {@code SEND-PLAIN-TEXT}, lines 1422 to 1431: transmits the message field as unformatted text and
     * returns, which abandons the map entirely.
     *
     * <p><strong>No paragraph of this member performs it.</strong> The source's own comment at line 1420
     * says it is not for production use, and a scan of every performed and jumped-to label confirms it is
     * never reached. It is translated because every paragraph maps to a named method, and it is exposed
     * rather than left as unreachable private code, so that the mapping is verifiable and the method is
     * reachable by a diagnostic caller. It is on no path the screen turn takes and it changes nothing.
     *
     * <p>Returning the text rather than transmitting it is the whole of the substitution: there is no
     * terminal to write to, and the caller decides what to do with it.
     *
     * @param message the message field to render, which may be {@code null}
     * @return the message at the {@value #ERROR_MESSAGE_WIDTH}-character width of the field the source
     *         transmits, space padded and never trimmed
     */
    public String sendPlainText(final String message) {
        final String rendered = moveToField(message, ERROR_MESSAGE_WIDTH);
        sendPlainTextExit();
        return rendered;
    }

    /**
     * {@code SEND-PLAIN-TEXT-EXIT}, lines 1433 to 1435: the {@code EXIT.} statement. A documented no-op.
     */
    private void sendPlainTextExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    /**
     * {@code SEND-LONG-TEXT}, lines 1441 to 1450: transmits the long diagnostic field as unformatted text
     * and returns.
     *
     * <p>Performed by no paragraph either, for the same reason and with the same treatment as the plain
     * text paragraph above. The source's comments at lines 1438 to 1439 say it is for debugging and not
     * for regular use.
     *
     * @param message the long diagnostic text, which may be {@code null}
     * @return the text at the {@value #LONG_MESSAGE_WIDTH}-character width of {@code WS-LONG-MSG},
     *         declared at line 111, space padded and never trimmed
     */
    public String sendLongText(final String message) {
        final String rendered = moveToField(message, LONG_MESSAGE_WIDTH);
        sendLongTextExit();
        return rendered;
    }

    /**
     * {@code SEND-LONG-TEXT-EXIT}, lines 1452 to 1454: the {@code EXIT.} statement. A documented no-op.
     */
    private void sendLongTextExit() {
        // See mainParaExit for why every exit paragraph is deliberately empty.
    }

    // ==============================================================================================
    // The emulated browse. STARTBR / READNEXT / READPREV / ENDBR over the base cluster, expressed
    // through the repository's inherited paged findAll with an explicit sort on the card number.
    // ==============================================================================================

    /**
     * The browse-start of lines 1129 to 1136 and 1273 to 1280: positions a walk of the base cluster at or
     * after the retained record identifier, ordered by card number.
     *
     * @param state the turn's working storage
     * @param descending {@code true} for the backward walk, which positions at or <em>before</em> the
     *                   identifier because that is what a backward read from a greater-or-equal start
     *                   delivers
     * @return the walk, positioned but not yet read from
     */
    private SequentialBrowse startBrowse(final TurnState state, final boolean descending) {
        LOG.trace("Card browse started: program={} resource={} startKeySupplied={} descending={}",
                LIT_THISPGM, LIT_CARD_FILE, !isBlankScreenField(state.ridCardNumber), descending);
        return new SequentialBrowse(this.cardRepository, state.ridCardNumber, descending);
    }

    /**
     * One read of the walk: the {@code READNEXT} of lines 1146 to 1154 and 1197 to 1205, and the
     * {@code READPREV} of lines 1294 to 1302 and 1322 to 1330.
     *
     * <p>A delivered record is stored into the single record area, exactly as the source's read does, so
     * that the end-of-file arm can still read the key of the last record read successfully.
     *
     * <p>A data-access failure is the analogue of the source's unclassified response: the source captures
     * a response code and folds anything it does not recognise into a screen message rather than failing
     * the transaction, so the failure is caught here and reported the same way. The diagnostic is emitted
     * before anything else happens to it.
     *
     * @param state the turn's working storage
     * @param browse the walk to advance
     * @param resourceName the legacy file name the diagnostic names
     * @return the condition the read reported, normalised onto one of the source's arms
     */
    private BrowseResponse readNext(final TurnState state, final SequentialBrowse browse,
            final String resourceName) {
        try {
            final Card record = browse.next();
            if (record == null) {
                return BrowseResponse.END_OF_FILE;
            }
            state.currentRecord = record;
            return BrowseResponse.NORMAL;
        } catch (final DataAccessException failure) {
            LOG.error("Card browse failed: program={} transaction={} fileStatus={} operation={} "
                            + "resource={} failure={}",
                    LIT_THISPGM, LIT_THISTRANID, BrowseResponse.OTHER.getStatus(),
                    FILE_ERROR_OPERATION_READ, resourceName, failure.getClass().getName());
            return BrowseResponse.OTHER;
        }
    }

    /**
     * The file-error message of lines 1226 to 1230, 1250 to 1254, 1312 to 1316 and 1365 to 1369, and the
     * one place in this class where the mandated emit-then-delegate ordering is exercised.
     *
     * <p><strong>Ordering.</strong> The raw two-character status and the resource name are written to the
     * log at error level <em>first</em>, and only then is the diagnostic delegated to the abend service.
     * The delegation uses that service's no-abend entry point deliberately: this member abends on no
     * output path at all &mdash; its abend-variable include is commented out at line 283 and it issues no
     * abend statement &mdash; so raising one here would invent a failure the estate does not have. The
     * entry point used exists for exactly this caller: one that has emitted the status and has not decided
     * to abend.
     *
     * <p><strong>Composition.</strong> The eight declared parts of the message field at lines 153 to 170
     * total exactly {@value #ERROR_MESSAGE_WIDTH} characters, and the field carries a further five-byte
     * filler at line 171 that brings its own width to eighty. The move into the
     * {@value #ERROR_MESSAGE_WIDTH}-character message field therefore drops precisely that filler, which
     * is why composing the eight parts reproduces the visible message with nothing lost and nothing
     * truncated. The two response slots carry the two-character status and blanks respectively, because
     * the CICS response and reason numbers the source moves into them have no equivalent once the data
     * store is relational.
     *
     * @param state the turn's working storage
     * @param resourceName the legacy file name the diagnostic names
     * @param response the condition the read reported
     */
    private void composeFileErrorMessage(final TurnState state, final String resourceName,
            final BrowseResponse response) {
        final String rawFileStatus = response.getStatus();

        LOG.error("Card list browse reported an unclassified condition: program={} transaction={} "
                        + "fileStatus={} operation={} resource={} alternateIndexResource={}",
                LIT_THISPGM, LIT_THISTRANID, rawFileStatus, FILE_ERROR_OPERATION_READ, resourceName,
                LIT_CARD_FILE_ACCT_PATH);
        this.abendService.displayIoStatus(rawFileStatus, FILE_ERROR_OPERATION_READ, resourceName);

        state.errorOperationName = FILE_ERROR_OPERATION_READ;
        state.errorResourceName = resourceName;
        state.errorResponse = rawFileStatus;
        state.errorMessage = FILE_ERROR_PREFIX
                + moveToField(state.errorOperationName, FILE_ERROR_OPNAME_WIDTH)
                + FILE_ERROR_ON
                + moveToField(state.errorResourceName, FILE_ERROR_FILE_WIDTH)
                + FILE_ERROR_RETURNED_RESP
                + moveToField(state.errorResponse, FILE_ERROR_RESP_WIDTH)
                + FILE_ERROR_RESP2
                + moveToField(NO_MESSAGE, FILE_ERROR_RESP_WIDTH);
    }

    /**
     * A sequential walk of the card base cluster in one direction, positioned at a record key.
     *
     * <p>This is the browse the source holds open across its read loop, expressed as what the source
     * actually performs: a positioning read on a retained record identifier followed by
     * record-at-a-time reads onward from it. The account finder the repository also declares is
     * deliberately not used, for the reason set out on the class.
     *
     * <p><strong>Positioning is pushed into the query, not emulated after it.</strong> A
     * greater-or-equal browse start is two reads because the range finders are strict: the inclusive
     * row is the inherited keyed read on the start key, and everything after it is a bounded range
     * read strictly beyond the last key handed out. The walk therefore never reads a row it intends to
     * discard, and reaching a distant cursor costs the same as reaching a near one - where the earlier
     * offset form walked the ordering from its beginning and threw away every row before the start.
     *
     * <p><strong>Why keyset rather than an offset page.</strong> Two reasons, and the second is
     * behavioural rather than a matter of cost. An offset page asks the store to count and discard
     * every earlier row on each fetch. And an offset page is <em>not stable</em>: a card inserted or
     * removed between two fetches shifts the window, so a row is delivered twice or missed entirely.
     * The source cannot have that defect, because it retains the card number of the row it stopped at
     * - lines 1212 to 1214 - and repositions on that value; a concurrent insert simply appears, or
     * does not, at its own key. Reading by key is therefore the faithful translation as well as the
     * bounded one.
     *
     * <p>Every fetch is bounded to one screen's worth of rows, so no call loads the whole cluster
     * however large it grows. The chunk exists so that the record-at-a-time reads of one page cost one
     * range read rather than seven; the source's own granularity - one row per verb - is what the walk
     * hands out.
     *
     * <p>Keys are compared whole and by the store. A card number is a fixed-width sequence of digits,
     * on which every collation this column can carry agrees with its numeric order, and the walk never
     * splits, parses or re-cases the value.
     *
     * <p>A blank start key means the source's own initial value, which a greater-or-equal start
     * resolves to the first row of the cluster: no card number equals a blank, and every card number
     * sorts above one, so the inclusive read finds nothing and the forward range read begins at the
     * first row. Descending, no row lies at or before a blank, so the walk yields nothing at once
     * &mdash; which is the same outcome the source reaches, because its single delivered row is the one
     * its unstored first read discards.
     */
    private static final class SequentialBrowse {

        /** The base cluster the walk reads. */
        private final CardRepository repository;

        /** {@code true} when the walk runs backwards, as the source's backward read does. */
        private final boolean descending;

        /**
         * The exclusive bound of the next range read: the start key until a row has been handed out,
         * and the key of the row most recently handed out after that.
         *
         * <p>This single field is what replaced the page index. It is the browse's whole position, and
         * it is a business key rather than an ordinal, which is precisely why a concurrent write cannot
         * shift the walk under it.
         */
        private String cursorKey;

        /** {@code false} until the inclusive positioning read has been attempted, which happens once. */
        private boolean positioningRead;

        /** The chunk currently being handed out, one row at a time. */
        private List<Card> buffer = List.of();

        /** The position within the current chunk of the next row to hand out. */
        private int bufferPosition;

        /** {@code true} once the cluster holds no further row in the walk's direction. */
        private boolean exhausted;

        /**
         * Positions a walk without reading from it.
         *
         * @param repository the base cluster to read
         * @param startKey the card number to start at, which may be blank for the source's own initial
         *                 value
         * @param descending whether the walk runs backwards
         */
        SequentialBrowse(final CardRepository repository, final String startKey,
                final boolean descending) {
            this.repository = repository;
            this.descending = descending;
            this.cursorKey = startKey == null ? NO_MESSAGE : startKey;
        }

        /**
         * Delivers the next record of the walk.
         *
         * @return the next record, or {@code null} when the walk has passed the end of the data, which
         *         is the end-of-file condition of the source's read
         */
        Card next() {
            if (!this.positioningRead) {
                this.positioningRead = true;
                final Card inclusive = this.repository.findById(this.cursorKey).orElse(null);
                if (inclusive != null) {
                    // The row the browse positioned on is the row the first read returns, which is the
                    // browse command's own contract in either direction.
                    this.cursorKey = inclusive.getCardNum();
                    return inclusive;
                }
            }
            if (this.bufferPosition >= this.buffer.size()) {
                if (this.exhausted) {
                    return null;
                }
                this.buffer = fetchChunk();
                this.bufferPosition = 0;
                if (this.buffer.size() < BROWSE_CHUNK_SIZE) {
                    // A short chunk is the end of the data in this direction, so the walk answers the
                    // next end-of-file without another query.
                    this.exhausted = true;
                }
                if (this.buffer.isEmpty()) {
                    return null;
                }
            }
            final Card candidate = this.buffer.get(this.bufferPosition);
            this.bufferPosition = this.bufferPosition + 1;
            this.cursorKey = candidate.getCardNum() == null ? NO_MESSAGE : candidate.getCardNum();
            return candidate;
        }

        /**
         * Reads one bounded chunk strictly beyond the cursor, in the walk's direction.
         *
         * @return at most one screen's worth of rows in read order, possibly empty
         */
        private List<Card> fetchChunk() {
            final Limit chunk = Limit.of(BROWSE_CHUNK_SIZE);
            return this.descending
                    ? this.repository.findByCardNumLessThanOrderByCardNumDesc(this.cursorKey, chunk)
                    : this.repository.findByCardNumGreaterThanOrderByCardNumAsc(this.cursorKey, chunk);
        }
    }

    // ==============================================================================================
    // Navigation state. The source builds its communication area with repeated groups of moves; each
    // group becomes one named transformation here so that the group is visible as a unit.
    // ==============================================================================================

    /**
     * The first-entry group of lines 316 to 323, repeated verbatim at lines 462 to 469: an empty state
     * stamped with this screen's own identity and marked as a first entry.
     *
     * <p>The user type is set to the standard-user code at lines 320 and 466, unconditionally, so an
     * administrator arriving here is recorded as a standard user. That is the source's own behaviour and
     * it is reproduced. It carries no authorisation consequence, because the echoed user type is state
     * the client returns and is reconciled against the authenticated principal before it is trusted; the
     * field is display and continuity state, not authority.
     *
     * @return an empty navigation state carrying this screen's identity and marked as a first entry
     */
    private static ScreenNavigationState firstEntryContext() {
        return new ScreenNavigationState(
                LIT_THISTRANID,
                LIT_THISPGM,
                null,
                LIT_THISPGM,
                null,
                USER_TYPE_STANDARD,
                ScreenNavigationState.ProgramContext.ENTER,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LIT_THISMAP,
                LIT_THISMAPSET);
    }

    /**
     * The screen-identity group of lines 386 to 392, 424 to 426, 464 to 469, 520 to 526, 548 to 554 and
     * 605 to 608: this screen stamps itself as the originating program and records its own map and mapset
     * as the last ones displayed, while nominating where control goes next.
     *
     * @param base the navigation state to stamp
     * @param nominatedProgram the legacy program name control is nominated to reach
     * @return the stamped navigation state
     */
    private static ScreenNavigationState screenIdentityContext(final ScreenNavigationState base,
            final String nominatedProgram) {
        return new ScreenNavigationState(
                LIT_THISTRANID,
                LIT_THISPGM,
                base.toTransactionId(),
                nominatedProgram,
                base.userId(),
                USER_TYPE_STANDARD,
                base.programContext(),
                base.customerId(),
                base.customerFirstName(),
                base.customerMiddleName(),
                base.customerLastName(),
                base.accountId(),
                base.accountStatus(),
                base.cardNumber(),
                LIT_THISMAP,
                LIT_THISMAPSET);
    }

    /**
     * The account-identifier move of lines 1011, 1024 and 1027: the navigation state's account identifier
     * follows the account filter's edit outcome.
     *
     * @param base the navigation state to amend
     * @param accountId the account filter's edit outcome
     * @return the amended navigation state
     */
    private static ScreenNavigationState withAccountIdentifier(final ScreenNavigationState base,
            final String accountId) {
        return new ScreenNavigationState(
                base.fromTransactionId(),
                base.fromProgram(),
                base.toTransactionId(),
                base.toProgram(),
                base.userId(),
                base.userType(),
                base.programContext(),
                base.customerId(),
                base.customerFirstName(),
                base.customerMiddleName(),
                base.customerLastName(),
                accountId,
                base.accountStatus(),
                base.cardNumber(),
                base.lastMap(),
                base.lastMapset());
    }

    /**
     * The card-number move of lines 1046, 1061 and 1064: the navigation state's card number follows the
     * card filter's edit outcome.
     *
     * @param base the navigation state to amend
     * @param cardNumber the card filter's edit outcome
     * @return the amended navigation state
     */
    private static ScreenNavigationState withCardNumber(final ScreenNavigationState base,
            final String cardNumber) {
        return new ScreenNavigationState(
                base.fromTransactionId(),
                base.fromProgram(),
                base.toTransactionId(),
                base.toProgram(),
                base.userId(),
                base.userType(),
                base.programContext(),
                base.customerId(),
                base.customerFirstName(),
                base.customerMiddleName(),
                base.customerLastName(),
                base.accountId(),
                base.accountStatus(),
                cardNumber,
                base.lastMap(),
                base.lastMapset());
    }

    /**
     * Reports whether the originating-program field names this member, which is the test at lines 337,
     * 358, 385, 460, 519 and 547 and the gate on receiving input at all.
     *
     * <p>The source compares two fixed-width eight-character fields, and this member's name fills the
     * field exactly. Trailing spaces are tolerated because a fixed-width client may pad, which is the same
     * comparison a byte-for-byte compare of the padded field performs. Nothing else is normalised: no
     * leading space is stripped and no case is folded, because either would accept a name the source's
     * comparison rejects.
     *
     * @param fromProgram the originating-program field, as the state carries it
     * @return {@code true} when the field names this member
     */
    private static boolean namesThisProgram(final String fromProgram) {
        if (fromProgram == null) {
            return false;
        }
        int end = fromProgram.length();
        while (end > 0 && fromProgram.charAt(end - 1) == ' ') {
            end = end - 1;
        }
        return end == LIT_THISPGM.length() && fromProgram.startsWith(LIT_THISPGM);
    }

    /**
     * The transfer of control of lines 402 to 405: nominate the destination, mark the destination as being
     * entered afresh, and resolve where control goes.
     *
     * <p>Resolution is delegated, so this class declares no destination table of its own. The delegate
     * raises the abend the legacy transfer would have raised when a nominated destination cannot be
     * resolved, which is the one abend path this member has; every failure of its own output path is
     * tolerated and reported on the screen instead.
     *
     * @param state the turn's working storage
     * @param nominatedProgram the legacy program name control is handed to
     */
    private void dispatchToProgram(final TurnState state, final String nominatedProgram) {
        state.navigationContext =
                screenIdentityContext(state.navigationContext, nominatedProgram).withFirstEntry();
        state.reEntry = false;
        state.route = this.navigationService.resolveNominatedDestination(carriedState(state.navigationContext),
                NavigationService.Route.CARD_LIST);
    }

    /**
     * The two selection transfers of lines 520 to 541 and 548 to 569: nominate the card detail or card
     * update screen and hand it the selected row's account identifier and card number, at lines 531 to 534
     * and 559 to 562.
     *
     * @param state the turn's working storage
     * @param nominatedProgram the legacy program name control is handed to
     */
    private void dispatchToSelectedCard(final TurnState state, final String nominatedProgram) {
        final CardListRow selectedRow = state.screenRows[state.selectedIndex - 1];
        dispatchToProgram(state, nominatedProgram);
        if (selectedRow != null) {
            state.navigationContext =
                    withCardNumber(withAccountIdentifier(state.navigationContext,
                            selectedRow.accountId()), selectedRow.cardNumber());
        }
    }

    /**
     * The selection the two dispatch arms at lines 518 and 546 subscript for, with the range guard the
     * source omits.
     *
     * <p>Those arms index the selection table with the chosen slot, which is zero whenever nothing was
     * chosen. The condition name at line 94 exists to prevent exactly that and the source does not consult
     * it, so a zero subscript reaches the table. In COBOL that reads storage outside the table and is
     * undefined; in Java it cannot be expressed at all. The guard is applied and the omission documented.
     *
     * @param state the turn's working storage
     * @return the selection the subscripted slot carries, or empty when the subscript falls outside the table
     */
    private static Optional<SelectionAction> selectedAction(final TurnState state) {
        if (state.selectedIndex < 1 || state.selectedIndex > PAGE_SIZE) {
            return Optional.empty();
        }
        return SelectionAction.fromSelection(state.selectionFlags[state.selectedIndex - 1]);
    }

    // ==============================================================================================
    // Row storage and the assembled outcome
    // ==============================================================================================

    /**
     * The clearing of every row slot at lines 1124 and 1266, which is what leaves a slot empty.
     *
     * @param state the turn's working storage
     */
    private static void clearScreenRows(final TurnState state) {
        for (int slot = 1; slot <= PAGE_SIZE; slot++) {
            state.screenRows[slot - 1] = null;
        }
    }

    /**
     * The row store of lines 1165 to 1171 and 1338 to 1344: the three record fields are written to one
     * slot, exactly as the record carries them.
     *
     * <p>The status is stored as the single character the record holds and is not resolved to a typed value
     * here, so a character outside the two the estate defines still reaches the screen unchanged. The card
     * verification value is never read on this screen and is deliberately not carried: it is not one of the
     * three fields the source stores, and a value such as {@code 007} would in any case remain the
     * three-character string it is rather than becoming a number.
     *
     * @param state the turn's working storage
     * @param screenSlot the one-based slot the record is written to
     */
    private static void storeRow(final TurnState state, final int screenSlot) {
        state.screenRows[screenSlot - 1] = new CardListRow(
                screenSlot,
                accountIdentifierOf(state.currentRecord),
                cardNumberOf(state.currentRecord),
                cardActiveStatusOf(state.currentRecord),
                state.selectionFlags[screenSlot - 1] == null
                        ? NO_MESSAGE
                        : state.selectionFlags[screenSlot - 1]);
    }

    /**
     * The rows the turn settled on, in screen-slot order and with no empty slot represented.
     *
     * <p>Slot order is ascending in both paging directions, which for the backward walk makes this the
     * reverse of the read order: that walk reads descending and fills slots downward from the seventh, so
     * iterating the slots upward restores the order the screen presents. The list carries only populated
     * slots and is never padded, so a partial page is shorter rather than blank filled; each row's own slot
     * says where on the screen it sits.
     *
     * @param state the turn's working storage
     * @return the populated rows in slot order, never null and never longer than {@value #PAGE_SIZE}
     */
    private static List<CardListRow> assembleRows(final TurnState state) {
        final List<CardListRow> rows = new ArrayList<>(PAGE_SIZE);
        for (int slot = 1; slot <= PAGE_SIZE; slot++) {
            if (state.screenRows[slot - 1] != null) {
                rows.add(state.screenRows[slot - 1]);
            }
        }
        return Collections.unmodifiableList(rows);
    }

    /**
     * The positional bitmap, as exactly {@value #PAGE_SIZE} entries in slot order.
     *
     * <p>Every slot is reported, including the ones that carry no error, because the source's bitmap writes
     * a cleared flag at each of them and that cleared flag is a fact about the slot. The list is never
     * compacted and no set is used.
     *
     * @param state the turn's working storage
     * @return exactly {@value #PAGE_SIZE} flags in slot order, never null
     */
    private static List<Boolean> assembleSelectionErrorFlags(final TurnState state) {
        final List<Boolean> flags = new ArrayList<>(PAGE_SIZE);
        for (int slot = 1; slot <= PAGE_SIZE; slot++) {
            flags.add(state.selectionErrorFlags[slot - 1]);
        }
        return Collections.unmodifiableList(flags);
    }

    /**
     * The paging state the turn leaves, carrying both retained cursors and the direction walked.
     *
     * <p>Both cursors are carried in both directions, because either direction can be reversed out of: the
     * source keeps its first key so the backward arm can restart from it and its last key so the forward
     * arm can, and it repositions on whichever the operator's next key calls for.
     *
     * <p>Whether a further page exists is the indicator the browse computed, which on the forward walk was
     * settled by the next <em>physical</em> row rather than the next matching one. Whether a page precedes
     * this one is the source's own test of not being on the first page, at lines 440 and 502, rather than a
     * separately retained flag.
     *
     * @param state the turn's working storage
     * @return the paging state the turn leaves, never null
     */
    private static BrowseWindow assembleBrowseWindow(final TurnState state) {
        final String displayedPageNumber = Integer.toString(state.caScreenNumber);
        final boolean hasPreviousPages = !state.onFirstPage();
        if (state.direction == BrowseWindow.PagingDirection.BACKWARD) {
            return BrowseWindow.backward(PAGE_SIZE, state.caFirstCardNumber, state.caLastCardNumber,
                    state.caNextPageExists, hasPreviousPages, displayedPageNumber);
        }
        return BrowseWindow.forward(PAGE_SIZE, state.caFirstCardNumber, state.caLastCardNumber,
                state.caNextPageExists, hasPreviousPages, displayedPageNumber);
    }

    /**
     * The number of populated row slots, for the diagnostics that report the shape of a page.
     *
     * @param state the turn's working storage
     * @return the count of populated slots, from zero to {@value #PAGE_SIZE}
     */
    private static int populatedRowCount(final TurnState state) {
        int populated = 0;
        for (int slot = 1; slot <= PAGE_SIZE; slot++) {
            if (state.screenRows[slot - 1] != null) {
                populated = populated + 1;
            }
        }
        return populated;
    }

    // ==============================================================================================
    // Record field access and the two screen-field edits
    //
    // Why the two edits are written here rather than delegated to the shared string utilities: the
    // shared not-supplied predicate also accepts the '*' decoration marker, and the shared numeric
    // predicate accepts the fuller NUMVAL-C grammar of a sign, a decimal point and grouping commas.
    // Either one would widen these two filters to admit input that lines 1017 and 1052 reject, and a
    // filter that accepts more than the source accepts changes which rows a page contains. The two
    // predicates below therefore reproduce exactly the two class conditions the source states and
    // nothing wider, and they match digits as the '0' to '9' range rather than by a locale-sensitive
    // digit test, so no locale can widen them either.
    // ==============================================================================================

    /**
     * {@code CARD-NUM} of the record area, as it stands.
     *
     * @param record the record area to read from
     * @return the card number, or an empty string when the area carries none
     */
    private static String cardNumberOf(final Card record) {
        return record == null || record.getCardNum() == null ? NO_MESSAGE : record.getCardNum();
    }

    /**
     * {@code CARD-ACCT-ID} of the record area, as it stands.
     *
     * @param record the record area to read from
     * @return the account identifier, or an empty string when the area carries none
     */
    private static String accountIdentifierOf(final Card record) {
        return record == null || record.getCardAcctId() == null ? NO_MESSAGE : record.getCardAcctId();
    }

    /**
     * {@code CARD-ACTIVE-STATUS} of the record area, as it stands.
     *
     * @param record the record area to read from
     * @return the active status, or an empty string when the area carries none
     */
    private static String cardActiveStatusOf(final Card record) {
        return record == null || record.getCardActiveStatus() == null
                ? NO_MESSAGE
                : record.getCardActiveStatus();
    }

    /**
     * The blank test the source applies to a one-character screen field: the field holds a space, or the
     * terminal transmitted nothing for it.
     *
     * <p>Deliberately narrower than a general whitespace test. The condition names at lines 80 to 82 name a
     * space and low values and nothing else, so a tab or a non-breaking space &mdash; which a terminal
     * cannot put in a one-character field but a client can &mdash; is not blank and falls to the trailing
     * arm exactly as the source sends it there.
     *
     * @param value the one-character screen field, as transmitted
     * @return {@code true} when the field carries a space or nothing at all
     */
    private static boolean isBlankScreenField(final String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * The not-supplied test of lines 1007 to 1009 and 1042 to 1044, which has three parts joined by
     * alternation: the field holds low values, or it holds all spaces, or its numeric redefinition reads as
     * all zeros.
     *
     * <p>The third part is the one easily missed and it is behavioural: a key filter of nothing but zeros,
     * at the full width of its field, is treated as no filter at all rather than as a search for an
     * identifier of zero.
     *
     * @param value the transmitted filter, which may be {@code null}
     * @param width the declared width of the field the filter occupies
     * @return {@code true} when the filter was not supplied
     */
    private static boolean isUnsuppliedKeyFilter(final String value, final int width) {
        if (isBlankScreenField(value)) {
            return true;
        }
        if (value.length() != width) {
            return false;
        }
        for (int position = 0; position < width; position++) {
            if (value.charAt(position) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * The COBOL numeric class condition of lines 1017 and 1052, applied to an alphanumeric field of the
     * declared width: every one of its positions must hold a digit.
     *
     * <p>A value shorter than the field fails, because the remaining positions hold spaces and a space is
     * not a digit &mdash; which is what makes a partly typed filter an error rather than a shorter one. A
     * value longer than the field fails too, because it cannot have come from that field.
     *
     * <p>Digits are matched as the character range rather than through a Unicode digit predicate. The class
     * condition recognises the character set's own digits, so a predicate that also accepted digits from
     * other scripts would let this filter through input the screen cannot send and the record cannot hold.
     *
     * @param value the field to test
     * @param width the declared width, every position of which must hold a digit
     * @return {@code true} when every position of the declared width holds an ASCII digit
     */
    private static boolean isFixedWidthAllDigits(final String value, final int width) {
        if (value == null || value.length() != width) {
            return false;
        }
        for (int position = 0; position < width; position++) {
            final char character = value.charAt(position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The COBOL alphanumeric move: the value is placed left justified in a field of the given width, space
     * padded when shorter and truncated on the right when longer.
     *
     * <p>Built character by character so that no positional slicing appears in this class; fixed-width
     * record handling belongs to the utility layer and this is a screen-message field, not a record image.
     *
     * @param value the sending value, which may be {@code null} and is then treated as an empty field
     * @param width the width of the receiving field
     * @return exactly {@code width} characters
     */
    private static String moveToField(final String value, final int width) {
        final String sending = value == null ? NO_MESSAGE : value;
        final StringBuilder receiving = new StringBuilder(width);
        for (int position = 0; position < width; position++) {
            receiving.append(position < sending.length() ? sending.charAt(position) : ' ');
        }
        return receiving.toString();
    }

    /**
     * The retained first key, from which a backward walk restarts. Opaque: echoed as it was given, never
     * split, parsed or re-cased.
     *
     * @param input the transmitted screen and the echoed navigation state
     * @return the retained first key, or an empty string when none was echoed
     */
    private static String previousCursorOf(final CardListScreenInput input) {
        final BrowseWindow.CursorRequest cursor = input.pageCursor();
        return cursor == null || cursor.previousCursorKey() == null
                ? NO_MESSAGE
                : cursor.previousCursorKey();
    }

    /**
     * The retained last key, from which a forward walk restarts. Opaque, on the same terms.
     *
     * @param input the transmitted screen and the echoed navigation state
     * @return the retained last key, or an empty string when none was echoed
     */
    private static String nextCursorOf(final CardListScreenInput input) {
        final BrowseWindow.CursorRequest cursor = input.pageCursor();
        return cursor == null || cursor.nextCursorKey() == null ? NO_MESSAGE : cursor.nextCursorKey();
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
