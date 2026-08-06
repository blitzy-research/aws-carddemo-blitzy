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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.util.CobolStringUtils;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.PfKeyTranslator;
import com.carddemo.util.ZonedDecimalCodec;

/**
 * The account-update transaction {@code CAUP}: the largest single translation in the estate.
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy authority {@code app/cbl/COACTUPC.cbl}, read at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The member is 4,236 lines and is one of only
 * three in the estate stored with CRLF line endings, so any count re-derived from it must strip the
 * carriage returns first or it measures zero paragraphs.
 *
 * <p>{@code COACTUPC} is the <strong>sole includer</strong> of four copybooks, which is why the whole
 * validation-lookup surface of the estate is exercised by this one feature:
 * {@code app/cpy/CSLKPCDY.cpy} (the lookup tables, 1,318 lines, included at line 602),
 * {@code app/cpy/CSSETATY.cpy} (the three-token field-decoration macro, 30 lines, expanded 39 times),
 * {@code app/cpy/CSUTLDPY.cpy} (the date cascade, grafted in at line 4232) and
 * {@code app/cpy/CSUTLDWY.cpy} (the date and string work fields, included at line <strong>166</strong>).
 *
 * <h2>Paragraph count: 88 measured against 85 in the action plan</h2>
 *
 * <p>The action plan states 85 paragraphs; the measured figure is <strong>88</strong>. Both are correct
 * and the difference is fully accounted for. A strict Area-A label census inside the
 * {@code PROCEDURE DIVISION}, which begins at line 858, yields exactly 85 labels. The same label shape
 * applied to the whole member yields 88, and the three additional matches are the
 * {@code IDENTIFICATION DIVISION} paragraphs {@code PROGRAM-ID.} (line 22), {@code DATE-WRITTEN.}
 * (line 24) and {@code DATE-COMPILED.} (line 26) - which are genuinely COBOL paragraphs, merely
 * non-procedural ones. So 88 = 3 identification paragraphs + 85 procedure paragraphs, and the action
 * plan counted only the procedure division. All 88 map to a named method here: the 85 procedural
 * paragraphs to the flow methods below, and the three identification paragraphs to
 * {@code programIdParagraph()}, {@code dateWrittenParagraph()} and {@code dateCompiledParagraph()}.
 *
 * <p>Two further Area-A {@code COPY} statements graft procedural text into this member's procedure
 * division and are credited to their own owners rather than duplicated here: {@code COPY 'CSSTRPFY'} at
 * line 4199 contributes two paragraphs, owned by {@code com.carddemo.util.PfKeyTranslator}, and
 * {@code COPY CSUTLDPY} at line 4232 contributes fourteen, owned by {@code DateValidationService}.
 *
 * <h2>The six counter-intuitive behaviours, all reproduced deliberately</h2>
 *
 * <ol>
 * <li><strong>Middle name and address line 2 are decorated but never validated.</strong> The screen
 * attribute block says so outright at lines 3345 and 3369. No constraint of any kind is attached to
 * either field here, and none may be added: doing so would reject input the legacy is documented to
 * accept. The two fields are not, however, alike in the source, and the difference is recorded rather
 * than smoothed over. Address line 2 is described accurately - no edit is performed for it anywhere, and
 * only its label assignment is commented out, at line 1614. The middle name is not: line 1571 performs
 * the optional alphabetic edit on it and that call is live, so the legacy really would reject a middle
 * name carrying a digit, which makes the comment at line 3345 a source defect in its own right and a
 * fourth one beyond the three mislabelled comments catalogued below. The directive governs, the field
 * accepts any input here, and the divergence can only accept more than the legacy rather than reject
 * more.</li>
 * <li><strong>The phone cascade forwards, it does not short-circuit.</strong> Lines 2225 to 2427. A
 * failing stage jumps to the <em>next</em> stage, not to the exit, so all three stages always run and
 * all three field flags are set independently. Area-code failures forward to the prefix stage at lines
 * 2259, 2277, 2291 and 2311; prefix failures forward to the line-number stage at 2330, 2348 and 2362;
 * line-number failures forward to the inner exit at 2383, 2401 and 2415. The cascade is invoked twice,
 * at lines 1632 to 1638 and 1640 to 1646.</li>
 * <li><strong>The credit score edit is gated.</strong> The inclusive 300 to 850 range is declared at
 * lines 848 to 849 over the three-digit numeric redefinition at 845 to 847, with its flag triad at
 * lines 231 to 234, and the edit paragraph at 2514 to 2531 runs <em>only</em> when the flag is still
 * valid, per the test at lines 1553 to 1554. It is online input validation only; there is no
 * persistence constraint, because 21 of the 50 seeded customer rows fall below 300.</li>
 * <li><strong>State and state-plus-postcode are different shapes.</strong> Lines 2493 to 2558. The
 * state test is a flat 56-code membership with no trim, no numeric check and no blank pre-check, and it
 * sets the state flag alone. The composite test builds a four-character positional key and, on failure,
 * sets <em>both</em> the state and the postcode flag and emits an unprefixed message. The 56-code list
 * and the 240-entry composite list are never intersected.</li>
 * <li><strong>The rollback is asymmetric.</strong> Lines 4065 to 4109. The account rewrite's failure arm
 * sets the update-failed state and leaves; the customer rewrite's failure arm sets the same state and
 * then issues the estate's only {@code EXEC CICS SYNCPOINT ROLLBACK} at line 4100. A plain
 * synchronisation point, unrelated to failure, sits at line 953.</li>
 * <li><strong>The write-outcome selection is ordered and it reads the message, not a flag.</strong>
 * Lines 2602 to 2615.</li>
 * </ol>
 *
 * <h2>Three mislabelled source comments - the tokens govern, never the comments</h2>
 *
 * <p>Every one of the 39 decoration call sites below is bound from the macro's substitution tokens, and
 * not one from the comment above it, because three of those comments are wrong:
 *
 * <ul>
 * <li>{@code app/cpy/CSSETATY.cpy} line 17, the macro's own leading comment, is corrupted and ends in a
 * stray {@code ACSHLIM} token;</li>
 * <li>{@code COACTUPC} line 3426 reads {@code EFT Account Id} but precedes the primary-cardholder
 * expansion, and line 3431 reads {@code Primary Card Holder} but precedes the EFT-account expansion -
 * the pair is transposed;</li>
 * <li>{@code COACTUPC} line 3375 reads {@code State} but precedes the postcode expansion.</li>
 * </ul>
 *
 * <p>A fourth stale comment sits at line 2078, which claims only letters and spaces are allowed while
 * the statement at 2079 to 2082 converts the 62-character alphanumeric table. The code governs.
 *
 * <h2>Screen-attribute decoration</h2>
 *
 * <p>{@code grep -c "COPY CSSETATY"} over this member returns 39 exactly, and all 39 expansions live
 * inside the screen-attribute paragraph that runs from line 2986 to its exit at 3437, always
 * substituting against map {@code CACTUPA}. A hand-written commented-out copy of the macro body sits at
 * lines 3196 to 3205 and remains inactive here, exactly as it is there. The macro does three
 * contractual things: it fires only when the re-enter flag is set, it writes the red attribute when the
 * field flag is not-ok <em>or</em> blank, and it writes a {@code '*'} marker only when the flag is
 * blank. Those roughly 234 generated lines collapse into 39 calls to one marking step - the largest
 * single de-duplication in the refactor.
 *
 * <h2>Two verified deviations from the file brief, both evidence-backed</h2>
 *
 * <p><strong>No card repository is consumed.</strong> The brief cites a card lookup "at line 580".
 * Line 580 is the {@code VALUE} clause of the literal {@code LIT-CARDFILENAME-ACCT-PATH}
 * ({@code 'CARDAIX '}); that literal and {@code LIT-CARDFILENAME} ({@code 'CARDDAT '}, lines 577 to
 * 578) are declared and then referenced nowhere in the procedure division. This member copies only
 * {@code CVACT01Y} (line 640), {@code CVACT03Y} (line 643) and {@code CVCUS01Y} (line 646), so it holds
 * no card record area at all, and its only file accesses are the cross-reference path at line 3655, the
 * account master at lines 3704 and 3895, and the customer master at lines 3754 and 3922. There is
 * therefore no card read to reproduce, and inventing one would be feature expansion. The three
 * card-database message constants at lines 514, 516 and 526 are declared-but-unexercised in this member
 * for the same reason.
 *
 * <p><strong>The cross-reference read is bounded to the one row a keyed read returns.</strong>
 * {@code CardCrossReferenceRepository} publishes two forms of the non-unique access path: one that
 * returns every row of an account, and an ordered-first form bounded to a single row in ascending
 * base-key order. This service reproduces a keyed {@code READ}, so it uses the ordered-first form; the
 * lowest card number is the record that read would have returned, because the card number is the
 * cluster's base key. The rule lives in the repository rather than being restated here and in four
 * sibling services. An empty result is the legacy not-found condition.
 *
 * <h2>Rules</h2>
 *
 * <p>{@code review_rules} reports that no user rules were provided, verified by a default read and by
 * an explicit full-range read. No rule-mandated file and no rule conflict apply to this class. That
 * absence is not licence to lower the bar: the work is held to the module's enterprise standards
 * instead. The construct mapping table is a requirement and the eight validation gates are acceptance
 * criteria; neither is a rule.
 *
 * <h2>Shape and thread safety</h2>
 *
 * <p>A stateless singleton. Every collaborator is injected through the constructor and there is no
 * mutable instance field, so concurrent transactions cannot see each other's edit state: all of it
 * lives in a per-invocation {@code EditState} that is created inside the entry point and passed
 * explicitly. Optimistic locking stays declarative on the entity - no pessimistic mode, no lock hint -
 * and this class translates the provider's conflict into
 * {@code OptimisticLockConflictException}, which is recoverable and never routed to
 * {@code AbendService}.
 *
 * <p>The screen turn itself is deliberately non-transactional. Only the two-record rewrite runs
 * inside {@link OnlineTransactionBoundary}; a failed repository unit therefore completes its
 * rollback before this service translates the failure into the source screen outcome.
 *
 * @see AccountUpdateCommand
 * @see AccountUpdateOutcome
 */
