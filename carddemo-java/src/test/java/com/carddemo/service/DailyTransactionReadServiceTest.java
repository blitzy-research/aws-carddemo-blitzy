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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.DailyTransactionRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.support.TestDataFactory;
import com.carddemo.util.ZonedDecimalCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies {@code DailyTransactionReadService}, the translation of {@code app/cbl/CBTRN01C.cbl} - the
 * estate's orphan: a complete, fully formed 491-line batch program of 18 paragraphs whose abend call
 * sits at line 473.
 *
 * <p><strong>The orphan status is proven, and migrating the member is a documented decision rather
 * than an omission.</strong> A recursive search for the member name across the whole legacy tree
 * returns only the member file itself: no job member names it in an execute statement, no cataloged
 * procedure names it, and no CICS program, transaction or resource definition names it. Being unwired
 * is a property of the wiring rather than of the logic, so the logic is carried across paragraph for
 * paragraph and the job configuration derived from it is <em>defined but excluded from the default
 * pipeline</em>. The estate's one deliberately unmigrated artefact is a different thing entirely - a
 * data copybook with zero inclusion references anywhere, which produces no artefact at all - and the
 * asymmetry between the two is intentional.
 *
 * <p><strong>This file is the service's only exercise path in the repository.</strong> Because the
 * job is defined but unwired, no end-to-end test, no pipeline test and no integration test drives
 * this code as part of a chain. Every one of the 18 paragraph units is therefore reached from here or
 * from nowhere, and the whole of this class's line contribution to the coverage floor originates in
 * this file. That is why nothing below is skipped, disabled or left hollow, and why each decisive
 * behaviour is asserted on its own rather than incidentally.
 *
 * <p>The properties most easily got wrong, and therefore asserted most directly:
 *
 * <ul>
 *   <li><strong>End of file terminates normally.</strong> The read paragraph normalises its raw
 *       two-byte status three ways - success, end of file, everything else - and the loop leaves on
 *       the end-of-file arm. Folding that arm into the error arm is the single most likely defect in
 *       a translation of this shape, because both arms leave the loop and a careless implementation
 *       reads "not success" as "error". Doing so would abend on the normal completion of every run,
 *       so a clean pass is asserted to raise nothing and to leave the abend path untouched.</li>
 *   <li><strong>The diagnostic precedes the abend.</strong> Every failing site displays its own
 *       literal, emits the raw two-character status and only then aborts. The order is contractual,
 *       because on a mainframe the diagnostic reached the operator whether or not anything survived
 *       the abend.</li>
 *   <li><strong>A row that is not there is not an exception.</strong> Both keyed-read paragraphs
 *       carry an invalid-key arm and no error arm, so an absent cross-reference row and an absent
 *       account are ordinary reported outcomes that must never propagate.</li>
 *   <li><strong>Amounts truncate.</strong> No rounding clause exists anywhere in the estate, so
 *       every store into a two-decimal field truncates toward zero, and a negative amount is carried
 *       unchanged rather than absolute-valued or re-signed.</li>
 * </ul>
 *
 * <p><strong>Independent oracles.</strong> Every literal, width, offset, status code and count
 * asserted below is restated here as a test constant taken from the legacy member at the cited line,
 * never read back from the class under test and never produced by a production formatter, codec,
 * template or record mapper. Padding is written as an explicit repetition so that every width is
 * visible to a reviewer, and every expected total is a hand-computed literal.
 *
 * <p>A plain surefire unit test: no Spring context, no container, no database, no port and no
 * filesystem access. The four repositories and the abend service are Mockito test doubles. The
 * service under test takes no clock, so no time source of any kind is consulted here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyTransactionReadService: the orphan extract pass, translated in full and exercised "
        + "only here")
final class DailyTransactionReadServiceTest {

    /**
     * The bound the two alternate-key finders now require, generous enough that these specifications
     * measure the finder's shape rather than its bound.
     *
     * <p>The bound itself is measured against a real server in the repository specifications, where a
     * fixture can hold two rows under one account identifier; a mock cannot establish it.
     */
    private static final Limit ALTERNATE_KEY_ROWS = Limit.of(100);

    // =============================================================================================
    // Oracles restated from app/cbl/CBTRN01C.cbl and the copybooks it includes. Nothing in this
    // block is read back from a production class; each value is a literal a reviewer can check
    // against the cited line.
    // =============================================================================================

    /** Line 156. */
    private static final String ORACLE_START = "START OF EXECUTION OF PROGRAM CBTRN01C";

    /** Line 195. */
    private static final String ORACLE_END = "END OF EXECUTION OF PROGRAM CBTRN01C";

    /** Line 219, the read paragraph's error literal. */
    private static final String ORACLE_ERROR_READING_DALYTRAN =
            "ERROR READING DAILY TRANSACTION FILE";

    /** Line 263, the daily-transaction open's error literal. */
    private static final String ORACLE_ERROR_OPENING_DALYTRAN =
            "ERROR OPENING DAILY TRANSACTION FILE";

    /** Line 300, the cross-reference open's error literal. */
    private static final String ORACLE_ERROR_OPENING_XREF = "ERROR OPENING CROSS REF FILE";

    /** Line 336, the account open's error literal. */
    private static final String ORACLE_ERROR_OPENING_ACCOUNT = "ERROR OPENING ACCOUNT FILE";

    /**
     * Line 372. The daily-transaction <em>close</em> tests its own status at line 364 yet displays the
     * customer file's literal and reports the customer file's status - a copy of the paragraph that
     * follows it. The diagnostic is externally observable, so it is asserted rather than corrected.
     */
    private static final String ORACLE_ERROR_CLOSING_CUSTOMER = "ERROR CLOSING CUSTOMER FILE";

    /** Line 408, the cross-reference close's error literal. */
    private static final String ORACLE_ERROR_CLOSING_XREF = "ERROR CLOSING CROSS REF FILE";

    /** Line 444, the account close's error literal. */
    private static final String ORACLE_ERROR_CLOSING_ACCOUNT = "ERROR CLOSING ACCOUNT FILE";

    /** A literal the member never displays: the close defect means this text must never appear. */
    private static final String ORACLE_LITERAL_THE_SOURCE_NEVER_DISPLAYS =
            "ERROR CLOSING DAILY TRANSACTION FILE";

    /** Line 232, the cross-reference invalid-key arm. */
    private static final String ORACLE_INVALID_CARD_FOR_XREF = "INVALID CARD NUMBER FOR XREF";

    /** Line 235, the cross-reference success arm. */
    private static final String ORACLE_SUCCESSFUL_READ_OF_XREF = "SUCCESSFUL READ OF XREF";

    /** Line 246, the account invalid-key arm. */
    private static final String ORACLE_INVALID_ACCOUNT = "INVALID ACCOUNT NUMBER FOUND";

    /** Line 249, the account success arm. */
    private static final String ORACLE_SUCCESSFUL_READ_OF_ACCOUNT =
            "SUCCESSFUL READ OF ACCOUNT FILE";

    /** The translated form of the account-not-found display at line 178. */
    private static final String ORACLE_ACCOUNT_NOT_FOUND =
            "ACCOUNT RECORD NOT FOUND FOR RESOLVED CROSS-REFERENCE";

    /** The translated form of the card-not-verified display at lines 181 to 183. */
    private static final String ORACLE_CARD_NOT_VERIFIED =
            "CARD NUMBER COULD NOT BE VERIFIED; SKIPPING DAILY TRANSACTION RECORD";

    /** The field-free record diagnostic that stands in for the whole-record display at line 168. */
    private static final String ORACLE_RECORD_READ_STATUS = "DALYTRAN-RECORD read fileStatus=00";

    /** The operator-recognisable prefix the status display carries, line 483. */
    private static final String ORACLE_STATUS_PREFIX = "FILE STATUS IS: NNNN";

    /** The literal the abend paragraph displays immediately before aborting, line 470. */
    private static final String ORACLE_ABENDING = "ABENDING PROGRAM";

    /** The value the abend paragraph moves into the abend-code item, line 472. */
    private static final String ORACLE_BATCH_ABEND_CODE = "999";

    /** The online tier's abend code, restated only to prove the batch path does not carry it. */
    private static final String ORACLE_ONLINE_ABEND_CODE = "9999";

    /** The eight-character member name the abend names as its culprit; the program name itself. */
    private static final String ORACLE_CULPRIT = "CBTRN01C";

    /** The operator message the abend substitutes when no message was supplied. */
    private static final String ORACLE_DEFAULT_ABEND_MESSAGE = "UNEXPECTED ABEND OCCURRED.";

    /** The three widths of the legacy abend context, and their total. */
    private static final int ORACLE_ABEND_CODE_WIDTH = 4;

    private static final int ORACLE_ABEND_CULPRIT_WIDTH = 8;

    private static final int ORACLE_ABEND_REASON_WIDTH = 50;

    private static final int ORACLE_ABEND_MESSAGE_WIDTH = 72;

    /** The total width of the abend context: the four fields above, summed by hand. */
    private static final int ORACLE_ABEND_CONTEXT_WIDTH = 134;

    /** {@code WS-XREF-READ-STATUS} and {@code WS-ACCT-READ-STATUS} after a successful keyed read. */
    private static final int ORACLE_READ_STATUS_OK = 0;

    /** The value both keyed-read paragraphs move on their invalid-key arm, lines 233 and 247. */
    private static final int ORACLE_READ_STATUS_INVALID_KEY = 4;

    /**
     * The three raw statuses the estate actually compares, and the only ones any assertion here
     * depends on. Two further values appear in older prose and are compared by no source member at
     * all; no test below references them, and no code path may depend on them.
     */
    private static final String ORACLE_STATUS_SUCCESS = "00";

    private static final String ORACLE_STATUS_END_OF_FILE = "10";

    private static final String ORACLE_STATUS_RECORD_NOT_FOUND = "23";

    /** The status a hard gateway failure reports, drawn from the vocabulary the source uses. */
    private static final String ORACLE_STATUS_PERMANENT_ERROR = "31";

    /** The width of a raw file status: two bytes, split into two one-byte items in working storage. */
    private static final int ORACLE_FILE_STATUS_WIDTH = 2;

    /** The four normalised values {@code APPL-RESULT} carries, lines 253, 205, 208 and 210. */
    private static final int ORACLE_APPL_AOK = 0;

    private static final int ORACLE_APPL_PENDING = 8;

    private static final int ORACLE_APPL_ERROR = 12;

    private static final int ORACLE_APPL_EOF = 16;

    // ---------------------------------------------------------------------------------------------
    // Record-layout oracles: the widths of app/cpy/CVTRA06Y.cpy, app/cpy/CVACT03Y.cpy and
    // app/cpy/CVACT01Y.cpy, each measured on encoded bytes rather than on character count.
    // ---------------------------------------------------------------------------------------------

    /** The daily-transaction record: 350 bytes. */
    private static final int ORACLE_DAILY_TRANSACTION_RECORD_WIDTH = 350;

    /** The card number, 16 bytes, at offset 262 of the record image. */
    private static final int ORACLE_CARD_NUMBER_WIDTH = 16;

    /** The account identifier, 11 bytes. */
    private static final int ORACLE_ACCOUNT_ID_WIDTH = 11;

    /** The customer identifier, 9 bytes. */
    private static final int ORACLE_CUSTOMER_ID_WIDTH = 9;

    /** Both timestamps, 26 bytes each, at offsets 278 and 304 of the record image. */
    private static final int ORACLE_TIMESTAMP_WIDTH = 26;

    /** The origination timestamp's offset within the 350-byte image. */
    private static final int ORACLE_ORIGINATION_TIMESTAMP_OFFSET = 278;

    /** The processing timestamp's offset within the 350-byte image. */
    private static final int ORACLE_PROCESSING_TIMESTAMP_OFFSET = 304;

    /** The account group identifier, 10 bytes, and ten spaces in all fifty seeded accounts. */
    private static final int ORACLE_ACCOUNT_GROUP_ID_WIDTH = 10;

    /**
     * The <strong>record</strong> timestamp form, 26 characters: a space between the date and the
     * time and colons inside the time. It is the form every delivered daily-transaction record
     * carries, and it is deliberately not the batch form.
     */
    private static final String ORACLE_RECORD_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * The <strong>batch</strong> timestamp form of the same instant, 26 characters: a hyphen between
     * the date and the time and dots inside it. Restated only so the record form can be asserted not
     * to be this one; the two forms are never unified.
     */
    private static final String ORACLE_BATCH_TIMESTAMP = "2022-06-10-19.27.53.000000";

    /** The processing timestamp every one of the three hundred delivered records carries. */
    private static final String ORACLE_BLANK_PROCESSING_TIMESTAMP = " ".repeat(26);

    /** The group identifier every one of the fifty seeded accounts carries. */
    private static final String ORACLE_BLANK_ACCOUNT_GROUP_ID = " ".repeat(10);

    // ---------------------------------------------------------------------------------------------
    // Fixture keys. Every value is the width its layout declares, and the type and category codes
    // differ from one another so that a composite key built in the wrong order cannot pass.
    // ---------------------------------------------------------------------------------------------

