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
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.DateFormat;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.CobolStringUtils;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.ZonedDecimalCodec;

/**
 * The transaction-add screen: the online path by which an operator writes a new row into the
 * transaction master.
 *
 * <p>Translation of {@code app/cbl/COTRN02C.cbl}, legacy CICS transaction {@code CT02}, 783 source
 * lines and 18 procedure-division paragraphs. The legacy tree is read-only reference and no source
 * text from it is copied here: this record cites member names, paragraph names, line numbers, field
 * names, widths and the exact operator-visible message literals only.
 *
 * <p><strong>This member is outside the five-program family.</strong> It includes neither the
 * attention-key copybook {@code app/cpy/CSSTRPFY.cpy} nor {@code app/cpy/CVCRD01Y.cpy}, and it
 * declares no {@code EXEC CICS HANDLE ABEND}. Accordingly this service wires <em>no</em> abend path,
 * routes through <em>no</em> key translator, and decodes the attention key directly in the shape the
 * source's own evaluation uses.
 *
 * <h2>Paragraph map: all 18 paragraphs, each to one named method</h2>
 * Each of the eighteen paragraphs resolves to exactly one named method below, and
 * {@code processTransactionAdd} carries the terminal {@code EXEC CICS RETURN TRANSID} of
 * {@code MAIN-PARA} at lines 156 to 159. The mapping with its source lines is held once in
 * {@code docs/traceability-matrix.md}.
 *
 * <h2>The send ends the task, so a turn reports at most one field failure</h2>
 * The send paragraph at lines 516 to 534 issues {@code EXEC CICS SEND MAP} and then
 * {@code EXEC CICS RETURN TRANSID}, which <strong>ends the task</strong>. Every
 * call of SEND-TRNADD-SCREEN inside the validation cascade is therefore terminal: the
 * performing paragraph never resumes, no later check runs, no later field is normalised, and no
 * insert is attempted. The consequence is contractual rather than incidental &mdash; the operator
 * sees the <em>first</em> failure's text at the <em>first</em> failure's cursor position and nothing
 * else &mdash; so the send latches a turn-ended state that every caller up the chain tests, and the
 * summary message is latched at the first failure and never overwritten.
 *
 * <h2>The online 26-character timestamp: the fraction is always six zeros</h2>
 * The estate carries <strong>two distinct 26-character timestamp forms and they are deliberately not
 * unified</strong>. This member is online, so it emits the online form and never the batch form. The
 * layout is proven by {@code app/cpy/CSDAT01Y.cpy} lines 42 to 55, which this member includes at line
 * 85: a four-digit year, a hyphen, a two-digit month, a hyphen, a two-digit day, a
 * <strong>space</strong> at position 11, a two-digit hour, a colon, two-digit minutes, a colon,
 * two-digit seconds, a period at position 20, and a six-digit fraction at positions 21 to 26 &mdash;
 * exactly 26 positions.
 *
 * <p>The construction is proven by {@code app/cbl/COBIL00C.cbl} lines 249 to 267, the canonical
 * online timestamp paragraph of the estate, reused here: the ten-character date and the
 * eight-character time are moved into their fixed positions and the six-digit fraction is then set to
 * zeros, while the initialise verb leaves the literal fillers alone so the space at position 11 and
 * the period at position 20 survive. The rendered value therefore <strong>always</strong> ends in
 * {@code .000000}, whatever sub-second value the clock happens to carry, and the seeded
 * daily-transaction origination timestamps confirm it independently. {@code onlineTimestamp} forces
 * the zeros rather than formatting a real microsecond field and asserts the result is exactly 26
 * encoded bytes.
 *
 * <p>The helper is private to this class on purpose. No shared or top-level timestamp formatter type
 * exists anywhere in the module: the batch form &mdash; a hyphen before the hour, dots between the
 * time parts, two hundredths digits and four literal zeros &mdash; belongs to the posting and
 * interest services as <em>their</em> private helpers and is never imported, shared or reused across
 * the tier boundary.
 *
 * <h2>The stored timestamps are the two screen values, not the clock</h2>
 * <strong>This member's own decision, and it differs from the bill-payment program's.</strong> At
 * {@code app/cbl/COBIL00C.cbl} lines 231 and 232 one clock-derived value is moved to both the
 * origination and the processing timestamp. This member does not: at lines 464 and 465
 * {@code ADD-TRANSACTION} moves the origination screen field into the origination timestamp and the
 * <em>separate</em> processing screen field into the processing timestamp, so the two values are
 * independent operator input. Both screen fields are ten characters wide, and a move of a
 * ten-character sender into the 26-character record field left justifies and space fills, which is
 * reproduced exactly. Substituting a clock value here would overwrite operator input and change the
 * stored record, so it is not done; the online timestamp this service builds is the screen header's,
 * which is where the member's own use of the date and time work group lies, at line 554.
 *
 * <h2>Two of the estate's four genuine date-validation call sites live here</h2>
 * The date-validation subprogram is invoked exactly four times in the whole estate: twice here, at
 * <strong>line 393</strong> for the origination date and <strong>line 413</strong> for the processing
 * date, and twice in the report-request program. Both sites here follow the same shape &mdash; move
 * the candidate, move the format literal {@code YYYY-MM-DD} from the ten-character work field at line
 * 60, blank the result block, call &mdash; and both are delegated to {@code DateValidationService}.
 *
 * <p><strong>The acceptance test is two-level and is not collapsed.</strong> Lines 397 to 407 and
 * identically 417 to 427 accept the date when the severity code equals the four-character literal
 * {@code 0000}; otherwise they inspect the message number and <em>stay silent</em> when it equals the
 * four-character literal {@code 2513}, emitting the error only for any other message number. So
 * <strong>a non-zero severity carrying message number {@code 2513} is accepted silently</strong>.
 * Both comparisons are text comparisons against four-character literals and neither field is parsed.
 * Collapsing the two levels into one boolean, or treating every non-zero severity as a failure, would
 * reject dates the legacy accepts.
 *
 * <h2>The identifier is a 16-character business key derived by browsing backwards</h2>
 * {@code ADD-TRANSACTION} mints the new key without any sequence facility: it seeds the record key
 * with high values at line 444, starts a browse, reads <strong>backward</strong> once &mdash; which
 * lands on the highest key present &mdash; ends the browse, moves what it found into a sixteen-digit
 * numeric work field, adds one, and moves the result back into the sixteen-character key. The
 * backward read's end-of-file arm at line 689 moves zeros into the key, so an empty table seeds zero
 * and the first identifier minted is the sixteen-character string {@code 0000000000000001} and never
 * a bare {@code 1}. The zero fill is delegated to {@code CobolStringUtils.rightJustifyZeroFill}
 * rather than assembled inline.
 *
 * <p>The browse is realised with the inherited paged, descending lookup and <strong>not</strong> with
 * the repository's maximum-identifier query: this member needs the whole highest-keyed
 * <em>record</em>, because the copy-last-transaction path at lines 481 to 492 populates eleven screen
 * fields from it. The allocating path takes the repository's transaction-scoped advisory lock before
 * that browse and holds it through the flushed insert, so the full-record read remains serial without
 * turning the identifier rule into a sequence. Identifier generation by maximum-key-plus-one over the
 * key alone belongs to the bill-payment service. No database sequence, no generated value and no
 * random identifier is used anywhere: a sequence never reuses a value it has handed out, whereas this
 * rule always reuses a gap, and the first rollback after an identifier is consumed would make a
 * sequence diverge permanently.
 *
 * <h2>Relationships are explicit repository calls</h2>
 * There are no persistence associations anywhere in this module and no foreign keys on the
 * transaction-related tables, deliberately, so every relationship is resolved by an explicit call.
 * The alternate-index read at lines 576 to 604 becomes the first-matching cross-reference row of an
 * account, and an <strong>absent row is the legacy not-found response</strong> with its own message
 * and its own cursor position rather than an exception. The base-cluster read at lines 609 to 637
 * becomes an identity lookup on the card number. Neither the account master nor the card master is
 * read at all: the member declares an account-file name at line 40 and never references it again,
 * and it issues no read against either file, so neither repository is injected. Injecting a
 * collaborator this translation cannot use would be dead code.
 *
 * <h2>What this service does not do</h2>
 *
 * <p>It adds one transaction, and nothing else: paging, rendering a stored transaction and date-range
 * querying belong to the list, view and report services, and the bill-payment behaviour that drives a
 * balance to zero belongs to its own service. It performs no rescaling - the monetary conversion is
 * delegated to the module's single decimal authority, which truncates toward zero because the estate
 * contains no rounding directive on any arithmetic statement anywhere.
 *
 * <p>This bean is a stateless singleton. Everything the legacy held in working storage lives in a
 * per-invocation state object, so two concurrent turns cannot observe one another and no identifier
 * is ever cached between calls.
 *
 * <p>The screen turn is non-transactional. Identifier lock, highest-key read and insert execute
 * together inside {@link OnlineTransactionBoundary}, so duplicate or write failures are translated
 * only after that unit has rolled back.
 *
 * @since 1.0.0
 */
@Service
public final class TransactionAddService {

    /** Diagnostic channel replacing the four {@code DISPLAY} statements at lines 598, 631, 662, 691. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionAddService.class);

    // ==========================================================================================
    // Program identity, from WS-VARIABLES at lines 36 and 37
    // ==========================================================================================

    /** {@code WS-PGMNAME}, {@code PIC X(08) VALUE 'COTRN02C'} at line 36. */
    private static final String WS_PGMNAME = "COTRN02C";

    /** {@code WS-TRANID}, {@code PIC X(04) VALUE 'CT02'} at line 37. */
    private static final String WS_TRANID = "CT02";

    // ==========================================================================================
    // The external-contract message literals, in source order.
    //
    // Casing, spacing and dot counts are contractual and are never normalised: the eleven
    // emptiness texts carry a capital-N NOT and three trailing dots, the three format texts
    // carry NO trailing dots at all, and the success fragments carry leading and trailing
    // spaces that are part of the literal.
    // ==========================================================================================

    /** Line 178. */
    private static final String MSG_CONFIRM_TO_ADD = "Confirm to add this transaction...";

    /** Line 184. */
    private static final String MSG_INVALID_CONFIRM_VALUE = "Invalid value. Valid values are (Y/N)...";

    /** Line 199. */
    private static final String MSG_ACCOUNT_ID_NOT_NUMERIC = "Account ID must be Numeric...";

    /** Line 213. */
    private static final String MSG_CARD_NUMBER_NOT_NUMERIC = "Card Number must be Numeric...";

    /** Line 226. */
    private static final String MSG_KEY_FIELD_REQUIRED = "Account or Card Number must be entered...";

    /** Line 254. */
    private static final String MSG_TYPE_CD_EMPTY = "Type CD can NOT be empty...";

    /** Line 260. */
    private static final String MSG_CATEGORY_CD_EMPTY = "Category CD can NOT be empty...";

    /** Line 266. */
    private static final String MSG_SOURCE_EMPTY = "Source can NOT be empty...";

    /** Line 272. */
    private static final String MSG_DESCRIPTION_EMPTY = "Description can NOT be empty...";

    /** Line 278. */
    private static final String MSG_AMOUNT_EMPTY = "Amount can NOT be empty...";

    /** Line 284. */
    private static final String MSG_ORIG_DATE_EMPTY = "Orig Date can NOT be empty...";

    /** Line 290. */
    private static final String MSG_PROC_DATE_EMPTY = "Proc Date can NOT be empty...";

    /** Line 296. */
    private static final String MSG_MERCHANT_ID_EMPTY = "Merchant ID can NOT be empty...";

    /** Line 302. */
    private static final String MSG_MERCHANT_NAME_EMPTY = "Merchant Name can NOT be empty...";

    /** Line 308. */
    private static final String MSG_MERCHANT_CITY_EMPTY = "Merchant City can NOT be empty...";

    /** Line 314. */
    private static final String MSG_MERCHANT_ZIP_EMPTY = "Merchant Zip can NOT be empty...";

    /** Line 325. */
    private static final String MSG_TYPE_CD_MUST_BE_NUMERIC = "Type CD must be Numeric...";

    /** Line 331. */
    private static final String MSG_CATEGORY_CD_MUST_BE_NUMERIC = "Category CD must be Numeric...";

    /** Line 345. <strong>No trailing dots</strong>, exactly as the source writes it. */
    private static final String MSG_AMOUNT_FORMAT = "Amount should be in format -99999999.99";

    /** Line 360. <strong>No trailing dots.</strong> */
    private static final String MSG_ORIG_DATE_FORMAT = "Orig Date should be in format YYYY-MM-DD";

    /** Line 375. <strong>No trailing dots.</strong> */
    private static final String MSG_PROC_DATE_FORMAT = "Proc Date should be in format YYYY-MM-DD";

    /** Line 401. Lower-case {@code date} is deliberate and differs from the format text above. */
    private static final String MSG_ORIG_DATE_INVALID = "Orig Date - Not a valid date...";

    /** Line 421. Lower-case {@code date} is deliberate. */
    private static final String MSG_PROC_DATE_INVALID = "Proc Date - Not a valid date...";

    /** Line 432. */
    private static final String MSG_MERCHANT_ID_MUST_BE_NUMERIC = "Merchant ID must be Numeric...";

    /** Line 593. */
    private static final String MSG_ACCOUNT_ID_NOT_FOUND = "Account ID NOT found...";

    /** Line 600. */
    private static final String MSG_XREF_AIX_LOOKUP_FAILED =
            "Unable to lookup Acct in XREF AIX file...";

    /** Line 626. */
    private static final String MSG_CARD_NUMBER_NOT_FOUND = "Card Number NOT found...";

    /** Line 633. The hash and the space before it are part of the literal. */
    private static final String MSG_XREF_LOOKUP_FAILED = "Unable to lookup Card # in XREF file...";

    /** Line 657. */
    private static final String MSG_TRANSACTION_ID_NOT_FOUND = "Transaction ID NOT found...";

    /** Lines 664 and 693. The same text is written by two paragraphs. */
    private static final String MSG_TRANSACTION_LOOKUP_FAILED = "Unable to lookup Transaction...";

    /** Line 738. The legacy spelling {@code exist} is contractual and is not corrected. */
    private static final String MSG_TRAN_ID_ALREADY_EXISTS = "Tran ID already exist...";

    /** Line 745. */
    private static final String MSG_UNABLE_TO_ADD = "Unable to Add Transaction...";

    /**
     * Line 728, the head of the success text. <strong>The trailing space is part of the literal</strong>
     * &mdash; the source appends it with {@code DELIMITED BY SIZE}, which transfers the whole
     * fixed-width literal including that space.
     */
    private static final String FRAGMENT_ADDED_SUCCESSFULLY = "Transaction added successfully. ";

    /**
     * Line 730, appended next. <strong>Both the leading and the trailing space are part of the
     * literal</strong>, again because the source appends it {@code DELIMITED BY SIZE}.
     */
    private static final String FRAGMENT_YOUR_TRAN_ID_IS = " Your Tran ID is ";

    /** Line 732, the final full stop, appended {@code DELIMITED BY SIZE}. */
    private static final String FRAGMENT_FULL_STOP = ".";

    // ==========================================================================================
    // The two four-character acceptance-test literals, lines 397 and 400 (and identically 417, 420)
    // ==========================================================================================

    /**
     * The severity that accepts outright, compared as four characters at lines 397 and 417. Never
     * parsed: the source compares text and so does this.
     */
    private static final String ACCEPTED_SEVERITY_CODE = "0000";

    /**
     * The message number that accepts <em>despite</em> a non-zero severity, compared as four
     * characters at lines 400 and 420. This is the second level of the acceptance test and the reason
     * it cannot be collapsed into one boolean.
     */
    private static final String TOLERATED_MESSAGE_NUMBER = "2513";

    // ==========================================================================================
    // Screen field identifiers, from app/cpy-bms/COTRN02.CPY. Each is the target of a MOVE -1 to
    // that field's length item, which is how the legacy positions the cursor.
    // ==========================================================================================

    /** {@code ACTIDIN}, the cursor target at lines 123, 201, 228, 595, 659, 666, 695, 740, 747, 764. */
    private static final String FIELD_ACCOUNT_ID = "ACTIDIN";

    /** {@code CARDNIN}, the cursor target at lines 215, 628 and 635. */
    private static final String FIELD_CARD_NUMBER = "CARDNIN";