@Service
public final class AccountUpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountUpdateService.class);

    /* ------------------------------------------------------------------------------------------
     * Identification division, lines 21 to 27. Held as constants and surfaced by the three named
     * accessors that complete the 88-paragraph mapping.
     * ------------------------------------------------------------------------------------------ */

    /** {@code PROGRAM-ID.} value, line 23; also {@code LIT-THISPGM} at line 534. */
    static final String LEGACY_PROGRAM_ID = "COACTUPC";

    /** {@code DATE-WRITTEN.} value, line 25. */
    static final String LEGACY_DATE_WRITTEN = "July 2022.";

    /** {@code DATE-COMPILED.} value, line 27. */
    static final String LEGACY_DATE_COMPILED = "Today.";

    /* ------------------------------------------------------------------------------------------
     * Literals, lines 533 to 582.
     * ------------------------------------------------------------------------------------------ */

    /** {@code LIT-THISTRANID}, line 536. */
    static final String LEGACY_TRANSACTION_ID = "CAUP";

    /**
     * The character a withheld regulated value is composed of on its way out to an unauthorized caller.
     *
     * <p>Declared here because this is the layer that has to recognise it coming back, and it must be the
     * same character the outbound gate composes with -
     * {@code com.carddemo.api.AccountProtectedDataAdapter.MASK_CHARACTER} - which a suite pins by
     * comparing the two. It cannot be imported from there: that class sits in the boundary layer and
     * nothing in this layer may depend upward.
     */
    static final char WITHHELD_VALUE_CHARACTER = '*';

    /** {@code LIT-THISMAPSET}, line 538, with its trailing pad removed for the response contract. */
    static final String LEGACY_MAPSET = "COACTUP";

    /** {@code LIT-THISMAP}, line 540: the single map every decoration site substitutes against. */
    static final String LEGACY_MAP = "CACTUPA";

    /** {@code LIT-ACCTFILENAME}, line 574, used as the resource name in diagnostics. */
    static final String RESOURCE_ACCOUNT_MASTER = "ACCTDAT";

    /** {@code LIT-CUSTFILENAME}, line 576. */
    static final String RESOURCE_CUSTOMER_MASTER = "CUSTDAT";

    /** {@code LIT-CARDXREFNAME-ACCT-PATH}, line 582: the alternate-index path actually read. */
    static final String RESOURCE_CARD_XREF_PATH = "CXACAIX";

    // ==============================================================================================
    // WS-FILE-ERROR-MESSAGE, lines 389 to 408: the text every catch-all read arm composes
    // ==============================================================================================

    /** {@code ERROR-OPNAME} as every catch-all arm sets it, lines 3687, 3737 and 3786. */
    static final String OPERATION_READ = "READ";

    /** Segment one, {@code FILLER PIC X(12) VALUE 'File Error: '}, lines 390 to 391. */
    private static final String FILE_ERROR_PREFIX = "File Error: ";

    /** Segment three, {@code FILLER PIC X(4) VALUE ' on '}, lines 394 to 395. */
    private static final String FILE_ERROR_ON = " on ";

    /** Segment five, {@code FILLER PIC X(15) VALUE ' returned RESP '}, lines 398 to 400. */
    private static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    /** Segment seven, {@code FILLER PIC X(7) VALUE ',RESP2 '}, lines 403 to 404. */
    private static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** {@code ERROR-OPNAME}, {@code PIC X(8)} at lines 392 to 393. */
    private static final int OPERATION_NAME_WIDTH = 8;

    /** {@code ERROR-FILE}, {@code PIC X(9)} at lines 396 to 397. */
    private static final int ERROR_FILE_NAME_WIDTH = 9;

    /** {@code ERROR-RESP} and {@code ERROR-RESP2}, each {@code PIC X(10)}. */
    private static final int RESPONSE_CODE_WIDTH = 10;

    /**
     * {@code WS-RETURN-MSG}, {@code PIC X(75)} at line 479.
     *
     * <p>The eight composed segments sum to exactly 12 + 8 + 4 + 9 + 15 + 10 + 7 + 10 = 75, so the
     * assembled text fills this field precisely and the five-character trailing filler at lines 407 to
     * 408 falls outside it. The legacy truncation is therefore reproduced by construction.
     */
    private static final int RETURN_MESSAGE_WIDTH = 75;

    /** {@code ABEND-CODE} written by the dispatch selection's otherwise branch, line 2635. */
    private static final String ABEND_CODE_UNEXPECTED_DATA = "0001";

    /** {@code ABEND-MSG} written by the same branch, lines 2637 to 2638. */
    private static final String ABEND_MSG_UNEXPECTED_DATA = "UNEXPECTED DATA SCENARIO";

    /** The default the abend routine substitutes when no message was supplied, line 4206. */
    private static final String ABEND_MSG_DEFAULT = "UNEXPECTED ABEND OCCURRED.";

    /** {@code ABCODE} the routine abends with, line 4223. */
    private static final String ABEND_ABCODE = "9999";

    /** {@code ACCTSID}: the account key field, protected at line 3442 and freed at line 2996. */
    private static final String BMS_ACCOUNT_ID = "ACCTSID";

    /** {@code AADDGRP}: the account group field, freed at line 3529; not a decorated field. */
    private static final String BMS_ACCOUNT_GROUP_ID = "AADDGRP";

    /** {@code ACSGOVT}: the government-issued identifier, freed at line 3557; not decorated. */
    private static final String BMS_GOVERNMENT_ISSUED_ID = "ACSGOVT";

    /** {@code ACSTNUM}: the customer number, deliberately left protected at line 3531. */
    private static final String BMS_CUSTOMER_NUMBER = "ACSTNUM";

    /** {@code INFOMSG}: the information line, protected at line 3560 and attributed at 3567. */
    private static final String BMS_INFO_MESSAGE = "INFOMSG";

    /** {@code FKEY05}: the save-key legend, highlighted at line 3579. */
    private static final String BMS_FUNCTION_KEY_05 = "FKEY05";

    /** {@code FKEY12}: the cancel-key legend, highlighted at lines 3575 and 3580. */
    private static final String BMS_FUNCTION_KEY_12 = "FKEY12";

    /* ------------------------------------------------------------------------------------------
     * Information messages: the WS-INFO-MSG condition names at lines 463 to 477. Reproduced
     * verbatim, punctuation and spacing included.
     * ------------------------------------------------------------------------------------------ */

    /** {@code FOUND-ACCOUNT-DATA}, line 467. */
    static final String INFO_FOUND_ACCOUNT_DATA = "Details of selected account shown above";

    /** {@code PROMPT-FOR-SEARCH-KEYS}, line 469. */
    static final String INFO_PROMPT_FOR_SEARCH_KEYS = "Enter or update id of account to update";

    /** {@code PROMPT-FOR-CHANGES}, line 471. Carries a trailing full stop; the others do not. */
    static final String INFO_PROMPT_FOR_CHANGES = "Update account details presented above.";

    /** {@code PROMPT-FOR-CONFIRMATION}, line 473. No space after the full stop, exactly as declared. */
    static final String INFO_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    /** {@code CONFIRM-UPDATE-SUCCESS}, line 475. */
    static final String INFO_CONFIRM_UPDATE_SUCCESS = "Changes committed to database";

    /** {@code INFORM-FAILURE}, line 477. */
    static final String INFO_INFORM_FAILURE = "Changes unsuccessful. Please try again";

    /* ------------------------------------------------------------------------------------------
     * Return messages: the WS-RETURN-MSG condition names at lines 479 to 528, plus the two texts
     * the edit paragraphs compose inline rather than through a condition name.
     *
     * The eleven texts of the block at lines 495 to 528 that the response contract already
     * declares are reused from AccountUpdateOutcome, and the four locking and conflict texts at
     * lines 518, 520, 522 and 524 are reused from OptimisticLockConflictException. Nothing in that
     * block is re-declared here.
     * ------------------------------------------------------------------------------------------ */

    /** {@code WS-EXIT-MESSAGE}, line 482. The fourteen trailing spaces are part of the literal. */
    static final String MSG_EXIT = "PF03 pressed.Exiting              ";

    /** {@code WS-PROMPT-FOR-ACCT}, line 484. */
    static final String MSG_ACCOUNT_NUMBER_NOT_PROVIDED = "Account number not provided";

    /** {@code NO-SEARCH-CRITERIA-RECEIVED}, line 490. */
    static final String MSG_NO_INPUT_RECEIVED = "No input received";

    /** {@code NO-CHANGES-DETECTED}, line 492. */
    static final String MSG_NO_CHANGES_DETECTED =
            "No change detected with respect to values fetched.";

    /** {@code DID-NOT-FIND-ACCT-IN-CARDXREF} as first declared, line 498. */
    static final String MSG_ACCOUNT_NOT_IN_XREF =
            "Did not find this account in account card xref file";

    /** {@code DID-NOT-FIND-ACCT-IN-ACCTDAT}, line 500. */
    static final String MSG_ACCOUNT_NOT_IN_MASTER =
            "Did not find this account in account master file";

    /** {@code DID-NOT-FIND-CUST-IN-CUSTDAT}, line 502. */
    static final String MSG_CUSTOMER_NOT_IN_MASTER =
            "Did not find associated customer in master file";

    /**
     * The literal the account-key edit actually composes at lines 1806 to 1810.
     *
     * <p>Source anomaly. Two condition names at lines 493 to 496 declare
     * {@code Account number must be a non zero 11 digit number} for exactly this outcome, and the
     * response contract publishes that text, but the live edit path composes this different wording
     * instead and never sets either condition name. Both are recorded; the live path emits this one.
     */
    static final String MSG_ACCOUNT_NUMBER_MALFORMED =
            "Account Number if supplied must be a 11 digit Non-Zero Number";

    /** Suffix composed after the trimmed field label by every blank-field arm. */
    static final String SUFFIX_MUST_BE_SUPPLIED = " must be supplied.";

    /** Suffix of the yes-or-no edit's second arm, lines 1884 to 1889. */
    static final String SUFFIX_MUST_BE_Y_OR_N = " must be Y or N.";

    /** Suffix of both alphabetic edits, lines 1939 to 1944 and 2045 to 2050. */
    static final String SUFFIX_ALPHABETS_ONLY = " can have alphabets only.";

    /** Suffix of both alphanumeric edits, lines 1997 to 2002 and 2093 to 2098. */
    static final String SUFFIX_NUMBERS_OR_ALPHABETS_ONLY = " can have numbers or alphabets only.";

    /** Suffix of the numeric edit's character-class arm, lines 2144 to 2149. */
    static final String SUFFIX_MUST_BE_ALL_NUMERIC = " must be all numeric.";

    /** Suffix of the numeric edit's zero arm, lines 2161 to 2166. */
    static final String SUFFIX_MUST_NOT_BE_ZERO = " must not be zero.";

    /** Suffix of the signed-amount edit's format arm, lines 2207 to 2211. No trailing full stop. */
    static final String SUFFIX_IS_NOT_VALID = " is not valid";

    /** Suffix of the national-identifier range arm, line 2457. No trailing full stop. */
    static final String SUFFIX_SSN_PART1_OUT_OF_RANGE =
            ": should not be 000, 666, or between 900 and 999";

    /* ------------------------------------------------------------------------------------------
     * Field labels moved into WS-EDIT-VARIABLE-NAME before each edit is performed. Every composed
     * message is the trimmed label followed by one of the suffixes above.
     * ------------------------------------------------------------------------------------------ */

    private static final String LABEL_ACCOUNT_STATUS = "Account Status";
    private static final String LABEL_OPEN_DATE = "Open Date";
    private static final String LABEL_CREDIT_LIMIT = "Credit Limit";
    private static final String LABEL_EXPIRY_DATE = "Expiry Date";
    private static final String LABEL_CASH_CREDIT_LIMIT = "Cash Credit Limit";
    private static final String LABEL_REISSUE_DATE = "Reissue Date";
    private static final String LABEL_CURRENT_BALANCE = "Current Balance";
    private static final String LABEL_CURRENT_CYCLE_CREDIT = "Current Cycle Credit Limit";
    private static final String LABEL_CURRENT_CYCLE_DEBIT = "Current Cycle Debit Limit";
    private static final String LABEL_SSN_PART1 = "SSN: First 3 chars";
    private static final String LABEL_SSN_PART2 = "SSN 4th & 5th chars";
    private static final String LABEL_SSN_PART3 = "SSN Last 4 chars";
    private static final String LABEL_DATE_OF_BIRTH = "Date of Birth";
    private static final String LABEL_FICO_SCORE = "FICO Score";
    private static final String LABEL_FIRST_NAME = "First Name";
    private static final String LABEL_MIDDLE_NAME = "Middle Name";
    private static final String LABEL_LAST_NAME = "Last Name";
    private static final String LABEL_ADDRESS_LINE_1 = "Address Line 1";
    private static final String LABEL_STATE = "State";
    private static final String LABEL_ZIP = "Zip";
    private static final String LABEL_CITY = "City";
    private static final String LABEL_COUNTRY = "Country";
    private static final String LABEL_PHONE_NUMBER_1 = "Phone Number 1";
    private static final String LABEL_PHONE_NUMBER_2 = "Phone Number 2";
    private static final String LABEL_EFT_ACCOUNT_ID = "EFT Account Id";
    private static final String LABEL_PRIMARY_CARD_HOLDER = "Primary Card Holder";

    /* ------------------------------------------------------------------------------------------
     * Widths and ranges the edits assert, taken from the picture clauses that declare them.
     * ------------------------------------------------------------------------------------------ */

    /** {@code WS-EDIT-US-PHONE-NUMA} and {@code -NUMB}, lines 87 and 92. */
    private static final int PHONE_AREA_AND_PREFIX_WIDTH = 3;

    /** {@code WS-EDIT-US-PHONE-NUMC}, line 97. */
    private static final int PHONE_LINE_NUMBER_WIDTH = 4;

    /** Length the account key must occupy, from {@code CC-ACCT-ID PIC X(11)}. */
    private static final int ACCOUNT_KEY_WIDTH = 11;

    /** Length the customer key must occupy, from {@code CC-CUST-ID PIC X(9)}. */
    private static final int CUSTOMER_KEY_WIDTH = 9;

    /** Characters of the postcode that join the state code, from the substring at line 2538. */
    private static final int ZIP_PREFIX_WIDTH = 2;

    /**
     * Width of the postal-code item on the screen, from {@code ACSZIPC} of the symbolic map.
     *
     * <p>The stored column is ten characters wide and the map item is five, and the presentation move at
     * line 2843 crosses that boundary by discarding the surplus. The width is stated once here so the
     * screen and the response contract cannot drift apart.
     */
    private static final int ZIP_CODE_SCREEN_WIDTH = 5;

    /** Inclusive lower bound of {@code FICO-RANGE-IS-VALID}, line 848. */
    static final int FICO_SCORE_MINIMUM = 300;

    /** Inclusive upper bound of {@code FICO-RANGE-IS-VALID}, line 849. */
    static final int FICO_SCORE_MAXIMUM = 850;

    /** The three excluded single values and range bound of {@code INVALID-SSN-PART1}, lines 121-123. */
    private static final int SSN_PART1_EXCLUDED_ZERO = 0;
    private static final int SSN_PART1_EXCLUDED_666 = 666;
    private static final int SSN_PART1_EXCLUDED_RANGE_START = 900;
    private static final int SSN_PART1_EXCLUDED_RANGE_END = 999;

    /**
     * The separator-delimited stored form of a date, written by the account write path at lines 3976
     * to 4000 and read back positionally by the change check at lines 4127 to 4137.
     *
     * <p>A pattern rather than fixed-width slicing on purpose: record-image offsets and picture widths
     * belong to {@code com.carddemo.util}, not to a service. Non-digit content and a missing separator
     * both fail to match, and a failure yields absent parts, which is the state the initial screen
     * shows.
     */
    private static final Pattern STORED_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    /**
     * The stored form of a telephone number, documented by the edit paragraph's own comment at lines
     * 2227 to 2228 as {@code (999)999-9999} inside a fifteen-character field, and written in exactly
     * that shape by the customer write path at lines 4027 to 4041.
     */
    private static final Pattern STORED_PHONE =
            Pattern.compile("\\((\\d{3})\\)(\\d{3})-(\\d{4})");

    /** The nine-digit national identifier as stored, split into the three keyed screen components. */
    private static final Pattern STORED_SSN = Pattern.compile("(\\d{3})(\\d{2})(\\d{4})");

    /**
     * {@code WS-CURDATE-MM-DD-YY}, the eight-character screen date built at lines 2680 to 2684.
     *
     * <p>{@code Locale.ROOT} is mandatory rather than tidy. Both of these fields are fixed-width screen
     * items, and a formatter built without an explicit locale renders digits in whatever numbering system
     * the default locale prescribes - Arabic-Indic digits under an Arabic locale, for instance - so the
     * same instant would produce a different byte sequence on a differently configured host. That is a
     * parity defect in a byte-exact format, not a presentation preference. This is the same term the
     * sibling user-administration service states for its own header date and time.</p>
     */
    private static final DateTimeFormatter SCREEN_DATE =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * {@code WS-CURTIME-HH-MM-SS}, the eight-character screen time built at lines 2686 to 2690, on the
     * same locale terms as the date above.
     */
    private static final DateTimeFormatter SCREEN_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * The three states every field-validation flag in this program can hold.
     *
     * <p>The estate declares this triad once per field as a level-88 group - the generic set at lines
     * 57 to 75 and the per-field sets from 183 to 352 - and always with the same three values: the null
     * byte for valid, {@code '0'} for supplied-but-wrong and {@code 'B'} for blank. A boolean cannot
     * carry it, because the decoration macro distinguishes the two failing states: both colour the
     * field, but only blank writes the {@code '*'} marker.
     *
     * <p>Two of the fields declare their valid state as the literal set {@code 'Y'} or {@code 'N'}
     * rather than the null byte - the account status at line 193 and the primary-cardholder indicator at
     * line 350 - which changes the stored byte but not the three-state shape, so they use this triad
     * too.
     */
    public enum FieldFlag {

        /** The field passed every edit that applies to it. Produces no error entry. */
        ISVALID,

        /** The field was supplied and failed an edit. Colours the field; writes no marker. */
        NOT_OK,

        /** The field was not supplied at all. Colours the field and writes the {@code '*'} marker. */
        BLANK;

        /** @return whether the field passed */
        public boolean isValid() {
            return this == ISVALID;
        }

        /** @return whether the field was supplied and rejected */
        public boolean isNotOk() {
            return this == NOT_OK;
        }

        /** @return whether the field was not supplied */
        public boolean isBlank() {
            return this == BLANK;
        }

        /**
         * The macro's outer condition at {@code app/cpy/CSSETATY.cpy} lines 18 to 19: not-ok
         * <em>or</em> blank.
         *
         * @return whether the field is to be coloured
         */
        public boolean requiresDecoration() {
            return this != ISVALID;
        }

        /**
         * The macro's inner condition at {@code app/cpy/CSSETATY.cpy} line 23: blank only.
         *
         * @return whether the {@code '*'} marker is written as well
         */
        public boolean writesMissingMarker() {
            return this == BLANK;
        }
    }

    /**
     * {@code ACUP-CHANGE-ACTION} and its condition names, lines 654 to 668.
     *
     * <p>Declaration order is preserved because the dispatch selection at lines 2563 onward is
     * evaluated top down and stops at the first match, and several of these states overlap through the
     * grouping conditions {@code ACUP-CHANGES-MADE} and {@code ACUP-CHANGES-FAILED}.
     */
    public enum ChangeAction {

        /** {@code ACUP-DETAILS-NOT-FETCHED}, line 656: the null byte or spaces. */
        DETAILS_NOT_FETCHED(' '),

        /** {@code ACUP-SHOW-DETAILS}, line 659. */
        SHOW_DETAILS('S'),

        /** {@code ACUP-CHANGES-NOT-OK}, line 663. */
        CHANGES_NOT_OK('E'),

        /** {@code ACUP-CHANGES-OK-NOT-CONFIRMED}, line 664. */
        CHANGES_OK_NOT_CONFIRMED('N'),

        /** {@code ACUP-CHANGES-OKAYED-AND-DONE}, line 665. */
        CHANGES_OKAYED_AND_DONE('C'),

        /** {@code ACUP-CHANGES-OKAYED-LOCK-ERROR}, line 667; also part of the failed group. */
        CHANGES_OKAYED_LOCK_ERROR('L'),

        /** {@code ACUP-CHANGES-OKAYED-BUT-FAILED}, line 668; also part of the failed group. */
        CHANGES_OKAYED_BUT_FAILED('F');

        private final char code;

        ChangeAction(final char code) {
            this.code = code;
        }

        /** @return the byte the legacy field holds for this state */
        public char getCode() {
            return this.code;
        }

        /**
         * {@code ACUP-CHANGES-MADE}, lines 660 to 662: the five-value group {@code E N C L F}.
         *
         * @return whether any change has been made in this conversation
         */
        public boolean changesMade() {
            return this != DETAILS_NOT_FETCHED && this != SHOW_DETAILS;
        }

        /**
         * {@code ACUP-CHANGES-FAILED}, line 666: the two-value group {@code L F}.
         *
         * @return whether the write was attempted and did not complete
         */
        public boolean changesFailed() {
            return this == CHANGES_OKAYED_LOCK_ERROR || this == CHANGES_OKAYED_BUT_FAILED;
        }
    }

    /**
     * The 39 decorated screen fields, in the exact order the macro is expanded.
     *
     * <p>Declaration order <strong>is</strong> the contract. It is the order of the 39
     * {@code COPY CSSETATY} sites between lines 3208 and 3432, and therefore the order in which field
     * errors are reported. Two oddities are preserved rather than tidied: the state code sits between
     * address line 1 and address line 2, and the postcode precedes the city and the country. Sorting
     * these into a conventional address order would change the reported order and the focus field.
     *
     * <p>Each constant carries four things: the response component name, the seven-character BMS field
     * identifier the macro substitutes, the macro's own validation-flag token, and the label the driver
     * moves into {@code WS-EDIT-VARIABLE-NAME} before performing the edit.
     */
    public enum ScreenField {

        /** Line 3208. */
        ACCT_STATUS("accountStatus", "ACSTTUS", "ACCT-STATUS", LABEL_ACCOUNT_STATUS),
        /** Line 3214. */
        OPEN_YEAR("openYear", "OPNYEAR", "OPEN-YEAR", LABEL_OPEN_DATE),
        /** Line 3220. */
        OPEN_MONTH("openMonth", "OPNMON", "OPEN-MONTH", LABEL_OPEN_DATE),
        /** Line 3226. */
        OPEN_DAY("openDay", "OPNDAY", "OPEN-DAY", LABEL_OPEN_DATE),
        /** Line 3232. */
        CRED_LIMIT("creditLimit", "ACRDLIM", "CRED-LIMIT", LABEL_CREDIT_LIMIT),
        /** Line 3238. */
        EXPIRY_YEAR("expiryYear", "EXPYEAR", "EXPIRY-YEAR", LABEL_EXPIRY_DATE),
        /** Line 3244. */
        EXPIRY_MONTH("expiryMonth", "EXPMON", "EXPIRY-MONTH", LABEL_EXPIRY_DATE),
        /** Line 3250. */
        EXPIRY_DAY("expiryDay", "EXPDAY", "EXPIRY-DAY", LABEL_EXPIRY_DATE),
        /** Line 3256. */
        CASH_CREDIT_LIMIT("cashCreditLimit", "ACSHLIM", "CASH-CREDIT-LIMIT",
                LABEL_CASH_CREDIT_LIMIT),
        /** Line 3262. */
        REISSUE_YEAR("reissueYear", "RISYEAR", "REISSUE-YEAR", LABEL_REISSUE_DATE),
        /** Line 3268. */
        REISSUE_MONTH("reissueMonth", "RISMON", "REISSUE-MONTH", LABEL_REISSUE_DATE),
        /** Line 3274. */
        REISSUE_DAY("reissueDay", "RISDAY", "REISSUE-DAY", LABEL_REISSUE_DATE),
        /** Line 3280. */
        CURR_BAL("currentBalance", "ACURBAL", "CURR-BAL", LABEL_CURRENT_BALANCE),
        /** Line 3286. */
        CURR_CYC_CREDIT("currentCycleCredit", "ACRCYCR", "CURR-CYC-CREDIT",
                LABEL_CURRENT_CYCLE_CREDIT),
        /** Line 3292. */
        CURR_CYC_DEBIT("currentCycleDebit", "ACRCYDB", "CURR-CYC-DEBIT",
                LABEL_CURRENT_CYCLE_DEBIT),
        /** Line 3298. */
        EDIT_US_SSN_PART1("ssnPart1", "ACTSSN1", "EDIT-US-SSN-PART1", LABEL_SSN_PART1),
        /** Line 3304. */
        EDIT_US_SSN_PART2("ssnPart2", "ACTSSN2", "EDIT-US-SSN-PART2", LABEL_SSN_PART2),
        /** Line 3310. */
        EDIT_US_SSN_PART3("ssnPart3", "ACTSSN3", "EDIT-US-SSN-PART3", LABEL_SSN_PART3),
        /** Line 3316. */
        DT_OF_BIRTH_YEAR("dateOfBirthYear", "DOBYEAR", "DT-OF-BIRTH-YEAR", LABEL_DATE_OF_BIRTH),
        /** Line 3322. */
        DT_OF_BIRTH_MONTH("dateOfBirthMonth", "DOBMON", "DT-OF-BIRTH-MONTH", LABEL_DATE_OF_BIRTH),
        /** Line 3328. */
        DT_OF_BIRTH_DAY("dateOfBirthDay", "DOBDAY", "DT-OF-BIRTH-DAY", LABEL_DATE_OF_BIRTH),
        /** Line 3334. */
        FICO_SCORE("ficoScore", "ACSTFCO", "FICO-SCORE", LABEL_FICO_SCORE),
        /** Line 3340. */
        FIRST_NAME("firstName", "ACSFNAM", "FIRST-NAME", LABEL_FIRST_NAME),
        /** Line 3346. Decorated, never validated - see line 3345. */
        MIDDLE_NAME("middleName", "ACSMNAM", "MIDDLE-NAME", LABEL_MIDDLE_NAME),
        /** Line 3352. */
        LAST_NAME("lastName", "ACSLNAM", "LAST-NAME", LABEL_LAST_NAME),
        /** Line 3358. */
        ADDRESS_LINE_1("addressLine1", "ACSADL1", "ADDRESS-LINE-1", LABEL_ADDRESS_LINE_1),
        /** Line 3364, between address line 1 and address line 2. Order preserved deliberately. */
        STATE("stateCode", "ACSSTTE", "STATE", LABEL_STATE),
        /** Line 3370. Decorated, never validated - see line 3369. */
        ADDRESS_LINE_2("addressLine2", "ACSADL2", "ADDRESS-LINE-2", "Address Line 2"),
        /** Line 3376, ahead of the city and the country. The comment at 3375 is the wrong one. */
        ZIPCODE("zipCode", "ACSZIPC", "ZIPCODE", LABEL_ZIP),
        /** Line 3382. */
        CITY("city", "ACSCITY", "CITY", LABEL_CITY),
        /** Line 3388. */
        COUNTRY("countryCode", "ACSCTRY", "COUNTRY", LABEL_COUNTRY),
        /** Line 3394. */
        PHONE_NUM_1A("phone1AreaCode", "ACSPH1A", "PHONE-NUM-1A", LABEL_PHONE_NUMBER_1),
        /** Line 3400. */
        PHONE_NUM_1B("phone1Prefix", "ACSPH1B", "PHONE-NUM-1B", LABEL_PHONE_NUMBER_1),
        /** Line 3405. */
        PHONE_NUM_1C("phone1LineNumber", "ACSPH1C", "PHONE-NUM-1C", LABEL_PHONE_NUMBER_1),
        /** Line 3411. */
        PHONE_NUM_2A("phone2AreaCode", "ACSPH2A", "PHONE-NUM-2A", LABEL_PHONE_NUMBER_2),
        /** Line 3417. */
        PHONE_NUM_2B("phone2Prefix", "ACSPH2B", "PHONE-NUM-2B", LABEL_PHONE_NUMBER_2),
        /** Line 3422. */
        PHONE_NUM_2C("phone2LineNumber", "ACSPH2C", "PHONE-NUM-2C", LABEL_PHONE_NUMBER_2),
        /** Line 3427. The comment at 3426 names the other field; the token governs. */
        PRI_CARDHOLDER("primaryCardHolderIndicator", "ACSPFLG", "PRI-CARDHOLDER",
                LABEL_PRIMARY_CARD_HOLDER),
        /** Line 3432. The comment at 3431 names the other field; the token governs. */
        EFT_ACCOUNT_ID("eftAccountId", "ACSEFTC", "EFT-ACCOUNT-ID", LABEL_EFT_ACCOUNT_ID);

        private final String fieldName;
        private final String bmsFieldId;
        private final String legacyFlagToken;
        private final String legacyLabel;

        ScreenField(final String fieldName, final String bmsFieldId, final String legacyFlagToken,
                final String legacyLabel) {
            this.fieldName = fieldName;
            this.bmsFieldId = bmsFieldId;
            this.legacyFlagToken = legacyFlagToken;
            this.legacyLabel = legacyLabel;
        }

        /** @return the response component name this field reports itself under */
        public String getFieldName() {
            return this.fieldName;
        }

        /** @return the seven-character BMS field identifier the macro substitutes for the map */
        public String getBmsFieldId() {
            return this.bmsFieldId;
        }

        /** @return the macro's validation-flag token, which is what every binding here follows */
        public String getLegacyFlagToken() {
            return this.legacyFlagToken;
        }

        /** @return the label the driver moves into the edit-variable-name field */
        public String getLegacyLabel() {
            return this.legacyLabel;
        }

        /**
         * The two fields the source decorates but never edits: the middle name, whose comment at line
         * 3345 reads "no edits coded", and address line 2, whose comment at line 3369 reads "NO EDITS
         * CODED AS YET" and whose edit call is commented out at lines 1613 to 1614.
         *
         * @return whether no edit of any kind may be attached to this field
         */
        public boolean neverValidated() {
            return this == MIDDLE_NAME || this == ADDRESS_LINE_2;
        }
    }

    /** Which of the two ordered rewrites is active when the transactional boundary fails. */
    private enum RewriteStage {
        ACCOUNT,
        CUSTOMER
    }

    /**
     * Everything the legacy holds in {@code WS-MISC-STORAGE} and {@code WS-THIS-PROGCOMMAREA} for the
     * duration of one turn, created fresh inside the entry point and passed explicitly to every
     * paragraph method.
     *
     * <p>This exists so the service itself can stay a stateless singleton. The legacy initialises these
     * areas at line 866 on every entry, so a per-invocation holder is the faithful translation as well
     * as the thread-safe one: nothing survives a turn except what the response carries back.
     */
    private static final class EditState {

        /** {@code WS-NON-KEY-FLAGS} and its neighbours: one three-state flag per decorated field. */
        private final Map<ScreenField, FieldFlag> flags = new EnumMap<>(ScreenField.class);

        /** The accumulating decoration, rebuilt on each mark because the decorator is immutable. */
        private FieldErrorMarks decoration = FieldErrorMarks.none();

        /** Per-field messages, keyed by field, so a caller can attribute the summary text. */
        private final Map<ScreenField, String> fieldMessages = new EnumMap<>(ScreenField.class);

        /** {@code WS-INPUT-FLAG}: set by every failing edit, lines 171 to 174. */
        private boolean inputError;

        /** {@code WS-RETURN-MSG}, line 479. Empty stands for the off condition at line 480. */
        private String returnMessage = "";

        /** {@code WS-INFO-MSG}, line 463. Empty stands for the no-message condition at line 464. */
        private String infoMessage = "";

        /** {@code WS-EDIT-VARIABLE-NAME}, line 53: the label the current edit composes against. */
        private String label = "";

        /** {@code WS-EDIT-ACCT-FLAG}, lines 183 to 186. */
        private FieldFlag accountFilter = FieldFlag.NOT_OK;

        /** {@code WS-EDIT-CUST-FLAG}, lines 187 to 190. */
        private FieldFlag customerFilter = FieldFlag.NOT_OK;

        /** {@code WS-DATACHANGED-FLAG}, lines 168 to 170. */
        private boolean changeHasOccurred;

        /** {@code WS-PFK-FLAG}, lines 178 to 180. */
        private boolean pfkInvalid;

        /** {@code WS-ACCOUNT-MASTER-READ-FLAG}, line 385. */
        private boolean foundAccountInMaster;

        /** {@code WS-CUST-MASTER-READ-FLAG}, line 387. */
        private boolean foundCustomerInMaster;

        /** {@code ACUP-CHANGE-ACTION}: the conversation state this turn started in and ends in. */
        private ChangeAction action = ChangeAction.DETAILS_NOT_FETCHED;

        /** {@code CC-ACCT-ID} of the screen work area, carried through every read. */
        private String accountId = "";

        /** {@code CDEMO-CUST-ID}, resolved from the cross-reference. */
        private String customerId = "";

        /** {@code CDEMO-CARD-NUM}, resolved from the cross-reference. */
        private String cardNumber = "";

        /** The account as fetched, standing for {@code ACCOUNT-RECORD}. */
        private Account account;

        /** The customer as fetched, standing for {@code CUSTOMER-RECORD}. */
        private Customer customer;

        /** The token that replaces the old-image copy the commarea extension used to carry. */
        private String concurrencyToken = "";

        /**
         * The submission as the receive paragraph leaves it, standing for {@code ACUP-NEW-DETAILS}.
         *
         * <p>{@code 1100-RECEIVE-MAP} moves every transmitted field into working storage and every
         * paragraph after it reads working storage rather than the map buffer. This translation takes the
         * command itself as the received map, which is faithful for the forty-three fields it copies
         * unchanged; the exception is the regulated group, whose withheld stand-in cannot be told from a
         * typed value until the old image has been read. Once it has, the restored submission is held here
         * so that the comparison, the edits, the write and the redisplay all read one received image -
         * exactly as they all read one working-storage group in the source.
         *
         * <p>{@code null} until the edit driver establishes it, which is every path that does not reach
         * the driver at all; {@link #receivedDetails(EditState, AccountUpdateCommand)} answers the
         * submission itself on those paths.
         */
        private AccountUpdateCommand receivedDetails;

        /** The ordered rewrite currently executing inside the independent transaction. */
        private RewriteStage rewriteStage = RewriteStage.ACCOUNT;

        /**
         * The ordered BMS identifiers the attribute paragraphs leave unprotected. The 3270 attribute
         * bytes themselves have no place in the response contract, but the cursor position they imply
         * does: it becomes the focus field whenever no field is in error.
         */
        private List<String> unprotectedFieldIds = List.of();

        private EditState() {
            for (final ScreenField field : ScreenField.values()) {
                this.flags.put(field, FieldFlag.ISVALID);
            }
        }

        /**
         * {@code MOVE LOW-VALUES TO WS-NON-KEY-FLAGS}, at lines 1466 and 1471 and again after a
         * no-change determination: every field flag returns to its valid state.
         */
        private void clearFieldFlags() {
            for (final ScreenField field : ScreenField.values()) {
                this.flags.put(field, FieldFlag.ISVALID);
            }
            this.fieldMessages.clear();
            this.decoration = FieldErrorMarks.none();
        }

        private FieldFlag flag(final ScreenField field) {
            return this.flags.get(field);
        }

        private void setFlag(final ScreenField field, final FieldFlag value) {
            this.flags.put(field, value);
        }

        /**
         * The first-error-wins gate that wraps <em>every</em> message assignment in this program:
         * {@code IF WS-RETURN-MSG-OFF} guards each {@code STRING ... INTO WS-RETURN-MSG}. The outcome
         * of a turn is therefore one summary message plus as many independent field errors as the edits
         * found.
         *
         * @param text the text this edit would like to claim the summary slot with
         */
        private void claimMessage(final String text) {
            if (this.returnMessage.isEmpty()) {
                this.returnMessage = text;
            }
        }

        /** {@code SET WS-RETURN-MSG-OFF TO TRUE}, at lines 876 and 2574. */
        private void clearMessage() {
            this.returnMessage = "";
        }

        /** {@code SET INPUT-ERROR TO TRUE}, at every failing arm. */
        private void markInputError() {
            this.inputError = true;
        }

        /**
         * Records a field failure: sets the flag, claims the summary slot through the gate, and keeps
         * the composed text against the field so the response can attribute it.
         *
         * @param field the field that failed
         * @param value the failing state, never the valid one
         * @param text  the composed message for this failure
         */
        private void fail(final ScreenField field, final FieldFlag value, final String text) {
            markInputError();
            setFlag(field, value);
            this.fieldMessages.put(field, text);
            claimMessage(text);
        }
    }

    /* ------------------------------------------------------------------------------------------
     * Collaborators. Constructor injection only, every field final, no setter anywhere.
     * ------------------------------------------------------------------------------------------ */

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final DateValidationService dateValidationService;
    private final ValidationLookupService validationLookupService;
    private final MessageCatalogService messageCatalogService;
    private final NavigationService navigationService;
    private final AbendService abendService;
    private final AccountConcurrencyTokenService concurrencyTokenService;
    private final SensitiveFieldEncryptionService fieldEncryption;
    private final OnlineTransactionBoundary transactionBoundary;
    private final Clock clock;

    /**
     * Creates the service with every collaborator the legacy program reached for.
     *
     * <p>The three repositories replace the three files this member opens; the date service owns the
     * fourteen-paragraph cascade grafted in at line 4232; the lookup service owns the tables included at
     * line 602; the catalogue owns the common messages; the navigation service owns the dispatch graph;
     * the abend service owns the routine at lines 4203 to 4228; the concurrency-token service owns the
     * change check at lines 4109 to 4193; the encryption service produces and reads the protected form
     * the customer entity requires for its two regulated identifiers; and the clock supplies the current
     * date the date-of-birth check compares against and the screen header shows.
     *
     * <p>No card repository is injected. That is deliberate and evidenced: the two card-file literals at
     * lines 577 to 580 are declared and never referenced, and this member copies no card record layout,
     * so there is no card access to reproduce.
     *
     * @param accountRepository            the account master
     * @param customerRepository           the customer master
     * @param cardCrossReferenceRepository the cross-reference read through its account path
     * @param dateValidationService        the date cascade and the date-of-birth check
     * @param validationLookupService      the area-code, state and state-plus-postcode tables
     * @param messageCatalogService        the common message catalogue
     * @param navigationService            the route resolver
     * @param abendService                 the abend routine
     * @param concurrencyTokenService      the change-before-update check
     * @param fieldEncryption              the protected-value producer and reader
     * @param transactionBoundary          the independent account-plus-customer rewrite unit
     * @param clock                        the current-date source
     */
    public AccountUpdateService(final AccountRepository accountRepository,
            final CustomerRepository customerRepository,
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final DateValidationService dateValidationService,
            final ValidationLookupService validationLookupService,
            final MessageCatalogService messageCatalogService,
            final NavigationService navigationService,
            final AbendService abendService,
            final AccountConcurrencyTokenService concurrencyTokenService,
            final SensitiveFieldEncryptionService fieldEncryption,
            final OnlineTransactionBoundary transactionBoundary,
            final Clock clock) {
        this.accountRepository =
                Objects.requireNonNull(accountRepository, "accountRepository must not be null");
        this.customerRepository =
                Objects.requireNonNull(customerRepository, "customerRepository must not be null");
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.dateValidationService = Objects.requireNonNull(dateValidationService,
                "dateValidationService must not be null");
        this.validationLookupService = Objects.requireNonNull(validationLookupService,
                "validationLookupService must not be null");
        this.messageCatalogService = Objects.requireNonNull(messageCatalogService,
                "messageCatalogService must not be null");
        this.navigationService =
                Objects.requireNonNull(navigationService, "navigationService must not be null");
        this.abendService = Objects.requireNonNull(abendService, "abendService must not be null");
        this.concurrencyTokenService = Objects.requireNonNull(concurrencyTokenService,
                "concurrencyTokenService must not be null");
        this.fieldEncryption =
                Objects.requireNonNull(fieldEncryption, "fieldEncryption must not be null");
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary,
                "transactionBoundary must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /* ------------------------------------------------------------------------------------------
     * IDENTIFICATION DIVISION paragraphs, lines 21 to 27. Non-procedural, so they map to named
     * accessors rather than to flow methods; they complete the 88-paragraph mapping and are read by
     * the diagnostic below.
     * ------------------------------------------------------------------------------------------ */

    /**
     * {@code PROGRAM-ID.} paragraph, {@code app/cbl/COACTUPC.cbl} lines 22 to 23.
     *
     * @return the legacy member name this class translates
     */
    public String programIdParagraph() {
        return LEGACY_PROGRAM_ID;
    }

    /**
     * {@code DATE-WRITTEN.} paragraph, {@code app/cbl/COACTUPC.cbl} lines 24 to 25.
     *
     * @return the authoring date the member records
     */
    public String dateWrittenParagraph() {
        return LEGACY_DATE_WRITTEN;
    }

    /**
     * {@code DATE-COMPILED.} paragraph, {@code app/cbl/COACTUPC.cbl} lines 26 to 27.
     *
     * @return the compilation-date placeholder the member records
     */
    public String dateCompiledParagraph() {
        return LEGACY_DATE_COMPILED;
    }

    /**
     * The map output area {@code CACTUPAO}, mutable for the duration of one turn.
     *
     * <p>Modelled as a mutable holder rather than an immutable value because that is what the legacy
     * builds: line 2669 clears the whole area with {@code MOVE LOW-VALUES}, and the header, value and
     * message paragraphs then move fields into it one at a time before the send at lines 3594 to 3601.
     * An absent component is {@code null}, which is this translation of the null-byte state the cleared
     * area holds.
     */
    private static final class MapOutput {

        private String transactionName;
        private String title01;
        private String currentDate;
        private String programName;
        private String title02;
        private String currentTime;
        private String accountId;
        private String accountStatus;
        private String openYear;
        private String openMonth;
        private String openDay;
        private BigDecimal creditLimit;
        private String expiryYear;
        private String expiryMonth;
        private String expiryDay;
        private BigDecimal cashCreditLimit;
        private String reissueYear;
        private String reissueMonth;
        private String reissueDay;
        private BigDecimal currentBalance;
        private BigDecimal currentCycleCredit;
        private String accountGroupId;
        private BigDecimal currentCycleDebit;
        private String customerId;
        private String ssnPart1;
        private String ssnPart2;
        private String ssnPart3;
        private String dateOfBirthYear;
        private String dateOfBirthMonth;
        private String dateOfBirthDay;
        private String ficoScore;
        private String firstName;
        private String middleName;
        private String lastName;
        private String addressLine1;
        private String stateCode;
        private String addressLine2;
        private String zipCode;
        private String city;
        private String countryCode;
        private String phone1AreaCode;
        private String phone1Prefix;
        private String phone1LineNumber;
        private String governmentIssuedId;
        private String phone2AreaCode;
        private String phone2Prefix;
        private String phone2LineNumber;
        private String eftAccountId;
        private String primaryCardHolderIndicator;

        /**
         * {@code MOVE LOW-VALUES TO CACTUPAO}, line 2669, and the same clearing the initial-values
         * paragraph performs across the detail fields at lines 2732 onward.
         */
        private void clearDetailFields() {
            this.accountStatus = null;
            this.openYear = null;
            this.openMonth = null;
            this.openDay = null;
            this.creditLimit = null;
            this.expiryYear = null;
            this.expiryMonth = null;
            this.expiryDay = null;
            this.cashCreditLimit = null;
            this.reissueYear = null;
            this.reissueMonth = null;
            this.reissueDay = null;
            this.currentBalance = null;
            this.currentCycleCredit = null;
            this.accountGroupId = null;
            this.currentCycleDebit = null;
            this.customerId = null;
            this.ssnPart1 = null;
            this.ssnPart2 = null;
            this.ssnPart3 = null;
            this.dateOfBirthYear = null;
            this.dateOfBirthMonth = null;
            this.dateOfBirthDay = null;
            this.ficoScore = null;
            this.firstName = null;
            this.middleName = null;
            this.lastName = null;
            this.addressLine1 = null;
            this.stateCode = null;
            this.addressLine2 = null;
            this.zipCode = null;
            this.city = null;
            this.countryCode = null;
            this.phone1AreaCode = null;
            this.phone1Prefix = null;
            this.phone1LineNumber = null;
            this.governmentIssuedId = null;
            this.phone2AreaCode = null;
            this.phone2Prefix = null;
            this.phone2LineNumber = null;
            this.eftAccountId = null;
            this.primaryCardHolderIndicator = null;
        }
    }

    /* ==========================================================================================
     * PROCEDURE DIVISION, line 858 onward. One named method per paragraph, in source order.
     * ========================================================================================== */

    /**
     * {@code 0000-MAIN}, lines 859 to 1005: the transaction entry point.
     *
     * <p>Convenience form for a caller that has already resolved the attention key to a typed action -
     * a controller mapping a REST verb, or a test. The overload that takes the raw attention
     * identifier is the one that reproduces the key mapping grafted in at line 4199.
     *
     * @param  request the screen input for this turn; must not be {@code null}
     * @return the screen this turn produces, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public AccountUpdateOutcome handle(final AccountUpdateCommand request) {
        return handle(request, null);
    }

    /**
     * {@code 0000-MAIN}, lines 859 to 1005: the transaction entry point, with the raw attention key.
     *
     * <p>The abend handler the legacy registers at lines 862 to 864 has no standing equivalent: this
     * member is one of the five-program family that registers one, and the handler's target routine is
     * reproduced by {@code abendRoutine}, which the dispatch selection reaches on its otherwise branch
     * exactly as the legacy does.
     *
     * <p>The turn is non-transactional until the write range. That range enters a separate proxied
     * transaction, so both rewrites commit together and any failure is translated only after rollback.
     *
     * @param  request                   the screen input for this turn; must not be {@code null}
     * @param  rawAttentionKeyIdentifier the raw attention identifier as transmitted, or {@code null} to
     *                                   use the typed action the request already carries
     * @return the screen this turn produces, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public AccountUpdateOutcome handle(final AccountUpdateCommand request,
            final String rawAttentionKeyIdentifier) {
        Objects.requireNonNull(request, "request must not be null");
        LOG.debug("transaction={} program={} written={} compiled={}: turn starting",
                LEGACY_TRANSACTION_ID, programIdParagraph(), dateWrittenParagraph(),
                dateCompiledParagraph());

        // INITIALIZE CC-WORK-AREA, WS-MISC-STORAGE, WS-COMMAREA at line 866, then
        // SET WS-RETURN-MSG-OFF TO TRUE at line 876.
        final EditState state = new EditState();
        state.clearMessage();
        final MapOutput output = new MapOutput();

        // Lines 880 to 893: a fresh entry re-initialises the carried areas, anything else adopts them.
        ScreenNavigationState context = incomingContext(request);
        if (freshEntry(context)) {
            context = ScreenNavigationState.empty().withFirstEntry();
            state.action = ChangeAction.DETAILS_NOT_FETCHED;
        } else {
            state.action = resolveIncomingChangeAction(request);
        }
        state.concurrencyToken = orEmpty(request.concurrencyToken());
        state.accountId = orEmpty(request.accountId());
        state.customerId = orEmpty(request.customerId());
        final ScreenInputState workArea = screenWorkArea(state, request);

        // PERFORM YYYY-STORE-PFKEY THRU ...-EXIT at lines 898 to 899, then the validity test at
        // lines 905 to 916 which forces ENTER whenever the pressed key is not usable here.
        final KeyAction pressedKey = storePfKey(state, request, rawAttentionKeyIdentifier, workArea);
        final KeyAction keyAction = screenKeyIsValid(state, pressedKey);

        // EVALUATE TRUE at line 921. Clause order is contractual and is preserved exactly.
        final AccountUpdateOutcome response;
        if (keyAction == KeyAction.PFK03) {
            response = exitToCaller(state, context, output);
        } else if ((state.action == ChangeAction.DETAILS_NOT_FETCHED && context.firstEntry())
                || (isMenuProgram(context.fromProgram()) && !context.reEntry())) {
            // Lines 964 to 973: fresh entry, so ask for the search key and re-arm.
            context = context.withReEntry();
            state.action = ChangeAction.DETAILS_NOT_FETCHED;
            sendMap(state, request, output, context);
            response = commonReturn(state, output, context, currentRoute());
        } else if (state.action == ChangeAction.CHANGES_OKAYED_AND_DONE
                || state.action.changesFailed()) {
            // Lines 979 to 989: the previous turn finished or failed, so reset and start again.
            context = context.withReEntry();
            state.clearFieldFlags();
            state.accountId = "";
            state.concurrencyToken = "";
            state.action = ChangeAction.DETAILS_NOT_FETCHED;
            sendMap(state, request, output, context);
            response = commonReturn(state, output, context, currentRoute());
        } else {
            // WHEN OTHER at lines 996 to 1003. Everything after the receive reads the received image, which
            // is what the source does too: 1100-RECEIVE-MAP fills ACUP-NEW-DETAILS and no later paragraph
            // looks at the map buffer again.
            processInputs(state, request, keyAction);
            final AccountUpdateCommand received = receivedDetails(state, request);
            context = decideAction(state, received, keyAction, context);
            sendMap(state, received, output, context);
            response = commonReturn(state, output, context, currentRoute());
        }
        mainExit();
        return response;
    }

    /**
     * {@code COMMON-RETURN}, lines 1007 to 1020.
     *
     * <p>Moves the accumulated return message into the screen's error slot and re-arms the transaction.
     * The re-arm is the client's next call in this translation, so what the legacy carried in the
     * commarea is carried by the response instead: the navigation context, the concurrency token that
     * replaces the old-image copy, and the resolved route.
     */
    private AccountUpdateOutcome commonReturn(final EditState state, final MapOutput output,
            final ScreenNavigationState context, final String route) {
        return sendScreen(state, output, context, route);
    }

    /**
     * {@code 0000-MAIN-EXIT}, lines 1021 to 1023: {@code EXIT} only.
     */
    private static void mainExit() {
        exitParagraph("0000-MAIN-EXIT");
    }

    /**
     * {@code 1000-PROCESS-INPUTS}, lines 1025 to 1034.
     *
     * <p>Receives the map, edits it, and then republishes the accumulated message and this program's own
     * name, mapset and map as the next screen - the moves at lines 1030 to 1033.
     */
    private void processInputs(final EditState state, final AccountUpdateCommand request,
            final KeyAction keyAction) {
        receiveMap(state, request);
        editMapInputs(state, request, keyAction);
        processInputsExit();
    }

    /**
     * {@code 1000-PROCESS-INPUTS-EXIT}, lines 1036 to 1038: {@code EXIT} only.
     */
    private static void processInputsExit() {
        exitParagraph("1000-PROCESS-INPUTS-EXIT");
    }

    /**
     * {@code 1100-RECEIVE-MAP}, lines 1039 to 1426.
     *
     * <p>The legacy receives the map and then, field by field, tests each transmitted value against
     * {@code '*'} and spaces and moves either the null byte or the value into the new-details area -
     * the shape at lines 1051 to 1058 and repeated for every field. In this translation the request
     * <em>is</em> the received map, so the paragraph's remaining work is the part that is not a plain
     * move: resolving the account key, and recording that the numeric amount fields arrive as lexemes
     * whose three-state edit happens in the signed-amount paragraph rather than here.
     *
     * <p>The early exit at lines 1060 to 1062 matters: when no details have been fetched yet, only the
     * account key is taken from the screen and every other field is left cleared.
     */
    private void receiveMap(final EditState state, final AccountUpdateCommand request) {
        state.accountId = screenValue(request.accountId());
        if (state.action == ChangeAction.DETAILS_NOT_FETCHED) {
            receiveMapExit();
            return;
        }
        state.customerId = screenValue(request.customerId());
        receiveMapExit();
    }

    /**
     * {@code 1100-RECEIVE-MAP-EXIT}, lines 1426 to 1428: {@code EXIT} only.
     */
    private static void receiveMapExit() {
        exitParagraph("1100-RECEIVE-MAP-EXIT");
    }

    /**
     * {@code 1200-EDIT-MAP-INPUTS}, lines 1429 to 1676: the edit driver.
     *
     * <p>Two early exits shape everything. Before any detail has been fetched, only the account key is
     * edited and the paragraph leaves at line 1446. Once details are on the screen, the comparison at
     * lines 1460 to 1468 leaves as well when nothing changed or when the previous turn completed - and it
     * clears every field flag on the way out, which is why an accepted turn shows no decoration.
     *
     * <p><strong>The awaiting-confirmation state is not a third reason to leave here, and that is a
     * deliberate divergence.</strong> Line 1464 lets the legacy skip all twenty-four edits on the
     * confirmation turn, and the legacy is safe doing so because that screen is protected: lines 2986 to
     * 3006 select the clause that leaves every field as lines 3441 to 3496 set it, and the attribute they
     * set is protected <em>with the modified-data tag on</em>. The terminal therefore re-transmits the
     * very image the edits had already passed, byte for byte, and the operator cannot alter a character
     * of it. This translation is stateless: the confirmation turn re-accepts the image from the caller,
     * so that guarantee does not exist and an unedited write would accept any value a caller chose to
     * substitute. The edits are consequently re-run on the confirmation turn as well. On a faithful echo
     * they pass, lines 1671 to 1675 restore the awaiting-confirmation state and the save proceeds exactly
     * as before; on an altered echo they reject, which is what the legacy would have done had the
     * operator been able to type the value on the detail screen. Recorded in
     * {@code docs/decision-log.md}.
     *
     * <p>The twenty-four edits that follow run in source order, and the order is contractual because the
     * first one to fail claims the single summary message slot while every one of them sets its own field
     * flag independently.
     *
     * <p>At each of those call sites the legacy moves a label into the edit-variable-name field, moves
     * the value into a generic work field with a reference-modification length, performs the edit, and
     * moves the generic flag into the field's own flag. Here the label travels with the field constant,
     * and the reference-modification length at every site equals the declared width of the component the
     * request contract already constrains, so the inspected prefix and the whole value coincide and no
     * slicing is needed.
     */
    private void editMapInputs(final EditState state, final AccountUpdateCommand request,
            final KeyAction keyAction) {
        state.inputError = false;

        if (state.action == ChangeAction.DETAILS_NOT_FETCHED) {
            editAccount(state, request);
            // Lines 1441 to 1443: a blank key is reported as no input at all.
            if (state.accountFilter.isBlank()) {
                state.claimMessage(MSG_NO_INPUT_RECEIVED);
            }
            editMapInputsExit();
            return;
        }

        // Lines 1452 to 1457: the details on the screen were fetched successfully, so the key and both
        // records are known good before the detail edits begin.
        state.infoMessage = INFO_FOUND_ACCOUNT_DATA;
        state.foundAccountInMaster = true;
        state.accountFilter = FieldFlag.ISVALID;
        state.foundCustomerInMaster = true;
        state.customerFilter = FieldFlag.ISVALID;

        // The legacy compares against the old image it carried in the commarea extension. This
        // translation is stateless, so the records themselves are the old image and are read here.
        if (!readAccount(state)) {
            editMapInputsExit();
            return;
        }

        // The old image is now available, which is the earliest point at which a withheld stand-in can be
        // told from a typed value. Everything downstream reads the restored submission, so no comparison,
        // no edit and no write ever sees a stand-in.
        final AccountUpdateCommand submitted = restoreWithheldValues(state, request);
        state.receivedDetails = submitted;

        compareOldNew(state, submitted);
        if (!state.changeHasOccurred || state.action == ChangeAction.CHANGES_OKAYED_AND_DONE) {
            if (state.action == ChangeAction.CHANGES_OK_NOT_CONFIRMED) {
                // NO-CHANGES-FOUND and the awaiting-confirmation state cannot coexist in the legacy:
                // that state is reached only from a turn on which a difference was found, at lines 2585
                // to 2591, and the save key is rejected in every other state by the validity test at
                // lines 905 to 916. A confirmation state that was derived from the arriving key rather
                // than carried in the commarea is therefore demoted here to the detail screen, which is
                // the state the legacy would have been in. The no-change arm at line 2585 then leaves it
                // there, so nothing is written and the information message is the prompt for changes -
                // both of which are what the legacy does with a submission that changed nothing.
                state.action = ChangeAction.SHOW_DETAILS;
            }
            state.clearFieldFlags();
            editMapInputsExit();
            return;
        }

        state.action = ChangeAction.CHANGES_NOT_OK;

        editYesNo(state, ScreenField.ACCT_STATUS, submitted.accountStatus());

        editDateGroup(state, ScreenField.OPEN_YEAR, ScreenField.OPEN_MONTH, ScreenField.OPEN_DAY,
                submitted.openYear(), submitted.openMonth(), submitted.openDay());

        editSigned9v2(state, ScreenField.CRED_LIMIT, submitted.creditLimit());

        editDateGroup(state, ScreenField.EXPIRY_YEAR, ScreenField.EXPIRY_MONTH,
                ScreenField.EXPIRY_DAY, submitted.expiryYear(), submitted.expiryMonth(),
                submitted.expiryDay());

        editSigned9v2(state, ScreenField.CASH_CREDIT_LIMIT, submitted.cashCreditLimit());

        editDateGroup(state, ScreenField.REISSUE_YEAR, ScreenField.REISSUE_MONTH,
                ScreenField.REISSUE_DAY, submitted.reissueYear(), submitted.reissueMonth(),
                submitted.reissueDay());

        editSigned9v2(state, ScreenField.CURR_BAL, submitted.currentBalance());
        editSigned9v2(state, ScreenField.CURR_CYC_CREDIT, submitted.currentCycleCredit());
        editSigned9v2(state, ScreenField.CURR_CYC_DEBIT, submitted.currentCycleDebit());

        editUsSsn(state, submitted);

        // Lines 1533 to 1543: the cascade first, then the date-of-birth check only when all three
        // group flags came back valid.
        editDateGroup(state, ScreenField.DT_OF_BIRTH_YEAR, ScreenField.DT_OF_BIRTH_MONTH,
                ScreenField.DT_OF_BIRTH_DAY, submitted.dateOfBirthYear(), submitted.dateOfBirthMonth(),
                submitted.dateOfBirthDay());
        if (dateGroupIsValid(state, ScreenField.DT_OF_BIRTH_YEAR, ScreenField.DT_OF_BIRTH_MONTH,
                ScreenField.DT_OF_BIRTH_DAY)) {
            editDateOfBirth(state, submitted);
        }

        // Lines 1545 to 1556: the numeric edit, then the range edit gated on the flag still being valid.
        editNumericRequired(state, ScreenField.FICO_SCORE, submitted.ficoScore());
        if (state.flag(ScreenField.FICO_SCORE).isValid()) {
            editFicoScore(state, submitted.ficoScore());
        }

        editAlphaRequired(state, ScreenField.FIRST_NAME, submitted.firstName());

        // Line 1571 performs the optional alphabetic edit on the middle name, yet the decoration
        // comment at line 3345 records "no edits coded". The action plan resolves the contradiction in
        // favour of the comment, so no edit runs here and the field accepts any input. The divergence
        // can only accept more than the legacy, never reject more, and is recorded in the decision log.
        noEditsCoded(state, ScreenField.MIDDLE_NAME);

        editAlphaRequired(state, ScreenField.LAST_NAME, submitted.lastName());
        editMandatory(state, ScreenField.ADDRESS_LINE_1, submitted.addressLine1());

        // Lines 1592 to 1602: the state code is edited as required alphabetic and only then, when that
        // generic flag is still valid, tested against the flat 56-code table.
        editAlphaRequired(state, ScreenField.STATE, submitted.stateCode());
        if (state.flag(ScreenField.STATE).isValid()) {
            editUsStateCode(state, submitted.stateCode());
        }

        editNumericRequired(state, ScreenField.ZIPCODE, submitted.zipCode());

        // Lines 1613 to 1614: the address line 2 edit is commented out in the source. Nothing runs.
        noEditsCoded(state, ScreenField.ADDRESS_LINE_2);

        // Line 1616: the screen's city field is backed by address line 3, not by a city column.
        editAlphaRequired(state, ScreenField.CITY, submitted.city());
        editAlphaRequired(state, ScreenField.COUNTRY, submitted.countryCode());

        editUsPhoneNumber(state, ScreenField.PHONE_NUM_1A, ScreenField.PHONE_NUM_1B,
                ScreenField.PHONE_NUM_1C, submitted.phone1AreaCode(), submitted.phone1Prefix(),
                submitted.phone1LineNumber());
        editUsPhoneNumber(state, ScreenField.PHONE_NUM_2A, ScreenField.PHONE_NUM_2B,
                ScreenField.PHONE_NUM_2C, submitted.phone2AreaCode(), submitted.phone2Prefix(),
                submitted.phone2LineNumber());

        editNumericRequired(state, ScreenField.EFT_ACCOUNT_ID, submitted.eftAccountId());
        editYesNo(state, ScreenField.PRI_CARDHOLDER, submitted.primaryCardHolderIndicator());

        // Lines 1664 to 1669: the one cross-field edit, gated on both of its inputs being valid.
        if (state.flag(ScreenField.STATE).isValid() && state.flag(ScreenField.ZIPCODE).isValid()) {
            editUsStateZipCode(state, submitted.stateCode(), submitted.zipCode());
        }

        // Lines 1671 to 1675.
        if (!state.inputError) {
            state.action = ChangeAction.CHANGES_OK_NOT_CONFIRMED;
        }
        LOG.debug("transaction={} key={} editOutcome={} fieldErrors={}", LEGACY_TRANSACTION_ID,
                keyAction, state.action, state.decoration.markedFields().size());
        editMapInputsExit();
    }

    /**
     * Restores the regulated values a caller was not permitted to see, so an unrelated change can be saved.
     *
     * <p><strong>The problem this exists to solve.</strong> The legacy screen withheld nothing: the map
     * carried the national identifier, the date of birth, the government-issued identifier and the
     * transfer-account identifier in clear, the terminal transmitted them back on every submission, and the
     * mandatory edits at lines 1520 to 1556 therefore always saw real values. This estate does withhold
     * them - a caller without the authority to see them receives a stand-in in their place - and a caller
     * echoes back what it received. Without this restoration those mandatory edits reject the stand-in and
     * an ordinary authorized operator can never save <em>any</em> change, not even to a field that has
     * nothing to do with the withheld ones. The government-issued identifier is worse than rejected: it
     * carries no edit at all, so a stand-in would compare as a difference and then be <em>written</em>,
     * replacing a stored identifier with a row of asterisks.
     *
     * <p><strong>Why this is safe, and where it is bounded.</strong> Three conditions must hold together
     * before any value is restored, and each closes a way the substitution could otherwise be abused.
     * First, the boundary must have recorded that the values were withheld from this caller; that fact
     * comes from the authenticated principal and never from the body, so a caller cannot ask for its own
     * typed values to be replaced. Second, the submitted value must consist solely of the stand-in
     * character, which is not legal input for any of these fields - the identifier parts and the transfer
     * account are digits and the date parts are digits - so a value an operator could legitimately have
     * typed is never reinterpreted. Third, its length must equal the stored value's, which is the width the
     * stand-in is composed at.
     *
     * <p><strong>What is deliberately <em>not</em> restored.</strong> The final part of the national
     * identifier, because the outbound gate does not withhold it - an operator is shown those digits so an
     * identity can be confirmed - so a stand-in in that component did not come from this server.
     *
     * <p><strong>The consequence, stated plainly.</strong> A caller without the authority to see these
     * values also cannot change them by echoing the screen: the stand-in restores to the stored value, so
     * the comparison reads unchanged and the record keeps what it held. Such a caller <em>can</em> still
     * change them by typing a real value over the stand-in, which is what the legacy permitted, so no
     * capability is removed. This is a documented divergence from a screen that withheld nothing, recorded
     * in {@code docs/decision-log.md}; the alternative - leaving the stand-in to be edited - is not a
     * stricter reading of the legacy but a total loss of the update transaction for every ordinary
     * operator.
     *
     * @param state the turn's working storage, holding the old image this restores from
     * @param request the submission as transmitted
     * @return the submission with every withheld stand-in replaced by the stored value, or the submission
     *         itself when nothing was withheld
     */
    private AccountUpdateCommand restoreWithheldValues(final EditState state,
            final AccountUpdateCommand request) {
        if (!request.protectedValuesWithheld()) {
            return request;
        }

        final Customer customer = state.customer;
        final String[] storedSsn = storedSsnParts(
                revealed(SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, customer.getCustSsn()));
        final String[] storedBirthDate = storedDateParts(customer.getCustDob());
        final String storedGovernmentId = revealed(
                SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                customer.getGovtIssuedId());

        return new AccountUpdateCommand(
                request.accountId(),
                request.accountStatus(),
                request.openYear(),
                request.openMonth(),
                request.openDay(),
                request.creditLimit(),
                request.expiryYear(),
                request.expiryMonth(),
                request.expiryDay(),
                request.cashCreditLimit(),
                request.reissueYear(),
                request.reissueMonth(),
                request.reissueDay(),
                request.currentBalance(),
                request.currentCycleCredit(),
                request.accountGroupId(),
                request.currentCycleDebit(),
                request.customerId(),
                restoredValue(request.ssnPart1(), storedSsn[0]),
                restoredValue(request.ssnPart2(), storedSsn[1]),
                request.ssnPart3(),
                restoredValue(request.dateOfBirthYear(), storedBirthDate[0]),
                restoredValue(request.dateOfBirthMonth(), storedBirthDate[1]),
                restoredValue(request.dateOfBirthDay(), storedBirthDate[2]),
                request.ficoScore(),
                request.firstName(),
                request.middleName(),
                request.lastName(),
                request.addressLine1(),
                request.stateCode(),
                request.addressLine2(),
                request.zipCode(),
                request.city(),
                request.countryCode(),
                request.phone1AreaCode(),
                request.phone1Prefix(),
                request.phone1LineNumber(),
                restoredValue(request.governmentIssuedId(), storedGovernmentId),
                request.phone2AreaCode(),
                request.phone2Prefix(),
                request.phone2LineNumber(),
                restoredValue(request.eftAccountId(), customer.getEftAccountId()),
                request.primaryCardHolderIndicator(),
                request.keyAction(),
                request.navigationContext(),
                request.concurrencyToken(),
                request.protectedValuesWithheld());
    }

    /**
     * Answers the stored value when the submitted one is the stand-in composed from it, and the submitted
     * one otherwise.
     *
     * <p>The stand-in is the withheld character repeated to the stored value's own width, so both the
     * character test and the width test have to pass. A stored value that is absent leaves the submitted
     * value untouched: there is nothing a stand-in could have been composed from, so whatever arrived was
     * typed.
     *
     * @param submitted the value as transmitted, which may be {@code null}
     * @param stored the value the record holds, which may be {@code null}
     * @return the stored value when the submitted one is its stand-in, otherwise the submitted one
     */
    private static String restoredValue(final String submitted, final String stored) {
        if (submitted == null || stored == null || submitted.length() != stored.length()) {
            return submitted;
        }
        for (int index = 0; index < submitted.length(); index++) {
            if (submitted.charAt(index) != WITHHELD_VALUE_CHARACTER) {
                return submitted;
            }
        }
        return stored;
    }

    /**
     * The submission every paragraph after the receive reads.
     *
     * <p>The restored image when the edit driver established one, and the submission itself otherwise -
     * which is every path that never reaches the driver, where nothing has been read and so nothing could
     * have been restored.
     *
     * @param state the turn's working storage
     * @param request the submission as transmitted
     * @return the received image, never {@code null}
     */
    private static AccountUpdateCommand receivedDetails(final EditState state,
            final AccountUpdateCommand request) {
        return state.receivedDetails == null ? request : state.receivedDetails;
    }

    /**
     * {@code 1200-EDIT-MAP-INPUTS-EXIT}, lines 1678 to 1680: {@code EXIT} only.
     */
    private static void editMapInputsExit() {
        exitParagraph("1200-EDIT-MAP-INPUTS-EXIT");
    }

    /**
     * {@code 1205-COMPARE-OLD-NEW}, lines 1681 to 1775.
     *
     * <p>Two ordered comparisons, not one. The account block runs first, at lines 1684 to 1705, and a
     * difference there leaves immediately so the customer block never runs and the no-change message is
     * never claimed. The customer block, at lines 1708 to 1773, is the only place in this program that
     * assigns a message <em>without</em> the first-error-wins gate: line 1769 sets it unconditionally.
     * That is reproduced as a direct assignment.
     *
     * <p>Case folding follows the source exactly. The account block folds only the active status and the
     * group identifier, and the group identifier is trimmed as well; the customer block folds most text
     * fields and trims those it folds; the identifiers, amounts, telephone parts, dates and the credit
     * score are compared as they stand. Folding uses the ASCII table the estate's own inspect literals
     * declare, never a locale-sensitive conversion.
     */
    private void compareOldNew(final EditState state, final AccountUpdateCommand request) {
        state.changeHasOccurred = false;

        final Account account = state.account;
        final Customer customer = state.customer;
        final boolean accountUnchanged =
                sameKey(request.accountId(), account.getAcctId(), ACCOUNT_KEY_WIDTH)
                && sameFolded(request.accountStatus(), account.getAcctActiveStatus())
                && sameAmount(request.currentBalance(), account.getAcctCurrBal())
                && sameAmount(request.creditLimit(), account.getAcctCreditLimit())
                && sameAmount(request.cashCreditLimit(), account.getAcctCashCreditLimit())
                && sameDate(request.openYear(), request.openMonth(), request.openDay(),
                        account.getAcctOpenDate())
                && sameDate(request.expiryYear(), request.expiryMonth(), request.expiryDay(),
                        account.getAcctExpirationDate())
                && sameDate(request.reissueYear(), request.reissueMonth(), request.reissueDay(),
                        account.getAcctReissueDate())
                && sameAmount(request.currentCycleCredit(), account.getAcctCurrCycCredit())
                && sameAmount(request.currentCycleDebit(), account.getAcctCurrCycDebit())
                && sameFoldedTrimmed(request.accountGroupId(), account.getAcctGroupId());
        if (!accountUnchanged) {
            state.changeHasOccurred = true;
            compareOldNewExit();
            return;
        }

        final boolean customerUnchanged =
                sameFoldedTrimmed(request.customerId(), customer.getCustId())
                && sameFoldedTrimmed(request.firstName(), customer.getFirstName())
                && sameFoldedTrimmed(request.middleName(), customer.getMiddleName())
                && sameFoldedTrimmed(request.lastName(), customer.getLastName())
                && sameFoldedTrimmed(request.addressLine1(), customer.getAddrLine1())
                && sameFoldedTrimmed(request.addressLine2(), customer.getAddrLine2())
                && sameFoldedTrimmed(request.city(), customer.getAddrLine3())
                && sameFoldedTrimmed(request.stateCode(), customer.getAddrStateCd())
                && sameFoldedTrimmed(request.countryCode(), customer.getAddrCountryCd())
                && sameFoldedTrimmed(request.zipCode(), customer.getAddrZip())
                && samePhone(request.phone1AreaCode(), request.phone1Prefix(),
                        request.phone1LineNumber(), customer.getPhoneNum1())
                && samePhone(request.phone2AreaCode(), request.phone2Prefix(),
                        request.phone2LineNumber(), customer.getPhoneNum2())
                && sameSsn(request.ssnPart1(), request.ssnPart2(), request.ssnPart3(),
                        revealed(SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD,
                                customer.getCustSsn()))
                && sameFoldedTrimmed(request.governmentIssuedId(),
                        revealed(SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                                customer.getGovtIssuedId()))
                && sameDate(request.dateOfBirthYear(), request.dateOfBirthMonth(),
                        request.dateOfBirthDay(), customer.getCustDob())
                && sameFoldedTrimmed(request.eftAccountId(), customer.getEftAccountId())
                && sameFoldedTrimmed(request.primaryCardHolderIndicator(),
                        customer.getPriCardHolderInd())
                && sameFoldedTrimmed(request.ficoScore(), customer.getFicoCreditScore());
        if (customerUnchanged) {
            // Line 1769, deliberately ungated: this assignment bypasses the first-error-wins gate.
            state.returnMessage = MSG_NO_CHANGES_DETECTED;
        } else {
            state.changeHasOccurred = true;
        }
        compareOldNewExit();
    }

    /**
     * {@code 1205-COMPARE-OLD-NEW-EXIT}, lines 1777 to 1779: {@code EXIT} only.
     */
    private static void compareOldNewExit() {
        exitParagraph("1205-COMPARE-OLD-NEW-EXIT");
    }

    /**
     * {@code 1210-EDIT-ACCOUNT}, lines 1783 to 1818: the search-key edit.
     *
     * <p>Three outcomes in order: blank, which claims the not-provided message and zeroes the key;
     * non-numeric or zero, which claims the composed malformed-key message; and valid. The composed
     * message at lines 1806 to 1810 is not the condition-name text the block at lines 493 to 496
     * declares for this outcome - a source discrepancy carried as a finding, with the live path
     * reproducing what the code actually strings together.
     */
    private void editAccount(final EditState state, final AccountUpdateCommand request) {
        state.accountFilter = FieldFlag.NOT_OK;
        final String keyed = screenValue(request.accountId());

        if (isUnsuppliedScreenValue(keyed)) {
            state.markInputError();
            state.accountFilter = FieldFlag.BLANK;
            state.claimMessage(MSG_ACCOUNT_NUMBER_NOT_PROVIDED);
            state.accountId = "";
            editAccountExit();
            return;
        }

        state.accountId = keyed;
        if (!isAllDigits(keyed) || keyed.length() != ACCOUNT_KEY_WIDTH || isZeroDigits(keyed)) {
            state.markInputError();
            state.claimMessage(MSG_ACCOUNT_NUMBER_MALFORMED);
            state.accountId = "";
            editAccountExit();
            return;
        }
        state.accountFilter = FieldFlag.ISVALID;
        editAccountExit();
    }

    /**
     * {@code 1210-EDIT-ACCOUNT-EXIT}, lines 1820 to 1822: {@code EXIT} only.
     */
    private static void editAccountExit() {
        exitParagraph("1210-EDIT-ACCOUNT-EXIT");
    }

    /**
     * {@code 1215-EDIT-MANDATORY}, lines 1824 to 1850.
     *
     * <p>Presence only: blank fails with the supplied suffix and anything else passes, with no
     * character-class test at all. Address line 1 is the only field routed here.
     */
    private void editMandatory(final EditState state, final ScreenField field, final String value) {
        state.label = field.getLegacyLabel();
        if (isUnsuppliedScreenValue(value)) {
            state.fail(field, FieldFlag.BLANK, composeMessage(state, SUFFIX_MUST_BE_SUPPLIED));
            editMandatoryExit();
            return;
        }
        state.setFlag(field, FieldFlag.ISVALID);
        editMandatoryExit();
    }

    /**
     * {@code 1215-EDIT-MANDATORY-EXIT}, lines 1852 to 1854: {@code EXIT} only.
     */
    private static void editMandatoryExit() {
        exitParagraph("1215-EDIT-MANDATORY-EXIT");
    }

    /**
     * {@code 1220-EDIT-YESNO}, lines 1856 to 1893.
     *
     * <p>Blank - and, uniquely among the edits, a zero as well, per line 1863 - fails with the supplied
     * suffix. Anything that is not literally {@code Y} or {@code N} then fails with its own suffix. The
     * initialising {@code SET} at line 1858 is commented out in the source, so an unmatched value keeps
     * whatever the field held; here the state is written explicitly on every arm, which is the same
     * observable outcome because both arms assign before leaving.
     */
    private void editYesNo(final EditState state, final ScreenField field, final String value) {
        state.label = field.getLegacyLabel();
        final String keyed = screenValue(value);
        if (isUnsuppliedScreenValue(keyed) || isZeroDigits(keyed)) {
            state.fail(field, FieldFlag.BLANK, composeMessage(state, SUFFIX_MUST_BE_SUPPLIED));
            editYesNoExit();
            return;
        }
        if ("Y".equals(keyed) || "N".equals(keyed)) {
            state.setFlag(field, FieldFlag.ISVALID);
            editYesNoExit();
            return;
        }
        state.fail(field, FieldFlag.NOT_OK, composeMessage(state, SUFFIX_MUST_BE_Y_OR_N));
        editYesNoExit();
    }

    /**
     * {@code 1220-EDIT-YESNO-EXIT}, lines 1894 to 1896: {@code EXIT} only.
     */
    private static void editYesNoExit() {
        exitParagraph("1220-EDIT-YESNO-EXIT");
    }

    /**
     * {@code 1225-EDIT-ALPHA-REQD}, lines 1898 to 1949.
     *
     * <p>Blank fails, then the character class is tested by the estate's own idiom: convert every letter
     * of the 52-character table to a space and assert the trimmed remainder is empty. A value containing
     * a space therefore <strong>passes</strong>, because a space was already a space and is trimmed away
     * exactly as a blanked letter is. That is why the predicate is
     * {@code CobolStringUtils.isAlphaOrSpace} and never a per-character letter test: the fixture value
     * {@code Aniya Von} and the ordinary given name {@code MARY ANN} both have to be accepted.
     */
    private void editAlphaRequired(final EditState state, final ScreenField field,
            final String value) {
        state.label = field.getLegacyLabel();
        final String keyed = screenValue(value);
        if (isUnsuppliedScreenValue(keyed)) {
            state.fail(field, FieldFlag.BLANK, composeMessage(state, SUFFIX_MUST_BE_SUPPLIED));
            editAlphaRequiredExit();
            return;
        }
        if (!CobolStringUtils.isAlphaOrSpace(keyed)) {
            state.fail(field, FieldFlag.NOT_OK, composeMessage(state, SUFFIX_ALPHABETS_ONLY));
            editAlphaRequiredExit();
            return;
        }
        state.setFlag(field, FieldFlag.ISVALID);
        editAlphaRequiredExit();
    }

    /**
     * {@code 1225-EDIT-ALPHA-REQD-EXIT}, lines 1951 to 1953: {@code EXIT} only.
     */
    private static void editAlphaRequiredExit() {
        exitParagraph("1225-EDIT-ALPHA-REQD-EXIT");
    }

    /**
     * {@code 1230-EDIT-ALPHANUM-REQD}, lines 1955 to 2007.
     *
     * <p>The same shape as the required alphabetic edit but converting the 62-character table, so digits
     * pass as well. No call site in this member routes to it - the driver reaches the required
     * alphabetic, the optional alphabetic, the required numeric, the mandatory and the signed edits, but
     * never this one - so it is translated and exercised without being wired, exactly as the source
     * leaves it. The misspelled flag name {@code FLG-ALPHNANUM-*} at line 1995 is preserved as the
     * field-flag identity it names, and the misspelling itself is surfaced elsewhere in the module.
     *
     * @param  state the per-turn state, whose label slot this edit composes against
     * @param  field the field being edited
     * @param  value the keyed value
     */
    private void editAlphanumericRequired(final EditState state, final ScreenField field,
            final String value) {
        state.label = field.getLegacyLabel();
        final String keyed = screenValue(value);
        if (isUnsuppliedScreenValue(keyed)) {
            state.fail(field, FieldFlag.BLANK, composeMessage(state, SUFFIX_MUST_BE_SUPPLIED));
            editAlphanumericRequiredExit();
            return;
        }
        if (!CobolStringUtils.isAlphaNumericOrSpace(keyed)) {
            state.fail(field, FieldFlag.NOT_OK,
                    composeMessage(state, SUFFIX_NUMBERS_OR_ALPHABETS_ONLY));
            editAlphanumericRequiredExit();
            return;
        }
        state.setFlag(field, FieldFlag.ISVALID);
        editAlphanumericRequiredExit();
    }

    /**
     * {@code 1230-EDIT-ALPHANUM-REQD-EXIT}, lines 2009 to 2011: {@code EXIT} only.
     */
    private static void editAlphanumericRequiredExit() {
        exitParagraph("1230-EDIT-ALPHANUM-REQD-EXIT");
    }

    /**
     * {@code 1235-EDIT-ALPHA-OPT}, lines 2012 to 2055.
     *
     * <p>Identical to the required alphabetic edit except that a blank value is declared valid and the
     * paragraph leaves early, at lines 2024 to 2025.
     *
     * <p><strong>Translated but deliberately not wired, and the evidence cuts both ways.</strong> The
     * middle name is the source's only call site, at line 1571, and that call is <em>live</em> - not
     * commented out - so the legacy really does apply this edit and really would reject a middle name
     * carrying a digit. The screen-attribute block nevertheless states the opposite at line 3345, "Middle
     * Name (no edits coded)", and the migration directive is explicit and repeated that no constraint of
     * any kind may be attached to this field, because attaching one would reject input the legacy is
     * documented to accept. The directive governs, so the driver does not reach this paragraph.
     *
     * <p>The comment at line 3345 is therefore itself a source defect, and a distinct one from the three
     * already catalogued: it describes a field that <em>is</em> edited. Address line 2, by contrast, is
     * described correctly - only its label assignment is commented out at line 1614 and no edit is
     * performed for it anywhere - so the two fields the directive pairs together are not actually alike.
     * Both facts belong in the decision log beside this deviation.
     *
     * <p>The paragraph is kept rather than dropped because every one of the member's paragraphs maps to a
     * named method, and it is left unreached rather than wired because the directive forbids the wiring.
     *
     * @param  state the per-turn state, whose label slot this edit composes against
     * @param  field the field being edited
     * @param  value the keyed value
     */
    private void editAlphaOptional(final EditState state, final ScreenField field,
            final String value) {
        state.label = field.getLegacyLabel();
        final String keyed = screenValue(value);
        if (isUnsuppliedScreenValue(keyed)) {
            state.setFlag(field, FieldFlag.ISVALID);
            editAlphaOptionalExit();
            return;
        }
        if (!CobolStringUtils.isAlphaOrSpace(keyed)) {
            state.fail(field, FieldFlag.NOT_OK, composeMessage(state, SUFFIX_ALPHABETS_ONLY));
            editAlphaOptionalExit();
            return;
        }
        state.setFlag(field, FieldFlag.ISVALID);
        editAlphaOptionalExit();
    }

    /**
     * {@code 1235-EDIT-ALPHA-OPT-EXIT}, lines 2057 to 2059: {@code EXIT} only.
     */
    private static void editAlphaOptionalExit() {
        exitParagraph("1235-EDIT-ALPHA-OPT-EXIT");
    }

    /**
     * {@code 1240-EDIT-ALPHANUM-OPT}, lines 2061 to 2103.
     *
     * <p>Blank is valid and leaves early at lines 2072 to 2073. The comment at line 2078 claims only
     * letters and spaces are allowed, but the statement at lines 2079 to 2082 converts the 62-character
     * alphanumeric table, so digits pass. <strong>The code governs and the comment is a recorded
     * finding.</strong> No call site in this member reaches this paragraph.
     *
     * @param  state the per-turn state, whose label slot this edit composes against
     * @param  field the field being edited
     * @param  value the keyed value
     */
    private void editAlphanumericOptional(final EditState state, final ScreenField field,
            final String value) {
        state.label = field.getLegacyLabel();
        final String keyed = screenValue(value);
        if (isUnsuppliedScreenValue(keyed)) {
            state.setFlag(field, FieldFlag.ISVALID);
            editAlphanumericOptionalExit();
            return;
        }
        if (!CobolStringUtils.isAlphaNumericOrSpace(keyed)) {
            state.fail(field, FieldFlag.NOT_OK,
                    composeMessage(state, SUFFIX_NUMBERS_OR_ALPHABETS_ONLY));
            editAlphanumericOptionalExit();
            return;
        }
        state.setFlag(field, FieldFlag.ISVALID);
        editAlphanumericOptionalExit();
    }

    /**
     * {@code 1240-EDIT-ALPHANUM-OPT-EXIT}, lines 2105 to 2107: {@code EXIT} only.
     */
    private static void editAlphanumericOptionalExit() {
        exitParagraph("1240-EDIT-ALPHANUM-OPT-EXIT");
    }

    /**
     * {@code 1245-EDIT-NUM-REQD}, lines 2109 to 2174: three ordered arms.
     *
     * <p>Blank fails with the supplied suffix; a value that is not entirely digits fails with the
     * all-numeric suffix, because {@code IS NUMERIC} on a display field is true only when every position
     * holds a digit; and a value that is numerically zero fails with the not-zero suffix. Five fields
     * route here - the credit score, the postcode, the electronic-funds account and the three parts of
     * the national identifier - so the arm order is what decides which message a bad value earns.
     */
    private void editNumericRequired(final EditState state, final ScreenField field,
            final String value) {
        state.label = field.getLegacyLabel();
        final String keyed = screenValue(value);
        if (isUnsuppliedScreenValue(keyed)) {
            state.fail(field, FieldFlag.BLANK, composeMessage(state, SUFFIX_MUST_BE_SUPPLIED));
            editNumericRequiredExit();
            return;
        }
        if (!isAllDigits(keyed)) {
            state.fail(field, FieldFlag.NOT_OK, composeMessage(state, SUFFIX_MUST_BE_ALL_NUMERIC));
            editNumericRequiredExit();
            return;
        }
        if (isZeroDigits(keyed)) {
            state.fail(field, FieldFlag.NOT_OK, composeMessage(state, SUFFIX_MUST_NOT_BE_ZERO));
            editNumericRequiredExit();
            return;
        }
        state.setFlag(field, FieldFlag.ISVALID);
        editNumericRequiredExit();
    }

    /**
     * {@code 1245-EDIT-NUM-REQD-EXIT}, lines 2176 to 2178: {@code EXIT} only.
     */
    private static void editNumericRequiredExit() {
        exitParagraph("1245-EDIT-NUM-REQD-EXIT");
    }

    /**
     * {@code 1250-EDIT-SIGNED-9V2}, lines 2180 to 2218: the five monetary fields.
     *
     * <p>Blank fails with the supplied suffix, then a lexeme that the currency-aware numeric test at
     * line 2201 rejects fails with the not-valid suffix, which carries no trailing full stop. Both tests
     * are owned by {@code CobolStringUtils}: the blank test composes the map-move test against
     * {@code '*'} and spaces with the paragraph's own test against the null byte and spaces, and the
     * format test is the currency-aware grammar itself.
     *
     * <p>The value is never scaled here. Conversion is delegated to {@code ZonedDecimalCodec}, which is
     * the module's only holder of a rounding policy, and that policy truncates toward zero because no
     * arithmetic statement in the estate specifies rounding.
     */
    private void editSigned9v2(final EditState state, final ScreenField field, final String value) {
        state.label = field.getLegacyLabel();
        if (CobolStringUtils.isUnsuppliedNumericLexeme(value)) {
            state.fail(field, FieldFlag.BLANK, composeMessage(state, SUFFIX_MUST_BE_SUPPLIED));
            editSigned9v2Exit();
            return;
        }
        if (!CobolStringUtils.isNumericLexeme(value)) {
            state.fail(field, FieldFlag.NOT_OK, composeMessage(state, SUFFIX_IS_NOT_VALID));
            editSigned9v2Exit();
            return;
        }
        // Documented divergence. The screen field is wider than the ten integer digits the record field
        // holds, so a well-formed lexeme can still be too large to store. The legacy move would truncate
        // the high-order digits and store a silently wrong amount; this rejects the value with the same
        // not-valid message instead, which is the fail-safe direction and cannot corrupt a balance.
        if (!fitsRecordAmount(ZonedDecimalCodec.fromNumericLexeme(value))) {
            state.fail(field, FieldFlag.NOT_OK, composeMessage(state, SUFFIX_IS_NOT_VALID));
            editSigned9v2Exit();
            return;
        }
        state.setFlag(field, FieldFlag.ISVALID);
        editSigned9v2Exit();
    }

    /**
     * {@code 1250-EDIT-SIGNED-9V2-EXIT}, lines 2221 to 2223: {@code EXIT} only.
     */
    private static void editSigned9v2Exit() {
        exitParagraph("1250-EDIT-SIGNED-9V2-EXIT");
    }

    /**
     * {@code 1260-EDIT-US-PHONE-NUM}, lines 2225 to 2245, and the range it heads.
     *
     * <p>One of only three genuine multi-paragraph {@code PERFORM THRU} ranges in the estate. The range
     * spans five intermediate paragraphs - the area code at 2246, the prefix at 2316, the line number at
     * 2370, the inner exit at 2424 and the range exit at 2427 - and the driver invokes it twice, at lines
     * 1632 to 1638 and 1640 to 1646.
     *
     * <p><strong>A failing stage forwards, it does not abort.</strong> Every failure jumps to the
     * <em>next</em> stage, never to the exit: the four area-code failures at lines 2259, 2277, 2291 and
     * 2311 all target the prefix stage, and the three prefix failures at 2330, 2348 and 2362 all target
     * the line-number stage. Only the line-number stage's three failures, at 2383, 2401 and 2415, reach
     * the inner exit, and by then every stage has already run. So all three stages always execute and
     * all three flags are set independently: a telephone number with three bad parts yields three field
     * errors and, because of the first-error-wins gate, exactly one summary message - the first
     * failure's.
     *
     * <p><strong>Source anomaly, reproduced verbatim.</strong> The all-blank shortcut at lines 2234 to
     * 2239 has three condition groups, and the third tests the <em>area code</em> where it plainly meant
     * the line number. The defect is reproduced rather than corrected: with a blank area code and a blank
     * prefix the shortcut fires whatever the line number holds, so such a number is accepted as "no
     * telephone supplied". Distinguishing an untransmitted component from an all-space one is what makes
     * the defect observable at all, and the two are represented here as an absent value and a blank
     * value respectively, exactly as the rest of the module represents them.
     *
     * <p>The stored form the comment at lines 2227 to 2228 documents is {@code (999)999-9999} inside a
     * fifteen-character field. Here the three components arrive keyed separately, so no positional
     * decomposition is needed on the way in.
     */
    private void editUsPhoneNumber(final EditState state, final ScreenField areaField,
            final ScreenField prefixField, final ScreenField lineField, final String areaCode,
            final String prefix, final String lineNumber) {
        state.label = areaField.getLegacyLabel();

        // Line 2232: the group starts out wholly invalid; each stage then writes its own flag.
        state.setFlag(areaField, FieldFlag.NOT_OK);
        state.setFlag(prefixField, FieldFlag.NOT_OK);
        state.setFlag(lineField, FieldFlag.NOT_OK);

        final boolean allBlankShortcut = (isSpaces(areaCode) || isLowValues(areaCode))
                && (isSpaces(prefix) || isLowValues(prefix))
                // Reproduced defect: the first operand is the area code, where the line number belongs.
                && (isSpaces(areaCode) || isLowValues(lineNumber));
        if (allBlankShortcut) {
            LOG.debug("transaction={} field={}: all-blank telephone shortcut fired; the third "
                    + "condition group at source lines 2238 to 2239 tests the area code where the "
                    + "line number belongs, and the defect is reproduced deliberately",
                    LEGACY_TRANSACTION_ID, areaField.getFieldName());
            state.setFlag(areaField, FieldFlag.ISVALID);
            state.setFlag(prefixField, FieldFlag.ISVALID);
            state.setFlag(lineField, FieldFlag.ISVALID);
            editUsPhoneExit();
            editUsPhoneNumberExit();
            return;
        }

        // Fall-through into the ordered cascade. Each stage runs unconditionally.
        editAreaCode(state, areaField, areaCode);
        editUsPhonePrefix(state, prefixField, prefix);
        editUsPhoneLineNumber(state, lineField, lineNumber);
        editUsPhoneExit();
        editUsPhoneNumberExit();
    }

    /**
     * {@code EDIT-AREA-CODE}, lines 2246 to 2314: four ordered arms, each forwarding to the prefix stage.
     *
     * <p>The fourth arm is the only one that consults a table, and it consults the
     * <strong>410-entry general-purpose set</strong> after trimming the candidate, per lines 2296 to
     * 2298. It is never the 490-entry union and never the 80-entry easily-recognisable set: the 490
     * figure is the derived union of the two and is not stored anywhere. So a trimmed but otherwise valid
     * general-purpose code is accepted, while an easily-recognisable code such as the toll-free one is
     * rejected here.
     */
    private void editAreaCode(final EditState state, final ScreenField field, final String areaCode) {
        if (isSpaces(areaCode) || isLowValues(areaCode)) {
            state.fail(field, FieldFlag.BLANK,
                    composeMessage(state, AccountUpdateOutcome.SUFFIX_AREA_CODE_REQUIRED));
            return;
        }
        if (!isAllDigits(areaCode) || areaCode.length() != PHONE_AREA_AND_PREFIX_WIDTH) {
            state.fail(field, FieldFlag.NOT_OK,
                    composeMessage(state, AccountUpdateOutcome.SUFFIX_AREA_CODE_NOT_3_DIGITS));
            return;
        }
        if (isZeroDigits(areaCode)) {
            state.fail(field, FieldFlag.NOT_OK,
                    composeMessage(state, AccountUpdateOutcome.SUFFIX_AREA_CODE_ZERO));
            return;
        }
        if (!this.validationLookupService.isValidGeneralPurposeAreaCode(areaCode.trim())) {
            state.fail(field, FieldFlag.NOT_OK, composeMessage(state,
                    AccountUpdateOutcome.SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE));
            return;
        }
        state.setFlag(field, FieldFlag.ISVALID);
    }

    /**
     * {@code EDIT-US-PHONE-PREFIX}, lines 2316 to 2367: three ordered arms, each forwarding to the
     * line-number stage. No table is consulted for the prefix.
     */
    private void editUsPhonePrefix(final EditState state, final ScreenField field,
            final String prefix) {
        if (isSpaces(prefix) || isLowValues(prefix)) {
            state.fail(field, FieldFlag.BLANK,
                    composeMessage(state, AccountUpdateOutcome.SUFFIX_PREFIX_REQUIRED));
            return;
        }
        if (!isAllDigits(prefix) || prefix.length() != PHONE_AREA_AND_PREFIX_WIDTH) {
            state.fail(field, FieldFlag.NOT_OK,
                    composeMessage(state, AccountUpdateOutcome.SUFFIX_PREFIX_NOT_3_DIGITS));
            return;
        }
        if (isZeroDigits(prefix)) {
            state.fail(field, FieldFlag.NOT_OK,
                    composeMessage(state, AccountUpdateOutcome.SUFFIX_PREFIX_ZERO));
            return;
        }
        state.setFlag(field, FieldFlag.ISVALID);
    }

    /**
     * {@code EDIT-US-PHONE-LINENUM}, lines 2370 to 2421: three ordered arms, each reaching the inner
     * exit. Four digits rather than three, and the width is what its second message names.
     */
    private void editUsPhoneLineNumber(final EditState state, final ScreenField field,
            final String lineNumber) {
        if (isSpaces(lineNumber) || isLowValues(lineNumber)) {
            state.fail(field, FieldFlag.BLANK,
                    composeMessage(state, AccountUpdateOutcome.SUFFIX_LINE_NUMBER_REQUIRED));
            return;
        }
        if (!isAllDigits(lineNumber) || lineNumber.length() != PHONE_LINE_NUMBER_WIDTH) {
            state.fail(field, FieldFlag.NOT_OK,
                    composeMessage(state, AccountUpdateOutcome.SUFFIX_LINE_NUMBER_NOT_4_DIGITS));
            return;
        }
        if (isZeroDigits(lineNumber)) {
            state.fail(field, FieldFlag.NOT_OK,
                    composeMessage(state, AccountUpdateOutcome.SUFFIX_LINE_NUMBER_ZERO));
            return;
        }
        state.setFlag(field, FieldFlag.ISVALID);
    }

    /**
     * {@code EDIT-US-PHONE-EXIT}, lines 2424 to 2426: the inner exit of the range, {@code EXIT} only.
     * It is the target of the all-blank shortcut and of the three line-number failures.
     */
    private static void editUsPhoneExit() {
        exitParagraph("EDIT-US-PHONE-EXIT");
    }

    /**
     * {@code 1260-EDIT-US-PHONE-NUM-EXIT}, lines 2427 to 2429: the range exit, {@code EXIT} only.
     */
    private static void editUsPhoneNumberExit() {
        exitParagraph("1260-EDIT-US-PHONE-NUM-EXIT");
    }

    /**
     * {@code 1265-EDIT-US-SSN}, lines 2431 to 2488: three parts, each with its own label.
     *
     * <p>Every part goes through the required-numeric edit - three digits, then two, then four - and the
     * first part is additionally range-checked against the excluded values the condition name at lines
     * 121 to 123 declares: zero, 666, and 900 through 999. That extra check runs only when the first
     * part's own flag is still valid, per line 2448.
     *
     * <p>Source anomaly. The range arm at lines 2450 to 2464 nests its message assignment differently
     * from every sibling edit: the gate's {@code ELSE} is a bare continue and the inner condition is not
     * closed the way the others are. The observable behaviour is the same first-error-wins assignment, so
     * that is what is reproduced, and the structural oddity is recorded.
     *
     * <p>The parts are never logged and never concatenated into a diagnostic: this is a regulated
     * identifier, and the response record redacts it from its own rendering for the same reason.
     */
    private void editUsSsn(final EditState state, final AccountUpdateCommand request) {
        editNumericRequired(state, ScreenField.EDIT_US_SSN_PART1, request.ssnPart1());
        if (state.flag(ScreenField.EDIT_US_SSN_PART1).isValid()
                && isExcludedSsnFirstPart(request.ssnPart1())) {
            state.fail(ScreenField.EDIT_US_SSN_PART1, FieldFlag.NOT_OK,
                    composeMessage(state, SUFFIX_SSN_PART1_OUT_OF_RANGE));
        }
        editNumericRequired(state, ScreenField.EDIT_US_SSN_PART2, request.ssnPart2());
        editNumericRequired(state, ScreenField.EDIT_US_SSN_PART3, request.ssnPart3());
        editUsSsnExit();
    }

    /**
     * {@code 1265-EDIT-US-SSN-EXIT}, lines 2489 to 2491: {@code EXIT} only.
     */
    private static void editUsSsnExit() {
        exitParagraph("1265-EDIT-US-SSN-EXIT");
    }

    /**
     * {@code 1270-EDIT-US-STATE-CD}, lines 2493 to 2510: the flat 56-code membership test.
     *
     * <p>Deliberately the plainest edit in the program. The candidate is moved into the lookup field
     * <strong>without a trim, without a numeric check and without a blank pre-check</strong> - line 2494
     * is a bare move - and tested against the 56-entry table. On failure it sets the state flag alone and
     * its message carries <strong>no trailing full stop</strong>.
     *
     * <p>This list is never intersected with the 240-entry state-and-postcode list. Six of the 62 prefixes
     * the composite list uses - the two overseas military prefixes, the diplomatic one, and the three
     * Pacific ones - do not appear among these 56 codes at all, so intersecting the two would reject
     * addresses the legacy accepts.
     */
    private void editUsStateCode(final EditState state, final String stateCode) {
        if (this.validationLookupService.isValidUsStateCode(orEmpty(stateCode))) {
            editUsStateCodeExit();
            return;
        }
        state.fail(ScreenField.STATE, FieldFlag.NOT_OK,
                composeMessage(state, AccountUpdateOutcome.SUFFIX_STATE_NOT_VALID));
        editUsStateCodeExit();
    }

    /**
     * {@code 1270-EDIT-US-STATE-CD-EXIT}, lines 2511 to 2513: {@code EXIT} only.
     */
    private static void editUsStateCodeExit() {
        exitParagraph("1270-EDIT-US-STATE-CD-EXIT");
    }

    /**
     * {@code 1275-EDIT-FICO-SCORE}, lines 2514 to 2530: the inclusive 300 to 850 range.
     *
     * <p>The bound is the condition name at lines 848 to 849, declared over the three-digit numeric
     * redefinition at 845 to 847, so both ends are inclusive: 300 and 850 pass, 299 and 851 do not. The
     * message at line 2523 carries <strong>no trailing full stop</strong>.
     *
     * <p>This is <strong>online input validation only</strong>. No persistence constraint and no
     * declarative annotation may express it, because 21 of the 50 seeded customer rows hold a score below
     * 300 - the lowest being 001 - and a constraint would reject the seed data the module ships.
     *
     * <p>The caller gates this edit on the field's own flag still being valid, per lines 1553 to 1554, so
     * a score that already failed the numeric edit is not edited again and does not earn a second message.
     */
    private void editFicoScore(final EditState state, final String ficoScore) {
        state.label = ScreenField.FICO_SCORE.getLegacyLabel();
        final int score = digitsAsInt(ficoScore);
        if (score >= FICO_SCORE_MINIMUM && score <= FICO_SCORE_MAXIMUM) {
            editFicoScoreExit();
            return;
        }
        state.fail(ScreenField.FICO_SCORE, FieldFlag.NOT_OK,
                composeMessage(state, AccountUpdateOutcome.SUFFIX_FICO_OUT_OF_RANGE));
        editFicoScoreExit();
    }

    /**
     * {@code 1275-EDIT-FICO-SCORE-EXIT}, lines 2531 to 2533: {@code EXIT} only.
     */
    private static void editFicoScoreExit() {
        exitParagraph("1275-EDIT-FICO-SCORE-EXIT");
    }

    /**
     * {@code 1280-EDIT-US-STATE-ZIP-CD}, lines 2536 to 2557: the one cross-field edit.
     *
     * <p>The composite key is built at lines 2537 to 2540 by concatenating the two-character state code
     * with the <strong>first two characters</strong> of the postcode, positionally and without a trim,
     * giving a four-character key tested against the 240-entry table. On failure, at lines 2546 to 2547,
     * the legacy sets <strong>both</strong> the state flag and the postcode flag, and the message at
     * lines 2549 to 2552 is the <strong>bare literal with no field-name prefix</strong> - the only
     * message in the program composed that way.
     *
     * <p>The paragraph's own comment calls the check crude, and it is: the table is a prefix map, not a
     * postcode validator. It is reproduced as-is.
     */
    private void editUsStateZipCode(final EditState state, final String stateCode,
            final String zipCode) {
        final String composite = stateZipCompositeKey(stateCode, zipCode);
        if (this.validationLookupService.isValidUsStateZipCodeCombination(composite)) {
            editUsStateZipCodeExit();
            return;
        }
        final String message = AccountUpdateOutcome.MSG_INVALID_ZIP_FOR_STATE;
        state.fail(ScreenField.STATE, FieldFlag.NOT_OK, message);
        state.fail(ScreenField.ZIPCODE, FieldFlag.NOT_OK, message);
        editUsStateZipCodeExit();
    }

    /**
     * {@code 1280-EDIT-US-STATE-ZIP-CD-EXIT}, lines 2558 to 2560: {@code EXIT} only.
     */
    private static void editUsStateZipCodeExit() {
        exitParagraph("1280-EDIT-US-STATE-ZIP-CD-EXIT");
    }

    /**
     * {@code 2000-DECIDE-ACTION}, lines 2562 to 2641: the dispatch selection.
     *
     * <p>Seven clauses and an otherwise branch, evaluated top down, stopping at the first match. The
     * order is contractual because the states overlap: the awaiting-confirmation state appears twice,
     * once conjoined with the save key at lines 2602 to 2603 and once alone at line 2620, and the
     * conjoined clause must be tested first or a save would never happen.
     *
     * <p>The <strong>inner selection at lines 2606 to 2615</strong> is reproduced clause for clause and
     * in order. It reads the <em>summary message</em> rather than a status flag, which has two
     * consequences worth stating. First, the four outcomes it distinguishes are message comparisons, so
     * the four texts are the contract - they are taken from the conflict exception that owns them rather
     * than re-declared. Second, because every message assignment in the write range passes through the
     * first-error-wins gate, an earlier claimed message suppresses the write-failure text and the
     * selection then falls to its otherwise branch. That is the legacy's behaviour and it is preserved.
     *
     * <p>Source anomaly. The customer-lock text is never tested by the inner selection, so a customer
     * that cannot be held for update falls to the otherwise branch and is reported as a completed
     * update. The defect is reproduced, not corrected, and recorded.
     *
     * @return the navigation context as this turn leaves it
     */
    private ScreenNavigationState decideAction(final EditState state,
            final AccountUpdateCommand request, final KeyAction keyAction,
            final ScreenNavigationState context) {
        ScreenNavigationState result = context;

        if (state.action == ChangeAction.DETAILS_NOT_FETCHED || keyAction == KeyAction.PFK12) {
            // Lines 2568 to 2580: fetch and present, or abandon the changes and present again.
            if (state.accountFilter.isValid()) {
                state.clearMessage();
                readAccount(state);
                if (state.foundCustomerInMaster) {
                    state.action = ChangeAction.SHOW_DETAILS;
                }
            }
        } else if (state.action == ChangeAction.SHOW_DETAILS) {
            // Lines 2585 to 2591.
            if (!state.inputError && !MSG_NO_CHANGES_DETECTED.equals(state.returnMessage)) {
                state.action = ChangeAction.CHANGES_OK_NOT_CONFIRMED;
            }
        } else if (state.action == ChangeAction.CHANGES_NOT_OK) {
            // Lines 2596 to 2597: nothing to decide; the decorated screen goes back out.
            LOG.debug("transaction={}: edit errors present, redisplaying with decoration",
                    LEGACY_TRANSACTION_ID);
        } else if (state.action == ChangeAction.CHANGES_OK_NOT_CONFIRMED
                && keyAction == KeyAction.PFK05) {
            // Lines 2602 to 2615: the confirmation arrived, so write and then map the outcome.
            writeProcessing(state, request);
            state.action = switch (state.returnMessage) {
                case OptimisticLockConflictException.MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE ->
                    ChangeAction.CHANGES_OKAYED_LOCK_ERROR;
                case OptimisticLockConflictException.MSG_LOCKED_BUT_UPDATE_FAILED ->
                    ChangeAction.CHANGES_OKAYED_BUT_FAILED;
                case OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE ->
                    ChangeAction.SHOW_DETAILS;
                default -> ChangeAction.CHANGES_OKAYED_AND_DONE;
            };
        } else if (state.action == ChangeAction.CHANGES_OK_NOT_CONFIRMED) {
            // Lines 2620 to 2621: validated but unconfirmed, so prompt again.
            LOG.debug("transaction={}: changes validated, awaiting confirmation",
                    LEGACY_TRANSACTION_ID);
        } else if (state.action == ChangeAction.CHANGES_OKAYED_AND_DONE) {
            // Lines 2625 to 2632: acknowledge, and clear the carried keys when nobody called us.
            state.action = ChangeAction.SHOW_DETAILS;
            if (isUnsuppliedScreenValue(context.fromTransactionId())) {
                result = new ScreenNavigationState(context.fromTransactionId(), context.fromProgram(),
                        context.toTransactionId(), context.toProgram(), context.userId(),
                        context.userType(), context.programContext(), context.customerId(),
                        context.customerFirstName(), context.customerMiddleName(),
                        context.customerLastName(), null, null, null, context.lastMap(),
                        context.lastMapset());
            }
        } else {
            // Lines 2633 to 2640: no clause matched, which the legacy treats as unrecoverable.
            abendRoutine(state, ABEND_CODE_UNEXPECTED_DATA, ABEND_MSG_UNEXPECTED_DATA);
        }
        decideActionExit();
        return result;
    }

    /**
     * {@code 2000-DECIDE-ACTION-EXIT}, lines 2643 to 2645: {@code EXIT} only.
     */
    private static void decideActionExit() {
        exitParagraph("2000-DECIDE-ACTION-EXIT");
    }

    /**
     * {@code 3000-SEND-MAP}, lines 2649 to 2662: the six presentation paragraphs, in order.
     *
     * <p>Header, then values, then the information message, then the field attributes, then the message
     * attributes, then the send. The order matters: the attribute paragraphs run after the value
     * paragraphs, so decoration is applied to a screen that already carries its values.
     */
    private void sendMap(final EditState state, final AccountUpdateCommand request,
            final MapOutput output, final ScreenNavigationState context) {
        screenInit(output);
        setupScreenVars(state, request, output, context);
        setupInfoMessage(state, output);
        final List<String> unprotected = setupScreenAttributes(state, context);
        setupInfoMessageAttributes(state);
        state.unprotectedFieldIds = unprotected;
        sendMapExit();
    }

    /**
     * {@code 3000-SEND-MAP-EXIT}, lines 2664 to 2666: {@code EXIT} only.
     */
    private static void sendMapExit() {
        exitParagraph("3000-SEND-MAP-EXIT");
    }

    /**
     * {@code 3100-SCREEN-INIT}, lines 2668 to 2692: the screen header.
     *
     * <p>Clears the output area, then writes the two catalogue titles, this transaction's identifier and
     * this program's name, and the current date and time in the two eight-character forms the header
     * shows - month, day and two-digit year separated by solidus, and hours, minutes and seconds
     * separated by colon. The clock is injected, so the header is deterministic under test.
     */
    private void screenInit(final MapOutput output) {
        output.clearDetailFields();
        output.title01 = this.messageCatalogService.screenTitle01();
        output.title02 = this.messageCatalogService.screenTitle02();
        output.transactionName = LEGACY_TRANSACTION_ID;
        output.programName = LEGACY_PROGRAM_ID;
        final LocalDateTime now = LocalDateTime.now(this.clock);
        output.currentDate = SCREEN_DATE.format(now);
        output.currentTime = SCREEN_TIME.format(now);
        screenInitExit();
    }

    /**
     * {@code 3100-SCREEN-INIT-EXIT}, lines 2694 to 2696: {@code EXIT} only.
     */
    private static void screenInitExit() {
        exitParagraph("3100-SCREEN-INIT-EXIT");
    }

    /**
     * {@code 3200-SETUP-SCREEN-VARS}, lines 2698 to 2726: which values the screen shows.
     *
     * <p>On a first entry nothing is written at all, per lines 2700 to 2701, so the header alone goes
     * out. Otherwise the account key is echoed - suppressed to the cleared state when it reads as zero
     * and the key is nonetheless valid, per lines 2703 to 2708 - and one of three value paragraphs runs,
     * selected by the four-clause selection at lines 2710 to 2724 whose otherwise branch repeats the
     * original-values choice.
     */
    private void setupScreenVars(final EditState state, final AccountUpdateCommand request,
            final MapOutput output, final ScreenNavigationState context) {
        if (context.firstEntry()) {
            setupScreenVarsExit();
            return;
        }
        final boolean keyReadsZero = isZeroOrAbsentKey(state.accountId);
        output.accountId = (keyReadsZero && state.accountFilter.isValid()) ? null : state.accountId;

        if (state.action == ChangeAction.DETAILS_NOT_FETCHED || keyReadsZero) {
            showInitialValues(output);
        } else if (state.action == ChangeAction.SHOW_DETAILS) {
            showOriginalValues(state, output);
        } else if (state.action.changesMade()) {
            showUpdatedValues(request, output);
        } else {
            showOriginalValues(state, output);
        }
        setupScreenVarsExit();
    }

    /**
     * {@code 3200-SETUP-SCREEN-VARS-EXIT}, lines 2727 to 2729: {@code EXIT} only.
     */
    private static void setupScreenVarsExit() {
        exitParagraph("3200-SETUP-SCREEN-VARS-EXIT");
    }

    /**
     * {@code 3201-SHOW-INITIAL-VALUES}, lines 2731 to 2783: every detail field cleared.
     *
     * <p>The legacy moves the null byte into each of them in turn; the cleared state is an absent
     * component here.
     */
    private void showInitialValues(final MapOutput output) {
        output.clearDetailFields();
        showInitialValuesExit();
    }

    /**
     * {@code 3201-SHOW-INITIAL-VALUES-EXIT}, lines 2783 to 2785: {@code EXIT} only.
     */
    private static void showInitialValuesExit() {
        exitParagraph("3201-SHOW-INITIAL-VALUES-EXIT");
    }

    /**
     * {@code 3202-SHOW-ORIGINAL-VALUES}, lines 2787 to 2867: the records as fetched.
     *
     * <p>The legacy shows its old-image copy, which this translation reads from the records themselves.
     * The stored dates and telephone numbers are separator-delimited images, so they are decomposed into
     * their keyed components by the patterns declared on this class rather than by positional slicing,
     * which belongs to the utility layer. A stored value that does not match its documented shape yields
     * absent components, which is the same cleared state the initial screen shows.
     *
     * <p>The two regulated identifiers are read through the field-encryption service, because the
     * migrated columns hold authenticated ciphertext rather than cleartext, and neither is ever logged.
     * The national identifier is the schema's only nullable column and is null in every seeded row, so
     * absence is normal here rather than exceptional.
     *
     * <p>The postal code is the one component whose stored column is wider than the screen item it is
     * shown in, and line 2843 moves the ten-character stored value into a five-character map field -
     * which keeps its leading five characters and discards the rest. The move is reproduced rather than
     * widened, for two reasons that agree: publishing ten characters would exceed the width the response
     * contract declares for the component and would be refused when the caller echoed it back, and it
     * would show the operator characters the screen never had room for.
     */
    private void showOriginalValues(final EditState state, final MapOutput output) {
        final Account account = state.account;
        final Customer customer = state.customer;
        if (account == null || customer == null) {
            showInitialValues(output);
            showOriginalValuesExit();
            return;
        }
        output.accountId = account.getAcctId();
        output.accountStatus = account.getAcctActiveStatus();
        output.creditLimit = monetary(account.getAcctCreditLimit());
        output.cashCreditLimit = monetary(account.getAcctCashCreditLimit());
        output.currentBalance = monetary(account.getAcctCurrBal());
        output.currentCycleCredit = monetary(account.getAcctCurrCycCredit());
        output.currentCycleDebit = monetary(account.getAcctCurrCycDebit());
        // The group identifier is ten spaces in every seeded row and is never trimmed.
        output.accountGroupId = account.getAcctGroupId();

        final String[] openParts = storedDateParts(account.getAcctOpenDate());
        output.openYear = openParts[0];
        output.openMonth = openParts[1];
        output.openDay = openParts[2];
        final String[] expiryParts = storedDateParts(account.getAcctExpirationDate());
        output.expiryYear = expiryParts[0];
        output.expiryMonth = expiryParts[1];
        output.expiryDay = expiryParts[2];
        final String[] reissueParts = storedDateParts(account.getAcctReissueDate());
        output.reissueYear = reissueParts[0];
        output.reissueMonth = reissueParts[1];
        output.reissueDay = reissueParts[2];

        output.customerId = customer.getCustId();
        output.firstName = customer.getFirstName();
        output.middleName = customer.getMiddleName();
        output.lastName = customer.getLastName();
        output.addressLine1 = customer.getAddrLine1();
        output.addressLine2 = customer.getAddrLine2();
        output.city = customer.getAddrLine3();
        output.stateCode = customer.getAddrStateCd();
        output.countryCode = customer.getAddrCountryCd();
        // Line 2843: the stored ten-character value is moved into a five-character map item.
        output.zipCode = atScreenWidth(customer.getAddrZip(), ZIP_CODE_SCREEN_WIDTH);
        output.eftAccountId = customer.getEftAccountId();
        output.primaryCardHolderIndicator = customer.getPriCardHolderInd();
        output.ficoScore = customer.getFicoCreditScore();

        final String[] dobParts = storedDateParts(customer.getCustDob());
        output.dateOfBirthYear = dobParts[0];
        output.dateOfBirthMonth = dobParts[1];
        output.dateOfBirthDay = dobParts[2];
        final String[] phone1Parts = storedPhoneParts(customer.getPhoneNum1());
        output.phone1AreaCode = phone1Parts[0];
        output.phone1Prefix = phone1Parts[1];
        output.phone1LineNumber = phone1Parts[2];
        final String[] phone2Parts = storedPhoneParts(customer.getPhoneNum2());
        output.phone2AreaCode = phone2Parts[0];
        output.phone2Prefix = phone2Parts[1];
        output.phone2LineNumber = phone2Parts[2];
        final String[] ssnParts = storedSsnParts(revealed(
                SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, customer.getCustSsn()));
        output.ssnPart1 = ssnParts[0];
        output.ssnPart2 = ssnParts[1];
        output.ssnPart3 = ssnParts[2];
        output.governmentIssuedId = revealed(
                SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                customer.getGovtIssuedId());
        showOriginalValuesExit();
    }

    /**
     * {@code 3202-SHOW-ORIGINAL-VALUES-EXIT}, lines 2867 to 2869: {@code EXIT} only.
     */
    private static void showOriginalValuesExit() {
        exitParagraph("3202-SHOW-ORIGINAL-VALUES-EXIT");
    }

    /**
     * {@code 3203-SHOW-UPDATED-VALUES}, lines 2870 to 2951: the values as keyed.
     *
     * <p>Echoes what the operator typed, so a rejected screen comes back carrying the operator's own
     * input beside the decoration rather than the stored values. The five monetary lexemes are converted
     * through the codec only when they are well formed; a rejected lexeme is left absent, because the
     * response contract admits only a correctly scaled amount and the operator's own text is what the
     * field-error list attributes.
     */
    private void showUpdatedValues(final AccountUpdateCommand request, final MapOutput output) {
        output.accountId = screenValue(request.accountId());
        output.accountStatus = screenValue(request.accountStatus());
        output.openYear = screenValue(request.openYear());
        output.openMonth = screenValue(request.openMonth());
        output.openDay = screenValue(request.openDay());
        output.creditLimit = lexemeAsMonetary(request.creditLimit());
        output.expiryYear = screenValue(request.expiryYear());
        output.expiryMonth = screenValue(request.expiryMonth());
        output.expiryDay = screenValue(request.expiryDay());
        output.cashCreditLimit = lexemeAsMonetary(request.cashCreditLimit());
        output.reissueYear = screenValue(request.reissueYear());
        output.reissueMonth = screenValue(request.reissueMonth());
        output.reissueDay = screenValue(request.reissueDay());
        output.currentBalance = lexemeAsMonetary(request.currentBalance());
        output.currentCycleCredit = lexemeAsMonetary(request.currentCycleCredit());
        output.accountGroupId = screenValue(request.accountGroupId());
        output.currentCycleDebit = lexemeAsMonetary(request.currentCycleDebit());
        output.customerId = screenValue(request.customerId());
        output.ssnPart1 = screenValue(request.ssnPart1());
        output.ssnPart2 = screenValue(request.ssnPart2());
        output.ssnPart3 = screenValue(request.ssnPart3());
        output.dateOfBirthYear = screenValue(request.dateOfBirthYear());
        output.dateOfBirthMonth = screenValue(request.dateOfBirthMonth());
        output.dateOfBirthDay = screenValue(request.dateOfBirthDay());
        output.ficoScore = screenValue(request.ficoScore());
        output.firstName = screenValue(request.firstName());
        output.middleName = screenValue(request.middleName());
        output.lastName = screenValue(request.lastName());
        output.addressLine1 = screenValue(request.addressLine1());
        output.stateCode = screenValue(request.stateCode());
        output.addressLine2 = screenValue(request.addressLine2());
        output.zipCode = screenValue(request.zipCode());
        output.city = screenValue(request.city());
        output.countryCode = screenValue(request.countryCode());
        output.phone1AreaCode = screenValue(request.phone1AreaCode());
        output.phone1Prefix = screenValue(request.phone1Prefix());
        output.phone1LineNumber = screenValue(request.phone1LineNumber());
        output.governmentIssuedId = screenValue(request.governmentIssuedId());
        output.phone2AreaCode = screenValue(request.phone2AreaCode());
        output.phone2Prefix = screenValue(request.phone2Prefix());
        output.phone2LineNumber = screenValue(request.phone2LineNumber());
        output.eftAccountId = screenValue(request.eftAccountId());
        output.primaryCardHolderIndicator = screenValue(request.primaryCardHolderIndicator());
        showUpdatedValuesExit();
    }

    /**
     * {@code 3203-SHOW-UPDATED-VALUES-EXIT}, lines 2951 to 2953: {@code EXIT} only.
     */
    private static void showUpdatedValuesExit() {
        exitParagraph("3203-SHOW-UPDATED-VALUES-EXIT");
    }

    /**
     * {@code 3250-SETUP-INFOMSG}, lines 2955 to 2982: the nine-clause information-message selection.
     *
     * <p>Ordered, top down, first match wins, and its last clause fires when no message has been set at
     * all. Both message slots are then written out: the information message and the accumulated return
     * message, which the screen shows as its error line.
     */
    private void setupInfoMessage(final EditState state, final MapOutput output) {
        final boolean firstEntry = state.action == ChangeAction.DETAILS_NOT_FETCHED;
        if (firstEntry) {
            state.infoMessage = INFO_PROMPT_FOR_SEARCH_KEYS;
        } else if (state.action == ChangeAction.SHOW_DETAILS
                || state.action == ChangeAction.CHANGES_NOT_OK) {
            state.infoMessage = INFO_PROMPT_FOR_CHANGES;
        } else if (state.action == ChangeAction.CHANGES_OK_NOT_CONFIRMED) {
            state.infoMessage = INFO_PROMPT_FOR_CONFIRMATION;
        } else if (state.action == ChangeAction.CHANGES_OKAYED_AND_DONE) {
            state.infoMessage = INFO_CONFIRM_UPDATE_SUCCESS;
        } else if (state.action.changesFailed()) {
            state.infoMessage = INFO_INFORM_FAILURE;
        } else if (state.infoMessage.isEmpty()) {
            state.infoMessage = INFO_PROMPT_FOR_SEARCH_KEYS;
        }
        setupInfoMessageExit();
    }

    /**
     * {@code 3250-SETUP-INFOMSG-EXIT}, lines 2983 to 2985: {@code EXIT} only.
     */
    private static void setupInfoMessageExit() {
        exitParagraph("3250-SETUP-INFOMSG-EXIT");
    }

    /**
     * {@code 3300-SETUP-SCREEN-ATTRS}, lines 2986 to 3437: protection, then the 39 decoration sites.
     *
     * <p>Every field is protected first, then the context selection at lines 2993 to 3000 frees either
     * the account key alone, when no detail has been fetched, or the detail fields, when details are on
     * the screen or the last edit failed.
     *
     * <p>The remainder of the paragraph is the 39 expansions of the field-decoration macro, between
     * lines 3208 and 3432, collapsed here into <strong>39 calls</strong> to one marking step - the
     * largest single de-duplication in this refactor, replacing roughly 234 generated lines. The calls
     * appear in the source's own order, which is the order field errors are reported in, and each is
     * bound from the macro's substitution tokens rather than from the comment above it, because three of
     * those comments are wrong.
     *
     * <p>The marking step reproduces the macro's three behaviours: it fires only on re-entry, it marks a
     * field whose flag is not-ok or blank, and only the blank state carries the marker that distinguishes
     * a missing field from an invalid one. A commented-out hand copy of the macro body sits at lines 3196
     * to 3205 in the source and stays inactive here.
     *
     * @return the ordered BMS identifiers left unprotected, for the cursor derivation
     */
    private List<String> setupScreenAttributes(final EditState state,
            final ScreenNavigationState context) {
        final List<String> unprotected = protectAllAttributes();
        if (state.action == ChangeAction.DETAILS_NOT_FETCHED) {
            // Line 2996: only the account key is editable while no detail has been fetched.
            unprotected.add(BMS_ACCOUNT_ID);
        } else if (state.action == ChangeAction.SHOW_DETAILS
                || state.action == ChangeAction.CHANGES_NOT_OK) {
            unprotected.addAll(unprotectFewAttributes());
        }

        // The 39 decoration sites, in source order. Line numbers name the COPY statement each replaces.
        markField(state, context, ScreenField.ACCT_STATUS);          // 3208
        markField(state, context, ScreenField.OPEN_YEAR);            // 3214
        markField(state, context, ScreenField.OPEN_MONTH);           // 3220
        markField(state, context, ScreenField.OPEN_DAY);             // 3226
        markField(state, context, ScreenField.CRED_LIMIT);           // 3232
        markField(state, context, ScreenField.EXPIRY_YEAR);          // 3238
        markField(state, context, ScreenField.EXPIRY_MONTH);         // 3244
        markField(state, context, ScreenField.EXPIRY_DAY);           // 3250
        markField(state, context, ScreenField.CASH_CREDIT_LIMIT);    // 3256
        markField(state, context, ScreenField.REISSUE_YEAR);         // 3262
        markField(state, context, ScreenField.REISSUE_MONTH);        // 3268
        markField(state, context, ScreenField.REISSUE_DAY);          // 3274
        markField(state, context, ScreenField.CURR_BAL);             // 3280
        markField(state, context, ScreenField.CURR_CYC_CREDIT);      // 3286
        markField(state, context, ScreenField.CURR_CYC_DEBIT);       // 3292
        markField(state, context, ScreenField.EDIT_US_SSN_PART1);    // 3298
        markField(state, context, ScreenField.EDIT_US_SSN_PART2);    // 3304
        markField(state, context, ScreenField.EDIT_US_SSN_PART3);    // 3310
        markField(state, context, ScreenField.DT_OF_BIRTH_YEAR);     // 3316
        markField(state, context, ScreenField.DT_OF_BIRTH_MONTH);    // 3322
        markField(state, context, ScreenField.DT_OF_BIRTH_DAY);      // 3328
        markField(state, context, ScreenField.FICO_SCORE);           // 3334
        markField(state, context, ScreenField.FIRST_NAME);           // 3340
        markField(state, context, ScreenField.MIDDLE_NAME);          // 3346
        markField(state, context, ScreenField.LAST_NAME);            // 3352
        markField(state, context, ScreenField.ADDRESS_LINE_1);       // 3358
        markField(state, context, ScreenField.STATE);                // 3364
        markField(state, context, ScreenField.ADDRESS_LINE_2);       // 3370
        markField(state, context, ScreenField.ZIPCODE);              // 3376
        markField(state, context, ScreenField.CITY);                 // 3382
        markField(state, context, ScreenField.COUNTRY);              // 3388
        markField(state, context, ScreenField.PHONE_NUM_1A);         // 3394
        markField(state, context, ScreenField.PHONE_NUM_1B);         // 3400
        markField(state, context, ScreenField.PHONE_NUM_1C);         // 3405
        markField(state, context, ScreenField.PHONE_NUM_2A);         // 3411
        markField(state, context, ScreenField.PHONE_NUM_2B);         // 3417
        markField(state, context, ScreenField.PHONE_NUM_2C);         // 3422
        markField(state, context, ScreenField.PRI_CARDHOLDER);       // 3427
        markField(state, context, ScreenField.EFT_ACCOUNT_ID);       // 3432

        setupScreenAttributesExit();
        return unprotected;
    }

    /**
     * One expansion of {@code app/cpy/CSSETATY.cpy}, lines 17 to 27, with its three tokens supplied.
     *
     * <p>The macro's own gate is line 20: it fires only when the re-enter flag is set, which is why a
     * first entry shows no decoration however the flags stand. The colour is written when the flag is
     * not-ok <em>or</em> blank, per lines 18 to 19 and 21 to 22, and the marker only when it is blank,
     * per lines 23 to 25. Those two failing states are what the response contract exposes separately, so
     * a caller can tell a field that was left empty from one that was filled in wrongly.
     *
     * <p>The decorator is immutable, so each mark yields a new instance that is stored back.
     */
    private void markField(final EditState state, final ScreenNavigationState context,
            final ScreenField field) {
        if (!context.reEntry()) {
            return;
        }
        final FieldFlag flag = state.flag(field);
        if (!flag.requiresDecoration()) {
            return;
        }
        final FieldErrorMarks.FlagState flagState = flag.writesMissingMarker()
                ? FieldErrorMarks.FlagState.BLANK
                : FieldErrorMarks.FlagState.NOT_OK;
        state.decoration =
                state.decoration.mark(field.getFieldName(), field.getBmsFieldId(), flagState);
    }

    /**
     * {@code 3300-SETUP-SCREEN-ATTRS-EXIT}, lines 3437 to 3439: {@code EXIT} only.
     */
    private static void setupScreenAttributesExit() {
        exitParagraph("3300-SETUP-SCREEN-ATTRS-EXIT");
    }

    /**
     * {@code 3310-PROTECT-ALL-ATTRS}, lines 3441 to 3494.
     *
     * <p>Writes the protected attribute into every field on the map, the account key at line 3442
     * included, so the caller starts from a wholly read-only screen and frees only what the context
     * allows.
     *
     * @return a fresh, empty, mutable accumulator: nothing is unprotected yet
     */
    private static List<String> protectAllAttributes() {
        final List<String> unprotected = new ArrayList<>();
        protectAllAttributesExit();
        return unprotected;
    }

    /**
     * {@code 3310-PROTECT-ALL-ATTRS-EXIT}, lines 3496 to 3498: {@code EXIT} only.
     */
    private static void protectAllAttributesExit() {
        exitParagraph("3310-PROTECT-ALL-ATTRS-EXIT");
    }

    /**
     * {@code 3320-UNPROTECT-FEW-ATTRS}, lines 3500 to 3561: the editable detail fields, in source order.
     *
     * <p>Three fields are deliberately left protected while the rest are freed, and each is worth
     * naming: the customer number at line 3531, because it is a key rather than a datum; the country code
     * at line 3547, because the source comment says the edits are country-specific; and the information
     * line at line 3560, because it is output only. Two fields that are <em>not</em> among the 39
     * decorated ones are freed here - the account group identifier at line 3529 and the government-issued
     * identifier at line 3557 - so the editable set is not simply the decorated set.
     *
     * @return the ordered identifiers this paragraph frees
     */
    private static List<String> unprotectFewAttributes() {
        final List<String> freed = new ArrayList<>(List.of(
                ScreenField.ACCT_STATUS.getBmsFieldId(),
                ScreenField.CRED_LIMIT.getBmsFieldId(),
                ScreenField.CASH_CREDIT_LIMIT.getBmsFieldId(),
                ScreenField.CURR_BAL.getBmsFieldId(),
                ScreenField.CURR_CYC_CREDIT.getBmsFieldId(),
                ScreenField.CURR_CYC_DEBIT.getBmsFieldId(),
                ScreenField.OPEN_YEAR.getBmsFieldId(),
                ScreenField.OPEN_MONTH.getBmsFieldId(),
                ScreenField.OPEN_DAY.getBmsFieldId(),
                ScreenField.EXPIRY_YEAR.getBmsFieldId(),
                ScreenField.EXPIRY_MONTH.getBmsFieldId(),
                ScreenField.EXPIRY_DAY.getBmsFieldId(),
                ScreenField.REISSUE_YEAR.getBmsFieldId(),
                ScreenField.REISSUE_MONTH.getBmsFieldId(),
                ScreenField.REISSUE_DAY.getBmsFieldId(),
                ScreenField.DT_OF_BIRTH_YEAR.getBmsFieldId(),
                ScreenField.DT_OF_BIRTH_MONTH.getBmsFieldId(),
                ScreenField.DT_OF_BIRTH_DAY.getBmsFieldId(),
                BMS_ACCOUNT_GROUP_ID,
                ScreenField.EDIT_US_SSN_PART1.getBmsFieldId(),
                ScreenField.EDIT_US_SSN_PART2.getBmsFieldId(),
                ScreenField.EDIT_US_SSN_PART3.getBmsFieldId(),
                ScreenField.FICO_SCORE.getBmsFieldId(),
                ScreenField.FIRST_NAME.getBmsFieldId(),
                ScreenField.MIDDLE_NAME.getBmsFieldId(),
                ScreenField.LAST_NAME.getBmsFieldId(),
                ScreenField.ADDRESS_LINE_1.getBmsFieldId(),
                ScreenField.ADDRESS_LINE_2.getBmsFieldId(),
                ScreenField.CITY.getBmsFieldId(),
                ScreenField.STATE.getBmsFieldId(),
                ScreenField.ZIPCODE.getBmsFieldId(),
                ScreenField.PHONE_NUM_1A.getBmsFieldId(),
                ScreenField.PHONE_NUM_1B.getBmsFieldId(),
                ScreenField.PHONE_NUM_1C.getBmsFieldId(),
                ScreenField.PHONE_NUM_2A.getBmsFieldId(),
                ScreenField.PHONE_NUM_2B.getBmsFieldId(),
                ScreenField.PHONE_NUM_2C.getBmsFieldId(),
                BMS_GOVERNMENT_ISSUED_ID,
                ScreenField.EFT_ACCOUNT_ID.getBmsFieldId(),
                ScreenField.PRI_CARDHOLDER.getBmsFieldId()));
        LOG.trace("attributes: {} fields freed; {}, {} and {} stay protected", freed.size(),
                BMS_CUSTOMER_NUMBER, ScreenField.COUNTRY.getBmsFieldId(), BMS_INFO_MESSAGE);
        unprotectFewAttributesExit();
        return freed;
    }

    /**
     * {@code 3320-UNPROTECT-FEW-ATTRS-EXIT}, lines 3562 to 3564: {@code EXIT} only.
     */
    private static void unprotectFewAttributesExit() {
        exitParagraph("3320-UNPROTECT-FEW-ATTRS-EXIT");
    }

    /**
     * {@code 3390-SETUP-INFOMSG-ATTRS}, lines 3566 to 3583: the message and key-legend highlighting.
     *
     * <p>Three decisions: the information line is dimmed when there is no message and bright when there
     * is; the cancel-key legend is brightened once changes have been made and the update has not yet
     * completed, per lines 3573 to 3576; and both the save and the cancel legends are brightened while a
     * confirmation is being prompted for, per lines 3578 to 3581.
     *
     * <p>Attribute bytes have no representation in the response contract - a REST client renders its own
     * emphasis - so the outcome is recorded as a diagnostic. The part of the attribute layer that
     * <em>is</em> observable, the two-state field error and the focus field, is carried by the response.
     */
    private void setupInfoMessageAttributes(final EditState state) {
        final List<String> highlighted = new ArrayList<>();
        if (!state.infoMessage.isEmpty()) {
            highlighted.add(BMS_INFO_MESSAGE);
        }
        if (state.action.changesMade() && state.action != ChangeAction.CHANGES_OKAYED_AND_DONE) {
            highlighted.add(BMS_FUNCTION_KEY_12);
        }
        if (INFO_PROMPT_FOR_CONFIRMATION.equals(state.infoMessage)) {
            highlighted.add(BMS_FUNCTION_KEY_05);
            if (!highlighted.contains(BMS_FUNCTION_KEY_12)) {
                highlighted.add(BMS_FUNCTION_KEY_12);
            }
        }
        LOG.trace("attributes: highlighted controls {}", highlighted);
        setupInfoMessageAttributesExit();
    }

    /**
     * {@code 3390-SETUP-INFOMSG-ATTRS-EXIT}, lines 3584 to 3586: {@code EXIT} only.
     */
    private static void setupInfoMessageAttributesExit() {
        exitParagraph("3390-SETUP-INFOMSG-ATTRS-EXIT");
    }

    /**
     * {@code 3400-SEND-SCREEN}, lines 3589 to 3602: the send.
     *
     * <p>The legacy declares its own mapset and map as the next screen, at lines 3591 to 3592, and sends
     * with the cursor positioned, the screen erased and the keyboard freed. Here the send is the returned
     * response: the same mapset and map identity travel in the navigation context, and the cursor becomes
     * the focus field - the first field in error when there is one, and otherwise the first field the
     * attribute paragraphs left unprotected.
     */
    private AccountUpdateOutcome sendScreen(final EditState state, final MapOutput output,
            final ScreenNavigationState context, final String route) {
        final List<ValidationException.FieldError> fieldErrors = state.decoration.fieldErrors();
        final ScreenNavigationState outgoing = new ScreenNavigationState(LEGACY_TRANSACTION_ID,
                LEGACY_PROGRAM_ID, context.toTransactionId(), context.toProgram(), context.userId(),
                context.userType(), context.programContext(), emptyToNull(state.customerId),
                context.customerFirstName(), context.customerMiddleName(),
                context.customerLastName(), emptyToNull(state.accountId), output.accountStatus,
                emptyToNull(state.cardNumber), LEGACY_MAP, LEGACY_MAPSET);
        final AccountUpdateOutcome response = new AccountUpdateOutcome(output.transactionName,
                output.title01, output.currentDate, output.programName, output.title02,
                output.currentTime, output.accountId, output.accountStatus, output.openYear,
                output.openMonth, output.openDay, output.creditLimit, output.expiryYear,
                output.expiryMonth, output.expiryDay, output.cashCreditLimit, output.reissueYear,
                output.reissueMonth, output.reissueDay, output.currentBalance,
                output.currentCycleCredit, output.accountGroupId, output.currentCycleDebit,
                output.customerId, output.ssnPart1, output.ssnPart2, output.ssnPart3,
                output.dateOfBirthYear, output.dateOfBirthMonth, output.dateOfBirthDay,
                output.ficoScore, output.firstName, output.middleName, output.lastName,
                output.addressLine1, output.stateCode, output.addressLine2, output.zipCode,
                output.city, output.countryCode, output.phone1AreaCode, output.phone1Prefix,
                output.phone1LineNumber, output.governmentIssuedId, output.phone2AreaCode,
                output.phone2Prefix, output.phone2LineNumber, output.eftAccountId,
                output.primaryCardHolderIndicator, emptyToNull(state.infoMessage),
                emptyToNull(state.returnMessage), state.inputError, focusFieldId(state, fieldErrors),
                route, outgoing, fieldErrors, emptyToNull(state.concurrencyToken));
        sendScreenExit();
        return response;
    }

    /**
     * {@code 3400-SEND-SCREEN-EXIT}, lines 3603 to 3605: {@code EXIT} only.
     */
    private static void sendScreenExit() {
        exitParagraph("3400-SEND-SCREEN-EXIT");
    }

    /**
     * {@code 9000-READ-ACCT}, lines 3608 to 3644: the three-step record resolution.
     *
     * <p>Cross-reference by account, then the account master, then the customer master, with a guard
     * after each. No association is declared anywhere in this module, so the join is these three explicit
     * calls and nothing else.
     *
     * @return whether both records were resolved
     */
    private boolean readAccount(final EditState state) {
        if (state.account != null && state.customer != null) {
            return true;
        }
        state.infoMessage = "";

        getCardXrefByAccount(state);
        if (state.accountFilter.isNotOk()) {
            readAccountExit();
            return false;
        }

        getAccountDataByAccount(state);
        if (MSG_ACCOUNT_NOT_IN_MASTER.equals(state.returnMessage)) {
            readAccountExit();
            return false;
        }

        getCustomerDataByCustomer(state);
        if (MSG_CUSTOMER_NOT_IN_MASTER.equals(state.returnMessage)) {
            readAccountExit();
            return false;
        }

        storeFetchedData(state);
        readAccountExit();
        return state.account != null && state.customer != null;
    }

    /**
     * {@code 9000-READ-ACCT-EXIT}, lines 3647 to 3649: {@code EXIT} only.
     */
    private static void readAccountExit() {
        exitParagraph("9000-READ-ACCT-EXIT");
    }

    /**
     * The part of the three catch-all read arms that is identical in all three: raise the input error,
     * name the operation and the resource, and move the composed file-error text into the message slot.
     *
     * <p>Not a paragraph and it owes no traceability row: the three paragraphs that call it own their
     * rows, and each supplies the resource it named and raises its own filter flag afterwards, which is
     * where the three arms genuinely differ.
     *
     * <p><strong>The move is ungated.</strong> The source composes this text into {@code WS-RETURN-MSG}
     * with no {@code IF WS-RETURN-MSG-OFF} around it, unlike every not-found arm, so it replaces whatever
     * an earlier edit had claimed. That asymmetry is reproduced by assigning the slot directly rather
     * than claiming it through the gate.
     *
     * @param state        the per-turn state
     * @param resourceName the cluster or path the failed read named
     * @param readFailure  the failure the read raised
     */
    private static void recordReadFailure(final EditState state, final String resourceName,
            final DataAccessException readFailure) {
        state.markInputError();
        state.returnMessage = fileErrorMessage(resourceName);
        LOG.error("transaction={} operation={} resource={}: the read failed; failureChain={}",
                LEGACY_TRANSACTION_ID, OPERATION_READ, resourceName,
                FailureDiagnostics.failureChainOf(readFailure));
    }

    /**
     * Records a read-for-update that returned a non-normal response.
     *
     * <p>Separate from {@link #recordReadFailure(EditState, String, DataAccessException)} because the
     * write range's own arms are separate: they compose no file-error text at all, they claim the
     * could-not-lock text through the message gate, and they leave the range immediately. Only the
     * diagnostic is shared in spirit, so only the diagnostic is factored.
     *
     * @param resourceName the cluster the hold was attempted on
     * @param holdFailure  the failure the read raised
     */
    private static void logHoldFailure(final String resourceName,
            final DataAccessException holdFailure) {
        LOG.warn("transaction={} operation={} resource={}: the read for update returned a non-normal"
                        + " response, so the write range was left; failureChain={}",
                LEGACY_TRANSACTION_ID, OPERATION_READ, resourceName,
                FailureDiagnostics.failureChainOf(holdFailure));
    }

    /**
     * {@code 9200-GETCARDXREF-BYACCT}, lines 3650 to 3697: the cross-reference read.
     *
     * <p>The legacy reads the cross-reference through its account-identifier alternate index, which is a
     * non-unique path, and takes the record the path positions on. The repository declares that as a
     * first-match finder ordered by card number, so the take-the-first semantics are in the query rather
     * than in a list traversal here, and an empty result is the not-found condition.
     *
     * <p>On success the customer identifier and the card number are carried forward, per lines 3666 to
     * 3667. On not-found the legacy composes a message that interpolates the account key and the CICS
     * response and reason codes; neither code exists here and a business key does not belong in
     * operator-visible text, so the condition name's own declared text is claimed instead. That is
     * recorded as a divergence.
     *
     * <p><strong>The catch-all arm at lines 3686 to 3695 is a third arm and not a variant of the
     * second.</strong> A read that fails is not a read that found nothing: the legacy distinguishes them
     * and reports the failure with the operation name, the resource name and the response pair rather
     * than with the business text, and it moves that composition into the message slot
     * <em>ungated</em> - so it overwrites a message an earlier edit had already claimed, which the
     * not-found arm does not. Here a repository failure is that arm. Letting it propagate instead would
     * abend a transaction the legacy leaves on the screen with a message.
     */
    private void getCardXrefByAccount(final EditState state) {
        // Lines 3654 to 3661: one keyed READ of the alternate-index path. The repository's
        // ordered-first finder is that read: bounded to one row and ordered on the base key, which is
        // what a keyed read of a duplicate-bearing path returns.
        final Optional<CardCrossReference> located;
        try {
            located = this.cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(state.accountId);
        } catch (final DataAccessException readFailure) {
            // Lines 3686 to 3695: WHEN OTHER.
            recordReadFailure(state, RESOURCE_CARD_XREF_PATH, readFailure);
            state.accountFilter = FieldFlag.NOT_OK;
            getCardXrefByAccountExit();
            return;
        }
        if (located.isEmpty()) {
            state.markInputError();
            state.accountFilter = FieldFlag.NOT_OK;
            state.claimMessage(MSG_ACCOUNT_NOT_IN_XREF);
            LOG.info("transaction={} resource={}: no cross-reference for the requested account",
                    LEGACY_TRANSACTION_ID, RESOURCE_CARD_XREF_PATH);
            getCardXrefByAccountExit();
            return;
        }
        final CardCrossReference xref = located.get();
        state.customerId = orEmpty(xref.getXrefCustId());
        state.cardNumber = orEmpty(xref.getXrefCardNum());
        getCardXrefByAccountExit();
    }

    /**
     * {@code 9200-GETCARDXREF-BYACCT-EXIT}, lines 3698 to 3700: {@code EXIT} only.
     */
    private static void getCardXrefByAccountExit() {
        exitParagraph("9200-GETCARDXREF-BYACCT-EXIT");
    }

    /**
     * {@code 9300-GETACCTDATA-BYACCT}, lines 3701 to 3746: the account master read.
     *
     * <p>Source anomaly, and a documented divergence from it. The not-found arm's
     * {@code SET DID-NOT-FIND-ACCT-IN-ACCTDAT} is commented out at line 3719, so the condition the
     * caller's guard at line 3627 tests is never satisfied and the legacy walks on to read the customer
     * master with an unresolved key. This translation claims the declared text, which makes that guard
     * reachable and stops the flow. The divergence is deliberate and fail-safe: it prevents a screen
     * being built from records that were never fetched.
     *
     * <p>The catch-all arm at lines 3736 to 3745 is reproduced as its own arm for the reason given on the
     * cross-reference read: a failing read is not a missing row, it names the resource rather than the
     * business condition, and its composition overwrites a claimed message where the not-found arm
     * would not.
     */
    private void getAccountDataByAccount(final EditState state) {
        final Optional<Account> located;
        try {
            located = this.accountRepository.findById(state.accountId);
        } catch (final DataAccessException readFailure) {
            // Lines 3736 to 3745: WHEN OTHER.
            recordReadFailure(state, RESOURCE_ACCOUNT_MASTER, readFailure);
            state.accountFilter = FieldFlag.NOT_OK;
            getAccountDataByAccountExit();
            return;
        }
        if (located.isEmpty()) {
            state.markInputError();
            state.accountFilter = FieldFlag.NOT_OK;
            state.claimMessage(MSG_ACCOUNT_NOT_IN_MASTER);
            LOG.info("transaction={} resource={}: account not present in the master",
                    LEGACY_TRANSACTION_ID, RESOURCE_ACCOUNT_MASTER);
            getAccountDataByAccountExit();
            return;
        }
        state.account = located.get();
        state.foundAccountInMaster = true;
        getAccountDataByAccountExit();
    }

    /**
     * {@code 9300-GETACCTDATA-BYACCT-EXIT}, lines 3748 to 3750: {@code EXIT} only.
     */
    private static void getAccountDataByAccountExit() {
        exitParagraph("9300-GETACCTDATA-BYACCT-EXIT");
    }

    /**
     * {@code 9400-GETCUSTDATA-BYCUST}, lines 3752 to 3796: the customer master read, keyed by the
     * identifier the cross-reference supplied.
     *
     * <p>Three arms, as the source declares them. The catch-all at lines 3785 to 3794 raises the
     * <em>customer</em> filter flag rather than the account one - which is the only structural difference
     * between this paragraph's failing arms and the two above - and composes the file-error text naming
     * the customer master.
     */
    private void getCustomerDataByCustomer(final EditState state) {
        final Optional<Customer> located;
        try {
            located = this.customerRepository.findById(state.customerId);
        } catch (final DataAccessException readFailure) {
            // Lines 3785 to 3794: WHEN OTHER.
            recordReadFailure(state, RESOURCE_CUSTOMER_MASTER, readFailure);
            state.customerFilter = FieldFlag.NOT_OK;
            getCustomerDataByCustomerExit();
            return;
        }
        if (located.isEmpty()) {
            state.markInputError();
            state.customerFilter = FieldFlag.NOT_OK;
            state.claimMessage(MSG_CUSTOMER_NOT_IN_MASTER);
            LOG.info("transaction={} resource={}: customer not present in the master",
                    LEGACY_TRANSACTION_ID, RESOURCE_CUSTOMER_MASTER);
            getCustomerDataByCustomerExit();
            return;
        }
        state.customer = located.get();
        state.foundCustomerInMaster = true;
        getCustomerDataByCustomerExit();
    }

    /**
     * {@code 9400-GETCUSTDATA-BYCUST-EXIT}, lines 3797 to 3799: {@code EXIT} only.
     */
    private static void getCustomerDataByCustomerExit() {
        exitParagraph("9400-GETCUSTDATA-BYCUST-EXIT");
    }

    /**
     * {@code 9500-STORE-FETCHED-DATA}, lines 3801 to 3884: the old image.
     *
     * <p>The legacy copies every fetched field into the commarea extension so the next turn can compare
     * against it. This translation is stateless, so the equivalent is a sealed token minted from both
     * records - the change check verifies against it rather than against a copied structure - and the
     * records themselves serve as the old image within a turn.
     *
     * <p>When either record is absent the area is left as the caller initialised it at line 3610, which
     * is why nothing is minted and nothing is dereferenced.
     *
     * <p>The other place a before-image is established is the success point of the write range, which
     * re-mints over the records it has just rewritten. The two do not overlap: this paragraph mints only
     * when no image arrived, and that one only after an image has been superseded.
     */
    private void storeFetchedData(final EditState state) {
        if (state.account == null || state.customer == null) {
            storeFetchedDataExit();
            return;
        }
        // A before-image that arrived on this turn is the one the legacy carried in the commarea from
        // the turn that actually fetched the records, and it is the only image the change check may
        // compare against. Minting over it would compare the rows against themselves as they were read
        // moments earlier, which always agrees and would make the change check unreachable.
        //
        // Suppressing the mint on that path is faithful rather than defensive: the legacy performs
        // 9500-STORE-FETCHED-DATA only from the fetch arm of its dispatch at lines 2568 to 2580. The
        // extra read this translation performs at the head of the edit driver has no legacy counterpart
        // at all - it is the stateless substitute for the old image already sitting in the commarea - so
        // the legacy did not execute this paragraph on that path either.
        if (!state.concurrencyToken.isEmpty()) {
            LOG.debug("transaction={}: a before-image arrived with this turn, so the fetched records "
                    + "do not replace it", LEGACY_TRANSACTION_ID);
            storeFetchedDataExit();
            return;
        }
        state.concurrencyToken =
                this.concurrencyTokenService.mint(state.account, state.customer);
        storeFetchedDataExit();
    }

    /**
     * {@code 9500-STORE-FETCHED-DATA-EXIT}, lines 3885 to 3887: {@code EXIT} only.
     */
    private static void storeFetchedDataExit() {
        exitParagraph("9500-STORE-FETCHED-DATA-EXIT");
    }

    /**
     * {@code 9600-WRITE-PROCESSING}, lines 3888 to 4103: the write range.
     *
     * <p>Written as a labelled single-pass loop because the exit label at line 4105 is the target of
     * <strong>seven</strong> transfers, not one: five forward jumps from within this paragraph, at lines
     * 3914, 3941, 3951, 4080 and 4102, and two <em>backward</em> jumps from the change check, at lines
     * 4144 and 4190. Every one of them becomes the same labelled break, so the structure is a loop rather
     * than recursion and no transfer is modelled as a special case.
     *
     * <p><strong>The rollback asymmetry, lines 4076 to 4103, remains observable.</strong> Both rewrites
     * execute in one independent transaction. An account-arm failure exits before a customer write has
     * occurred, while a customer-arm failure rolls back the account rewrite that already succeeded.
     * Both failures return through the same legacy update-failed message and outcome selection, and
     * neither can escape later as a rollback-only exception because mapping happens after the boundary
     * has completed its rollback. Neither arm abends.
     *
     * <p>Concurrency control is declarative on the entity - a version column that the provider checks on
     * flush - so no lock mode, no lock hint and no pessimistic read appears anywhere. Where the provider
     * reports a conflict, this method translates it.
     *
     * <p><strong>Both holds test for the normal response and treat everything else alike.</strong> The
     * source writes {@code IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL) ... ELSE}, not a three-arm evaluation,
     * so a missing row and a failing read reach one arm: the input error is raised, the could-not-lock
     * text is claimed through the message gate, and the range is left. A read that raised instead of
     * returning nothing is therefore mapped onto that arm rather than escaping, because escaping would
     * abend a turn the legacy leaves on the screen with a message.
     */
    private void writeProcessing(final EditState state, final AccountUpdateCommand request) {
        writeRange:
        while (true) {
            // Lines 3892 to 3915: hold the account. The source's test is
            // IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL) ... ELSE, so EVERY non-normal response is the
            // failure to hold it - not only the missing row. An absent result and a failing read are
            // therefore the same arm here, which is what makes the arm reachable for both.
            final Optional<Account> heldAccount;
            try {
                heldAccount = this.accountRepository.findById(state.accountId);
            } catch (final DataAccessException holdFailure) {
                logHoldFailure(RESOURCE_ACCOUNT_MASTER, holdFailure);
                state.markInputError();
                state.claimMessage(
                        OptimisticLockConflictException.MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE);
                break writeRange;
            }
            if (heldAccount.isEmpty()) {
                state.markInputError();
                state.claimMessage(
                        OptimisticLockConflictException.MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE);
                break writeRange;
            }
            final Account account = heldAccount.get();

            // Lines 3919 to 3942: hold the customer, over the same all-non-normal-responses test.
            final Optional<Customer> heldCustomer;
            try {
                heldCustomer = this.customerRepository.findById(state.customerId);
            } catch (final DataAccessException holdFailure) {
                logHoldFailure(RESOURCE_CUSTOMER_MASTER, holdFailure);
                state.markInputError();
                state.claimMessage(
                        OptimisticLockConflictException.MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE);
                break writeRange;
            }
            if (heldCustomer.isEmpty()) {
                state.markInputError();
                state.claimMessage(
                        OptimisticLockConflictException.MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE);
                break writeRange;
            }
            final Customer customer = heldCustomer.get();
            final Customer customerBefore = copyCustomer(customer);

            // Lines 3947 to 3952, and the two backward transfers from the change check.
            if (checkChangeInRecord(state, account, customer)) {
                break writeRange;
            }

            // Lines 3956 to 4059: prepare both images.
            applyAccountChanges(request, account);
            applyCustomerChanges(request, customer);

            // Lines 4065 to 4103: both rewrites share one durable transaction. The stage marker keeps
            // the source's account-versus-customer failure arms distinguishable after rollback.
            try {
                this.transactionBoundary.execute(() -> {
                    state.rewriteStage = RewriteStage.ACCOUNT;
                    this.accountRepository.saveAndFlush(account);
                    state.rewriteStage = RewriteStage.CUSTOMER;
                    if (this.customerRepository.compareAndSet(customerBefore, customer) != 1) {
                        throw new CustomerRecordChangedException();
                    }
                    return Boolean.TRUE;
                });
            } catch (final CustomerRecordChangedException changed) {
                state.markInputError();
                state.claimMessage(
                        OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
                LOG.info("transaction={} resource={}: the held customer image no longer matched;"
                                + " the account rewrite was rolled back",
                        LEGACY_TRANSACTION_ID, RESOURCE_CUSTOMER_MASTER);
                break writeRange;
            } catch (final RuntimeException writeFailed) {
                state.markInputError();
                state.claimMessage(OptimisticLockConflictException.MSG_LOCKED_BUT_UPDATE_FAILED);
                if (state.rewriteStage == RewriteStage.ACCOUNT) {
                    LOG.warn("transaction={} resource={}: the account rewrite failed before the "
                                    + "customer rewrite; the empty unit of work was rolled back;"
                                    + " failureChain={}",
                            LEGACY_TRANSACTION_ID, RESOURCE_ACCOUNT_MASTER,
                            FailureDiagnostics.failureChainOf(writeFailed));
                } else {
                    LOG.warn("transaction={} resource={}: the customer rewrite failed; the account "
                                    + "rewrite completed earlier in the unit and was rolled back;"
                                    + " failureChain={}",
                            LEGACY_TRANSACTION_ID, RESOURCE_CUSTOMER_MASTER,
                            FailureDiagnostics.failureChainOf(writeFailed));
                }
                break writeRange;
            }

            state.account = account;
            state.customer = customer;
            // Both rewrites committed, so the records the caller was shown a moment ago are no longer
            // the records on file. The legacy did not have to say anything here: its old-image copy
            // became irrelevant because the completed state at lines 2625 to 2632 resets the
            // conversation and the next turn re-fetches. This translation carries the old image in the
            // response instead of in a commarea, so the before-image is re-minted over the rewritten
            // records. Leaving the pre-write token in place would present an image and a before-image
            // that disagree, and the very next change would be refused as somebody else's - a conflict
            // this turn caused itself.
            state.concurrencyToken = this.concurrencyTokenService.mint(account, customer);
            break writeRange;
        }
        writeProcessingExit();
    }

    private static Customer copyCustomer(final Customer source) {
        return new Customer(source);
    }

    private static final class CustomerRecordChangedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * {@code 9600-WRITE-PROCESSING-EXIT}, lines 4105 to 4107: {@code EXIT} only, and the busiest exit
     * label in the member - the target of five forward and two backward transfers.
     */
    private static void writeProcessingExit() {
        exitParagraph("9600-WRITE-PROCESSING-EXIT");
    }

    /**
     * {@code 9700-CHECK-CHANGE-IN-REC}, lines 4109 to 4192: did anyone change the records meanwhile.
     *
     * <p>The legacy compares each freshly held record against the old image it carried, in two blocks
     * whose failure arms are the two backward transfers at lines 4144 and 4190. Both blocks are owned by
     * the concurrency-token service, which is documented as this paragraph and which digests the two
     * records rather than copying them, so the regulated identifiers never have to be decrypted to answer
     * the question. The version column the provider checks on flush answers a different question - a
     * change between holding and writing - and neither check replaces the other.
     *
     * @return whether the records changed, which is both backward transfers to the write exit
     */
    private boolean checkChangeInRecord(final EditState state, final Account account,
            final Customer customer) {
        try {
            this.concurrencyTokenService.verify(state.concurrencyToken, account, customer);
        } catch (final OptimisticLockConflictException changed) {
            state.markInputError();
            state.claimMessage(
                    OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
            LOG.info("transaction={} conflictKind={}: the records moved before the update",
                    LEGACY_TRANSACTION_ID, changed.conflictKind());
            checkChangeInRecordExit();
            return true;
        }
        checkChangeInRecordExit();
        return false;
    }

    /**
     * {@code 9700-CHECK-CHANGE-IN-REC-EXIT}, lines 4193 to 4195: {@code EXIT} only.
     */
    private static void checkChangeInRecordExit() {
        exitParagraph("9700-CHECK-CHANGE-IN-REC-EXIT");
    }

    /**
     * {@code ABEND-ROUTINE}, lines 4203 to 4225.
     *
     * <p>Substitutes the default message when none was supplied, per lines 4205 to 4207, names this
     * program as the culprit at line 4209, transmits the abend structure, deregisters the handler at lines
     * 4218 to 4220, and abends with its own code at lines 4222 to 4223. The diagnostic is emitted first
     * and carries the code and the resource, never a regulated identifier and never the national
     * identifier.
     *
     * <p>Reached only from the dispatch selection's otherwise branch. An optimistic-lock conflict never
     * arrives here.
     */
    private void abendRoutine(final EditState state, final String abendCode,
            final String abendMessage) {
        final String message = (abendMessage == null || abendMessage.isEmpty())
                ? ABEND_MSG_DEFAULT
                : abendMessage;
        this.abendService.displayIoStatus(abendCode, ABEND_MSG_UNEXPECTED_DATA,
                RESOURCE_ACCOUNT_MASTER);
        LOG.error("transaction={} program={} abcode={} action={}: {}", LEGACY_TRANSACTION_ID,
                LEGACY_PROGRAM_ID, ABEND_ABCODE, state.action, message);
        this.abendService.abendOnline(LEGACY_PROGRAM_ID, message, message);
        abendRoutineExit();
    }

    /**
     * {@code ABEND-ROUTINE-EXIT}, lines 4226 to 4228: {@code EXIT} only. Unreachable in practice, because
     * the routine it follows always raises; translated and named because the paragraph exists.
     */
    private static void abendRoutineExit() {
        exitParagraph("ABEND-ROUTINE-EXIT");
    }

    /* ==========================================================================================
     * Grafted-in and delegated behaviour, and the primitives the paragraphs above are written in.
     * ========================================================================================== */

    /**
     * {@code COPY 'CSSTRPFY'} at line 4199, and the validity test at lines 905 to 916 that follows the
     * performed range at lines 898 to 899.
     *
     * <p>The key mapping itself belongs to {@code PfKeyTranslator}, whose two paragraphs are credited
     * there rather than duplicated here. Two of its properties matter to this caller: keys 13 to 24 fold
     * onto keys 1 to 12, so they are not distinct actions, and the mapping has no otherwise branch, so an
     * identifier it does not recognise produces no action at all. There is no previously held value to
     * retain in a stateless conversation, so an unrecognised identifier raises the invalid-key flag and
     * claims the catalogue's invalid-key text, which is fifty characters wide and is never trimmed.
     *
     * @param raw the raw attention identifier, or {@code null} to take the typed action as given
     */
    private KeyAction storePfKey(final EditState state, final AccountUpdateCommand request,
            final String raw, final ScreenInputState workArea) {
        if (raw == null) {
            return workArea.attentionKey().orElseGet(
                    () -> request.keyAction() == null ? KeyAction.ENTER : request.keyAction());
        }
        final Optional<KeyAction> translated = PfKeyTranslator.translate(raw);
        if (translated.isEmpty()) {
            state.pfkInvalid = true;
            state.markInputError();
            state.claimMessage(this.messageCatalogService.invalidKeyMessage());
            return KeyAction.ENTER;
        }
        return translated.get();
    }

    /**
     * Lines 905 to 916: is the pressed key usable in the state the conversation is in.
     *
     * <p>Four keys are usable, and two of them conditionally: enter always, the exit key always, the save
     * key only while changes are validated and unconfirmed, and the cancel key only once details have been
     * fetched. Anything else is forced to enter, which is how the legacy makes an out-of-context key
     * redisplay the screen rather than act.
     *
     * @return the key that will actually be acted on
     */
    private KeyAction screenKeyIsValid(final EditState state, final KeyAction pressed) {
        final boolean usable = pressed == KeyAction.ENTER
                || pressed == KeyAction.PFK03
                || (pressed == KeyAction.PFK05
                        && state.action == ChangeAction.CHANGES_OK_NOT_CONFIRMED)
                || (pressed == KeyAction.PFK12
                        && state.action != ChangeAction.DETAILS_NOT_FETCHED);
        if (usable) {
            return pressed;
        }
        state.pfkInvalid = true;
        return KeyAction.ENTER;
    }

    /**
     * Lines 927 to 959: the exit key.
     *
     * <p>Names this transaction and program as the caller for whoever runs next, hands the user type and
     * the entry state back to their initial values, records this mapset and map as the last one shown, and
     * reaches the synchronisation point at line 953 - which here is the commit of the surrounding
     * transaction, needing no statement of its own. The destination is resolved by the navigation service,
     * which falls back to the user menu exactly as lines 930 to 942 do when nothing called us.
     */
    private AccountUpdateOutcome exitToCaller(final EditState state,
            final ScreenNavigationState context, final MapOutput output) {
        final NavigationService.Route destination = this.navigationService
                .resolveBackNavigation(carriedState(context), NavigationService.Route.USER_MENU);
        final ScreenNavigationState outgoing = new ScreenNavigationState(LEGACY_TRANSACTION_ID,
                LEGACY_PROGRAM_ID, destination.getLegacyTransactionId(),
                destination.getLegacyProgramName(), context.userId(), context.userType(),
                ScreenNavigationState.ProgramContext.ENTER, context.customerId(),
                context.customerFirstName(), context.customerMiddleName(),
                context.customerLastName(), context.accountId(), context.accountStatus(),
                context.cardNumber(), LEGACY_MAP, LEGACY_MAPSET);
        screenInit(output);
        LOG.debug("transaction={} key=PFK03 destination={}", LEGACY_TRANSACTION_ID,
                destination.getRouteValue());
        return sendScreen(state, output, outgoing, destination.getRouteValue());
    }

    /**
     * Lines 1478 to 1507 and 1533 to 1538: one date group through the copybook cascade.
     *
     * <p>The whole cascade is delegated, never re-implemented. It runs year, then month, then day, then
     * the combined day-month-year check, then the strict calendar stage, each with its own early exit, and
     * it returns one flag per component. Delegating the head paragraph alone would silently skip every
     * stage after the first, which is the single most dangerous mistranslation available in this estate.
     *
     * <p>The cascade's own accumulated message is offered to the summary slot through the same
     * first-error-wins gate as everything else, so a date failure cannot displace an earlier one.
     */
    private void editDateGroup(final EditState state, final ScreenField yearField,
            final ScreenField monthField, final ScreenField dayField, final String year,
            final String month, final String day) {
        state.label = yearField.getLegacyLabel();
        final String candidate = dateCandidate(year, month, day);
        final DateValidationService.DateEditResult result =
                this.dateValidationService.validateCcyymmddDate(candidate, state.returnMessage);
        applyDateResult(state, yearField, monthField, dayField, result);
    }

    /**
     * Lines 1539 to 1543: the date-of-birth check, which lies outside the cascade's range.
     *
     * <p>A separate entry point on the same collaborator, reached only when all three group flags came
     * back valid, and compared against the injected clock's current date so the outcome is deterministic
     * under test.
     */
    private void editDateOfBirth(final EditState state, final AccountUpdateCommand request) {
        state.label = ScreenField.DT_OF_BIRTH_YEAR.getLegacyLabel();
        final String candidate = dateCandidate(request.dateOfBirthYear(),
                request.dateOfBirthMonth(), request.dateOfBirthDay());
        final DateValidationService.DateEditResult result = this.dateValidationService
                .validateDateOfBirth(candidate, LocalDate.now(this.clock), state.returnMessage);
        applyDateResult(state, ScreenField.DT_OF_BIRTH_YEAR, ScreenField.DT_OF_BIRTH_MONTH,
                ScreenField.DT_OF_BIRTH_DAY, result);
    }

    /**
     * Moves the cascade's three flags into the three field flags, which is the
     * {@code MOVE WS-EDIT-DATE-FLGS TO ...} at lines 1482, 1494, 1507, 1538 and 1542.
     */
    private void applyDateResult(final EditState state, final ScreenField yearField,
            final ScreenField monthField, final ScreenField dayField,
            final DateValidationService.DateEditResult result) {
        state.setFlag(yearField, fieldFlagOf(result.yearFlag()));
        state.setFlag(monthField, fieldFlagOf(result.monthFlag()));
        state.setFlag(dayField, fieldFlagOf(result.dayFlag()));
        if (result.inputError()) {
            state.markInputError();
        }
        if (!result.returnMessage().isEmpty()) {
            state.claimMessage(result.returnMessage());
        }
        recordDateFieldMessage(state, yearField, monthField, dayField, result);
    }

    /**
     * Keeps the cascade's message against whichever of the three components failed first, so the response
     * can attribute it. Purely additive: it changes no flag and no summary message.
     */
    private static void recordDateFieldMessage(final EditState state, final ScreenField yearField,
            final ScreenField monthField, final ScreenField dayField,
            final DateValidationService.DateEditResult result) {
        if (result.returnMessage().isEmpty()) {
            return;
        }
        for (final ScreenField candidate : List.of(yearField, monthField, dayField)) {
            if (state.flag(candidate).requiresDecoration()) {
                state.fieldMessages.putIfAbsent(candidate, result.returnMessage());
                return;
            }
        }
    }

    /**
     * The group-level valid condition the date work fields declare: all three components valid.
     *
     * @return whether the date-of-birth check may run
     */
    private static boolean dateGroupIsValid(final EditState state, final ScreenField yearField,
            final ScreenField monthField, final ScreenField dayField) {
        return state.flag(yearField).isValid() && state.flag(monthField).isValid()
                && state.flag(dayField).isValid();
    }

    /** Maps the date collaborator's flag triad onto this program's own, constant for constant. */
    private static FieldFlag fieldFlagOf(final DateValidationService.DateEditFlag flag) {
        return switch (flag) {
            case VALID -> FieldFlag.ISVALID;
            case NOT_OK -> FieldFlag.NOT_OK;
            case BLANK -> FieldFlag.BLANK;
        };
    }

    /**
     * The two fields the source decorates and never edits: the flag is left in its valid state so the
     * field accepts whatever was keyed, and no message is ever composed against it.
     */
    private static void noEditsCoded(final EditState state, final ScreenField field) {
        state.setFlag(field, FieldFlag.ISVALID);
        LOG.trace("field={} token={}: no edits coded, any input is accepted",
                field.getFieldName(), field.getLegacyFlagToken());
    }

    /**
     * Applies the keyed account values to the held record, reproducing the moves at lines 3956 to 4002.
     *
     * <p>Eleven fields are written and the postal code is not, because the update record the legacy
     * builds has no slot for it - the same omission the change check makes. The three dates are rebuilt in
     * the separator-delimited form the legacy strings together, and the reissue date is written twice in
     * the source, at line 3993 and then again at 3994 to 4000, of which only the second survives.
     *
     * <p>No amount is scaled here. Every monetary lexeme goes through the codec, which is the only holder
     * of a rounding policy in the module and truncates toward zero.
     */
    private static void applyAccountChanges(final AccountUpdateCommand request,
            final Account account) {
        account.setAcctActiveStatus(screenValue(request.accountStatus()));
        account.setAcctCurrBal(lexemeAsMonetary(request.currentBalance()));
        account.setAcctCreditLimit(lexemeAsMonetary(request.creditLimit()));
        account.setAcctCashCreditLimit(lexemeAsMonetary(request.cashCreditLimit()));
        account.setAcctCurrCycCredit(lexemeAsMonetary(request.currentCycleCredit()));
        account.setAcctCurrCycDebit(lexemeAsMonetary(request.currentCycleDebit()));
        account.setAcctOpenDate(storedDate(request.openYear(), request.openMonth(),
                request.openDay()));
        account.setAcctExpirationDate(storedDate(request.expiryYear(), request.expiryMonth(),
                request.expiryDay()));
        account.setAcctReissueDate(storedDate(request.reissueYear(), request.reissueMonth(),
                request.reissueDay()));
        account.setAcctGroupId(request.accountGroupId());
    }

    /**
     * Applies the keyed customer values to the held record, reproducing the moves at lines 4007 to 4059.
     *
     * <p>The two telephone numbers are rebuilt into the parenthesised form the record stores, the date of
     * birth into its separator-delimited form, and the national identifier by concatenating its three
     * keyed parts. Both regulated identifiers are sealed before they are set, because the entity refuses
     * cleartext for either of them - which is the mechanism that stops an unprotected value ever reaching
     * the persistence boundary.
     */
    private void applyCustomerChanges(final AccountUpdateCommand request, final Customer customer) {
        customer.setFirstName(screenValue(request.firstName()));
        customer.setMiddleName(screenValue(request.middleName()));
        customer.setLastName(screenValue(request.lastName()));
        customer.setAddrLine1(screenValue(request.addressLine1()));
        customer.setAddrLine2(screenValue(request.addressLine2()));
        customer.setAddrLine3(screenValue(request.city()));
        customer.setAddrStateCd(screenValue(request.stateCode()));
        customer.setAddrCountryCd(screenValue(request.countryCode()));
        customer.setAddrZip(screenValue(request.zipCode()));
        customer.setPhoneNum1(storedPhone(request.phone1AreaCode(), request.phone1Prefix(),
                request.phone1LineNumber()));
        customer.setPhoneNum2(storedPhone(request.phone2AreaCode(), request.phone2Prefix(),
                request.phone2LineNumber()));
        customer.setCustSsn(this.fieldEncryption.protectNullable(
                SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD,
                storedSsn(request.ssnPart1(), request.ssnPart2(), request.ssnPart3())));
        customer.setGovtIssuedId(this.fieldEncryption.protectNullable(
                SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                screenValue(request.governmentIssuedId())));
        customer.setCustDob(storedDate(request.dateOfBirthYear(), request.dateOfBirthMonth(),
                request.dateOfBirthDay()));
        customer.setEftAccountId(screenValue(request.eftAccountId()));
        customer.setPriCardHolderInd(screenValue(request.primaryCardHolderIndicator()));
        customer.setFicoCreditScore(screenValue(request.ficoScore()));
    }

    /**
     * The cursor the send positions, at line 3597, expressed as a field identifier.
     *
     * <p>The first field in error when there is one, so an operator's attention lands where the decoration
     * is; otherwise the first field the attribute paragraphs left unprotected.
     */
    private static String focusFieldId(final EditState state,
            final List<ValidationException.FieldError> fieldErrors) {
        if (!fieldErrors.isEmpty()) {
            return fieldErrors.get(0).bmsFieldId();
        }
        return state.unprotectedFieldIds.isEmpty() ? null : state.unprotectedFieldIds.get(0);
    }

    /**
     * The {@code EXIT} statement every exit paragraph consists of, recorded once so that each of the
     * forty-one named exit methods above has a body rather than an empty one.
     *
     * @param paragraphName the COBOL paragraph this exit belongs to
     */
    private static void exitParagraph(final String paragraphName) {
        LOG.trace("paragraph {} reached", paragraphName);
    }

    /**
     * {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) suffix DELIMITED BY SIZE INTO WS-RETURN-MSG}:
     * the composition every edit message uses.
     *
     * @param  state  the per-turn state, whose label slot supplies the prefix
     * @param  suffix the literal this failure contributes
     * @return the composed text
     */
    private static String composeMessage(final EditState state, final String suffix) {
        return state.label.trim() + suffix;
    }

    /** Absent becomes the empty string, so a null never reaches a comparison or a length test. */
    private static String orEmpty(final String value) {
        return value == null ? "" : value;
    }

    /**
     * One value as a fixed-width alphanumeric field: left justified, space filled, truncated on the
     * right.
     *
     * <p>The three moves that assemble the file-error text are moves into declared alphanumeric items,
     * and a move into such an item pads or truncates on the right. Doing that arithmetically here means
     * the composed text is the declared width by construction rather than by the caller remembering.
     *
     * @param value the value to place in the field
     * @param width the field's declared width
     * @return exactly {@code width} characters
     */
    private static String fieldImage(final String value, final int width) {
        final String supplied = orEmpty(value);
        if (supplied.length() >= width) {
            return supplied.substring(0, width);
        }
        return supplied + " ".repeat(width - supplied.length());
    }

    /**
     * Composes {@code WS-FILE-ERROR-MESSAGE}, lines 389 to 408, at exactly the width of the field the
     * catch-all arms move it into.
     *
     * <p><strong>The two response slots are left blank rather than filled with an invented pair.</strong>
     * They hold a CICS response and reason code in the legacy, and a relational store reports neither, so
     * nothing is fabricated to occupy them; their declared width and blank initial value are what the
     * composition carries. That is the same convention the read-only twin of this transaction uses, so an
     * operator sees one shape of file-error text across the account screens rather than two.
     *
     * @param resourceName the cluster or path the failed read named
     * @return the composed message, exactly {@value #RETURN_MESSAGE_WIDTH} characters
     */
    private static String fileErrorMessage(final String resourceName) {
        return fieldImage(FILE_ERROR_PREFIX
                + fieldImage(OPERATION_READ, OPERATION_NAME_WIDTH)
                + FILE_ERROR_ON
                + fieldImage(resourceName, ERROR_FILE_NAME_WIDTH)
                + FILE_ERROR_RETURNED_RESP
                + fieldImage("", RESPONSE_CODE_WIDTH)
                + FILE_ERROR_RESP2
                + fieldImage("", RESPONSE_CODE_WIDTH), RETURN_MESSAGE_WIDTH);
    }

    /** The empty string becomes absent, which is how the cleared screen state is represented. */
    private static String emptyToNull(final String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    /**
     * A stored value as the screen item it is moved into holds it.
     *
     * <p>A COBOL {@code MOVE} into a narrower alphanumeric item keeps the leading characters and discards
     * the surplus, so a value already inside the width is returned untouched and a wider one is cut. Only
     * the presentation of a value uses this: no comparison, no edit and no write narrows anything, so the
     * stored value keeps its own width everywhere except on the screen.
     *
     * @param storedValue the value as the record holds it, or {@code null} when the record holds none
     * @param screenWidth the declared width of the map item the value is shown in
     * @return the value at the screen item's width, or {@code null} when there was none
     */
    private static String atScreenWidth(final String storedValue, final int screenWidth) {
        if (storedValue == null || storedValue.length() <= screenWidth) {
            return storedValue;
        }
        return storedValue.substring(0, screenWidth);
    }

    /**
     * One transmitted screen field as the receive paragraph takes it: the decoration marker and an
     * all-space value both read as the cleared state, per the shape at lines 1051 to 1058.
     */
    private static String screenValue(final String transmitted) {
        if (transmitted == null) {
            return null;
        }
        final String trimmed = transmitted.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return null;
        }
        return transmitted;
    }

    /**
     * {@code EQUAL LOW-VALUES OR EQUAL SPACES OR FUNCTION LENGTH(FUNCTION TRIM(...)) = 0}: the blank test
     * every required edit opens with.
     */
    private static boolean isUnsuppliedScreenValue(final String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * {@code EQUAL SPACES}: a value that was transmitted and holds only spaces. Kept distinct from the
     * untransmitted case because the telephone shortcut's reproduced defect turns on the difference.
     */
    private static boolean isSpaces(final String value) {
        return value != null && !value.isEmpty() && value.trim().isEmpty();
    }

    /**
     * {@code EQUAL LOW-VALUES}: a component that was not transmitted at all, which is what the cleared
     * field holds.
     */
    private static boolean isLowValues(final String value) {
        return value == null || value.isEmpty();
    }

    /**
     * {@code IS NUMERIC} on a display field: true only when every position holds an ASCII digit.
     *
     * <p>Deliberately not {@code Character.isDigit}, which is Unicode-aware and would admit digits the
     * legacy picture clause cannot hold, and deliberately not a regular expression, for the same reason.
     */
    private static boolean isAllDigits(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /** {@code FUNCTION NUMVAL(...) = 0} over an all-digit value: every position is a zero. */
    private static boolean isZeroDigits(final String value) {
        if (!isAllDigits(value)) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '0') {
                return false;
            }
        }
        return true;
    }

    /** The numeric redefinition of an all-digit field, read as an integer. */
    private static int digitsAsInt(final String value) {
        if (!isAllDigits(value)) {
            return -1;
        }
        int accumulated = 0;
        for (int index = 0; index < value.length(); index++) {
            accumulated = accumulated * 10 + (value.charAt(index) - '0');
        }
        return accumulated;
    }

    /** {@code CC-ACCT-ID-N = 0}, tested at lines 2703 and 2712 through the numeric redefinition. */
    private static boolean isZeroOrAbsentKey(final String key) {
        return isUnsuppliedScreenValue(key) || isZeroDigits(key);
    }

    /**
     * {@code INVALID-SSN-PART1}, lines 121 to 123: zero, 666, or 900 through 999 inclusive.
     */
    private static boolean isExcludedSsnFirstPart(final String part) {
        final int value = digitsAsInt(part);
        return value == SSN_PART1_EXCLUDED_ZERO
                || value == SSN_PART1_EXCLUDED_666
                || (value >= SSN_PART1_EXCLUDED_RANGE_START
                        && value <= SSN_PART1_EXCLUDED_RANGE_END);
    }

    /**
     * The composite key built at lines 2537 to 2540: the two-character state code followed by the first
     * two characters of the postcode, positionally and without a trim.
     *
     * <p>Assembled by concatenation from the two keyed components, so no positional slicing is needed: the
     * postcode's first two characters are the ones a caller keyed into the first two positions, and a
     * shorter value simply contributes what it has, which is what the space-padded field would too.
     */
    private static String stateZipCompositeKey(final String stateCode, final String zipCode) {
        final String state = orEmpty(stateCode);
        final String zip = orEmpty(zipCode);
        final StringBuilder key = new StringBuilder(4).append(state);
        for (int index = 0; index < ZIP_PREFIX_WIDTH; index++) {
            key.append(index < zip.length() ? zip.charAt(index) : ' ');
        }
        return key.toString();
    }

    /** Brings a stored amount to the contract's scale through the codec, never through a local rescale. */
    private static BigDecimal monetary(final BigDecimal stored) {
        return stored == null ? null : ZonedDecimalCodec.toMonetaryScale(stored);
    }

    /**
     * Converts a keyed monetary lexeme, or reports absence when it is unsupplied or malformed.
     *
     * <p>A malformed lexeme has already earned its own field error, and the response contract admits only
     * a correctly scaled amount, so absence is the honest projection rather than a substituted zero.
     */
    private static BigDecimal lexemeAsMonetary(final String lexeme) {
        if (CobolStringUtils.isUnsuppliedNumericLexeme(lexeme)
                || !CobolStringUtils.isNumericLexeme(lexeme)) {
            return null;
        }
        final BigDecimal converted = ZonedDecimalCodec.fromNumericLexeme(lexeme);
        return fitsRecordAmount(converted) ? converted : null;
    }

    /**
     * Whether an amount fits the ten integer digits the record field declares, which is the same bound the
     * response contract enforces on itself.
     */
    private static boolean fitsRecordAmount(final BigDecimal amount) {
        return amount != null
                && amount.precision() - amount.scale() <= AccountUpdateOutcome.MONEY_INTEGER_DIGITS;
    }

    /** The eight-character candidate the date cascade takes, assembled from its three keyed parts. */
    private static String dateCandidate(final String year, final String month, final String day) {
        return padded(year, 4) + padded(month, 2) + padded(day, 2);
    }

    /** The separator-delimited stored form the write path strings together. */
    private static String storedDate(final String year, final String month, final String day) {
        return padded(year, 4) + "-" + padded(month, 2) + "-" + padded(day, 2);
    }

    /** The parenthesised stored form the write path strings together at lines 4027 to 4041. */
    private static String storedPhone(final String areaCode, final String prefix,
            final String lineNumber) {
        return "(" + padded(areaCode, PHONE_AREA_AND_PREFIX_WIDTH) + ")"
                + padded(prefix, PHONE_AREA_AND_PREFIX_WIDTH) + "-"
                + padded(lineNumber, PHONE_LINE_NUMBER_WIDTH);
    }

    /** The nine-digit stored form, or absence when no part was keyed. */
    private static String storedSsn(final String part1, final String part2, final String part3) {
        final String joined = orEmpty(part1) + orEmpty(part2) + orEmpty(part3);
        return joined.trim().isEmpty() ? null : joined;
    }

    /**
     * Space-pads a keyed component to the width of the field it occupies, which is what moving a shorter
     * value into a fixed-width picture does.
     */
    private static String padded(final String value, final int width) {
        final String present = orEmpty(value);
        if (present.length() >= width) {
            return present;
        }
        final StringBuilder padded = new StringBuilder(width).append(present);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * Decomposes a stored date into its three keyed components by matching the documented
     * separator-delimited shape, never by positional slicing.
     *
     * @return a three-element array of year, month and day, each absent when the value does not match
     */
    private static String[] storedDateParts(final String stored) {
        final String[] parts = new String[] {null, null, null};
        if (stored == null) {
            return parts;
        }
        final Matcher matched = STORED_DATE.matcher(stored.trim());
        if (matched.matches()) {
            parts[0] = matched.group(1);
            parts[1] = matched.group(2);
            parts[2] = matched.group(3);
        }
        return parts;
    }

    /**
     * Decomposes a stored telephone number into its three keyed components by matching the shape the edit
     * paragraph's comment documents at lines 2227 to 2228.
     *
     * @return a three-element array of area code, prefix and line number, each absent on no match
     */
    private static String[] storedPhoneParts(final String stored) {
        final String[] parts = new String[] {null, null, null};
        if (stored == null) {
            return parts;
        }
        final Matcher matched = STORED_PHONE.matcher(stored.trim());
        if (matched.matches()) {
            parts[0] = matched.group(1);
            parts[1] = matched.group(2);
            parts[2] = matched.group(3);
        }
        return parts;
    }

    /**
     * Decomposes a revealed national identifier into the three keyed screen components.
     *
     * <p>The value is never logged and never placed in a message; the response record redacts it from its
     * own rendering. Absence is the normal case, because the column is the schema's only nullable one and
     * is null in every seeded row.
     *
     * @return a three-element array, each absent when no value is held or it does not match
     */
    private static String[] storedSsnParts(final String revealed) {
        final String[] parts = new String[] {null, null, null};
        if (revealed == null) {
            return parts;
        }
        final Matcher matched = STORED_SSN.matcher(revealed.trim());
        if (matched.matches()) {
            parts[0] = matched.group(1);
            parts[1] = matched.group(2);
            parts[2] = matched.group(3);
        }
        return parts;
    }

    /**
     * Reads a protected column, tolerating absence and refusing to interpret a value it cannot open.
     *
     * <p>Neither the value nor any part of it reaches a log, here or anywhere below.
     */
    private String revealed(final String fieldName, final String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        try {
            return this.fieldEncryption.revealNullable(fieldName, stored);
        } catch (final IllegalArgumentException | IllegalStateException unreadable) {
            LOG.warn("transaction={} field={}: the protected value could not be opened and is "
                    + "treated as absent; failureChain={}", LEGACY_TRANSACTION_ID, fieldName,
                    FailureDiagnostics.failureChainOf(unreadable));
            return null;
        }
    }

    /** A key comparison at the width of the field, so leading zeros and padding cannot disagree. */
    private static boolean sameKey(final String keyed, final String stored, final int width) {
        return CobolStringUtils.rightJustifyZeroFill(orEmpty(keyed).trim(), width)
                .equals(CobolStringUtils.rightJustifyZeroFill(orEmpty(stored).trim(), width));
    }

    /** {@code FUNCTION UPPER-CASE(a) = FUNCTION UPPER-CASE(b)} with no trim, per lines 1685 to 1688. */
    private static boolean sameFolded(final String keyed, final String stored) {
        return CobolStringUtils.asciiUpperFold(orEmpty(keyed))
                .equals(CobolStringUtils.asciiUpperFold(orEmpty(stored)));
    }

    /** The folded and trimmed comparison, per lines 1697 to 1700 and the customer block. */
    private static boolean sameFoldedTrimmed(final String keyed, final String stored) {
        return CobolStringUtils.asciiUpperFold(orEmpty(keyed).trim())
                .equals(CobolStringUtils.asciiUpperFold(orEmpty(stored).trim()));
    }

    /**
     * A numeric comparison of two amounts, which is what comparing two zoned two-decimal fields is: a
     * value differing only in trailing zeros is not a change.
     */
    private static boolean sameAmount(final String keyedLexeme, final BigDecimal stored) {
        final BigDecimal keyed = lexemeAsMonetary(keyedLexeme);
        if (keyed == null || stored == null) {
            return keyed == null && stored == null;
        }
        return keyed.compareTo(ZonedDecimalCodec.toMonetaryScale(stored)) == 0;
    }

    /**
     * A date comparison by parts rather than by string, which is what testing positions 1 to 4, 6 to 7 and
     * 9 to 10 amounts to: a value differing only in its separators is not a change.
     */
    private static boolean sameDate(final String year, final String month, final String day,
            final String stored) {
        final String[] parts = storedDateParts(stored);
        return orEmpty(year).trim().equals(orEmpty(parts[0]).trim())
                && orEmpty(month).trim().equals(orEmpty(parts[1]).trim())
                && orEmpty(day).trim().equals(orEmpty(parts[2]).trim());
    }

    /** A telephone comparison by parts, per lines 1748 to 1753 which compare the three sub-fields. */
    private static boolean samePhone(final String areaCode, final String prefix,
            final String lineNumber, final String stored) {
        final String[] parts = storedPhoneParts(stored);
        return orEmpty(areaCode).trim().equals(orEmpty(parts[0]).trim())
                && orEmpty(prefix).trim().equals(orEmpty(parts[1]).trim())
                && orEmpty(lineNumber).trim().equals(orEmpty(parts[2]).trim());
    }

    /** A national-identifier comparison by parts, never logged and never messaged. */
    private static boolean sameSsn(final String part1, final String part2, final String part3,
            final String revealed) {
        final String[] parts = storedSsnParts(revealed);
        return orEmpty(part1).trim().equals(orEmpty(parts[0]).trim())
                && orEmpty(part2).trim().equals(orEmpty(parts[1]).trim())
                && orEmpty(part3).trim().equals(orEmpty(parts[2]).trim());
    }

    /** The navigation context as it arrived, with absence made explicit rather than left null. */
    private static ScreenNavigationState incomingContext(final AccountUpdateCommand request) {
        return request.navigationContext() == null
                ? ScreenNavigationState.empty()
                : request.navigationContext();
    }

    /**
     * Lines 880 to 882: is this a fresh entry.
     *
     * <p>Either nothing was carried at all, which is the zero-length commarea the legacy tests for, or the
     * caller was the user menu and this is not a re-entry.
     */
    private boolean freshEntry(final ScreenNavigationState context) {
        return isNavigationStateAbsent(context)
                || (isMenuProgram(context.fromProgram()) && !context.reEntry());
    }

    /** {@code CDEMO-FROM-PROGRAM = LIT-MENUPGM}, the literal declared at line 558. */
    private static boolean isMenuProgram(final String fromProgram) {
        return NavigationService.Route.USER_MENU.getLegacyProgramName()
                .equals(orEmpty(fromProgram).trim());
    }

    /**
     * The conversation state this turn starts in, which the legacy carried in the commarea extension.
     *
     * <p>Derived rather than carried, and derived from evidence the legacy itself establishes. A turn with
     * no concurrency token cannot have been presented with details, because the token is minted only when
     * both records have been fetched. A turn arriving with the save key must have come from the
     * confirmation screen, because that is the only screen on which the attribute paragraphs free the save
     * legend, at lines 3578 to 3581. Anything else is a turn on the detail screen.
     *
     * <p><strong>What a derived state cannot be trusted for.</strong> The legacy read this state out of
     * its own storage, so it was evidence that the previous turn had validated an image and that the
     * screen the image came back from was protected. Derived from an arriving request, it is evidence of
     * neither: the caller chose the key and the caller supplied the image. Two consumers therefore treat
     * the awaiting-confirmation state as a claim to be checked rather than a fact - the edit driver
     * re-runs the edits over the submitted image, and it demotes the state to the detail screen when the
     * image turns out to carry no change at all, which is a combination the legacy could not reach.
     */
    private static ChangeAction resolveIncomingChangeAction(final AccountUpdateCommand request) {
        if (isUnsuppliedScreenValue(request.concurrencyToken())) {
            return ChangeAction.DETAILS_NOT_FETCHED;
        }
        if (request.keyAction() == KeyAction.PFK05) {
            return ChangeAction.CHANGES_OK_NOT_CONFIRMED;
        }
        return ChangeAction.SHOW_DETAILS;
    }

    /** This transaction's own route, which every turn but the exit stays on. */
    private static String currentRoute() {
        return NavigationService.Route.ACCOUNT_UPDATE.getRouteValue();
    }

    /**
     * {@code CC-WORK-AREAS} of {@code app/cpy/CVCRD01Y.cpy}, populated as the legacy populates it: the
     * pressed key, this program's own next-screen declaration, and the three business keys.
     *
     * <p>Its numeric views are what the zero tests at lines 2703 and 2712 read, so the work area is built
     * rather than bypassed.
     */
    private static ScreenInputState screenWorkArea(final EditState state,
            final AccountUpdateCommand request) {
        return new ScreenInputState(request.keyAction(), LEGACY_PROGRAM_ID, LEGACY_MAPSET, LEGACY_MAP,
                emptyToNull(state.returnMessage), emptyToNull(state.returnMessage),
                emptyToNull(state.accountId), emptyToNull(state.cardNumber),
                emptyToNull(state.customerId));
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
