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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.ZonedDecimalCodec;

/**
 * The transaction-view screen: one keyed read of the transaction file, rendered back to the operator.
 * Translated from {@code app/cbl/COTRN01C.cbl}, transaction {@code CT01}, 330 lines and
 * <strong>9 paragraphs</strong>, every one of which has a named method here. Checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} (2022-07-19).
 *
 * <p>The nine paragraphs and their methods, in source order:
 *
 * <ul>
 *   <li>{@code MAIN-PARA} line 86 &rarr; {@code mainPara}</li>
 *   <li>{@code PROCESS-ENTER-KEY} line 144 &rarr; {@code processEnterKey}</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} line 197 &rarr; {@code returnToPrevScreen}</li>
 *   <li>{@code SEND-TRNVIEW-SCREEN} line 213 &rarr; {@code sendTrnviewScreen}</li>
 *   <li>{@code RECEIVE-TRNVIEW-SCREEN} line 230 &rarr; {@code receiveTrnviewScreen}</li>
 *   <li>{@code POPULATE-HEADER-INFO} line 243 &rarr; {@code populateHeaderInfo}</li>
 *   <li>{@code READ-TRANSACT-FILE} line 267 &rarr; {@code readTransactFile}</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} line 301 &rarr; {@code clearCurrentScreen}</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} line 309 &rarr; {@code initializeAllFields}</li>
 * </ul>
 *
 * <p><strong>This member sits outside the five-program family, so it has no abend handler and no
 * attention-key copybook, and neither is wired here.</strong> The family that includes
 * {@code CVCRD01Y} and the attention-key copybook {@code CSSTRPFY} is
 * {@code COACTUPC}, {@code COACTVWC}, {@code COCRDLIC}, {@code COCRDSLC} and {@code COCRDUPC}; this
 * program is in neither list. It declares no {@code EXEC CICS HANDLE ABEND}, issues no
 * {@code EXEC CICS ABEND} and makes no call to a language-environment abort routine, so no abend
 * service is injected, and it includes no key copybook, so the key translator that exists for those
 * five includers is not used. Its attention key is evaluated directly, in the shape the source
 * evaluates it. Adding either collaborator would be feature expansion.
 *
 * <p><strong>The whole risk in this translation is quiet type drift, not complexity.</strong> This is
 * the smallest online member in the package, and every field it displays is a value whose legacy
 * representation a plausible, compiling, well-meaning translation would silently change. Four are
 * load bearing and each is defended at its accessor on {@code TransactionProjection}:
 *
 * <ul>
 *   <li>the identifier is <strong>16 alphanumeric characters</strong> and is never parsed to a numeric
 *       type anywhere in this class, so its zero padding survives &mdash;
 *       {@code "0000000000000001"} and {@code "1"} are different keys and only the first one reads a
 *       row;</li>
 *   <li>the amount is a {@code BigDecimal} at the record's own precision and is never a
 *       floating-point type;</li>
 *   <li>the two timestamps are <strong>26-character strings that are returned exactly as stored</strong>,
 *       with no temporal type, no parse, no format and no cast anywhere on the path;</li>
 *   <li>the source is a raw space-padded string, never trimmed and never an enumeration at this
 *       layer.</li>
 * </ul>
 *
 * <p><strong>Two 26-character timestamp formats coexist in the estate and this class does not unify
 * them.</strong> The online writers produce a form with a space between date and time, colons inside
 * the time and a six-digit fraction; the batch writers produce a form with a hyphen before the hour,
 * dots between the time parts and two hundredths digits followed by four literal zeros. Both are
 * twenty-six characters, both are stored in the same column, and a row written by either writer is
 * displayed by this screen. Normalising one to the other would be a behavioural regression that no
 * requirement asks for, so this class carries whichever form it read, byte for byte. That is why the
 * projection exposes them as text and why no method here consults a date-time library about them.
 *
 * <p><strong>Read-only by contract.</strong> A view transaction writes nothing: there is no store, no
 * delete, no flush and no modifying query on any path, and the one read runs in a read-only
 * transaction. The legacy read is issued <em>with the update option</em> at lines 269 to 278, which
 * acquires an update lock the program never uses because it never rewrites the record and never
 * reaches a syncpoint of its own. Reproducing that lock would add contention with no observable
 * benefit, so the read is plain and the divergence is recorded in the decision log.
 *
 * <p><strong>The identifier can arrive by two routes and the source's precedence is preserved.</strong>
 * The screen has its own search field, and the transaction-list screen can hand an identifier forward
 * through the communication area. On a first entry the carried selection wins when it is present:
 * lines 103 to 107 move it into the search field and then run the enter-key path, so a caller that
 * supplies both gets the selection. On a re-entry only the transmitted search field is read, because
 * the source does not re-consult the selection there. A detail worth recording, because the field
 * names do not match across the two programs: {@code CDEMO-CT01-TRN-SELECTED} at line 61 is declared
 * only in this member, as a continuation of the communication area after {@code COPY COCOM01Y}, while
 * {@code app/cbl/COTRN00C.cbl} lines 63 to 72 declares a byte-identical group under
 * {@code CDEMO-CT00-} names, writes the selection into it and transfers control here at lines 188 to
 * 195. The two groups overlay the same bytes, which is how the value actually travels. The shared
 * navigation state models only the sixteen fields of {@code COCOM01Y} and carries no selection, so the
 * selection is a component of this class's own input contract.
 *
 * <p><strong>What a turn returns, and what renders it.</strong> Every path yields a
 * {@code TransactionViewResult}: the resolved route, the navigation state, the retrieved record or
 * nothing, the summary message, the per-field detail, the cursor position, the error flag and the
 * re-enter flag. Nothing here builds an HTTP response, chooses a status code, or bounds a value to a
 * screen width. The screen widths diverge from the record widths in five places &mdash; the
 * description is 60 on the screen against 100 in the record, the merchant name 30 against 50, the
 * merchant city 25 against 50, and both timestamps 10 against 26, from
 * {@code app/cpy-bms/COTRN01.CPY} lines 96, 108, 114, 126 and 132 &mdash; and applying those bounds is
 * the presentation layer's job, not this one's. This class hands over the record as it read it, which
 * is also what keeps the timestamp guarantee above true.
 *
 * <p><strong>Stateless singleton.</strong> The class declares no mutable field and caches no record.
 * The legacy working storage lives in a per-invocation scratch object, so two concurrent turns cannot
 * observe one another, and a turn's outcome depends only on its own input and the row it read.
 *
 * <p><strong>Faithful beats idiomatic.</strong> Where the legacy semantics and the natural Java shape
 * diverge, the legacy wins and the divergence is documented at the member concerned. The four that
 * matter are the untouched timestamps, the alphanumeric identifier, the refusal rather than truncation
 * of an over-long identifier described on {@code processEnterKey}, and the plain read described above.
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
public class TransactionViewService {

    /**
     * Diagnostic channel for this class, replacing the console-display statement that was this
     * program's only instrumentation &mdash; the single {@code DISPLAY} at line 290, on the
     * lookup-failure arm of the read.
     *
     * <p>No transaction identifier, card number, description, amount, merchant detail or timestamp is
     * ever written here: the record this screen displays is cardholder data in its entirety, so a
     * diagnostic reports the <em>outcome</em> of a turn and never its content. The route token, the
     * cursor field name, the error flag and the field-error count are values this class owns and are
     * logged directly.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionViewService.class);

    // ==========================================================================================
    // Program identity, from WS-VARIABLES at lines 36 and 37
    // ==========================================================================================

    /** {@code WS-PGMNAME}, {@code PIC X(08) VALUE 'COTRN01C'} at line 36. */
    private static final String WS_PGMNAME = "COTRN01C";

    /** {@code WS-TRANID}, {@code PIC X(04) VALUE 'CT01'} at line 37. */
    private static final String WS_TRANID = "CT01";

    /**
     * {@code WS-TRANSACT-FILE}, {@code PIC X(08) VALUE 'TRANSACT'} at line 39: the dataset the read at
     * line 270 names.
     *
     * <p>Retained as the resource name a diagnostic reports, so a failed lookup names the same
     * resource the legacy operator would have seen. The repository resolves the table itself, so this
     * value selects nothing.
     */
    private static final String WS_TRANSACT_FILE = "TRANSACT";

    // ==========================================================================================
    // The three external-contract message literals, in source order.
    //
    // Casing, spacing and dot counts are contractual and are never normalised. Each carries exactly
    // three trailing dots with no space before them, and the first capitalises NOT, which is the
    // house style for the estate's emptiness messages. The presentation record for this screen
    // publishes its own copies for its rendering contract; both derive from the source lines cited
    // below and neither is authoritative over the other.
    // ==========================================================================================

    /** {@code 'Tran ID can NOT be empty...'} at line 149, the blank arm of the enter-key cascade. */
    private static final String MSG_TRAN_ID_EMPTY = "Tran ID can NOT be empty...";

    /** {@code 'Transaction ID NOT found...'} at line 285, the record-absent arm of the read. */
    private static final String MSG_TRAN_ID_NOT_FOUND = "Transaction ID NOT found...";

    /** {@code 'Unable to lookup Transaction...'} at line 292, the failure arm of the read. */
    private static final String MSG_LOOKUP_FAILED = "Unable to lookup Transaction...";

    // ==========================================================================================
    // Screen field identifiers and the property name a consumer binds to
    // ==========================================================================================

    /**
     * {@code TRNIDIN}, the search field of the input map at {@code app/cpy-bms/COTRN01.CPY} line 60.
     *
     * <p>The only field this screen ever positions the cursor on: every {@code MOVE -1} in the member
     * targets its length item, at lines 102, 151, 154, 287, 294 and 311.
     */
    private static final String FIELD_TRANSACTION_ID = "TRNIDIN";

    /** The property name the per-field detail carries for the search field. */
    private static final String PROPERTY_TRANSACTION_ID = "transactionId";

    // ==========================================================================================
    // The one bound this class enforces
    // ==========================================================================================

    /**
     * 16: the width of the transaction key, and the only bound this class checks.
     *
     * <p>It is declared three times over in the source and all three agree: the record key
     * {@code TRAN-ID} is {@code PIC X(16)} in {@code app/cpy/CVTRA05Y.cpy}, the search field
     * {@code TRNIDINI} is {@code PIC X(16)} at {@code app/cpy-bms/COTRN01.CPY} line 60, and the read at
     * line 274 passes {@code KEYLENGTH(LENGTH OF TRAN-ID)}. A value wider than this names no row,
     * because the key space is exactly sixteen characters wide.
     *
     * <p>This is a <em>length</em> bound and never a slice: nothing in this class truncates a value to
     * it. See {@code processEnterKey} for why refusing is the right outcome and where the divergence
     * is recorded.
     */
    private static final int TRANSACTION_ID_LENGTH = 16;

    // ==========================================================================================
    // Character and text constants
    // ==========================================================================================

    /** The COBOL figurative constant {@code SPACES}, one character position of it. */
    private static final char SPACE = ' ';

    /**
     * The COBOL figurative constant {@code LOW-VALUES}, one character position of it.
     *
     * <p>A 3270 field the terminal did not transmit arrives as low values rather than as spaces, which
     * is why every blank test in the source reads {@code = SPACES OR LOW-VALUES} and why the test here
     * accepts both.
     */
    private static final char LOW_VALUE = '\u0000';

    /** The authored form of a message field holding nothing. */
    private static final String NO_MESSAGE = "";

    /** The separator of the header date, {@code FILLER VALUE '/'} in {@code app/cpy/CSDAT01Y.cpy}. */
    private static final String HEADER_DATE_SEPARATOR = "/";

    /** The separator of the header time, {@code FILLER VALUE ':'} in {@code app/cpy/CSDAT01Y.cpy}. */
    private static final String HEADER_TIME_SEPARATOR = ":";

    /** The leading zero a two-digit header component takes when its value is a single digit. */
    private static final String LEADING_ZERO = "0";

    /**
     * 100: the modulus that reduces a value to the low-order two digits of a two-position display
     * field.
     *
     * <p>Applied to the four-digit year it reproduces {@code WS-CURDATE-YEAR(3:2)} at line 254 exactly
     * &mdash; the last two characters of a {@code PIC 9(4)} item are its value modulo one hundred
     * &mdash; without slicing a string, and applied to the remaining five header components it is the
     * identity, since none of them can reach three digits.
     */
    private static final int TWO_DIGIT_MODULUS = 100;

    /** 10: the value below which a two-digit header component needs its leading zero. */
    private static final int SINGLE_DIGIT_BOUND = 10;

    // ==========================================================================================
    // Collaborators, all constructor injected
    // ==========================================================================================

    /**
     * The transaction file, replacing {@code EXEC CICS READ DATASET('TRANSACT')} at lines 269 to 278.
     *
     * <p>Only the inherited single-key lookup is used. The two methods this repository declares of its
     * own &mdash; the highest-key probe and the processing-date range slice &mdash; belong to the
     * bill-payment and report services respectively and are deliberately untouched here: this screen
     * generates no identifier and filters no date range.
     */
    private final TransactionRepository transactionRepository;

    /**
     * The common-message catalogue, supplying the invalid-key text used by the default arm of the
     * attention-key evaluation at line 130 and the two screen titles the header paragraph moves at
     * lines 247 and 248.
     */
    private final MessageCatalogService messageCatalogService;

    /**
     * The navigation rules, replacing the zero-length-communication-area branch at line 94 and the
     * transfer-control statement at lines 205 to 208.
     */
    private final NavigationService navigationService;

    /**
     * The clock standing in for {@code FUNCTION CURRENT-DATE} at line 245, the header paragraph's only
     * external input.
     *
     * <p>Injected rather than read from the system so a turn's header is reproducible in a test. It is
     * used for the screen header alone: no stored timestamp is ever compared with it, converted using
     * it, or re-rendered through it.
     */
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param transactionRepository the transaction file this screen reads; mandatory
     * @param messageCatalogService the common-message catalogue; mandatory
     * @param navigationService     the navigation rules; mandatory
     * @param clock                 the clock the screen header reads; mandatory
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public TransactionViewService(final TransactionRepository transactionRepository,
            final MessageCatalogService messageCatalogService,
            final NavigationService navigationService,
            final Clock clock) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository must not be null");
        this.messageCatalogService = Objects.requireNonNull(messageCatalogService,
                "messageCatalogService must not be null");
        this.navigationService = Objects.requireNonNull(navigationService,
                "navigationService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ==========================================================================================
    // The legacy two-state flags, from the level-88 condition names at lines 41, 42, 46 and 47
    // ==========================================================================================

    /**
     * {@code WS-ERR-FLG} and its two condition names, {@code ERR-FLG-ON} {@code VALUE 'Y'} at line 41
     * and {@code ERR-FLG-OFF} {@code VALUE 'N'} at line 42.
     *
     * <p>An enumeration rather than a character, so the two states are type checked and a third is not
     * representable. Every {@code SET ERR-FLG-OFF TO TRUE} and {@code MOVE 'Y' TO WS-ERR-FLG} in the
     * member becomes an assignment of a constant, and the two {@code IF NOT ERR-FLG-ON} gates at lines
     * 158 and 176 become {@link #isOff()}.
     */
    public enum ErrorFlag {

        /** {@code ERR-FLG-OFF}, the state the main paragraph establishes at line 88. */
        OFF,

        /** {@code ERR-FLG-ON}, raised by each of the four failure sites at lines 129, 148, 284 and 291. */
        ON;

        /**
         * Reports whether the flag is raised, the condition {@code ERR-FLG-ON} tests.
         *
         * @return {@code true} when the flag is {@link #ON}
         */
        public boolean isOn() {
            return this == ON;
        }

        /**
         * Reports whether the flag is clear, the condition {@code NOT ERR-FLG-ON} tests at lines 158
         * and 176.
         *
         * @return {@code true} when the flag is {@link #OFF}
         */
        public boolean isOff() {
            return this == OFF;
        }
    }

    /**
     * {@code WS-USR-MODIFIED} and its two condition names, {@code USR-MODIFIED-YES} {@code VALUE 'Y'}
     * at line 46 and {@code USR-MODIFIED-NO} {@code VALUE 'N'} at line 47.
     *
     * <p><strong>The raised state is unreachable in this member and is preserved rather than
     * removed.</strong> The flag is declared at line 45, cleared by {@code SET USR-MODIFIED-NO TO TRUE}
     * at line 89, and then never tested and never raised anywhere in the remaining 240 lines &mdash; a
     * vestige of the update programs, where an equivalent flag guards a rewrite. A view transaction
     * modifies nothing, so there is nothing here to raise it. Both constants are declared because the
     * legacy field has a two-state domain, and the outcome reports the flag so the reproduction is
     * observable rather than asserted; it is always clear, which is exactly what the source produces.
     */
    public enum UserModifiedFlag {

        /** {@code USR-MODIFIED-NO}, the state line 89 establishes and the only reachable one. */
        NO,

        /** {@code USR-MODIFIED-YES}, declared by the source and raised by no statement in this member. */
        YES;

        /**
         * Reports whether the operator's input was marked as modified.
         *
         * @return {@code true} when the flag is {@link #YES}, which no path in this member produces
         */
        public boolean isYes() {
            return this == YES;
        }
    }

    // ==========================================================================================
    // The screen contract, as values
    // ==========================================================================================

    /**
     * One inbound turn of the transaction-view screen: the transmitted search field, the identifier the
     * list screen may have carried forward, the attention key that arrived, and the navigation state
     * echoed by the client in place of the legacy communication area.
     *
     * <p>Every component may be {@code null}, because a 3270 field the terminal did not transmit
     * arrives as low values rather than as spaces and an omitted JSON component is the same state. No
     * component is bounded, trimmed or normalised by the caller; deciding what a value means is this
     * service's job.
     *
     * <p>The attention key arrives already decoded. Decoding a raw terminal identifier is not this
     * member's concern &mdash; it includes no attention-key copybook &mdash; and a {@code null} key
     * means none was decoded, which the dispatch treats as an unmapped key exactly as the source's
     * catch-all arm at lines 128 to 131 does.
     *
     * @param transactionIdInput    {@code TRNIDINI} of the input map, {@code PIC X(16)} at
     *                              {@code app/cpy-bms/COTRN01.CPY} line 60: the identifier the operator
     *                              keyed. Read on every enter-key path, and the only source consulted
     *                              on a re-entry
     * @param selectedTransactionId {@code CDEMO-CT01-TRN-SELECTED}, {@code PIC X(16)} at line 61: the
     *                              identifier the transaction-list screen carried forward through the
     *                              communication area. Consulted on a first entry only, where it takes
     *                              precedence over the search field per lines 103 to 106
     * @param keyAction             the decoded attention key, or {@code null} when none was decoded
     * @param navigationContext     the echoed navigation state, or {@code null} for a turn carrying
     *                              none, which is the zero-length communication area of line 94
     */
    public record TransactionViewInput(String transactionIdInput,
                                       String selectedTransactionId,
                                       KeyAction keyAction,
                                       NavigationContext navigationContext) {
    }

    /**
     * The screen header as {@code POPULATE-HEADER-INFO} leaves it, from lines 243 to 262.
     *
     * <p>Six values, none of which is derived from the transaction: two catalogue titles, this
     * program's own transaction identifier and name, and the clock date and time. The clock is read
     * afresh on every send, so a turn that sends more than once carries the last send's reading.
     *
     * @param title01         {@code CCDA-TITLE01} as moved to {@code TITLE01O} at line 247
     * @param title02         {@code CCDA-TITLE02} as moved to {@code TITLE02O} at line 248
     * @param transactionName {@code WS-TRANID} as moved to {@code TRNNAMEO} at line 249
     * @param programName     {@code WS-PGMNAME} as moved to {@code PGMNAMEO} at line 250
     * @param currentDate     {@code CURDATEO} as assembled at lines 252 to 256, in {@code MM/DD/YY}
     *                        form with the year reduced to its low-order two digits exactly as
     *                        {@code WS-CURDATE-YEAR(3:2)} reduces it
     * @param currentTime     {@code CURTIMEO} as assembled at lines 258 to 262, in {@code HH:MM:SS}
     *                        form on a 24-hour clock
     */
    public record ScreenHeader(String title01,
                               String title02,
                               String transactionName,
                               String programName,
                               String currentDate,
                               String currentTime) {
    }

    /**
     * The transaction record as the read returned it, carried at the record's own widths and never at
     * the screen's.
     *
     * <p>Component order follows the order of the thirteen moves at lines 178 to 190, so a reviewer can
     * read the two side by side. Every value is exactly what the row holds: nothing is trimmed, padded,
     * re-scaled, reformatted or parsed on the way here, which is what makes the four guarantees in this
     * class's own documentation checkable rather than merely stated.
     *
     * <p><strong>Five widths are narrower on the screen than in the record and this record carries the
     * record's.</strong> The description is 60 on the screen against 100 here, the merchant name 30
     * against 50, the merchant city 25 against 50, and both timestamps 10 against 26 &mdash; from
     * {@code app/cpy-bms/COTRN01.CPY} lines 96, 108, 114, 126 and 132 against
     * {@code app/cpy/CVTRA05Y.cpy}. Applying the screen bound is the presentation layer's step, and
     * deferring it is deliberate: a 10-character timestamp could not satisfy the guarantee that a
     * stored stamp comes back byte for byte.
     *
     * <p><strong>The rendered form of the amount is deliberately absent.</strong> Line 177 moves the
     * amount through {@code WS-TRAN-AMT}, a {@code PIC +99999999.99} edited field declared at line 49,
     * before the screen receives it. That mask carries a forced sign and only <em>eight</em> integer
     * digits against the record field's nine, so a nine-digit amount loses its high-order digit on the
     * screen. It is an editing anomaly of the presentation, not of the value, and it is recorded in the
     * decision log; this record publishes the numeric value alone and lets the presentation layer
     * decide how to edit it.
     *
     * @param transactionId        {@code TRAN-ID}, 16 characters, moved at line 178. <strong>Alphanumeric,
     *                             not numeric</strong>: leading zeros are part of the key and this class
     *                             never parses it to a number
     * @param cardNumber           {@code TRAN-CARD-NUM}, 16 characters, moved at line 179
     * @param typeCode             {@code TRAN-TYPE-CD}, 2 characters, moved at line 180
     * @param categoryCode         {@code TRAN-CAT-CD}, 4 characters, moved at line 181
     * @param source               {@code TRAN-SOURCE}, 10 characters, moved at line 182. Raw and
     *                             space-padded: <strong>never trimmed and never an enumeration
     *                             here</strong>, so its padding survives for a byte-parity check
     * @param amount               {@code TRAN-AMT}, moved at line 183 by way of the edited field. A
     *                             {@code BigDecimal} at the record's scale of two, legitimately negative
     *                             for a refund, and never a floating-point type
     * @param description          {@code TRAN-DESC}, 100 characters, moved at line 184
     * @param originationTimestamp {@code TRAN-ORIG-TS}, 26 characters at record offset 278, moved at
     *                             line 185. <strong>Returned exactly as stored</strong>, in whichever of
     *                             the estate's two 26-character forms wrote it
     * @param processingTimestamp  {@code TRAN-PROC-TS}, 26 characters at record offset 304, moved at
     *                             line 186, and equally untouched. May be entirely blank, which is the
     *                             state of a transaction that has not been processed
     * @param merchantId           {@code TRAN-MERCHANT-ID}, 9 characters, moved at line 187
     * @param merchantName         {@code TRAN-MERCHANT-NAME}, 50 characters, moved at line 188
     * @param merchantCity         {@code TRAN-MERCHANT-CITY}, 50 characters, moved at line 189
     * @param merchantZip          {@code TRAN-MERCHANT-ZIP}, 10 characters, moved at line 190
     */
    public record TransactionProjection(String transactionId,
                                        String cardNumber,
                                        String typeCode,
                                        String categoryCode,
                                        String source,
                                        BigDecimal amount,
                                        String description,
                                        String originationTimestamp,
                                        String processingTimestamp,
                                        String merchantId,
                                        String merchantName,
                                        String merchantCity,
                                        String merchantZip) {

        /**
         * Fixed stand-in emitted by {@link #toString()} in place of each regulated component, matching
         * the placeholder every redacting contract in this module uses so that the absence of a
         * regulated value is auditable by one search.
         */
        private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

        /**
         * Renders the projection with every regulated component withheld.
         *
         * <p>The generated rendering could not stand: this record <em>is</em> cardholder data, and a
         * single stray interpolation of it into a log record would disclose a card number, an amount
         * and a merchant. The three components that survive &mdash; the type code, the category code
         * and the source &mdash; are reference codes drawn from small published tables and identify no
         * cardholder, no card and no transaction, which is the same line the presentation record for
         * this screen draws. A constant is used rather than any transformation of the withheld value,
         * so neither its length nor a prefix nor a digest can be recovered from a stringified
         * instance.
         *
         * @return a rendering safe to place in a diagnostic
         */
        @Override
        public String toString() {
            return "TransactionProjection["
                    + "transactionId=" + REDACTION_PLACEHOLDER
                    + ", cardNumber=" + REDACTION_PLACEHOLDER
                    + ", typeCode=" + this.typeCode
                    + ", categoryCode=" + this.categoryCode
                    + ", source=" + this.source
                    + ", amount=" + REDACTION_PLACEHOLDER
                    + ", description=" + REDACTION_PLACEHOLDER
                    + ", originationTimestamp=" + REDACTION_PLACEHOLDER
                    + ", processingTimestamp=" + REDACTION_PLACEHOLDER
                    + ", merchantId=" + REDACTION_PLACEHOLDER
                    + ", merchantName=" + REDACTION_PLACEHOLDER
                    + ", merchantCity=" + REDACTION_PLACEHOLDER
                    + ", merchantZip=" + REDACTION_PLACEHOLDER
                    + "]";
        }
    }

    /**
     * The outcome of one turn of the transaction-view screen.
     *
     * <p>Every path produces one of these and no path throws an exception of this service's own: a blank
     * identifier, an identifier that is too wide to be a key, an identifier that names no row and a
     * failed lookup are all reported here, exactly as the legacy reported each of them on the screen.
     *
     * @param route                 the destination the turn leads to. This screen's own destination on
     *                              every path that re-presents it, because the return at lines 136 to
     *                              139 re-arms this same transaction, and a different destination only
     *                              where control was transferred
     * @param navigationContext     the navigation state the turn carries forward, never {@code null}
     * @param reArmedTransactionId  {@code WS-TRANID} where the turn ended with the pseudo-conversational
     *                              return of lines 136 to 139, and empty where it transferred control
     *                              instead, since a transfer carries the communication area but re-arms
     *                              no transaction
     * @param searchTransactionId   the search field as the turn leaves it. Populated with the identifier
     *                              that was looked up, so a record-absent turn still shows the operator
     *                              what was searched for, and blank after the clear key, which blanks it
     *                              at line 312
     * @param transaction           the record that was read, or {@code null} when none was &mdash;
     *                              because no identifier was submitted, because it was refused, because
     *                              no row matched, or because the lookup failed. The source expresses
     *                              the same distinction by blanking the thirteen display fields at lines
     *                              159 to 171 before every read and filling them only at lines 178 to
     *                              190 once one succeeded
     * @param message               {@code WS-MESSAGE}, {@code PIC X(80)} at line 38, in its authored
     *                              rather than padded form, and empty when the turn produced no message.
     *                              The <strong>first</strong> failure's text on a turn that produced
     *                              more than one
     * @param focusField            the screen field the cursor is positioned on, from the corresponding
     *                              {@code MOVE -1} to a field's length item. Always the search field in
     *                              this member, and empty only where control was transferred
     * @param errorFlag             {@code WS-ERR-FLG} as the turn leaves it: {@code true} when any of
     *                              the four failure sites was reached
     * @param userModified          {@code WS-USR-MODIFIED} as the turn leaves it. Always {@code false},
     *                              because line 89 clears the flag and no statement in the member raises
     *                              it; reported so the faithful reproduction of a vestigial field is
     *                              observable
     * @param reEntry               the program-context gate, {@code true} once the turn has set
     *                              {@code CDEMO-PGM-REENTER} at line 100 or found it already set. It is
     *                              raised <em>before</em> the enter-key path runs on a first entry, which
     *                              is why per-field detail can only ever accompany a raised gate
     * @param fieldErrors           the per-field detail for the search field, unmodifiable and never
     *                              {@code null}: one entry stating that the field was not supplied, or
     *                              one stating that it was supplied wrongly, and empty on every other
     *                              path. An identifier that names no row is an outcome and not an edit
     *                              failure, so it adds no entry
     * @param header                the screen header as the header paragraph populated it, and
     *                              {@code null} on a path that transferred control without sending
     */
    public record TransactionViewResult(NavigationService.Route route,
                                        NavigationContext navigationContext,
                                        String reArmedTransactionId,
                                        String searchTransactionId,
                                        TransactionProjection transaction,
                                        String message,
                                        String focusField,
                                        boolean errorFlag,
                                        boolean userModified,
                                        boolean reEntry,
                                        List<ValidationException.FieldError> fieldErrors,
                                        ScreenHeader header) {

        /**
         * Fixed stand-in emitted by {@link #toString()} in place of the search identifier.
         */
        private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

        /**
         * Normalises the per-field detail to an unmodifiable, never-{@code null} list, so a consumer
         * never has to null-check it and a caller cannot mutate a returned outcome.
         *
         * <p>An absent list is treated as no detail rather than rejected, because most paths of this
         * screen legitimately produce none. A {@code null} element is rejected by the copy, which is
         * correct: an entry with no field state cannot describe a state the legacy screen can be in.
         */
        public TransactionViewResult {
            fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
        }

        /**
         * The record that was read, as an optional.
         *
         * <p>Offered alongside the nullable component so a consumer can branch on presence without a
         * null test. Absence is not a failure on its own: a first entry that carried no selection
         * completes normally with nothing retrieved and no message at all.
         *
         * @return the retrieved record, or an empty optional when the turn retrieved none
         */
        public Optional<TransactionProjection> retrievedTransaction() {
            return Optional.ofNullable(this.transaction);
        }

        /**
         * Renders the outcome with the search identifier withheld.
         *
         * <p>The identifier is a transaction identifier whether it named a row or not, so it is
         * withheld on the same terms as the projection withholds the one it retrieved. The projection
         * and the navigation state redact themselves, so no regulated value reaches a diagnostic
         * through this rendering by any path.
         *
         * @return a rendering safe to place in a diagnostic
         */
        @Override
        public String toString() {
            return "TransactionViewResult["
                    + "route=" + this.route
                    + ", navigationContext=" + this.navigationContext
                    + ", reArmedTransactionId=" + this.reArmedTransactionId
                    + ", searchTransactionId=" + REDACTION_PLACEHOLDER
                    + ", transaction=" + this.transaction
                    + ", message=" + this.message
                    + ", focusField=" + this.focusField
                    + ", errorFlag=" + this.errorFlag
                    + ", userModified=" + this.userModified
                    + ", reEntry=" + this.reEntry
                    + ", fieldErrors=" + this.fieldErrors
                    + ", header=" + this.header
                    + "]";
        }
    }

    // ==========================================================================================
    // Entry point: the procedure division, line 85
    // ==========================================================================================

    /**
     * Runs one turn of the transaction-view screen.
     *
     * <p>This is the procedure division: it establishes the working storage the legacy declares at lines
     * 35 to 61, runs the main paragraph, and then performs the terminal
     * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} at lines 136 to 139 by
     * re-arming the transaction. Nothing is retained between calls, so two concurrent turns are wholly
     * independent.
     *
     * <p><strong>Read-only, and enforced rather than asserted.</strong> The transaction is declared
     * read-only, and the single repository call on any path is a lookup by primary key. No path stores,
     * deletes, flushes or issues a modifying query.
     *
     * <p><strong>The turn always completes normally.</strong> Every failure this screen can encounter
     * &mdash; a blank identifier, an over-wide identifier, an identifier that matches no row, and a
     * failure of the lookup itself &mdash; is reported in the returned value with the message and cursor
     * position the source produces, because that is what the source does: it raises its error flag,
     * writes its message and re-sends the same screen. In particular an absent row is
     * <strong>not</strong> an escaping exception. The one exception that can leave this method is raised
     * by the navigation rules when the state a client echoed nominates a program that resolves to no
     * destination, which is the transfer the legacy would have attempted and abended on; that failure
     * belongs to those rules and this member neither provokes it nor suppresses it.
     *
     * @param input the transmitted screen, the decoded attention key and the echoed navigation state;
     *              must not be {@code null}
     * @return the outcome of the turn, never {@code null}
     * @throws NullPointerException if {@code input} is {@code null}
     */
    @Transactional(readOnly = true)
    public TransactionViewResult viewTransaction(final TransactionViewInput input) {
        Objects.requireNonNull(input, "input must not be null");

        final TurnState state = new TurnState();
        mainPara(state, input);

        // EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA) at lines 136 to 139. The
        // transfer paths have already left the program, so the re-arm is suppressed for them.
        returnToCics(state);

        LOG.debug("Transaction-view turn complete: route={} reEntry={} recordRetrieved={} errorFlag={}"
                        + " focusField={} fieldErrors={}",
                state.route.getRouteValue(), state.context.reEntry(), state.record != null,
                state.errorFlag.isOn(), state.focusField, state.fieldErrors.size());
        return state.toResult();
    }

    // ==========================================================================================
    // Paragraph 1 of 9 - MAIN-PARA, line 86
    // ==========================================================================================

    /**
     * The main paragraph at line 86.
     *
     * <p>Clears both flags and blanks both message fields at lines 88 to 92; routes a turn carrying no
     * navigation state to sign-on at lines 94 to 96; otherwise takes the echoed state at line 98 and
     * branches on the re-enter gate at line 99.
     *
     * <p><strong>The first-entry branch can perform a lookup, and that is easy to miss.</strong> Lines
     * 99 to 109 set the gate, clear the outbound map, position the cursor on the search field, and then
     * &mdash; only when the carried selection is present &mdash; move that selection into the search
     * field and run the enter-key path, before sending. So an operator arriving from the transaction
     * list sees the record on the very first turn, and a selection naming no row produces the
     * record-absent message on the first turn too.
     *
     * <p><strong>The gate is raised before that lookup runs, at line 100 against line 107.</strong> That
     * ordering is what makes the re-enter gate structural here: per-field detail can only be produced by
     * the enter-key path, and every route into that path has the gate already raised. There is
     * therefore no separate runtime test guarding decoration, and none is invented.
     *
     * <p><strong>A send does not end the task in this member.</strong> Unlike several of its siblings,
     * the send paragraph here ends with the map transmission and no jump, so control returns to the
     * performing paragraph and the turn continues to the return at line 136. One consequence is
     * visible: on the first-entry-with-selection path the enter-key path sends at line 152 or 191 and
     * line 109 then sends again. Both sends render the same working storage, so the operator-visible
     * content is identical; only the header clock is read afresh. That is reproduced rather than
     * optimised away, and it is why nothing here latches a send as terminal.
     *
     * <p>The attention-key evaluation at lines 112 to 132 has five arms in this member &mdash; the enter
     * key, the third, fourth and fifth program-function keys, and a catch-all. Clause order is preserved
     * because a COBOL evaluation stops at its first match, and the catch-all maps to the default arm. A
     * key that was never decoded reaches the same arm, because an absent key is none of the four the
     * source names. Note that the clear key has <em>no</em> arm of its own here and so produces the
     * invalid-key message; adding one would change observable output.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen and echoed navigation state
     */
    private void mainPara(final TurnState state, final TransactionViewInput input) {
        // SET ERR-FLG-OFF TO TRUE at line 88 and SET USR-MODIFIED-NO TO TRUE at line 89; MOVE SPACES TO
        // WS-MESSAGE and to ERRMSGO OF COTRN1AO at lines 91 and 92. The state is constructed in exactly
        // that condition, so the clearing needs no separate statement.
        if (isNavigationStateAbsent(input.navigationContext())) {
            // IF EIBCALEN = 0 at line 94, then MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM at line 95. The
            // destination is the one the navigation rules hold for a turn carrying no state, so no
            // program name is written here as a literal.
            state.context = withNominatedProgram(NavigationContext.empty(),
                    navigationService.resolveAbsentContextRoute().getLegacyProgramName());
            returnToPrevScreen(state);
            return;
        }

        // MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA at line 98.
        state.context = input.navigationContext();

        if (state.context.firstEntry()) {
            firstEntry(state, input);
            return;
        }

        receiveTrnviewScreen(state, input);

        // EVALUATE EIBAID at lines 112 to 132, clause order preserved and WHEN OTHER mapped to default.
        switch (input.keyAction()) {
            case ENTER -> processEnterKey(state);
            case PFK03 -> {
                // WHEN DFHPF3 at lines 115 to 122. Both arms of the test at lines 116 to 121 are the
                // back-navigation rule: the originating program when the state names one, and this
                // screen's own default of the user main menu when it does not. The default is per
                // screen, which is why it is passed rather than assumed.
                state.context = withNominatedProgram(state.context,
                        navigationService.resolveBackNavigation(carriedState(state.context),
                                NavigationService.Route.USER_MENU).getLegacyProgramName());
                returnToPrevScreen(state);
            }
            case PFK04 -> clearCurrentScreen(state);
            case PFK05 -> {
                // WHEN DFHPF5 at lines 125 to 127: MOVE 'COTRN00C' TO CDEMO-TO-PROGRAM, the
                // transaction-list screen this one is reached from, named through the navigation
                // vocabulary rather than as a literal.
                state.context = withNominatedProgram(state.context,
                        NavigationService.Route.TRANSACTION_LIST.getLegacyProgramName());
                returnToPrevScreen(state);
            }
            case null, default -> {
                // WHEN OTHER at lines 128 to 131: MOVE 'Y' TO WS-ERR-FLG, then the catalogue message.
                // It is carried at its full contractual width of fifty characters and is deliberately
                // not trimmed.
                state.raiseError(messageCatalogService.invalidKeyMessage(), FIELD_TRANSACTION_ID);
                sendTrnviewScreen(state);
            }
        }
    }

    /**
     * The first-entry branch of the main paragraph, lines 99 to 109.
     *
     * <p>Not a paragraph of its own in the source: it is the {@code IF NOT CDEMO-PGM-REENTER} arm,
     * factored out so that the main paragraph's attention-key evaluation reads as the single decision it
     * is. The four statements are reproduced in order &mdash; raise the gate at line 100, clear the
     * outbound map at line 101, position the cursor at line 102, and consult the carried selection at
     * lines 103 to 108 &mdash; followed by the send at line 109.
     *
     * <p>Clearing the outbound map needs no statement here: the outbound values are projected from this
     * working storage, which holds no record on a first entry, so the map is already blank.
     *
     * <p><strong>The precedence rule, stated exactly.</strong> The carried selection wins when it is
     * present, and the transmitted search field is what is used otherwise. The two are never consulted
     * on the same turn, because the source only receives a map on a re-entry: this branch reads the
     * selection and never the transmitted field, and the re-entry branch reads the transmitted field and
     * never the selection. So a first entry whose selection is absent leaves the search field
     * <em>blank</em> and looks nothing up &mdash; the enter-key path at line 107 sits inside the
     * selection test at line 103 and is not reached &mdash; and the operator is shown the empty screen
     * to key into, which is what {@code MOVE LOW-VALUES TO COTRN1AO} at line 101 puts on the wire.
     * Carrying a transmitted value forward on this path instead would put a value on the screen that the
     * legacy blanked, so it is not done.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen, for the carried selection
     */
    private void firstEntry(final TurnState state, final TransactionViewInput input) {
        // SET CDEMO-PGM-REENTER TO TRUE at line 100.
        state.context = state.context.withReEntry();

        // MOVE -1 TO TRNIDINL OF COTRN1AI at line 102.
        state.focusField = FIELD_TRANSACTION_ID;

        // IF CDEMO-CT01-TRN-SELECTED NOT = SPACES AND LOW-VALUES at lines 103 and 104.
        if (isSupplied(input.selectedTransactionId())) {
            // MOVE CDEMO-CT01-TRN-SELECTED TO TRNIDINI OF COTRN1AI at lines 105 and 106: the carried
            // selection overwrites the search field, which is the whole of the precedence rule.
            state.searchTransactionId = input.selectedTransactionId();
            processEnterKey(state);
        }

        // PERFORM SEND-TRNVIEW-SCREEN at line 109, reached whether or not the selection was consulted.
        sendTrnviewScreen(state);
    }

    // ==========================================================================================
    // Paragraph 2 of 9 - PROCESS-ENTER-KEY, line 144
    // ==========================================================================================

    /**
     * The enter-key paragraph at line 144: edit the identifier, read the record, publish it.
     *
     * <p>Three sentences in the source and three stages here, each gated on the error flag exactly as the
     * source gates it.
     *
     * <p><strong>Stage one, lines 146 to 156</strong>, is an {@code EVALUATE TRUE} with two arms whose
     * order is contractual. The first tests the search field for blankness &mdash;
     * {@code = SPACES OR LOW-VALUES}, so an untransmitted field counts as blank &mdash; and on a match
     * raises the flag, writes the emptiness text, positions the cursor and sends. The catch-all merely
     * positions the cursor and continues. Because the arms are tested against a truth value rather than
     * against a selector, the Java form is an ordered test and its catch-all is the final branch; a
     * selector would have to be invented and there is none.
     *
     * <p><strong>Stage two, lines 158 to 174</strong>, runs only when the flag is still clear. It blanks
     * the thirteen display fields at lines 159 to 171 <em>before</em> the read, which is what leaves a
     * record-absent turn showing the search key with every record field empty; it moves the search field
     * into the record key at line 172; and it reads.
     *
     * <p><strong>Stage three, lines 176 to 192</strong>, is a second sentence with its own gate, so a
     * failed read skips it and leaves the failure text standing. It moves the amount through the edited
     * field at line 177 and then publishes the thirteen values at lines 178 to 190, and sends.
     *
     * <p><strong>The format check, and the one divergence it introduces.</strong> The identifier the
     * screen accepts is sixteen characters wide, three times over: the record key is {@code PIC X(16)},
     * the search field is {@code PIC X(16)}, and the read passes {@code KEYLENGTH(LENGTH OF TRAN-ID)}. A
     * 3270 terminal cannot transmit more than the field holds, so the source has no test for a wider
     * value and needs none. A machine client can send one, and there are only two things to do with it:
     * cut it to sixteen characters and read, or refuse it. <strong>This class refuses it</strong>, marking
     * the field as supplied wrongly and reporting the record-absent text, because cutting is the more
     * dangerous of the two &mdash; a seventeen-character value whose first sixteen characters happen to
     * match a stored key would return <em>a different transaction than the one asked for</em>, silently
     * and with no indication that anything was discarded. Refusing cannot be silent. The record-absent
     * text is reused rather than a new one invented: the key space is exactly sixteen characters wide, so
     * a wider value names no record, which is precisely what that text says, and the screen's message
     * vocabulary stays the three literals the member declares. The divergence is recorded in the decision
     * log.
     *
     * <p><strong>No other edit is applied, deliberately.</strong> The record key is {@code PIC X(16)},
     * an alphanumeric field with no character-class constraint anywhere in the source, so no digit test,
     * case test or pattern test is added. Adding one would reject identifiers the legacy screen accepts,
     * which is a behavioural regression rather than a hardening.
     *
     * @param state the turn's working storage
     */
    private void processEnterKey(final TurnState state) {
        // EVALUATE TRUE at line 146. Clause order preserved: the blank test at line 147 first, the
        // catch-all at line 153 second.
        if (isBlankField(state.searchTransactionId)) {
            // Lines 148 to 152: MOVE 'Y' TO WS-ERR-FLG, the emptiness text, MOVE -1 TO TRNIDINL, send.
            faultField(state, MSG_TRAN_ID_EMPTY, ValidationException.FieldState.MISSING);
            return;
        }

        // WHEN OTHER at lines 153 to 155: MOVE -1 TO TRNIDINL, then CONTINUE. The cursor is positioned
        // on the search field whether the edit passed or failed, which is the only thing this arm does.
        state.focusField = FIELD_TRANSACTION_ID;

        // The fixed-width key bound. Not part of the source's cascade because the source's screen makes
        // it unreachable; see this method's documentation for why it is refused rather than cut.
        if (state.searchTransactionId.length() > TRANSACTION_ID_LENGTH) {
            LOG.debug("Refusing a transaction identifier wider than the {}-character key space:"
                    + " submittedLength={}", TRANSACTION_ID_LENGTH, state.searchTransactionId.length());
            faultField(state, MSG_TRAN_ID_NOT_FOUND, ValidationException.FieldState.INVALID);
            return;
        }

        // IF NOT ERR-FLG-ON at line 158. Reached only with the flag clear, since both failure paths
        // above have returned, but the gate is stated because the source states it.
        if (state.errorFlag.isOff()) {
            // MOVE SPACES TO the thirteen display fields at lines 159 to 171. Holding no record is how
            // this state expresses a blank display, so clearing it is a single assignment.
            state.record = null;

            // MOVE TRNIDINI OF COTRN1AI TO TRAN-ID at line 172. Character for character: the key is
            // alphanumeric and is never parsed, so a leading zero is part of it.
            state.tranId = state.searchTransactionId;
            readTransactFile(state);
        }

        // IF NOT ERR-FLG-ON at line 176, the second sentence's own gate.
        if (state.errorFlag.isOff()) {
            // MOVE TRAN-AMT TO WS-TRAN-AMT at line 177, then the thirteen moves at lines 178 to 190.
            publishRecord(state);
            sendTrnviewScreen(state);
        }
    }

    /**
     * The thirteen moves of the enter-key paragraph's third sentence, lines 177 to 190.
     *
     * <p>Not a paragraph of its own: it is the body of the second {@code IF NOT ERR-FLG-ON}, factored out
     * so the paragraph's three stages stay legible. It is reached only with a record in hand.
     *
     * <p><strong>Every value crosses unchanged but one, and that one is the amount.</strong> Line 177
     * moves the amount into a two-decimal receiving field before the screen sees it, so the store is
     * reproduced through the module's decimal codec, which is the only component permitted to impose a
     * scale and which truncates toward zero rather than rounding. Truncation is correct and not an
     * approximation: the keyword that would request rounding appears <strong>zero</strong> times
     * anywhere in the estate, so every COBOL store into a two-decimal field truncates, and rounding here
     * would differ by a cent from the legacy on half of all values. A value already at the record's scale
     * is unaffected, which is every value a stored row can hold; the store matters for a value assembled
     * in memory at another scale. An absent amount is carried through as absent rather than passed to the
     * codec, which rejects one &mdash; the column forbids it, so this guards a hand-built record and not
     * a stored one.
     *
     * <p>The other twelve values are handed over exactly as the row holds them. In particular the two
     * timestamps are not touched, the source is not trimmed, and the identifier is not parsed.
     *
     * @param state the turn's working storage, holding the record the read returned
     */
    private void publishRecord(final TurnState state) {
        final Transaction read = state.record;
        final BigDecimal storedAmount = read.getTranAmt();

        state.projection = new TransactionProjection(read.getTranId(),
                read.getTranCardNum(),
                read.getTranTypeCd(),
                read.getTranCatCd(),
                read.getTranSource(),
                (storedAmount == null) ? null : ZonedDecimalCodec.toMonetaryScale(storedAmount),
                read.getTranDesc(),
                read.getTranOrigTs(),
                read.getTranProcTs(),
                read.getMerchantId(),
                read.getMerchantName(),
                read.getMerchantCity(),
                read.getMerchantZip());
    }

    // ==========================================================================================
    // Paragraph 3 of 9 - RETURN-TO-PREV-SCREEN, line 197
    // ==========================================================================================

    /**
     * The transfer paragraph at lines 197 to 208.
     *
     * <p>Lines 199 to 201 default an unnominated destination to sign-on, lines 202 to 204 stamp this
     * screen as the originator and reset the program context to its first-entry value, and lines 205 to
     * 208 transfer control. A transfer carries the communication area but does not re-arm a transaction,
     * so the turn reports no re-armed identifier and the pseudo-conversational return at line 136 is
     * never reached.
     *
     * <p>Resetting the program context is what makes the destination screen see a first entry, so it
     * clears its own map and positions its own cursor rather than treating the turn as a re-submission.
     *
     * @param state the turn's working storage
     */
    private void returnToPrevScreen(final TurnState state) {
        // IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES, MOVE 'COSGN00C' at lines 199 to 201. Both arms are
        // the nominated-destination rule, with sign-on as this screen's default.
        final NavigationService.Route destination = navigationService
                .resolveNominatedDestination(carriedState(state.context), NavigationService.Route.SIGN_ON);

        // MOVE WS-TRANID TO CDEMO-FROM-TRANID, MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM and MOVE ZEROS TO
        // CDEMO-PGM-CONTEXT at lines 202 to 204.
        state.context = withOriginatingProgram(
                withNominatedProgram(state.context, destination.getLegacyProgramName()))
                .withFirstEntry();

        // EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA) at lines 205 to 208.
        state.route = destination;
        state.transferred = true;
        LOG.debug("Transferring control from the transaction-view screen to route {}",
                destination.getRouteValue());
    }

    // ==========================================================================================
    // Paragraph 4 of 9 - SEND-TRNVIEW-SCREEN, line 213
    // ==========================================================================================

    /**
     * The send paragraph at lines 213 to 225.
     *
     * <p>It populates the header at line 215, moves the eighty-character message work field into the
     * outbound message field at line 217, and transmits the map with the erase and cursor options at
     * lines 219 to 225.
     *
     * <p><strong>The send is not terminal in this member</strong>, and that is a genuine difference from
     * several of its siblings: the paragraph ends with the transmission and no jump, so control returns to
     * whichever paragraph performed it and the turn runs on to the return at line 136. A turn can
     * therefore send more than once &mdash; the first-entry-with-selection path does &mdash; and each
     * send renders the same working storage, so the operator-visible content of the last send is what the
     * screen carries. Nothing here latches the turn as over.
     *
     * <p>Two of the paragraph's steps have no equivalent to model. The outbound message field is
     * {@code PIC X(78)} against the work field's {@code PIC X(80)}, and bounding a value to a screen width
     * is the presentation layer's step, so the outcome carries the message in its authored form and the
     * presentation record declares the bound. The erase and cursor options are 3270 attributes with no
     * machine-contract equivalent; the cursor's <em>position</em> is carried, which is the part that
     * conveys meaning.
     *
     * @param state the turn's working storage
     */
    private void sendTrnviewScreen(final TurnState state) {
        // PERFORM POPULATE-HEADER-INFO at line 215.
        populateHeaderInfo(state);

        // MOVE WS-MESSAGE TO ERRMSGO OF COTRN1AO at line 217. The message is already held in the state
        // that the outcome publishes, so the move needs no second field here.
        state.screenSent = true;
    }

    // ==========================================================================================
    // Paragraph 5 of 9 - RECEIVE-TRNVIEW-SCREEN, line 230
    // ==========================================================================================

    /**
     * The receive paragraph at lines 230 to 238.
     *
     * <p>Receives the input map into {@code COTRN1AI}. This member has exactly one field an operator can
     * key, the search field, so the receive takes one value; the twelve record fields of the map are
     * output-only and the program writes them without ever reading them back.
     *
     * <p>The response and reason codes the receive captures at lines 236 and 237 are never tested by the
     * source, so nothing is derived from them here. That is not an omission: a value the source
     * captures and ignores has no observable effect, and inventing one would be a behavioural change.
     *
     * <p>A field the client omitted arrives as absent, which the blankness test treats identically to the
     * low values a 3270 terminal would send. The value is <strong>not</strong> bounded to the field width
     * here: see {@code processEnterKey} for why an over-wide value is refused rather than cut.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen
     */
    private void receiveTrnviewScreen(final TurnState state, final TransactionViewInput input) {
        state.searchTransactionId = valueOrEmpty(input.transactionIdInput());
    }

    // ==========================================================================================
    // Paragraph 6 of 9 - POPULATE-HEADER-INFO, line 243
    // ==========================================================================================

    /**
     * The header paragraph at lines 243 to 262.
     *
     * <p>Reads the clock at line 245, moves the two catalogue titles at lines 247 and 248, this screen's
     * transaction identifier and program name at lines 249 and 250, the date as {@code MM/DD/YY} at lines
     * 252 to 256, and the time as {@code HH:MM:SS} at lines 258 to 262.
     *
     * <p><strong>One clock reading, two derived values.</strong> The source moves
     * {@code FUNCTION CURRENT-DATE} into a single work area and then takes the date and time components
     * out of it, so both describe the same instant. Reading the clock twice here could straddle a second
     * boundary and produce a header whose time does not belong to its date; one instant is taken and both
     * components are derived from it.
     *
     * <p><strong>The two-digit year is arithmetic, not a slice.</strong> Line 254 moves
     * {@code WS-CURDATE-YEAR(3:2)} &mdash; the last two character positions of a four-digit display item
     * &mdash; which for any four-digit year is the value modulo one hundred. Reducing arithmetically gives
     * exactly the same two characters without slicing a string, and it keeps the ninth-century and
     * year-2100 cases behaving as the source behaves.
     *
     * <p>Nothing in this paragraph touches a stored timestamp. The clock supplies the screen header and
     * nothing else: no stored value is parsed with it, compared against it or re-rendered through it.
     *
     * @param state the turn's working storage
     */
    private void populateHeaderInfo(final TurnState state) {
        // MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA at line 245: one reading, both components.
        final Instant reading = clock.instant();
        final LocalDate today = LocalDate.ofInstant(reading, clock.getZone());
        final LocalTime now = LocalTime.ofInstant(reading, clock.getZone());

        // MOVE CCDA-TITLE01 and CCDA-TITLE02 at lines 247 and 248, each at its contractual width of
        // forty characters and neither trimmed.
        state.title01 = messageCatalogService.screenTitle01();
        state.title02 = messageCatalogService.screenTitle02();

        // MOVE WS-TRANID TO TRNNAMEO and MOVE WS-PGMNAME TO PGMNAMEO at lines 249 and 250.
        state.transactionName = WS_TRANID;
        state.programName = WS_PGMNAME;

        // Lines 252 to 256: MM/DD/YY, the year reduced as WS-CURDATE-YEAR(3:2) reduces it.
        state.currentDate = twoDigits(today.getMonthValue())
                + HEADER_DATE_SEPARATOR
                + twoDigits(today.getDayOfMonth())
                + HEADER_DATE_SEPARATOR
                + twoDigits(today.getYear());

        // Lines 258 to 262: HH:MM:SS on a 24-hour clock.
        state.currentTime = twoDigits(now.getHour())
                + HEADER_TIME_SEPARATOR
                + twoDigits(now.getMinute())
                + HEADER_TIME_SEPARATOR
                + twoDigits(now.getSecond());
    }

    // ==========================================================================================
    // Paragraph 7 of 9 - READ-TRANSACT-FILE, line 267
    // ==========================================================================================

    /**
     * The read paragraph at lines 267 to 296: one keyed read, then a three-armed evaluation of its
     * outcome.
     *
     * <p>The read at lines 269 to 278 is a direct read of the transaction file by full key. It becomes the
     * inherited single-key lookup of the repository, which is the same operation: one row, or none, by
     * primary key. The two methods the repository declares of its own are not used here &mdash; this
     * screen generates no identifier and filters no date range.
     *
     * <p><strong>The evaluation at lines 280 to 296 has three arms and each maps to a distinct
     * outcome.</strong> Clause order is preserved and the catch-all maps to the default:
     *
     * <ul>
     *   <li>the normal response at lines 281 and 282 continues, carrying the record forward;</li>
     *   <li>the not-found response at lines 283 to 288 raises the flag, writes the record-absent text,
     *       positions the cursor and sends. <strong>An empty result is this arm</strong>, and it is
     *       reported rather than thrown: the source neither abends nor propagates here, so nothing
     *       escapes to the caller. This member has no abend path at all;</li>
     *   <li>the catch-all at lines 289 to 295 displays the two response codes and then does the same
     *       with the lookup-failure text. A failure of the data-access layer is that arm: it is caught,
     *       logged with its cause, and reported on the screen.</li>
     * </ul>
     *
     * <p><strong>The legacy read carries the update option and this one does not.</strong> Line 275
     * requests an update lock that the program never uses: it rewrites no record, deletes none, and reaches
     * no syncpoint of its own, so the lock is held for the remainder of the task and released unused. The
     * lookup here runs in a read-only transaction instead. The divergence removes contention and changes
     * no value this screen produces; it is recorded in the decision log.
     *
     * @param state the turn's working storage, holding the key to read
     */
    private void readTransactFile(final TurnState state) {
        final Optional<Transaction> found;
        try {
            // EXEC CICS READ DATASET(WS-TRANSACT-FILE) RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID) at
            // lines 269 to 278. The key is passed as the sixteen characters it is.
            found = transactionRepository.findById(state.tranId);
        } catch (final DataAccessException failure) {
            // WHEN OTHER at lines 289 to 295, the arm the DISPLAY of the response codes belongs to. The
            // cause is logged in place of those codes; no key and no record content is logged.
            LOG.error("Transaction lookup failed on resource {}: reporting the lookup-failure message"
                    + " and re-presenting the screen", WS_TRANSACT_FILE, failure);
            state.raiseError(MSG_LOOKUP_FAILED, FIELD_TRANSACTION_ID);
            sendTrnviewScreen(state);
            return;
        }

        if (found.isPresent()) {
            // WHEN DFHRESP(NORMAL) at lines 281 and 282: CONTINUE, with the record in hand.
            state.record = found.get();
            return;
        }

        // WHEN DFHRESP(NOTFND) at lines 283 to 288. An outcome rather than an edit failure, so it writes
        // the message and positions the cursor but adds no per-field entry.
        LOG.debug("Transaction lookup found no matching row: reporting the record-absent message");
        state.raiseError(MSG_TRAN_ID_NOT_FOUND, FIELD_TRANSACTION_ID);
        sendTrnviewScreen(state);
    }

    // ==========================================================================================
    // Paragraph 8 of 9 - CLEAR-CURRENT-SCREEN, line 301
    // ==========================================================================================

    /**
     * The clear paragraph at lines 301 to 304: reset every field, then send.
     *
     * <p>Two performed statements and nothing else. It is reached from the fourth program-function key
     * alone, at line 124, and it is the only path that blanks the search field as well as the display
     * fields, which is what distinguishes it from a failed lookup.
     *
     * @param state the turn's working storage
     */
    private void clearCurrentScreen(final TurnState state) {
        // PERFORM INITIALIZE-ALL-FIELDS at line 303.
        initializeAllFields(state);

        // PERFORM SEND-TRNVIEW-SCREEN at line 304.
        sendTrnviewScreen(state);
    }

    // ==========================================================================================
    // Paragraph 9 of 9 - INITIALIZE-ALL-FIELDS, line 309
    // ==========================================================================================

    /**
     * The reset paragraph at lines 309 to 326.
     *
     * <p>Positions the cursor on the search field at line 311, then blanks the search field, the thirteen
     * display fields and the message work field at lines 312 to 326. Holding no record is how this state
     * expresses a blank display, so the thirteen fields clear in one assignment.
     *
     * <p>Clearing the message is what leaves the clear key with a screen carrying no text at all, even
     * when the turn it cleared had been reporting a failure. The error flag is <em>not</em> cleared here,
     * because the source does not clear it: the flag is cleared once, at line 88, at the top of the turn.
     * Within a single turn the clear key is only ever reached with the flag already clear, since the
     * attention-key arms are mutually exclusive, so the distinction has no observable effect and is
     * preserved rather than tidied.
     *
     * @param state the turn's working storage
     */
    private void initializeAllFields(final TurnState state) {
        // MOVE -1 TO TRNIDINL OF COTRN1AI at line 311.
        state.focusField = FIELD_TRANSACTION_ID;

        // MOVE SPACES TO TRNIDINI at line 312, to the thirteen display fields at lines 313 to 325, and
        // to WS-MESSAGE at line 326.
        state.searchTransactionId = NO_MESSAGE;
        state.tranId = NO_MESSAGE;
        state.record = null;
        state.projection = null;
        state.message = NO_MESSAGE;
    }

    // ==========================================================================================
    // The terminal statement of the procedure division, lines 136 to 139
    // ==========================================================================================

    /**
     * The pseudo-conversational return at lines 136 to 139:
     * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)}.
     *
     * <p>Not a paragraph &mdash; it is the closing statement of the main paragraph &mdash; so it adds no
     * row to the paragraph traceability matrix and is named here only so the re-arm has one place to
     * happen. It re-arms this same transaction, which is why a turn that re-presented the screen reports
     * this screen's own destination.
     *
     * <p>A turn that transferred control never reaches this statement, because the transfer left the
     * program, so the re-arm is suppressed for those paths rather than overriding the destination they
     * resolved.
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
     * Records one failure of the search field: raises the error flag, latches the summary text and cursor
     * position if this is the turn's first failure, adds the per-field entry, and sends the screen.
     *
     * <p>Both edit failures of this screen do these four things in the same order, which is why they live
     * here once. It is used for an <em>edit</em> failure only: an identifier that names no row is an
     * outcome rather than a malformed field, so the read paragraph writes its message directly and adds no
     * entry.
     *
     * @param state      the turn's working storage
     * @param message    the failure's own text, byte exact
     * @param fieldState whether the field was not supplied or was supplied wrongly
     */
    private void faultField(final TurnState state, final String message,
            final ValidationException.FieldState fieldState) {
        state.raiseError(message, FIELD_TRANSACTION_ID);
        state.recordFieldError(fieldState, message);
        sendTrnviewScreen(state);
    }

    /**
     * Reports whether a field was supplied, reproducing the abbreviated combined relation
     * {@code NOT = SPACES AND LOW-VALUES} at lines 103 and 104.
     *
     * <p>That relation expands to "is not all spaces <em>and</em> is not all low values", so a field is
     * supplied when it holds at least one character that is neither. The inverse of the blank test, and
     * declared separately because the source states the condition both ways round and each reads clearly
     * only in its own direction.
     *
     * @param field the value to test, which may be {@code null}
     * @return {@code true} when the field holds something other than padding
     */
    private static boolean isSupplied(final String field) {
        return !isBlankField(field);
    }

    /**
     * Reports whether a field is blank, reproducing {@code = SPACES OR LOW-VALUES} at line 147.
     *
     * <p>Deliberately <strong>not</strong> the conventional Java blank test. A COBOL field is fixed width,
     * so it is blank when every position holds a space or a low value and not when it holds any other
     * white space: a field carrying a tab or a line feed is <em>supplied</em> as far as this screen is
     * concerned, and is looked up, and names no row. An absent or empty value counts as blank, because a
     * 3270 field the terminal did not transmit arrives as low values and an omitted component is the same
     * state.
     *
     * @param field the value to test, which may be {@code null}
     * @return {@code true} when the field is absent, empty, or made up entirely of spaces and low values
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
     * Substitutes the authored empty form for an absent value, so a field this class carries is never
     * {@code null}.
     *
     * <p>A field the client omitted and a field it sent empty are the same state on a 3270 screen, and
     * both are blank to the test above, so collapsing them costs no information.
     *
     * @param value the value to normalise, which may be {@code null}
     * @return the value, or the empty form when it is absent
     */
    private static String valueOrEmpty(final String value) {
        return (value == null) ? NO_MESSAGE : value;
    }

    /**
     * Renders a value into a two-position display field: the low-order two digits, zero filled on the
     * left.
     *
     * <p>Serves all six components of the screen header. For the month, day, hour, minute and second the
     * modulus is the identity, since none can reach three digits; for the four-digit year it reproduces
     * {@code WS-CURDATE-YEAR(3:2)} at line 254 exactly, because the last two character positions of a
     * four-digit display item are its value modulo one hundred. The magnitude is taken, matching an
     * unsigned receiving field.
     *
     * <p>Assembled by concatenation rather than by a format pattern, so the result cannot vary with the
     * ambient locale: a locale with non-Latin digits or an unexpected digit-grouping convention would
     * otherwise change a screen the legacy renders identically everywhere.
     *
     * @param value the value to render
     * @return exactly two digits
     */
    private static String twoDigits(final int value) {
        final int bounded = Math.abs(value) % TWO_DIGIT_MODULUS;
        return (bounded < SINGLE_DIGIT_BOUND)
                ? LEADING_ZERO + bounded
                : Integer.toString(bounded);
    }

    /**
     * Returns a copy of the navigation state whose nominated-destination program is the one supplied,
     * reproducing a move into {@code CDEMO-TO-PROGRAM} at lines 95, 117, 120, 126 and 200.
     *
     * <p>Every other component is carried across unchanged. The sixteen components are restated explicitly
     * because the state is an immutable record; a mutable copy would let client-echoed state be altered in
     * place.
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
     * Returns a copy of the navigation state stamped with this screen's transaction identifier and program
     * name as the originator, reproducing lines 202 and 203.
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

    // ==========================================================================================
    // Per-invocation working storage
    // ==========================================================================================

    /**
     * The working storage the legacy declares at lines 35 to 61, held per invocation so the service bean
     * itself stays stateless and two concurrent turns cannot observe one another.
     *
     * <p>Package-private mutable fields rather than accessors: this is a local scratch area belonging to
     * one call of one enclosing class, and accessors would add ceremony without adding safety.
     *
     * <p>Two groups the source declares are deliberately absent. {@code WS-RESP-CD} and
     * {@code WS-REAS-CD} at lines 43 and 44 are captured by the receive and the read and are tested only
     * as the read's three-armed outcome, which is modelled by that paragraph's control flow rather than by
     * a numeric field. And the paging components of the {@code CDEMO-CT01-INFO} group at lines 54 to 59
     * &mdash; the first and last identifier of a page, the page number and the next-page flag with its two
     * condition names &mdash; are read by <em>no</em> statement in this member: they belong to the
     * transaction-list screen, which owns the same bytes under its own names, and modelling them here would
     * be paging this screen does not do.
     */
    private static final class TurnState {

        /** {@code WS-ERR-FLG} at line 40, cleared at line 88 and raised by each failure site. */
        private ErrorFlag errorFlag = ErrorFlag.OFF;

        /**
         * {@code WS-USR-MODIFIED} at line 45, cleared at line 89 and raised by no statement in this
         * member.
         */
        private UserModifiedFlag userModified = UserModifiedFlag.NO;

        /**
         * {@code WS-MESSAGE}, {@code PIC X(80)} at line 38, blanked at line 91, in its authored rather
         * than padded form.
         */
        private String message = NO_MESSAGE;

        /** {@code TRNIDINI} of the input map: the search field as the turn leaves it. */
        private String searchTransactionId = NO_MESSAGE;

        /**
         * {@code TRAN-ID} of the record area, the key the read is issued with, as moved at line 172.
         *
         * <p>Held as the sixteen characters it is. Nothing in this class converts it to a number, so a
         * leading zero is part of the key rather than insignificant notation.
         */
        private String tranId = NO_MESSAGE;

        /**
         * {@code TRAN-RECORD}, the record area the read fills at line 271, and {@code null} until a read
         * succeeds.
         *
         * <p>Cleared before every read, which is how the blanking of the thirteen display fields at lines
         * 159 to 171 is expressed.
         */
        private Transaction record;

        /** The thirteen published values, filled by the moves at lines 177 to 190. */
        private TransactionProjection projection;

        /** The cursor position, from the corresponding {@code MOVE -1} to a field's length item. */
        private String focusField = NO_MESSAGE;

        /**
         * Whether the map has been transmitted at least once.
         *
         * <p>Recorded, but <strong>not</strong> treated as the end of the turn: the send paragraph of this
         * member ends with the transmission and no jump, so control returns to the performing paragraph
         * and a turn can send more than once. It exists so that the header, which is populated by the
         * send, is published only when a send actually happened, and a path that transferred control
         * without sending reports no header at all.
         */
        private boolean screenSent;

        /** Whether control was transferred, which is what makes the return at line 136 unreachable. */
        private boolean transferred;

        /** The transaction identifier re-armed by the return, empty when control was transferred. */
        private String reArmedTransactionId = NO_MESSAGE;

        /** {@code CARDDEMO-COMMAREA}, the navigation state the turn carries. */
        private NavigationContext context = NavigationContext.empty();

        /**
         * The destination the turn leads to, opening on this screen's own destination because the return
         * at lines 136 to 139 re-arms this same transaction, and overridden only by a transfer.
         */
        private NavigationService.Route route = NavigationService.Route.TRANSACTION_VIEW;

        /** {@code CCDA-TITLE01} as moved at line 247. */
        private String title01 = NO_MESSAGE;

        /** {@code CCDA-TITLE02} as moved at line 248. */
        private String title02 = NO_MESSAGE;

        /** {@code TRNNAMEO} as moved at line 249. */
        private String transactionName = NO_MESSAGE;

        /** {@code PGMNAMEO} as moved at line 250. */
        private String programName = NO_MESSAGE;

        /** {@code CURDATEO} as assembled at lines 252 to 256. */
        private String currentDate = NO_MESSAGE;

        /** {@code CURTIMEO} as assembled at lines 258 to 262. */
        private String currentTime = NO_MESSAGE;

        /**
         * The per-field detail. A list because the outbound contract carries one, though this screen has a
         * single keyable field and so records at most one entry per turn.
         */
        private final List<ValidationException.FieldError> fieldErrors = new ArrayList<>();

        /**
         * Raises the error flag and latches the summary text and cursor position on the turn's first
         * failure.
         *
         * <p><strong>First error wins for the summary, and every field flag is set independently.</strong>
         * That is the shape of this program family: each message assignment is reached only while the
         * message field is still blank, so the text the operator sees is the first failure's, while the
         * per-field detail accumulates on its own. Latching here is what guarantees it, rather than
         * relying on no second failure ever occurring.
         *
         * @param text        the failure's text, byte exact
         * @param cursorField the screen field the cursor is positioned on
         */
        private void raiseError(final String text, final String cursorField) {
            this.errorFlag = ErrorFlag.ON;
            if (this.message.isEmpty()) {
                this.message = text;
                this.focusField = cursorField;
            }
        }

        /**
         * Adds one per-field entry for the search field, independently of whether the summary text was
         * latched.
         *
         * @param fieldState whether the field was not supplied or was supplied wrongly
         * @param text       the field's own message
         */
        private void recordFieldError(final ValidationException.FieldState fieldState,
                final String text) {
            this.fieldErrors.add(new ValidationException.FieldError(PROPERTY_TRANSACTION_ID,
                    FIELD_TRANSACTION_ID, fieldState, text));
        }

        /**
         * Projects the working storage onto the turn's outcome.
         *
         * <p>The header is published only when a send actually happened, so a path that transferred
         * control reports none rather than reporting six empty strings that no screen ever carried. The
         * per-field detail is copied, so nothing mutable escapes.
         *
         * @return the outcome of the turn
         */
        private TransactionViewResult toResult() {
            return new TransactionViewResult(this.route,
                    this.context,
                    this.reArmedTransactionId,
                    this.searchTransactionId,
                    this.projection,
                    this.message,
                    this.focusField,
                    this.errorFlag.isOn(),
                    this.userModified.isYes(),
                    this.context.reEntry(),
                    List.copyOf(this.fieldErrors),
                    this.screenSent
                            ? new ScreenHeader(this.title01,
                                    this.title02,
                                    this.transactionName,
                                    this.programName,
                                    this.currentDate,
                                    this.currentTime)
                            : null);
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