    /** {@code TTYPCD}, the cursor target at lines 256 and 327. */
    private static final String FIELD_TYPE_CD = "TTYPCD";

    /** {@code TCATCD}, the cursor target at lines 262 and 333. */
    private static final String FIELD_CATEGORY_CD = "TCATCD";

    /** {@code TRNSRC}, the cursor target at line 268. */
    private static final String FIELD_SOURCE = "TRNSRC";

    /** {@code TDESC}, the cursor target at line 274. */
    private static final String FIELD_DESCRIPTION = "TDESC";

    /** {@code TRNAMT}, the cursor target at lines 280 and 347. */
    private static final String FIELD_AMOUNT = "TRNAMT";

    /** {@code TORIGDT}, the cursor target at lines 286, 362 and 404. */
    private static final String FIELD_ORIG_DATE = "TORIGDT";

    /** {@code TPROCDT}, the cursor target at lines 292, 377 and 424. */
    private static final String FIELD_PROC_DATE = "TPROCDT";

    /** {@code MID}, the cursor target at lines 298 and 434. */
    private static final String FIELD_MERCHANT_ID = "MID";

    /** {@code MNAME}, the cursor target at line 304. */
    private static final String FIELD_MERCHANT_NAME = "MNAME";

    /** {@code MCITY}, the cursor target at line 310. */
    private static final String FIELD_MERCHANT_CITY = "MCITY";

    /** {@code MZIP}, the cursor target at line 316. */
    private static final String FIELD_MERCHANT_ZIP = "MZIP";

    /** {@code CONFIRM}, the cursor target at lines 180 and 186. */
    private static final String FIELD_CONFIRM = "CONFIRM";

    // ==========================================================================================
    // Property names a consumer binds to, one per screen field the cascade can fault
    // ==========================================================================================

    private static final String PROPERTY_ACCOUNT_ID = "accountId";

    private static final String PROPERTY_CARD_NUMBER = "cardNumber";

    private static final String PROPERTY_TYPE_CD = "typeCd";

    private static final String PROPERTY_CATEGORY_CD = "categoryCd";

    private static final String PROPERTY_SOURCE = "source";

    private static final String PROPERTY_DESCRIPTION = "description";

    private static final String PROPERTY_AMOUNT = "amount";

    private static final String PROPERTY_ORIG_DATE = "origDate";

    private static final String PROPERTY_PROC_DATE = "procDate";

    private static final String PROPERTY_MERCHANT_ID = "merchantId";

    private static final String PROPERTY_MERCHANT_NAME = "merchantName";

    private static final String PROPERTY_MERCHANT_CITY = "merchantCity";

    private static final String PROPERTY_MERCHANT_ZIP = "merchantZip";

    private static final String PROPERTY_CONFIRM = "confirm";

    private static final String PROPERTY_TRAN_ID = "tranId";

    // ==========================================================================================
    // Screen field widths, from app/cpy-bms/COTRN02.CPY. Every value is bounded to its declared
    // width by the receive paragraph, so every later comparison works on a fixed-width value.
    // ==========================================================================================

    /** {@code ACTIDINI PIC X(11)}. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code CARDNINI PIC X(16)}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code TTYPCDI PIC X(2)}. */
    private static final int TYPE_CD_WIDTH = 2;

    /** {@code TCATCDI PIC X(4)}. */
    private static final int CATEGORY_CD_WIDTH = 4;

    /** {@code TRNSRCI PIC X(10)}. */
    private static final int SOURCE_WIDTH = 10;

    /** {@code TDESCI PIC X(60)}. */
    private static final int DESCRIPTION_WIDTH = 60;

    /** {@code TRNAMTI PIC X(12)}: a sign, eight digits, a point and two digits. */
    private static final int AMOUNT_WIDTH = 12;

    /** {@code TORIGDTI} and {@code TPROCDTI}, both {@code PIC X(10)}. */
    private static final int SCREEN_DATE_WIDTH = 10;

    /** {@code MIDI PIC X(9)}. */
    private static final int MERCHANT_ID_WIDTH = 9;

    /** {@code MNAMEI PIC X(30)}. */
    private static final int MERCHANT_NAME_WIDTH = 30;

    /** {@code MCITYI PIC X(25)}. */
    private static final int MERCHANT_CITY_WIDTH = 25;

    /** {@code MZIPI PIC X(10)}. */
    private static final int MERCHANT_ZIP_WIDTH = 10;

    /** {@code CONFIRMI PIC X(1)}. */
    private static final int CONFIRM_WIDTH = 1;

    /** {@code ERRMSGO PIC X(78)}: the move at line 520 truncates the 80-character work field into it. */
    private static final int ERROR_MESSAGE_WIDTH = 78;

    /** Width of the two-digit header parts the header paragraph assembles. */
    private static final int HEADER_PART_WIDTH = 2;

    /** Width of the four-digit year the header paragraph reads. */
    private static final int YEAR_WIDTH = 4;

    /** Zero-based offset of the two-digit year within the four-character year, {@code year(3:2)}. */
    private static final int YEAR_SHORT_FORM_OFFSET = 2;

    // ==========================================================================================
    // Record field widths, from app/cpy/CVTRA05Y.cpy through the transaction entity
    // ==========================================================================================

    /** {@code TRAN-ID PIC X(16)}, the business key and the field the browse addresses. */
    private static final int TRAN_ID_WIDTH = 16;

    /** {@code TRAN-DESC PIC X(100)}: the 60-character screen field space fills into it. */
    private static final int TRAN_DESC_WIDTH = 100;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    private static final int TRAN_MERCHANT_NAME_WIDTH = 50;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    private static final int TRAN_MERCHANT_CITY_WIDTH = 50;

    /** {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}, both {@code PIC X(26)}. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** Integer digit count of the edited amount field {@code WS-TRAN-AMT-E PIC +99999999.99} at line 59. */
    private static final int EDITED_AMOUNT_INTEGER_DIGITS = 8;

    /** Fractional digit count of the edited amount field. */
    private static final int EDITED_AMOUNT_FRACTION_DIGITS = 2;

    // ==========================================================================================
    // Amount-format positions, from the reference modifications at lines 340 to 343.
    // Declared zero-based; the source writes them one-based.
    // ==========================================================================================

    /** {@code TRNAMTI(1:1)}, the sign position tested at line 340. */
    private static final int AMOUNT_SIGN_OFFSET = 0;

    /** {@code TRNAMTI(2:8)}, the integer digits tested at line 341. */
    private static final int AMOUNT_INTEGER_OFFSET = 1;

    /** {@code TRNAMTI(10:1)}, the decimal point tested at line 342. */
    private static final int AMOUNT_POINT_OFFSET = 9;

    /** {@code TRNAMTI(11:2)}, the fractional digits tested at line 343. */
    private static final int AMOUNT_FRACTION_OFFSET = 10;

    // ==========================================================================================
    // Date-format positions, from the reference modifications at lines 354 to 358 and 369 to 373
    // ==========================================================================================

    /** {@code (1:4)}, the year. */
    private static final int DATE_YEAR_OFFSET = 0;

    /** {@code (5:1)}, the first separator. */
    private static final int DATE_FIRST_SEPARATOR_OFFSET = 4;

    /** {@code (6:2)}, the month. */
    private static final int DATE_MONTH_OFFSET = 5;

    /** {@code (8:1)}, the second separator. */
    private static final int DATE_SECOND_SEPARATOR_OFFSET = 7;

    /** {@code (9:2)}, the day. */
    private static final int DATE_DAY_OFFSET = 8;

    /** Width of the month and day parts of a ten-character date. */
    private static final int MONTH_DAY_WIDTH = 2;

    // ==========================================================================================
    // Character and text constants
    // ==========================================================================================

    /** The space character, the fill of every alphanumeric screen field. */
    private static final char SPACE = ' ';

    /**
     * The low-value character. A 3270 field the terminal did not transmit arrives as low values rather
     * than as spaces, and the source tests for both in the same breath at lines 124, 196, 210, 252 and
     * its ten peers, and at 175 and 176.
     */
    private static final char LOW_VALUE = '\0';

    /** The plus sign accepted in the amount's sign position at line 340. */
    private static final char AMOUNT_SIGN_PLUS = '+';

    /** The minus sign accepted in the amount's sign position at line 340. */
    private static final char AMOUNT_SIGN_MINUS = '-';

    /** The decimal point required at the amount's tenth position, line 342. */
    private static final char DECIMAL_POINT = '.';

    /** The hyphen required at both separator positions of a ten-character date. */
    private static final char DATE_SEPARATOR = '-';

    /** The separator of the header date, {@code FILLER VALUE '/'} in {@code app/cpy/CSDAT01Y.cpy}. */
    private static final String HEADER_DATE_SEPARATOR = "/";

    /** The separator of the header time, {@code FILLER VALUE ':'} in {@code app/cpy/CSDAT01Y.cpy}. */
    private static final String HEADER_TIME_SEPARATOR = ":";

    /**
     * The literal filler at position 11 of the online timestamp group, {@code FILLER PIC X(01) VALUE
     * ' '} at {@code app/cpy/CSDAT01Y.cpy} line 48. It is a <strong>space</strong>, which is what
     * distinguishes the online form from the batch form.
     */
    private static final String TIMESTAMP_DATE_TIME_SEPARATOR = " ";

    /**
     * The literal filler at position 20 of the online timestamp group, {@code FILLER PIC X(01) VALUE
     * '.'} at {@code app/cpy/CSDAT01Y.cpy} line 54.
     */
    private static final String TIMESTAMP_FRACTION_SEPARATOR = ".";

    /**
     * The six-digit fraction of the online timestamp, {@code WS-TIMESTAMP-TM-MS6 PIC 9(06)} at
     * {@code app/cpy/CSDAT01Y.cpy} line 55. <strong>Always six zeros</strong>: the canonical
     * construction at {@code app/cbl/COBIL00C.cbl} line 266 moves zeros into it after the time is
     * placed, so no sub-second value from the clock ever reaches the rendered form.
     */
    private static final String TIMESTAMP_ZERO_FRACTION = "000000";

    /** Accepted affirmative confirmation, upper case, matched at line 170. */
    private static final String CONFIRM_YES_UPPER = "Y";

    /** Accepted affirmative confirmation, lower case, matched at line 171. */
    private static final String CONFIRM_YES_LOWER = "y";

    /** Accepted negative confirmation, upper case, matched at line 173. */
    private static final String CONFIRM_NO_UPPER = "N";

    /** Accepted negative confirmation, lower case, matched at line 174. */
    private static final String CONFIRM_NO_LOWER = "n";

    /** The authored form of a message field holding nothing. */
    private static final String NO_MESSAGE = "";

    /** The value moved into the key at line 689 when the backward read reports end of file. */
    private static final long EMPTY_FILE_SEED = 0L;

    /** The increment applied at line 449. */
    private static final long IDENTIFIER_INCREMENT = 1L;

    /**
     * How many times the allocate-and-write span of lines 444 to 466 is performed before the duplicate
     * arm reports: two, so the highest key is re-read under the allocation lock exactly once more.
     *
     * <p><strong>The bound belongs to this service and not to the repository</strong>, which states the
     * obligation and explains why only the holder of the transactional boundary can decide how many
     * attempts are reasonable - see {@link TransactionRepository#lockIdentifierAllocation(long)}. Two is
     * what that contract asks for: <em>re-read the maximum under the lock and try once more</em>.
     *
     * <p><strong>A retry is needed at all only against a writer that reached the transaction master
     * without taking the allocation lock</strong> - a bulk load, a migration script, or a future caller
     * that forgot. Two allocators that both take the lock are serialised by it and cannot collide, so
     * between them the first attempt always succeeds and this bound is never consumed.
     */
    private static final int IDENTIFIER_ALLOCATION_ATTEMPTS = 2;

    /** The record type named in a not-found diagnostic. */
    private static final String CROSS_REFERENCE_RECORD = "CardCrossReference";

    /** Which part of the lock-read-insert unit was active when persistence failed. */
    private enum PersistenceStage {
        ALLOCATION,
        INSERT
    }

    // ==========================================================================================
    // Collaborators, all constructor injected
    // ==========================================================================================

    /** The shared date-validation subprogram, invoked at lines 393 and 413. */
    private final DateValidationService dateValidationService;

    /** The common-message catalogue supplying the invalid-key text and the two screen titles. */
    private final MessageCatalogService messageCatalogService;

    /** The navigation rules replacing the transfer-control dispatch at lines 508 to 511. */
    private final NavigationService navigationService;

    /**
     * The transaction master, replacing the browse at lines 642 to 706 and the write at lines 711 to
     * 721. Only the inherited operations are used: the maximum-identifier query belongs to the
     * bill-payment path and is deliberately not called here.
     */
    private final TransactionRepository transactionRepository;

    /**
     * The card cross reference, replacing both reads: the alternate-index path at lines 576 to 604 and
     * the base cluster at lines 609 to 637.
     */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /** Owns the lock-read-insert unit so failures return after rollback. */
    private final OnlineTransactionBoundary transactionBoundary;

