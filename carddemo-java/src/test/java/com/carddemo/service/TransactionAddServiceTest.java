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
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.DateFormat;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Surefire unit tests for {@link TransactionAddService}, the Java translation of
 * {@code app/cbl/COTRN02C.cbl} - legacy CICS transaction {@code CT02}, 783 source lines and 18
 * procedure-division paragraphs.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy tree is read-only reference; no
 * COBOL, copybook, JCL or CICS-definition text is transcribed here. Member names, paragraph names,
 * line numbers, field widths, record offsets and the operator-visible message literals are cited as
 * metadata only.
 *
 * <h2>A pure unit test</h2>
 * No container, no Spring context, no JDBC connection, no socket and no clock reading. All seven
 * constructor collaborators are Mockito mocks and the clock is a {@link Clock#fixed} instance, so
 * every rendered timestamp is deterministic. Nothing here extends the container-backed support bases
 * and nothing here declares a container, a Spring test annotation or a property source.
 *
 * <h2>Independent oracles</h2>
 * Every expected value is written out by hand. In particular the 26-character online timestamp literal
 * is spelled character for character rather than produced by formatting the clock's instant, so a
 * regression in the production renderer cannot be mirrored by a regression in the expectation. Where a
 * literal can be corroborated independently it is: the timestamp text is cross-checked against the
 * value measured from the delivered daily-transaction fixture, and the record widths and timestamp
 * offsets against the layout catalogue derived from {@code app/cpy/CVTRA05Y.cpy}. Neither the service
 * under test nor any production formatter, codec, template class or record mapper is ever asked what
 * the answer should be.
 *
 * <h2>The six contracts a plausible translation breaks silently</h2>
 * <ol>
 *   <li><strong>The online 26-character timestamp always carries a six-zero fraction.</strong> The work
 *       group at {@code app/cpy/CSDAT01Y.cpy} lines 42 to 55 declares a <em>space</em> at position 11
 *       and a six-digit fraction at positions 21 to 26, and the canonical construction at
 *       {@code app/cbl/COBIL00C.cbl} lines 249 to 267 moves zeros over that fraction after the time is
 *       placed. An implementation that formats real microseconds passes a zero-nanosecond clock and
 *       fails a non-zero one, which is exactly the clock this suite uses. The batch 26-character form -
 *       a hyphen before the hour and dots between the time parts - belongs to the batch tier and is
 *       proven absent here.</li>
 *   <li><strong>The date-acceptance test is two fields, not one boolean.</strong> Lines 397 to 407 and
 *       identically 417 to 427 accept outright on severity {@code 0000}; otherwise they report only
 *       when the message number is <em>not</em> {@code 2513}, so a non-zero severity carrying
 *       {@code 2513} is accepted <em>silently</em>. Both halves are asserted independently and the two
 *       non-zero outcomes are compared, because a collapsed boolean makes them identical.</li>
 *   <li><strong>The identifier is a sixteen-character business key with significant zero fill.</strong>
 *       Lines 444 to 451 browse backward to the highest key, add one, and store the result into a
 *       {@code PIC 9(16)} field; the end-of-file arm at line 689 seeds zero, so the first identifier on
 *       an empty master is {@code 0000000000000001} and never a bare {@code 1}. No sequence, no
 *       generated value: a sequence never reuses a value it has handed out, whereas this rule always
 *       reuses a gap, and the first rollback would make a sequence diverge permanently.</li>
 *   <li><strong>An absent cross-reference row is the not-found arm.</strong> Lines 591 to 596 and 624
 *       to 629 answer with a screen message and a cursor position - never an index error, never an
 *       exception, never an abend, because this member declares no abend path at all.</li>
 *   <li><strong>The amount truncates toward zero.</strong> The estate carries no rounding directive on
 *       any arithmetic statement anywhere, so a value with a third decimal digit loses it rather than
 *       rounding up. The discriminating case is asserted, not an ambiguous one.</li>
 *   <li><strong>Fixed-width fields keep their padding.</strong> The source code keeps its trailing
 *       spaces, the category code keeps its leading zeros, and every width assertion is made on the
 *       encoded byte image rather than on a character count.</li>
 * </ol>
 *
 * <h2>Collaborators this member does not have</h2>
 * {@code COTRN02C} is outside the five-program family that includes the attention-key copybook - that
 * family is {@code COACTUPC}, {@code COACTVWC}, {@code COCRDLIC}, {@code COCRDSLC} and
 * {@code COCRDUPC} - and it declares no {@code EXEC CICS HANDLE ABEND}. There is therefore no abend
 * service and no key translator anywhere in this suite. Neither the account master nor the card master
 * is read either: the member names an account file at line 40 and never references it again, so those
 * repositories are not collaborators and are deliberately not mocked.
 *
 * <h2>Paragraph traceability</h2>
 * All 18 paragraphs are named in the test methods that exercise them, so the traceability matrix can be
 * filled from this class: {@code MAIN-PARA} 107, {@code PROCESS-ENTER-KEY} 164,
 * {@code VALIDATE-INPUT-KEY-FIELDS} 193, {@code VALIDATE-INPUT-DATA-FIELDS} 235,
 * {@code ADD-TRANSACTION} 442, {@code COPY-LAST-TRAN-DATA} 471, {@code RETURN-TO-PREV-SCREEN} 500,
 * {@code SEND-TRNADD-SCREEN} 516, {@code RECEIVE-TRNADD-SCREEN} 539, {@code POPULATE-HEADER-INFO} 552,
 * {@code READ-CXACAIX-FILE} 576, {@code READ-CCXREF-FILE} 609, {@code STARTBR-TRANSACT-FILE} 642,
 * {@code READPREV-TRANSACT-FILE} 673, {@code ENDBR-TRANSACT-FILE} 702,
 * {@code WRITE-TRANSACT-FILE} 711, {@code CLEAR-CURRENT-SCREEN} 754 and
 * {@code INITIALIZE-ALL-FIELDS} 762.
 *
 * <p>No monetary value anywhere in this class is held as a {@code double}, a {@code Double} or a
 * {@code float}: every amount is an exact decimal built from a string literal, and every amount
 * assertion is made on the exact decimal text and the declared scale.
 *
 * @since 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionAddService: COTRN02C, transaction CT02, the transaction-add screen")
final class TransactionAddServiceTest {

    // ==============================================================================================
    // Independent oracles: written out by hand, never produced by a production class
    // ==============================================================================================

    /**
     * The module's pinned instant. The same instant the container-backed support base freezes its clock
     * to, restated here rather than inherited because a unit test must not load a type whose
     * initialisation starts a database server.
     */
    private static final Instant PINNED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /**
     * The pinned instant carrying a <strong>non-zero sub-second component</strong>. This is the
     * anti-regression clock: a renderer that formats real microseconds produces
     * {@code .123456} here and passes only against a zero-nanosecond clock.
     */
    private static final Instant SUBSECOND_INSTANT = Instant.parse("2022-06-10T19:27:53.123456789Z");

    /**
     * The expected online 26-character timestamp, spelled out character for character: four year
     * digits, a hyphen, two month digits, a hyphen, two day digits, a <strong>space</strong> at
     * position 11, two hour digits, a colon, two minute digits, a colon, two second digits, a period at
     * position 20 and a six-zero fraction.
     */
    private static final String EXPECTED_ONLINE_TIMESTAMP = "2022-06-10 19:27:53.000000";

    private static final int TIMESTAMP_WIDTH = 26;

    /** Zero-based index of the online form's date/time separator, which is a space and not a hyphen. */
    private static final int DATE_TIME_SEPARATOR_INDEX = 10;

    private static final int FRACTION_SEPARATOR_INDEX = 19;

    private static final int FRACTION_INDEX = 20;

    /** Zero-based index of the colon between hour and minute, which the batch form spells as a dot. */
    private static final int HOUR_MINUTE_COLON_INDEX = 13;

    /** Zero-based index of the colon between minute and second, which the batch form spells as a dot. */
    private static final int MINUTE_SECOND_COLON_INDEX = 16;

    /** The six-zero fraction the legacy construction forces, whatever the clock carries. */
    private static final String ZERO_FRACTION = "000000";

    private static final String EXPECTED_HEADER_DATE = "06/10/22";

    private static final String EXPECTED_HEADER_TIME = "19:27:53";

    private static final int COMMON_MESSAGE_WIDTH = 50;

    /**
     * The unmapped-key message at its full contractual width: 40 visible characters plus exactly 10
     * trailing spaces. Written as a literal and a repeat count so the padding is visible in the source
     * rather than hidden inside a padded constant.
     */
    private static final String INVALID_KEY_MESSAGE =
            "Invalid key pressed. Please see below..." + " ".repeat(10);

    /** A screen title at the catalogue's 40-character width, standing in for the real catalogue value. */
    private static final String TITLE01 = "AWS Mainframe Modernization" + " ".repeat(13);

    private static final String TITLE02 = "CardDemo" + " ".repeat(32);

    /** The declared width of the outbound message field, into which the 80-character work field moves. */
    private static final int ERROR_MESSAGE_WIDTH = 78;

    private static final String RE_ARMED_TRANSACTION_ID = "CT02";

    private static final String PROGRAM_NAME = "COTRN02C";

    /** The dangling CICS program definition at {@code app/csd/CARDDEMO.CSD} line 211 with no member. */
    private static final String DANGLING_PROGRAM_NAME = "COCRDSEC";

    private static final String ACCOUNT_ID = "00000000011";

    private static final String CARD_NUMBER = "4111111111111111";

    private static final String CUSTOMER_ID = "000000011";

    private static final String AMOUNT = "-00000100.00";

    private static final String ORIG_DATE = "2022-07-19";

    private static final String PROC_DATE = "2022-07-20";

    /** The transaction source as keyed: eight characters into a ten-character field. */
    private static final String SOURCE = "POS TERM";

    private static final String STORED_SOURCE = SOURCE + " ".repeat(2);

    private static final int SOURCE_WIDTH = 10;

    /** The description as keyed, carrying embedded spaces that must survive untouched. */
    private static final String DESCRIPTION = "PURCHASE AT MERCHANT";

    /** The merchant identifier as keyed: nine digits, because the field is tested for all digits. */
    private static final String MERCHANT_ID = "000000001";

    private static final String MERCHANT_NAME = "MERCHANT NAME";

    private static final String MERCHANT_CITY = "MERCHANT CITY";

    /** A merchant postal code, five characters into a ten-character field. */
    private static final String MERCHANT_ZIP = "10001";

    private static final String FIRST_IDENTIFIER = "0000000000000001";

    private static final int TRAN_ID_WIDTH = 16;

    /** Declared width of {@code ACTIDINI PIC X(11)} in app/cpy-bms/COTRN02.CPY. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** The account key field as the screen carries it when nothing was transmitted: blank-filled. */
    private static final String BLANK_ACCOUNT_ID_FIELD = " ".repeat(ACCOUNT_ID_WIDTH);

    private static final String SUCCESS_MESSAGE = "Transaction added successfully. "
            + " Your Tran ID is " + FIRST_IDENTIFIER + ".";

    // ==============================================================================================
    // The message literals, in source order. Casing, spacing and dot counts are contractual.
    // ==============================================================================================

    private static final String MSG_CONFIRM_TO_ADD = "Confirm to add this transaction...";

    private static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";

    private static final String MSG_ACCOUNT_ID_NOT_NUMERIC = "Account ID must be Numeric...";

    private static final String MSG_CARD_NUMBER_NOT_NUMERIC = "Card Number must be Numeric...";

    private static final String MSG_KEY_FIELD_REQUIRED = "Account or Card Number must be entered...";

    private static final String MSG_TYPE_CD_EMPTY = "Type CD can NOT be empty...";

    private static final String MSG_DESCRIPTION_EMPTY = "Description can NOT be empty...";

    private static final String MSG_AMOUNT_EMPTY = "Amount can NOT be empty...";

    private static final String MSG_ORIG_DATE_EMPTY = "Orig Date can NOT be empty...";

    private static final String MSG_PROC_DATE_EMPTY = "Proc Date can NOT be empty...";

    private static final String MSG_TYPE_CD_NOT_NUMERIC = "Type CD must be Numeric...";

    /** Line 345. No trailing dots, exactly as the source writes it. */
    private static final String MSG_AMOUNT_FORMAT = "Amount should be in format -99999999.99";

    /** Line 360. No trailing dots. */
    private static final String MSG_ORIG_DATE_FORMAT = "Orig Date should be in format YYYY-MM-DD";

    /** Line 375. No trailing dots. */
    private static final String MSG_PROC_DATE_FORMAT = "Proc Date should be in format YYYY-MM-DD";

    /** Line 401. The lower-case {@code date} is deliberate and differs from the format text. */
    private static final String MSG_ORIG_DATE_INVALID = "Orig Date - Not a valid date...";

    /** Line 421. The lower-case {@code date} is deliberate. */
    private static final String MSG_PROC_DATE_INVALID = "Proc Date - Not a valid date...";

    private static final String MSG_MERCHANT_ID_NOT_NUMERIC = "Merchant ID must be Numeric...";

    private static final String MSG_ACCOUNT_ID_NOT_FOUND = "Account ID NOT found...";

    private static final String MSG_CARD_NUMBER_NOT_FOUND = "Card Number NOT found...";

    /** Line 633. The hash and the space before it are part of the literal. */
    private static final String MSG_XREF_LOOKUP_FAILED = "Unable to lookup Card # in XREF file...";

    /** Lines 664 and 693: the same text is written by two paragraphs. */
    private static final String MSG_TRANSACTION_LOOKUP_FAILED = "Unable to lookup Transaction...";

    /** Line 738. The legacy spelling {@code exist} is contractual and is not corrected. */
    private static final String MSG_TRAN_ID_ALREADY_EXISTS = "Tran ID already exist...";

    private static final String MSG_UNABLE_TO_ADD = "Unable to Add Transaction...";

    // ==============================================================================================
    // The two four-character acceptance-test codes, compared as text and never parsed
    // ==============================================================================================

    private static final String ACCEPTED_SEVERITY = "0000";

    /** A non-zero severity, which alone decides nothing. */
    private static final String ERROR_SEVERITY = "0003";

    /** The message number that accepts <em>despite</em> a non-zero severity, lines 400 and 420. */
    private static final String TOLERATED_MESSAGE_NUMBER = "2513";

    /** Any other message number, which with a non-zero severity is an error. */
    private static final String REJECTED_MESSAGE_NUMBER = "2508";

    private static final String SUCCESS_MESSAGE_NUMBER = "0000";

    // ==============================================================================================
    // Screen field identifiers, the cursor targets of the corresponding MOVE -1
    // ==============================================================================================

    private static final String FIELD_ACCOUNT_ID = "ACTIDIN";

    private static final String FIELD_CARD_NUMBER = "CARDNIN";

    private static final String FIELD_TYPE_CD = "TTYPCD";

    private static final String FIELD_DESCRIPTION = "TDESC";

    private static final String FIELD_AMOUNT = "TRNAMT";

    private static final String FIELD_ORIG_DATE = "TORIGDT";

    private static final String FIELD_PROC_DATE = "TPROCDT";

    private static final String FIELD_MERCHANT_ID = "MID";

    private static final String FIELD_CONFIRM = "CONFIRM";

    // ==============================================================================================
    // Collaborators: every one a mock, so nothing here reaches a database, a queue or the network
    // ==============================================================================================

    @Mock
    private DateValidationService dateValidationService;

    @Mock
    private MessageCatalogService messageCatalogService;

    @Mock
    private NavigationService navigationService;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private OnlineTransactionBoundary transactionBoundary;

    private TransactionAddService service;

    @BeforeEach
    void buildServiceOnThePinnedClock() {
        this.service = serviceOn(Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC));
    }

    /**
     * Builds the service on a caller-supplied clock, for the tests that need a second reading.
     *
     * @param  clock the clock the header paragraph reads
     * @return a service wired to the same six mocks and that clock
     */
    private TransactionAddService serviceOn(final Clock clock) {
        return new TransactionAddService(dateValidationService, messageCatalogService,
                navigationService, transactionRepository, cardCrossReferenceRepository,
                transactionBoundary, clock);
    }

    // ==============================================================================================
    // Fixtures and opt-in stubs. Each stub is set up only by the tests that consume it, because the
    // strict-stubs policy makes an unconsumed stub a failure rather than a warning.
    // ==============================================================================================

    /**
     * Builds a result block whose five text components are at the widths the record's own compact
     * constructor enforces: four, four, fifteen, ten and ten encoded bytes.
     *
     * <p>The feedback constant is chosen to agree with the two codes rather than being arbitrary, so a
     * reader cannot mistake a crafted block for an inconsistent one. Only the two <em>codes</em> are
     * what the service reads.
     *
     * @param  feedback       the outcome the block reports
     * @param  severityCode   the four-character severity view
     * @param  messageNumber  the four-character message-number view
     * @param  resultText     the fifteen-character outcome text
     * @return the result block
     */
    private static DateValidationService.SubprogramResult resultBlock(
            final DateValidationService.DateFeedback feedback, final String severityCode,
            final String messageNumber, final String resultText) {
        return new DateValidationService.SubprogramResult(feedback, severityCode, messageNumber,
                resultText, ORIG_DATE, DateFormat.YYYY_MM_DD.getValue());
    }

    private static DateValidationService.SubprogramResult acceptedBlock() {
        return resultBlock(DateValidationService.DateFeedback.DATE_IS_VALID, ACCEPTED_SEVERITY,
                SUCCESS_MESSAGE_NUMBER, "Date is valid  ");
    }

    private static DateValidationService.SubprogramResult toleratedBlock() {
        return resultBlock(DateValidationService.DateFeedback.UNSUPPORTED_RANGE, ERROR_SEVERITY,
                TOLERATED_MESSAGE_NUMBER, "Unsupp. Range  ");
    }

    private static DateValidationService.SubprogramResult rejectedBlock() {
        return resultBlock(DateValidationService.DateFeedback.BAD_DATE_VALUE, ERROR_SEVERITY,
                REJECTED_MESSAGE_NUMBER, "Datevalue error");
    }

    private void datesAnswer(final DateValidationService.SubprogramResult block) {
        when(dateValidationService.validateDate(anyString(), any(DateFormat.class)))
                .thenReturn(block);
    }

    private void datesAnswer(final DateValidationService.SubprogramResult origination,
            final DateValidationService.SubprogramResult processing) {
        when(dateValidationService.validateDate(anyString(), any(DateFormat.class)))
                .thenReturn(origination, processing);
    }

    private static CardCrossReference crossReferenceRow() {
        return new CardCrossReference(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID);
    }

    private void accountResolves() {
        when(cardCrossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(crossReferenceRow()));
    }

    private void cardResolves() {
        when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                .thenReturn(Optional.of(crossReferenceRow()));
    }

    /**
     * Runs the independent lock-read-insert unit inline, so the ordering inside it is observable while
     * the unit itself is still proven to be entered exactly once.
     *
     * <p>The type witness is explicit because the boundary's operation is generic and the unit it runs
     * here yields a transaction.
     */
    private void boundaryRunsInline() {
        when(transactionBoundary.<Transaction>execute(any())).thenAnswer(invocation -> {
            final Supplier<Transaction> unit = invocation.getArgument(0);
            return unit.get();
        });
    }

    private void boundaryFailsAfterRunning(final RuntimeException failure) {
        when(transactionBoundary.<Transaction>execute(any())).thenAnswer(invocation -> {
            final Supplier<Transaction> unit = invocation.getArgument(0);
            unit.get();
            throw failure;
        });
    }

    /**
     * Stubs the descending single-row browse of the transaction master.
     *
     * @param highestKey the identifier the highest existing row carries, or {@code null} for an empty
     *                   master, which is the end-of-file arm at line 689
     */
    private void highestTransactionIs(final String highestKey) {
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(pageOf(highestKey));
    }

    private void insertSucceeds() {
        when(transactionRepository.insertAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Builds the single-row page the descending browse returns.
     *
     * @param  highestKey the identifier of the highest existing row, or {@code null} for an empty master
     * @return a page holding that one row, or an empty page
     */
    private static Page<Transaction> pageOf(final String highestKey) {
        return highestKey == null
                ? new PageImpl<>(List.of(), PageRequest.of(0, 1), 0)
                : pageOfRecord(TestDataFactory.transaction().id(highestKey).build());
    }

    /**
     * Builds the single-row page the descending browse returns, from a fully populated record.
     *
     * @param  row the highest-keyed record
     * @return a page holding exactly that row
     */
    private static Page<Transaction> pageOfRecord(final Transaction row) {
        final List<Transaction> rows = List.of(row);
        return new PageImpl<>(rows, PageRequest.of(0, 1), rows.size());
    }

    private void catalogueTitlesAvailable() {
        when(messageCatalogService.screenTitle01()).thenReturn(TITLE01);
        when(messageCatalogService.screenTitle02()).thenReturn(TITLE02);
    }

    private static ScreenNavigationState reSubmission() {
        return new ScreenNavigationState(RE_ARMED_TRANSACTION_ID, PROGRAM_NAME,
                RE_ARMED_TRANSACTION_ID, PROGRAM_NAME, "USER0001", "U",
                ScreenNavigationState.ProgramContext.REENTER, null, null, null, null, null, null,
                null, "COTRN2A", "COTRN02");
    }

    private static ScreenNavigationState firstEntry() {
        return reSubmission().withFirstEntry();
    }

    /**
     * Builds a fully populated, valid turn keyed on the account identifier.
     *
     * @param  confirm the confirmation field as transmitted
     * @return the turn
     */
    private static TransactionAddService.TransactionAddScreenInput validTurn(final String confirm) {
        return new TransactionAddService.TransactionAddScreenInput(ACCOUNT_ID, null, "01", "0001",
                SOURCE, DESCRIPTION, AMOUNT, ORIG_DATE, PROC_DATE, MERCHANT_ID, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP, confirm, null, KeyAction.ENTER, reSubmission());
    }

    private static TransactionAddService.TransactionAddScreenInput confirmedTurn() {
        return validTurn("Y");
    }

    /**
     * Copies a turn, replacing the two key fields.
     *
     * @param  base       the turn to copy
     * @param  accountId  the account key to substitute, possibly {@code null}
     * @param  cardNumber the card key to substitute, possibly {@code null}
     * @return the copy
     */
    private static TransactionAddService.TransactionAddScreenInput withKeys(
            final TransactionAddService.TransactionAddScreenInput base, final String accountId,
            final String cardNumber) {
        return new TransactionAddService.TransactionAddScreenInput(accountId, cardNumber,
                base.typeCd(), base.categoryCd(), base.source(), base.description(), base.amount(),
                base.origDate(), base.procDate(), base.merchantId(), base.merchantName(),
                base.merchantCity(), base.merchantZip(), base.confirm(), base.selectedTransaction(),
                base.keyAction(), base.navigationContext());
    }

    /**
     * Copies a turn, replacing the amount and the two dates. A {@code null} argument keeps the base's
     * own value, so each edit test names only the field it is about.
     *
     * @param  base     the turn to copy
     * @param  amount   the amount lexeme to substitute, or {@code null}
     * @param  origDate the origination date to substitute, or {@code null}
     * @param  procDate the processing date to substitute, or {@code null}
     * @return the copy
     */
    private static TransactionAddService.TransactionAddScreenInput withDataFields(
            final TransactionAddService.TransactionAddScreenInput base, final String amount,
            final String origDate, final String procDate) {
        return new TransactionAddService.TransactionAddScreenInput(base.accountId(),
                base.cardNumber(), base.typeCd(), base.categoryCd(), base.source(),
                base.description(), amount == null ? base.amount() : amount,
                origDate == null ? base.origDate() : origDate,
                procDate == null ? base.procDate() : procDate, base.merchantId(),
                base.merchantName(), base.merchantCity(), base.merchantZip(), base.confirm(),
                base.selectedTransaction(), base.keyAction(), base.navigationContext());
    }

    /**
     * Copies a turn, replacing the transaction type and category codes.
     *
     * @param  base       the turn to copy
     * @param  typeCd     the type code to substitute, possibly {@code null}
     * @param  categoryCd the category code to substitute, possibly {@code null}
     * @return the copy
     */
    private static TransactionAddService.TransactionAddScreenInput withCodes(
            final TransactionAddService.TransactionAddScreenInput base, final String typeCd,
            final String categoryCd) {
        return new TransactionAddService.TransactionAddScreenInput(base.accountId(),
                base.cardNumber(), typeCd, categoryCd, base.source(), base.description(),
                base.amount(), base.origDate(), base.procDate(), base.merchantId(),
                base.merchantName(), base.merchantCity(), base.merchantZip(), base.confirm(),
                base.selectedTransaction(), base.keyAction(), base.navigationContext());
    }

    /**
     * Copies a turn, replacing the merchant identifier and the merchant name.
     *
     * @param  base       the turn to copy
     * @param  merchantId the merchant identifier to substitute, possibly {@code null}
     * @param  name       the merchant name to substitute, possibly {@code null}
     * @return the copy
     */
    private static TransactionAddService.TransactionAddScreenInput withMerchant(
            final TransactionAddService.TransactionAddScreenInput base, final String merchantId,
            final String name) {
        return new TransactionAddService.TransactionAddScreenInput(base.accountId(),
                base.cardNumber(), base.typeCd(), base.categoryCd(), base.source(),
                base.description(), base.amount(), base.origDate(), base.procDate(), merchantId,
                name, base.merchantCity(), base.merchantZip(), base.confirm(),
                base.selectedTransaction(), base.keyAction(), base.navigationContext());
    }

    /**
     * Copies a turn, replacing the source and the description.
     *
     * @param  base        the turn to copy
     * @param  source      the source code to substitute, possibly {@code null}
     * @param  description the description to substitute, possibly {@code null}
     * @return the copy
     */
    private static TransactionAddService.TransactionAddScreenInput withText(
            final TransactionAddService.TransactionAddScreenInput base, final String source,
            final String description) {
        return new TransactionAddService.TransactionAddScreenInput(base.accountId(),
                base.cardNumber(), base.typeCd(), base.categoryCd(), source, description,
                base.amount(), base.origDate(), base.procDate(), base.merchantId(),
                base.merchantName(), base.merchantCity(), base.merchantZip(), base.confirm(),
                base.selectedTransaction(), base.keyAction(), base.navigationContext());
    }

    /**
     * Copies a turn, replacing the pre-selected transaction the calling screen handed over.
     *
     * @param  base      the turn to copy
     * @param  selected  the pre-selected transaction, possibly {@code null}
     * @param  keyAction the decoded attention key, possibly {@code null}
     * @param  context   the echoed navigation state, possibly {@code null}
     * @return the copy
     */
    private static TransactionAddService.TransactionAddScreenInput withSelection(
            final TransactionAddService.TransactionAddScreenInput base, final String selected,
            final KeyAction keyAction, final ScreenNavigationState context) {
        return new TransactionAddService.TransactionAddScreenInput(base.accountId(),
                base.cardNumber(), base.typeCd(), base.categoryCd(), base.source(),
                base.description(), base.amount(), base.origDate(), base.procDate(),
                base.merchantId(), base.merchantName(), base.merchantCity(), base.merchantZip(),
                base.confirm(), selected, keyAction, context);
    }

    /**
     * Copies a turn, replacing the attention key and the navigation state.
     *
     * @param  base      the turn to copy
     * @param  keyAction the decoded attention key, possibly {@code null}
     * @param  context   the echoed navigation state, possibly {@code null}
     * @return the copy
     */
    private static TransactionAddService.TransactionAddScreenInput withTurnState(
            final TransactionAddService.TransactionAddScreenInput base, final KeyAction keyAction,
            final ScreenNavigationState context) {
        return new TransactionAddService.TransactionAddScreenInput(base.accountId(),
                base.cardNumber(), base.typeCd(), base.categoryCd(), base.source(),
                base.description(), base.amount(), base.origDate(), base.procDate(),
                base.merchantId(), base.merchantName(), base.merchantCity(), base.merchantZip(),
                base.confirm(), base.selectedTransaction(), keyAction, context);
    }

    /**
     * Runs the canonical successful insert and returns the record the service handed to the store.
     *
     * @param  turn the turn to run
     * @return the entity captured at the insert
     */
    private Transaction insertedBy(final TransactionAddService.TransactionAddScreenInput turn) {
        accountResolves();
        datesAnswer(acceptedBlock());
        boundaryRunsInline();
        highestTransactionIs(null);
        insertSucceeds();
        return insertedTransaction(turn);
    }

    /**
     * Runs a turn whose stubs the caller has already established and captures the record the service
     * handed to the store.
     *
     * @param  turn the turn to run
     * @return the entity captured at the insert
     */
    private Transaction insertedTransaction(
            final TransactionAddService.TransactionAddScreenInput turn) {
        service.processTransactionAdd(turn);

        final ArgumentCaptor<Transaction> written = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).insertAndFlush(written.capture());
        return written.getValue();
    }

    /**
     * Measures a value's encoded byte width, which is the only width a fixed-width field declares.
     *
     * @param  value the value to measure
     * @return the number of bytes the value occupies
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Looks a field up in the layout catalogue derived from {@code app/cpy/CVTRA05Y.cpy}, so record
     * offsets are asserted against a measured catalogue rather than against a restated number.
     *
     * @param  cobolName the legacy field name
     * @return the field's declared offset and width
     */
    private static TestDataFactory.FieldSpec transactionLayoutField(final String cobolName) {
        return TestDataFactory.TRANSACTION.fields().stream()
                .filter(field -> cobolName.equals(field.cobolName()))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    @DisplayName("construction: all seven collaborators are mandatory")
    final class ConstructionContract {

        @Test
        @DisplayName("every one of the seven constructor arguments is rejected when absent")
        void everyCollaboratorIsMandatory() {
            final Clock clock = Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC);

            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new TransactionAddService(null, messageCatalogService,
                                    navigationService, transactionRepository,
                                    cardCrossReferenceRepository, transactionBoundary, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new TransactionAddService(dateValidationService, null,
                                    navigationService, transactionRepository,
                                    cardCrossReferenceRepository, transactionBoundary, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new TransactionAddService(dateValidationService,
                                    messageCatalogService, null, transactionRepository,
                                    cardCrossReferenceRepository, transactionBoundary, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new TransactionAddService(dateValidationService,
                                    messageCatalogService, navigationService, null,
                                    cardCrossReferenceRepository, transactionBoundary, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new TransactionAddService(dateValidationService,
                                    messageCatalogService, navigationService, transactionRepository,
                                    null, transactionBoundary, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new TransactionAddService(dateValidationService,
                                    messageCatalogService, navigationService, transactionRepository,
                                    cardCrossReferenceRepository, null, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new TransactionAddService(dateValidationService,
                                    messageCatalogService, navigationService, transactionRepository,
                                    cardCrossReferenceRepository, transactionBoundary, null)));
        }

        @Test
        @DisplayName("the entry point refuses an absent turn rather than inventing a blank screen")
        void theEntryPointRefusesAnAbsentTurn() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.processTransactionAdd(null));

            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary, dateValidationService, navigationService);
        }
    }

    @Nested
    @DisplayName("POPULATE-HEADER-INFO line 552: the online 26-character timestamp")
    final class OnlineTimestampContract {

        @Test
        @DisplayName("populateHeaderInfo forces an all-zero fraction even when the clock carries "
                + "sub-second precision, and keeps the space at position eleven")
        void populateHeaderInfoForcesAnAllZeroFractionOnASubSecondClock() {
            final TransactionAddService subSecondService =
                    serviceOn(Clock.fixed(SUBSECOND_INSTANT, ZoneOffset.UTC));

            final TransactionAddService.TransactionAddResult result = subSecondService
                    .processTransactionAdd(withKeys(confirmedTurn(), null, null));

            final String stamped = result.header().screenTimestamp();
            assertAll(
                    // The decisive assertion: a renderer that formatted the reading's own nanoseconds
                    // would emit .123456 here and would pass only against a zero-nanosecond clock.
                    () -> assertThat(stamped.substring(FRACTION_INDEX))
                            .as("the fraction is invariably six zeros, whatever the clock carries")
                            .isEqualTo(ZERO_FRACTION),
                    () -> assertThat(encodedWidth(stamped))
                            .as("the group occupies exactly 26 byte positions")
                            .isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(stamped.charAt(DATE_TIME_SEPARATOR_INDEX))
                            .as("position eleven is the literal space filler of the online form")
                            .isEqualTo(' '),
                    () -> assertThat(stamped)
                            .as("the whole rendered value is the same as on a zero-nanosecond clock")
                            .isEqualTo(EXPECTED_ONLINE_TIMESTAMP));
        }

        @Test
        @DisplayName("populateHeaderInfo renders the pinned instant as the declared literal, with the "
                + "header date and time taken from the same single reading")
        void populateHeaderInfoRendersThePinnedInstantAsTheDeclaredLiteral() {
            catalogueTitlesAvailable();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(withKeys(confirmedTurn(), null, null));

            final TransactionAddService.ScreenHeader header = result.header();
            assertAll(
                    () -> assertThat(header.screenTimestamp()).isEqualTo(EXPECTED_ONLINE_TIMESTAMP),
                    () -> assertThat(header.currentDate()).isEqualTo(EXPECTED_HEADER_DATE),
                    () -> assertThat(header.currentTime()).isEqualTo(EXPECTED_HEADER_TIME),
                    () -> assertThat(header.transactionName()).isEqualTo(RE_ARMED_TRANSACTION_ID),
                    () -> assertThat(header.programName()).isEqualTo(PROGRAM_NAME),
                    () -> assertThat(header.title01()).isEqualTo(TITLE01),
                    () -> assertThat(header.title02()).isEqualTo(TITLE02),
                    () -> assertThat(header.messageHighlighted())
                            .as("only the successful-insert path recolours the message field")
                            .isFalse());
        }

        @Test
        @DisplayName("the emitted value is the online form and never the batch form: no hyphen before "
                + "the hour and no dots between the time parts")
        void theEmittedValueIsNeverTheBatchForm() {
            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(withKeys(confirmedTurn(), null, null));

            final String stamped = result.header().screenTimestamp();
            assertAll(
                    () -> assertThat(stamped.charAt(DATE_TIME_SEPARATOR_INDEX))
                            .as("the batch form carries a hyphen here; the online form a space")
                            .isNotEqualTo('-'),
                    () -> assertThat(stamped.indexOf('.'))
                            .as("the only period is the fraction separator at position twenty")
                            .isEqualTo(FRACTION_SEPARATOR_INDEX),
                    () -> assertThat(stamped.lastIndexOf('.'))
                            .as("the batch form carries dots between the time parts; this carries none")
                            .isEqualTo(FRACTION_SEPARATOR_INDEX),
                    () -> assertThat(stamped.charAt(HOUR_MINUTE_COLON_INDEX))
                            .as("the online form separates hour from minute with a colon")
                            .isEqualTo(':'),
                    () -> assertThat(stamped.charAt(MINUTE_SECOND_COLON_INDEX))
                            .as("and minute from second with a colon")
                            .isEqualTo(':'));
        }

        @Test
        @DisplayName("the hand-written timestamp literal agrees with the value measured from the "
                + "delivered daily-transaction fixture")
        void theHandWrittenLiteralAgreesWithTheMeasuredFixtureValue() {
            assertAll(
                    () -> assertThat(EXPECTED_ONLINE_TIMESTAMP)
                            .as("two independent oracles for the same 26-character form")
                            .isEqualTo(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP),
                    () -> assertThat(TIMESTAMP_WIDTH)
                            .isEqualTo(TestDataFactory.TIMESTAMP_TEXT_WIDTH),
                    () -> assertThat(encodedWidth(EXPECTED_ONLINE_TIMESTAMP))
                            .isEqualTo(TIMESTAMP_WIDTH));
        }

        @Test
        @DisplayName("ADD-TRANSACTION lines 464 and 465 store the two independent screen date fields "
                + "and never a clock reading, each space filled to twenty-six bytes")
        void addTransactionStoresTheTwoScreenDateFieldsRatherThanAClockReading() {
            final Transaction stored = insertedBy(confirmedTurn());

            assertAll(
                    () -> assertThat(stored.getTranOrigTs())
                            .isEqualTo(ORIG_DATE + " ".repeat(TIMESTAMP_WIDTH - ORIG_DATE.length())),
                    () -> assertThat(stored.getTranProcTs())
                            .isEqualTo(PROC_DATE + " ".repeat(TIMESTAMP_WIDTH - PROC_DATE.length())),
                    () -> assertThat(encodedWidth(stored.getTranOrigTs())).isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(encodedWidth(stored.getTranProcTs())).isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(stored.getTranOrigTs())
                            .as("the two timestamps are separate operator input, not one value")
                            .isNotEqualTo(stored.getTranProcTs()),
                    () -> assertThat(stored.getTranOrigTs())
                            .as("no clock value is substituted over operator input")
                            .isNotEqualTo(EXPECTED_ONLINE_TIMESTAMP));
        }

        @Test
        @DisplayName("the record timestamps sit at the offsets the external sort specifications address")
        void theRecordTimestampOffsetsAreTheOnesTheExternalSortAddresses() {
            final TestDataFactory.FieldSpec origination = transactionLayoutField("TRAN-ORIG-TS");
            final TestDataFactory.FieldSpec processing = transactionLayoutField("TRAN-PROC-TS");

            assertAll(
                    () -> assertThat(origination.offset()).isEqualTo(278),
                    () -> assertThat(processing.offset()).isEqualTo(304),
                    () -> assertThat(origination.width()).isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(processing.width()).isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(TestDataFactory.TRANSACTION.recordLength()).isEqualTo(350));
        }
    }

    @Nested
    @DisplayName("VALIDATE-INPUT-DATA-FIELDS lines 389 to 427: the severity and message-number pair")
    final class DateAcceptanceContract {

        @Test
        @DisplayName("a non-zero severity carrying message number 2513 is accepted silently, so the "
                + "insert proceeds with no field error at all")
        void aNonZeroSeverityCarryingTheToleratedMessageNumberIsAcceptedSilently() {
            accountResolves();
            datesAnswer(toleratedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            assertAll(
                    () -> assertThat(result.transactionAdded()).isTrue(),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.message()).isEqualTo(SUCCESS_MESSAGE),
                    () -> assertThat(result.transaction()).isNotNull());
        }

        @Test
        @DisplayName("the same non-zero severity carrying any other message number is an error, with "
                + "the origination date's own text and cursor position")
        void theSameSeverityWithAnotherMessageNumberIsAnError() {
            accountResolves();
            datesAnswer(rejectedBlock());

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_ORIG_DATE_INVALID),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ORIG_DATE),
                    () -> assertThat(result.transactionAdded()).isFalse(),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.fieldErrors()).singleElement()
                            .extracting(ValidationException.FieldError::state)
                            .isEqualTo(ValidationException.FieldState.INVALID));
            verifyNoInteractions(transactionBoundary);
        }

        @Test
        @DisplayName("the accepted severity 0000 passes outright, without the message number being "
                + "consulted at all")
        void theAcceptedSeverityPassesOutright() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            assertThat(result.transactionAdded()).isTrue();
        }

        @Test
        @DisplayName("the two non-zero-severity outcomes differ from one another, which is what a "
                + "collapsed boolean could not produce")
        void theTwoNonZeroOutcomesDifferFromOneAnother() {
            accountResolves();
            // Three answers: the tolerated turn consumes two, and the rejected turn consumes one
            // because its first failure ends the task before the processing date is offered.
            when(dateValidationService.validateDate(anyString(), any(DateFormat.class)))
                    .thenReturn(toleratedBlock(), toleratedBlock(), rejectedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final TransactionAddService.TransactionAddResult tolerated =
                    service.processTransactionAdd(confirmedTurn());
            final TransactionAddService.TransactionAddResult rejected =
                    service.processTransactionAdd(confirmedTurn());

            assertAll(
                    () -> assertThat(tolerated.errorFlag())
                            .as("both blocks carry the same non-zero severity, so only the message "
                                    + "number can separate them")
                            .isNotEqualTo(rejected.errorFlag()),
                    () -> assertThat(tolerated.transactionAdded()).isTrue(),
                    () -> assertThat(rejected.transactionAdded()).isFalse(),
                    () -> assertThat(tolerated.message()).isNotEqualTo(rejected.message()));
        }

        @Test
        @DisplayName("both call sites are handed the hyphenated ten-character mask, and neither the "
                + "raw-mask overload nor the shared acceptance helper is used")
        void bothCallSitesAreHandedTheHyphenatedMask() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            service.processTransactionAdd(confirmedTurn());

            final ArgumentCaptor<DateFormat> masks = ArgumentCaptor.forClass(DateFormat.class);
            verify(dateValidationService, times(2)).validateDate(anyString(), masks.capture());
            assertAll(
                    () -> assertThat(masks.getAllValues())
                            .containsExactly(DateFormat.YYYY_MM_DD, DateFormat.YYYY_MM_DD),
                    () -> assertThat(DateFormat.YYYY_MM_DD.getValue()).isEqualTo("YYYY-MM-DD"),
                    () -> assertThat(encodedWidth(DateFormat.YYYY_MM_DD.getValue())).isEqualTo(10));
            verify(dateValidationService, never()).validateDate(anyString(), anyString());
            verify(dateValidationService, never()).isDateAcceptable(any());
        }

        @Test
        @DisplayName("a rejected origination date is never followed by a processing-date call, so the "
                + "number of invocations matches the legacy's")
        void aRejectedOriginationDateIsNeverFollowedByAProcessingCall() {
            accountResolves();
            datesAnswer(rejectedBlock());

            service.processTransactionAdd(confirmedTurn());

            verify(dateValidationService, times(1)).validateDate(anyString(), any(DateFormat.class));
        }

        @Test
        @DisplayName("a rejected processing date reports with its own text and its own cursor position")
        void aRejectedProcessingDateReportsWithItsOwnTextAndCursor() {
            accountResolves();
            datesAnswer(acceptedBlock(), rejectedBlock());

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_PROC_DATE_INVALID),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_PROC_DATE),
                    () -> assertThat(result.errorFlag()).isTrue());
            verifyNoInteractions(transactionBoundary);
        }

        @Test
        @DisplayName("a validator that answers with no result block at all lets no date through and "
                + "writes nothing, so a broken collaborator can never be read as acceptance")
        void aValidatorAnsweringWithNoResultBlockWritesNothing() {
            accountResolves();
            when(dateValidationService.validateDate(anyString(), any(DateFormat.class)))
                    .thenReturn(null);

            // The two-level test reads two fields of the block, so an absent block is a programming
            // fault in the collaborator and surfaces as one. What matters contractually is the
            // consequence asserted below: the date is never accepted by default and nothing is written.
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.processTransactionAdd(confirmedTurn()));

            verifyNoInteractions(transactionBoundary);
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        }
    }

    /**
     * The two cross-reference reads, and the relationship resolution they stand for.
     *
     * <p>The alternate key is non-unique, so more than one row can carry one account identifier and the
     * access path is defined to yield the first of them in base-key order. The service expresses that
     * bounded, ordered read as the repository's ordered-first finder, which answers with at most one
     * row: an <strong>absent row is the not-found arm</strong> and cannot be an index error, because the
     * service never fetches the candidate list and never indexes into it. The list-returning finder the
     * repository also declares belongs to other callers and is proven unused here.
     */
    @Nested
    @DisplayName("READ-CXACAIX-FILE line 576 and READ-CCXREF-FILE line 609: relationship resolution")
    final class CrossReferenceResolutionContract {

        @Test
        @DisplayName("an absent cross-reference row is the not-found arm, with no index error and no "
                + "exception of any kind escaping the turn")
        void anAbsentCrossReferenceRowIsTheNotFoundArm() {
            when(cardCrossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThatNoException()
                    .isThrownBy(() -> service.processTransactionAdd(confirmedTurn()));

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());
            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_ACCOUNT_ID_NOT_FOUND),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.fieldErrors()).singleElement()
                            .extracting(ValidationException.FieldError::field)
                            .isEqualTo("accountId"));
            verifyNoInteractions(transactionBoundary, dateValidationService);
        }

        @Test
        @DisplayName("the candidate rows are never fetched as a list and never re-sorted: the first row "
                + "in base-key order as the access path supplies it is the one used")
        void theFirstCandidateAsSuppliedIsTheOneUsed() {
            // Three rows on one account, deliberately not in ascending card-number order. The access
            // path yields the first of them; nothing in the service may reorder or re-pick.
            final List<CardCrossReference> candidates = List.of(
                    new CardCrossReference("4111111111111113", CUSTOMER_ID, ACCOUNT_ID),
                    new CardCrossReference("4111111111111112", CUSTOMER_ID, ACCOUNT_ID),
                    new CardCrossReference("4111111111111111", CUSTOMER_ID, ACCOUNT_ID));
            when(cardCrossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(candidates.get(0)));
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final Transaction stored = insertedTransaction(confirmedTurn());

            assertAll(
                    () -> assertThat(SensitiveValues.fingerprint(stored.getTranCardNum()))
                            .isEqualTo(SensitiveValues.fingerprint(candidates.get(0).getXrefCardNum())),
                    () -> assertThat(SensitiveValues.fingerprint(stored.getTranCardNum()))
                            .isNotEqualTo(SensitiveValues.fingerprint(candidates.get(1).getXrefCardNum())),
                    () -> assertThat(SensitiveValues.fingerprint(stored.getTranCardNum()))
                            .isNotEqualTo(SensitiveValues.fingerprint(candidates.get(2).getXrefCardNum())));
            verify(cardCrossReferenceRepository, never()).findByXrefAcctId(anyString());
        }

        @Test
        @DisplayName("READ-CCXREF-FILE reports an absent row at the card field with its own text")
        void anAbsentCardRowReportsAtTheCardField() {
            when(cardCrossReferenceRepository.findById(CARD_NUMBER)).thenReturn(Optional.empty());

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withKeys(confirmedTurn(), null, CARD_NUMBER));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_CARD_NUMBER_NOT_FOUND),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_CARD_NUMBER),
                    () -> assertThat(result.fieldErrors()).singleElement()
                            .extracting(ValidationException.FieldError::bmsFieldId)
                            .isEqualTo(FIELD_CARD_NUMBER));
            verifyNoInteractions(transactionBoundary);
        }

        @Test
        @DisplayName("a failed lookup becomes the third response arm's own text rather than a "
                + "propagating persistence failure")
        void aFailedLookupBecomesTheThirdResponseArm() {
            when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));

            final TransactionAddService.TransactionAddResult result;
            result = service.processTransactionAdd(withKeys(confirmedTurn(), null, CARD_NUMBER));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_XREF_LOOKUP_FAILED),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_CARD_NUMBER),
                    () -> assertThat(result.errorFlag()).isTrue());
        }

        @Test
        @DisplayName("whichever key is supplied resolves the other, and a supplied account wins so the "
                + "card field is overwritten with what the cross reference holds")
        void aSuppliedAccountWinsAndOverwritesTheCardField() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withKeys(confirmedTurn(), ACCOUNT_ID, "9999999999999999"));

            final ArgumentCaptor<Transaction> written = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).insertAndFlush(written.capture());
            assertAll(
                    () -> assertThat(SensitiveValues.fingerprint(written.getValue().getTranCardNum()))
                            .isEqualTo(SensitiveValues.fingerprint(CARD_NUMBER)),
                    () -> assertThat(result.transactionAdded()).isTrue());
            verify(cardCrossReferenceRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("a supplied card resolves the account identifier through the base cluster and the "
                + "resolved value is written back over the screen field")
        void aSuppliedCardResolvesTheAccountIdentifier() {
            cardResolves();
            datesAnswer(acceptedBlock());

            // A turn held at the confirmation gate, because the successful-insert path resets all
            // fourteen screen fields and would blank the very value under test.
            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withKeys(validTurn(null), null, CARD_NUMBER));

            assertAll(
                    () -> assertThat(result.screen().accountId()).isEqualTo(ACCOUNT_ID),
                    () -> assertThat(SensitiveValues.fingerprint(result.screen().cardNumber()))
                            .isEqualTo(SensitiveValues.fingerprint(CARD_NUMBER)),
                    () -> assertThat(result.message()).isEqualTo(MSG_CONFIRM_TO_ADD));
            verify(cardCrossReferenceRepository, never())
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(anyString());
            verifyNoInteractions(transactionBoundary);
        }

        @Test
        @DisplayName("VALIDATE-INPUT-KEY-FIELDS line 193: neither key supplied reaches the catch-all "
                + "arm, which reports the field as not supplied rather than as supplied wrongly")
        void neitherKeySuppliedReachesTheCatchAllArm() {
            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(withKeys(confirmedTurn(), null, null));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_KEY_FIELD_REQUIRED),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.fieldErrors()).singleElement()
                            .extracting(ValidationException.FieldError::state)
                            .isEqualTo(ValidationException.FieldState.MISSING));
            verifyNoInteractions(cardCrossReferenceRepository, transactionRepository,
                    transactionBoundary);
        }

        @ParameterizedTest
        @ValueSource(strings = {"1234567890A", "123", "0000000001 ", "-0000000001"})
        @DisplayName("the numeric test is the legacy's, so every character position must hold a digit "
                + "and a space-padded account identifier is not numeric")
        void everyCharacterPositionOfTheAccountKeyMustHoldADigit(final String accountId) {
            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(withKeys(confirmedTurn(), accountId, null));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_ACCOUNT_ID_NOT_NUMERIC),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.fieldErrors()).singleElement()
                            .extracting(ValidationException.FieldError::state)
                            .isEqualTo(ValidationException.FieldState.INVALID));
            verifyNoInteractions(cardCrossReferenceRepository, transactionBoundary);
        }

        @Test
        @DisplayName("a non-numeric card number reports with the card field's own text and cursor")
        void aNonNumericCardNumberReportsWithItsOwnText() {
            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withKeys(confirmedTurn(), null, "411111111111111A"));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_CARD_NUMBER_NOT_NUMERIC),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_CARD_NUMBER));
            verifyNoInteractions(cardCrossReferenceRepository, transactionBoundary);
        }

        @Test
        @DisplayName("an add naming a card the store does not hold is decided by the service and never "
                + "by a constraint, because the transaction tables carry no foreign key at all")
        void anAddNamingAnAbsentCardIsDecidedByTheService() {
            when(cardCrossReferenceRepository.findById(CARD_NUMBER)).thenReturn(Optional.empty());

            final TransactionAddService.TransactionAddResult result;
            result = service.processTransactionAdd(withKeys(confirmedTurn(), null, CARD_NUMBER));

            assertAll(
                    () -> assertThat(result.errorFlag())
                            .as("the decision is the service's own response arm")
                            .isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_CARD_NUMBER_NOT_FOUND));
            // Exactly one explicit lookup and nothing else: the relationship is resolved by a call,
            // never by traversing an association, and no write is even attempted.
            verify(cardCrossReferenceRepository).findById(CARD_NUMBER);
            verifyNoMoreInteractions(cardCrossReferenceRepository);
            verifyNoInteractions(transactionRepository, transactionBoundary);
        }

        @Test
        @DisplayName("the cross-reference record is fifty bytes carrying thirty-six data bytes, which "
                + "is why the delivered fixture and the encoded data set differ in size")
        void theCrossReferenceRecordCarriesThirtySixDataBytesInFifty() {
            assertAll(
                    () -> assertThat(TestDataFactory.CARD_CROSS_REFERENCE_DATASET.recordLength())
                            .isEqualTo(50),
                    () -> assertThat(TestDataFactory.CARD_CROSS_REFERENCE_DATASET.dataLength())
                            .isEqualTo(36),
                    () -> assertThat(TestDataFactory.CARD_CROSS_REFERENCE_DATASET.fillerLength())
                            .isEqualTo(14),
                    () -> assertThat(TestDataFactory.CARD_CROSS_REFERENCE_FIXTURE.recordLength())
                            .isEqualTo(36));
        }
    }

    /**
     * Identifier allocation: the highest existing key plus one, rendered as a sixteen-character
     * business key with significant zero fill.
     *
     * <p>No sequence, no generated value and no random identifier is involved anywhere. A sequence never
     * reuses a value it has handed out, whereas this rule always reuses a gap, so the first turn that
     * rolled back would make a sequence diverge from the legacy permanently. The maximum-identifier
     * query the repository also declares belongs to the bill-payment path and is proven unused here,
     * because this member needs the whole highest-keyed <em>record</em> and not just its key.
     */
    @Nested
    @DisplayName("ADD-TRANSACTION line 442: the sixteen-character identifier, minted without a sequence")
    final class IdentifierAllocationContract {

        @Test
        @DisplayName("the minted identifier is the highest existing key plus one, sixteen encoded bytes "
                + "with its zero fill intact and never trimmed to its significant digits")
        void theMintedIdentifierIsTheHighestKeyPlusOne() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs("0000000000000042");
            insertSucceeds();

            // The declared type of the accessor is String, which is what this assignment proves at
            // compile time: no numeric type is ever the carrier of this key.
            final String minted = insertedTransaction(confirmedTurn()).getTranId();

            assertAll(
                    () -> assertThat(minted).isEqualTo("0000000000000043"),
                    () -> assertThat(encodedWidth(minted)).isEqualTo(TRAN_ID_WIDTH),
                    () -> assertThat(minted)
                            .as("fourteen leading zeros, every one of them significant")
                            .startsWith("0".repeat(14)),
                    () -> assertThat(minted)
                            .as("zero fill is significant: the significant-digit form is another key")
                            .isNotEqualTo("43"));
        }

        @Test
        @DisplayName("READPREV-TRANSACT-FILE seeds zero on an empty master, so the first identifier is "
                + "sixteen zero-filled digits and never a bare one")
        void anEmptyMasterYieldsTheFirstIdentifier() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final String minted = insertedTransaction(confirmedTurn()).getTranId();

            assertAll(
                    () -> assertThat(minted).isEqualTo(FIRST_IDENTIFIER),
                    () -> assertThat(encodedWidth(minted)).isEqualTo(TRAN_ID_WIDTH),
                    () -> assertThat(minted).isNotEqualTo("1"));
        }

        @Test
        @DisplayName("STARTBR-TRANSACT-FILE positions the browse at the high end of the key sequence as "
                + "a descending single-row read, and no maximum-identifier query is ever consulted")
        void theBrowseIsADescendingSingleRowReadAndNoSequenceIsConsulted() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            service.processTransactionAdd(confirmedTurn());

            final ArgumentCaptor<Pageable> browse = ArgumentCaptor.forClass(Pageable.class);
            verify(transactionRepository).findAll(browse.capture());
            assertAll(
                    () -> assertThat(browse.getValue().getPageNumber()).isZero(),
                    () -> assertThat(browse.getValue().getPageSize())
                            .as("a backward read consumes exactly one record")
                            .isEqualTo(1),
                    () -> assertThat(browse.getValue().getSort())
                            .isEqualTo(Sort.by(Sort.Direction.DESC, "tranId")));
            verify(transactionRepository, never()).findMaxId();
        }

        @Test
        @DisplayName("the allocation lock is taken exactly once, with the key the repository declares")
        void theAllocationLockIsTakenOnceWithTheDeclaredKey() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            service.processTransactionAdd(confirmedTurn());

            verify(transactionRepository)
                    .lockIdentifierAllocation(TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
            verify(transactionBoundary, times(1)).<Transaction>execute(any());
        }

        @ParameterizedTest
        @CsvSource({"1,0000000000000002", "0000000000000009,0000000000000010",
                    "0000000000000099,0000000000000100", "9999999999999998,9999999999999999"})
        @DisplayName("a highest key of any width still yields a sixteen-digit zero-filled successor")
        void anyHighestKeyWidthYieldsSixteenZeroFilledDigits(final String highestKey,
                final String expected) {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(highestKey);
            insertSucceeds();

            final String minted = insertedTransaction(confirmedTurn()).getTranId();

            assertAll(
                    () -> assertThat(minted).isEqualTo(expected),
                    () -> assertThat(encodedWidth(minted)).isEqualTo(TRAN_ID_WIDTH));
        }

        @Test
        @DisplayName("a highest key wider than the sixteen-digit work field keeps only its low-order "
                + "sixteen digits, exactly as an unrounded store into a fixed-width numeric field does")
        void aHighestKeyWiderThanTheFieldKeepsItsLowOrderDigits() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs("99999999999999999");
            insertSucceeds();

            final String minted = insertedTransaction(confirmedTurn()).getTranId();

            assertAll(
                    () -> assertThat(minted).isEqualTo("0".repeat(TRAN_ID_WIDTH)),
                    () -> assertThat(encodedWidth(minted)).isEqualTo(TRAN_ID_WIDTH));
        }

        @Test
        @DisplayName("a highest key that is not all digits seeds from zero rather than inventing a value")
        void aNonNumericHighestKeySeedsFromZero() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs("ABCDEFGHIJKLMNOP");
            insertSucceeds();

            assertThat(insertedTransaction(confirmedTurn()).getTranId()).isEqualTo(FIRST_IDENTIFIER);
        }

        @Test
        @DisplayName("ENDBR-TRANSACT-FILE releases the position without reporting anything, so one "
                + "successful turn establishes and consumes exactly one browse")
        void theBrowseIsEstablishedAndConsumedExactlyOnce() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            verify(transactionRepository, times(1)).findAll(any(Pageable.class));
            assertThat(result.errorFlag()).isFalse();
        }

        @Test
        @DisplayName("a failure positioning the browse becomes the transaction-lookup text at the "
                + "account field rather than a propagating persistence failure")
        void aFailurePositioningTheBrowseBecomesTheLookupText() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            when(transactionRepository.findAll(any(Pageable.class)))
                    .thenThrow(new DataAccessResourceFailureException("browse refused"));

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_TRANSACTION_LOOKUP_FAILED),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.transaction()).isNull());
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("WRITE-TRANSACT-FILE reports an identifier already present with the legacy "
                + "spelling, after re-reading the locked maximum once more")
        void anIdentifierAlreadyPresentIsReportedWithTheLegacySpelling() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            when(transactionRepository.existsById(FIRST_IDENTIFIER)).thenReturn(true);

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_ALREADY_EXISTS),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.transactionAdded()).isFalse());
            verify(transactionRepository, times(2)).findAll(any(Pageable.class));
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("a duplicate raised by the flush is reported after the unit has rolled back")
        void aDuplicateRaisedByTheFlushIsReportedAfterRollback() {
            accountResolves();
            datesAnswer(acceptedBlock());
            // The shape a real duplicate has, measured against PostgreSQL in
            // RecordWriterDuplicateClassificationIT: the framework translates a JPA constraint failure
            // to the BROAD integrity type, and what identifies it as a refused key rather than a
            // foreign-key, not-null or check refusal is the complete SQL state the driver reported.
            boundaryFailsAfterRunning(new DataIntegrityViolationException("duplicate key",
                    new SQLException("duplicate key value violates unique constraint", "23505")));
            highestTransactionIs(null);
            insertSucceeds();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_ALREADY_EXISTS),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.transaction()).isNull());
        }

        @Test
        @DisplayName("any other write failure reports the catch-all text at the account field")
        void anyOtherWriteFailureReportsTheCatchAllText() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryFailsAfterRunning(new DataAccessResourceFailureException("write refused"));
            highestTransactionIs(null);
            insertSucceeds();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_ADD),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.errorFlag()).isTrue());
        }
    }

    @Nested
    @DisplayName("ADD-TRANSACTION lines 450 to 465: fixed-width and exact-decimal fidelity")
    final class FieldFidelityContract {

        @Test
        @DisplayName("the transaction source keeps the trailing spaces its fixed-width field pads with, "
                + "measured on the encoded byte image and never trimmed")
        void theTransactionSourceKeepsItsTrailingSpaces() {
            final Transaction stored = insertedBy(confirmedTurn());

            assertAll(
                    () -> assertThat(stored.getTranSource()).isEqualTo(STORED_SOURCE),
                    () -> assertThat(encodedWidth(stored.getTranSource())).isEqualTo(SOURCE_WIDTH),
                    () -> assertThat(stored.getTranSource()).endsWith(" "),
                    () -> assertThat(stored.getTranSource())
                            .as("a trimmed source would be a different fixed-width value")
                            .isNotEqualTo(SOURCE));
        }

        @Test
        @DisplayName("every stored field carries its declared width, so the category code keeps its "
                + "leading zeros and the description its trailing pad")
        void everyStoredFieldCarriesItsDeclaredWidth() {
            final Transaction stored = insertedBy(confirmedTurn());

            assertAll(
                    () -> assertThat(stored.getTranTypeCd()).isEqualTo("01"),
                    () -> assertThat(stored.getTranCatCd())
                            .as("a four-character category stays four characters")
                            .isEqualTo("0001"),
                    () -> assertThat(encodedWidth(stored.getTranCatCd())).isEqualTo(4),
                    () -> assertThat(stored.getTranDesc())
                            .isEqualTo(DESCRIPTION + " ".repeat(100 - DESCRIPTION.length())),
                    () -> assertThat(encodedWidth(stored.getTranDesc())).isEqualTo(100),
                    () -> assertThat(stored.getMerchantId()).isEqualTo(MERCHANT_ID),
                    () -> assertThat(encodedWidth(stored.getMerchantName())).isEqualTo(50),
                    () -> assertThat(encodedWidth(stored.getMerchantCity())).isEqualTo(50),
                    () -> assertThat(encodedWidth(stored.getMerchantZip())).isEqualTo(10),
                    () -> assertThat(encodedWidth(stored.getTranCardNum())).isEqualTo(16));
        }

        @Test
        @DisplayName("the amount is an exact decimal at scale two, carrying the operator's sign")
        void theAmountIsAnExactDecimalAtScaleTwo() {
            // The declared type of the accessor is BigDecimal, which this assignment proves at compile
            // time: no binary floating-point type is ever the carrier of a monetary value.
            final BigDecimal stored = insertedBy(confirmedTurn()).getTranAmt();

            assertAll(
                    () -> assertThat(stored.scale()).isEqualTo(2),
                    () -> assertThat(stored.toPlainString()).isEqualTo("-100.00"),
                    () -> assertThat(stored).isEqualTo(new BigDecimal("-100.00")));
        }

        @Test
        @DisplayName("a third decimal digit is truncated toward zero and never rounded, which is the "
                + "contract because the estate carries no rounding directive anywhere")
        void aThirdDecimalDigitIsTruncatedAndNeverRounded() {
            final BigDecimal stored = insertedBy(
                    withDataFields(confirmedTurn(), "+00000001.239", null, null)).getTranAmt();

            assertAll(
                    () -> assertThat(stored.toPlainString())
                            .as("truncation toward zero keeps the second decimal digit unchanged")
                            .isEqualTo("1.23"),
                    () -> assertThat(stored.scale()).isEqualTo(2),
                    () -> assertThat(stored)
                            .as("half-even or half-up would have produced this value instead")
                            .isNotEqualTo(new BigDecimal("1.24")));
        }

        @Test
        @DisplayName("a negative amount is stored with its sign intact, because negative amounts are a "
                + "live production shape")
        void aNegativeAmountIsStoredWithItsSignIntact() {
            final BigDecimal stored = insertedBy(
                    withDataFields(confirmedTurn(), "-00000045.67", null, null)).getTranAmt();

            assertAll(
                    () -> assertThat(stored.toPlainString()).isEqualTo("-45.67"),
                    () -> assertThat(stored.signum()).isNegative(),
                    () -> assertThat(stored.scale()).isEqualTo(2));
        }

        @Test
        @DisplayName("the amount lexeme is rewritten in the edited picture, which always emits a sign, "
                + "so a positive amount comes back with a leading plus")
        void theAmountLexemeIsRewrittenInTheEditedPicture() {
            accountResolves();
            datesAnswer(acceptedBlock());

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withDataFields(validTurn(null), "+00000012.34", null, null));

            assertAll(
                    () -> assertThat(result.screen().amount()).isEqualTo("+00000012.34"),
                    () -> assertThat(encodedWidth(result.screen().amount())).isEqualTo(12));
        }

        @ParameterizedTest
        @ValueSource(strings = {"MARY ANN", "Aniya Von", "MERCHANT  NAME"})
        @DisplayName("a value carrying embedded spaces is accepted and stored byte for byte, neither "
                + "folded nor trimmed: this member declares no alphabetic edit at all")
        void embeddedSpacesAreAcceptedAndCarriedByteForByte(final String merchantName) {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final Transaction stored = insertedTransaction(
                    withMerchant(confirmedTurn(), MERCHANT_ID, merchantName));

            assertAll(
                    () -> assertThat(stored.getMerchantName())
                            .as("the embedded space survives and the case is not folded")
                            .startsWith(merchantName),
                    () -> assertThat(stored.getMerchantName().strip()).isEqualTo(merchantName),
                    () -> assertThat(encodedWidth(stored.getMerchantName())).isEqualTo(50));
        }

        @ParameterizedTest
        @ValueSource(strings = {"00000000A", "0000000 1", "-00000001"})
        @DisplayName("the predicate discriminates: a field this member requires to be all digits is "
                + "rejected when any position holds something else")
        void anAllDigitFieldIsRejectedWhenAnyPositionHoldsSomethingElse(final String merchantId) {
            accountResolves();
            datesAnswer(acceptedBlock());

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withMerchant(confirmedTurn(), merchantId, MERCHANT_NAME));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_MERCHANT_ID_NOT_NUMERIC),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_MERCHANT_ID),
                    () -> assertThat(result.errorFlag()).isTrue());
            verifyNoInteractions(transactionBoundary);
        }

        @Test
        @DisplayName("the merchant properties of the posted record are unprefixed, which is the "
                + "convention this entity keeps and never shares with the daily-transaction entity")
        void theMerchantPropertiesOfThePostedRecordAreUnprefixed() {
            final Transaction stored = insertedBy(confirmedTurn());

            assertAll(
                    () -> assertThat(stored.getMerchantName()).startsWith(MERCHANT_NAME),
                    () -> assertThat(stored.getMerchantCity()).startsWith(MERCHANT_CITY),
                    () -> assertThat(stored.getMerchantZip()).startsWith(MERCHANT_ZIP),
                    () -> assertThat(stored.getMerchantId()).isEqualTo(MERCHANT_ID));
        }
    }

    @Nested
    @DisplayName("VALIDATE-INPUT-DATA-FIELDS lines 251 to 320 and the message contract")
    final class MessageAndFieldErrorContract {

        @Test
        @DisplayName("the unmapped-key arm passes the catalogue message through byte-identically at its "
                + "full fifty encoded bytes, with its ten trailing spaces intact and never trimmed")
        void theUnmappedKeyArmPassesTheCatalogueMessageThroughUntrimmed() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(INVALID_KEY_MESSAGE);

            // The clear key is deliberately not one of the four arms the source names, so it reaches
            // the catch-all exactly as an undecoded key does.
            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withTurnState(confirmedTurn(), KeyAction.CLEAR, reSubmission()));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(INVALID_KEY_MESSAGE),
                    () -> assertThat(encodedWidth(result.message())).isEqualTo(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(result.message()).endsWith(" ".repeat(10)),
                    () -> assertThat(result.message())
                            .as("a trimmed catalogue value is a different fixed-width message")
                            .isNotEqualTo(result.message().strip()),
                    () -> assertThat(result.errorFlag()).isTrue());
            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }

        @Test
        @DisplayName("an undecoded attention key reaches the same catch-all arm as an unnamed one")
        void anUndecodedAttentionKeyReachesTheSameCatchAllArm() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(INVALID_KEY_MESSAGE);

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withTurnState(confirmedTurn(), null, reSubmission()));

            assertThat(result.message()).isEqualTo(INVALID_KEY_MESSAGE);
        }

        @Test
        @DisplayName("the outbound message field truncates the eighty-character work field into its own "
                + "seventy-eight positions and keeps the message left justified")
        void theOutboundMessageFieldCarriesSeventyEightPositions() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(INVALID_KEY_MESSAGE);

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withTurnState(confirmedTurn(), KeyAction.CLEAR, reSubmission()));

            assertAll(
                    () -> assertThat(encodedWidth(result.header().errorMessage()))
                            .isEqualTo(ERROR_MESSAGE_WIDTH),
                    () -> assertThat(result.header().errorMessage()).startsWith(INVALID_KEY_MESSAGE));
        }

        @Test
        @DisplayName("a required field that was not supplied is reported as MISSING, with the field's "
                + "own text and its own cursor position")
        void aFieldNotSuppliedIsReportedAsMissing() {
            accountResolves();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(withCodes(confirmedTurn(), null, "0001"));

            assertThat(result.fieldErrors()).hasSize(1);
            final ValidationException.FieldError error = result.fieldErrors().get(0);
            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_TYPE_CD_EMPTY),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_TYPE_CD),
                    () -> assertThat(error.field()).isEqualTo("typeCd"),
                    () -> assertThat(error.bmsFieldId()).isEqualTo(FIELD_TYPE_CD),
                    () -> assertThat(error.state())
                            .isEqualTo(ValidationException.FieldState.MISSING),
                    () -> assertThat(error.message()).isEqualTo(MSG_TYPE_CD_EMPTY));
        }

        @Test
        @DisplayName("a field supplied wrongly is reported as INVALID, which is a different state from "
                + "a field that was not supplied at all")
        void aFieldSuppliedWronglyIsReportedAsInvalid() {
            accountResolves();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(withCodes(confirmedTurn(), "0A", "0001"));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_TYPE_CD_NOT_NUMERIC),
                    () -> assertThat(result.fieldErrors()).singleElement()
                            .extracting(ValidationException.FieldError::state)
                            .isEqualTo(ValidationException.FieldState.INVALID));
        }

        @Test
        @DisplayName("the field-error state has exactly two constants, so a blank field and a wrongly "
                + "supplied one can never collapse into one boolean")
        void theFieldErrorStateHasExactlyTwoConstants() {
            assertThat(ValidationException.FieldState.values())
                    .containsExactly(ValidationException.FieldState.MISSING,
                            ValidationException.FieldState.INVALID);
        }

        @Test
        @DisplayName("the field-error list is never null and is unmodifiable, so nothing that leaves the "
                + "service can be altered by a consumer")
        void theFieldErrorListIsNeverNullAndUnmodifiable() {
            accountResolves();

            final TransactionAddService.TransactionAddResult faulted =
                    service.processTransactionAdd(withCodes(confirmedTurn(), null, "0001"));
            final List<ValidationException.FieldError> errors = faulted.fieldErrors();

            assertAll(
                    () -> assertThat(errors).isNotNull().hasSize(1),
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(() -> errors.add(new ValidationException.FieldError("x", "X",
                                    ValidationException.FieldState.INVALID, "text"))),
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(errors::clear));
        }

        @Test
        @DisplayName("a successful turn reports an empty but non-null field-error list")
        void aSuccessfulTurnReportsAnEmptyButNonNullFieldErrorList() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            assertThat(result.fieldErrors()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("the emptiness cascade keeps the source's own order, which tests the description "
                + "before the amount even though the screen lists them the other way round")
        void theEmptinessCascadeTestsTheDescriptionBeforeTheAmount() {
            accountResolves();

            // Both fields are blank at once, so whichever text appears is the one the cascade reached
            // first. The source's order is contractual: the operator sees the description's text.
            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withDataFields(withText(confirmedTurn(), SOURCE, null), "", null, null));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_DESCRIPTION_EMPTY),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_DESCRIPTION),
                    () -> assertThat(result.message()).isNotEqualTo(MSG_AMOUNT_EMPTY));
        }

        @ParameterizedTest
        // The expected texts are the declared constants rather than a second spelling of the same
        // literal, so a contract text exists in exactly one place in this class.
        @CsvSource({"''," + MSG_AMOUNT_EMPTY,
                    "'0000000100'," + MSG_AMOUNT_FORMAT,
                    "'+0000010000'," + MSG_AMOUNT_FORMAT,
                    "'+00000100,00'," + MSG_AMOUNT_FORMAT})
        @DisplayName("the amount is empty before it is malformed, and a sign is mandatory because the "
                + "combined relation reads as neither a minus nor a plus")
        void theAmountIsEmptyBeforeItIsMalformed(final String amount, final String expected) {
            accountResolves();

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withDataFields(confirmedTurn(), amount, null, null));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(expected),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_AMOUNT));
        }

        @ParameterizedTest
        @CsvSource({"''," + MSG_ORIG_DATE_EMPTY,
                    "'2022/07/19'," + MSG_ORIG_DATE_FORMAT,
                    "'202-07-19'," + MSG_ORIG_DATE_FORMAT})
        @DisplayName("the origination date is empty before it is malformed, and its five fixed positions "
                + "are tested before the subprogram is asked whether the shape names a real day")
        void theOriginationDateIsEmptyBeforeItIsMalformed(final String origDate,
                final String expected) {
            accountResolves();

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withDataFields(confirmedTurn(), null, origDate, null));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(expected),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ORIG_DATE));
            verifyNoInteractions(dateValidationService);
        }

        @Test
        @DisplayName("the processing date reports the empty and the malformed cases with its own texts")
        void theProcessingDateReportsWithItsOwnTexts() {
            accountResolves();

            final TransactionAddService.TransactionAddResult empty = service.processTransactionAdd(
                    withDataFields(confirmedTurn(), null, null, ""));
            final TransactionAddService.TransactionAddResult malformed =
                    service.processTransactionAdd(
                            withDataFields(confirmedTurn(), null, null, "2022.07.20"));

            assertAll(
                    () -> assertThat(empty.message()).isEqualTo(MSG_PROC_DATE_EMPTY),
                    () -> assertThat(malformed.message()).isEqualTo(MSG_PROC_DATE_FORMAT),
                    () -> assertThat(empty.focusField()).isEqualTo(FIELD_PROC_DATE),
                    () -> assertThat(malformed.focusField()).isEqualTo(FIELD_PROC_DATE));
            verifyNoInteractions(dateValidationService);
        }

        @Test
        @DisplayName("SEND-TRNADD-SCREEN ends the task, so a turn with several failing fields reports "
                + "exactly one - the first the cascade reached")
        void aTurnWithSeveralFailingFieldsReportsExactlyOne() {
            accountResolves();

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withDataFields(withCodes(confirmedTurn(), null, null), null, null, null));

            assertAll(
                    () -> assertThat(result.fieldErrors()).hasSize(1),
                    () -> assertThat(result.message()).isEqualTo(MSG_TYPE_CD_EMPTY),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_TYPE_CD));
        }

        @Test
        @DisplayName("PROCESS-ENTER-KEY conflates a declined confirmation with an unanswered one, which "
                + "is the source's own conflation and is preserved rather than tidied")
        void aDeclinedAndAnUnansweredConfirmationTakeTheSameArm() {
            accountResolves();
            datesAnswer(acceptedBlock(), acceptedBlock());

            final TransactionAddService.TransactionAddResult declined =
                    service.processTransactionAdd(validTurn("N"));

            assertAll(
                    () -> assertThat(declined.message()).isEqualTo(MSG_CONFIRM_TO_ADD),
                    () -> assertThat(declined.focusField()).isEqualTo(FIELD_CONFIRM),
                    () -> assertThat(declined.fieldErrors()).singleElement()
                            .extracting(ValidationException.FieldError::state)
                            .isEqualTo(ValidationException.FieldState.MISSING));
            verifyNoInteractions(transactionBoundary);
        }

        @ParameterizedTest
        @ValueSource(strings = {"Y", "y"})
        @DisplayName("an affirmative confirmation in either case inserts")
        void anAffirmativeConfirmationInEitherCaseInserts(final String confirm) {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            assertThat(service.processTransactionAdd(validTurn(confirm)).transactionAdded()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"X", "1", "?"})
        @DisplayName("any other confirmation value is reported as invalid with its own text")
        void anyOtherConfirmationValueIsReportedAsInvalid(final String confirm) {
            accountResolves();
            datesAnswer(acceptedBlock());

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(validTurn(confirm));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_INVALID_CONFIRM),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_CONFIRM),
                    () -> assertThat(result.fieldErrors()).singleElement()
                            .extracting(ValidationException.FieldError::state)
                            .isEqualTo(ValidationException.FieldState.INVALID));
            verifyNoInteractions(transactionBoundary);
        }

        @Test
        @DisplayName("WRITE-TRANSACT-FILE composes the success text from its four fragments, whose "
                + "leading and trailing spaces are part of the literals")
        void theSuccessTextIsComposedFromItsFourFragments() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(SUCCESS_MESSAGE),
                    () -> assertThat(result.message())
                            .as("the head's trailing space and the middle's leading space both survive")
                            .contains("successfully.  Your"),
                    () -> assertThat(result.message()).endsWith(FIRST_IDENTIFIER + "."),
                    () -> assertThat(result.header().messageHighlighted())
                            .as("the successful-insert path is the one place the field is recoloured")
                            .isTrue());
        }
    }

    @Nested
    @DisplayName("WRITE-TRANSACT-FILE line 711: interaction order and discipline")
    final class InteractionOrderContract {

        @Test
        @DisplayName("the lookup, the independent unit, the allocation lock, the backward read, the "
                + "existence probe and the flush occur in exactly that order, and nothing else is called")
        void thePersistenceCallsOccurInTheDeclaredOrder() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            service.processTransactionAdd(confirmedTurn());

            final InOrder order = inOrder(cardCrossReferenceRepository, transactionBoundary,
                    transactionRepository);
            order.verify(cardCrossReferenceRepository)
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            order.verify(transactionBoundary).<Transaction>execute(any());
            order.verify(transactionRepository)
                    .lockIdentifierAllocation(TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
            order.verify(transactionRepository).findAll(any(Pageable.class));
            order.verify(transactionRepository).existsById(FIRST_IDENTIFIER);
            order.verify(transactionRepository).insertAndFlush(any(Transaction.class));
            order.verifyNoMoreInteractions();

            verifyNoMoreInteractions(transactionRepository);
            verifyNoMoreInteractions(cardCrossReferenceRepository);
            verifyNoMoreInteractions(transactionBoundary);
        }

        @Test
        @DisplayName("the date subprogram is consulted exactly twice and the catalogue exactly twice, "
                + "with no stray call to either collaborator")
        void theCollaboratorsAreConsultedExactlyAsOftenAsTheSourceConsultsThem() {
            accountResolves();
            catalogueTitlesAvailable();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            service.processTransactionAdd(confirmedTurn());

            verify(dateValidationService, times(2)).validateDate(anyString(), any(DateFormat.class));
            verifyNoMoreInteractions(dateValidationService);
            verify(messageCatalogService).screenTitle01();
            verify(messageCatalogService).screenTitle02();
            verify(messageCatalogService, never()).invalidKeyMessage();
            verifyNoMoreInteractions(messageCatalogService);
            verifyNoInteractions(navigationService);
        }

        @Test
        @DisplayName("a turn stopped by a key-field failure touches neither the transaction master nor "
                + "the independent unit, so no partial work is ever attempted")
        void aTurnStoppedByAKeyFieldFailureTouchesNoPersistence() {
            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(withKeys(confirmedTurn(), null, null));

            assertThat(result.errorFlag()).isTrue();
            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary, dateValidationService);
        }
    }

    @Nested
    @DisplayName("MAIN-PARA line 107 and RETURN-TO-PREV-SCREEN line 500: routing without forwarding")
    final class RouteContract {

        @Test
        @DisplayName("a turn that re-presents the screen answers with this screen's own route constant "
                + "and re-arms its own transaction, so the client drives the next call")
        void aRePresentedScreenAnswersWithItsOwnRouteConstant() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            assertAll(
                    () -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.TRANSACTION_ADD),
                    () -> assertThat(result.route().getRouteValue()).isEqualTo("transaction-add"),
                    () -> assertThat(result.route().getLegacyTransactionId())
                            .isEqualTo(RE_ARMED_TRANSACTION_ID),
                    () -> assertThat(result.route().getLegacyProgramName()).isEqualTo(PROGRAM_NAME),
                    () -> assertThat(result.reArmedTransactionId())
                            .as("the re-arm is the client's next call, never a server-side forward")
                            .isEqualTo(RE_ARMED_TRANSACTION_ID));
            verifyNoInteractions(navigationService);
        }

        @Test
        @DisplayName("the exit key nominates a destination and the navigation rules resolve it, so the "
                + "route is never a hardcoded literal of this service's own")
        void theExitKeyLetsTheNavigationRulesResolveTheDestination() {
            // A distinctive destination no arm of this member could have written as a literal.
            when(navigationService.resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON)))
                    .thenReturn(NavigationService.Route.CARD_LIST);

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withTurnState(confirmedTurn(), KeyAction.PFK03, reSubmission()));

            assertAll(
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_LIST),
                    () -> assertThat(result.reArmedTransactionId())
                            .as("a transfer carries the state but re-arms no transaction")
                            .isEmpty(),
                    () -> assertThat(result.navigationContext().fromTransactionId())
                            .isEqualTo(RE_ARMED_TRANSACTION_ID),
                    () -> assertThat(result.navigationContext().fromProgram()).isEqualTo(PROGRAM_NAME),
                    () -> assertThat(result.navigationContext().firstEntry())
                            .as("the program context is reset on the way out")
                            .isTrue());
            verify(navigationService).resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON));
            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }

        @Test
        @DisplayName("a turn carrying no navigation state at all is routed by the rules for an absent "
                + "context rather than by a program name written here")
        void aTurnCarryingNoNavigationStateIsRoutedByTheRules() {
            when(navigationService.resolveAbsentContextRoute())
                    .thenReturn(NavigationService.Route.SIGN_ON);
            when(navigationService.resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON)))
                    .thenReturn(NavigationService.Route.SIGN_ON);

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withTurnState(confirmedTurn(), KeyAction.ENTER, null));

            assertAll(
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.SIGN_ON),
                    () -> assertThat(result.reArmedTransactionId()).isEmpty(),
                    () -> assertThat(result.transaction()).isNull());
            verify(navigationService).resolveAbsentContextRoute();
            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary, dateValidationService);
        }

        @Test
        @DisplayName("no route this service can answer with names the dangling CICS program definition "
                + "that has no source member")
        void noRouteNamesTheDanglingProgramDefinition() {
            assertThat(NavigationService.Route.values())
                    .as("the definition exists in the resource table but no member implements it")
                    .noneMatch(route -> DANGLING_PROGRAM_NAME.equals(route.getLegacyProgramName()));
        }

        @Test
        @DisplayName("MAIN-PARA sets the re-enter gate on a first entry and reports the gate back, "
                + "because per-field detail can only be produced on a re-submission")
        void aFirstEntrySetsTheReEnterGateAndReportsItBack() {
            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withTurnState(confirmedTurn(), KeyAction.ENTER, firstEntry()));

            assertAll(
                    () -> assertThat(result.reEnter()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.message())
                            .as("a first entry with no pre-selection simply presents the screen")
                            .isEmpty(),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.TRANSACTION_ADD));
            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary, dateValidationService, navigationService);
        }

        @Test
        @DisplayName("a first entry carrying a pre-selected transaction moves it into the card field and "
                + "processes the enter key immediately, before the map has been received, so the key "
                + "resolves without an operator keystroke while the data fields are still blank")
        void aFirstEntryCarryingAPreSelectedTransactionProcessesTheEnterKeyImmediately() {
            cardResolves();

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withKeys(withSelection(validTurn(null), CARD_NUMBER, KeyAction.ENTER,
                            firstEntry()), null, null));

            assertAll(
                    () -> assertThat(result.screen().cardNumber())
                            .as("the pre-selected transaction is moved into the card field")
                            .isEqualTo(CARD_NUMBER),
                    () -> assertThat(result.screen().accountId())
                            .as("and the cross reference resolves the account from it")
                            .isEqualTo(ACCOUNT_ID),
                    () -> assertThat(result.message())
                            .as("the receive paragraph has not run on a first entry, so the data "
                                    + "fields are still blank and the cascade reports its first field")
                            .isEqualTo(MSG_TYPE_CD_EMPTY),
                    () -> assertThat(result.reEnter()).isTrue());
            verify(cardCrossReferenceRepository).findById(CARD_NUMBER);
            verifyNoInteractions(dateValidationService, transactionBoundary);
        }
    }

    @Nested
    @DisplayName("CLEAR-CURRENT-SCREEN 754, INITIALIZE-ALL-FIELDS 762, COPY-LAST-TRAN-DATA 471")
    final class ScreenResetAndCopyContract {

        @Test
        @DisplayName("CLEAR-CURRENT-SCREEN blanks every field and leaves no text at all, which is the "
                + "source's behaviour and is not embellished with a message")
        void clearCurrentScreenBlanksEveryFieldAndLeavesNoText() {
            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withTurnState(confirmedTurn(), KeyAction.PFK04, reSubmission()));

            final TransactionAddService.ScreenFields screen = result.screen();
            assertAll(
                    () -> assertThat(result.message()).isEmpty(),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(screen.accountId()).isEqualTo(" ".repeat(11)),
                    () -> assertThat(screen.cardNumber()).isEqualTo(" ".repeat(16)),
                    () -> assertThat(screen.typeCd()).isEqualTo(" ".repeat(2)),
                    () -> assertThat(screen.categoryCd()).isEqualTo(" ".repeat(4)),
                    () -> assertThat(screen.source()).isEqualTo(" ".repeat(10)),
                    () -> assertThat(screen.description()).isEqualTo(" ".repeat(60)),
                    () -> assertThat(screen.amount()).isEqualTo(" ".repeat(12)),
                    () -> assertThat(screen.origDate()).isEqualTo(" ".repeat(10)),
                    () -> assertThat(screen.procDate()).isEqualTo(" ".repeat(10)),
                    () -> assertThat(screen.merchantId()).isEqualTo(" ".repeat(9)),
                    () -> assertThat(screen.merchantName()).isEqualTo(" ".repeat(30)),
                    () -> assertThat(screen.merchantCity()).isEqualTo(" ".repeat(25)),
                    () -> assertThat(screen.merchantZip()).isEqualTo(" ".repeat(10)),
                    () -> assertThat(screen.confirm()).isEqualTo(" "));
            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }

        @Test
        @DisplayName("INITIALIZE-ALL-FIELDS blanks all fourteen fields after a successful insert, which "
                + "is why the operator gets a clean screen with the success text on it")
        void initializeAllFieldsBlanksAllFourteenFieldsAfterASuccessfulInsert() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            final TransactionAddService.ScreenFields screen = result.screen();
            assertAll(
                    () -> assertThat(screen.accountId()).isBlank(),
                    () -> assertThat(screen.cardNumber()).isBlank(),
                    () -> assertThat(screen.amount()).isBlank(),
                    () -> assertThat(screen.confirm()).isBlank(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.message())
                            .as("the reset blanks the work field and the success text is composed after")
                            .isEqualTo(SUCCESS_MESSAGE));
        }

        @Test
        @DisplayName("COPY-LAST-TRAN-DATA copies eleven fields from the highest-keyed record, truncating "
                + "both twenty-six-character timestamps to their ten-character date part, and runs the "
                + "key-field validation a second time exactly as the source performs it twice")
        void copyLastTranDataCopiesElevenFieldsAndTruncatesBothTimestamps() {
            final Transaction last = TestDataFactory.transaction()
                    .id("0000000000000042")
                    .typeCode("07")
                    .categoryCode("0003")
                    .source("OPERATOR")
                    .description("LAST TRANSACTION")
                    .amount(new BigDecimal("25.75"))
                    .merchantId("000000777")
                    .merchantName("LAST MERCHANT")
                    .merchantCity("LAST CITY")
                    .merchantZip("20002")
                    .cardNumber(CARD_NUMBER)
                    .originalTimestamp("2022-06-10 19:27:53.000000")
                    .processingTimestamp("2022-06-11 08:15:00.000000")
                    .build();
            accountResolves();
            when(transactionRepository.findAll(any(Pageable.class))).thenReturn(pageOfRecord(last));
            datesAnswer(acceptedBlock());

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withTurnState(validTurn(null), KeyAction.PFK05, reSubmission()));

            final TransactionAddService.ScreenFields screen = result.screen();
            assertAll(
                    () -> assertThat(screen.typeCd()).isEqualTo("07"),
                    () -> assertThat(screen.categoryCd()).isEqualTo("0003"),
                    () -> assertThat(screen.source()).isEqualTo("OPERATOR" + " ".repeat(2)),
                    () -> assertThat(screen.description())
                            .isEqualTo("LAST TRANSACTION" + " ".repeat(44)),
                    () -> assertThat(screen.amount()).isEqualTo("+00000025.75"),
                    () -> assertThat(screen.origDate())
                            .as("twenty-six characters into ten keeps the leftmost ten, the date part")
                            .isEqualTo("2022-06-10"),
                    () -> assertThat(screen.procDate()).isEqualTo("2022-06-11"),
                    () -> assertThat(screen.merchantId()).isEqualTo("000000777"),
                    () -> assertThat(screen.merchantName())
                            .isEqualTo("LAST MERCHANT" + " ".repeat(17)),
                    () -> assertThat(screen.merchantCity()).isEqualTo("LAST CITY" + " ".repeat(16)),
                    () -> assertThat(screen.merchantZip()).isEqualTo("20002" + " ".repeat(5)),
                    () -> assertThat(result.message()).isEqualTo(MSG_CONFIRM_TO_ADD));
            verify(cardCrossReferenceRepository, times(2))
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            verifyNoInteractions(transactionBoundary);
        }

        @Test
        @DisplayName("COPY-LAST-TRAN-DATA on an empty master leaves the screen fields as transmitted, "
                + "because there is no record to copy and no value may be invented")
        void copyLastTranDataOnAnEmptyMasterLeavesTheScreenAsTransmitted() {
            accountResolves();
            highestTransactionIs(null);
            datesAnswer(acceptedBlock());

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withTurnState(validTurn(null), KeyAction.PFK05, reSubmission()));

            assertAll(
                    () -> assertThat(result.screen().typeCd()).isEqualTo("01"),
                    () -> assertThat(result.screen().origDate()).isEqualTo(ORIG_DATE),
                    () -> assertThat(result.message()).isEqualTo(MSG_CONFIRM_TO_ADD));
        }

        @Test
        @DisplayName("RECEIVE-TRNADD-SCREEN bounds every transmitted value to its declared width, so a "
                + "longer value loses its excess on the right and a shorter one is space filled")
        void receiveTrnaddScreenBoundsEveryTransmittedValueToItsDeclaredWidth() {
            accountResolves();
            datesAnswer(acceptedBlock());

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withText(validTurn(null), "A", "B".repeat(80)));

            assertAll(
                    () -> assertThat(result.screen().source()).isEqualTo("A" + " ".repeat(9)),
                    () -> assertThat(encodedWidth(result.screen().source())).isEqualTo(SOURCE_WIDTH),
                    () -> assertThat(result.screen().description()).isEqualTo("B".repeat(60)),
                    () -> assertThat(encodedWidth(result.screen().description())).isEqualTo(60),
                    () -> assertThat(encodedWidth(result.screen().accountId())).isEqualTo(11),
                    () -> assertThat(encodedWidth(result.screen().confirm())).isEqualTo(1));
        }
    }

    @Nested
    @DisplayName("absent and boundary input, and the whole reported surface")
    final class AbsentInputAndReportedSurfaceContract {

        @Test
        @DisplayName("every screen component may be absent: an untransmitted field is treated exactly as "
                + "a blank one, with no runtime failure escaping")
        void everyScreenComponentMayBeAbsent() {
            final TransactionAddService.TransactionAddScreenInput empty =
                    new TransactionAddService.TransactionAddScreenInput(null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null,
                            KeyAction.ENTER, reSubmission());

            assertThatNoException().isThrownBy(() -> service.processTransactionAdd(empty));

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(empty);
            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_KEY_FIELD_REQUIRED),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.fieldErrors()).hasSize(1));
            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary, dateValidationService);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "0000000001100000000011"})
        @DisplayName("a blank, a short and an over-long account key are all answered by a message and a "
                + "cursor position, never by an index failure")
        void blankShortAndOverLongAccountKeysAreAnsweredWithAMessage(final String accountId) {
            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(withKeys(confirmedTurn(), accountId, null));

            // The receive paragraph bounds the field to its declared width before anything tests it, so a
            // twenty-two character submission is an eleven character key and the excess is simply gone.
            // The first eleven characters of the over-long value are the fixture key, so that case reaches
            // the cross-reference lookup; the blank and all-space cases never leave the key edit.
            final String boundedKey = accountId.length() > ACCOUNT_ID_WIDTH
                    ? accountId.substring(0, ACCOUNT_ID_WIDTH)
                    : accountId;
            final boolean keyReachesTheLookup = !boundedKey.isBlank();

            assertAll(
                    () -> assertThat(result.errorFlag())
                            .as("every one of the three is refused")
                            .isTrue(),
                    () -> assertThat(result.transactionAdded()).isFalse(),
                    () -> assertThat(result.transaction())
                            .as("nothing is written on a refused turn")
                            .isNull(),
                    () -> assertThat(result.focusField())
                            .as("the cursor lands on the account key, which is the field at fault")
                            .isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.message())
                            .as("a key that survives the bound is looked up and not found; one that does "
                                    + "not is the key-required message")
                            .isEqualTo(keyReachesTheLookup
                                    ? MSG_ACCOUNT_ID_NOT_FOUND : MSG_KEY_FIELD_REQUIRED),
                    () -> assertThat(result.fieldErrors()).singleElement()
                            .extracting(ValidationException.FieldError::field)
                            .isEqualTo("accountId"),
                    () -> assertThat(result.screen().accountId())
                            .as("the screen carries the field at its declared width: the bounded key "
                                    + "when one survived, and a blank-filled field when none did - "
                                    + "never the submitted characters")
                            .isEqualTo(keyReachesTheLookup ? ACCOUNT_ID : BLANK_ACCOUNT_ID_FIELD),
                    () -> assertThat(encodedWidth(result.screen().accountId()))
                            .as("a fixed-width field is returned at its width, neither trimmed nor "
                                    + "overflowing")
                            .isEqualTo(ACCOUNT_ID_WIDTH));

            if (keyReachesTheLookup) {
                verify(cardCrossReferenceRepository)
                        .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
                verifyNoInteractions(transactionRepository, transactionBoundary,
                        dateValidationService);
            } else {
                verifyNoInteractions(transactionRepository, cardCrossReferenceRepository,
                        transactionBoundary, dateValidationService);
            }
        }

        @Test
        @DisplayName("an over-long account key is bounded to eleven characters before it is tested, so "
                + "the excess is lost on the right exactly as the receive paragraph loses it")
        void anOverLongAccountKeyIsBoundedBeforeItIsTested() {
            accountResolves();
            datesAnswer(acceptedBlock());

            // Twenty-two characters: the first eleven are the fixture key and the rest are discarded.
            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withKeys(validTurn(null), ACCOUNT_ID + ACCOUNT_ID, null));

            assertAll(
                    () -> assertThat(result.screen().accountId()).isEqualTo(ACCOUNT_ID),
                    () -> assertThat(result.message()).isEqualTo(MSG_CONFIRM_TO_ADD));
            verify(cardCrossReferenceRepository).findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
        }

        @Test
        @DisplayName("a successful turn reports every component of every nested record, so the whole "
                + "public surface of the outcome is exercised")
        void aSuccessfulTurnReportsEveryComponentOfEveryNestedRecord() {
            accountResolves();
            catalogueTitlesAvailable();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());
            final TransactionAddService.TransactionProjection written = result.transaction();
            final TransactionAddService.ScreenHeader header = result.header();

            assertAll(
                    () -> assertThat(result.transactionAdded()).isTrue(),
                    () -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.TRANSACTION_ADD),
                    () -> assertThat(result.navigationContext()).isNotNull(),
                    () -> assertThat(result.reArmedTransactionId()).isEqualTo(RE_ARMED_TRANSACTION_ID),
                    () -> assertThat(result.message()).isEqualTo(SUCCESS_MESSAGE),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.reEnter()).isTrue(),
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.screen()).isNotNull(),
                    () -> assertThat(header.title01()).isEqualTo(TITLE01),
                    () -> assertThat(header.title02()).isEqualTo(TITLE02),
                    () -> assertThat(header.transactionName()).isEqualTo(RE_ARMED_TRANSACTION_ID),
                    () -> assertThat(header.programName()).isEqualTo(PROGRAM_NAME),
                    () -> assertThat(header.currentDate()).isEqualTo(EXPECTED_HEADER_DATE),
                    () -> assertThat(header.currentTime()).isEqualTo(EXPECTED_HEADER_TIME),
                    () -> assertThat(header.screenTimestamp()).isEqualTo(EXPECTED_ONLINE_TIMESTAMP),
                    () -> assertThat(header.errorMessage()).startsWith(SUCCESS_MESSAGE),
                    () -> assertThat(header.messageHighlighted()).isTrue(),
                    () -> assertThat(written.tranId()).isEqualTo(FIRST_IDENTIFIER),
                    () -> assertThat(written.tranTypeCd()).isEqualTo("01"),
                    () -> assertThat(written.tranCatCd()).isEqualTo("0001"),
                    () -> assertThat(written.tranSource()).isEqualTo(STORED_SOURCE),
                    () -> assertThat(written.tranDesc()).startsWith(DESCRIPTION),
                    () -> assertThat(written.tranAmt()).isEqualTo(new BigDecimal("-100.00")),
                    () -> assertThat(written.merchantId()).isEqualTo(MERCHANT_ID),
                    () -> assertThat(written.merchantName()).startsWith(MERCHANT_NAME),
                    () -> assertThat(written.merchantCity()).startsWith(MERCHANT_CITY),
                    () -> assertThat(written.merchantZip()).startsWith(MERCHANT_ZIP),
                    () -> assertThat(written.tranCardNum()).isEqualTo(CARD_NUMBER),
                    () -> assertThat(written.tranOrigTs()).startsWith(ORIG_DATE),
                    () -> assertThat(written.tranProcTs()).startsWith(PROC_DATE));
        }

        @Test
        @DisplayName("a turn that wrote nothing reports no projection and reads as not added")
        void aTurnThatWroteNothingReportsNoProjection() {
            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(withKeys(confirmedTurn(), null, null));

            assertAll(
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.transactionAdded()).isFalse());
        }

        @Test
        @DisplayName("the projection is a value and never the managed entity, so nothing that leaves the "
                + "service can be mutated into the database")
        void theProjectionIsAValueAndNeverTheManagedEntity() {
            accountResolves();
            datesAnswer(acceptedBlock());
            boundaryRunsInline();
            highestTransactionIs(null);
            insertSucceeds();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(confirmedTurn());

            assertThat(result.transaction()).isNotInstanceOf(Transaction.class);
        }
    }
}
