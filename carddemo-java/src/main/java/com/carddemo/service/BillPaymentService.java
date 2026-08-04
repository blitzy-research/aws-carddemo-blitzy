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

import jakarta.persistence.OptimisticLockException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.CobolStringUtils;
import com.carddemo.util.ZonedDecimalCodec;

/**
 * The online bill-payment screen: pay an account balance in full and record the transaction that
 * settles it.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from {@code app/cbl/COBIL00C.cbl}, transaction {@code CB00}, 572 lines, 16
 * paragraphs, at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} (2022-07-19). No COBOL, copybook, job-control or map
 * text is reproduced here; the legacy estate is cited and never transcribed.
 *
 * <p>Cited authorities, by line:
 *
 * <ul>
 *   <li>{@code app/cbl/COBIL00C.cbl} lines 51 to 70 - the confirmation flag with its two condition
 *       names and the program-local extension to the communication area</li>
 *   <li>lines 99 to 149 - the main paragraph, and the single terminal re-arm at 146 to 149</li>
 *   <li>lines 193 to 206 - the pre-payment balance placed on the screen, then the nothing-to-pay
 *       rejection</li>
 *   <li>lines 208 to 219 - identifier generation by backward browse</li>
 *   <li>lines 218 to 232 - the synthesized transaction record</li>
 *   <li>lines 233 to 235 - the write, the balance computation and the account update, in that
 *       order</li>
 *   <li>lines 236 to 242 - the confirmation prompt and the unconditional send at 242</li>
 *   <li>lines 249 to 267 - the 26-character timestamp, whose fraction is zeroed at 266</li>
 *   <li>lines 472 to 496 - the backward read, whose end-of-file arm at 488 seeds zeros</li>
 *   <li>lines 510 to 547 - the three write outcomes, the green recolour and the send at 532</li>
 *   <li>line 552 - clear the current screen; line 560 - blank every field</li>
 *   <li>{@code app/cpy/CSDAT01Y.cpy} lines 42 to 55 - the 26-character timestamp group</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} - the 350-byte transaction layout</li>
 *   <li>{@code app/cpy/CVACT03Y.cpy} - the 50-byte cross-reference layout, 36 data bytes plus 14
 *       filler</li>
 * </ul>
 *
 * <h2>All sixteen paragraphs, one named method each</h2>
 *
 * <p>The traceability matrix carries one row per paragraph. In source order, with the source line
 * and the method that reproduces it: 99 {@code mainPara}, 154 {@code processEnterKey}, 249
 * {@code getCurrentTimestamp}, 273 {@code returnToPrevScreen}, 289 {@code sendBillpayScreen}, 306
 * {@code receiveBillpayScreen}, 319 {@code populateHeaderInfo}, 343 {@code readAcctdatFile}, 377
 * {@code updateAcctdatFile}, 408 {@code readCxacaixFile}, 441 {@code startbrTransactFile}, 472
 * {@code readprevTransactFile}, 501 {@code endbrTransactFile}, 510 {@code writeTransactFile}, 552
 * {@code clearCurrentScreen}, 560 {@code initializeAllFields}.
 *
 * <p>This member contains no backward {@code GO TO}, so no method here reproduces a loop, and every
 * performed range is the paired paragraph-and-exit idiom that becomes a private method with an early
 * return. Both selection constructs are reproduced as switches whose clause order is preserved,
 * because the language evaluates top down and stops at the first match, and whose catch-all arm
 * becomes the default arm. The four two-state condition-name groups become nested enumerations with
 * predicate methods, so a flag is type-checked state rather than a character comparison.
 *
 * <h2>Three contracts this member owns for the whole module</h2>
 *
 * <p><strong>The online 26-character timestamp always carries a zero fraction.</strong> The
 * construction paragraph formats the date and the time, initialises the group and then writes zeros
 * over the six-digit fraction, so the rendered form is invariably a hyphenated date, a space, a
 * colon-separated time, a period and six zeros. The two structural characters survive the initialise
 * because they are filler items, which the verb does not touch. The clock's own sub-second value is
 * therefore discarded rather than rendered - see {@link #getCurrentTimestamp()} for why a formatter
 * pattern emitting real microseconds is a silent parity break, and why the batch tier's differently
 * shaped 26-character form is deliberately not shared with this one.
 *
 * <p><strong>The transaction identifier is the highest existing key plus one, minted inside the
 * transaction that inserts it.</strong> On an empty table the first identifier is the sixteen
 * character string {@code 0000000000000001} and not {@code 1}. A database sequence, a generated
 * value and a random identifier are all prohibited: a sequence never reuses a value it has issued,
 * whereas this rule always reuses a gap, and the first rollback after an identifier is consumed
 * guarantees a gap. See {@link #processEnterKey(TurnState)}.
 *
 * <p><strong>The write order is transaction, then balance, then account - the reverse of the batch
 * posting order.</strong> Both orderings are contractual for their own tier and the two are
 * deliberately not aligned. Because the amount paid <em>is</em> the full current balance, the
 * resulting balance is exactly zero.
 *
 * <h2>What this service is not</h2>
 *
 * <p>This member is not one of the five programs that include the attention-key copybook, so it
 * declares no abend handler and no key-translation collaborator, and none is wired here. It returns
 * a value rather than a transport response: no status code, no response entity, no header. Mapping
 * the outcome onto a transport belongs to the controller.
 *
 * <p>It holds no mutable state. Every turn's working storage is a fresh {@code TurnState}, so two
 * concurrent turns cannot observe one another, and no identifier, account or balance is cached
 * between calls.
 *
 * <p>This type is deliberately <em>not</em> {@code final}. The {@code @Transactional} methods
 * declared below are advised through a CGLIB subclass proxy, and a final class cannot be
 * subclassed, so declaring this type final makes the application context fail to start with
 * {@code Cannot subclass final class}. The proxy is what applies the declared transaction
 * semantics, so the modifier and the annotation cannot both be present. The sibling services that
 * carry transactional methods are non-final for the same reason, and extension is not invited: the
 * constructor is the only way to build one, every field is final, and no method is designed to be
 * overridden.
 *
 * @see MessageCatalogService
 * @see NavigationService
 */