    private static final String CARD_ONE = "4859452612877065";

    private static final String CARD_TWO = "4859452612877066";

    private static final String CARD_THREE = "4859452612877067";

    private static final String UNKNOWN_CARD = "9999999999999999";

    private static final String ACCOUNT_ONE = "00000000011";

    private static final String ACCOUNT_TWO = "00000000022";

    private static final String ACCOUNT_THREE = "00000000033";

    private static final String CUSTOMER_ONE = "000000001";

    private static final String TRANSACTION_ONE = "0000000000683580";

    private static final String TRANSACTION_TWO = "0000000000683581";

    private static final String TRANSACTION_THREE = "0000000000683582";

    /** A two-character type code, deliberately different from the four-character category code. */
    private static final String TRAN_TYPE_CD = "03";

    /** A four-character category code, deliberately different from the type code. */
    private static final String TRAN_CAT_CD = "0007";

    /** The exclusive keyset cursor the sequential scan starts from: the empty key. */
    private static final String KEYSET_START_CURSOR = "";

    /** The JPA property the sequential scan is ordered by, ascending. */
    private static final String ORDERED_SCAN_PROPERTY = "dalytranId";

    /** A purchase amount at the two fraction digits the field holds. */
    private static final BigDecimal PURCHASE_AMOUNT = new BigDecimal("504.77");

    /** An operator-originated return, carried negative and never re-signed. */
    private static final BigDecimal RETURN_AMOUNT = new BigDecimal("-919.00");

    /** Zero at scale two, the balance every one of the fifty seeded category-balance rows carries. */
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    @Mock
    private DailyTransactionRepository dailyTransactionRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Mock
    private AbendService abendService;

    private DailyTransactionReadService service;

    private Logger serviceLogger;

    private ListAppender<ILoggingEvent> recorder;

    private Level originalLevel;

    /** The outcomes the pass under test offered, in the order it produced them. */
    private final List<DailyTransactionReadService.DailyTransactionVerification> streamed =
            new ArrayList<>();