    /** The clock standing in for {@code FUNCTION CURRENT-DATE} at line 554. */
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param dateValidationService        the subprogram invoked for both supplied dates; mandatory
     * @param messageCatalogService        the common-message catalogue; mandatory
     * @param navigationService            the navigation rules; mandatory
     * @param transactionRepository        the transaction master; mandatory
     * @param cardCrossReferenceRepository the card cross reference; mandatory
     * @param transactionBoundary          the independent identifier-allocation and insert unit
     * @param clock                        the clock the screen header reads; mandatory
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public TransactionAddService(final DateValidationService dateValidationService,
            final MessageCatalogService messageCatalogService,
            final NavigationService navigationService,
            final TransactionRepository transactionRepository,
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final OnlineTransactionBoundary transactionBoundary,
            final Clock clock) {
        this.dateValidationService =
                Objects.requireNonNull(dateValidationService, "dateValidationService must not be null");
        this.messageCatalogService =
                Objects.requireNonNull(messageCatalogService, "messageCatalogService must not be null");
        this.navigationService =
                Objects.requireNonNull(navigationService, "navigationService must not be null");
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary,
                "transactionBoundary must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ==========================================================================================
    // The screen contract, as values
    // ==========================================================================================

    /**
     * One inbound turn of the transaction-add screen: the fourteen transmitted fields of the input map
     * {@code COTRN2AI}, the attention key that arrived, the pre-selected transaction the calling screen
     * may have handed over, and the navigation state echoed by the client in place of the legacy
     * communication area.
     *
     * <p>Every field is carried verbatim and every field may be {@code null}, because a 3270 field the
     * terminal did not transmit arrives as low values rather than as spaces and an omitted JSON
     * component is the same state. Bounding each value to its declared screen width is the receive
     * paragraph's job, not the caller's.
     *
     * <p>The attention key arrives already decoded. It is <strong>not</strong> translated by the
     * module's key translator, because this member does not include the attention-key copybook: the
     * source evaluates the raw terminal identifier directly against four named values and a catch-all,
     * and that is what the dispatch reproduces. A {@code null} key reaches the catch-all, exactly as an
     * identifier the source does not name does.
     *
     * @param accountId          {@code ACTIDINI}, the account key tested first at line 196
     * @param cardNumber         {@code CARDNINI}, the card key tested second at line 210
     * @param typeCd             {@code TTYPCDI}, the transaction type code
     * @param categoryCd         {@code TCATCDI}, the transaction category code
     * @param source             {@code TRNSRCI}, the source code, carried raw and never trimmed
     * @param description        {@code TDESCI}, the description
     * @param amount             {@code TRNAMTI}, the signed amount in the fixed twelve-character form
     * @param origDate           {@code TORIGDTI}, the origination date, validated at line 393
     * @param procDate           {@code TPROCDTI}, the processing date, validated at line 413
     * @param merchantId         {@code MIDI}, the merchant identifier
     * @param merchantName       {@code MNAMEI}, the merchant name
     * @param merchantCity       {@code MCITYI}, the merchant city
     * @param merchantZip        {@code MZIPI}, the merchant postal code
     * @param confirm            {@code CONFIRMI}, the confirmation field the gate at line 169 reads
     * @param selectedTransaction {@code CDEMO-CT02-TRN-SELECTED} at line 80, the transaction the
     *                            calling screen pre-selected; when present on a first entry it is
     *                            moved into the card-number field at lines 126 and 127 and the enter
     *                            key is processed immediately
     * @param keyAction          the decoded attention key, or {@code null} when none was decoded
     * @param navigationContext  the echoed navigation state, or {@code null} for a turn carrying none
     */
    public record TransactionAddScreenInput(String accountId,
                                            String cardNumber,
                                            String typeCd,
                                            String categoryCd,
                                            String source,
                                            String description,
                                            String amount,
                                            String origDate,
                                            String procDate,
                                            String merchantId,
                                            String merchantName,
                                            String merchantCity,
                                            String merchantZip,
                                            String confirm,
                                            String selectedTransaction,
                                            KeyAction keyAction,
                                            ScreenNavigationState navigationContext) {
    }

    /**
     * The screen header the header paragraph populates at lines 552 to 571.
     *
     * <p>The two titles keep the catalogue's contractual widths and the outbound message field keeps
     * the {@value #ERROR_MESSAGE_WIDTH}-character width of {@code ERRMSGO}, because the move at line
     * 520 truncates the eighty-character work field into it. Nothing here is trimmed.
     *
     * @param title01           {@code CCDA-TITLE01}, moved at line 556
     * @param title02           {@code CCDA-TITLE02}, moved at line 557
     * @param transactionName   the transaction identifier, moved at line 558
     * @param programName       the program name, moved at line 559
     * @param currentDate       the header date as {@code MM/DD/YY}, assembled at lines 561 to 565
     * @param currentTime       the header time as {@code HH:MM:SS}, assembled at lines 567 to 571
     * @param screenTimestamp   the full online 26-character timestamp of the same clock reading, the
     *                          group declared at {@code app/cpy/CSDAT01Y.cpy} lines 42 to 55 and built
     *                          exactly as {@code app/cbl/COBIL00C.cbl} lines 249 to 267 build it, so
     *                          its fraction is always six zeros
     * @param errorMessage      the outbound message field, {@value #ERROR_MESSAGE_WIDTH} characters,
     *                          as moved at line 520
     * @param messageHighlighted {@code true} only where the source recolours the message field at line
     *                          727, which is the successful-insert path
     */
    public record ScreenHeader(String title01,
                               String title02,
                               String transactionName,
                               String programName,
                               String currentDate,
                               String currentTime,
                               String screenTimestamp,
                               String errorMessage,
                               boolean messageHighlighted) {
    }

    /**
     * The fourteen screen fields as they stand when the turn ends, each at its declared width.
     *
     * <p>This is what the operator sees echoed back, and it matters because three paths rewrite it: the
     * key-field validation writes the resolved account and card numbers back over the transmitted ones
     * at lines 206 to 209 and 220 to 223, the amount normalisation writes the edited form back at line
     * 386, and the reset paragraph at lines 762 to 779 blanks all fourteen.
     *
     * @param accountId    the account key, normalised to eleven zero-filled digits when it was supplied
     * @param cardNumber   the card key, normalised to sixteen zero-filled digits, or resolved from the
     *                     cross reference when the account key was the one supplied
     * @param typeCd       the transaction type code
     * @param categoryCd   the transaction category code
     * @param source       the source code, raw and never trimmed
     * @param description  the description
     * @param amount       the amount after the edited normalisation at lines 383 to 386
     * @param origDate     the origination date
     * @param procDate     the processing date
     * @param merchantId   the merchant identifier
     * @param merchantName the merchant name
     * @param merchantCity the merchant city
     * @param merchantZip  the merchant postal code
     * @param confirm      the confirmation field
     */
    public record ScreenFields(String accountId,
                               String cardNumber,
                               String typeCd,
                               String categoryCd,
                               String source,
                               String description,
                               String amount,
                               String origDate,
                               String procDate,
                               String merchantId,
                               String merchantName,
                               String merchantCity,
                               String merchantZip,
                               String confirm) {
    }

    /**
     * The record the turn wrote, projected at the widths the record layout declares.
     *
     * <p>Present only when the insert succeeded; a turn that validated nothing, was blocked by the
     * confirmation gate or failed the insert carries no projection at all. The projection is a value
     * and never the managed entity, so nothing that escapes this service can mutate a persistent row.
     *
     * @param tranId       {@code TRAN-ID}, sixteen characters with its zero fill intact
     * @param tranTypeCd   {@code TRAN-TYPE-CD}, two characters
     * @param tranCatCd    {@code TRAN-CAT-CD}, four characters, so a category of {@code 0002} stays the
     *                     four-character text and is never reduced to {@code 2}
     * @param tranSource   {@code TRAN-SOURCE}, ten characters, raw and space padded, never trimmed and
     *                     never mapped to an enumerated constant
     * @param tranDesc     {@code TRAN-DESC}, one hundred characters
     * @param tranAmt      {@code TRAN-AMT}, an exact decimal at scale two, never a floating-point type
     * @param merchantId   {@code TRAN-MERCHANT-ID}, nine characters. The property is
     *                     <strong>unprefixed</strong> on the transaction entity
     * @param merchantName {@code TRAN-MERCHANT-NAME}, fifty characters, unprefixed property
     * @param merchantCity {@code TRAN-MERCHANT-CITY}, fifty characters, unprefixed property
     * @param merchantZip  {@code TRAN-MERCHANT-ZIP}, ten characters, unprefixed property
     * @param tranCardNum  {@code TRAN-CARD-NUM}, sixteen characters
     * @param tranOrigTs   {@code TRAN-ORIG-TS}, twenty-six characters: the ten-character origination
     *                     screen field space filled, as the move at line 464 fills it
     * @param tranProcTs   {@code TRAN-PROC-TS}, twenty-six characters: the ten-character processing
     *                     screen field space filled, as the move at line 465 fills it. A
     *                     <em>separate</em> value from the origination timestamp, because this member
     *                     takes two independent screen fields where the bill-payment program takes one
     *                     clock reading
     */
    public record TransactionProjection(String tranId,
                                        String tranTypeCd,
                                        String tranCatCd,
                                        String tranSource,
                                        String tranDesc,
                                        BigDecimal tranAmt,
                                        String merchantId,
                                        String merchantName,
                                        String merchantCity,
                                        String merchantZip,
                                        String tranCardNum,
                                        String tranOrigTs,
                                        String tranProcTs) {
    }

    /**
     * The outcome of one turn of the transaction-add screen.
     *
     * <p>This is a value, not an HTTP response: it carries no status code and no response entity,
     * because the legacy program's outcome is a screen plus a communication area and the mapping onto a
     * transport belongs to the controller.
     *
     * @param route                the destination the turn leads to. This screen's own destination
     *                             whenever the turn re-presents the screen, since the return at lines
     *                             156 to 159 and again at 530 to 534 re-arms this same transaction; the
     *                             resolved destination on the two transfer paths at lines 117 and 143.
     *                             Never {@code null}
     * @param navigationContext    the navigation state the turn hands back, standing in for the
     *                             communication area the return and the transfer both carry. Never
     *                             {@code null}
     * @param reArmedTransactionId the transaction identifier the turn re-armed, from
     *                             {@code EXEC CICS RETURN TRANSID}; empty on the transfer path, which
     *                             transfers control instead of returning. Never {@code null}
     * @param transaction          the record the turn wrote, or {@code null} when nothing was written
     * @param message              the summary message, byte exact and authored rather than padded. When
     *                             more than one check could have failed this is the <em>first</em>
     *                             failure's text, which is the text the legacy screen showed. Empty
     *                             when there is nothing to say. Never {@code null}
     * @param focusField           the screen field the cursor is positioned on, from the corresponding
     *                             {@code MOVE -1} to that field's length item. Never {@code null}
     * @param errorFlag            the state of {@code WS-ERR-FLG}. The explicit flag rather than an
     *                             inference from the message, because a path can raise the flag and a
     *                             path can set a message without the other
     * @param reEnter              the state of {@code CDEMO-PGM-REENTER} as the turn leaves it. The
     *                             gate is what tells a consumer whether per-field detail is populated
     *                             at all, because the source can only produce it on a re-submission
     * @param fieldErrors          one entry per field the turn faulted, in the order the source checks
     *                             them, distinguishing a field that was not supplied from one supplied
     *                             wrongly. Unmodifiable and never {@code null}
     * @param header               the screen header, as the header paragraph populated it
     * @param screen               the fourteen screen fields as the turn leaves them
     */
    public record TransactionAddResult(NavigationService.Route route,
                                       ScreenNavigationState navigationContext,
                                       String reArmedTransactionId,
                                       TransactionProjection transaction,
                                       String message,
                                       String focusField,
                                       boolean errorFlag,
                                       boolean reEnter,
                                       List<ValidationException.FieldError> fieldErrors,
                                       ScreenHeader header,
                                       ScreenFields screen) {

        /**
         * Reports whether the turn wrote a transaction.
         *
         * @return {@code true} when no error was raised and a record was inserted
         */
        public boolean transactionAdded() {
            return !this.errorFlag && this.transaction != null;
        }
    }

    // ==========================================================================================
    // Entry point: the procedure division, lines 106 to 159
    // ==========================================================================================

    /**
     * Runs one turn of the transaction-add screen.
     *
     * <p>This is the procedure division: it establishes the working storage the legacy declares at
     * lines 35 to 93, runs the main paragraph, and then performs the terminal
     * the pseudo-conversational return that re-arms this transaction with the carried work area, at lines
     * 156 to 159, by re-arming the transaction. Nothing is retained between calls, so two concurrent turns
     * are wholly independent and no identifier is ever cached.
     *
     * <p>The turn remains outside a transaction until the allocating write paragraph. That paragraph
     * takes the advisory lock, reads the highest key and flushes the insert in one independent unit;
     * duplicate or write failures are mapped here only after rollback.
     *
     * @param input the transmitted screen, the decoded attention key and the echoed navigation state;
     *              must not be {@code null}
     * @return the outcome of the turn, never {@code null}
     * @throws NullPointerException if {@code input} is {@code null}
     */
    public TransactionAddResult processTransactionAdd(final TransactionAddScreenInput input) {
        Objects.requireNonNull(input, "input must not be null");

        final TurnState state = new TurnState();
        mainPara(state, input);

        // Lines 156 to 159 re-arm this transaction on return. The main paragraph's transfer path
        // has already ended the turn by transferring control, and re-arming is idempotent, so this
        // reproduces the unconditional return without overriding a transfer.
        returnToCics(state);

        LOG.debug("Transaction-add turn complete: route={} errorFlag={} added={} fieldErrors={}",
                state.route.getRouteValue(), state.errorFlag, state.written != null,
                state.fieldErrors.size());
        return state.toResult();
    }

    // ==========================================================================================
    // MAIN-PARA, line 107
    // ==========================================================================================

    /**
     * The main paragraph at lines 107 to 159.
     *
     * <p>Clears the error and user-modified flags and blanks both message fields at lines 109 to 113;
     * routes a turn carrying no navigation state to sign-on at lines 115 to 117; otherwise takes the
     * echoed state at line 119 and branches on the re-enter gate at line 120.
     *
     * <p>A <strong>first entry</strong> sets the gate, clears the outbound map, positions the cursor on
     * the account field, and then does one thing more that no sibling screen does: if the calling
     * screen pre-selected a transaction at lines 124 and 125, that value is moved into the card-number
     * field and the enter key is processed immediately, so a first entry can validate, and even insert,
     * without an operator keystroke. The send at line 130 follows either way.
     *
     * <p>A <strong>re-entry</strong> receives the screen and dispatches on the attention key. The
     * evaluation at lines 133 to 152 has exactly five arms &mdash; the enter key, the third, fourth and
     * fifth program-function keys, and a catch-all &mdash; and their order is preserved. The third key's
     * destination is resolved from the originating-program field at lines 137 to 142, whose blank
     * fallback is this screen's <em>own</em> default, the user main menu, and not sign-on. The
     * <strong>clear key is not an arm here</strong>, so it reaches the catch-all and produces the
     * invalid-key message; adding an arm the source lacks would change observable output.
     *
     * <p><strong>The re-enter gate is the decoration gate.</strong> Per-field detail can only be
     * produced from within a branch that runs the validation cascade, and the source's gate is exactly
     * the condition under which that happens on a re-submission, so the gate is structural here rather
     * than a repeated runtime test.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen and echoed navigation state
     */
    private void mainPara(final TurnState state, final TransactionAddScreenInput input) {
        // Lines 109 and 110 set ERR-FLG-OFF and USR-MODIFIED-NO, and blank both WS-MESSAGE and
        // ERRMSGO at lines 112 and 113. The state is constructed in exactly that condition. The
        // user-modified flag is set nowhere in the member and read nowhere either, so it is recorded
        // here as observed rather than modelled as a value nothing can change.
        if (isNavigationStateAbsent(input.navigationContext())) {
            // Line 115 tests the commarea length for zero, then line 116 carries 'COSGN00C' into CDEMO-TO-PROGRAM. The
            // destination is the one the navigation rules hold for a turn carrying no state, so no
            // program name is written here as a literal.
            state.context = withNominatedProgram(ScreenNavigationState.empty(),
                    navigationService.resolveAbsentContextRoute().getLegacyProgramName());
            returnToPrevScreen(state);
            return;
        }

        // Line 119 copies the passed commarea, for its transmitted length, into CARDDEMO-COMMAREA.
        state.context = input.navigationContext();

        if (state.context.firstEntry()) {
            // IF NOT CDEMO-PGM-REENTER at line 120: set the gate at line 121, clear the outbound map at
            // line 122 and position the cursor at line 123. The outbound map is assembled from this
            // state, which is blank on a first entry, so clearing it needs no separate step.
            state.context = state.context.withReEntry();
            state.focusField = FIELD_ACCOUNT_ID;

            // Lines 124 and 125 test the carried selection for neither blank nor empty: the
            // pre-selected transaction is moved into the card-number field at lines 126 and 127 and the
            // enter key is processed at line 128.
            if (isSupplied(input.selectedTransaction())) {
                state.cardNumber = moveToField(input.selectedTransaction(), CARD_NUMBER_WIDTH);
                processEnterKey(state);
                if (state.screenSent) {
                    return;
                }
            }

            sendTrnaddScreen(state);
            return;
        }

        receiveTrnaddScreen(state, input);

        // Lines 133 to 152 hold a multi-way selection on EIBAID. Clause order is preserved and the catch-all maps to the
        // default arm. A key that was never decoded reaches the same arm, because an absent key is not
        // one of the four the source names. The key is evaluated directly: this member includes no
        // attention-key copybook, so no translator is involved.
        switch (input.keyAction()) {
            case ENTER -> processEnterKey(state);
            case PFK03 -> {
                // Lines 137 to 142: the originating-program field decides, and its blank fallback is
                // this screen's own default of the user main menu. The nomination is written and the
                // resolution itself is left to the navigation rules, so no route table is declared here.
                state.context = withNominatedProgram(state.context,
                        resolveExitProgramName(state.context));
                returnToPrevScreen(state);
            }
            case PFK04 -> clearCurrentScreen(state);
            case PFK05 -> copyLastTranData(state);
            case null, default -> {
                // Lines 148 to 151. The catalogue message is carried at its full contractual width of
                // fifty characters and is deliberately not trimmed.
                state.raiseError(messageCatalogService.invalidKeyMessage(), state.focusField);
                sendTrnaddScreen(state);
            }
        }
    }

    // ==========================================================================================
    // PROCESS-ENTER-KEY, line 164
    // ==========================================================================================

    /**
     * The enter-key paragraph at lines 164 to 188.
     *
     * <p>Runs the two validation paragraphs in order at lines 166 and 167, then evaluates the
     * confirmation field at lines 169 to 188. Because every failure inside either validation paragraph
     * ends the task, a failed validation means the confirmation gate is never reached at all, which is
     * why the turn-ended state is tested between the two halves rather than the error flag.
     *
     * <p>The confirmation evaluation has three arms and their order is contractual: an affirmative
     * value in either case inserts; a negative value in either case, a blank field or an untransmitted
     * field asks for confirmation; anything else reports the value as invalid. Both of the latter two
     * position the cursor on the confirmation field and send.
     *
     * @param state the turn's working storage
     */
    private void processEnterKey(final TurnState state) {
        LOG.debug("Processing the enter key for the transaction-add screen");

        // Line 166 runs VALIDATE-INPUT-KEY-FIELDS.
        validateInputKeyFields(state);
        if (state.screenSent) {
            return;
        }

        // Line 167 runs VALIDATE-INPUT-DATA-FIELDS.
        validateInputDataFields(state);
        if (state.screenSent) {
            return;
        }

        // Lines 169 to 188 hold a multi-way selection on CONFIRMI OF COTRN2AI. The selector is a one-character screen
        // field, so the arms are compared as text and the catch-all is the default.
        final String confirm = state.confirm;
        if (CONFIRM_YES_UPPER.equals(confirm) || CONFIRM_YES_LOWER.equals(confirm)) {
            // The upper- and lower-case 'Y' arms at lines 170 to 172.
            addTransaction(state);
        } else if (CONFIRM_NO_UPPER.equals(confirm) || CONFIRM_NO_LOWER.equals(confirm)
                || isBlankField(confirm)) {
            // The 'N', 'n', blank and empty arms at lines 173 to 181. A declined
            // confirmation and an unanswered one take the same arm and produce the same prompt, which
            // is the source's own conflation and is preserved.
            faultField(state, MSG_CONFIRM_TO_ADD, PROPERTY_CONFIRM, FIELD_CONFIRM,
                    ValidationException.FieldState.MISSING);
        } else {
            // WHEN OTHER at lines 182 to 187.
            faultField(state, MSG_INVALID_CONFIRM_VALUE, PROPERTY_CONFIRM, FIELD_CONFIRM,
                    ValidationException.FieldState.INVALID);
        }
    }

    // ==========================================================================================
    // VALIDATE-INPUT-KEY-FIELDS, line 193
    // ==========================================================================================

    /**
     * The key-field validation paragraph at lines 193 to 230.
     *
     * <p>An ordered, top-down evaluation of independent conditions whose clause order is contractual:
     * the account field is considered first at line 196, the card field second at line 210, and the
     * catch-all at line 224 fires only when neither was supplied. <strong>Whichever key is supplied
     * resolves the other</strong> through the cross reference, and if both are supplied the account
     * field wins and the card field is overwritten with what the cross reference holds &mdash; a
     * consequence of the first-match-wins ordering, not of any explicit preference.
     *
     * <p>Each arm does the same four things: reject a non-numeric field, convert it, write the
     * normalised zero-filled digits back over the screen field and into the cross-reference key, and
     * read the cross reference. The numeric test precedes the conversion and ends the task on failure,
     * so the conversion only ever sees an all-digit field.
     *
     * <p>The numeric test is the legacy's: for an alphanumeric item every character position must hold
     * a digit, so a value padded with spaces is <em>not</em> numeric even though it reads as a number.
     * That is why an eleven-character field holding {@code 123} followed by spaces is rejected here.
     *
     * @param state the turn's working storage
     */
    private void validateInputKeyFields(final TurnState state) {
        if (isSupplied(state.accountId)) {
            // The arm testing the screen account field for neither blank nor empty, at line 196.
            if (!isAllDigits(state.accountId)) {
                // Lines 197 to 203.
                faultField(state, MSG_ACCOUNT_ID_NOT_NUMERIC, PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                        ValidationException.FieldState.INVALID);
                return;
            }
            // Lines 204 and 205 convert the screen account lexeme to its numeric value, and lines 206
            // and 207 carry it into XREF-ACCT-ID and back over the screen field: a store into
            // PIC 9(11) zero fills on the left.
            state.accountId = numericField(state.accountId, ACCOUNT_ID_WIDTH);
            state.xrefAccountId = state.accountId;

            readCxacaixFile(state);
            if (state.screenSent) {
                return;
            }

            // Line 209 carries XREF-CARD-NUM into CARDNINI.
            state.cardNumber = moveToField(state.xrefCardNumber, CARD_NUMBER_WIDTH);
            return;
        }

        if (isSupplied(state.cardNumber)) {
            // The arm testing the screen card field for neither blank nor empty, at line 210.
            if (!isAllDigits(state.cardNumber)) {
                // Lines 211 to 217.
                faultField(state, MSG_CARD_NUMBER_NOT_NUMERIC, PROPERTY_CARD_NUMBER, FIELD_CARD_NUMBER,
                        ValidationException.FieldState.INVALID);
                return;
            }
            // Lines 218 and 219 convert the screen card lexeme to its numeric value, and lines 220
            // and 221 carry it into XREF-CARD-NUM and back over the screen field.
            state.cardNumber = numericField(state.cardNumber, CARD_NUMBER_WIDTH);
            state.xrefCardNumber = state.cardNumber;

            readCcxrefFile(state);
            if (state.screenSent) {
                return;
            }

            // Line 223 carries XREF-ACCT-ID into ACTIDINI.
            state.accountId = moveToField(state.xrefAccountId, ACCOUNT_ID_WIDTH);
            return;
        }

        // WHEN OTHER at lines 224 to 229.
        faultField(state, MSG_KEY_FIELD_REQUIRED, PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                ValidationException.FieldState.MISSING);
    }

    // ==========================================================================================
    // VALIDATE-INPUT-DATA-FIELDS, line 235
    // ==========================================================================================

    /**
     * The data-field validation paragraph at lines 235 to 437, in the eight stages the source declares
     * and in the order it declares them. Every stage ends the task on failure, so exactly one stage can
     * report.
     *
     * <ol>
     *   <li>Lines 237 to 249 &mdash; when the error flag is already raised, blank all eleven data
     *       fields. Reachable only through the pre-selected-transaction path of the first entry, since
     *       every other way of raising the flag has already ended the task.</li>
     *   <li>Lines 251 to 320 &mdash; the eleven emptiness tests. <strong>Their order is the source's
     *       and not the screen's:</strong> the description is tested <em>before</em> the amount, which
     *       is the reverse of the screen layout, and that order is preserved because the first failure
     *       is the one the operator sees.</li>
     *   <li>Lines 322 to 337 &mdash; the type and category codes must be all digits.</li>
     *   <li>Lines 339 to 351 &mdash; the amount's four fixed positions.</li>
     *   <li>Lines 353 to 366 &mdash; the origination date's five fixed positions.</li>
     *   <li>Lines 368 to 381 &mdash; the processing date's five fixed positions.</li>
     *   <li>Lines 383 to 386 &mdash; the amount is converted and the edited form written back.</li>
     *   <li>Lines 389 to 436 &mdash; the two date-validation calls and the merchant-identifier numeric
     *       test.</li>
     * </ol>
     *
     * @param state the turn's working storage
     */
    private void validateInputDataFields(final TurnState state) {
        // Lines 237 to 249 blank the eleven data fields when the error flag is on. The account and card fields and
        // the confirmation field are deliberately left alone: this reset is narrower than the one the
        // reset paragraph performs.
        if (state.errorFlag) {
            blankDataFields(state);
        }

        editEmptyDataFields(state);
        if (state.screenSent) {
            return;
        }

        editNumericCodes(state);
        if (state.screenSent) {
            return;
        }

        editAmountFormat(state);
        if (state.screenSent) {
            return;
        }

        editOrigDateFormat(state);
        if (state.screenSent) {
            return;
        }

        editProcDateFormat(state);
        if (state.screenSent) {
            return;
        }

        normaliseAmount(state);

        editSuppliedDates(state);
        if (state.screenSent) {
            return;
        }

        // IF MIDI OF COTRN2AI IS NOT NUMERIC at lines 430 to 436.
        if (!isAllDigits(state.merchantId)) {
            faultField(state, MSG_MERCHANT_ID_MUST_BE_NUMERIC, PROPERTY_MERCHANT_ID,
                    FIELD_MERCHANT_ID, ValidationException.FieldState.INVALID);
        }
    }

    /**
     * The first evaluation of the data-field paragraph, lines 251 to 320: eleven emptiness tests in the
     * source's own order, each with its own text and its own cursor position, and a catch-all that
     * continues.
     *
     * <p>A blank field is <strong>MISSING</strong> rather than INVALID, because the field was not
     * supplied at all.
     *
     * @param state the turn's working storage
     */
    private void editEmptyDataFields(final TurnState state) {
        if (isBlankField(state.typeCd)) {
            faultField(state, MSG_TYPE_CD_EMPTY, PROPERTY_TYPE_CD, FIELD_TYPE_CD,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.categoryCd)) {
            faultField(state, MSG_CATEGORY_CD_EMPTY, PROPERTY_CATEGORY_CD, FIELD_CATEGORY_CD,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.source)) {
            faultField(state, MSG_SOURCE_EMPTY, PROPERTY_SOURCE, FIELD_SOURCE,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.description)) {
            // Line 270: the description is tested here, before the amount, which is the source's order.
            faultField(state, MSG_DESCRIPTION_EMPTY, PROPERTY_DESCRIPTION, FIELD_DESCRIPTION,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.amount)) {
            faultField(state, MSG_AMOUNT_EMPTY, PROPERTY_AMOUNT, FIELD_AMOUNT,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.origDate)) {
            faultField(state, MSG_ORIG_DATE_EMPTY, PROPERTY_ORIG_DATE, FIELD_ORIG_DATE,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.procDate)) {
            faultField(state, MSG_PROC_DATE_EMPTY, PROPERTY_PROC_DATE, FIELD_PROC_DATE,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.merchantId)) {
            faultField(state, MSG_MERCHANT_ID_EMPTY, PROPERTY_MERCHANT_ID, FIELD_MERCHANT_ID,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.merchantName)) {
            faultField(state, MSG_MERCHANT_NAME_EMPTY, PROPERTY_MERCHANT_NAME, FIELD_MERCHANT_NAME,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.merchantCity)) {
            faultField(state, MSG_MERCHANT_CITY_EMPTY, PROPERTY_MERCHANT_CITY, FIELD_MERCHANT_CITY,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.merchantZip)) {
            faultField(state, MSG_MERCHANT_ZIP_EMPTY, PROPERTY_MERCHANT_ZIP, FIELD_MERCHANT_ZIP,
                    ValidationException.FieldState.MISSING);
        }
        // WHEN OTHER CONTINUE at lines 318 and 319: nothing to do.
    }

    /**
     * The second evaluation, lines 322 to 337: the type and category codes must be all digits.
     *
     * <p>The test is applied to the whole fixed-width field, so a two-character type code holding a
     * digit followed by a space is not numeric. Both fields have already been proved non-blank by the
     * previous stage, so the only way to reach a failure here is a genuinely non-numeric value, which
     * is <strong>INVALID</strong> rather than MISSING.
     *
     * @param state the turn's working storage
     */
    private void editNumericCodes(final TurnState state) {
        if (!isAllDigits(state.typeCd)) {
            faultField(state, MSG_TYPE_CD_MUST_BE_NUMERIC, PROPERTY_TYPE_CD, FIELD_TYPE_CD,
                    ValidationException.FieldState.INVALID);
        } else if (!isAllDigits(state.categoryCd)) {
            faultField(state, MSG_CATEGORY_CD_MUST_BE_NUMERIC, PROPERTY_CATEGORY_CD,
                    FIELD_CATEGORY_CD, ValidationException.FieldState.INVALID);
        }
        // WHEN OTHER CONTINUE at lines 335 and 336.
    }

    /**
     * The third evaluation, lines 339 to 351: the amount's four fixed positions.
     *
     * <p>The four conditions are alternatives of one arm, so any of them produces the same single text.
     * The first is the abbreviated combined relation excluding both signs, which expands to "is
     * neither a minus nor a plus" &mdash; so a sign is <strong>mandatory</strong> and an unsigned amount
     * is rejected. The remaining three require eight digits at positions 2 to 9, a decimal point at
     * position 10, and two digits at positions 11 and 12.
     *
     * @param state the turn's working storage
     */
    private void editAmountFormat(final TurnState state) {
        final String amount = state.amount;
        final char sign = amount.charAt(AMOUNT_SIGN_OFFSET);
        final boolean signWrong = sign != AMOUNT_SIGN_MINUS && sign != AMOUNT_SIGN_PLUS;
        final boolean integerPartWrong = !isAllDigits(
                amount.substring(AMOUNT_INTEGER_OFFSET, AMOUNT_INTEGER_OFFSET + EDITED_AMOUNT_INTEGER_DIGITS));
        final boolean pointWrong = amount.charAt(AMOUNT_POINT_OFFSET) != DECIMAL_POINT;
        final boolean fractionWrong = !isAllDigits(
                amount.substring(AMOUNT_FRACTION_OFFSET,
                        AMOUNT_FRACTION_OFFSET + EDITED_AMOUNT_FRACTION_DIGITS));

        if (signWrong || integerPartWrong || pointWrong || fractionWrong) {
            faultField(state, MSG_AMOUNT_FORMAT, PROPERTY_AMOUNT, FIELD_AMOUNT,
                    ValidationException.FieldState.INVALID);
        }
        // WHEN OTHER CONTINUE at lines 349 and 350.
    }

    /**
     * The fourth evaluation, lines 353 to 366: the origination date's five fixed positions, being four
     * digits, a hyphen, two digits, a hyphen and two digits.
     *
     * @param state the turn's working storage
     */
    private void editOrigDateFormat(final TurnState state) {
        if (!isTenCharacterDateShape(state.origDate)) {
            faultField(state, MSG_ORIG_DATE_FORMAT, PROPERTY_ORIG_DATE, FIELD_ORIG_DATE,
                    ValidationException.FieldState.INVALID);
        }
        // WHEN OTHER CONTINUE at lines 364 and 365.
    }

    /**
     * The fifth evaluation, lines 368 to 381: the processing date's five fixed positions, tested
     * identically to the origination date's but reported with its own text and its own cursor position.
     *
     * @param state the turn's working storage
     */
    private void editProcDateFormat(final TurnState state) {
        if (!isTenCharacterDateShape(state.procDate)) {
            faultField(state, MSG_PROC_DATE_FORMAT, PROPERTY_PROC_DATE, FIELD_PROC_DATE,
                    ValidationException.FieldState.INVALID);
        }
        // WHEN OTHER CONTINUE at lines 379 and 380.
    }

    /**
     * The amount normalisation at lines 383 to 386: convert the screen lexeme, store it into the
     * nine-integer-digit two-decimal work field, move that into the edited field
     * {@code PIC +99999999.99} and move the edited field straight back over the screen field.
     *
     * <p>Three consequences of the legacy moves are reproduced rather than tidied away. The edited
     * picture <strong>always emits a sign</strong>, so a positive amount comes back with a leading plus
     * even if the operator keyed a minus-free value &mdash; which the format test above has already made
     * impossible, but the picture is what guarantees it. The edited picture has <strong>eight integer
     * digits while the work field has nine</strong>, so a value of a hundred million or more loses its
     * leading digit on the way back to the screen; that is a store into a narrower display field and it
     * truncates. And the conversion itself truncates surplus fractional digits, because the estate
     * carries no rounding directive anywhere; the module's single decimal authority applies that
     * truncation and nothing here rescales.
     *
     * <p>No arithmetic is performed and no expression is rearranged: the source converts and stores, and
     * so does this.
     *
     * @param state the turn's working storage
     */
    private void normaliseAmount(final TurnState state) {
        final BigDecimal converted = numericLexemeValue(state.amount);
        if (converted == null) {
            // The conversion of an argument that is not a well-formed numeric lexeme is undefined in the
            // language, so the field is left exactly as transmitted. Unreachable from here because the
            // format test above has already proved the shape, and kept because leaving the field alone
            // is the only reading under which no value is invented.
            return;
        }
        state.amount = editedAmountField(converted);
    }

    /**
     * The two date-validation call sites, lines 389 to 427: the only two genuine invocations of the
     * date-validation subprogram in this member, and two of exactly four in the whole estate.
     *
     * <p>Each site moves the candidate into the ten-character first parameter, moves the format literal
     * {@code YYYY-MM-DD} from the work field at line 60 into the second, blanks the eighty-character
     * result block, and calls. Both are delegated; the mask is the typed form of that same literal.
     *
     * <p>The second call is guarded by the turn-ended state because the source guards it structurally:
     * the first site's failure path ends the task at line 405, so a rejected origination date means the
     * processing date is never offered to the subprogram at all. Guarding it is what keeps the number of
     * invocations, and the number of reported failures, the same as the legacy's.
     *
     * @param state the turn's working storage
     */
    private void editSuppliedDates(final TurnState state) {
        // Lines 389 to 407, the call itself at line 393.
        if (!isDateAccepted(dateValidationService.validateDate(state.origDate, DateFormat.YYYY_MM_DD))) {
            faultField(state, MSG_ORIG_DATE_INVALID, PROPERTY_ORIG_DATE, FIELD_ORIG_DATE,
                    ValidationException.FieldState.INVALID);
            return;
        }

        // Lines 409 to 427, the call itself at line 413.
        if (!isDateAccepted(dateValidationService.validateDate(state.procDate, DateFormat.YYYY_MM_DD))) {
            faultField(state, MSG_PROC_DATE_INVALID, PROPERTY_PROC_DATE, FIELD_PROC_DATE,
                    ValidationException.FieldState.INVALID);
        }
    }

    /**
     * The two-level acceptance test the source applies to the result block at lines 397 to 407 and
     * identically at lines 417 to 427.
     *
     * <p><strong>Both levels are load bearing and neither may be dropped.</strong> The accepted severity
     * passes outright. Otherwise a message number that is <em>not</em> the tolerated one is rejected.
     * Otherwise &mdash; a non-zero severity carrying the tolerated message number &mdash; the date is
     * accepted <em>silently</em>, exactly as the legacy accepts it: the source's inner test is
     * the tolerated number, so it falls straight through the empty else with no
     * message, no flag and no cursor move.
     *
     * <p>Both comparisons are four-character text comparisons. Neither field is parsed into a number,
     * because the source compares the character fields and a numeric reading would accept forms the
     * character comparison rejects. Collapsing the two levels into a single boolean, or treating every
     * non-zero severity as a failure, would reject dates the legacy admits.
     *
     * @param result the typed form of the eighty-character result block the subprogram returned
     * @return {@code true} when the date is accepted by either route
     */
    private static boolean isDateAccepted(final DateValidationService.SubprogramResult result) {
        // Level one, lines 397 and 417: a severity code of '0000' is accepted and nothing further is done.
        if (ACCEPTED_SEVERITY_CODE.equals(result.severityCode())) {
            return true;
        }
        // Level two, lines 400 and 420: a message number other than '2513' is reported as an error.
        if (!TOLERATED_MESSAGE_NUMBER.equals(result.messageNumber())) {
            return false;
        }
        LOG.debug("Accepting a transaction date on the tolerated message number [{}] despite severity"
                + " [{}]", result.messageNumber(), result.severityCode());
        return true;
    }

    // ==========================================================================================
    // ADD-TRANSACTION, line 442
    // ==========================================================================================

    /**
     * The insert paragraph at lines 442 to 466, in the order the source performs it.
     *
     * <p><strong>Identifier generation, lines 444 to 449.</strong> High values are moved into the record
     * key, the browse is started, one record is read backward, the browse is ended, what was found is
     * moved into a sixteen-digit numeric work field and one is added. No sequence, no generated value and
     * no random identifier is involved. The backward read's end-of-file arm moves zeros into the key, so
     * an empty table seeds zero and the first identifier is {@code 0000000000000001}. The
     * transaction-scoped advisory lock is taken before the browse and remains held through the flushed
     * insert, preventing another allocator from reading the same highest key.
     *
     * <p><strong>The allocation lock is taken here, before the browse, and the span is
     * bounded-retried.</strong> The legacy region held its browse position across the read, the increment
     * and the write, and a relational store expresses that hold as {@link
     * TransactionRepository#lockIdentifierAllocation(long)}: transaction-scoped, so it covers the whole
     * turn, and taken once because it is re-entrant. Lines 444 to 466 are then performed up to {@link
     * #IDENTIFIER_ALLOCATION_ATTEMPTS} times, so an identifier taken by a writer that reached the table
     * <em>without</em> the lock is re-minted from a re-read highest key rather than refused. The
     * copy-last-transaction path takes no lock, because it mints nothing.
     *
     * <p><strong>Field assignment, lines 450 to 465, in the source's order and no other.</strong> The
     * record is initialised, then the key, type code, category code, source, description, amount, card
     * number, merchant identifier, name, city and postal code, and finally the origination and
     * processing timestamps are assigned. The amount is converted a <em>second</em> time at line 456,
     * from the screen field the normalisation already rewrote, and that second conversion is reproduced
     * rather than replaced by reuse of the first: the field it reads is the edited form, so the two
     * conversions are of different text and only the second is what the record receives.
     *
     * <p>The two timestamps take the two <em>separate</em> ten-character screen fields, space filled into
     * their twenty-six-character record fields. This is where this member differs from the bill-payment
     * program, which assigns one clock value to both; here the operator supplies both independently, so
     * no clock value is substituted.
     *
     * <p>Then the write, at line 466.
     *
     * @param state the turn's working storage
     */
    private void addTransaction(final TurnState state) {
        // The write paragraph owns the proxied transaction boundary so the advisory lock, the
        // backward maximum-key read and the insert cannot be split across transactions.
        writeTransactFile(state);
    }

    /**
     * Performs the source-ordered identifier allocation and record assembly inside the write
     * paragraph's transaction.
     *
     * @param state the turn's working storage
     * @return the record to insert, or {@code null} only on the defensive invalid-amount arm
     */
    private Transaction allocateTransactionRecord(final TurnState state) {
        // Line 444 carries HIGH-VALUES into TRAN-ID, then the three browse paragraphs at lines 445 to 447.
        state.browseCursor = highestTransactionWindow();
        readprevTransactFile(state);
        endbrTransactFile(state);

        // Line 448 carries TRAN-ID into WS-TRAN-ID-N and adds 1 to WS-TRAN-ID-N at line 449. The work field
        // is PIC 9(16), so the store back at line 451 zero fills on the left and, on overflow, keeps the
        // low-order sixteen digits exactly as an unrounded store into a fixed-width numeric field does.
        final long nextIdentifier = state.browsedKeyValue + IDENTIFIER_INCREMENT;

        // Line 450 clears TRAN-RECORD, then the twelve assignments at lines 451 to 465 in order.
        final String tranId = CobolStringUtils.rightJustifyZeroFill(Long.toString(nextIdentifier),
                TRAN_ID_WIDTH);
        final String tranTypeCd = moveToField(state.typeCd, TYPE_CD_WIDTH);
        final String tranCatCd = moveToField(state.categoryCd, CATEGORY_CD_WIDTH);
        final String tranSource = moveToField(state.source, SOURCE_WIDTH);
        final String tranDesc = moveToField(state.description, TRAN_DESC_WIDTH);

        // Lines 456 and 457 convert the edited screen amount to its numeric value, and line 458
        // carries it into TRAN-AMT. The lexeme is the edited form the normalisation wrote
        // back, and it converts because the format test proved its shape.
        final BigDecimal tranAmt = numericLexemeValue(state.amount);
        if (tranAmt == null) {
            // Defensive: the format test at lines 339 to 351 has already proved the shape, so this arm
            // is unreachable. It raises the operator-visible failure the source's own catch-all raises
            // rather than writing a record with no amount.
            faultField(state, MSG_UNABLE_TO_ADD, PROPERTY_AMOUNT, FIELD_ACCOUNT_ID,
                    ValidationException.FieldState.INVALID);
            return null;
        }

        final String tranCardNum = moveToField(state.cardNumber, CARD_NUMBER_WIDTH);
        final String merchantId = moveToField(state.merchantId, MERCHANT_ID_WIDTH);
        final String merchantName = moveToField(state.merchantName, TRAN_MERCHANT_NAME_WIDTH);
        final String merchantCity = moveToField(state.merchantCity, TRAN_MERCHANT_CITY_WIDTH);
        final String merchantZip = moveToField(state.merchantZip, MERCHANT_ZIP_WIDTH);

        // Line 464 carries TORIGDTI into TRAN-ORIG-TS and carries TPROCDTI into TRAN-PROC-TS at line 465: two
        // distinct ten-character senders, each left justified and space filled into twenty-six positions.
        final String tranOrigTs = moveToField(state.origDate, TIMESTAMP_WIDTH);
        final String tranProcTs = moveToField(state.procDate, TIMESTAMP_WIDTH);

        state.pending = new Transaction(tranId,
                tranTypeCd,
                tranCatCd,
                tranSource,
                tranDesc,
                tranAmt,
                merchantId,
                merchantName,
                merchantCity,
                merchantZip,
                tranCardNum,
                tranOrigTs,
                tranProcTs);
        return state.pending;
    }

    // ==========================================================================================
    // COPY-LAST-TRAN-DATA, line 471
    // ==========================================================================================

    /**
     * The copy-last-transaction paragraph at lines 471 to 495, reached from the fifth program-function
     * key.
     *
     * <p>It validates the key fields at line 473, browses backward to the highest-keyed record at lines
     * 475 to 478, and then &mdash; guarded by {@code IF NOT ERR-FLG-ON} at line 480 &mdash; copies eleven
     * fields of that record onto the screen at lines 481 to 492, before performing the enter-key
     * paragraph at line 495. That last step runs the key-field validation a <strong>second</strong> time;
     * the duplication is the source's and is reproduced rather than optimised away.
     *
     * <p>The two timestamp copies are <strong>truncating</strong> moves: a twenty-six-character record
     * field into a ten-character screen field keeps the leftmost ten characters, which is exactly the
     * date part. The amount copy goes through the edited picture, so it arrives on screen signed and
     * zero filled to eight integer digits.
     *
     * <p>Because the browse's end-of-file arm raises no error, an empty table reaches the copy with no
     * record at all. The source would copy whatever the uninitialised record area held; here there is
     * nothing to copy, so the screen fields are left as transmitted and the enter-key paragraph runs on
     * them. That is the only defensible reading of an uninitialised record area and it invents no value.
     *
     * @param state the turn's working storage
     */
    private void copyLastTranData(final TurnState state) {
        // Line 473 runs VALIDATE-INPUT-KEY-FIELDS.
        validateInputKeyFields(state);
        if (state.screenSent) {
            return;
        }

        // Line 475 carries HIGH-VALUES into TRAN-ID and the three browse paragraphs at lines 476 to 478.
        startbrTransactFile(state);
        if (state.screenSent) {
            return;
        }
        readprevTransactFile(state);
        if (state.screenSent) {
            return;
        }
        endbrTransactFile(state);

        // IF NOT ERR-FLG-ON at line 480.
        if (!state.errorFlag && state.browsed != null) {
            final Transaction last = state.browsed;
            // Line 481 carries TRAN-AMT into WS-TRAN-AMT-E and WS-TRAN-AMT-E TO TRNAMTI at line 485.
            state.amount = editedAmountField(last.getTranAmt());
            state.typeCd = moveToField(last.getTranTypeCd(), TYPE_CD_WIDTH);
            state.categoryCd = moveToField(last.getTranCatCd(), CATEGORY_CD_WIDTH);
            state.source = moveToField(last.getTranSource(), SOURCE_WIDTH);
            state.description = moveToField(last.getTranDesc(), DESCRIPTION_WIDTH);
            // Lines 487 and 488: twenty-six characters into ten, so the move truncates on the right.
            state.origDate = moveToField(last.getTranOrigTs(), SCREEN_DATE_WIDTH);
            state.procDate = moveToField(last.getTranProcTs(), SCREEN_DATE_WIDTH);
            state.merchantId = moveToField(last.getMerchantId(), MERCHANT_ID_WIDTH);
            state.merchantName = moveToField(last.getMerchantName(), MERCHANT_NAME_WIDTH);
            state.merchantCity = moveToField(last.getMerchantCity(), MERCHANT_CITY_WIDTH);
            state.merchantZip = moveToField(last.getMerchantZip(), MERCHANT_ZIP_WIDTH);
        }

        // Line 495 runs PROCESS-ENTER-KEY.
        processEnterKey(state);
    }

    // ==========================================================================================
    // RETURN-TO-PREV-SCREEN, line 500
    // ==========================================================================================

    /**
     * The transfer paragraph at lines 500 to 511.
     *
     * <p>Lines 502 to 504 default the nominated destination to sign-on when nothing is nominated; the
     * resolution itself is the navigation rules' concern, so no route table is declared here. Lines 505
     * to 507 then stamp the originating transaction and program and reset the program context to its
     * first-entry value, and lines 508 to 511 transfer control. A transfer carries the communication area
     * but does not re-arm a transaction, so the turn reports no re-armed identifier.
     *
     * @param state the turn's working storage
     */
    private void returnToPrevScreen(final TurnState state) {
        // Lines 502 to 504, both arms, resolved by the navigation rules.
        final NavigationService.Route destination = navigationService
                .resolveNominatedDestination(carriedState(state.context), NavigationService.Route.SIGN_ON);

        // Lines 505 to 507 carry this transaction id into CDEMO-FROM-TRANID, this program name into
        // CDEMO-FROM-PROGRAM, and zero into CDEMO-PGM-CONTEXT.
        state.context = withOriginatingProgram(
                withNominatedProgram(state.context, destination.getLegacyProgramName()))
                .withFirstEntry();

        // Lines 508 to 511 transfer control to the nominated program with the carried work area.
        state.route = destination;
        state.transferred = true;
        LOG.debug("Transferring control from the transaction-add screen to route {}",
                destination.getRouteValue());
    }

    // ==========================================================================================
    // SEND-TRNADD-SCREEN, line 516
    // ==========================================================================================

    /**
     * The send paragraph at lines 516 to 534.
     *
     * <p>It populates the header at line 518, moves the eighty-character message work field into the
     * {@value #ERROR_MESSAGE_WIDTH}-character outbound message field at line 520, sends the map at lines
     * 522 to 528, and then issues {@code EXEC CICS RETURN TRANSID} at lines 530 to 534.
     *
     * <p><strong>That return makes the send terminal.</strong> It ends the task, so the performing
     * paragraph never resumes and nothing after the first send executes: no later validation stage runs,
     * no later field is normalised and no insert is attempted. Ending the task leaves the performed range
     * altogether rather than reaching an exit label within it, so a plain {@code return} from this method
     * could not express it &mdash; every caller up the chain has to see that the turn is over. This
     * method therefore renders the outbound state, as a rendering step must, and additionally
     * <strong>latches the turn-ended state</strong> that its callers test.
     *
     * @param state the turn's working storage
     */
    private void sendTrnaddScreen(final TurnState state) {
        populateHeaderInfo(state);

        // Line 520 carries WS-MESSAGE into ERRMSGO OF COTRN2AO.
        state.errorMessageField = moveToField(state.message, ERROR_MESSAGE_WIDTH);

        // EXEC CICS RETURN TRANSID at lines 530 to 534: the task ends here, so the turn is over for
        // every paragraph that is still notionally on the stack.
        state.screenSent = true;
        returnToCics(state);
    }

    // ==========================================================================================
    // RECEIVE-TRNADD-SCREEN, line 539
    // ==========================================================================================

    /**
     * The receive paragraph at lines 539 to 547.
     *
     * <p>Receives the input map, which is the point at which each transmitted value is bounded to the
     * width its symbolic-map field declares: a longer value loses its excess on the right and a shorter
     * one is space filled, so every later comparison works on a fixed-width value exactly as the
     * source's comparisons do. A field the client omitted arrives as low values, which the blankness
     * tests treat identically to spaces.
     *
     * <p>The response and reason codes the source captures at lines 545 and 546 are inspected nowhere in
     * the member, so nothing branches on them here either.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen
     */
    private void receiveTrnaddScreen(final TurnState state, final TransactionAddScreenInput input) {
        state.accountId = moveToField(input.accountId(), ACCOUNT_ID_WIDTH);
        state.cardNumber = moveToField(input.cardNumber(), CARD_NUMBER_WIDTH);
        state.typeCd = moveToField(input.typeCd(), TYPE_CD_WIDTH);
        state.categoryCd = moveToField(input.categoryCd(), CATEGORY_CD_WIDTH);
        state.source = moveToField(input.source(), SOURCE_WIDTH);
        state.description = moveToField(input.description(), DESCRIPTION_WIDTH);
        state.amount = moveToField(input.amount(), AMOUNT_WIDTH);
        state.origDate = moveToField(input.origDate(), SCREEN_DATE_WIDTH);
        state.procDate = moveToField(input.procDate(), SCREEN_DATE_WIDTH);
        state.merchantId = moveToField(input.merchantId(), MERCHANT_ID_WIDTH);
        state.merchantName = moveToField(input.merchantName(), MERCHANT_NAME_WIDTH);
        state.merchantCity = moveToField(input.merchantCity(), MERCHANT_CITY_WIDTH);
        state.merchantZip = moveToField(input.merchantZip(), MERCHANT_ZIP_WIDTH);
        state.confirm = moveToField(input.confirm(), CONFIRM_WIDTH);
    }

    // ==========================================================================================
    // POPULATE-HEADER-INFO, line 552
    // ==========================================================================================

    /**
     * The header paragraph at lines 552 to 571.
     *
     * <p>Reads the current date and time into the work group this member includes at line 85, then stamps
     * the two catalogue titles, the transaction identifier and the program name, and assembles the
     * two-digit header date and time. The header date keeps the legacy's two-digit year, taken as the
     * last two characters of the four-character year exactly as the reference modification at line 563
     * takes it, and both separators are the ones the work group declares.
     *
     * <p>The same clock reading also yields the group's full online twenty-six-character timestamp, which
     * {@code onlineTimestamp} renders. One reading serves all three renderings, so the header date, the
     * header time and the timestamp cannot disagree with one another.
     *
     * @param state the turn's working storage
     */
    private void populateHeaderInfo(final TurnState state) {
        // Line 554 carries the current date into WS-CURDATE-DATA.
        final LocalDateTime now = LocalDateTime.now(clock);

        state.title01 = messageCatalogService.screenTitle01();
        state.title02 = messageCatalogService.screenTitle02();
        state.transactionName = WS_TRANID;
        state.programName = WS_PGMNAME;

        // Lines 561 to 565: MM/DD/YY, the year taken as WS-CURDATE-YEAR(3:2).
        final String year = numericValueField(now.getYear(), YEAR_WIDTH);
        state.currentDate = numericValueField(now.getMonthValue(), HEADER_PART_WIDTH)
                + HEADER_DATE_SEPARATOR
                + numericValueField(now.getDayOfMonth(), HEADER_PART_WIDTH)
                + HEADER_DATE_SEPARATOR
                + year.substring(YEAR_SHORT_FORM_OFFSET);

        // Lines 567 to 571: HH:MM:SS.
        state.currentTime = numericValueField(now.getHour(), HEADER_PART_WIDTH)
                + HEADER_TIME_SEPARATOR
                + numericValueField(now.getMinute(), HEADER_PART_WIDTH)
                + HEADER_TIME_SEPARATOR
                + numericValueField(now.getSecond(), HEADER_PART_WIDTH);

        state.screenTimestamp = onlineTimestamp(now);
    }

    /**
     * Renders the <strong>online</strong> twenty-six-character timestamp of the work group declared at
     * {@code app/cpy/CSDAT01Y.cpy} lines 42 to 55, built exactly as the canonical construction at
     * {@code app/cbl/COBIL00C.cbl} lines 249 to 267 builds it.
     *
     * <p>The layout is a four-digit year, a hyphen, a two-digit month, a hyphen, a two-digit day, a
     * <strong>space at position 11</strong>, a two-digit hour, a colon, two-digit minutes, a colon,
     * two-digit seconds, a <strong>period at position 20</strong>, and a six-digit fraction &mdash; and
     * the fraction is <strong>invariably six zeros</strong>. The canonical construction places the
     * ten-character date and the eight-character time and then moves zeros over the fraction, and the
     * initialise verb it opens with leaves the literal fillers untouched, so the space and the period both
     * survive. No sub-second value the clock happens to carry ever reaches the rendered form, which is why
     * the zeros are written here rather than formatted from the reading.
     *
     * <p>This helper is <strong>private to this class</strong> and no shared or top-level timestamp
     * formatter type exists in the module. The batch twenty-six-character form &mdash; a hyphen before the
     * hour, dots between the time parts, two hundredths digits and four literal zeros &mdash; belongs to
     * the posting and interest services as their own private helpers and is never reused across the tier
     * boundary. Emitting the batch form from an online path would silently break byte parity, so the two
     * are kept apart by construction rather than by convention.
     *
     * @param reading the clock reading the header paragraph took
     * @return the online form, exactly {@value #TIMESTAMP_WIDTH} encoded bytes
     * @throws IllegalStateException if the rendered value is not exactly {@value #TIMESTAMP_WIDTH}
     *                              encoded bytes, which can only mean this renderer drifted from the
     *                              group layout
     */
    private static String onlineTimestamp(final LocalDateTime reading) {
        final String rendered = numericValueField(reading.getYear(), YEAR_WIDTH)
                + DATE_SEPARATOR
                + numericValueField(reading.getMonthValue(), MONTH_DAY_WIDTH)
                + DATE_SEPARATOR
                + numericValueField(reading.getDayOfMonth(), MONTH_DAY_WIDTH)
                // FILLER PIC X(01) VALUE ' ' at copybook line 48: a space, and never a hyphen.
                + TIMESTAMP_DATE_TIME_SEPARATOR
                + numericValueField(reading.getHour(), HEADER_PART_WIDTH)
                + HEADER_TIME_SEPARATOR
                + numericValueField(reading.getMinute(), HEADER_PART_WIDTH)
                + HEADER_TIME_SEPARATOR
                + numericValueField(reading.getSecond(), HEADER_PART_WIDTH)
                // FILLER PIC X(01) VALUE '.' at copybook line 54.
                + TIMESTAMP_FRACTION_SEPARATOR
                // COBIL00C line 266 zeroes WS-TIMESTAMP-TM-MS6. The reading's own nanosecond
                // field is deliberately not consulted.
                + TIMESTAMP_ZERO_FRACTION;

        final int encodedWidth = rendered.getBytes(StandardCharsets.US_ASCII).length;
        if (encodedWidth != TIMESTAMP_WIDTH) {
            throw new IllegalStateException("the online timestamp must be exactly " + TIMESTAMP_WIDTH
                    + " encoded bytes to fill WS-TIMESTAMP, but measures " + encodedWidth);
        }
        return rendered;
    }

    // ==========================================================================================
    // READ-CXACAIX-FILE, line 576
    // ==========================================================================================

    /**
     * The alternate-index read at lines 576 to 604: the cross-reference record of one account, read
     * through the {@code CXACAIX} access path keyed on the account identifier.
     *
     * <p>The evaluation of the response has three arms in the source's order: a normal response
     * continues, a not-found response reports {@value #MSG_ACCOUNT_ID_NOT_FOUND} at the account field,
     * and anything else reports {@value #MSG_XREF_AIX_LOOKUP_FAILED} at the same field after writing a
     * diagnostic.
     *
     * <p><strong>The alternate key is non-unique</strong>, so the access path is defined to yield the
     * first matching row in key order, which is what the legacy read of that path returns. An
     * <strong>absent row is the not-found arm</strong> &mdash; a screen message and a cursor position, not
     * an exception and not an abend, because this member has no abend path at all. There is no persistence
     * association and no foreign key behind this call: the relationship is resolved by this explicit
     * lookup, deliberately, and that absence of constraints is what keeps the batch tier's reject paths
     * reachable.
     *
     * <p>The third arm's diagnostic names the record type and never the account identifier, which is a
     * customer-linked key.
     *
     * @param state the turn's working storage
     */
    private void readCxacaixFile(final TurnState state) {
        final Optional<CardCrossReference> located;
        try {
            // One keyed READ of the alternate-index path, expressed as the repository's ordered-first
            // finder: bounded to one row and ordered on the base key, which is the row that read returns.
            located = cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(state.xrefAccountId);
        } catch (final RuntimeException lookupFailure) {
            // WHEN OTHER at lines 597 to 603, including the DISPLAY at line 598.
            LOG.error("Alternate-index lookup of the {} failed: failureChain={}",
                    CROSS_REFERENCE_RECORD, FailureDiagnostics.failureChainOf(lookupFailure));
            faultField(state, MSG_XREF_AIX_LOOKUP_FAILED, PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                    ValidationException.FieldState.INVALID);
            return;
        }

        if (located.isEmpty()) {
            // The NOTFND-response arm at lines 591 to 596.
            LOG.debug("No {} row carries the requested account identifier", CROSS_REFERENCE_RECORD);
            faultField(state, MSG_ACCOUNT_ID_NOT_FOUND, PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                    ValidationException.FieldState.INVALID);
            return;
        }

        // The NORMAL-response arm at lines 589 and 590, which does nothing: the record area now holds the row.
        final CardCrossReference record = located.get();
        state.xrefCardNumber = record.getXrefCardNum();
        state.xrefAccountId = record.getXrefAcctId();
    }

    // ==========================================================================================
    // READ-CCXREF-FILE, line 609
    // ==========================================================================================

    /**
     * The base-cluster read at lines 609 to 637: the cross-reference record of one card, read by its own
     * key.
     *
     * <p>The card number is the cluster key, so this is an identity lookup and its result is at most one
     * row. The three response arms keep the source's order and their own texts,
     * {@value #MSG_CARD_NUMBER_NOT_FOUND} and {@value #MSG_XREF_LOOKUP_FAILED}, and both position the
     * cursor on the card field rather than the account field. As with the alternate-index read, an absent
     * row is a screen message and never an exception.
     *
     * @param state the turn's working storage
     */
    private void readCcxrefFile(final TurnState state) {
        final Optional<CardCrossReference> located;
        try {
            located = cardCrossReferenceRepository.findById(state.xrefCardNumber);
        } catch (final RuntimeException lookupFailure) {
            // WHEN OTHER at lines 630 to 636, including the DISPLAY at line 631.
            LOG.error("Base-cluster lookup of the {} failed: failureChain={}",
                    CROSS_REFERENCE_RECORD, FailureDiagnostics.failureChainOf(lookupFailure));
            faultField(state, MSG_XREF_LOOKUP_FAILED, PROPERTY_CARD_NUMBER, FIELD_CARD_NUMBER,
                    ValidationException.FieldState.INVALID);
            return;
        }

        if (located.isEmpty()) {
            // The NOTFND-response arm at lines 624 to 629.
            LOG.debug("No {} row carries the requested card number", CROSS_REFERENCE_RECORD);
            faultField(state, MSG_CARD_NUMBER_NOT_FOUND, PROPERTY_CARD_NUMBER, FIELD_CARD_NUMBER,
                    ValidationException.FieldState.INVALID);
            return;
        }

        // The NORMAL-response arm at lines 622 and 623, which does nothing.
        final CardCrossReference record = located.get();
        state.xrefCardNumber = record.getXrefCardNum();
        state.xrefAccountId = record.getXrefAcctId();
    }

    // ==========================================================================================
    // STARTBR-TRANSACT-FILE, line 642
    // ==========================================================================================

    /**
     * The browse-start paragraph at lines 642 to 668: position the transaction master at the record key,
     * which both call sites have just set to high values, so the browse opens at the end of the key
     * sequence.
     *
     * <p>Positioning becomes the ordered, descending, single-row lookup that the backward read then
     * consumes. Establishing the position and consuming it are separate paragraphs in the source and stay
     * separate methods here, so the paragraph map keeps one row for each.
     *
     * <p><strong>The not-found arm at lines 655 to 660 is unreachable in the relational
     * realisation.</strong> Positioning at the high end of an ordered index cannot fail independently of
     * the read: either the index has rows and the read returns the last one, or it has none and the read
     * reports end of file. The legacy's own empty-table path is the backward read's end-of-file arm at
     * line 689, which raises no error and seeds the key with zeros &mdash; and that is precisely what
     * makes the first identifier reachable on an empty table. The arm's text,
     * {@value #MSG_TRANSACTION_ID_NOT_FOUND}, is kept as a declared constant so the contract stays
     * recorded, and the divergence is raised for the decision log. The failure arm at lines 661 to 667
     * remains live and reports {@value #MSG_TRANSACTION_LOOKUP_FAILED}.
     *
     * @param state the turn's working storage
     */
    private void startbrTransactFile(final TurnState state) {
        // Lines 444 and 475 fill TRAN-ID with high values at the call sites: the browse is positioned at
        // the end of the key sequence, which a descending order expresses directly.
        try {
            state.browseCursor = highestTransactionWindow();
        } catch (final RuntimeException browseFailure) {
            // WHEN OTHER at lines 661 to 667, including the DISPLAY at line 662.
            LOG.error("Positioning the transaction browse at the highest key failed:"
                    + " failureChain={}", FailureDiagnostics.failureChainOf(browseFailure));
            faultField(state, MSG_TRANSACTION_LOOKUP_FAILED, PROPERTY_TRAN_ID, FIELD_ACCOUNT_ID,
                    ValidationException.FieldState.INVALID);
        }
    }

    /**
     * Reads the highest transaction-key window without translating a persistence failure.
     *
     * <p>The copy-last path calls it through {@link #startbrTransactFile(TurnState)}, which maps a
     * failure immediately because no write transaction exists. The add path calls it inside
     * {@link OnlineTransactionBoundary}; allowing a failure to escape there is what guarantees the
     * transaction rolls back before the outer screen arm maps it.
     */
    private List<Transaction> highestTransactionWindow() {
        // A single-row descending read, which is what READPREV from HIGH-VALUES is. It replaces a
        // descending page of size one: a page carries a total, so the server was counting the whole
        // transaction master on every turn of this screen and nothing ever read the figure. The window
        // shape is retained because the browse position is legitimately "at most one row" - an empty
        // window is the end-of-file response that seeds zero. Recorded as DL-296.
        return transactionRepository.findFirstByOrderByTranIdDesc()
                .map(List::of)
                .orElseGet(List::of);
    }

    // ==========================================================================================
    // READPREV-TRANSACT-FILE, line 673
    // ==========================================================================================

    /**
     * The backward-read paragraph at lines 673 to 697: read one record backward from the browse position,
     * which lands on the highest key present.
     *
     * <p>The three response arms keep the source's order. A normal response continues with the record in
     * the record area and its key in the key field. <strong>An end-of-file response moves zeros into the
     * key at line 689 and raises no error</strong>, which is what makes an empty table seed zero so that
     * the first identifier minted is one, rendered as sixteen zero-filled digits. Anything else reports
     * {@value #MSG_TRANSACTION_LOOKUP_FAILED} at the account field after writing a diagnostic.
     *
     * <p>The key is read as a number here because the source reads it as one: it moves the
     * sixteen-character key into a sixteen-digit numeric work field. A key that is not sixteen digits
     * cannot arise from any writer in this module &mdash; every writer stores the zero-filled numeric form
     * &mdash; and if one somehow did, the numeric move is undefined in the language, so the seed is left
     * at zero rather than a value being invented.
     *
     * @param state the turn's working storage
     */
    private void readprevTransactFile(final TurnState state) {
        if (state.browseCursor.isEmpty()) {
            // The end-of-file arm at lines 688 and 689 zeroes TRAN-ID. No error is raised.
            state.browsed = null;
            state.browsedKeyValue = EMPTY_FILE_SEED;
            LOG.debug("The transaction master holds no rows; seeding the identifier from zero");
            return;
        }

        // The NORMAL-response arm at lines 686 and 687, which does nothing.
        final Transaction last = state.browseCursor.get(0);
        state.browsed = last;
        // Line 448 carries TRAN-ID into WS-TRAN-ID-N at the call site.
        state.browsedKeyValue = numericKeyValue(last.getTranId());
    }

    // ==========================================================================================
    // ENDBR-TRANSACT-FILE, line 702
    // ==========================================================================================

    /**
     * The browse-end paragraph at lines 702 to 706: release the browse position.
     *
     * <p>The source captures no response and evaluates nothing, so nothing can fail here and nothing is
     * reported. Releasing the position is what the paragraph does, and the paged lookup's result is
     * released by dropping the reference to it, which is done here rather than implicitly so that the
     * paragraph keeps its own row in the paragraph map and the browse's lifetime is visible in the code
     * exactly where the source makes it visible.
     *
     * @param state the turn's working storage
     */
    private void endbrTransactFile(final TurnState state) {
        state.browseCursor = List.of();
    }

    // ==========================================================================================
    // WRITE-TRANSACT-FILE, line 711
    // ==========================================================================================

    /**
     * The write paragraph at lines 711 to 749.
     *
     * <p>The three response arms keep the source's order. A <strong>normal</strong> response resets all
     * fourteen screen fields at line 725, blanks the message work field at line 726, recolours the message
     * field green at line 727, and composes the success text at lines 728 to 733 from four fragments: the
     * head, which carries a trailing space; the middle, which carries both a leading and a trailing space;
     * the transaction identifier <strong>delimited by its first space</strong>, which for a sixteen-digit
     * key means the whole key; and a full stop. Every fragment's spacing is contractual and is reproduced
     * exactly.
     *
     * <p>A <strong>duplicate</strong> response &mdash; either of the two the source names in one arm
     * &mdash; reports {@value #MSG_TRAN_ID_ALREADY_EXISTS}, and anything else reports
     * {@value #MSG_UNABLE_TO_ADD}; both position the cursor on the account field.
     *
     * <p>The transaction boundary includes the advisory lock and highest-key read that precede this
     * paragraph. A failed allocation or insert therefore rolls back before this method translates it
     * into the matching legacy response arm; no rollback-only failure can replace the screen result at
     * method exit.
     *
     * @param state the turn's working storage
     */
    private void writeTransactFile(final TurnState state) {
        final Transaction saved;
        try {
            saved = transactionBoundary.execute(() -> {
                state.persistenceStage = PersistenceStage.ALLOCATION;
                transactionRepository.lockIdentifierAllocation(
                        TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
                for (int attempt = 1; attempt <= IDENTIFIER_ALLOCATION_ATTEMPTS; attempt++) {
                    state.finalAllocationAttempt = attempt == IDENTIFIER_ALLOCATION_ATTEMPTS;
                    final Transaction pending = allocateTransactionRecord(state);
                    if (pending == null) {
                        return null;
                    }
                    if (transactionRepository.existsById(pending.getTranId())) {
                        state.identifierAlreadyTaken = true;
                        if (!state.finalAllocationAttempt) {
                            LOG.info("Transaction-add identifier is already present; re-reading the"
                                    + " locked maximum attempt={}", attempt + 1);
                            continue;
                        }
                        return null;
                    }
                    state.identifierAlreadyTaken = false;
                    state.persistenceStage = PersistenceStage.INSERT;
                    return transactionRepository.insertAndFlush(pending);
                }
                return null;
            });
        } catch (final RuntimeException writeFailure) {
            if (state.persistenceStage == PersistenceStage.ALLOCATION) {
                LOG.error("Positioning the transaction browse at the highest key failed:"
                                + " file=TRANSACT failureChain={}",
                        FailureDiagnostics.failureChainOf(writeFailure));
                faultField(state, MSG_TRANSACTION_LOOKUP_FAILED, PROPERTY_TRAN_ID, FIELD_ACCOUNT_ID,
                        ValidationException.FieldState.INVALID);
                return;
            }
            if (isDuplicateKeyFailure(writeFailure)) {
                // The duplicate-key and duplicate-record arms at lines 735 to 741. No re-mint follows: a
                // refused flush leaves the surrounding transaction marked for rollback, so a further
                // attempt inside it could not commit.
                LOG.warn("The transaction master already holds the minted identifier: file=TRANSACT"
                        + " failureChain={}", FailureDiagnostics.failureChainOf(writeFailure));
                faultField(state, MSG_TRAN_ID_ALREADY_EXISTS, PROPERTY_TRAN_ID, FIELD_ACCOUNT_ID,
                        ValidationException.FieldState.INVALID);
                return;
            }
            // WHEN OTHER at lines 742 to 748, including the DISPLAY at line 743.
            LOG.error("Writing the transaction failed: file=TRANSACT failureChain={}",
                    FailureDiagnostics.failureChainOf(writeFailure));
            faultField(state, MSG_UNABLE_TO_ADD, PROPERTY_TRAN_ID, FIELD_ACCOUNT_ID,
                    ValidationException.FieldState.INVALID);
            return;
        }
        if (state.identifierAlreadyTaken) {
            // The duplicate-key and duplicate-record arms at lines 735 to 741, reached without asking the
            // store to merge a record whose assigned key is already present.
            LOG.warn("The transaction master already holds the minted identifier and the allocation"
                    + " attempts are exhausted: file=TRANSACT");
            faultField(state, MSG_TRAN_ID_ALREADY_EXISTS, PROPERTY_TRAN_ID, FIELD_ACCOUNT_ID,
                    ValidationException.FieldState.INVALID);
            return;
        }
        if (saved == null) {
            return;
        }

        // The NORMAL-response arm at lines 724 to 734.
        state.written = projectionOf(saved);

        // Line 725 runs INITIALIZE-ALL-FIELDS and carries SPACES into WS-MESSAGE at line 726.
        initializeAllFields(state);

        // Line 727 carries DFHGREEN into ERRMSGC: the one place the member recolours the message field.
        state.messageHighlighted = true;

        // STRING ... INTO WS-MESSAGE at lines 728 to 733.
        state.setMessage(FRAGMENT_ADDED_SUCCESSFULLY
                + FRAGMENT_YOUR_TRAN_ID_IS
                + delimitedBySpace(state.written.tranId())
                + FRAGMENT_FULL_STOP);

        sendTrnaddScreen(state);
    }

    // ==========================================================================================
    // CLEAR-CURRENT-SCREEN, line 754
    // ==========================================================================================

    /**
     * The clear paragraph at lines 754 to 757, reached from the fourth program-function key: reset every
     * field and send. It raises no error and sets no message, so the operator gets an empty screen with no
     * text, which is the source's behaviour and is not embellished here.
     *
     * @param state the turn's working storage
     */
    private void clearCurrentScreen(final TurnState state) {
        initializeAllFields(state);
        sendTrnaddScreen(state);
    }

    // ==========================================================================================
    // INITIALIZE-ALL-FIELDS, line 762
    // ==========================================================================================

    /**
     * The reset paragraph at lines 762 to 779: position the cursor on the account field at line 764, then
     * blank all fourteen screen input fields and the message work field at lines 765 to 779.
     *
     * <p>Both of its call sites are terminal for the turn: the successful-insert path at line 725 and the
     * clear key at line 756. Blanking the message is why the clear key leaves no text behind; the
     * successful-insert path composes its own text immediately afterwards.
     *
     * @param state the turn's working storage
     */
    private void initializeAllFields(final TurnState state) {
        // Line 764 places the cursor on the account-identifier field of the input map.
        state.focusField = FIELD_ACCOUNT_ID;

        state.accountId = blankField(ACCOUNT_ID_WIDTH);
        state.cardNumber = blankField(CARD_NUMBER_WIDTH);
        blankDataFields(state);
        state.confirm = blankField(CONFIRM_WIDTH);
        state.message = NO_MESSAGE;
    }

    /**
     * Blanks the eleven data fields, which is what the narrower reset at lines 238 to 248 does and what
     * the wider reset at lines 767 to 777 does as part of its own work.
     *
     * <p>The two resets differ in exactly which fields they cover &mdash; the narrower one leaves the
     * account, card and confirmation fields alone &mdash; so the eleven they share live here once and each
     * caller adds its own remainder.
     *
     * @param state the turn's working storage
     */
    private static void blankDataFields(final TurnState state) {
        state.typeCd = blankField(TYPE_CD_WIDTH);
        state.categoryCd = blankField(CATEGORY_CD_WIDTH);
        state.source = blankField(SOURCE_WIDTH);
        state.amount = blankField(AMOUNT_WIDTH);
        state.description = blankField(DESCRIPTION_WIDTH);
        state.origDate = blankField(SCREEN_DATE_WIDTH);
        state.procDate = blankField(SCREEN_DATE_WIDTH);
        state.merchantId = blankField(MERCHANT_ID_WIDTH);
        state.merchantName = blankField(MERCHANT_NAME_WIDTH);
        state.merchantCity = blankField(MERCHANT_CITY_WIDTH);
        state.merchantZip = blankField(MERCHANT_ZIP_WIDTH);
    }

    /**
     * The terminal return of the procedure division at lines 156 to 159 and of the send paragraph at lines
     * 530 to 534: the pseudo-conversational return that re-arms this transaction with its carried work area.
     *
     * <p>Re-arming is idempotent and never overrides a transfer, because a transfer has already left the
     * program. The source has no separate return paragraph, so this is not one of the eighteen: it is the
     * statement both sites issue, factored so the two cannot drift apart.
     *
     * @param state the turn's working storage
     */
    private void returnToCics(final TurnState state) {
        if (state.transferred) {
            return;
        }
        state.reArmedTransactionId = WS_TRANID;
    }

    // ==========================================================================================
    // Shared primitives for the constructs the source uses
    // ==========================================================================================

    /**
     * Records one field failure: raises the error flag, latches the summary text and cursor position if
     * this is the first failure of the turn, adds the per-field entry, and sends the screen.
     *
     * <p>Every failure site in the source does these four things in the same order, which is why they live
     * here once rather than being restated two dozen times.
     *
     * <p><strong>This method is terminal, because the send it ends with is terminal.</strong> The send
     * issues the return that ends the task, so a caller must treat a call to this method as the end of the
     * turn: it either returns immediately or tests the turn-ended state before doing anything else.
     * Nothing here can enforce that on a caller's behalf, which is why the state is latched rather than
     * left implicit.
     *
     * @param state      the turn's working storage
     * @param message    the failure's own text, byte exact
     * @param property   the property name a consumer binds to
     * @param bmsFieldId the legacy screen field name the cursor is positioned on
     * @param fieldState whether the field was not supplied or was supplied wrongly
     */
    private void faultField(final TurnState state, final String message, final String property,
            final String bmsFieldId, final ValidationException.FieldState fieldState) {
        state.raiseError(message, bmsFieldId);
        state.recordFieldError(property, bmsFieldId, fieldState, message);
        sendTrnaddScreen(state);
    }

    /**
     * Resolves the destination the third program-function key leads to, reproducing lines 137 to 142: when
     * the originating-program field holds spaces or low values the screen's own default applies, and
     * otherwise the field is honoured.
     *
     * <p>The default here is the <strong>user main menu</strong> and not sign-on, because line 138 names
     * that program specifically. The default is per screen throughout the estate, which is why it is
     * stated here rather than assumed anywhere else, and it is named through the navigation vocabulary
     * rather than written as a literal program name.
     *
     * @param context the navigation state echoed by the client
     * @return the program name to nominate
     */
    private static String resolveExitProgramName(final ScreenNavigationState context) {
        if (isBlankField(context.fromProgram())) {
            // Line 138 carries 'COMEN01C' into CDEMO-TO-PROGRAM.
            return NavigationService.Route.USER_MENU.getLegacyProgramName();
        }
        // Lines 140 and 141 carry CDEMO-FROM-PROGRAM into CDEMO-TO-PROGRAM.
        return context.fromProgram();
    }

    /**
     * Reports whether a field was supplied, reproducing the abbreviated combined relation
     * the abbreviated combined relation at lines 124, 196 and 210, which expands to "is neither all
     * spaces nor all low values".
     *
     * @param field the field to test, which may be {@code null}
     * @return {@code true} when the field carries something other than spaces and low values
     */
    private static boolean isSupplied(final String field) {
        return !isBlankField(field);
    }

    /**
     * Reports whether a fixed-width field is blank in the legacy sense, reproducing
     * {@code = SPACES OR LOW-VALUES} at lines 175, 176, 252 and its ten peers.
     *
     * <p>Blank means every character position is a space or a low value. An absent value and an empty value
     * are both blank, because a field the terminal did not transmit arrives as low values and carries no
     * character positions of its own.
     *
     * @param field the field to test, which may be {@code null}
     * @return {@code true} when the field is absent, empty, or wholly spaces and low values
     */
    private static boolean isBlankField(final String field) {
        if (field == null) {
            return true;
        }
        for (int index = 0; index < field.length(); index++) {
            final char position = field.charAt(index);
            if (position != SPACE && position != LOW_VALUE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether every character position holds an ASCII digit, reproducing {@code IS NOT NUMERIC}
     * at lines 197, 211, 323, 329, 341, 343, 354, 356, 358, 369, 371, 373 and 430 when negated.
     *
     * <p>Membership is tested against the ten ASCII digits and never against a Unicode digit test, which
     * would widen the accepted set past the single-byte characters the legacy field can hold. A field with
     * a space in it is <strong>not</strong> numeric, which is the legacy rule for an alphanumeric item and
     * is why a padded value is rejected. An empty field is not numeric either, matching a field with no
     * digit in it.
     *
     * @param field the field to test, never {@code null} at any call site here
     * @return {@code true} when the field is non-empty and holds nothing but ASCII digits
     */
    private static boolean isAllDigits(final String field) {
        if (field.isEmpty()) {
            return false;
        }
        for (int index = 0; index < field.length(); index++) {
            final char position = field.charAt(index);
            if (position < '0' || position > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a ten-character field carries the five fixed positions the date evaluations at lines
     * 354 to 358 and 369 to 373 require: four digits, a hyphen, two digits, a hyphen and two digits.
     *
     * <p>The five conditions are alternatives of one arm in the source, so any one of them failing gives
     * the same single text and this predicate answers the whole shape at once. Only the shape is tested
     * here; whether the shape names a real calendar day is the subprogram's question and is asked
     * afterwards, which is the order the source asks them in.
     *
     * @param field the ten-character screen date field
     * @return {@code true} when all five positions hold what the evaluation requires
     */
    private static boolean isTenCharacterDateShape(final String field) {
        return isAllDigits(field.substring(DATE_YEAR_OFFSET, DATE_YEAR_OFFSET + YEAR_WIDTH))
                && field.charAt(DATE_FIRST_SEPARATOR_OFFSET) == DATE_SEPARATOR
                && isAllDigits(field.substring(DATE_MONTH_OFFSET, DATE_MONTH_OFFSET + MONTH_DAY_WIDTH))
                && field.charAt(DATE_SECOND_SEPARATOR_OFFSET) == DATE_SEPARATOR
                && isAllDigits(field.substring(DATE_DAY_OFFSET, DATE_DAY_OFFSET + MONTH_DAY_WIDTH));
    }

    /**
     * Converts a screen amount lexeme, reproducing {@code FUNCTION NUMVAL-C} at lines 383 and 456.
     *
     * <p>The conversion is delegated to the module's <strong>single decimal authority</strong>, which
     * applies the canonical monetary scale by truncating toward zero. Nothing is rescaled here and no
     * rounding mode is chosen here: the estate carries no rounding directive on any arithmetic statement,
     * so truncation is the correct behaviour and concentrating it in one place is what stops a second,
     * divergent policy from appearing.
     *
     * <p>A lexeme that is not a well-formed argument yields {@code null} rather than an exception, because
     * the conversion of such an argument is undefined in the language and the source neither guards it nor
     * recovers from it; the caller leaves the field exactly as transmitted.
     *
     * @param lexeme the screen field, already bounded to its declared width
     * @return the converted value at the monetary scale, or {@code null} when the lexeme is not
     *         well formed
     */
    private static BigDecimal numericLexemeValue(final String lexeme) {
        if (!CobolStringUtils.isNumericLexeme(lexeme)) {
            return null;
        }
        return ZonedDecimalCodec.fromNumericLexeme(lexeme);
    }

    /**
     * Renders a value into the edited amount field {@code WS-TRAN-AMT-E PIC +99999999.99} declared at line
     * 59, which is what lines 385 and 481 move into.
     *
     * <p>Three properties of the picture are reproduced. The sign position <strong>always</strong> carries
     * a character, a plus for a value of zero or more and a minus otherwise, so the rendered field is
     * always twelve characters. The integer part is zero filled to {@value #EDITED_AMOUNT_INTEGER_DIGITS}
     * digits and, when the value has more digits than that, <strong>loses its leading excess</strong>,
     * because a store into a narrower display field truncates the high-order digits. The fractional part is
     * exactly {@value #EDITED_AMOUNT_FRACTION_DIGITS} digits, taken from the value already at the monetary
     * scale, so nothing is rounded here.
     *
     * <p>The zero fill is delegated to the module's string primitives rather than assembled from a format
     * specification, both because the primitive already reproduces the legacy left-truncation and because
     * a format specification would carry a locale.
     *
     * @param value the amount, already at the monetary scale
     * @return exactly {@value #AMOUNT_WIDTH} characters
     */
    private static String editedAmountField(final BigDecimal value) {
        final BigDecimal scaled = ZonedDecimalCodec.toMonetaryScale(value);
        final String sign = scaled.signum() < 0
                ? String.valueOf(AMOUNT_SIGN_MINUS)
                : String.valueOf(AMOUNT_SIGN_PLUS);
        final String digits = scaled.abs().toPlainString();
        final int pointIndex = digits.indexOf(DECIMAL_POINT);
        final String integerDigits = pointIndex < 0 ? digits : digits.substring(0, pointIndex);
        final String fractionDigits = pointIndex < 0
                ? "0".repeat(EDITED_AMOUNT_FRACTION_DIGITS)
                : digits.substring(pointIndex + 1);
        return sign
                + CobolStringUtils.rightJustifyZeroFill(integerDigits, EDITED_AMOUNT_INTEGER_DIGITS)
                + DECIMAL_POINT
                + CobolStringUtils.rightJustifyZeroFill(fractionDigits, EDITED_AMOUNT_FRACTION_DIGITS);
    }

    /**
     * Reproduces a move of an all-digit screen field into an unsigned numeric field of the given digit
     * count and straight back over the screen field, as lines 204 to 207 and 218 to 221 do: the value is
     * zero filled on the left and, on overflow, keeps its low-order digits.
     *
     * <p>Both call sites have already proved the field is all digits, so the numeric conversion is exact
     * and the whole operation reduces to the zero fill the store performs.
     *
     * @param field the all-digit screen field
     * @param width the digit count of the receiving numeric field
     * @return exactly {@code width} digits
     */
    private static String numericField(final String field, final int width) {
        return CobolStringUtils.rightJustifyZeroFill(field, width);
    }

    /**
     * Reproduces a move of an unsigned whole number into a display field of the given digit count: zero
     * filled on the left, and keeping the low-order digits when the value has more digits than the field
     * has positions.
     *
     * @param value the value to render; its magnitude is used, matching an unsigned receiving field
     * @param width the digit count of the receiving field
     * @return exactly {@code width} digits
     */
    private static String numericValueField(final int value, final int width) {
        return CobolStringUtils.rightJustifyZeroFill(Integer.toString(Math.abs(value)), width);
    }

    /**
     * Reproduces line 448, where TRAN-ID is carried into WS-TRAN-ID-N: the sixteen-character record key
     * read as the sixteen-digit numeric work field the increment is applied to.
     *
     * <p>Every writer in this module stores the zero-filled numeric form, so a key that is not sixteen
     * digits cannot arise. Should one somehow be present, the numeric move is undefined in the language, so
     * the seed is left at the value an empty file would have produced rather than a value being invented;
     * the condition is recorded as a warning that names neither the key nor any digit of it.
     *
     * @param key the sixteen-character record key
     * @return the key as a whole number, or the empty-file seed when the key is not all digits
     */
    private static long numericKeyValue(final String key) {
        if (key == null || !isAllDigits(key)) {
            LOG.warn("The highest transaction key is not a {}-digit numeric value; seeding from zero",
                    TRAN_ID_WIDTH);
            return EMPTY_FILE_SEED;
        }
        return Long.parseLong(key);
    }

    /**
     * Reproduces {@code STRING ... DELIMITED BY SPACE}, which stops assembling at the first space, as used
     * on the transaction identifier at line 731. For a sixteen-digit key there is no space, so the whole
     * key is taken.
     *
     * @param field the fixed-width field to consume
     * @return the part of the field before its first space, or the whole field when it holds none
     */
    private static String delimitedBySpace(final String field) {
        final int firstSpace = field.indexOf(SPACE);
        return firstSpace < 0 ? field : field.substring(0, firstSpace);
    }

    /**
     * Reproduces a move into an alphanumeric field of the given width: left justified, space filled on the
     * right when the sender is shorter, and truncated on the right when it is longer. An absent sender
     * yields a blank field, because a field the terminal did not transmit holds no characters.
     *
     * @param value the sending value, which may be {@code null}
     * @param width the receiving field width in character positions
     * @return exactly {@code width} characters
     */
    private static String moveToField(final String value, final int width) {
        if (value == null) {
            return blankField(width);
        }
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + blankField(width - value.length());
    }

    /**
     * Produces a blank field of the given width, which is what {@code INITIALIZE} and
     * a blanking move write into an alphanumeric item.
     *
     * @param width the field width in character positions
     * @return exactly {@code width} spaces
     */
    private static String blankField(final int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    /**
     * Reports whether a persistence failure is the duplicate-key condition the source's combined
     * {@code DUPKEY}/{@code DUPREC} arm at lines 735 and 736 handles.
     *
     * <p>The test walks the cause chain and matches on the persistence provider's own duplicate-entity
     * type rather than on a message or a vendor error code, so it does not depend on the database in use
     * or on the locale a message is rendered in.
     *
     * @param failure the failure the write raised
     * @return {@code true} when the failure is a duplicate key
     */
    private static boolean isDuplicateKeyFailure(final RuntimeException failure) {
        return RecordWriter.isDuplicateKey(failure);
    }

    /**
     * Projects a persisted transaction onto a value, so nothing that leaves this service holds a managed
     * entity that a consumer could mutate into the database.
     *
     * <p>Every field is carried exactly as stored: nothing is trimmed, padded, folded or rescaled, because
     * the fixed-width padding and the decimal scale are part of the record contract. In particular the
     * source code keeps its trailing spaces and the category code keeps its leading zeros.
     *
     * @param stored the persisted transaction
     * @return the projection
     */
    private static TransactionProjection projectionOf(final Transaction stored) {
        return new TransactionProjection(stored.getTranId(),
                stored.getTranTypeCd(),
                stored.getTranCatCd(),
                stored.getTranSource(),
                stored.getTranDesc(),
                stored.getTranAmt(),
                stored.getMerchantId(),
                stored.getMerchantName(),
                stored.getMerchantCity(),
                stored.getMerchantZip(),
                stored.getTranCardNum(),
                stored.getTranOrigTs(),
                stored.getTranProcTs());
    }

    /**
     * Returns a copy of the navigation state whose nominated-destination program is the one supplied,
     * reproducing a move into {@code CDEMO-TO-PROGRAM} at lines 116, 138, 141 and 503.
     *
     * <p>Every other component is carried across unchanged. The sixteen components are restated explicitly
     * because the state is an immutable record; the alternative, a mutable copy, would let echoed client
     * state be altered in place.
     *
     * @param context     the state to copy
     * @param programName the destination program name to nominate
     * @return a new state differing only in its nominated-destination program
     */
    private static ScreenNavigationState withNominatedProgram(final ScreenNavigationState context,
            final String programName) {
        return new ScreenNavigationState(context.fromTransactionId(),
                context.fromProgram(),
                context.toTransactionId(),
                programName,
                context.userId(),
                context.userType(),
                context.programContext(),
                context.customerId(),
                context.customerFirstName(),
                context.customerMiddleName(),
                context.customerLastName(),
                context.accountId(),
                context.accountStatus(),
                context.cardNumber(),
                context.lastMap(),
                context.lastMapset());
    }

    /**
     * Returns a copy of the navigation state stamped with this screen's transaction identifier and program
     * name as the originator, reproducing lines 505 and 506.
     *
     * @param context the state to copy
     * @return a new state differing only in its originating transaction and program
     */
    private static ScreenNavigationState withOriginatingProgram(final ScreenNavigationState context) {
        return new ScreenNavigationState(WS_TRANID,
                WS_PGMNAME,
                context.toTransactionId(),
                context.toProgram(),
                context.userId(),
                context.userType(),
                context.programContext(),
                context.customerId(),
                context.customerFirstName(),
                context.customerMiddleName(),
                context.customerLastName(),
                context.accountId(),
                context.accountStatus(),
                context.cardNumber(),
                context.lastMap(),
                context.lastMapset());
    }

    // ==========================================================================================
    // Per-invocation working storage
    // ==========================================================================================

    /**
     * The working storage the legacy declares at lines 35 to 93, held per invocation so the service bean
     * itself stays stateless and two concurrent turns cannot observe one another. No identifier and no
     * last-issued key is ever retained beyond a call.
     *
     * <p>Package-private mutable fields rather than accessors: this is a local scratch area belonging to
     * one call of one enclosing class, and accessors would add ceremony without adding safety.
     *
     * <p><strong>Three working-storage items of the source are deliberately absent</strong>, because the
     * source itself never reads them and modelling a value nothing can observe would misrepresent the
     * program. The user-modified flag at line 49 is set once at line 110 and tested nowhere; the edited
     * amount work field at line 53 and the eight-character date work field at line 54 are declared and
     * referenced nowhere at all. Each is recorded here rather than dropped silently, so the mapping from
     * working storage to state stays complete.
     */
    private static final class TurnState {

        /** {@code WS-ERR-FLG} at line 44, with its two condition names at lines 45 and 46. */
        private boolean errorFlag;

        /** {@code WS-MESSAGE}, {@code PIC X(80)} at line 38, authored rather than padded. */
        private String message = NO_MESSAGE;

        /** {@code ACTIDINI}. */
        private String accountId = blankField(ACCOUNT_ID_WIDTH);

        /** {@code CARDNINI}. */
        private String cardNumber = blankField(CARD_NUMBER_WIDTH);

        /** {@code TTYPCDI}. */
        private String typeCd = blankField(TYPE_CD_WIDTH);

        /** {@code TCATCDI}. */
        private String categoryCd = blankField(CATEGORY_CD_WIDTH);

        /** {@code TRNSRCI}, carried raw and never trimmed. */
        private String source = blankField(SOURCE_WIDTH);

        /** {@code TDESCI}. */
        private String description = blankField(DESCRIPTION_WIDTH);

        /** {@code TRNAMTI}. */
        private String amount = blankField(AMOUNT_WIDTH);

        /** {@code TORIGDTI}. */
        private String origDate = blankField(SCREEN_DATE_WIDTH);

        /** {@code TPROCDTI}. */
        private String procDate = blankField(SCREEN_DATE_WIDTH);

        /** {@code MIDI}. */
        private String merchantId = blankField(MERCHANT_ID_WIDTH);

        /** {@code MNAMEI}. */
        private String merchantName = blankField(MERCHANT_NAME_WIDTH);

        /** {@code MCITYI}. */
        private String merchantCity = blankField(MERCHANT_CITY_WIDTH);

        /** {@code MZIPI}. */
        private String merchantZip = blankField(MERCHANT_ZIP_WIDTH);

        /** {@code CONFIRMI}. */
        private String confirm = blankField(CONFIRM_WIDTH);

        /** {@code XREF-ACCT-ID}, the eleven-character key of the cross-reference record. */
        private String xrefAccountId = blankField(ACCOUNT_ID_WIDTH);

        /** {@code XREF-CARD-NUM}, the sixteen-character key of the cross-reference record. */
        private String xrefCardNumber = blankField(CARD_NUMBER_WIDTH);

        /**
         * The browse position the start paragraph establishes and the end paragraph releases, holding at
         * most the one record the backward read consumes.
         */
        private List<Transaction> browseCursor = List.of();

        /** {@code TRAN-RECORD} after the backward read, or {@code null} when the table holds no rows. */
        private Transaction browsed;

        /**
         * {@code WS-TRAN-ID-N}, {@code PIC 9(16)} at line 57, after the move at line 448 and before the
         * increment at line 449. Zero when the backward read reported end of file, from line 689.
         */
        private long browsedKeyValue = EMPTY_FILE_SEED;

        /** The record assembled at lines 450 to 465 and handed to the write at line 466. */
        private Transaction pending;

        /** The durable operation active inside the independent transaction boundary. */
        private PersistenceStage persistenceStage = PersistenceStage.ALLOCATION;

        /** The projection of what the write stored, or {@code null} when nothing was written. */
        private TransactionProjection written;

        /**
         * Whether the identifier the allocate-and-write span minted was found already stored, so the span
         * is performed again from a re-read highest key.
         *
         * <p>Not a legacy field: the legacy browse held its position across the read, the increment and
         * the write, so no legacy statement can observe this state. It exists because a relational store
         * expresses that hold as an advisory lock plus an existence probe, and the probe needs somewhere
         * to report from. Raised only while a further attempt remains, so the write paragraph's own
         * duplicate arm still reports on the last one.
         */
        private boolean identifierAlreadyTaken;

        /**
         * Whether the attempt now running is the last the allocation bound allows.
         *
         * <p>Also not a legacy field. It is what makes the bound observable from inside the write
         * paragraph, which is the only place that can tell a duplicate from a successful write, and it is
         * what stops the deferral above from becoming an unbounded loop.
         */
        private boolean finalAllocationAttempt;

        /** {@code TITLE01O}. */
        private String title01 = NO_MESSAGE;

        /** {@code TITLE02O}. */
        private String title02 = NO_MESSAGE;

        /** {@code TRNNAMEO}. */
        private String transactionName = NO_MESSAGE;

        /** {@code PGMNAMEO}. */
        private String programName = NO_MESSAGE;

        /** {@code CURDATEO}, the header date as {@code MM/DD/YY}. */
        private String currentDate = NO_MESSAGE;

        /** {@code CURTIMEO}, the header time as {@code HH:MM:SS}. */
        private String currentTime = NO_MESSAGE;

        /** The online twenty-six-character timestamp of the same clock reading. */
        private String screenTimestamp = NO_MESSAGE;

        /** {@code ERRMSGO}, the message work field truncated to seventy-eight characters at line 520. */
        private String errorMessageField = blankField(ERROR_MESSAGE_WIDTH);

        /** The {@code DFHGREEN} recolouring at line 727, which only the successful-insert path applies. */
        private boolean messageHighlighted;

        /** The screen field the cursor sits on, from the corresponding {@code MOVE -1}. */
        private String focusField = NO_MESSAGE;

        /** The transaction identifier the turn re-armed, empty on the transfer path. */
        private String reArmedTransactionId = NO_MESSAGE;

        /** Set once control has been transferred, so the re-arm cannot override it. */
        private boolean transferred;

        /**
         * Set once the screen has been sent, which the source's return makes terminal. Every caller of a
         * send tests this before continuing, because the legacy paragraph would never have resumed.
         */
        private boolean screenSent;

        /** {@code CARDDEMO-COMMAREA}, the navigation state the turn carries and hands back. */
        private ScreenNavigationState context = ScreenNavigationState.empty();

        /** The destination the turn leads to; this screen's own unless a transfer replaced it. */
        private NavigationService.Route route = NavigationService.Route.TRANSACTION_ADD;

        /** The per-field detail, one entry per field the turn faulted. */
        private final List<ValidationException.FieldError> fieldErrors = new ArrayList<>();

        /**
         * Raises the error flag and latches the summary text and cursor position on the first failure of
         * the turn.
         *
         * <p>Latching is what keeps the operator-visible outcome byte-identical to the legacy's: the screen
         * carries the first failure's text at the first failure's cursor position. Since the send ends the
         * task as the legacy's does, only one failure can be recorded per turn anyway, and the latch is
         * what guarantees it stays the first one.
         *
         * @param text        the failure's text, byte exact
         * @param cursorField the screen field the cursor is positioned on
         */
        private void raiseError(final String text, final String cursorField) {
            this.errorFlag = true;
            if (this.message.isEmpty()) {
                this.message = text;
                this.focusField = cursorField;
            }
        }

        /**
         * Sets the message unconditionally, for the one site that writes a message with no failure behind
         * it: the success text at lines 728 to 733.
         *
         * @param text the message text, byte exact
         */
        private void setMessage(final String text) {
            this.message = text;
        }

        /**
         * Adds one per-field entry.
         *
         * @param property   the property name a consumer binds to
         * @param bmsFieldId the legacy screen field name
         * @param fieldState whether the field was not supplied or was supplied wrongly
         * @param text       the field's own message
         */
        private void recordFieldError(final String property, final String bmsFieldId,
                final ValidationException.FieldState fieldState, final String text) {
            this.fieldErrors.add(
                    new ValidationException.FieldError(property, bmsFieldId, fieldState, text));
        }

        /**
         * Projects the working storage onto the turn's outcome.
         *
         * @return the outcome, with the per-field detail copied so nothing mutable escapes
         */
        private TransactionAddResult toResult() {
            return new TransactionAddResult(this.route,
                    this.context,
                    this.reArmedTransactionId,
                    this.written,
                    this.message,
                    this.focusField,
                    this.errorFlag,
                    this.context.reEntry(),
                    List.copyOf(this.fieldErrors),
                    new ScreenHeader(this.title01,
                            this.title02,
                            this.transactionName,
                            this.programName,
                            this.currentDate,
                            this.currentTime,
                            this.screenTimestamp,
                            this.errorMessageField,
                            this.messageHighlighted),
                    new ScreenFields(this.accountId,
                            this.cardNumber,
                            this.typeCd,
                            this.categoryCd,
                            this.source,
                            this.description,
                            this.amount,
                            this.origDate,
                            this.procDate,
                            this.merchantId,
                            this.merchantName,
                            this.merchantCity,
                            this.merchantZip,
                            this.confirm));
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