@Service
public class BillPaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(BillPaymentService.class);

    // ==============================================================================================
    // Program identity, from the working storage the source declares at lines 37 and 38
    // ==============================================================================================

    /** {@code WS-PGMNAME}, the program name stamped into the header and the originating field. */
    private static final String WS_PGMNAME = "COBIL00C";

    /** {@code WS-TRANID}, the transaction identifier the terminal return re-arms at line 147. */
    private static final String WS_TRANID = "CB00";

    // ==============================================================================================
    // Screen field widths
    //
    // These are terminal field widths taken from symbolic map COBIL00, not record-image offsets: the
    // fixed-width record layer and every offset it works with live in the utility package, and
    // nothing here slices a record image. A width is used only to reproduce a move into a screen
    // field, which is what makes the source's fixed-width comparisons behave identically.
    // ==============================================================================================

    /** {@code ACTIDINI}, eleven characters. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code CONFIRMI}, one character. */
    private static final int CONFIRM_WIDTH = 1;

    /** {@code ERRMSGO}, seventy-eight characters; the eighty-character work field truncates into it. */
    private static final int ERROR_MESSAGE_WIDTH = 78;

    /** {@code CURBALI}, fourteen characters: a sign, ten digits, a point and two digits. */
    private static final int BALANCE_DISPLAY_WIDTH = 14;

    /** The integer digit count of the edited balance field declared at line 56. */
    private static final int BALANCE_INTEGER_DIGITS = 10;

    /**
     * The character count of the transaction identifier.
     *
     * <p>The one width this class needs for something other than a screen field, because the
     * identifier rule requires the incremented value to be zero-filled back to it. The fill itself is
     * delegated to the module's string utilities rather than performed here.
     */
    private static final int TRANSACTION_ID_WIDTH = 16;

    // ==============================================================================================
    // Operator messages, byte exact
    //
    // Every literal below is reproduced exactly as the source emits it, including the trailing
    // ellipsis and the deliberate double space in the success text. These are an external contract:
    // operators and downstream tooling match on them, so none is reworded, re-punctuated or trimmed.
    // ==============================================================================================

    /** Line 161: the account-id field was not supplied. */
    private static final String MSG_ACCT_ID_CAN_NOT_BE_EMPTY = "Acct ID can NOT be empty...";

    /** Line 187: the confirmation field held something other than the four accepted characters. */
    private static final String MSG_INVALID_CONFIRMATION_VALUE =
            "Invalid value. Valid values are (Y/N)...";

    /** Lines 361, 392 and 425: the keyed read found no record. */
    private static final String MSG_ACCOUNT_ID_NOT_FOUND = "Account ID NOT found...";

    /** Line 368: the account read failed for a reason other than not-found. */
    private static final String MSG_UNABLE_TO_LOOKUP_ACCOUNT = "Unable to lookup Account...";

    /** Line 399: the account rewrite failed for a reason other than not-found. */
    private static final String MSG_UNABLE_TO_UPDATE_ACCOUNT = "Unable to Update Account...";

    /** Line 432: the cross-reference read failed for a reason other than not-found. */
    private static final String MSG_UNABLE_TO_LOOKUP_XREF = "Unable to lookup XREF AIX file...";

    /** Line 456: the browse could not be positioned. */
    private static final String MSG_TRANSACTION_ID_NOT_FOUND = "Transaction ID NOT found...";

    /** Lines 463 and 492: the browse or the backward read failed. */
    private static final String MSG_UNABLE_TO_LOOKUP_TRANSACTION = "Unable to lookup Transaction...";

    /** Line 201: three dots, and no space before them. */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** Line 237: emitted whenever confirmation has not been given. */
    private static final String MSG_CONFIRM_BILL_PAYMENT = "Confirm to make a bill payment...";

    /** Line 536: the insert collided with an existing key. */
    private static final String MSG_TRAN_ID_ALREADY_EXISTS = "Tran ID already exist...";

    /** Line 543: the insert failed for any other reason. */
    private static final String MSG_UNABLE_TO_ADD_TRANSACTION = "Unable to Add Bill pay Transaction...";

    /**
     * Line 527, the first fragment of the success text, carrying its own trailing space.
     *
     * <p>The source assembles the text from four fragments delimited by size, except for the
     * identifier which is delimited by space. This fragment ends with a space and the next begins with
     * one, so the assembled text carries <strong>two consecutive spaces</strong> after the first full
     * stop. That is reproduced rather than corrected, and it is recorded in the decision log.
     */
    private static final String FRAGMENT_PAYMENT_SUCCESSFUL = "Payment successful. ";

    /** Line 528, the second fragment, carrying both a leading and a trailing space. */
    private static final String FRAGMENT_TRANSACTION_ID_IS = " Your Transaction ID is ";

    /** Line 530, the fourth and final fragment. */
    private static final String FRAGMENT_SENTENCE_TERMINATOR = ".";

    /** The state of the message work field after it is blanked at lines 104, 525 and 566. */
    private static final String NO_MESSAGE = "";

    // ==============================================================================================
    // Screen field identifiers and the property names a consumer binds to
    // ==============================================================================================

    /** The map field the cursor returns to at lines 115, 163, 203, 363, 394, 427, 458, 465, 494,
     * 538, 545 and 562. */
    private static final String FIELD_ACCOUNT_ID = "ACTIDIN";

    /** The map field the cursor returns to at lines 189 and 239. */
    private static final String FIELD_CONFIRM = "CONFIRM";

    /** The property name a consumer binds an account-id fault to. */
    private static final String PROPERTY_ACCOUNT_ID = "accountId";

    /** The property name a consumer binds a confirmation fault to. */
    private static final String PROPERTY_CONFIRM = "confirm";

    // ==============================================================================================
    // The confirmation field's four accepted characters, tested in the source's own clause order
    // ==============================================================================================

    /** Line 174. */
    private static final String CONFIRM_YES_UPPER = "Y";

    /** Line 175: accepted in either case, as two separate clauses of the same arm. */
    private static final String CONFIRM_YES_LOWER = "y";

    /** Line 178. */
    private static final String CONFIRM_NO_UPPER = "N";

    /** Line 179. */
    private static final String CONFIRM_NO_LOWER = "n";

    // ==============================================================================================
    // The synthesized transaction's constant field values, lines 220 to 229
    //
    // Each is declared in the exact form the receiving field holds after the move, so that no width
    // arithmetic and no padding happens at any call site. The category code is four digits because
    // its field is four digits wide, and the source code is ten characters because its field is;
    // both stay textual, because every digit-only lexeme in this schema is a bounded variable-length
    // column and normalising either to a number would discard its leading zeros or its padding.
    // ==============================================================================================

    /** Line 220: the transaction type code. */
    private static final String TRAN_TYPE_CD_BILL_PAYMENT = "02";

    /**
     * Line 221: the category code.
     *
     * <p>The source moves the numeric literal two into a four-digit field, so the stored value is
     * four characters. It is <strong>never</strong> the single character {@code 2}.
     */
    private static final String TRAN_CAT_CD_BILL_PAYMENT = "0002";

    /**
     * Line 222: the source code, in its stored ten-character form.
     *
     * <p>The literal the source moves is eight characters and the receiving field is ten, so the
     * stored value carries two trailing spaces. They are part of the value and are never trimmed.
     */
    private static final String TRAN_SOURCE_POS_TERM = "POS TERM  ";

    /** Line 223: the description. */
    private static final String TRAN_DESC_BILL_PAYMENT_ONLINE = "BILL PAYMENT - ONLINE";

    /**
     * Line 226: the merchant identifier, in its stored nine-character form.
     *
     * <p>Textual for the same reason the category code is: the column is a bounded variable-length
     * column, so a numeric type here would be a different value.
     */
    private static final String MERCHANT_ID_BILL_PAYMENT = "999999999";

    /** Line 227: the merchant name. */
    private static final String MERCHANT_NAME_BILL_PAYMENT = "BILL PAYMENT";

    /** Line 228: the merchant city. */
    private static final String MERCHANT_CITY_NOT_APPLICABLE = "N/A";

    /** Line 229: the merchant postal code. */
    private static final String MERCHANT_ZIP_NOT_APPLICABLE = "N/A";

    /** The entity the optimistic-lock translation names when the account rewrite conflicts. */
    private static final String ENTITY_NAME_ACCOUNT = "Account";

    // ==============================================================================================
    // Timestamp and header composition
    // ==============================================================================================

    /**
     * The six-digit fraction the construction paragraph writes at line 266.
     *
     * <p>A constant rather than a formatted value, because the whole point is that the clock's own
     * sub-second reading never reaches the output.
     */
    private static final String TIMESTAMP_ZERO_FRACTION = "000000";

    /** The character count of the timestamp group declared at {@code app/cpy/CSDAT01Y.cpy} 42 to 55. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** The separator of the timestamp's date parts, from the filler items at copybook lines 44 and 46. */
    private static final char TIMESTAMP_DATE_SEPARATOR = '-';

    /**
     * The character at timestamp position 11, from the filler item at copybook line 48.
     *
     * <p>A space, and it survives the initialise at line 263 because the verb does not touch filler.
     */
    private static final char TIMESTAMP_DATE_TIME_SEPARATOR = ' ';

    /** The separator of the timestamp's time parts, from the filler items at copybook lines 50 and 52. */
    private static final char TIMESTAMP_TIME_SEPARATOR = ':';

    /** The character at timestamp position 20, from the filler item at copybook line 54. */
    private static final char TIMESTAMP_FRACTION_SEPARATOR = '.';

    /** The separator of the header date assembled at lines 328 to 332. */
    private static final char HEADER_DATE_SEPARATOR = '/';

    /** The separator of the header time assembled at lines 334 to 338. */
    private static final char HEADER_TIME_SEPARATOR = ':';

    /** The digit count of a four-digit year. */
    private static final int YEAR_WIDTH = 4;

    /** The digit count of a two-digit header or timestamp part. */
    private static final int PART_WIDTH = 2;

    /**
     * The offset the header takes the two-digit year from, reproducing the reference modification
     * {@code WS-CURDATE-YEAR(3:2)} at line 330.
     */
    private static final int YEAR_SHORT_FORM_OFFSET = 2;

    // ==============================================================================================
    // Characters and values the source's own tests use
    // ==============================================================================================

    /** The character a blank field position holds. */
    private static final char SPACE = ' ';

    /**
     * The character a field position the terminal did not transmit holds.
     *
     * <p>The source's combined relations at lines 116, 159, 183 and 199 name low values alongside
     * spaces, so both are tested.
     */
    private static final char LOW_VALUE = '\u0000';

    /** The decimal point of a plain decimal rendering. */
    private static final char DECIMAL_POINT = '.';

    /** The sign character an edited field carries for a non-negative value. */
    private static final char PLUS_SIGN = '+';

    /** The sign character an edited field carries for a negative value. */
    private static final char MINUS_SIGN = '-';

    /**
     * The seed the backward read's end-of-file arm writes into the key at line 488.
     *
     * <p>Zero, so that adding one at line 217 yields the first identifier on an empty table.
     */
    private static final long TRANSACTION_ID_EOF_SEED = 0L;

    /**
     * The browse-positioning key the move at line 212 writes: the high-value figurative constant across
     * the whole sixteen-character key.
     *
     * <p>The figurative constant is the highest value in the collating sequence, so a browse positioned
     * at it sits past every record and the backward read below it lands on the highest identifier
     * present. The character used here is the highest the platform has, which preserves that ordering
     * property exactly. It is a sentinel and never a stored value: no identifier is ever written from it,
     * because the backward read replaces the key before the increment reads it.
     */
    private static final String HIGH_VALUES_TRANSACTION_KEY =
            String.valueOf(Character.MAX_VALUE).repeat(TRANSACTION_ID_WIDTH);

    // ==============================================================================================
    // The four two-state condition-name groups, as enumerations with predicate methods
    //
    // Each group is a one-character work flag carrying two condition names. Modelling them as
    // enumerations rather than as characters turns the source's SET-to-true statements into
    // assignments and its condition tests into predicate calls, so a flag becomes type-checked state
    // that cannot be compared against a character the source never uses.
    // ==============================================================================================

    /**
     * {@code WS-ERR-FLG} and its two condition names, declared at lines 43 to 45.
     *
     * <p>Cleared at line 101 and raised by every failure site. This is the gate that the three
     * successive tests at lines 169, 197 and 208 read, which is what makes a failure earlier in the
     * enter-key paragraph suppress every later stage without any jump.
     */
    public enum ErrorFlag {

        /** {@code ERR-FLG-ON}, line 44. */
        ON,

        /** {@code ERR-FLG-OFF}, line 45. */
        OFF;

        /**
         * Reproduces the condition name {@code ERR-FLG-ON}.
         *
         * @return {@code true} when the flag is raised
         */
        public boolean isOn() {
            return this == ON;
        }

        /**
         * Reproduces the condition name {@code ERR-FLG-OFF}, and therefore the negated test
         * {@code IF NOT ERR-FLG-ON} at lines 169, 197 and 208.
         *
         * @return {@code true} when the flag is clear
         */
        public boolean isOff() {
            return this == OFF;
        }
    }

    /**
     * {@code WS-USR-MODIFIED} and its two condition names, declared at lines 48 to 50.
     *
     * <p>Set to its no state at line 102 and never tested anywhere in the member: the field name
     * appears exactly once in the source, at its declaration. It is modelled because the source
     * declares and sets it, and because omitting a flag the program maintains would lose a
     * traceability row; nothing here branches on it, and nothing should be made to.
     */
    public enum UserModifiedFlag {

        /** {@code USR-MODIFIED-YES}, line 49. Unreachable in this member. */
        YES,

        /** {@code USR-MODIFIED-NO}, line 50, the state line 102 sets. */
        NO;

        /**
         * Reproduces the condition name {@code USR-MODIFIED-YES}.
         *
         * @return {@code true} when the operator is recorded as having modified the screen
         */
        public boolean isYes() {
            return this == YES;
        }

        /**
         * Reproduces the condition name {@code USR-MODIFIED-NO}.
         *
         * @return {@code true} when no modification is recorded
         */
        public boolean isNo() {
            return this == NO;
        }
    }

    /**
     * {@code WS-CONF-PAY-FLG} and its two condition names, declared at lines 51 to 53 with an initial
     * value of no.
     *
     * <p>Reset to its no state at line 156 on every pass of the enter-key paragraph, raised to its yes
     * state only by the affirmative arm at line 176, and tested at line 210 to decide between making
     * the payment and prompting for confirmation. Because the reset happens first, an affirmative
     * answer has to arrive on the very turn that performs the payment; it is not remembered.
     */
    public enum ConfirmPaymentFlag {

        /** {@code CONF-PAY-YES}, line 52. */
        YES,

        /** {@code CONF-PAY-NO}, line 53, and the declared initial value. */
        NO;

        /**
         * Reproduces the condition name {@code CONF-PAY-YES}, the test at line 210.
         *
         * @return {@code true} when the operator has confirmed on this turn
         */
        public boolean isYes() {
            return this == YES;
        }

        /**
         * Reproduces the condition name {@code CONF-PAY-NO}.
         *
         * @return {@code true} when the operator has not confirmed on this turn
         */
        public boolean isNo() {
            return this == NO;
        }
    }

    /**
     * {@code CDEMO-CB00-NEXT-PAGE-FLG} and its two condition names, from the program-local extension
     * to the communication area at lines 68 to 70, with an initial value of no.
     *
     * <p>Declared and never tested. The extension at lines 64 to 72 also carries a first and a last
     * transaction identifier, a page number and a selection flag, all of which belong to a paging
     * conversation this member does not have: it declares no row table, no page size and no browse
     * over transactions for display. The one extension field the source actually reads is the selected
     * value at line 116, and it is read as an account identifier. No paging behaviour is introduced
     * here, because the member has none to reproduce.
     */
    public enum NextPageFlag {

        /** {@code NEXT-PAGE-YES}, line 69. Unreachable in this member. */
        YES,

        /** {@code NEXT-PAGE-NO}, line 70, and the declared initial value. */
        NO;

        /**
         * Reproduces the condition name {@code NEXT-PAGE-YES}.
         *
         * @return {@code true} when a further page is available
         */
        public boolean isYes() {
            return this == YES;
        }

        /**
         * Reproduces the condition name {@code NEXT-PAGE-NO}.
         *
         * @return {@code true} when no further page is available
         */
        public boolean isNo() {
            return this == NO;
        }
    }

    /**
     * The four arms of the confirmation-field evaluation at lines 173 to 191, as a selector.
     *
     * <p>The source construct evaluates one field against six literals grouped into three arms plus a
     * catch-all, and the language stops at the first matching clause. Deriving a selector in exactly
     * that order and switching over it preserves the ordering explicitly, which a chain of equality
     * tests would leave implicit.
     */
    private enum ConfirmationSelection {

        /** Lines 174 and 175: the affirmative characters, in either case. */
        YES,

        /** Lines 178 and 179: the negative characters, in either case. */
        NO,

        /** Lines 182 and 183: the field was not supplied. */
        BLANK,

        /** Line 185: anything else. */
        OTHER
    }

    // ==============================================================================================
    // The screen contract, as values
    // ==============================================================================================

    /**
     * One inbound turn of the bill-payment screen: the two transmitted fields of input map
     * {@code COBIL0AI}, the attention key that arrived, and the navigation state the client echoes in
     * place of the legacy communication area.
     *
     * <p>Both fields may be absent. A screen field the terminal did not transmit arrives as low values
     * rather than as spaces, and an omitted component of a request is the same state, which is why the
     * blankness tests treat the two identically. Bounding each value to its declared screen width is
     * the receive paragraph's job at line 306, not the caller's.
     *
     * <p>The attention key arrives already decoded. This member does not include the attention-key
     * copybook and has no key-translation collaborator, so the decoding is the transport's, and an
     * absent key reaches the same catch-all arm as an unmapped one - which is what the source's own
     * catch-all at line 138 does.
     *
     * @param accountId         {@code ACTIDINI}, the account whose balance is to be paid, tested at
     *                          lines 159 and 199
     * @param confirm           {@code CONFIRMI}, the one-character confirmation the evaluation at line
     *                          173 reads
     * @param keyAction         the decoded attention key, or {@code null} when none was decoded
     * @param navigationContext the echoed navigation state, or {@code null} for a turn carrying none,
     *                          which is the analogue of a zero-length communication area at line 107
     */
    public record BillPaymentScreenInput(String accountId,
                                         String confirm,
                                         KeyAction keyAction,
                                         NavigationContext navigationContext) {
    }

    /**
     * The screen header the header paragraph populates at lines 319 to 338.
     *
     * <p>The two titles keep the catalogue's contractual width and the outbound message field keeps
     * the seventy-eight character width of {@code ERRMSGO}, because the move at line 293 truncates the
     * eighty-character work field into it. Nothing here is trimmed.
     *
     * @param title01         the first screen title, moved at line 323
     * @param title02         the second screen title, moved at line 324
     * @param transactionName the transaction identifier, moved at line 325
     * @param programName     the program name, moved at line 326
     * @param currentDate     the header date as two-digit month, day and year, assembled at lines 328
     *                        to 332
     * @param currentTime     the header time as two-digit hours, minutes and seconds, assembled at
     *                        lines 334 to 338
     * @param errorMessage    the outbound message field, seventy-eight characters, as moved at line
     *                        293
     */
    public record ScreenHeader(String title01,
                               String title02,
                               String transactionName,
                               String programName,
                               String currentDate,
                               String currentTime,
                               String errorMessage) {
    }

    /**
     * The three screen fields as they stand when the turn ends, each at its declared width.
     *
     * <p>This is what the operator sees echoed back, and it matters because the reset paragraph at
     * line 560 blanks all three: a successful payment and a declined confirmation both return a
     * cleared screen, while every error path returns the values that were transmitted.
     *
     * @param accountId      {@code ACTIDINI}, eleven characters
     * @param currentBalance {@code CURBALI}, fourteen characters in the edited form the field declared
     *                       at line 56 imposes: a sign, ten zero-filled integer digits, a point and
     *                       two fraction digits. This is the <strong>pre-payment</strong> balance,
     *                       because the move at lines 193 and 194 happens before any deduction
     * @param confirm        {@code CONFIRMI}, one character
     */
    public record ScreenFields(String accountId,
                               String currentBalance,
                               String confirm) {
    }

    /**
     * The transaction the payment created, projected out of the entity so nothing managed escapes the
     * transaction that created it.
     *
     * <p>The thirteen components are the thirteen named fields of the 350-byte record layout, in
     * layout order. The twenty-byte trailing filler is not a field and is deliberately absent. Every
     * digit-only component stays textual, because each is a bounded variable-length column whose
     * leading zeros and padding are contractual.
     *
     * @param tranId      the sixteen-character identifier minted at lines 216 to 219
     * @param tranTypeCd  the type code, moved at line 220
     * @param tranCatCd   the four-character category code, moved at line 221
     * @param tranSource  the ten-character source code, moved at line 222, carrying two trailing
     *                    spaces
     * @param tranDesc    the description, moved at line 223
     * @param tranAmt     the amount, moved at line 224: the account's full pre-payment balance, at
     *                    scale two
     * @param merchantId  the nine-character merchant identifier, moved at line 226
     * @param merchantName the merchant name, moved at line 227
     * @param merchantCity the merchant city, moved at line 228
     * @param merchantZip the merchant postal code, moved at line 229
     * @param tranCardNum the cross-referenced card number, moved at line 225
     * @param tranOrigTs  the origination timestamp, moved at line 231
     * @param tranProcTs  the processing timestamp, moved at line 232 - the <strong>same</strong> value
     *                    as the origination timestamp, because one move has two receiving fields
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
     * The account as the turn leaves it, projected out of the entity.
     *
     * <p>Carries the <strong>post-payment</strong> balance: the value the computation at line 234
     * produced and the update at line 235 stored. Because the amount paid is the full pre-payment
     * balance, a completed payment leaves this at exactly zero. The screen, by contrast, shows the
     * pre-payment balance, which is why the two figures are carried separately.
     *
     * @param acctId      the eleven-character account identifier
     * @param acctCurrBal the balance after the payment, at scale two; exactly zero after a completed
     *                    payment
     * @param version     the provider-managed version the successful write left behind, which is what
     *                    the next writer's optimistic check will compare against
     */
    public record AccountProjection(String acctId,
                                    BigDecimal acctCurrBal,
                                    long version) {
    }

    /**
     * The outcome of one turn of the bill-payment screen.
     *
     * <p>A value and not a transport response: it carries no status code, no response entity and no
     * header, because the legacy program's outcome is a screen plus a communication area and the
     * mapping onto a transport belongs to the controller.
     *
     * @param route                   the destination the turn leads to. This screen's own destination
     *                                whenever the turn re-presents the screen, because the return at
     *                                lines 146 to 149 re-arms this same transaction; the resolved
     *                                destination on the two transfer paths at lines 109 and 135. Never
     *                                {@code null}
     * @param navigationContext       the navigation state the turn hands back, standing in for the
     *                                communication area that both the return and the transfer carry.
     *                                Never {@code null}
     * @param reArmedTransactionId    the transaction identifier the turn re-armed at line 147; empty on
     *                                the two transfer paths, which transfer control instead of
     *                                returning. Never {@code null}
     * @param transaction             the transaction the payment created, or {@code null} on every turn
     *                                that created none - which is every turn that did not reach the
     *                                affirmative arm at line 210 with the error flag clear
     * @param account                 the account as the turn left it, with the post-payment balance, or
     *                                {@code null} when no account was read or none was updated
     * @param screenBalance           the <strong>pre-payment</strong> balance the move at lines 193 and
     *                                194 placed on the screen, at scale two, or {@code null} when the
     *                                turn never reached that move. Deliberately distinct from the
     *                                account projection's balance: the operator sees the balance before
     *                                the deduction, and displaying the post-payment figure instead
     *                                would be a different screen
     * @param message                 the summary message, byte exact and authored rather than padded.
     *                                Where more than one thing went wrong this is the <em>first</em>
     *                                failure's text, which is the text the legacy screen showed, and it
     *                                is empty where the source sets none. Never {@code null}
     * @param messageHighlightedGreen {@code true} only where the source recolours the message field at
     *                                line 526, which is the successful-payment path
     * @param focusField              the screen field the cursor is positioned on, from the
     *                                corresponding move of minus one into that field's length item.
     *                                Never {@code null}
     * @param errorFlag               the state of {@code WS-ERR-FLG}. The explicit flag rather than an
     *                                inference from the message, because the negative-confirmation arm
     *                                at lines 180 and 181 raises the flag and leaves no message at all
     * @param confirmationState       the state of {@code WS-CONF-PAY-FLG} when the turn ended, which
     *                                distinguishes a payment that was confirmed from one that was only
     *                                prompted for. Never {@code null}
     * @param reEnterGateSet          {@code true} once the re-enter flag has been set at line 113, so a
     *                                consumer can tell a first entry from a re-submission. The gate is
     *                                also what the module's field-level error decoration is conditioned
     *                                on elsewhere
     * @param fieldErrors             one entry per field the turn faulted, in the order the source
     *                                checks them, distinguishing a field that was not supplied from one
     *                                supplied wrongly. Unmodifiable and never {@code null}
     * @param header                  the screen header, as the header paragraph populated it
     * @param screen                  the three screen fields as the turn leaves them
     */
    public record BillPaymentResult(NavigationService.Route route,
                                    NavigationContext navigationContext,
                                    String reArmedTransactionId,
                                    TransactionProjection transaction,
                                    AccountProjection account,
                                    BigDecimal screenBalance,
                                    String message,
                                    boolean messageHighlightedGreen,
                                    String focusField,
                                    boolean errorFlag,
                                    ConfirmPaymentFlag confirmationState,
                                    boolean reEnterGateSet,
                                    List<ValidationException.FieldError> fieldErrors,
                                    ScreenHeader header,
                                    ScreenFields screen) {

        /**
         * Reports whether this turn completed a payment.
         *
         * @return {@code true} when no error was raised, the operator confirmed, and a transaction was
         *         written
         */
        public boolean paymentAccepted() {
            return !this.errorFlag && this.confirmationState.isYes() && this.transaction != null;
        }
    }

    // ==============================================================================================
    // Collaborators, all constructor injected. The bean holds no other state.
    // ==============================================================================================

    /** The transaction master, replacing the keyed file the source names at line 40. */
    private final TransactionRepository transactionRepository;

    /** The account master, replacing the keyed file the source names at line 41. */
    private final AccountRepository accountRepository;

    /**
     * The cross-reference access path, replacing the alternate index the source names at line 42.
     *
     * <p>The alternate key is non-unique, so this path resolves to the first matching row rather than
     * to a unique one, and an absent result is the analogue of the legacy not-found response.
     */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /** The common-message catalogue supplying the invalid-key text and the two screen titles. */
    private final MessageCatalogService messageCatalogService;

    /** The navigation rules replacing the transfer-control dispatch at lines 281 to 284. */
    private final NavigationService navigationService;

    /**
     * The clock standing in for the system time requests at lines 251 and 321.
     *
     * <p>A JDK abstraction injected as a bean, which is what makes the zero-fraction timestamp
     * contract testable: a clock carrying a non-zero sub-second reading must still produce a rendered
     * fraction of six zeros.
     */
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param transactionRepository        the transaction master; mandatory
     * @param accountRepository            the account master; mandatory
     * @param cardCrossReferenceRepository the cross-reference access path; mandatory
     * @param messageCatalogService        the common-message catalogue; mandatory
     * @param navigationService            the navigation rules; mandatory
     * @param clock                        the clock the timestamp and the screen header read;
     *                                     mandatory
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public BillPaymentService(final TransactionRepository transactionRepository,
            final AccountRepository accountRepository,
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final MessageCatalogService messageCatalogService,
            final NavigationService navigationService,
            final Clock clock) {
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.accountRepository =
                Objects.requireNonNull(accountRepository, "accountRepository must not be null");
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.messageCatalogService = Objects.requireNonNull(messageCatalogService,
                "messageCatalogService must not be null");
        this.navigationService =
                Objects.requireNonNull(navigationService, "navigationService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ==============================================================================================
    // Entry point: the procedure division, lines 98 to 149
    // ==============================================================================================

    /**
     * Runs one turn of the bill-payment screen.
     *
     * <p>This is the procedure division. It establishes the working storage the source declares at
     * lines 36 to 72, runs the main paragraph, and then performs the terminal return at lines 146 to
     * 149 by re-arming the transaction. Nothing is retained between calls, so two concurrent turns are
     * wholly independent.
     *
     * <h2>Why the whole turn is one transaction</h2>
     *
     * <p>The transactional boundary spans the entire turn because the legacy unit of work is the task,
     * and because the identifier rule requires it: the maximum existing key is read, incremented and
     * inserted without any other writer being able to interleave a commit between the read and the
     * insert. Splitting the read from the write - or caching the value read - would let two concurrent
     * payments observe the same maximum and mint the same identifier. The boundary also covers the
     * account rewrite, so a conflict on that rewrite rolls the inserted transaction back with it,
     * which is the behaviour the estate's single explicit rollback expresses on the online update path.
     *
     * <p>The turns that touch no data at all - a transfer, a cleared screen, an unmapped key - run
     * inside the same boundary and commit nothing, which costs a boundary and buys a single, uniform
     * rule instead of a conditional one.
     *
     * <h2>What this method throws</h2>
     *
     * <p>Every outcome the source expresses as a screen message is returned as a value, not raised:
     * the not-found arms, the lookup failures, the duplicate key and both validation failures all
     * re-present the screen with a message, exactly as the legacy does, and none of them abends. This
     * member declares no abend handler, and none is wired.
     *
     * <p>The one failure that does propagate is a concurrent modification of the account, which
     * surfaces as {@code OptimisticLockConflictException} so that the caller can offer the operator a
     * retry. That is recoverable and explicitly non-abending - see
     * {@link #updateAcctdatFile(TurnState)}.
     *
     * @param input the transmitted screen, the decoded attention key and the echoed navigation state;
     *              must not be {@code null}
     * @return the outcome of the turn, never {@code null}
     * @throws NullPointerException            if {@code input} is {@code null}
     * @throws OptimisticLockConflictException if another writer changed the account between this
     *                                         turn's read and its write
     */
    @Transactional
    public BillPaymentResult processBillPayment(final BillPaymentScreenInput input) {
        Objects.requireNonNull(input, "input must not be null");

        final TurnState state = new TurnState();
        mainPara(state, input);

        // EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA) at lines 146 to 149. The
        // main paragraph's two transfer paths have already left the program, and re-arming is
        // idempotent, so this reproduces the unconditional return without overriding a transfer.
        returnToCics(state);

        if (state.screenSends > 1) {
            // Source oddity, recorded in the decision log: on the confirmed-payment path the screen is
            // sent twice, once inside the write paragraph at line 532 and once at line 242. A terminal
            // send is idempotent there because the second send re-presents the state the first one
            // left, so the operator sees one screen either way. Over a request-response transport the
            // turn yields exactly ONE response, assembled from the final state.
            LOG.debug("Bill-payment turn sent the screen {} times; the source sends twice on the"
                    + " confirmed-payment path, at lines 532 and 242, and one response is returned",
                    state.screenSends);
        }

        // The structured replacement for the diagnostic display channel the legacy tier relies on. It
        // reports the end state of all four of the program's work flags, including the two the source
        // declares and never tests, because a diagnostic that omitted them would not describe the
        // program's storage. No identifier, card number or balance is logged.
        LOG.debug("Bill-payment turn complete: route={} errorFlag={} confirmation={} userModified={}"
                        + " nextPage={} transactionWritten={} fieldErrors={} screenSends={}",
                state.route.getRouteValue(), state.errorFlag, state.confirmPayFlag,
                state.userModifiedFlag, state.nextPageFlag, state.createdTransaction != null,
                state.fieldErrors.size(), state.screenSends);
        return state.toResult();
    }

    // ==============================================================================================
    // MAIN-PARA, line 99
    // ==============================================================================================

    /**
     * The main paragraph at line 99.
     *
     * <p>This is the shared structure of all seventeen online programs, and this member is where it was
     * verified. It clears the error flag and the user-modified flag and blanks both message fields at
     * lines 101 to 105; routes a turn carrying no communication area to the sign-on program at lines
     * 107 to 109; otherwise adopts the inbound communication area at line 111 and branches on the
     * re-enter gate at line 112. A first entry sets the gate, blanks the outbound map, positions the
     * cursor and sends. A re-entry receives the screen and dispatches on the attention key.
     *
     * <p><strong>A first entry is not always a blank send.</strong> Lines 116 to 121 test the
     * program-local extension's selected value and, when it carries one, move it into the account-id
     * field and run the enter-key paragraph before the send. So a caller that nominates an account
     * reaches the payment logic on the very first turn. Omitting that branch would silently make the
     * nominated-account entry path dead.
     *
     * <p><strong>The attention-key evaluation at lines 125 to 142 has exactly four arms</strong> - the
     * enter key, the third program-function key, the fourth program-function key, and a catch-all.
     * Clause order is preserved and the catch-all becomes the default arm, which a key that was never
     * decoded also reaches, because an absent key is none of the three the source names.
     *
     * <p><strong>Only this paragraph returns.</strong> The send paragraph at line 289 sends the map and
     * hands control back to its caller; it does not end the task. That separation is load bearing: a
     * failure arm sends the screen and then <em>continues</em>, and it is the error flag, tested again
     * at lines 169, 197 and 208, that suppresses the later stages. Collapsing the send into a
     * terminating operation would change which later statements run.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen and echoed navigation state
     */
    private void mainPara(final TurnState state, final BillPaymentScreenInput input) {
        // SET ERR-FLG-OFF TO TRUE at line 101 and SET USR-MODIFIED-NO TO TRUE at line 102; MOVE SPACES
        // TO WS-MESSAGE and ERRMSGO at lines 104 and 105. The working storage is constructed in
        // exactly that condition, so no separate clearing step is needed.

        if (isNavigationStateAbsent(input.navigationContext())) {
            // IF EIBCALEN = 0 at line 107, then MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM at line 108. The
            // destination is the one the navigation rules hold for a turn carrying no state, so no
            // program name is written as a literal here.
            state.context = withNominatedProgram(NavigationContext.empty(),
                    navigationService.resolveAbsentContextRoute().getLegacyProgramName());
            returnToPrevScreen(state);
            return;
        }

        // MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA at line 111.
        state.context = input.navigationContext();

        if (state.context.firstEntry()) {
            // IF NOT CDEMO-PGM-REENTER at line 112: SET CDEMO-PGM-REENTER TO TRUE at line 113, MOVE
            // LOW-VALUES TO COBIL0AO at line 114 and MOVE -1 TO ACTIDINL at line 115. The outbound map
            // is assembled from this state, which is blank on a first entry, so clearing it needs no
            // separate step.
            state.context = state.context.withReEntry();
            state.reEnterGateSet = true;
            state.focusField = FIELD_ACCOUNT_ID;

            // IF CDEMO-CB00-TRN-SELECTED NOT = SPACES AND LOW-VALUES at lines 116 and 117.
            //
            // The program-local extension at lines 64 to 72 has no counterpart in the shared
            // communication area, and the one field this member reads from it holds an account
            // identifier: line 118 moves it straight into the account-id screen field. The navigation
            // state's own account identifier is therefore its carrier, which keeps the nomination in
            // client-echoed state rather than inventing server state for it. The extension's remaining
            // fields drive a paging conversation this member does not have.
            final String nominatedAccount = state.context.accountId();
            if (isSupplied(nominatedAccount)) {
                // MOVE CDEMO-CB00-TRN-SELECTED TO ACTIDINI at lines 118 and 119. The sending field is
                // sixteen characters and the receiving field eleven, so the move truncates on the
                // right.
                state.accountIdInput = moveToField(nominatedAccount, ACCOUNT_ID_WIDTH);
                processEnterKey(state);
            }

            // PERFORM SEND-BILLPAY-SCREEN at line 122, outside the test above and therefore reached
            // whether or not an account was nominated.
            sendBillpayScreen(state);
            return;
        }

        // PERFORM RECEIVE-BILLPAY-SCREEN at line 124.
        receiveBillpayScreen(state, input);

        // EVALUATE EIBAID at lines 125 to 142.
        switch (input.keyAction()) {
            case ENTER -> processEnterKey(state);
            case PFK03 -> {
                // Lines 129 to 134: the originating-program field names the destination when it carries
                // one, and this screen's own default - the user menu - applies when it does not. Both
                // arms are the navigation rules' back-navigation rule, so neither program name appears
                // here as a literal.
                final NavigationService.Route destination = navigationService
                        .resolveBackNavigation(carriedState(state.context), NavigationService.Route.USER_MENU);
                state.context =
                        withNominatedProgram(state.context, destination.getLegacyProgramName());
                returnToPrevScreen(state);
            }
            case PFK04 -> clearCurrentScreen(state);
            case null, default -> {
                // Lines 138 to 141. The catalogue message is carried at its full contractual
                // fifty-character width and is deliberately not trimmed.
                state.errorFlag = ErrorFlag.ON;
                state.setMessage(messageCatalogService.invalidKeyMessage());
                sendBillpayScreen(state);
            }
        }
    }

    // ==============================================================================================
    // PROCESS-ENTER-KEY, line 154
    // ==============================================================================================

    /**
     * The enter-key paragraph at lines 154 to 244.
     *
     * <p>Four stages, separated by three successive tests of the error flag at lines 169, 197 and 208.
     * A failure in any stage raises the flag, sends the screen and then <em>returns to this
     * paragraph</em>, and it is the next flag test - not a jump - that suppresses the stages that
     * follow. Reproducing the flag tests rather than returning early is what keeps that behaviour, and
     * it is why the message is written by a plain assignment at every site: the source uses an
     * unconditional move, so where a turn writes more than one message the last one written is the one
     * the operator sees.
     *
     * <ol>
     *   <li>Lines 158 to 167 - the account-id field must be supplied.</li>
     *   <li>Lines 169 to 195 - adopt the key, evaluate the confirmation field, read the account, and
     *       place the balance on the screen.</li>
     *   <li>Lines 197 to 206 - the nothing-to-pay rejection.</li>
     *   <li>Lines 208 to 244 - make the payment, or prompt for confirmation; then send.</li>
     * </ol>
     *
     * <p><strong>The balance the screen shows is the balance before the deduction.</strong> Lines 193
     * and 194 move the account's current balance into the edited work field and then onto the map,
     * and they do so <em>before</em> the payment stage runs. Showing the post-payment figure instead
     * would be a different screen, so the two balances are tracked separately throughout.
     *
     * <p><strong>Lines 193 and 194 run even when the confirmation evaluation failed.</strong> They sit
     * inside the block the test at line 169 opened, so the flag state at that point - not the state
     * afterwards - decides whether they execute. A read that found nothing, and the negative
     * confirmation arm that reads nothing at all, therefore both still write the balance field.
     *
     * <p><strong>The payment stage does not re-test the flag between its steps.</strong> Lines 211 to
     * 235 run as a straight sequence, so a failed cross-reference read or a failed browse does not stop
     * the record from being assembled, the write from being attempted, the balance from being computed
     * or the account from being rewritten. That is the source's own behaviour and it is reproduced
     * rather than corrected; the consequences are handled where the source handles them - in the write
     * paragraph's catch-all arm - and the finding is recorded in the decision log.
     *
     * @param state the turn's working storage
     */
    private void processEnterKey(final TurnState state) {
        // SET CONF-PAY-NO TO TRUE at line 156. The reset happens on every pass, so an affirmative
        // answer only counts on the turn that carries it; confirmation is never remembered.
        state.confirmPayFlag = ConfirmPaymentFlag.NO;

        // EVALUATE TRUE at lines 158 to 167. The construct evaluates an independent condition rather
        // than a selector, and its catch-all arm at lines 165 and 166 continues without doing anything,
        // so the faithful Java form is a single guarded block and not a switch.
        if (!isSupplied(state.accountIdInput)) {
            // Lines 160 to 164.
            state.errorFlag = ErrorFlag.ON;
            state.setMessage(MSG_ACCT_ID_CAN_NOT_BE_EMPTY);
            state.focusField = FIELD_ACCOUNT_ID;
            state.recordFieldError(PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                    ValidationException.FieldState.MISSING, MSG_ACCT_ID_CAN_NOT_BE_EMPTY);
            sendBillpayScreen(state);
        }

        // IF NOT ERR-FLG-ON at line 169.
        if (state.errorFlag.isOff()) {
            // MOVE ACTIDINI TO ACCT-ID and XREF-ACCT-ID at lines 170 and 171: one move with two
            // receiving fields, so the account read and the cross-reference read are keyed by the same
            // transmitted value.
            state.acctIdKey = state.accountIdInput;
            state.xrefAcctIdKey = state.accountIdInput;

            // EVALUATE CONFIRMI at lines 173 to 191. A selector evaluation of one field against six
            // literals in three arms plus a catch-all, so clause order is preserved by deriving the arm
            // in the source's own order and the catch-all becomes the default arm.
            switch (confirmationSelection(state.confirmInput)) {
                case YES -> {
                    // Lines 174 to 177.
                    state.confirmPayFlag = ConfirmPaymentFlag.YES;
                    readAcctdatFile(state);
                }
                case NO -> {
                    // Lines 178 to 181. The screen is cleared and sent first, and only then is the flag
                    // raised, which is what leaves this path with a blank screen and no message at all.
                    clearCurrentScreen(state);
                    state.errorFlag = ErrorFlag.ON;
                }
                case BLANK -> {
                    // Lines 182 to 184: the account is read so the balance can be displayed, but no
                    // payment is made, because the confirmation flag is still in its no state.
                    readAcctdatFile(state);
                }
                default -> {
                    // WHEN OTHER at lines 185 to 190.
                    state.errorFlag = ErrorFlag.ON;
                    state.setMessage(MSG_INVALID_CONFIRMATION_VALUE);
                    state.focusField = FIELD_CONFIRM;
                    state.recordFieldError(PROPERTY_CONFIRM, FIELD_CONFIRM,
                            ValidationException.FieldState.INVALID, MSG_INVALID_CONFIRMATION_VALUE);
                    sendBillpayScreen(state);
                }
            }

            // MOVE ACCT-CURR-BAL TO WS-CURR-BAL at line 193, then MOVE WS-CURR-BAL TO CURBALI at line
            // 194: the pre-payment balance, in the edited form the field declared at line 56 imposes.
            state.screenBalance = currentBalanceOfRecord(state);
            state.balanceDisplayField = editedBalance(state.screenBalance);
        }

        // IF NOT ERR-FLG-ON at line 197.
        if (state.errorFlag.isOff()) {
            // Lines 198 and 199: BOTH conditions must hold. A non-positive balance alone is not enough
            // - the account-id field must also be neither spaces nor low values - so a turn carrying an
            // empty account-id field never reaches this rejection. It cannot in practice, because the
            // first stage already faulted it, but the guard is the source's and is reproduced as
            // written.
            if (currentBalanceOfRecord(state).signum() <= 0 && isSupplied(state.accountIdInput)) {
                // Lines 200 to 204.
                state.errorFlag = ErrorFlag.ON;
                state.setMessage(MSG_NOTHING_TO_PAY);
                state.focusField = FIELD_ACCOUNT_ID;
                state.recordFieldError(PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                        ValidationException.FieldState.INVALID, MSG_NOTHING_TO_PAY);
                sendBillpayScreen(state);
            }
        }

        // IF NOT ERR-FLG-ON at line 208.
        if (state.errorFlag.isOff()) {
            if (state.confirmPayFlag.isYes()) {
                makeBillPayment(state);
            } else {
                // Lines 236 to 240. No error flag is raised here: the turn simply asks again.
                state.setMessage(MSG_CONFIRM_BILL_PAYMENT);
                state.focusField = FIELD_CONFIRM;
            }

            // PERFORM SEND-BILLPAY-SCREEN at line 242, outside the branch above and therefore
            // unconditional within this block. On the confirmed path the write paragraph has already
            // sent once at line 532, which is the source's double send.
            sendBillpayScreen(state);
        }
    }

    /**
     * The confirmed-payment sequence at lines 210 to 235.
     *
     * <p>Extracted from the enter-key paragraph because it is one branch of one arm and reads far more
     * clearly on its own; it is not a paragraph of its own and carries no line of its own. Every
     * statement of the source's sequence is reproduced in order, and - as the source does - no error
     * flag is tested between them.
     *
     * <h2>Identifier generation, lines 212 to 219</h2>
     *
     * <p>The high-value sentinel is moved into the key, a browse is started at it, the file is read
     * <em>backward</em> by one record to reach the highest identifier present, the browse is ended, the
     * identifier is moved into a sixteen-digit numeric work field - {@code WS-TRAN-ID-NUM}, declared at
     * line 57 with an initial value of zeros - and one is added. The backward read's end-of-file arm at
     * line 488 writes zeros into the key, so an empty table seeds zero and the first identifier is one.
     *
     * <p><strong>The increment happens here, not in the repository.</strong> The repository supplies the
     * maximum and nothing else, and the increment and the insert share this method's transaction, which
     * is what prevents two concurrent payments from observing the same maximum.
     *
     * <p><strong>The first identifier on an empty table is the sixteen-character string
     * {@code 0000000000000001}, not {@code 1}</strong>, because the source moves a sixteen-digit numeric
     * value back into a sixteen-character alphanumeric key and such a move always produces sixteen
     * zero-padded digits. The zero fill is delegated to the module's string utilities.
     *
     * <p><strong>A textual maximum is correct and collation-independent here for one reason only:</strong>
     * every identifier is exactly sixteen zero-padded digit characters with no sign overpunch, so the
     * lexicographic and the numeric maximum coincide and every character lies in the digit range. That
     * precondition is not assumed - the backward read verifies it and takes its catch-all arm when it
     * does not hold.
     *
     * <p><strong>No sequence, no generated value, no random identifier and no cached last value.</strong>
     * A sequence never reuses a value it has issued, whereas this rule always reuses a gap, and the
     * first rollback after an identifier is consumed guarantees a gap - after which a sequence would
     * diverge from the legacy numbering for the remaining life of the table.
     *
     * @param state the turn's working storage
     */
    private void makeBillPayment(final TurnState state) {
        // PERFORM READ-CXACAIX-FILE at line 211.
        readCxacaixFile(state);

        // MOVE HIGH-VALUES TO TRAN-ID at line 212: position the browse past the last record.
        state.transactionKey = HIGH_VALUES_TRANSACTION_KEY;

        // Lines 213 to 215.
        startbrTransactFile(state);
        readprevTransactFile(state);
        endbrTransactFile(state);

        // MOVE TRAN-ID TO WS-TRAN-ID-NUM at line 216, then ADD 1 TO WS-TRAN-ID-NUM at line 217.
        state.transactionIdNumber = numericIdentifierOf(state.transactionKey);
        if (state.transactionIdNumber != null) {
            state.transactionIdNumber = state.transactionIdNumber + 1L;
        }

        // INITIALIZE TRAN-RECORD at line 218, then the field moves at lines 219 to 232. The locals are
        // assigned in the source's own move order - the card number at line 225 precedes the merchant
        // identifier at line 226 - and the record is then materialised from them, because the entity's
        // constructor takes the layout order rather than the move order.
        final String tranId = state.transactionIdNumber == null
                ? null
                : CobolStringUtils.rightJustifyZeroFill(
                        Long.toString(state.transactionIdNumber), TRANSACTION_ID_WIDTH);
        state.transactionKey = tranId;
        final String tranTypeCd = TRAN_TYPE_CD_BILL_PAYMENT;
        final String tranCatCd = TRAN_CAT_CD_BILL_PAYMENT;
        final String tranSource = TRAN_SOURCE_POS_TERM;
        final String tranDesc = TRAN_DESC_BILL_PAYMENT_ONLINE;
        final BigDecimal tranAmt = currentBalanceOfRecord(state);
        final String tranCardNum = crossReferencedCardNumber(state);
        final String merchantId = MERCHANT_ID_BILL_PAYMENT;
        final String merchantName = MERCHANT_NAME_BILL_PAYMENT;
        final String merchantCity = MERCHANT_CITY_NOT_APPLICABLE;
        final String merchantZip = MERCHANT_ZIP_NOT_APPLICABLE;

        // PERFORM GET-CURRENT-TIMESTAMP at line 230, then one move with two receiving fields at lines
        // 231 and 232, so the origination and the processing timestamp are the SAME value.
        final String timestamp = getCurrentTimestamp();
        final String tranOrigTs = timestamp;
        final String tranProcTs = timestamp;

        state.transactionRecord = new Transaction(tranId,
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

        // PERFORM WRITE-TRANSACT-FILE at line 233.
        writeTransactFile(state);

        // COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT at line 234.
        //
        // The operand order is the source's and is never rearranged: truncation makes the arithmetic
        // non-associative, and because the estate contains no rounding clause anywhere, every store
        // into a two-decimal field truncates toward zero. The scaling is delegated to the module's
        // zoned-decimal codec, which is the only place a scale is imposed and which always truncates.
        //
        // Because the amount IS the full current balance, the result is exactly zero.
        state.postPaymentBalance = ZonedDecimalCodec.toMonetaryScale(
                currentBalanceOfRecord(state).subtract(state.transactionRecord.getTranAmt()));

        // PERFORM UPDATE-ACCTDAT-FILE at line 235. Reached whether or not the write succeeded, because
        // the source tests no flag between the two.
        updateAcctdatFile(state);
    }

    // ==============================================================================================
    // GET-CURRENT-TIMESTAMP, line 249
    // ==============================================================================================

    /**
     * The timestamp paragraph at lines 249 to 267, and the module's authority for the <em>online</em>
     * 26-character timestamp.
     *
     * <p>The source asks the system for the absolute time, formats it into a ten-character
     * hyphen-separated date and an eight-character colon-separated time, initialises the timestamp
     * group, moves the date into positions 1 to 10, moves the time into positions 12 to 19, and then
     * writes zeros over the six-digit fraction at line 266.
     *
     * <p>The group is declared at {@code app/cpy/CSDAT01Y.cpy} lines 42 to 55 as a four-digit year, a
     * hyphen, a two-digit month, a hyphen, a two-digit day, <strong>a space at position 11</strong>, a
     * two-digit hour, a colon, two-digit minutes, a colon, two-digit seconds, <strong>a period at
     * position 20</strong> and a six-digit fraction - exactly 26 characters. The space and the period
     * are filler items, and the initialise verb does not touch filler, so both survive intact.
     *
     * <p><strong>The fraction is therefore always six zeros.</strong> The rendered form is invariably a
     * hyphenated date, a space, a colon-separated time, a period and {@code 000000}, whatever
     * sub-second value the clock is carrying. All three hundred seeded origination timestamps end in a
     * zero fraction, which is the same fact observed from the other side.
     *
     * <p><strong>A formatter pattern that emitted real microseconds would be a silent parity break</strong>
     * - it would compile, it would look right, and every generated timestamp would differ from the
     * legacy's in its last six characters. That is why the fraction is a constant here rather than
     * anything derived from the clock.
     *
     * <p><strong>This form is deliberately not shared with the batch tier.</strong> The batch programs
     * build a differently shaped 26-character timestamp - a hyphen before the hour, dots between the
     * time parts, two hundredths digits and four literal zeros - and that form belongs to the batch
     * services as their own private helpers. There is no shared timestamp type anywhere in the module,
     * and there must not be: emitting the batch form from an online service, or the online form from a
     * batch one, is a byte-parity break that no compiler and no type system would catch.
     *
     * @return the 26-character online timestamp
     * @throws IllegalStateException if the assembled value is not exactly 26 encoded bytes, which would
     *                               mean the composition no longer matches the declared group
     */
    private String getCurrentTimestamp() {
        // EXEC CICS ASKTIME ABSTIME(WS-ABS-TIME) at lines 251 to 253, then EXEC CICS FORMATTIME with a
        // hyphen date separator and a colon time separator at lines 255 to 261. The two work fields the
        // source formats into are declared at lines 60 and 61 and are ten and eight characters wide.
        final LocalDateTime now = LocalDateTime.now(clock);

        final String formattedDate = numericField(now.getYear(), YEAR_WIDTH)
                + TIMESTAMP_DATE_SEPARATOR
                + numericField(now.getMonthValue(), PART_WIDTH)
                + TIMESTAMP_DATE_SEPARATOR
                + numericField(now.getDayOfMonth(), PART_WIDTH);
        final String formattedTime = numericField(now.getHour(), PART_WIDTH)
                + TIMESTAMP_TIME_SEPARATOR
                + numericField(now.getMinute(), PART_WIDTH)
                + TIMESTAMP_TIME_SEPARATOR
                + numericField(now.getSecond(), PART_WIDTH);

        // INITIALIZE WS-TIMESTAMP at line 263; the date into positions 1 to 10 at line 264; the time
        // into positions 12 to 19 at line 265; and ZEROS into the six-digit fraction at line 266. The
        // separator at position 11 and the point at position 20 are filler and are written here as the
        // literals the copybook declares them to be.
        final String timestamp = formattedDate
                + TIMESTAMP_DATE_TIME_SEPARATOR
                + formattedTime
                + TIMESTAMP_FRACTION_SEPARATOR
                + TIMESTAMP_ZERO_FRACTION;

        final int encodedLength = timestamp.getBytes(StandardCharsets.US_ASCII).length;
        if (encodedLength != TIMESTAMP_WIDTH) {
            // Not a defensive nicety: the width is the contract, and a composition that drifted from it
            // would otherwise be stored and only noticed when a downstream consumer sliced the wrong
            // bytes. Encoded bytes rather than characters, because the field the value is written into
            // is measured in bytes.
            throw new IllegalStateException("the online timestamp must be exactly " + TIMESTAMP_WIDTH
                    + " encoded bytes, as declared at app/cpy/CSDAT01Y.cpy lines 42 to 55, but was "
                    + encodedLength);
        }
        return timestamp;
    }

    // ==============================================================================================
    // RETURN-TO-PREV-SCREEN, line 273
    // ==============================================================================================

    /**
     * The transfer paragraph at lines 273 to 284.
     *
     * <p>Defaults the destination to the sign-on program when nothing is nominated at lines 275 to 277,
     * stamps this screen's transaction identifier and program name as the originator at lines 278 and
     * 279, resets the program context to zeros at line 280 - which is the first-entry state, so the
     * receiving screen sees a first entry - and transfers control at lines 281 to 284.
     *
     * <p>A transfer carries the communication area but does not re-arm a transaction, so a turn that
     * takes this path reports no re-armed identifier.
     *
     * @param state the turn's working storage
     */
    private void returnToPrevScreen(final TurnState state) {
        // Lines 275 to 277, both arms, resolved by the navigation rules rather than by a literal.
        final NavigationService.Route destination = navigationService
                .resolveNominatedDestination(carriedState(state.context), NavigationService.Route.SIGN_ON);

        // MOVE WS-TRANID TO CDEMO-FROM-TRANID at line 278, MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM at
        // line 279, MOVE ZEROS TO CDEMO-PGM-CONTEXT at line 280.
        state.context = withOriginatingProgram(
                withNominatedProgram(state.context, destination.getLegacyProgramName()))
                .withFirstEntry();

        // EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA) at lines 281 to 284.
        state.route = destination;
        state.transferred = true;
        LOG.debug("Transferring control from the bill-payment screen to route {}",
                destination.getRouteValue());
    }

    // ==============================================================================================
    // SEND-BILLPAY-SCREEN, line 289
    // ==============================================================================================

    /**
     * The send paragraph at lines 289 to 301.
     *
     * <p>Populates the header, moves the eighty-character message work field into the seventy-eight
     * character outbound message field at line 293, and sends the map with the erase and cursor options
     * at lines 295 to 301.
     *
     * <p><strong>This paragraph does not return, and that separation is load bearing.</strong> Unlike
     * several of its siblings it does not end with a jump to a return paragraph: it sends the map and
     * hands control straight back to whichever paragraph performed it, which then carries on with its
     * next statement. Only the main paragraph returns, at lines 146 to 149. Making the send terminal
     * here would suppress every statement the source actually still executes after a failure - the
     * balance display at lines 193 and 194 after a failed read, and the whole payment sequence at lines
     * 212 to 235 after a failed cross-reference read - and would therefore change observable output.
     *
     * <p>The send count is tracked only so that the source's double send on the confirmed-payment path
     * can be reported once, at debug level, from the entry point. It has no bearing on the outcome: a
     * request-response transport carries exactly one response, assembled from the final state.
     *
     * @param state the turn's working storage
     */
    private void sendBillpayScreen(final TurnState state) {
        populateHeaderInfo(state);

        // MOVE WS-MESSAGE TO ERRMSGO OF COBIL0AO at line 293.
        state.errorMessageField = moveToField(state.message, ERROR_MESSAGE_WIDTH);

        state.screenSends = state.screenSends + 1;
    }

    // ==============================================================================================
    // RECEIVE-BILLPAY-SCREEN, line 306
    // ==============================================================================================

    /**
     * The receive paragraph at lines 306 to 314.
     *
     * <p>Receives the input map, which is the point at which each transmitted value is bounded to the
     * width its symbolic-map field declares: a longer value loses its excess on the right and a shorter
     * one is space filled. Every later comparison therefore works on a fixed-width value exactly as the
     * source's comparisons do. A field the client omitted arrives as low values, which the blankness
     * tests treat identically to spaces.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen
     */
    private void receiveBillpayScreen(final TurnState state, final BillPaymentScreenInput input) {
        state.accountIdInput = moveToField(input.accountId(), ACCOUNT_ID_WIDTH);
        state.confirmInput = moveToField(input.confirm(), CONFIRM_WIDTH);
    }

    // ==============================================================================================
    // POPULATE-HEADER-INFO, line 319
    // ==============================================================================================

    /**
     * The header paragraph at lines 319 to 338.
     *
     * <p>Reads the current date and time at line 321, stamps the two catalogue titles at lines 323 and
     * 324 and this screen's transaction identifier and program name at lines 325 and 326, then assembles
     * the two-digit header date at lines 328 to 332 and the two-digit header time at lines 334 to 338.
     *
     * <p>The header year is the last two characters of the four-character year, exactly as the reference
     * modification at line 330 takes it, and both separators are the ones the date and time work groups
     * declare. Both titles keep the catalogue's contractual width and neither is trimmed.
     *
     * @param state the turn's working storage
     */
    private void populateHeaderInfo(final TurnState state) {
        // MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA at line 321.
        final LocalDateTime now = LocalDateTime.now(clock);

        state.title01 = messageCatalogService.screenTitle01();
        state.title02 = messageCatalogService.screenTitle02();
        state.transactionName = WS_TRANID;
        state.programName = WS_PGMNAME;

        // Lines 328 to 332, with the year taken as WS-CURDATE-YEAR(3:2) at line 330.
        final String year = numericField(now.getYear(), YEAR_WIDTH);
        state.currentDate = numericField(now.getMonthValue(), PART_WIDTH)
                + HEADER_DATE_SEPARATOR
                + numericField(now.getDayOfMonth(), PART_WIDTH)
                + HEADER_DATE_SEPARATOR
                + year.substring(YEAR_SHORT_FORM_OFFSET);

        // Lines 334 to 338.
        state.currentTime = numericField(now.getHour(), PART_WIDTH)
                + HEADER_TIME_SEPARATOR
                + numericField(now.getMinute(), PART_WIDTH)
                + HEADER_TIME_SEPARATOR
                + numericField(now.getSecond(), PART_WIDTH);
    }

    // ==============================================================================================
    // The three response outcomes the source's file evaluations distinguish
    // ==============================================================================================

    /**
     * The arms of the response evaluations at lines 356, 387, 420, 451 and 484.
     *
     * <p>Each of those evaluations tests the same three outcomes in the same order - the normal
     * response, the not-found response, and a catch-all - so one selector serves them all and each
     * paragraph keeps its own arm bodies and its own message. The backward read substitutes an
     * end-of-file arm for the not-found arm, which is a different response with different handling and
     * is therefore a separate constant.
     */
    private enum FileResponse {

        /** The normal response: the operation succeeded. */
        NORMAL,

        /** The not-found response, and at line 487 the end-of-file response. */
        NOT_FOUND,

        /** Any other response: the catch-all arm. */
        OTHER
    }

    // ==============================================================================================
    // READ-ACCTDAT-FILE, line 343
    // ==============================================================================================

    /**
     * The account read paragraph at lines 343 to 372.
     *
     * <p>Reads the account for update by its key and evaluates the response over three arms: the normal
     * response continues at lines 357 and 358, the not-found response reports that the account
     * identifier was not found, and the catch-all reports that the account could not be looked up. Both
     * failure arms raise the error flag, position the cursor on the account-id field and send.
     *
     * <p><strong>The read-for-update intent becomes optimistic, not pessimistic.</strong> The legacy
     * read acquires an update lock, and its file definition rested on a locking update model plus the
     * program's own image comparison, with uncommitted read integrity, no recovery and no journaling.
     * The relational replacement holds a provider-managed version on the account and checks it on write,
     * under read-committed isolation. That is <strong>strictly stronger</strong> than the legacy posture
     * and is a deliberate, documented improvement rather than a regression - which is why no lock mode
     * and no pessimistic hint appears anywhere here.
     *
     * <p>The catch-all arm fires when a row was returned that cannot drive the payment, which in this
     * schema means one carrying no balance. The column is declared not-null, so a well-formed row cannot
     * reach it; the arm defends against a malformed one, and it reports the source's own text for an
     * unreadable record instead of failing with a null dereference three stages later.
     *
     * @param state the turn's working storage
     */
    private void readAcctdatFile(final TurnState state) {
        final Optional<Account> found = accountRepository.findById(state.acctIdKey);
        final FileResponse response;
        if (found.isEmpty()) {
            response = FileResponse.NOT_FOUND;
        } else if (found.get().getAcctCurrBal() == null) {
            response = FileResponse.OTHER;
        } else {
            state.account = found.get();
            response = FileResponse.NORMAL;
        }

        // EVALUATE WS-RESP-CD at lines 356 to 372, clause order preserved, catch-all as the default arm.
        switch (response) {
            case NORMAL -> {
                // CONTINUE at lines 357 and 358.
            }
            case NOT_FOUND -> {
                // Lines 359 to 364.
                state.errorFlag = ErrorFlag.ON;
                state.setMessage(MSG_ACCOUNT_ID_NOT_FOUND);
                state.focusField = FIELD_ACCOUNT_ID;
                state.recordFieldError(PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                        ValidationException.FieldState.INVALID, MSG_ACCOUNT_ID_NOT_FOUND);
                sendBillpayScreen(state);
            }
            default -> {
                // Lines 365 to 371. The source's diagnostic display of the response and reason codes
                // becomes a structured log record; the account identifier is not logged, because it is
                // an identifier.
                LOG.warn("Account read did not return a usable record: file=ACCTDAT response={}",
                        response);
                state.errorFlag = ErrorFlag.ON;
                state.setMessage(MSG_UNABLE_TO_LOOKUP_ACCOUNT);
                state.focusField = FIELD_ACCOUNT_ID;
                sendBillpayScreen(state);
            }
        }
    }

    // ==============================================================================================
    // UPDATE-ACCTDAT-FILE, line 377
    // ==============================================================================================

    /**
     * The account rewrite paragraph at lines 377 to 403.
     *
     * <p>Rewrites the account record and evaluates the response over the same three arms: the normal
     * response continues, the not-found response reports that the account identifier was not found, and
     * the catch-all reports that the account could not be updated.
     *
     * <p><strong>A concurrent modification is translated here, and it never abends.</strong> The version
     * attribute on the account is declarative and the provider raises on flush, so the rewrite is
     * flushed inside this paragraph rather than at commit - otherwise the conflict would surface after
     * this service had returned, where it could no longer be translated. The provider's failure becomes
     * a domain conflict carrying the account key, which the caller can turn into a retry. This is not
     * an abend path: the source's own single rollback on the online update path is a recoverable
     * outcome, and this member declares no abend handler at all.
     *
     * <p>The catch-all arm fires when there is a balance to store but no computation produced one, which
     * cannot arise from the source's own sequence and guards against a caller reaching this paragraph
     * out of order.
     *
     * @param state the turn's working storage
     * @throws OptimisticLockConflictException if another writer changed the account since it was read
     */
    private void updateAcctdatFile(final TurnState state) {
        final FileResponse response = rewriteAccountRecord(state);

        // EVALUATE WS-RESP-CD at lines 387 to 403, clause order preserved, catch-all as the default arm.
        switch (response) {
            case NORMAL -> {
                // CONTINUE at lines 388 and 389.
            }
            case NOT_FOUND -> {
                // Lines 390 to 395.
                state.errorFlag = ErrorFlag.ON;
                state.setMessage(MSG_ACCOUNT_ID_NOT_FOUND);
                state.focusField = FIELD_ACCOUNT_ID;
                sendBillpayScreen(state);
            }
            default -> {
                // Lines 396 to 402.
                LOG.warn("Account rewrite was not attempted: file=ACCTDAT response={}", response);
                state.errorFlag = ErrorFlag.ON;
                state.setMessage(MSG_UNABLE_TO_UPDATE_ACCOUNT);
                state.focusField = FIELD_ACCOUNT_ID;
                sendBillpayScreen(state);
            }
        }
    }

    /**
     * Performs the rewrite the account paragraph evaluates, and reports which arm it resolved to.
     *
     * <p>Separated from the arm bodies so that the paragraph above reads as the source's evaluation does.
     * The flush is deliberate and is the whole reason this is not a plain save: the provider's version
     * check has to run while this method is still on the stack.
     *
     * @param state the turn's working storage
     * @return the arm the rewrite resolved to
     * @throws OptimisticLockConflictException if the version check failed
     */
    private FileResponse rewriteAccountRecord(final TurnState state) {
        if (state.account == null) {
            return FileResponse.NOT_FOUND;
        }
        if (state.postPaymentBalance == null) {
            return FileResponse.OTHER;
        }

        state.account.setAcctCurrBal(state.postPaymentBalance);
        try {
            state.account = accountRepository.saveAndFlush(state.account);
            return FileResponse.NORMAL;
        } catch (final OptimisticLockingFailureException | OptimisticLockException conflict) {
            // The second type is caught for the case where the provider's exception is not translated;
            // the first is what a translated repository raises. Either way the outcome is one domain
            // conflict, and the account key travels with it so a caller can name the record.
            throw new OptimisticLockConflictException(
                    OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE,
                    ENTITY_NAME_ACCOUNT, state.account.getAcctId(), conflict);
        }
    }

    // ==============================================================================================
    // READ-CXACAIX-FILE, line 408
    // ==============================================================================================

    /**
     * The cross-reference read paragraph at lines 408 to 436.
     *
     * <p>Reads the cross-reference by account identifier through the alternate-index access path and
     * evaluates the response over the same three arms: the normal response continues, the not-found
     * response reports that the account identifier was not found, and the catch-all reports that the
     * cross-reference path could not be looked up.
     *
     * <p><strong>The alternate key is non-unique, so the access path resolves to the first matching row
     * rather than to a unique one.</strong> The repository exposes it as a first-match-ordered lookup
     * whose empty result is the analogue of the legacy not-found response - which is exactly what the
     * legacy read of that path does, because a keyed read over a non-unique path returns the first
     * record. A single-valued unordered lookup would instead fail the moment two rows shared an account
     * identifier, and that would be a behaviour the legacy does not have. No association is declared
     * between the two entities, here or anywhere in the module.
     *
     * <p>The catch-all arm fires when a row was returned that carries no usable card number. That value
     * is what the synthesized transaction's card number is taken from, and the schema constrains it
     * against the card master, so admitting a blank would turn an operator message into a constraint
     * failure that rolled the whole turn back.
     *
     * @param state the turn's working storage
     */
    private void readCxacaixFile(final TurnState state) {
        final Optional<CardCrossReference> found = cardCrossReferenceRepository
                .findFirstByXrefAcctIdOrderByXrefCardNumAsc(state.xrefAcctIdKey);
        final FileResponse response;
        if (found.isEmpty()) {
            response = FileResponse.NOT_FOUND;
        } else if (!isSupplied(found.get().getXrefCardNum())) {
            response = FileResponse.OTHER;
        } else {
            state.crossReference = found.get();
            response = FileResponse.NORMAL;
        }

        // EVALUATE WS-RESP-CD at lines 420 to 436, clause order preserved, catch-all as the default arm.
        switch (response) {
            case NORMAL -> {
                // CONTINUE at lines 421 and 422.
            }
            case NOT_FOUND -> {
                // Lines 423 to 428.
                state.errorFlag = ErrorFlag.ON;
                state.setMessage(MSG_ACCOUNT_ID_NOT_FOUND);
                state.focusField = FIELD_ACCOUNT_ID;
                state.recordFieldError(PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                        ValidationException.FieldState.INVALID, MSG_ACCOUNT_ID_NOT_FOUND);
                sendBillpayScreen(state);
            }
            default -> {
                // Lines 429 to 435. Neither the account identifier nor the card number is logged.
                LOG.warn("Cross-reference read did not return a usable record: file=CXACAIX response={}",
                        response);
                state.errorFlag = ErrorFlag.ON;
                state.setMessage(MSG_UNABLE_TO_LOOKUP_XREF);
                state.focusField = FIELD_ACCOUNT_ID;
                sendBillpayScreen(state);
            }
        }
    }

    // ==============================================================================================
    // STARTBR-TRANSACT-FILE, line 441
    // ==============================================================================================

    /**
     * The browse-start paragraph at lines 441 to 467.
     *
     * <p>Starts a browse of the transaction master positioned at the key the previous statement set, and
     * evaluates the response over the same three arms: the normal response continues, the not-found
     * response reports that the transaction identifier was not found, and the catch-all reports that the
     * transaction could not be looked up.
     *
     * <p><strong>In a relational store the positioning itself is not an operation that can fail.</strong>
     * The browse exists to be read backward from, and the backward read is where the maximum key is
     * actually obtained, so what this paragraph reproduces is the establishment of the browse and the
     * precondition it depends on. The two failure arms therefore guard that precondition and report the
     * source's own texts for it: with no positioning key there is nothing to position at, and with a key
     * other than the high-value sentinel the browse would not be positioned past the last record and the
     * backward read below it would return the wrong record. Neither can arise from the source's own
     * statement order, which sets the sentinel immediately before performing this paragraph; both would
     * arise from an edit that broke that order, and reporting them beats silently minting a duplicate
     * identifier.
     *
     * @param state the turn's working storage
     */
    private void startbrTransactFile(final TurnState state) {
        final FileResponse response;
        if (state.transactionKey == null) {
            response = FileResponse.NOT_FOUND;
        } else if (!HIGH_VALUES_TRANSACTION_KEY.equals(state.transactionKey)) {
            response = FileResponse.OTHER;
        } else {
            state.browseActive = true;
            response = FileResponse.NORMAL;
        }

        // EVALUATE WS-RESP-CD at lines 451 to 467, clause order preserved, catch-all as the default arm.
        switch (response) {
            case NORMAL -> {
                // CONTINUE at lines 452 and 453.
            }
            case NOT_FOUND -> {
                // Lines 454 to 459.
                state.errorFlag = ErrorFlag.ON;
                state.setMessage(MSG_TRANSACTION_ID_NOT_FOUND);
                state.focusField = FIELD_ACCOUNT_ID;
                sendBillpayScreen(state);
            }
            default -> {
                // Lines 460 to 466.
                LOG.warn("Transaction browse could not be established: file=TRANSACT response={}",
                        response);
                state.errorFlag = ErrorFlag.ON;
                state.setMessage(MSG_UNABLE_TO_LOOKUP_TRANSACTION);
                state.focusField = FIELD_ACCOUNT_ID;
                sendBillpayScreen(state);
            }
        }
    }

    // ==============================================================================================
    // READPREV-TRANSACT-FILE, line 472
    // ==============================================================================================

    /**
     * The backward-read paragraph at lines 472 to 496: the read half of the identifier rule.
     *
     * <p>Reads the transaction master backward from the browse position, which lands on the highest
     * identifier present, and evaluates the response over three arms. The normal response continues with
     * the identifier it found. <strong>The end-of-file arm at line 488 moves zeros into the key</strong>,
     * which is what makes the first identifier on an empty table one - and, once the increment is zero
     * filled back to sixteen characters, the string {@code 0000000000000001}. The catch-all reports that
     * the transaction could not be looked up.
     *
     * <p>The backward read is the repository's maximum-identifier query, whose empty result is exactly
     * the end-of-file response: the maximum over no rows is absent. That correspondence is why the query
     * returns an optional rather than a bare value, and it is what gives this paragraph a defined seed
     * instead of an absent value to defend against.
     *
     * <p><strong>The catch-all arm verifies the precondition the textual maximum depends on.</strong> The
     * identifier column is character data, so the query's maximum is lexicographic. Lexicographic and
     * numeric maxima coincide only while every identifier is exactly sixteen zero-padded digits with no
     * sign overpunch, which is what the source guarantees by moving a sixteen-digit numeric value into
     * the key. A shorter, unpadded or non-numeric identifier would break the ordering silently and
     * without breaking compilation, so a maximum that is not a well-formed sixteen-digit lexeme is
     * refused here under the source's own catch-all text rather than incremented.
     *
     * <p>When the catch-all arm fires the key is left unresolved. The source instead leaves the sentinel
     * in place and then moves it into a numeric field, whose result the language does not define; leaving
     * the key unresolved is the defined equivalent, and it reaches the same observable outcome, because
     * the write paragraph's own catch-all arm is what the resulting write would have taken.
     *
     * @param state the turn's working storage
     */
    private void readprevTransactFile(final TurnState state) {
        final Optional<String> highestIdentifier = transactionRepository.findMaxId();
        final FileResponse response;
        if (highestIdentifier.isEmpty()) {
            response = FileResponse.NOT_FOUND;
        } else if (numericIdentifierOf(highestIdentifier.get()) == null) {
            response = FileResponse.OTHER;
        } else {
            response = FileResponse.NORMAL;
        }

        // EVALUATE WS-RESP-CD at lines 484 to 496, clause order preserved, catch-all as the default arm.
        switch (response) {
            case NORMAL -> {
                // CONTINUE at lines 485 and 486: the record read leaves its key in TRAN-ID.
                state.transactionKey = highestIdentifier.get();
            }
            case NOT_FOUND -> {
                // MOVE ZEROS TO TRAN-ID at line 488, for the end-of-file response at line 487.
                state.transactionKey = CobolStringUtils.rightJustifyZeroFill(
                        Long.toString(TRANSACTION_ID_EOF_SEED), TRANSACTION_ID_WIDTH);
            }
            default -> {
                // Lines 489 to 495. The malformed maximum is not logged: it is an identifier, and a
                // diagnostic that reproduced it would disclose one.
                LOG.warn("Highest transaction identifier is not a well-formed sixteen-digit key, so"
                        + " identifier generation is refused: file=TRANSACT response={}", response);
                state.transactionKey = null;
                state.errorFlag = ErrorFlag.ON;
                state.setMessage(MSG_UNABLE_TO_LOOKUP_TRANSACTION);
                state.focusField = FIELD_ACCOUNT_ID;
                sendBillpayScreen(state);
            }
        }
    }

    // ==============================================================================================
    // ENDBR-TRANSACT-FILE, line 501
    // ==============================================================================================

    /**
     * The browse-end paragraph at lines 501 to 505.
     *
     * <p>Ends the browse of the transaction master. Alone among this member's file paragraphs it
     * evaluates no response at all - the source requests no response code and handles no failure - so
     * this paragraph has exactly one arm and emits no message.
     *
     * <p>Because it handles no failure, releasing a browse that was never established would pass
     * silently, and the source relies on its own statement order to guarantee that cannot happen. The
     * order is reproduced, and the reliance is made visible: a release with no browse open is reported
     * rather than ignored. Nothing about the turn's outcome changes, because the source's outcome does
     * not change either.
     *
     * @param state the turn's working storage
     */
    private void endbrTransactFile(final TurnState state) {
        if (!state.browseActive) {
            LOG.warn("Transaction browse was released without having been established, so the"
                    + " statement order of lines 213 to 215 was not followed: file=TRANSACT");
        }

        // EXEC CICS ENDBR DATASET(WS-TRANSACT-FILE) at lines 503 to 505.
        state.browseActive = false;
    }

    // ==============================================================================================
    // WRITE-TRANSACT-FILE, line 510
    // ==============================================================================================

    /**
     * The transaction insert paragraph at lines 510 to 547.
     *
     * <p>Writes the assembled record and evaluates the response over three arms whose order is
     * preserved. The normal arm at lines 523 to 532 blanks every field, recolours the message field to
     * green and composes the success text. The duplicate arm covers both duplicate responses, which the
     * source groups into one arm at lines 533 and 534. The catch-all reports that the payment
     * transaction could not be added.
     *
     * <p><strong>The success text carries two consecutive spaces after the first full stop.</strong> The
     * source assembles it from four fragments; the first ends with a space and the second begins with
     * one, so the assembled text reads with a double space. It is reproduced rather than corrected, and
     * the finding is recorded in the decision log. The identifier fragment is delimited by space rather
     * than by size, which takes the whole sixteen-character key because a well-formed identifier holds
     * no space.
     *
     * <p><strong>The duplicate arm needs an explicit existence check, and that is not defensive
     * padding.</strong> A persistence save of a new record whose key is assigned rather than generated
     * resolves to an update when the key already exists, so without the check a duplicate identifier
     * would silently overwrite an existing transaction instead of being refused. The legacy write
     * returns a duplicate response, and reproducing that response is the only way this arm is reachable
     * at all.
     *
     * <p>The catch-all arm fires when the assembled record cannot be inserted: no identifier was
     * resolved, or no card number was, which is the state the source reaches when the cross-reference
     * read or the backward read failed and the sequence carried on regardless. Detecting it here rather
     * than letting the store refuse the row keeps the outcome an operator message - which is what the
     * legacy produced once its own write was refused - instead of a constraint failure that would roll
     * the turn back.
     *
     * @param state the turn's working storage
     */
    private void writeTransactFile(final TurnState state) {
        final Transaction record = state.transactionRecord;
        final WriteResponse response;
        if (record == null || record.getTranId() == null || !isSupplied(record.getTranCardNum())) {
            response = WriteResponse.OTHER;
        } else if (transactionRepository.existsById(record.getTranId())) {
            response = WriteResponse.DUPLICATE;
        } else {
            state.createdTransaction = transactionRepository.save(record);
            response = WriteResponse.NORMAL;
        }

        // EVALUATE WS-RESP-CD at lines 522 to 547, clause order preserved, catch-all as the default arm.
        switch (response) {
            case NORMAL -> {
                // PERFORM INITIALIZE-ALL-FIELDS at line 524 and MOVE SPACES TO WS-MESSAGE at line 525.
                initializeAllFields(state);

                // MOVE DFHGREEN TO ERRMSGC at line 526: the message field is recoloured, which is the
                // only place in this member that happens.
                state.messageHighlightedGreen = true;

                // STRING at lines 527 to 531.
                state.setMessage(FRAGMENT_PAYMENT_SUCCESSFUL
                        + FRAGMENT_TRANSACTION_ID_IS
                        + delimitedBySpace(state.createdTransaction.getTranId())
                        + FRAGMENT_SENTENCE_TERMINATOR);

                // PERFORM SEND-BILLPAY-SCREEN at line 532: the first of the two sends this path makes.
                sendBillpayScreen(state);
            }
            case DUPLICATE -> {
                // Lines 533 to 539, one arm for both duplicate responses.
                state.errorFlag = ErrorFlag.ON;
                state.setMessage(MSG_TRAN_ID_ALREADY_EXISTS);
                state.focusField = FIELD_ACCOUNT_ID;
                sendBillpayScreen(state);
            }
            default -> {
                // Lines 540 to 546.
                LOG.warn("Bill-payment transaction could not be inserted: file=TRANSACT response={}",
                        response);
                state.errorFlag = ErrorFlag.ON;
                state.setMessage(MSG_UNABLE_TO_ADD_TRANSACTION);
                state.focusField = FIELD_ACCOUNT_ID;
                sendBillpayScreen(state);
            }
        }
    }

    /**
     * The arms of the insert evaluation at lines 522 to 547.
     *
     * <p>A separate selector from the read paragraphs' because this evaluation names a different set of
     * responses: the source groups the two duplicate responses into a single arm, and there is no
     * not-found response to a write.
     */
    private enum WriteResponse {

        /** The normal response: the record was inserted. */
        NORMAL,

        /** Either duplicate response, which the source handles in one arm at lines 533 and 534. */
        DUPLICATE,

        /** Any other response: the catch-all arm. */
        OTHER
    }

    // ==============================================================================================
    // CLEAR-CURRENT-SCREEN, line 552
    // ==============================================================================================

    /**
     * The clear paragraph at lines 552 to 555: blank every field, then send.
     *
     * <p>Reached from the fourth program-function key at line 137 and from the negative confirmation arm
     * at line 180. It raises no error flag of its own; the negative confirmation arm raises one
     * afterwards, at line 181, which is what stops the payment stage from running on that path while
     * leaving the screen blank and the message empty.
     *
     * @param state the turn's working storage
     */
    private void clearCurrentScreen(final TurnState state) {
        initializeAllFields(state);
        sendBillpayScreen(state);
    }

    // ==============================================================================================
    // INITIALIZE-ALL-FIELDS, line 560
    // ==============================================================================================

    /**
     * The reset paragraph at lines 560 to 566.
     *
     * <p>Positions the cursor on the account-id field at line 562, then blanks the three screen input
     * fields and the message work field at lines 563 to 566. Clearing the message is what leaves the
     * negative confirmation path with no text at all, and it is also why the success path has to compose
     * its text <em>after</em> performing this paragraph rather than before.
     *
     * @param state the turn's working storage
     */
    private void initializeAllFields(final TurnState state) {
        // MOVE -1 TO ACTIDINL at line 562.
        state.focusField = FIELD_ACCOUNT_ID;

        // MOVE SPACES TO ACTIDINI, CURBALI, CONFIRMI and WS-MESSAGE at lines 563 to 566.
        state.accountIdInput = blankField(ACCOUNT_ID_WIDTH);
        state.balanceDisplayField = blankField(BALANCE_DISPLAY_WIDTH);
        state.confirmInput = blankField(CONFIRM_WIDTH);
        state.message = NO_MESSAGE;
    }

    // ==============================================================================================
    // The terminal return, lines 146 to 149
    // ==============================================================================================

    /**
     * The terminal return at lines 146 to 149: the transaction is re-armed with the communication area
     * carried across.
     *
     * <p>Not a paragraph of the source - the return is the closing statement of the main paragraph - but
     * it is given its own method because the return and the transfer are mutually exclusive and the
     * distinction has to be explicit. Re-arming never overrides a transfer, because a transfer has
     * already left the program.
     *
     * @param state the turn's working storage
     */
    private void returnToCics(final TurnState state) {
        if (state.transferred) {
            return;
        }
        state.reArmedTransactionId = WS_TRANID;
    }

    // ==============================================================================================
    // Primitives for the constructs the source uses
    // ==============================================================================================

    /**
     * Derives the arm of the confirmation evaluation at lines 173 to 191, in the source's own clause
     * order.
     *
     * <p>The order is the contract: the language evaluates a selector evaluation top down and stops at
     * the first matching clause, so the affirmative characters are tested before the negative ones and
     * both before blankness, with everything else falling to the catch-all.
     *
     * @param field the confirmation field, already bounded to its one-character width
     * @return the arm the field selects
     */
    private static ConfirmationSelection confirmationSelection(final String field) {
        if (CONFIRM_YES_UPPER.equals(field) || CONFIRM_YES_LOWER.equals(field)) {
            return ConfirmationSelection.YES;
        }
        if (CONFIRM_NO_UPPER.equals(field) || CONFIRM_NO_LOWER.equals(field)) {
            return ConfirmationSelection.NO;
        }
        if (isBlankField(field)) {
            return ConfirmationSelection.BLANK;
        }
        return ConfirmationSelection.OTHER;
    }

    /**
     * The current balance the account record holds, as the moves at lines 193, 198, 224 and 234 read it.
     *
     * <p>Zero when no account record has been read. That is the defined equivalent of what the source
     * does rather than a convenience: the record lives in working storage with no initial value, so an
     * unpopulated balance field holds spaces, and on the platform the source targets a space is a byte
     * whose low-order nibble is zero - which is exactly what a zoned decimal field reads a digit from.
     * An unpopulated record therefore reads as zero there too, and the two paths that reach these moves
     * without a record - a read that found nothing, and the negative confirmation arm, which reads
     * nothing - both display a zero balance as a result.
     *
     * <p>The value is brought to the monetary scale by the module's codec on every read, because each of
     * the four moves stores into a two-decimal field and the codec is the only place a scale is imposed.
     *
     * @param state the turn's working storage
     * @return the balance at scale two, never {@code null}
     */
    private static BigDecimal currentBalanceOfRecord(final TurnState state) {
        if (state.account == null) {
            return ZonedDecimalCodec.toMonetaryScale(BigDecimal.ZERO);
        }
        return ZonedDecimalCodec.toMonetaryScale(state.account.getAcctCurrBal());
    }

    /**
     * The card number the cross-reference record holds, as the move at line 225 reads it.
     *
     * <p>Absent when no cross-reference record has been read, which is the state the sequence reaches
     * when the cross-reference read failed and carried on regardless. An absent card number is what the
     * insert paragraph's catch-all arm detects, and reporting it there is what keeps a record the store
     * would refuse from ever being offered to it.
     *
     * @param state the turn's working storage
     * @return the sixteen-character card number, or {@code null} when no record was read
     */
    private static String crossReferencedCardNumber(final TurnState state) {
        return state.crossReference == null ? null : state.crossReference.getXrefCardNum();
    }

    /**
     * Renders a value into the edited balance field declared at line 56.
     *
     * <p>The field carries a mandatory sign, ten integer digits and two fraction digits across fourteen
     * character positions, so a value is rendered with an explicit plus for a non-negative value and a
     * minus for a negative one, the integer digits zero filled on the left, and the fraction always two
     * digits. The zero fill is the module's string primitive, which keeps the low-order digits when a
     * value has more integer digits than the field has positions - which is how an unrounded store into
     * a fixed-width numeric field behaves.
     *
     * @param value the value to render, at any scale
     * @return exactly fourteen characters
     */
    private static String editedBalance(final BigDecimal value) {
        final BigDecimal scaled = ZonedDecimalCodec.toMonetaryScale(value);
        final String plainMagnitude = scaled.abs().toPlainString();
        final int decimalPoint = plainMagnitude.indexOf(DECIMAL_POINT);
        final String integerDigits = plainMagnitude.substring(0, decimalPoint);
        final String fractionDigits = plainMagnitude.substring(decimalPoint + 1);
        final char sign = scaled.signum() < 0 ? MINUS_SIGN : PLUS_SIGN;
        return sign
                + CobolStringUtils.rightJustifyZeroFill(integerDigits, BALANCE_INTEGER_DIGITS)
                + DECIMAL_POINT
                + fractionDigits;
    }

    /**
     * Reproduces the move of a key into the sixteen-digit numeric work field at line 216, refusing any
     * value the move could not meaningfully carry.
     *
     * <p>Accepts a key only when it is exactly sixteen ASCII digit characters. That is the precondition
     * the textual maximum and the increment both depend on, and it is what the source itself guarantees
     * by only ever writing a sixteen-digit numeric value into the key. The browse sentinel and a
     * malformed identifier are both refused, and the caller reports the refusal under the source's own
     * catch-all text rather than incrementing a value whose ordering cannot be trusted.
     *
     * <p>Membership is tested against the ten ASCII digits rather than against a Unicode digit test,
     * which would admit characters the legacy field could never hold.
     *
     * @param key the key to convert, which may be {@code null}
     * @return the numeric value of the key, or {@code null} when the key is not a well-formed
     *         sixteen-digit identifier
     */
    private static Long numericIdentifierOf(final String key) {
        if (key == null || key.length() != TRANSACTION_ID_WIDTH) {
            return null;
        }
        for (int index = 0; index < key.length(); index++) {
            final char position = key.charAt(index);
            if (position < '0' || position > '9') {
                return null;
            }
        }
        // Sixteen digits reach at most 9,999,999,999,999,999, which a long carries with three decimal
        // orders of magnitude to spare, so the conversion cannot overflow.
        return Long.valueOf(Long.parseLong(key));
    }

    /**
     * Reproduces the abbreviated combined relation {@code NOT = SPACES AND LOW-VALUES} at lines 116, 159
     * and 199, which expands to "is neither all spaces nor all low values".
     *
     * @param field the field to test, which may be {@code null}
     * @return {@code true} when the field carries something other than spaces and low values
     */
    private static boolean isSupplied(final String field) {
        return !isBlankField(field);
    }

    /**
     * Reproduces {@code = SPACES OR LOW-VALUES}, as the confirmation evaluation tests it at lines 182
     * and 183.
     *
     * <p>Blank means every character position is a space or a low value. An absent value and an empty
     * value are both blank, because a field the terminal did not transmit arrives as low values and
     * carries no character positions of its own.
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
     * Reproduces {@code STRING ... DELIMITED BY SPACE}, which stops assembling at the first space, as the
     * identifier fragment of the success text uses it at line 529.
     *
     * <p>A well-formed identifier is sixteen digits and holds no space, so this takes the whole key. The
     * delimiter is reproduced anyway because it is what the source wrote, and because it is the reason a
     * padded value would not appear padded in the message.
     *
     * @param field the field to consume
     * @return the part of the field before its first space, or the whole field when it holds none
     */
    private static String delimitedBySpace(final String field) {
        final int firstSpace = field.indexOf(SPACE);
        return firstSpace < 0 ? field : field.substring(0, firstSpace);
    }

    /**
     * Reproduces a move into an alphanumeric field of the given width: left justified, space filled on
     * the right when the sender is shorter, and truncated on the right when it is longer. An absent
     * sender yields a blank field, because a field the terminal did not transmit holds no characters.
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
     * Reproduces a move of an unsigned value into a display field of the given digit count: zero filled
     * on the left, keeping the low-order digits when the value has more digits than the field has
     * positions, which is how an unrounded store into a fixed-width numeric field behaves.
     *
     * @param value the value to render; its magnitude is used, matching an unsigned receiving field
     * @param width the digit count of the receiving field
     * @return exactly {@code width} digits
     */
    private static String numericField(final int value, final int width) {
        return CobolStringUtils.rightJustifyZeroFill(Integer.toString(Math.abs(value)), width);
    }

    /**
     * Produces a blank field of the given width, which is what a move of spaces writes into an
     * alphanumeric item.
     *
     * @param width the field width in character positions
     * @return exactly {@code width} spaces
     */
    private static String blankField(final int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    /**
     * Returns a copy of the navigation state whose nominated-destination program is the one supplied,
     * reproducing a move into {@code CDEMO-TO-PROGRAM} at lines 108, 130, 133 and 276.
     *
     * <p>Every other component is carried across unchanged. The sixteen components are restated
     * explicitly because the state is an immutable record; a mutable copy would let client-echoed state
     * be altered in place.
     *
     * @param context     the state to copy
     * @param programName the destination program name to nominate
     * @return a new state differing only in its nominated-destination program
     */
    private static NavigationContext withNominatedProgram(final NavigationContext context,
            final String programName) {
        return new NavigationContext(context.fromTransactionId(),
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
     * Returns a copy of the navigation state stamped with this screen's transaction identifier and
     * program name as the originator, reproducing lines 278 and 279.
     *
     * @param context the state to copy
     * @return a new state differing only in its originating transaction and program
     */
    private static NavigationContext withOriginatingProgram(final NavigationContext context) {
        return new NavigationContext(WS_TRANID,
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

    // ==============================================================================================
    // Per-invocation working storage
    // ==============================================================================================

    /**
     * The working storage the source declares at lines 36 to 72, held per invocation so the service bean
     * itself stays stateless and two concurrent turns cannot observe one another.
     *
     * <p>Private mutable fields rather than accessors: this is a scratch area belonging to one call of
     * one enclosing class, and accessors would add ceremony without adding safety. Nothing here outlives
     * the call - no identifier, no account and no balance is cached between turns, because caching any of
     * them would break the identifier rule the enclosing class exists to reproduce.
     */
    private static final class TurnState {

        /** {@code WS-ERR-FLG}, cleared at line 101 and raised by every failure site. */
        private ErrorFlag errorFlag = ErrorFlag.OFF;

        /**
         * {@code WS-USR-MODIFIED}, set to its no state at line 102 and never tested in this member.
         *
         * <p>Assigned once, exactly as the source assigns it, and deliberately never read: adding a
         * branch on it would add behaviour the program does not have.
         */
        private UserModifiedFlag userModifiedFlag = UserModifiedFlag.NO;

        /** {@code WS-CONF-PAY-FLG}, reset at line 156 and raised only by the affirmative arm at line 176. */
        private ConfirmPaymentFlag confirmPayFlag = ConfirmPaymentFlag.NO;

        /**
         * {@code CDEMO-CB00-NEXT-PAGE-FLG} from the program-local extension at lines 68 to 70.
         *
         * <p>Carried at its declared initial value and never changed, because this member has no paging
         * conversation to change it for.
         */
        private NextPageFlag nextPageFlag = NextPageFlag.NO;

        /**
         * {@code WS-MESSAGE}, blanked at lines 104, 525 and 566, in its authored rather than padded form.
         *
         * <p>Written by an unconditional assignment at every site, because the source writes it with an
         * unconditional move at every site. There is deliberately no first-write latch here: this
         * member's send paragraph does not end the task, so a turn can genuinely write more than one
         * message and the operator sees the last one written. A latch would show the wrong one.
         */
        private String message = NO_MESSAGE;

        /** {@code ERRMSGO}, the outbound message field the send paragraph fills at line 293. */
        private String errorMessageField = blankField(ERROR_MESSAGE_WIDTH);

        /** The screen field the cursor is positioned on, from each move of minus one into a length item. */
        private String focusField = FIELD_ACCOUNT_ID;

        /** Whether the message field was recoloured, which happens only at line 526. */
        private boolean messageHighlightedGreen;

        /** {@code CARDDEMO-COMMAREA}, adopted at line 111 and handed back by the return at line 148. */
        private NavigationContext context = NavigationContext.empty();

        /**
         * The destination the turn leads to.
         *
         * <p>This screen's own route by default, because the return at lines 146 to 149 re-arms this same
         * transaction; a transfer replaces it.
         */
        private NavigationService.Route route = NavigationService.Route.BILL_PAYMENT;

        /** Whether the turn transferred control, which makes the terminal return unreachable. */
        private boolean transferred;

        /** How many times the screen was sent, so the source's double send can be reported once. */
        private int screenSends;

        /** The transaction identifier the return re-armed at line 147; empty on a transfer. */
        private String reArmedTransactionId = NO_MESSAGE;

        /** Whether the re-enter gate was set at line 113. */
        private boolean reEnterGateSet;

        /** {@code ACTIDINI}, bounded to its declared width by the receive paragraph. */
        private String accountIdInput = blankField(ACCOUNT_ID_WIDTH);

        /** {@code CONFIRMI}, bounded to its declared width by the receive paragraph. */
        private String confirmInput = blankField(CONFIRM_WIDTH);

        /** {@code CURBALI}, the edited pre-payment balance the moves at lines 193 and 194 place on the map. */
        private String balanceDisplayField = blankField(BALANCE_DISPLAY_WIDTH);

        /** {@code ACCT-ID}, the account read key set at line 170. */
        private String acctIdKey;

        /** {@code XREF-ACCT-ID}, the cross-reference read key set at line 171. */
        private String xrefAcctIdKey;

        /** {@code ACCOUNT-RECORD}, absent until the account read succeeds. */
        private Account account;

        /** {@code CARD-XREF-RECORD}, absent until the cross-reference read succeeds. */
        private CardCrossReference crossReference;

        /**
         * {@code TRAN-ID}: the browse sentinel at line 212, then the maximum the backward read found or
         * the zeros its end-of-file arm wrote, then the incremented identifier the move at line 219
         * stores. Absent when the backward read refused a malformed maximum.
         */
        private String transactionKey;

        /**
         * {@code WS-TRAN-ID-NUM}, the sixteen-digit numeric work field declared at line 57.
         *
         * <p>Absent rather than zero when the key could not be converted, so that the insert paragraph's
         * catch-all arm reproduces the outcome the source's undefined numeric move would have reached.
         */
        private Long transactionIdNumber;

        /** {@code TRAN-RECORD} as the moves at lines 218 to 232 leave it, whether or not it was inserted. */
        private Transaction transactionRecord;

        /** The record the insert actually stored, absent on every arm but the normal one. */
        private Transaction createdTransaction;

        /** The pre-payment balance the moves at lines 193 and 194 read; absent before they run. */
        private BigDecimal screenBalance;

        /** The balance the computation at line 234 produced; absent before it runs. */
        private BigDecimal postPaymentBalance;

        /** Whether a browse of the transaction master is open, between lines 213 and 215. */
        private boolean browseActive;

        /** {@code CCDA-TITLE01}, stamped at line 323. */
        private String title01 = NO_MESSAGE;

        /** {@code CCDA-TITLE02}, stamped at line 324. */
        private String title02 = NO_MESSAGE;

        /** {@code TRNNAMEO}, stamped at line 325. */
        private String transactionName = NO_MESSAGE;

        /** {@code PGMNAMEO}, stamped at line 326. */
        private String programName = NO_MESSAGE;

        /** {@code CURDATEO}, assembled at lines 328 to 332. */
        private String currentDate = NO_MESSAGE;

        /** {@code CURTIMEO}, assembled at lines 334 to 338. */
        private String currentTime = NO_MESSAGE;

        /**
         * The per-field detail the outbound contract carries.
         *
         * <p>A list because more than one field can be faulted across a turn: this member's send does not
         * end the task, so a later stage can fault a second field after an earlier one already did.
         */
        private final List<ValidationException.FieldError> fieldErrors = new ArrayList<>();

        /**
         * Writes the message work field, reproducing an unconditional move into {@code WS-MESSAGE}.
         *
         * @param text the message text, byte exact
         */
        private void setMessage(final String text) {
            this.message = text;
        }

        /**
         * Adds one per-field entry, distinguishing a field that was not supplied from one supplied
         * wrongly.
         *
         * @param property   the property name a consumer binds to
         * @param bmsFieldId the legacy screen field name
         * @param fieldState which of the two legacy error states the field is in
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
        private BillPaymentResult toResult() {
            return new BillPaymentResult(this.route,
                    this.context,
                    this.reArmedTransactionId,
                    projectTransaction(),
                    projectAccount(),
                    this.screenBalance,
                    this.message,
                    this.messageHighlightedGreen,
                    this.focusField,
                    this.errorFlag.isOn(),
                    this.confirmPayFlag,
                    this.reEnterGateSet,
                    List.copyOf(this.fieldErrors),
                    new ScreenHeader(this.title01,
                            this.title02,
                            this.transactionName,
                            this.programName,
                            this.currentDate,
                            this.currentTime,
                            this.errorMessageField),
                    new ScreenFields(this.accountIdInput,
                            this.balanceDisplayField,
                            this.confirmInput));
        }

        /**
         * Projects the inserted transaction, or nothing when none was inserted.
         *
         * <p>The record assembled but refused is deliberately not projected: a consumer must not be able
         * to mistake a refused payment for a completed one, and the message and the error flag already
         * describe the refusal.
         *
         * @return the projection, or {@code null} when no transaction was inserted
         */
        private TransactionProjection projectTransaction() {
            if (this.createdTransaction == null) {
                return null;
            }
            return new TransactionProjection(this.createdTransaction.getTranId(),
                    this.createdTransaction.getTranTypeCd(),
                    this.createdTransaction.getTranCatCd(),
                    this.createdTransaction.getTranSource(),
                    this.createdTransaction.getTranDesc(),
                    this.createdTransaction.getTranAmt(),
                    this.createdTransaction.getMerchantId(),
                    this.createdTransaction.getMerchantName(),
                    this.createdTransaction.getMerchantCity(),
                    this.createdTransaction.getMerchantZip(),
                    this.createdTransaction.getTranCardNum(),
                    this.createdTransaction.getTranOrigTs(),
                    this.createdTransaction.getTranProcTs());
        }

        /**
         * Projects the account as the turn left it, or nothing when none was read.
         *
         * <p>The balance carried is the account's own, which after a completed payment is the
         * post-payment figure and is exactly zero. The pre-payment figure the screen shows is carried
         * separately.
         *
         * @return the projection, or {@code null} when no account was read
         */
        private AccountProjection projectAccount() {
            if (this.account == null) {
                return null;
            }
            return new AccountProjection(this.account.getAcctId(),
                    this.account.getAcctCurrBal(),
                    this.account.getVersion());
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