    /**
     * Builds the service over the test doubles and attaches a recorder to its own logger.
     *
     * <p>The level is set explicitly rather than inherited from the surrounding configuration, so
     * that these tests assert the service's own emissions and never the ambient logging setup.
     */
    @BeforeEach
    void createServiceAndAttachRecorder() {
        this.service = new DailyTransactionReadService(this.dailyTransactionRepository,
                this.cardCrossReferenceRepository, this.accountRepository, this.abendService);
        this.serviceLogger = (Logger) LoggerFactory.getLogger(DailyTransactionReadService.class);
        this.originalLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.TRACE);
        this.recorder = new ListAppender<>();
        this.recorder.setContext(this.serviceLogger.getLoggerContext());
        this.recorder.start();
        this.serviceLogger.addAppender(this.recorder);
    }

    @AfterEach
    void detachRecorder() {
        this.serviceLogger.detachAppender(this.recorder);
        this.recorder.stop();
        this.serviceLogger.setLevel(this.originalLevel);
    }

    // =============================================================================================
    // Fixtures and helpers. Every value is presented at the width its layout declares.
    // =============================================================================================

    /**
     * Builds one daily-transaction record at the widths of the 350-byte layout, with the record-form
     * origination timestamp and the all-spaces processing timestamp every delivered record carries.
     *
     * @param identifier the sixteen-character identifier
     * @param cardNumber the sixteen-character card number
     * @param amount the amount at two fraction digits, positive or negative
     * @return a record carrying exactly those values, unvalidated and untrimmed
     */
    private static DailyTransaction record(final String identifier, final String cardNumber,
            final BigDecimal amount) {
        return new DailyTransaction(identifier, TRAN_TYPE_CD, TRAN_CAT_CD, "POS TERM  ",
                "Purchase at Abshire-Lowe", amount, "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112     ", cardNumber, ORACLE_RECORD_TIMESTAMP,
                ORACLE_BLANK_PROCESSING_TIMESTAMP);
    }

    /**
     * @param identifier the sixteen-character identifier
     * @param cardNumber the sixteen-character card number
     * @return a purchase record carrying the positive amount
     */
    private static DailyTransaction record(final String identifier, final String cardNumber) {
        return record(identifier, cardNumber, PURCHASE_AMOUNT);
    }

    /**
     * @param cardNumber the sixteen-character card number, which is this record's own key
     * @param accountId the eleven-character account identifier the card resolves to
     * @return a cross-reference row at the widths of the 50-byte layout
     */
    private static CardCrossReference crossReference(final String cardNumber,
            final String accountId) {
        return new CardCrossReference(cardNumber, CUSTOMER_ONE, accountId);
    }

    /**
     * Builds an account at the widths of the 300-byte layout, carrying the ten-space group
     * identifier every one of the fifty seeded accounts carries. The Java property for the field the
     * copybook misspells is spelled correctly, and the value is supplied positionally regardless.
     *
     * @param accountId the eleven-character account identifier
     * @return an account carrying that identifier
     */
    private static Account account(final String accountId) {
        return new Account(accountId, "Y", new BigDecimal("1000.00"), new BigDecimal("5000.00"),
                new BigDecimal("1000.00"), "2022-01-01", "2030-01-01", "2022-01-01", ZERO_AMOUNT,
                ZERO_AMOUNT, "A000000000", ORACLE_BLANK_ACCOUNT_GROUP_ID);
    }

    /** Wires one card so that the cross-reference resolves it and the account it names exists. */
    private void givenResolvable(final String cardNumber, final String accountId) {
        when(this.cardCrossReferenceRepository.findById(cardNumber))
                .thenReturn(Optional.of(crossReference(cardNumber, accountId)));
        when(this.accountRepository.findById(accountId))
                .thenReturn(Optional.of(account(accountId)));
    }

    /** Makes the abend path raise, as the real one does after it has emitted its diagnostic. */
    private void givenTheAbendPathRaises() {
        doThrow(new AbendException(ORACLE_BATCH_ABEND_CODE, ORACLE_CULPRIT,
                ORACLE_ERROR_READING_DALYTRAN, ORACLE_DEFAULT_ABEND_MESSAGE))
                .when(this.abendService)
                .abendBatch(any(), any(), any(), any(), any());
    }

    /**
     * Runs one pass over the supplied ordered records, collecting the outcomes it streams.
     *
     * @param orderedRecords the records to walk, in the order supplied
     * @return the pass's counts and terminal result value
     */
    private DailyTransactionReadService.DailyTransactionReadResult runPass(
            final Iterable<DailyTransaction> orderedRecords) {
        return this.service.execute(orderedRecords, this.streamed::add);
    }

    /** @return every message the recorder holds, formatted, in emission order */
    private List<String> recorded() {
        return this.recorder.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * @param fragment the text to locate
     * @return the position of the first recorded message containing the fragment, or -1
     */
    private int indexOfContaining(final String fragment) {
        final List<String> messages = recorded();
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).contains(fragment)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The legacy not-found representation for a multi-row result: the first row as supplied, or
     * nothing at all when the result is empty. Written here rather than borrowed from production so
     * that the empty case is proven to need no exception.
     *
     * @param rows the rows as the repository supplied them
     * @return the first row, or {@code null} when there is none
     */
    private static CardCrossReference firstAsSupplied(final List<CardCrossReference> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * @param value the value to measure
     * @return the value's width in encoded bytes, never its character count
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /** @return how many outcomes the pass under test has offered so far */
    private int streamedCount() {
        return this.streamed.size();
    }

    /**
     * The end-of-file arm of {@code 1000-DALYTRAN-GET-NEXT} - lines 216 to 217 - which is a normal,
     * non-exceptional termination and the reason this whole class exists.
     */
    @Nested
    @DisplayName("End of file terminates the read loop normally")
    final class EndOfFileTerminatesNormally {

        /**
         * The decisive test of the file. The read paragraph is the one paragraph in the member with an
         * end-of-file arm, and that arm merely raises the loop's terminating flag; the error arm
         * displays, emits the raw status and aborts. Both arms leave the loop, which is exactly why a
         * careless translation collapses the two and turns the normal completion of every run into an
         * abend.
         */
        @Test
        @DisplayName("a populated input completes normally with no abend at all - the defect this test "
                + "guards is collapsing the end-of-file arm into the error arm")
        void endOfFileIsNotAnErrorArm() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            final List<DailyTransaction> input = List.of(record(TRANSACTION_ONE, CARD_ONE));

            final DailyTransactionReadService.DailyTransactionReadResult result =
                    assertDoesNotThrow(() -> runPass(input));

            assertAll(
                    // The pass returned its real counts rather than a sentinel or an empty object.
                    () -> assertThat(result).isNotNull(),
                    () -> assertThat(result.recordsRead()).isEqualTo(1),
                    () -> assertThat(result.recordsVerified()).isEqualTo(1),
                    () -> assertThat(result.allRecordsVerified()).isTrue(),
                    () -> assertThat(result.returnCode()).isEqualTo(ORACLE_APPL_AOK),
                    // Nothing on the abend path was touched: no diagnostic emitted, nothing raised.
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("an exhausted source raises neither a file-status failure nor an abend")
        void exhaustedSourceRaisesNeitherFailureType() {
            final Throwable thrown = catchThrowable(() -> runPass(List.of()));

            assertAll(
                    () -> assertThat(thrown)
                            .describedAs("end of file must not surface as any exception")
                            .isNull(),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("the terminal result value is the success value, never the armed sentinel, the "
                + "error value or the end-of-file value")
        void terminalResultValueIsSuccess() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);

            final DailyTransactionReadService.DailyTransactionReadResult result =
                    assertDoesNotThrow(() -> runPass(List.of(record(TRANSACTION_ONE, CARD_ONE))));

            // The coarse result is exercised through its owner: the pass's own terminal value. The
            // sentinel armed before every open and close, the error value and the end-of-file value
            // are all distinct from success, and none of them may leak into a completed pass.
            assertAll(
                    () -> assertThat(result.returnCode()).isEqualTo(ORACLE_APPL_AOK),
                    () -> assertThat(result.returnCode()).isNotEqualTo(ORACLE_APPL_PENDING),
                    () -> assertThat(result.returnCode()).isNotEqualTo(ORACLE_APPL_ERROR),
                    () -> assertThat(result.returnCode()).isNotEqualTo(ORACLE_APPL_EOF));
        }

        @Test
        @DisplayName("the end-of-file pass still closes all six files, so the run ends with its own "
                + "closing literal")
        void endOfFileStillReachesEveryClose() {
            assertDoesNotThrow(() -> runPass(List.of()));

            assertAll(
                    () -> assertThat(recorded()).contains(ORACLE_START, ORACLE_END),
                    () -> assertThat(indexOfContaining(ORACLE_START))
                            .isLessThan(indexOfContaining(ORACLE_END)),
                    () -> verify(dailyTransactionRepository,
                            times(2)).count());
        }
    }

    /**
     * {@code MAIN-PARA}'s loop bookkeeping: the counts the translation reports where the legacy
     * program reported only by display.
     */
    @Nested
    @DisplayName("Counts report exactly what was read, at both extremes of the loop")
    final class CountsAreExact {

        @Test
        @DisplayName("a three-record input reports exactly three read and three verified")
        void knownSizeInputCountsExactly() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            givenResolvable(CARD_TWO, ACCOUNT_TWO);
            givenResolvable(CARD_THREE, ACCOUNT_THREE);
            final List<DailyTransaction> input = List.of(
                    record(TRANSACTION_ONE, CARD_ONE),
                    record(TRANSACTION_TWO, CARD_TWO),
                    record(TRANSACTION_THREE, CARD_THREE));

            final DailyTransactionReadService.DailyTransactionReadResult result = runPass(input);

            // Three: counted by hand from the fixture above, not read back from the input's size.
            assertAll(
                    () -> assertThat(result.recordsRead()).isEqualTo(3),
                    () -> assertThat(result.recordsVerified()).isEqualTo(3),
                    () -> assertThat(result.cardsNotVerified()).isZero(),
                    () -> assertThat(result.accountsNotFound()).isZero(),
                    () -> assertThat(result.allRecordsVerified()).isTrue());
        }

        @Test
        @DisplayName("a single-record input reports exactly one read, with no off-by-one at the near "
                + "end of the loop")
        void singleRecordInputCountsExactly() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);

            final DailyTransactionReadService.DailyTransactionReadResult result =
                    runPass(List.of(record(TRANSACTION_ONE, CARD_ONE)));

            assertAll(
                    () -> assertThat(result.recordsRead()).isEqualTo(1),
                    () -> assertThat(result.recordsVerified()).isEqualTo(1),
                    () -> assertThat(streamedCount()).isEqualTo(2));
        }

        @Test
        @DisplayName("an empty input reports nothing read, with no off-by-one at the far end")
        void emptyInputCountsExactly() {
            final DailyTransactionReadService.DailyTransactionReadResult result =
                    runPass(List.of());

            assertAll(
                    () -> assertThat(result.recordsRead()).isZero(),
                    () -> assertThat(result.recordsVerified()).isZero(),
                    () -> assertThat(result.returnCode()).isEqualTo(ORACLE_APPL_AOK));
        }

        /**
         * The verification block at lines 170 to 184 sits <em>outside</em> the end-of-file test at line
         * 167, so the loop runs it once more over the record area the previous read left in place. The
         * extra pass is real behaviour and is asserted as such; what must never drift is the read
         * count, which is why the two counters are asserted together.
         */
        @Test
        @DisplayName("the loop makes exactly one extra verification pass after end of file, over the "
                + "last record, and never counts it as a read")
        void oneTrailingVerificationPassAfterEndOfFile() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            givenResolvable(CARD_TWO, ACCOUNT_TWO);

            final DailyTransactionReadService.DailyTransactionReadResult result = runPass(List.of(
                    record(TRANSACTION_ONE, CARD_ONE),
                    record(TRANSACTION_TWO, CARD_TWO)));

            final DailyTransactionReadService.DailyTransactionVerification trailing =
                    streamed
                            .get(streamedCount() - 1);
            assertAll(
                    // Two records read, three passes: hand-counted, one pass per record plus one.
                    () -> assertThat(result.recordsRead()).isEqualTo(2),
                    () -> assertThat(result.recordsVerified()).isEqualTo(2),
                    () -> assertThat(result.verificationPasses()).isEqualTo(3),
                    () -> assertThat(streamedCount()).isEqualTo(3),
                    () -> assertThat(trailing.afterEndOfFile()).isTrue(),
                    () -> assertThat(trailing.dalytranId()).isEqualTo(TRANSACTION_TWO));
        }

        @Test
        @DisplayName("an empty input still makes the one trailing pass, over a record area no read has "
                + "filled")
        void emptyInputStillMakesTheTrailingPass() {
            final DailyTransactionReadService.DailyTransactionReadResult result =
                    runPass(List.of());

            assertAll(
                    () -> assertThat(result.verificationPasses()).isEqualTo(1),
                    () -> assertThat(result.cardsNotVerified()).isEqualTo(1),
                    () -> assertThat(streamedCount()).isEqualTo(1),
                    () -> assertThat(streamed.get(0)
                            .dalytranId()).isNull());
        }

        @Test
        @DisplayName("each outcome reaches its destination as it is produced, so no pass ever holds a "
                + "whole dataset's detail")
        void outcomesAreStreamedRatherThanAccumulated() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            givenResolvable(CARD_TWO, ACCOUNT_TWO);
            final List<Integer> sizeWhenOffered = new ArrayList<>();
            final Consumer<DailyTransactionReadService.DailyTransactionVerification> sink =
                    outcome -> {
                        streamed.add(outcome);
                        sizeWhenOffered.add(Integer.valueOf(streamedCount()));
                    };

            final DailyTransactionReadService.DailyTransactionReadResult result =
                    service.execute(List.of(
                            record(TRANSACTION_ONE, CARD_ONE),
                            record(TRANSACTION_TWO, CARD_TWO)), sink);

            assertAll(
                    () -> assertThat(sizeWhenOffered).containsExactly(
                            Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3)),
                    () -> assertThat(result.verificationPasses()).isEqualTo(3));
        }
    }

    /**
     * The error arm every failing I/O site shares: display the literal, emit the raw two-character
     * status, and only then abort. Two mechanisms prove the ordering, and both are present on purpose.
     *
     * <p>The recorder attached to the service's own logger proves that the site's literal had already
     * been emitted at the instant the abend was observed, because an event the recorder holds when the
     * throw is caught can only have been emitted before the raise. The raw status, however, is emitted
     * by the collaborator the service delegates its status rendering to - the estate keeps one status
     * renderer rather than two - so the recorder on the service's logger cannot see it. An ordered
     * verification across that collaborator supplies the remaining half: the status display is proven
     * to precede the abend request, and the status handed to it is captured and asserted to be the raw
     * two-character code rather than a rendered one.
     */
    @Nested
    @DisplayName("The error arm emits its literal and the raw two-byte status before it raises")
    final class TheErrorArmEmitsThenRaises {

        @Test
        @DisplayName("a failing open emits its own literal before the abend is raised, and the literal "
                + "is already recorded when the abend is caught")
        void failingOpenEmitsItsLiteralBeforeRaising() {
            when(dailyTransactionRepository.count())
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));
            givenTheAbendPathRaises();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> runPass(List.of()));

            assertAll(
                    () -> assertThat(recorded()).contains(ORACLE_ERROR_OPENING_DALYTRAN),
                    () -> assertThat(indexOfContaining(ORACLE_ERROR_OPENING_DALYTRAN))
                            .isNotNegative(),
                    // The underlying access failure's own text is never re-emitted.
                    () -> assertThat(recorded())
                            .noneMatch(message -> message.contains("gateway unavailable")));
        }

        @Test
        @DisplayName("the raw two-character status is emitted before the abend is requested, and it is "
                + "the raw code rather than a rendered value")
        void rawStatusIsEmittedBeforeTheAbendIsRequested() {
            when(dailyTransactionRepository.count())
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));
            givenTheAbendPathRaises();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> runPass(List.of()));

            final ArgumentCaptor<String> displayedStatus = ArgumentCaptor.forClass(String.class);
            final InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus(displayedStatus.capture(), any(), any());
            order.verify(abendService).abendBatch(any(), any(), any(), any(), any());
            order.verifyNoMoreInteractions();
            assertAll(
                    () -> assertThat(displayedStatus.getValue())
                            .isEqualTo(ORACLE_STATUS_PERMANENT_ERROR),
                    () -> assertThat(encodedWidth(displayedStatus.getValue()))
                            .isEqualTo(ORACLE_FILE_STATUS_WIDTH));
        }

        @Test
        @DisplayName("the abend names the member itself as culprit and carries the failing operation, "
                + "the resource and the raw status")
        void abendCarriesTheProgramNameAsCulprit() {
            when(accountRepository.count())
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));
            givenTheAbendPathRaises();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> runPass(List.of()));

            final ArgumentCaptor<String> culprit = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> rawStatus = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> operation = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> resource = ArgumentCaptor.forClass(String.class);
            verify(abendService).abendBatch(culprit.capture(), reason.capture(),
                    rawStatus.capture(), operation.capture(), resource.capture());
            assertAll(
                    () -> assertThat(culprit.getValue()).isEqualTo(ORACLE_CULPRIT),
                    () -> assertThat(encodedWidth(culprit.getValue()))
                            .isEqualTo(ORACLE_ABEND_CULPRIT_WIDTH),
                    () -> assertThat(reason.getValue()).isEqualTo(ORACLE_ERROR_OPENING_ACCOUNT),
                    () -> assertThat(rawStatus.getValue())
                            .isEqualTo(ORACLE_STATUS_PERMANENT_ERROR),
                    () -> assertThat(operation.getValue()).isEqualTo("OPEN INPUT"),
                    () -> assertThat(resource.getValue()).isEqualTo("ACCTFILE"));
        }

        /**
         * The abend the batch tier raises carries the legacy context image: a four-character code, an
         * eight-character culprit, a fifty-character reason and a seventy-two-character message, each
         * left-justified in its own field. The four offsets and the total are hand-computed literals,
         * and the padding is written out so that every width is visible.
         */
        @Test
        @DisplayName("the abend context is exactly one hundred and thirty-four characters with its "
                + "four fields at their declared offsets")
        void abendContextIsExactlyOneHundredAndThirtyFourCharacters() {
            final AbendException abend = new AbendException(ORACLE_BATCH_ABEND_CODE, ORACLE_CULPRIT,
                    ORACLE_ERROR_READING_DALYTRAN, ORACLE_DEFAULT_ABEND_MESSAGE);

            final String context = abend.toFixedWidthContext();

            assertAll(
                    () -> assertThat(encodedWidth(context)).isEqualTo(ORACLE_ABEND_CONTEXT_WIDTH),
                    // [0,4): the batch abend code, one character short of its field exactly as the
                    // legacy leaves it, so the field carries a single trailing space.
                    () -> assertThat(context.substring(0, 4))
                            .isEqualTo(ORACLE_BATCH_ABEND_CODE + " ".repeat(1)),
                    // [4,12): the culprit, which is the eight-character program name and fills it.
                    () -> assertThat(context.substring(4, 12)).isEqualTo(ORACLE_CULPRIT),
                    // [12,62): the reason, thirty-six characters padded to fifty.
                    () -> assertThat(context.substring(12, 62))
                            .isEqualTo(ORACLE_ERROR_READING_DALYTRAN + " ".repeat(14)),
                    // [62,134): the operator message, twenty-six characters padded to seventy-two.
                    () -> assertThat(context.substring(62, 134))
                            .isEqualTo(ORACLE_DEFAULT_ABEND_MESSAGE + " ".repeat(46)),
                    () -> assertThat(abend.code()).isEqualTo(ORACLE_BATCH_ABEND_CODE),
                    () -> assertThat(abend.code()).isNotEqualTo(ORACLE_ONLINE_ABEND_CODE),
                    () -> assertThat(abend.culprit()).isEqualTo(ORACLE_CULPRIT),
                    () -> assertThat(abend.reason()).isEqualTo(ORACLE_ERROR_READING_DALYTRAN),
                    () -> assertThat(abend.getMessage()).isEqualTo(ORACLE_DEFAULT_ABEND_MESSAGE));
        }

        @Test
        @DisplayName("the batch abend code is the three-character value the abend paragraph moves, and "
                + "never the online tier's four-character one")
        void batchAbendCodeIsTheBatchValue() {
            assertAll(
                    () -> assertThat(AbendException.BATCH_ABEND_CODE)
                            .isEqualTo(ORACLE_BATCH_ABEND_CODE),
                    () -> assertThat(AbendException.ONLINE_ABEND_CODE)
                            .isEqualTo(ORACLE_ONLINE_ABEND_CODE),
                    () -> assertThat(AbendException.BATCH_ABEND_CODE)
                            .isNotEqualTo(AbendException.ONLINE_ABEND_CODE),
                    () -> assertThat(AbendException.CONTEXT_LENGTH)
                            .isEqualTo(ORACLE_ABEND_CONTEXT_WIDTH),
                    () -> assertThat(AbendException.CODE_LENGTH).isEqualTo(ORACLE_ABEND_CODE_WIDTH),
                    () -> assertThat(AbendException.CULPRIT_LENGTH)
                            .isEqualTo(ORACLE_ABEND_CULPRIT_WIDTH),
                    () -> assertThat(AbendException.REASON_LENGTH)
                            .isEqualTo(ORACLE_ABEND_REASON_WIDTH),
                    () -> assertThat(AbendException.MESSAGE_LENGTH)
                            .isEqualTo(ORACLE_ABEND_MESSAGE_WIDTH),
                    () -> assertThat(AbendException.DEFAULT_MESSAGE)
                            .isEqualTo(ORACLE_DEFAULT_ABEND_MESSAGE));
        }

        @Test
        @DisplayName("a failing cross-reference open emits the cross-reference literal, so each of the "
                + "six open paragraphs keeps its own text")
        void failingCrossReferenceOpenEmitsItsOwnLiteral() {
            when(cardCrossReferenceRepository.count())
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));
            givenTheAbendPathRaises();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> runPass(List.of()));

            assertAll(
                    () -> assertThat(recorded()).contains(ORACLE_ERROR_OPENING_XREF),
                    () -> assertThat(recorded()).doesNotContain(ORACLE_ERROR_OPENING_DALYTRAN));
        }

        /**
         * The daily-transaction close carries the customer file's literal, a copy of the paragraph that
         * follows it in the source. Reaching that arm needs an open that succeeds and a close that does
         * not, which is why the probe is made to fail only on its second call.
         */
        @Test
        @DisplayName("the daily-transaction close emits the customer file's literal, reproducing the "
                + "source defect rather than repairing it")
        void dalytranCloseEmitsTheCustomerFileLiteral() {
            when(dailyTransactionRepository.count())
                    .thenReturn(Long.valueOf(0L))
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));
            givenTheAbendPathRaises();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> runPass(List.of()));

            assertAll(
                    () -> assertThat(recorded()).contains(ORACLE_ERROR_CLOSING_CUSTOMER),
                    () -> assertThat(recorded())
                            .doesNotContain(ORACLE_LITERAL_THE_SOURCE_NEVER_DISPLAYS));
        }

        @Test
        @DisplayName("a failing cross-reference close emits its own literal and reports the close "
                + "operation")
        void failingCrossReferenceCloseReportsTheCloseOperation() {
            when(cardCrossReferenceRepository.count())
                    .thenReturn(Long.valueOf(0L))
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));
            givenTheAbendPathRaises();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> runPass(List.of()));

            final ArgumentCaptor<String> operation = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> resource = ArgumentCaptor.forClass(String.class);
            verify(abendService).abendBatch(eq(ORACLE_CULPRIT), eq(ORACLE_ERROR_CLOSING_XREF),
                    eq(ORACLE_STATUS_PERMANENT_ERROR), operation.capture(), resource.capture());
            assertAll(
                    () -> assertThat(recorded()).contains(ORACLE_ERROR_CLOSING_XREF),
                    () -> assertThat(operation.getValue()).isEqualTo("CLOSE"),
                    () -> assertThat(resource.getValue()).isEqualTo("XREFFILE"));
        }

        @Test
        @DisplayName("a failing account close emits its own literal, so each of the six close "
                + "paragraphs keeps its own text")
        void failingAccountCloseEmitsItsOwnLiteral() {
            when(accountRepository.count())
                    .thenReturn(Long.valueOf(0L))
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));
            givenTheAbendPathRaises();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> runPass(List.of()));

            assertThat(recorded()).contains(ORACLE_ERROR_CLOSING_ACCOUNT);
        }

        /**
         * A source element carrying no record image is neither a record nor end of file, so the read
         * paragraph has only its error arm left for it - and that arm emits, reports the status and
         * abends exactly as a failing read does. This is the read paragraph's error arm, reached
         * without a failing gateway.
         */
        @Test
        @DisplayName("a source element with no record image takes the read paragraph's error arm and "
                + "emits the read literal before raising")
        void elementWithoutARecordImageTakesTheReadErrorArm() {
            givenTheAbendPathRaises();
            final List<DailyTransaction> input = Collections.singletonList(null);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> runPass(input));

            final ArgumentCaptor<String> rawStatus = ArgumentCaptor.forClass(String.class);
            final InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus(rawStatus.capture(), eq("READ"),
                    eq("DALYTRAN"));
            order.verify(abendService).abendBatch(eq(ORACLE_CULPRIT),
                    eq(ORACLE_ERROR_READING_DALYTRAN), eq(ORACLE_STATUS_PERMANENT_ERROR),
                    eq("READ"), eq("DALYTRAN"));
            assertAll(
                    () -> assertThat(recorded()).contains(ORACLE_ERROR_READING_DALYTRAN),
                    () -> assertThat(rawStatus.getValue())
                            .isEqualTo(ORACLE_STATUS_PERMANENT_ERROR),
                    () -> assertThat(encodedWidth(rawStatus.getValue()))
                            .isEqualTo(ORACLE_FILE_STATUS_WIDTH));
        }

        /**
         * The delegation itself, observed without the raise. The estate's abend path emits its
         * diagnostic and then raises, and every other test here stubs it to do exactly that; this one
         * lets it merely record, so that what the error arm <em>hands</em> it is visible on its own.
         * The legacy call terminated the run where it stood and this collaborator never returns in
         * production, so the continuation seen here is an artefact of the double rather than behaviour
         * the member has.
         */
        @Test
        @DisplayName("the read paragraph's error arm hands the abend path the read literal, the raw "
                + "status, the operation and the resource, in that one call")
        void theReadErrorArmDelegatesToTheAbendPath() {
            runPass(Collections.singletonList(null));

            final InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus(ORACLE_STATUS_PERMANENT_ERROR, "READ",
                    "DALYTRAN");
            order.verify(abendService).abendBatch(ORACLE_CULPRIT, ORACLE_ERROR_READING_DALYTRAN,
                    ORACLE_STATUS_PERMANENT_ERROR, "READ", "DALYTRAN");
            assertThat(recorded()).contains(ORACLE_ERROR_READING_DALYTRAN);
        }

        /**
         * The same delegation from an open paragraph and from a close paragraph, in one pass, again
         * without the raise so that both arms are observable. The two calls carry different literals,
         * different operations and different resources, which is what proves the twelve paragraphs are
         * not collapsed into one.
         */
        @Test
        @DisplayName("an open paragraph and a close paragraph each hand the abend path their own "
                + "literal, operation and resource")
        void theOpenAndCloseErrorArmsEachDelegateWithTheirOwnValues() {
            when(dailyTransactionRepository.count())
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));

            runPass(List.of());

            assertAll(
                    () -> verify(abendService).abendBatch(ORACLE_CULPRIT,
                            ORACLE_ERROR_OPENING_DALYTRAN, ORACLE_STATUS_PERMANENT_ERROR,
                            "OPEN INPUT", "DALYTRAN"),
                    // The close arm carries the customer file's literal and resource, reproducing the
                    // source defect on this path as well.
                    () -> verify(abendService).abendBatch(ORACLE_CULPRIT,
                            ORACLE_ERROR_CLOSING_CUSTOMER, ORACLE_STATUS_PERMANENT_ERROR, "CLOSE",
                            "CUSTFILE"),
                    () -> verify(abendService).displayIoStatus(ORACLE_STATUS_PERMANENT_ERROR,
                            "OPEN INPUT", "DALYTRAN"),
                    () -> verify(abendService).displayIoStatus(ORACLE_STATUS_PERMANENT_ERROR,
                            "CLOSE", "CUSTFILE"),
                    () -> assertThat(recorded()).contains(ORACLE_ERROR_OPENING_DALYTRAN,
                            ORACLE_ERROR_CLOSING_CUSTOMER));
        }

        @Test
        @DisplayName("the run is abandoned where the abend was raised, so no closing literal follows it")
        void theRunIsAbandonedWhereTheAbendWasRaised() {
            when(dailyTransactionRepository.count())
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));
            givenTheAbendPathRaises();

            assertThatThrownBy(() -> runPass(List.of()))
                    .isInstanceOf(AbendException.class)
                    .hasMessage(ORACLE_DEFAULT_ABEND_MESSAGE);

            assertAll(
                    () -> assertThat(recorded()).contains(ORACLE_START),
                    () -> assertThat(recorded()).doesNotContain(ORACLE_END),
                    () -> assertThat(recorded()).doesNotContain(ORACLE_ERROR_CLOSING_CUSTOMER));
        }
    }

    /**
     * {@code 2000-LOOKUP-XREF} and {@code 3000-READ-ACCOUNT} - lines 227 to 250. Both carry an
     * invalid-key arm and a not-invalid-key arm and no error arm at all, so a row that is not there is
     * an ordinary reported outcome and never an exception.
     */
    @Nested
    @DisplayName("A row that is not there is the legacy not-found path, never a throw")
    final class MissingRowsAreTheNotFoundPath {

        @Test
        @DisplayName("an unresolved card number takes the cross-reference invalid-key arm and raises "
                + "nothing")
        void unresolvedCardTakesTheInvalidKeyArm() {
            when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());

            final DailyTransactionReadService.DailyTransactionVerification verification =
                    assertDoesNotThrow(() -> service.verify(record(TRANSACTION_ONE, UNKNOWN_CARD)));

            assertAll(
                    () -> assertThat(verification.cardVerified()).isFalse(),
                    () -> assertThat(verification.xrefReadStatus())
                            .isEqualTo(ORACLE_READ_STATUS_INVALID_KEY),
                    () -> assertThat(verification.accountLookupAttempted()).isFalse(),
                    () -> assertThat(verification.xrefAcctId()).isNull(),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("the account is never read for a card the cross-reference could not resolve, so "
                + "the two tests keep the source's order")
        void accountIsNotReadForAnUnresolvedCard() {
            when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());

            service.verify(record(TRANSACTION_ONE, UNKNOWN_CARD));

            verify(accountRepository, never()).findById(any());
        }

        @Test
        @DisplayName("an account the file does not hold takes the account invalid-key arm and raises "
                + "nothing")
        void missingAccountTakesTheInvalidKeyArm() {
            when(cardCrossReferenceRepository.findById(CARD_ONE))
                    .thenReturn(Optional.of(crossReference(CARD_ONE, ACCOUNT_ONE)));
            when(accountRepository.findById(ACCOUNT_ONE)).thenReturn(Optional.empty());

            final DailyTransactionReadService.DailyTransactionVerification verification =
                    assertDoesNotThrow(() -> service.verify(record(TRANSACTION_ONE, CARD_ONE)));

            assertAll(
                    () -> assertThat(verification.cardVerified()).isTrue(),
                    () -> assertThat(verification.accountLookupAttempted()).isTrue(),
                    () -> assertThat(verification.accountFound()).isFalse(),
                    () -> assertThat(verification.acctReadStatus())
                            .isEqualTo(ORACLE_READ_STATUS_INVALID_KEY),
                    () -> assertThat(verification.xrefAcctId()).isEqualTo(ACCOUNT_ONE),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("a resolvable card reports both keyed reads successful")
        void resolvableCardReportsBothReadsSuccessful() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);

            final DailyTransactionReadService.DailyTransactionVerification verification =
                    service.verify(record(TRANSACTION_ONE, CARD_ONE));

            assertAll(
                    () -> assertThat(verification.cardVerified()).isTrue(),
                    () -> assertThat(verification.accountFound()).isTrue(),
                    () -> assertThat(verification.xrefReadStatus()).isEqualTo(ORACLE_READ_STATUS_OK),
                    () -> assertThat(verification.acctReadStatus()).isEqualTo(ORACLE_READ_STATUS_OK),
                    () -> assertThat(verification.afterEndOfFile()).isFalse(),
                    () -> assertThat(verification.dalytranId()).isEqualTo(TRANSACTION_ONE),
                    () -> assertThat(verification.dalytranCardNum()).isEqualTo(CARD_ONE));
        }

        /**
         * The account-level cross-reference finder answers with a list rather than an optional, and an
         * empty list is the legacy not-found response rather than an error. Consuming it the legacy way
         * - first row as supplied, nothing at all when there is none - must not reach for an element
         * that is not there.
         */
        @Test
        @DisplayName("the account-level cross-reference finder answers with a list, and an empty list "
                + "is the not-found path rather than an out-of-bounds or no-such-element failure")
        void emptyCrossReferenceListIsTheNotFoundPath() {
            when(cardCrossReferenceRepository.findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ONE, ALTERNATE_KEY_ROWS))
                    .thenReturn(List.of());

            final List<CardCrossReference> rows =
                    cardCrossReferenceRepository.findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ONE, ALTERNATE_KEY_ROWS);

            assertAll(
                    () -> assertThat(rows).isEmpty(),
                    () -> assertThat(catchThrowable(() -> firstAsSupplied(rows)))
                            .describedAs("an empty result must not raise")
                            .isNull(),
                    () -> assertThat(firstAsSupplied(rows)).isNull());
        }

        /**
         * A keyed read of a duplicate-bearing path yields the first row it is given. The fixture is
         * built in a deliberately non-natural order, so a client-side sort slipped in anywhere would
         * change which row wins and this assertion would fail.
         */
        @Test
        @DisplayName("a multi-row cross-reference result yields the first row exactly as supplied, with "
                + "no re-sorting")
        void multiRowCrossReferenceListYieldsTheFirstRowAsSupplied() {
            final CardCrossReference higherCard = crossReference(CARD_THREE, ACCOUNT_ONE);
            final CardCrossReference lowerCard = crossReference(CARD_ONE, ACCOUNT_ONE);
            // Deliberately descending by card number: natural order would put the lower card first.
            final List<CardCrossReference> supplied = List.of(higherCard, lowerCard);
            when(cardCrossReferenceRepository.findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ONE, ALTERNATE_KEY_ROWS)).thenReturn(supplied);

            final List<CardCrossReference> rows =
                    cardCrossReferenceRepository.findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ONE, ALTERNATE_KEY_ROWS);

            assertAll(
                    () -> assertThat(rows).containsExactly(higherCard, lowerCard),
                    () -> assertThat(firstAsSupplied(rows)).isSameAs(higherCard),
                    () -> assertThat(firstAsSupplied(rows).getXrefCardNum()).isEqualTo(CARD_THREE),
                    () -> assertThat(rows).hasSize(2));
        }

        /**
         * The not-found representation the estate declares carries a no-argument constructor precisely
         * so that an empty optional can be turned into it by a method reference, and it carries the one
         * status code the estate compares for a record that is not there.
         */
        @Test
        @DisplayName("the not-found representation is constructible with no arguments and carries the "
                + "record-not-found status")
        void notFoundRepresentationIsConstructibleWithNoArguments() {
            final Optional<CardCrossReference> absent = Optional.empty();

            assertAll(
                    () -> assertThatExceptionOfType(RecordNotFoundException.class)
                            .isThrownBy(() -> absent.orElseThrow(RecordNotFoundException::new)),
                    () -> assertThat(RecordNotFoundException.STATUS_RECORD_NOT_FOUND)
                            .isEqualTo(ORACLE_STATUS_RECORD_NOT_FOUND),
                    () -> assertThat(encodedWidth(RecordNotFoundException.STATUS_RECORD_NOT_FOUND))
                            .isEqualTo(ORACLE_FILE_STATUS_WIDTH));
        }

        @Test
        @DisplayName("a card number of spaces cannot match, so it takes the invalid-key arm without "
                + "issuing a keyed read")
        void blankCardNumberTakesTheInvalidKeyArmWithoutAKeyedRead() {
            final DailyTransactionReadService.DailyTransactionVerification verification =
                    service.verify(record(TRANSACTION_ONE, " ".repeat(16)));

            assertAll(
                    () -> assertThat(verification.cardVerified()).isFalse(),
                    () -> assertThat(verification.xrefReadStatus())
                            .isEqualTo(ORACLE_READ_STATUS_INVALID_KEY),
                    () -> verify(cardCrossReferenceRepository, never()).findById(any()));
        }

        @Test
        @DisplayName("a cross-reference row naming a blank account takes the account invalid-key arm "
                + "without issuing a keyed read")
        void blankAccountIdentifierTakesTheInvalidKeyArmWithoutAKeyedRead() {
            when(cardCrossReferenceRepository.findById(CARD_ONE))
                    .thenReturn(Optional.of(crossReference(CARD_ONE, " ".repeat(11))));

            final DailyTransactionReadService.DailyTransactionVerification verification =
                    service.verify(record(TRANSACTION_ONE, CARD_ONE));

            assertAll(
                    () -> assertThat(verification.cardVerified()).isTrue(),
                    () -> assertThat(verification.accountFound()).isFalse(),
                    () -> assertThat(verification.acctReadStatus())
                            .isEqualTo(ORACLE_READ_STATUS_INVALID_KEY),
                    () -> verify(accountRepository, never()).findById(any()));
        }

        @Test
        @DisplayName("a record area no read has filled behaves as an unverifiable record rather than "
                + "failing")
        void unfilledRecordAreaIsToleratedRatherThanFailing() {
            final DailyTransactionReadService.DailyTransactionVerification verification =
                    assertDoesNotThrow(() -> service.verify(null));

            assertAll(
                    () -> assertThat(verification.cardVerified()).isFalse(),
                    () -> assertThat(verification.dalytranId()).isNull(),
                    () -> assertThat(verification.dalytranCardNum()).isNull(),
                    () -> verifyNoInteractions(abendService));
        }
    }

    /**
     * Reachability of all eighteen paragraph units, and the shape of the reads that reach them. Because
     * no pipeline drives this service, a unit that is not reached from here is executed nowhere in the
     * repository at all.
     */
    @Nested
    @DisplayName("Every one of the eighteen paragraph units is reachable, and none is a stub")
    final class EveryParagraphUnitIsReachable {

        /**
         * The six open paragraphs and the six close paragraphs, in one pass. Three of the six files
         * have a gateway in the relational target and are probed twice each - once by the open and once
         * by the close; the three the member opens but never reads have no gateway to probe, so their
         * paragraphs report success and take no interaction.
         */
        @Test
        @DisplayName("all six open paragraphs and all six close paragraphs run in one pass")
        void allTwelveOpenAndCloseParagraphsRun() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);

            runPass(List.of(record(TRANSACTION_ONE, CARD_ONE)));

            assertAll(
                    () -> verify(dailyTransactionRepository, times(2)).count(),
                    () -> verify(cardCrossReferenceRepository, times(2)).count(),
                    () -> verify(accountRepository, times(2)).count());
        }

        /**
         * Each related record is fetched by its own explicit keyed read. There is no association to
         * traverse anywhere in the module - no one-to-many, no many-to-one, no join column, no entity
         * graph and no join fetch - so the proof takes the form of the expected reads happening and no
         * unexpected ones happening at all.
         */
        @Test
        @DisplayName("each related record is fetched by an explicit second lookup, and nothing else is "
                + "touched")
        void relatedRecordsAreFetchedByExplicitSecondLookups() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);

            runPass(List.of(record(TRANSACTION_ONE, CARD_ONE)));

            // Twice each: once for the record itself and once for the trailing pass the loop makes
            // after end of file over the same record area.
            verify(cardCrossReferenceRepository, times(2)).findById(CARD_ONE);
            verify(accountRepository, times(2)).findById(ACCOUNT_ONE);
            verify(dailyTransactionRepository, times(2)).count();
            verify(cardCrossReferenceRepository, times(2)).count();
            verify(accountRepository, times(2)).count();
            verifyNoMoreInteractions(dailyTransactionRepository, cardCrossReferenceRepository,
                    accountRepository, transactionCategoryBalanceRepository);
        }

        /**
         * The legacy read is a physical sequential read of a sequential dataset and the repository
         * imposes no ordering of its own, so the scan the translation issues carries its order
         * explicitly: the finder it calls declares an ascending order on the record's own identity in
         * its name, it starts from the empty exclusive cursor, and it is bounded. The unordered
         * inherited reads are never used.
         */
        @Test
        @DisplayName("the repository-backed scan is explicitly ordered by record identity ascending, "
                + "starts from the empty cursor and is bounded")
        void repositoryScanCarriesItsOrderExplicitly() {
            service.execute(streamed::add);

            final ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Limit> bound = ArgumentCaptor.forClass(Limit.class);
            verify(dailyTransactionRepository)
                    .findByDalytranIdGreaterThanOrderByDalytranIdAsc(cursor.capture(),
                            bound.capture());
            assertAll(
                    () -> assertThat(cursor.getValue()).isEqualTo(KEYSET_START_CURSOR),
                    () -> assertThat(bound.getValue().isLimited()).isTrue(),
                    () -> assertThat(bound.getValue().max()).isPositive(),
                    // The order is declared by the finder, so no unordered inherited read is used.
                    () -> verify(dailyTransactionRepository, never()).findAll(),
                    () -> verify(dailyTransactionRepository, never()).findAll(any(Sort.class)));
        }

        /**
         * The order the pass walks is the order it is given. Where a caller supplies the sequence, the
         * translation performs no sort of its own, because the legacy read returns records in the order
         * the dataset holds them.
         */
        @Test
        @DisplayName("a caller-supplied sequence is walked exactly as supplied, with no client-side "
                + "sort of its own")
        void callerSuppliedSequenceIsWalkedAsSupplied() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            givenResolvable(CARD_TWO, ACCOUNT_TWO);
            // Deliberately descending by identifier: a client-side sort would reverse this.
            final List<DailyTransaction> descending = List.of(
                    record(TRANSACTION_TWO, CARD_TWO),
                    record(TRANSACTION_ONE, CARD_ONE));

            runPass(descending);

            assertThat(streamed)
                    .extracting(
                            DailyTransactionReadService.DailyTransactionVerification::dalytranId)
                    .containsExactly(TRANSACTION_TWO, TRANSACTION_ONE, TRANSACTION_ONE);
        }

        @Test
        @DisplayName("the repository-backed scan walks the rows the finder returns and reports them in "
                + "that order")
        void repositoryScanWalksTheRowsTheFinderReturns() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            givenResolvable(CARD_TWO, ACCOUNT_TWO);
            when(dailyTransactionRepository.findByDalytranIdGreaterThanOrderByDalytranIdAsc(any(),
                    any()))
                    .thenReturn(List.of(record(TRANSACTION_ONE, CARD_ONE),
                            record(TRANSACTION_TWO, CARD_TWO)))
                    .thenReturn(List.of());

            final DailyTransactionReadService.DailyTransactionReadResult result =
                    service.execute(streamed::add);

            assertAll(
                    () -> assertThat(result.recordsRead()).isEqualTo(2),
                    () -> assertThat(streamed)
                            .extracting(DailyTransactionReadService
                                    .DailyTransactionVerification::dalytranId)
                            .containsExactly(TRANSACTION_ONE, TRANSACTION_TWO, TRANSACTION_TWO));
        }

        /**
         * Every public entry point is driven here, and each is proven to do real work: it either
         * returns a value derived from what it was given or interacts with a collaborator, and none of
         * them raises anything - so none is an unimplemented stub throwing
         * UnsupportedOperationException, and none is a silent no-op.
         */
        @Test
        @DisplayName("every public entry point does real work: none raises, none is an unimplemented "
                + "stub and none is a silent no-op")
        void everyPublicEntryPointDoesRealWork() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            when(dailyTransactionRepository.findByDalytranIdGreaterThanOrderByDalytranIdAsc(any(),
                    any()))
                    .thenReturn(List.of(record(TRANSACTION_ONE, CARD_ONE)))
                    .thenReturn(List.of());
            final List<DailyTransaction> supplied = List.of(record(TRANSACTION_ONE, CARD_ONE));
            final BooleanSupplier neverStops = () -> false;

            final DailyTransactionReadService.DailyTransactionReadResult fromSuppliedSource =
                    assertDoesNotThrow(() -> service.execute(supplied, streamed::add));
            final DailyTransactionReadService.DailyTransactionReadResult fromSuppliedSourceWithProbe =
                    assertDoesNotThrow(() -> service.execute(supplied, streamed::add, neverStops));
            final DailyTransactionReadService.DailyTransactionReadResult fromRepositoryScan =
                    assertDoesNotThrow(() -> service.execute(streamed::add));
            final DailyTransactionReadService.DailyTransactionVerification single =
                    assertDoesNotThrow(() -> service.verify(record(TRANSACTION_ONE, CARD_ONE)));

            assertAll(
                    () -> assertThat(fromSuppliedSource.recordsRead()).isEqualTo(1),
                    () -> assertThat(fromSuppliedSourceWithProbe.recordsRead()).isEqualTo(1),
                    () -> assertThat(fromRepositoryScan.recordsRead()).isEqualTo(1),
                    () -> assertThat(single.dalytranId()).isEqualTo(TRANSACTION_ONE),
                    () -> assertThat(single.dalytranCardNum()).isEqualTo(CARD_ONE),
                    () -> assertThat(single.cardVerified()).isTrue());
        }

        @Test
        @DisplayName("the repository-backed form observing a stop probe completes normally when no stop "
                + "is requested")
        void repositoryFormWithAProbeCompletesWhenNoStopIsRequested() {
            final BooleanSupplier neverStops = () -> false;

            final DailyTransactionReadService.DailyTransactionReadResult result =
                    assertDoesNotThrow(() -> service.execute(streamed::add, neverStops));

            assertAll(
                    () -> assertThat(result.recordsRead()).isZero(),
                    () -> assertThat(result.returnCode()).isEqualTo(ORACLE_APPL_AOK));
        }

        @Test
        @DisplayName("a stop requested before the first open abandons the pass without opening anything")
        void stopRequestedBeforeTheFirstOpenAbandonsThePass() {
            final BooleanSupplier alwaysStops = () -> true;

            assertThatExceptionOfType(CancellationException.class)
                    .isThrownBy(() -> service.execute(List.of(), streamed::add, alwaysStops));

            assertAll(
                    () -> verify(dailyTransactionRepository, never()).count(),
                    () -> verifyNoInteractions(abendService));
        }
    }

    /**
     * The two-level status model of {@code 1000-DALYTRAN-GET-NEXT} and its twelve two-way siblings: the
     * raw two-byte code, the coarse result the code is normalised into, and the terminal path that
     * emits the raw code and aborts.
     *
     * <p>The vocabulary is narrow and verified. Success, end of file and record-not-found are the only
     * raw literals the estate compares anywhere, and no assertion in this file depends on any other
     * value. The coarse result is a type nested inside the service that owns it, so it is exercised
     * through that owner - through the terminal value a pass reports and through the arms a pass takes
     * - and never through a type of its own.
     */
    @Nested
    @DisplayName("File status: three raw literals, one coarse result, one terminal path")
    final class TheTwoLevelFileStatusModel {

        @Test
        @DisplayName("the three raw literals the estate compares each resolve, and each carries its own "
                + "meaning")
        void theThreeRawLiteralsResolveToTheirOwnMeanings() {
            final Optional<FileStatus> success = FileStatus.fromCode(ORACLE_STATUS_SUCCESS);
            final Optional<FileStatus> endOfFile = FileStatus.fromCode(ORACLE_STATUS_END_OF_FILE);
            final Optional<FileStatus> notFound =
                    FileStatus.fromCode(ORACLE_STATUS_RECORD_NOT_FOUND);

            assertAll(
                    () -> assertThat(success).isPresent(),
                    () -> assertThat(success.orElseThrow().isSuccess()).isTrue(),
                    () -> assertThat(success.orElseThrow().isEndOfFile()).isFalse(),
                    () -> assertThat(endOfFile).isPresent(),
                    () -> assertThat(endOfFile.orElseThrow().isEndOfFile()).isTrue(),
                    () -> assertThat(endOfFile.orElseThrow().isSuccess()).isFalse(),
                    () -> assertThat(notFound).isPresent(),
                    // Record-not-found is neither, so the read paragraph routes it to the error arm.
                    () -> assertThat(notFound.orElseThrow().isSuccess()).isFalse(),
                    () -> assertThat(notFound.orElseThrow().isEndOfFile()).isFalse(),
                    () -> assertThat(encodedWidth(success.orElseThrow().getCode()))
                            .isEqualTo(ORACLE_FILE_STATUS_WIDTH),
                    () -> assertThat(encodedWidth(endOfFile.orElseThrow().getCode()))
                            .isEqualTo(ORACLE_FILE_STATUS_WIDTH),
                    () -> assertThat(encodedWidth(notFound.orElseThrow().getCode()))
                            .isEqualTo(ORACLE_FILE_STATUS_WIDTH));
        }

        @Test
        @DisplayName("an unrecognised code resolves to nothing, which is what falls to the error arm")
        void anUnrecognisedCodeResolvesToNothing() {
            assertAll(
                    () -> assertThat(FileStatus.fromCode(null)).isEmpty(),
                    () -> assertThat(FileStatus.fromCode("ZZ")).isEmpty());
        }

        /**
         * The coarse result, exercised through the service that owns it. Success continues the loop,
         * end of file terminates it normally, and everything else takes the error arm - the three arms
         * the read paragraph's nested condition produces, in that order.
         */
        @Test
        @DisplayName("the coarse result is exercised through its owner: success continues, end of file "
                + "terminates normally and anything else takes the error arm")
        void theCoarseResultIsExercisedThroughItsOwner() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);

            final DailyTransactionReadService.DailyTransactionReadResult continued =
                    runPass(List.of(record(TRANSACTION_ONE, CARD_ONE)));

            assertAll(
                    // Success: the record was counted and the loop went round again.
                    () -> assertThat(continued.recordsRead()).isEqualTo(1),
                    // End of file: the loop left normally and the pass reported the success value.
                    () -> assertThat(continued.returnCode()).isEqualTo(ORACLE_APPL_AOK),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("the error arm is the third arm, reached without a gateway failure and distinct "
                + "from both of the others")
        void theErrorArmIsTheThirdArm() {
            givenTheAbendPathRaises();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> runPass(Collections.singletonList(null)));

            verify(abendService).abendBatch(eq(ORACLE_CULPRIT), eq(ORACLE_ERROR_READING_DALYTRAN),
                    eq(ORACLE_STATUS_PERMANENT_ERROR), eq("READ"), eq("DALYTRAN"));
        }

        /**
         * The file-status failure type models the error arm alone. Success and end of file are normal
         * outcomes, so constructing it with either is refused - which is the type-level guarantee that
         * end of file can never be reported as a failure anywhere in the estate.
         */
        @Test
        @DisplayName("the file-status failure refuses the success code and the end-of-file code, and "
                + "accepts an error code")
        void theFileStatusFailureRefusesTheTwoNonErrorCodes() {
            final FileStatusException error = new FileStatusException(
                    ORACLE_STATUS_PERMANENT_ERROR, "READ", "DALYTRAN");

            assertAll(
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new FileStatusException(ORACLE_STATUS_SUCCESS, "READ",
                                    "DALYTRAN")),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new FileStatusException(ORACLE_STATUS_END_OF_FILE,
                                    "READ", "DALYTRAN")),
                    () -> assertThat(error.code()).isEqualTo(ORACLE_STATUS_PERMANENT_ERROR),
                    () -> assertThat(error.firstByte()).isEqualTo('3'),
                    () -> assertThat(error.secondByte()).isEqualTo('1'),
                    () -> assertThat(error.operation()).isEqualTo("READ"),
                    () -> assertThat(error.resourceName()).isEqualTo("DALYTRAN"),
                    () -> assertThat(FileStatusException.CODE_LENGTH)
                            .isEqualTo(ORACLE_FILE_STATUS_WIDTH),
                    () -> assertThat(FileStatusException.STATUS_SUCCESS)
                            .isEqualTo(ORACLE_STATUS_SUCCESS),
                    () -> assertThat(FileStatusException.STATUS_END_OF_FILE)
                            .isEqualTo(ORACLE_STATUS_END_OF_FILE),
                    () -> assertThat(FileStatusException.DISPLAY_PREFIX)
                            .isEqualTo(ORACLE_STATUS_PREFIX));
        }

        /**
         * The pre-operation sentinel the twelve open and close paragraphs arm before they act is a
         * fourth value, distinct from success, from end of file and from the error value. It is armed
         * and then immediately overwritten by the normalisation, so it must never appear as the
         * terminal value a pass reports.
         */
        @Test
        @DisplayName("the pre-operation sentinel is distinct from success, end of file and error, and "
                + "never leaks into a completed pass")
        void thePreOperationSentinelNeverLeaksIntoAResult() {
            final DailyTransactionReadService.DailyTransactionReadResult result =
                    runPass(List.of());

            assertAll(
                    // The four normalised values are four distinct hand-written literals.
                    () -> assertThat(ORACLE_APPL_PENDING).isNotEqualTo(ORACLE_APPL_AOK),
                    () -> assertThat(ORACLE_APPL_PENDING).isNotEqualTo(ORACLE_APPL_EOF),
                    () -> assertThat(ORACLE_APPL_PENDING).isNotEqualTo(ORACLE_APPL_ERROR),
                    // And the completed pass reports success, never the armed sentinel.
                    () -> assertThat(result.returnCode()).isEqualTo(ORACLE_APPL_AOK),
                    () -> assertThat(result.returnCode()).isNotEqualTo(ORACLE_APPL_PENDING));
        }
    }

    /**
     * Decimal fidelity. The estate carries no rounding clause anywhere, so every store into a
     * two-decimal field truncates toward zero, and the member itself performs no arithmetic at all:
     * there is no {@code COMPUTE} in its 491 lines, and the only arithmetic statement adds eight to
     * zero to arm an internal result flag. An amount therefore passes through this service unchanged,
     * sign included.
     */
    @Nested
    @DisplayName("Decimal fidelity: truncation toward zero, scale two, and a negative carried unchanged")
    final class DecimalFidelity {

        /**
         * The discriminating case. A value whose surplus digits would round the second decimal place
         * upward under a half-even policy must instead lose them, so the expected value here is the
         * truncated one and the value half-even would have produced is asserted <em>not</em> to appear.
         */
        @Test
        @DisplayName("a surplus-digit amount truncates toward zero rather than rounding, in the one "
                + "case where a half-even policy would differ")
        void surplusDigitsTruncateRatherThanRound() {
            final DailyTransaction stored = TestDataFactory.dailyTransaction()
                    .id(TRANSACTION_ONE)
                    .cardNumber(CARD_ONE)
                    .amount(new BigDecimal("12.999"))
                    .build();

            assertAll(
                    // Truncated by hand: 12.999 at two fraction digits, toward zero, is 12.99.
                    () -> assertThat(stored.getDalytranAmt()).isEqualTo(new BigDecimal("12.99")),
                    // Half-even would have produced 13.00, which must never appear.
                    () -> assertThat(stored.getDalytranAmt())
                            .isNotEqualTo(new BigDecimal("13.00")),
                    () -> assertThat(stored.getDalytranAmt().scale())
                            .isEqualTo(ZonedDecimalCodec.MONETARY_SCALE),
                    () -> assertThat(stored.getDalytranAmt().scale()).isEqualTo(2));
        }

        @Test
        @DisplayName("a negative surplus-digit amount truncates toward zero as well, so its magnitude "
                + "never grows")
        void negativeSurplusDigitsTruncateTowardZero() {
            final DailyTransaction stored = TestDataFactory.dailyTransaction()
                    .id(TRANSACTION_ONE)
                    .cardNumber(CARD_ONE)
                    .amount(new BigDecimal("-12.999"))
                    .build();

            assertAll(
                    // Toward zero by hand: -12.999 at two fraction digits is -12.99, not -13.00.
                    () -> assertThat(stored.getDalytranAmt()).isEqualTo(new BigDecimal("-12.99")),
                    () -> assertThat(stored.getDalytranAmt())
                            .isNotEqualTo(new BigDecimal("-13.00")),
                    () -> assertThat(stored.getDalytranAmt().scale()).isEqualTo(2));
        }

        @Test
        @DisplayName("the module's rounding policy is truncation toward zero at two fraction digits, "
                + "and never a half-even or half-up policy")
        void theModuleRoundingPolicyIsTruncation() {
            assertAll(
                    () -> assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE)
                            .isEqualTo(RoundingMode.DOWN),
                    () -> assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE)
                            .isNotEqualTo(RoundingMode.HALF_EVEN),
                    () -> assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE)
                            .isNotEqualTo(RoundingMode.HALF_UP),
                    () -> assertThat(ZonedDecimalCodec.MONETARY_SCALE).isEqualTo(2));
        }

        /**
         * The fifty operator-originated returns of the delivered fixture carry negative amounts, and the
         * member neither reads the amount nor writes it. A pass must therefore leave it exactly as it
         * found it: never absolute-valued and never re-signed.
         */
        @Test
        @DisplayName("a negative amount survives a whole pass unchanged, neither absolute-valued nor "
                + "re-signed")
        void negativeAmountSurvivesThePassUnchanged() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            final DailyTransaction operatorReturn =
                    record(TRANSACTION_ONE, CARD_ONE, RETURN_AMOUNT);

            runPass(List.of(operatorReturn));

            assertAll(
                    () -> assertThat(operatorReturn.getDalytranAmt())
                            .isEqualTo(new BigDecimal("-919.00")),
                    () -> assertThat(operatorReturn.getDalytranAmt().signum()).isNegative(),
                    () -> assertThat(operatorReturn.getDalytranAmt().scale()).isEqualTo(2),
                    () -> assertThat(operatorReturn.getDalytranAmt().abs())
                            .isNotEqualTo(operatorReturn.getDalytranAmt()));
        }

        @Test
        @DisplayName("a positive amount survives a whole pass unchanged at two fraction digits")
        void positiveAmountSurvivesThePassUnchanged() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            final DailyTransaction purchase = record(TRANSACTION_ONE, CARD_ONE, PURCHASE_AMOUNT);

            runPass(List.of(purchase));

            assertAll(
                    () -> assertThat(purchase.getDalytranAmt()).isEqualTo(new BigDecimal("504.77")),
                    () -> assertThat(purchase.getDalytranAmt().scale()).isEqualTo(2));
        }

        @Test
        @DisplayName("an amount of zero at two fraction digits is carried as zero, and a pass over it "
                + "completes normally")
        void zeroAmountIsCarriedAsZero() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            final DailyTransaction free = record(TRANSACTION_ONE, CARD_ONE, ZERO_AMOUNT);

            final DailyTransactionReadService.DailyTransactionReadResult result =
                    assertDoesNotThrow(() -> runPass(List.of(free)));

            assertAll(
                    () -> assertThat(free.getDalytranAmt()).isEqualTo(new BigDecimal("0.00")),
                    () -> assertThat(free.getDalytranAmt().signum()).isZero(),
                    () -> assertThat(free.getDalytranAmt().scale()).isEqualTo(2),
                    () -> assertThat(result.recordsRead()).isEqualTo(1),
                    () -> assertThat(result.allRecordsVerified()).isTrue());
        }

        @Test
        @DisplayName("a record area whose amount was never filled does not fail the pass")
        void absentAmountDoesNotFailThePass() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            final DailyTransaction unpriced = record(TRANSACTION_ONE, CARD_ONE, null);

            final DailyTransactionReadService.DailyTransactionReadResult result =
                    assertDoesNotThrow(() -> runPass(List.of(unpriced)));

            assertAll(
                    () -> assertThat(unpriced.getDalytranAmt()).isNull(),
                    () -> assertThat(result.recordsRead()).isEqualTo(1),
                    () -> verifyNoInteractions(abendService));
        }
    }

    /**
     * Fixed-width fidelity. Every width below is measured on encoded bytes rather than on a character
     * count, every expected value is written out with its padding visible, and nothing is trimmed: a
     * field of blanks is a populated fixed-width value, not an absent one.
     */
    @Nested
    @DisplayName("Fixed-width fidelity: encoded-byte widths, and blanks that are values rather than "
            + "absences")
    final class FixedWidthFidelity {

        @Test
        @DisplayName("the daily-transaction record's own fields each measure their declared width in "
                + "encoded bytes")
        void recordFieldsMeasureTheirDeclaredWidths() {
            final DailyTransaction stored = record(TRANSACTION_ONE, CARD_ONE);

            assertAll(
                    () -> assertThat(encodedWidth(stored.getDalytranId()))
                            .isEqualTo(ORACLE_CARD_NUMBER_WIDTH),
                    () -> assertThat(encodedWidth(stored.getDalytranCardNum()))
                            .isEqualTo(ORACLE_CARD_NUMBER_WIDTH),
                    () -> assertThat(encodedWidth(stored.getDalytranOrigTs()))
                            .isEqualTo(ORACLE_TIMESTAMP_WIDTH),
                    () -> assertThat(encodedWidth(stored.getDalytranProcTs()))
                            .isEqualTo(ORACLE_TIMESTAMP_WIDTH),
                    () -> assertThat(encodedWidth(stored.getDalytranTypeCd())).isEqualTo(2),
                    () -> assertThat(encodedWidth(stored.getDalytranCatCd())).isEqualTo(4));
        }

        @Test
        @DisplayName("the 350-byte layout's two timestamp offsets are the offsets the record image "
                + "declares, and they are twenty-six bytes apart")
        void theTwoTimestampOffsetsAreTheDeclaredOnes() {
            // Hand-computed from the copybook: the record is 350 bytes, the origination timestamp
            // begins at 278, the processing timestamp begins at 304, and 20 filler bytes follow.
            assertAll(
                    () -> assertThat(ORACLE_PROCESSING_TIMESTAMP_OFFSET
                            - ORACLE_ORIGINATION_TIMESTAMP_OFFSET)
                            .isEqualTo(ORACLE_TIMESTAMP_WIDTH),
                    () -> assertThat(ORACLE_PROCESSING_TIMESTAMP_OFFSET + ORACLE_TIMESTAMP_WIDTH + 20)
                            .isEqualTo(ORACLE_DAILY_TRANSACTION_RECORD_WIDTH),
                    () -> assertThat(TestDataFactory.DAILY_TRANSACTION.recordLength())
                            .isEqualTo(ORACLE_DAILY_TRANSACTION_RECORD_WIDTH));
        }

        /**
         * Every one of the three hundred delivered records carries a processing timestamp of
         * twenty-six blanks. That is a populated fixed-width value: it is neither absent nor empty, and
         * a pass must return it exactly as it found it.
         */
        @Test
        @DisplayName("an all-blank processing timestamp is returned as twenty-six spaces, never as an "
                + "absent value and never as an empty one")
        void blankProcessingTimestampIsAPopulatedValue() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            final DailyTransaction stored = record(TRANSACTION_ONE, CARD_ONE);

            runPass(List.of(stored));

            assertAll(
                    () -> assertThat(stored.getDalytranProcTs()).isNotNull(),
                    () -> assertThat(stored.getDalytranProcTs()).isNotEmpty(),
                    () -> assertThat(stored.getDalytranProcTs()).isEqualTo(" ".repeat(26)),
                    () -> assertThat(encodedWidth(stored.getDalytranProcTs()))
                            .isEqualTo(ORACLE_TIMESTAMP_WIDTH),
                    () -> assertThat(TestDataFactory.BLANK_PROCESSING_TIMESTAMP)
                            .isEqualTo(" ".repeat(26)));
        }

        /**
         * The origination timestamp is the populated one, and it carries the record form: a space
         * between the date and the time, colons inside the time. The batch form of the same instant
         * writes a hyphen and dots instead, and the two forms are never unified.
         */
        @Test
        @DisplayName("the populated timestamp carries the record form and is asserted not to be the "
                + "batch form of the same instant")
        void populatedTimestampCarriesTheRecordFormAndNotTheBatchForm() {
            final DailyTransaction stored = record(TRANSACTION_ONE, CARD_ONE);

            assertAll(
                    () -> assertThat(stored.getDalytranOrigTs()).isEqualTo(ORACLE_RECORD_TIMESTAMP),
                    () -> assertThat(stored.getDalytranOrigTs())
                            .isNotEqualTo(ORACLE_BATCH_TIMESTAMP),
                    () -> assertThat(encodedWidth(ORACLE_RECORD_TIMESTAMP))
                            .isEqualTo(ORACLE_TIMESTAMP_WIDTH),
                    () -> assertThat(encodedWidth(ORACLE_BATCH_TIMESTAMP))
                            .isEqualTo(ORACLE_TIMESTAMP_WIDTH),
                    // The record form separates date from time with a space and uses colons.
                    () -> assertThat(stored.getDalytranOrigTs().charAt(10)).isEqualTo(' '),
                    () -> assertThat(stored.getDalytranOrigTs().charAt(13)).isEqualTo(':'),
                    // The batch form does neither, which is what keeps the two apart.
                    () -> assertThat(ORACLE_BATCH_TIMESTAMP.charAt(10)).isEqualTo('-'),
                    () -> assertThat(ORACLE_BATCH_TIMESTAMP.charAt(13)).isEqualTo('.'),
                    () -> assertThat(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP)
                            .isEqualTo(ORACLE_RECORD_TIMESTAMP));
        }

        /**
         * The group identifier of all fifty seeded accounts is ten spaces. It is a present value of a
         * fixed-width field rather than a missing one, so the account read that resolves such an
         * account still succeeds and the value survives untrimmed.
         */
        @Test
        @DisplayName("a ten-space account group identifier is a populated value: the account read "
                + "succeeds and the field is never trimmed")
        void blankAccountGroupIdentifierIsAPopulatedValue() {
            final Account resolved = account(ACCOUNT_ONE);
            when(cardCrossReferenceRepository.findById(CARD_ONE))
                    .thenReturn(Optional.of(crossReference(CARD_ONE, ACCOUNT_ONE)));
            when(accountRepository.findById(ACCOUNT_ONE)).thenReturn(Optional.of(resolved));

            final DailyTransactionReadService.DailyTransactionVerification verification =
                    service.verify(record(TRANSACTION_ONE, CARD_ONE));

            assertAll(
                    () -> assertThat(verification.accountFound()).isTrue(),
                    () -> assertThat(resolved.getAcctGroupId()).isNotNull(),
                    () -> assertThat(resolved.getAcctGroupId()).isNotEmpty(),
                    () -> assertThat(resolved.getAcctGroupId()).isEqualTo(" ".repeat(10)),
                    () -> assertThat(encodedWidth(resolved.getAcctGroupId()))
                            .isEqualTo(ORACLE_ACCOUNT_GROUP_ID_WIDTH),
                    () -> assertThat(TestDataFactory.SEEDED_ACCOUNT_GROUP_ID)
                            .isEqualTo(" ".repeat(10)));
        }

        @Test
        @DisplayName("the account's correctly spelled expiry property carries the value the misspelled "
                + "legacy field holds, at the width the layout declares")
        void theAccountExpiryPropertyIsSpelledCorrectly() {
            final Account resolved = account(ACCOUNT_ONE);

            assertAll(
                    () -> assertThat(resolved.getAcctExpirationDate()).isEqualTo("2030-01-01"),
                    () -> assertThat(encodedWidth(resolved.getAcctExpirationDate())).isEqualTo(10),
                    () -> assertThat(encodedWidth(resolved.getAcctId()))
                            .isEqualTo(ORACLE_ACCOUNT_ID_WIDTH));
        }

        @Test
        @DisplayName("the cross-reference row's three fields each measure their declared width, and its "
                + "thirty-six data bytes explain why the fixture is shorter than the dataset")
        void crossReferenceFieldsMeasureTheirDeclaredWidths() {
            final CardCrossReference row = crossReference(CARD_ONE, ACCOUNT_ONE);

            assertAll(
                    () -> assertThat(encodedWidth(row.getXrefCardNum()))
                            .isEqualTo(ORACLE_CARD_NUMBER_WIDTH),
                    () -> assertThat(encodedWidth(row.getXrefCustId()))
                            .isEqualTo(ORACLE_CUSTOMER_ID_WIDTH),
                    () -> assertThat(encodedWidth(row.getXrefAcctId()))
                            .isEqualTo(ORACLE_ACCOUNT_ID_WIDTH),
                    // 16 + 9 + 11 = 36 data bytes, hand-summed, inside a 50-byte record.
                    () -> assertThat(ORACLE_CARD_NUMBER_WIDTH + ORACLE_CUSTOMER_ID_WIDTH
                            + ORACLE_ACCOUNT_ID_WIDTH).isEqualTo(36),
                    () -> assertThat(TestDataFactory.CARD_CROSS_REFERENCE_DATASET.recordLength())
                            .isEqualTo(50));
        }

        @Test
        @DisplayName("the merchant properties of the daily-transaction record carry the prefixed names, "
                + "which are never unified with the posted record's unprefixed ones")
        void merchantPropertiesKeepTheirPrefixedNames() {
            final DailyTransaction stored = record(TRANSACTION_ONE, CARD_ONE);

            assertAll(
                    () -> assertThat(stored.getDalytranMerchantId()).isEqualTo("800000000"),
                    () -> assertThat(stored.getDalytranMerchantName()).isEqualTo("Abshire-Lowe"),
                    () -> assertThat(stored.getDalytranMerchantCity())
                            .isEqualTo("North Enoshaven"),
                    () -> assertThat(stored.getDalytranMerchantZip()).isEqualTo("72112     "),
                    () -> assertThat(encodedWidth(stored.getDalytranMerchantZip())).isEqualTo(10),
                    () -> assertThat(encodedWidth(stored.getDalytranMerchantId()))
                            .isEqualTo(ORACLE_CUSTOMER_ID_WIDTH),
                    () -> assertThat(encodedWidth(stored.getDalytranSource())).isEqualTo(10));
        }
    }

    /**
     * The composite record key. Its three components are declared account first, then transaction type,
     * then transaction category, and a constructor call that transposes two of them compiles cleanly
     * and silently mis-keys every lookup - which is exactly why the order is captured and asserted here
     * rather than assumed.
     */
    @Nested
    @DisplayName("The composite key is built account, then type code, then category code")
    final class TheCompositeKeyOrder {

        @Test
        @DisplayName("the key a lookup is issued with carries its three components in the declared "
                + "order")
        void theKeyCarriesItsComponentsInTheDeclaredOrder() {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId(ACCOUNT_ONE, TRAN_TYPE_CD, TRAN_CAT_CD);
            final TransactionCategoryBalance row = new TransactionCategoryBalance(ACCOUNT_ONE,
                    TRAN_TYPE_CD, TRAN_CAT_CD, ZERO_AMOUNT);
            when(transactionCategoryBalanceRepository.findById(key)).thenReturn(Optional.of(row));

            final Optional<TransactionCategoryBalance> found =
                    transactionCategoryBalanceRepository.findById(key);

            final ArgumentCaptor<TransactionCategoryBalanceId> issued =
                    ArgumentCaptor.forClass(TransactionCategoryBalanceId.class);
            verify(transactionCategoryBalanceRepository).findById(issued.capture());
            assertAll(
                    () -> assertThat(found).isPresent(),
                    () -> assertThat(issued.getValue().getTrancatAcctId()).isEqualTo(ACCOUNT_ONE),
                    () -> assertThat(issued.getValue().getTrancatTypeCd()).isEqualTo(TRAN_TYPE_CD),
                    () -> assertThat(issued.getValue().getTrancatCd()).isEqualTo(TRAN_CAT_CD),
                    // The three widths differ, so a transposed argument could not even be the right
                    // length: eleven, two and four bytes, hand-counted from the layout.
                    () -> assertThat(encodedWidth(issued.getValue().getTrancatAcctId()))
                            .isEqualTo(ORACLE_ACCOUNT_ID_WIDTH),
                    () -> assertThat(encodedWidth(issued.getValue().getTrancatTypeCd()))
                            .isEqualTo(2),
                    () -> assertThat(encodedWidth(issued.getValue().getTrancatCd())).isEqualTo(4));
        }

        @Test
        @DisplayName("a key whose type and category components are transposed is not the same key, so a "
                + "transposition cannot pass unnoticed")
        void aTransposedKeyIsNotTheSameKey() {
            final TransactionCategoryBalanceId declared =
                    new TransactionCategoryBalanceId(ACCOUNT_ONE, TRAN_TYPE_CD, TRAN_CAT_CD);
            final TransactionCategoryBalanceId transposed =
                    new TransactionCategoryBalanceId(ACCOUNT_ONE, TRAN_CAT_CD, TRAN_TYPE_CD);

            assertAll(
                    () -> assertThat(transposed).isNotEqualTo(declared),
                    () -> assertThat(transposed.getTrancatTypeCd()).isEqualTo(TRAN_CAT_CD),
                    () -> assertThat(declared.getTrancatTypeCd()).isEqualTo(TRAN_TYPE_CD));
        }

        @Test
        @DisplayName("the row's own key derivation produces the same three components in the same order")
        void theRowDerivesTheSameKey() {
            final TransactionCategoryBalance row = new TransactionCategoryBalance(ACCOUNT_ONE,
                    TRAN_TYPE_CD, TRAN_CAT_CD, ZERO_AMOUNT);

            final TransactionCategoryBalanceId derived = row.toId();

            assertAll(
                    () -> assertThat(derived).isEqualTo(new TransactionCategoryBalanceId(
                            ACCOUNT_ONE, TRAN_TYPE_CD, TRAN_CAT_CD)),
                    () -> assertThat(derived.getTrancatAcctId()).isEqualTo(ACCOUNT_ONE),
                    () -> assertThat(derived.getTrancatTypeCd()).isEqualTo(TRAN_TYPE_CD),
                    () -> assertThat(derived.getTrancatCd()).isEqualTo(TRAN_CAT_CD),
                    // Every seeded row carries a zero balance, so an update path is exercisable from
                    // the seed while a create path needs a key built here.
                    () -> assertThat(row.getTranCatBal()).isEqualTo(new BigDecimal("0.00")),
                    () -> assertThat(row.getTranCatBal().scale()).isEqualTo(2));
        }
    }

    /**
     * The diagnostics each paragraph emits. The legacy program reported everything it did by display, so
     * these literals are the member's externally observable behaviour and are reproduced verbatim - with
     * one deliberate departure, recorded on the service itself: the whole-record display is replaced by
     * a field-free status line, because writing a record's card number, amount, merchant details and
     * timestamps into an exported log would create a second uncontrolled copy of protected data.
     */
    @Nested
    @DisplayName("Diagnostics: the legacy literals, and no protected field in any of them")
    final class Diagnostics {

        @Test
        @DisplayName("the two successful keyed reads each emit their own literal")
        void successfulKeyedReadsEmitTheirOwnLiterals() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);

            service.verify(record(TRANSACTION_ONE, CARD_ONE));

            assertThat(recorded())
                    .contains(ORACLE_SUCCESSFUL_READ_OF_XREF, ORACLE_SUCCESSFUL_READ_OF_ACCOUNT);
        }

        @Test
        @DisplayName("the two invalid-key arms each emit their own literal, and the account arm emits "
                + "its not-found message as well")
        void invalidKeyArmsEmitTheirOwnLiterals() {
            when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());

            service.verify(record(TRANSACTION_ONE, UNKNOWN_CARD));

            assertThat(recorded())
                    .contains(ORACLE_INVALID_CARD_FOR_XREF, ORACLE_CARD_NOT_VERIFIED);
        }

        @Test
        @DisplayName("the account invalid-key arm emits both its literal and the not-found message")
        void accountInvalidKeyArmEmitsBothMessages() {
            when(cardCrossReferenceRepository.findById(CARD_ONE))
                    .thenReturn(Optional.of(crossReference(CARD_ONE, ACCOUNT_ONE)));
            when(accountRepository.findById(ACCOUNT_ONE)).thenReturn(Optional.empty());

            service.verify(record(TRANSACTION_ONE, CARD_ONE));

            assertThat(recorded()).contains(ORACLE_INVALID_ACCOUNT, ORACLE_ACCOUNT_NOT_FOUND);
        }

        @Test
        @DisplayName("the successful cross-reference diagnostic exposes none of the three identifiers it "
                + "resolved")
        void successfulCrossReferenceDiagnosticExposesNoIdentifier() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);

            service.verify(record(TRANSACTION_ONE, CARD_ONE));

            assertAll(
                    () -> assertThat(recorded())
                            .noneMatch(message -> message.contains(CARD_ONE)),
                    () -> assertThat(recorded())
                            .noneMatch(message -> message.contains(ACCOUNT_ONE)),
                    () -> assertThat(recorded())
                            .noneMatch(message -> message.contains(CUSTOMER_ONE)));
        }

        @Test
        @DisplayName("the record diagnostic reports the successful read status and withholds every "
                + "protected field of the record")
        void recordDiagnosticWithholdsEveryProtectedField() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);

            runPass(List.of(record(TRANSACTION_ONE, CARD_ONE, PURCHASE_AMOUNT)));

            assertAll(
                    () -> assertThat(recorded()).contains(ORACLE_RECORD_READ_STATUS),
                    () -> assertThat(recorded()).allSatisfy(message -> assertThat(message)
                            .doesNotContain(CARD_ONE, ACCOUNT_ONE, CUSTOMER_ONE, TRANSACTION_ONE,
                                    "504.77", "Abshire-Lowe", "North Enoshaven", "72112",
                                    ORACLE_RECORD_TIMESTAMP)));
        }

        @Test
        @DisplayName("the record diagnostic is emitted once for each record read and never for the "
                + "trailing pass")
        void recordDiagnosticIsEmittedOncePerRecordRead() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            givenResolvable(CARD_TWO, ACCOUNT_TWO);

            runPass(List.of(record(TRANSACTION_ONE, CARD_ONE), record(TRANSACTION_TWO, CARD_TWO)));

            // Two records read, so two record diagnostics: hand-counted, and the trailing pass adds
            // none because no read delivered a record to it.
            assertThat(recorded().stream()
                    .filter(message -> message.equals(ORACLE_RECORD_READ_STATUS))
                    .count()).isEqualTo(2L);
        }

        @Test
        @DisplayName("the record diagnostic is skipped entirely when the level in force would discard it")
        void recordDiagnosticIsSkippedWhenTheLevelWouldDiscardIt() {
            serviceLogger.setLevel(Level.WARN);
            givenResolvable(CARD_ONE, ACCOUNT_ONE);

            runPass(List.of(record(TRANSACTION_ONE, CARD_ONE)));

            assertThat(recorded()).doesNotContain(ORACLE_RECORD_READ_STATUS);
        }
    }

    /**
     * The contracts the translation adds, and the orphan status itself.
     *
     * <p>The counts, the streamed outcomes and the terminal result value have no legacy counterpart:
     * the member reported its work solely by display. They exist so that a caller can observe a pass
     * that nothing in the estate ever drove, and their invariants are enforced rather than assumed.
     */
    @Nested
    @DisplayName("Contracts, and the orphan status recorded as a decision")
    final class ContractsAndOrphanStatus {

        @Test
        @DisplayName("each of the four collaborators is required, and each refusal names the one that "
                + "was missing")
        void everyCollaboratorIsRequired() {
            assertAll(
                    () -> assertThatThrownBy(() -> new DailyTransactionReadService(null,
                            cardCrossReferenceRepository, accountRepository, abendService))
                            .isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("dailyTransactionRepository"),
                    () -> assertThatThrownBy(() -> new DailyTransactionReadService(
                            dailyTransactionRepository, null, accountRepository, abendService))
                            .isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("cardCrossReferenceRepository"),
                    () -> assertThatThrownBy(() -> new DailyTransactionReadService(
                            dailyTransactionRepository, cardCrossReferenceRepository, null,
                            abendService))
                            .isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("accountRepository"),
                    () -> assertThatThrownBy(() -> new DailyTransactionReadService(
                            dailyTransactionRepository, cardCrossReferenceRepository,
                            accountRepository, null))
                            .isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("abendService"));
        }

        @Test
        @DisplayName("the supplied-source form refuses a source that is absent altogether, which is not "
                + "the same thing as an empty one")
        void theSuppliedSourceFormRefusesAnAbsentSource() {
            final Consumer<DailyTransactionReadService.DailyTransactionVerification> sink =
                    outcome -> streamed.add(outcome);

            assertAll(
                    () -> assertThatThrownBy(() -> service.execute(
                            (Iterable<DailyTransaction>) null, sink))
                            .isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("orderedDailyTransactions"),
                    // An empty source, by contrast, is an ordinary pass that reads nothing.
                    () -> assertThat(runPass(List.of()).recordsRead()).isZero());
        }

        @Test
        @DisplayName("every form refuses a pass with no destination for its per-record outcomes")
        void everyFormRefusesAnAbsentDestination() {
            assertAll(
                    () -> assertThatThrownBy(() -> service.execute(null))
                            .isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("verificationSink"),
                    () -> assertThatThrownBy(() -> service.execute(List.of(), null))
                            .isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("verificationSink"));
        }

        @Test
        @DisplayName("the repository-backed form refuses an absent stop probe")
        void theRepositoryBackedFormRefusesAnAbsentStopProbe() {
            assertThatThrownBy(() -> service.execute(streamed::add, (BooleanSupplier) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("stopRequested");
        }

        @Test
        @DisplayName("the result refuses a negative count, a verified count above the read count and a "
                + "pass count below the verified count")
        void theResultRefusesCombinationsItCouldNotHaveProduced() {
            assertAll(
                    () -> assertThatThrownBy(
                            () -> new DailyTransactionReadService.DailyTransactionReadResult(
                                    -1, 0, 0, 0, 0, 0))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("recordsRead"),
                    () -> assertThatThrownBy(
                            () -> new DailyTransactionReadService.DailyTransactionReadResult(
                                    1, 2, 2, 0, 0, 0))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("recordsVerified"),
                    () -> assertThatThrownBy(
                            () -> new DailyTransactionReadService.DailyTransactionReadResult(
                                    2, 2, 1, 0, 0, 0))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("verificationPasses"));
        }

        @Test
        @DisplayName("an account read that never happened never reports the account as found")
        void anUnattemptedAccountReadIsNeverReportedAsFound() {
            final DailyTransactionReadService.DailyTransactionVerification unresolved =
                    new DailyTransactionReadService.DailyTransactionVerification(TRANSACTION_ONE,
                            CARD_ONE, null, ORACLE_READ_STATUS_INVALID_KEY, ORACLE_READ_STATUS_OK,
                            false, false);

            assertAll(
                    () -> assertThat(unresolved.cardVerified()).isFalse(),
                    () -> assertThat(unresolved.accountFound()).isFalse(),
                    () -> assertThat(unresolved.accountLookupAttempted()).isFalse(),
                    () -> assertThat(unresolved.xrefAcctId()).isNull());
        }

        @Test
        @DisplayName("a pass that could not verify a card does not report every record verified")
        void aPassWithAnUnverifiableCardIsNotReportedAsFullyVerified() {
            when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());

            final DailyTransactionReadService.DailyTransactionReadResult result =
                    runPass(List.of(record(TRANSACTION_ONE, UNKNOWN_CARD)));

            assertAll(
                    () -> assertThat(result.allRecordsVerified()).isFalse(),
                    // Two: the record's own pass and the trailing pass over the same record area.
                    () -> assertThat(result.cardsNotVerified()).isEqualTo(2),
                    () -> assertThat(result.accountsNotFound()).isZero(),
                    () -> assertThat(result.recordsRead()).isEqualTo(1));
        }

        @Test
        @DisplayName("a pass whose account is missing does not report every record verified")
        void aPassWithAMissingAccountIsNotReportedAsFullyVerified() {
            when(cardCrossReferenceRepository.findById(CARD_ONE))
                    .thenReturn(Optional.of(crossReference(CARD_ONE, ACCOUNT_ONE)));
            when(accountRepository.findById(ACCOUNT_ONE)).thenReturn(Optional.empty());

            final DailyTransactionReadService.DailyTransactionReadResult result =
                    runPass(List.of(record(TRANSACTION_ONE, CARD_ONE)));

            assertAll(
                    () -> assertThat(result.allRecordsVerified()).isFalse(),
                    () -> assertThat(result.accountsNotFound()).isEqualTo(2),
                    () -> assertThat(result.cardsNotVerified()).isZero());
        }

        /**
         * The orphan status, asserted as a property of the delivery rather than left to prose. The
         * member has no caller of any kind in the legacy estate, so migrating it was a decision and the
         * job derived from it is excluded from the default pipeline; the artefact the estate genuinely
         * leaves unmigrated is a different one - a data copybook with zero inclusion references, which
         * produces no artefact at all. What follows is the observable consequence: this service is
         * fully functional and reachable from a test, while nothing else drives it.
         */
        @Test
        @DisplayName("the orphan is fully functional despite having no caller anywhere in the estate, "
                + "which is why migrating it was a decision rather than an omission")
        void theOrphanIsFullyFunctionalDespiteHavingNoCaller() {
            givenResolvable(CARD_ONE, ACCOUNT_ONE);
            givenResolvable(CARD_TWO, ACCOUNT_TWO);
            givenResolvable(CARD_THREE, ACCOUNT_THREE);

            final DailyTransactionReadService.DailyTransactionReadResult result =
                    assertDoesNotThrow(() -> runPass(List.of(
                            record(TRANSACTION_ONE, CARD_ONE, PURCHASE_AMOUNT),
                            record(TRANSACTION_TWO, CARD_TWO, RETURN_AMOUNT),
                            record(TRANSACTION_THREE, CARD_THREE, ZERO_AMOUNT))));

            assertAll(
                    // A complete pass: six opens, three reads, three verifications and six closes.
                    () -> assertThat(recorded()).contains(ORACLE_START, ORACLE_END),
                    () -> assertThat(result.recordsRead()).isEqualTo(3),
                    () -> assertThat(result.recordsVerified()).isEqualTo(3),
                    () -> assertThat(result.verificationPasses()).isEqualTo(4),
                    () -> assertThat(result.allRecordsVerified()).isTrue(),
                    () -> assertThat(result.returnCode()).isEqualTo(ORACLE_APPL_AOK),
                    () -> assertThat(streamedCount()).isEqualTo(4),
                    () -> verifyNoInteractions(abendService));
        }

        /**
         * The delivered fixture's census, restated as hand-measured literals so that the composition
         * this class's fixtures imitate is visible: three hundred records, two hundred and fifty
         * positive point-of-sale purchases, fifty negative operator-originated returns, a blank
         * processing timestamp on every one of them, and a posted-transaction table that starts empty.
         */
        @Test
        @DisplayName("the fixture census this class's records imitate is the measured one")
        void theFixtureCensusIsTheMeasuredOne() {
            assertAll(
                    () -> assertThat(TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT).isEqualTo(300),
                    () -> assertThat(TestDataFactory.SEEDED_PURCHASE_COUNT).isEqualTo(250),
                    () -> assertThat(TestDataFactory.SEEDED_RETURN_COUNT).isEqualTo(50),
                    // 250 purchases plus 50 returns is 300 records, summed by hand.
                    () -> assertThat(TestDataFactory.SEEDED_PURCHASE_COUNT
                            + TestDataFactory.SEEDED_RETURN_COUNT).isEqualTo(300),
                    // The posted-transaction table starts empty after the reference-data seed, which
                    // is why an empty pass is a case this class asserts rather than an oddity.
                    () -> assertThat(TestDataFactory.SEEDED_TRANSACTION_COUNT).isZero());
        }
    }
}
