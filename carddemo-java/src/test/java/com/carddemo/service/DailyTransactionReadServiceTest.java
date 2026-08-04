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
import java.util.List;
import java.util.Optional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.exception.AbendException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.DailyTransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@code DailyTransactionReadService}, the migration of {@code app/cbl/CBTRN01C.cbl} - the
 * estate's orphan, a complete 491-line batch program of 18 paragraphs that no job member, no
 * cataloged procedure and no CICS resource definition invokes.
 *
 * <p><strong>These tests are the program's only exercise path.</strong> Because the job configuration
 * is deliberately defined but unwired, nothing else in the module drives this code, so coverage here
 * is not a nicety: it is the entire verification of the translation. That is why the decisive
 * behaviours are asserted individually rather than incidentally.
 *
 * <p>The four properties most likely to be got wrong, and therefore asserted most directly:
 *
 * <ul>
 *   <li><strong>End of file terminates normally.</strong> The legacy read normalises its status three
 *       ways and the loop ends on the end-of-file arm. Folding that arm into the error arm - the most
 *       likely defect in any translation of this tier - would turn every healthy run into an abend,
 *       so a clean pass over a populated input is asserted to raise nothing at all.</li>
 *   <li><strong>The diagnostic precedes the abend.</strong> Every failing site displays its literal,
 *       displays the raw two-character status and only then aborts. The proof is structural: events
 *       recorded by an appender at the instant the abend is caught can only have been emitted before
 *       the raise, and their relative order in the recorder is the order they were emitted in.</li>
 *   <li><strong>A missing record is not an exception.</strong> The cross-reference and account
 *       paragraphs carry an invalid-key arm and no error arm, so an absent row is an ordinary reported
 *       outcome and must not propagate.</li>
 *   <li><strong>Amounts truncate.</strong> No rounding clause exists anywhere in the estate, so a
 *       store into a two-decimal field truncates toward zero. A value with surplus fractional digits
 *       must therefore lose them rather than round them up.</li>
 * </ul>
 *
 * <p><strong>Independent oracles.</strong> Every literal, status code and count asserted below is
 * restated here from {@code app/cbl/CBTRN01C.cbl} at the cited line, never read back from the class
 * under test, so agreement between the two is evidence rather than tautology.
 *
 * <p>A plain unit test: no Spring context, no container, no database and no filesystem access. The
 * three repositories and the abend service are test doubles.
 */
@DisplayName("DailyTransactionReadService: the orphan extract pass, translated in full")
final class DailyTransactionReadServiceTest {

    // ---------------------------------------------------------------------------------------------
    // Oracles restated from app/cbl/CBTRN01C.cbl. None is read back from the production class.
    // ---------------------------------------------------------------------------------------------

    /** Line 156. */
    private static final String ORACLE_START = "START OF EXECUTION OF PROGRAM CBTRN01C";

    /** Line 195. */
    private static final String ORACLE_END = "END OF EXECUTION OF PROGRAM CBTRN01C";

    /** Line 263, the daily-transaction open's error literal. */
    private static final String ORACLE_ERROR_OPENING_DALYTRAN =
            "ERROR OPENING DAILY TRANSACTION FILE";

    /** Line 300, the cross-reference open's error literal. */
    private static final String ORACLE_ERROR_OPENING_XREF = "ERROR OPENING CROSS REF FILE";

    /**
     * Line 372. The daily-transaction <em>close</em> displays the customer file's literal - a copy of
     * the paragraph that follows it. The defect is externally observable, so it is asserted rather
     * than corrected.
     */
    private static final String ORACLE_ERROR_CLOSING_CUSTOMER = "ERROR CLOSING CUSTOMER FILE";

    /** Line 232. */
    private static final String ORACLE_INVALID_CARD_FOR_XREF = "INVALID CARD NUMBER FOR XREF";

    /** Line 246. */
    private static final String ORACLE_INVALID_ACCOUNT = "INVALID ACCOUNT NUMBER FOUND";

    /** Line 249. */
    private static final String ORACLE_SUCCESSFUL_READ_OF_ACCOUNT = "SUCCESSFUL READ OF ACCOUNT FILE";

    /** Line 235. */
    private static final String ORACLE_SUCCESSFUL_READ_OF_XREF = "SUCCESSFUL READ OF XREF";

    /** The prefix the status display carries, recognisable to an operator. */
    private static final String ORACLE_STATUS_PREFIX = "FILE STATUS IS: NNNN";

    /** The literal the abend path displays immediately before aborting, line 470. */
    private static final String ORACLE_ABENDING = "ABENDING PROGRAM";

    /** The value the abend paragraph moves into the abend code item, line 472. */
    private static final String ORACLE_BATCH_ABEND_CODE = "999";

    /** The eight-character member name the abend names as its culprit. */
    private static final String ORACLE_CULPRIT = "CBTRN01C";

    /** {@code WS-XREF-READ-STATUS} and {@code WS-ACCT-READ-STATUS} after a successful keyed read. */
    private static final int ORACLE_READ_STATUS_OK = 0;

    /** The value both keyed-read paragraphs move on their invalid-key arm, lines 233 and 247. */
    private static final int ORACLE_READ_STATUS_INVALID_KEY = 4;

    /** A hard access failure is reported with the permanent-error status of the legacy vocabulary. */
    private static final String ORACLE_PERMANENT_ERROR_STATUS = "31";

    /** The JPA property the sequential scan must order by, ascending. */
    private static final String ORACLE_SORT_PROPERTY = "dalytranId";

    private DailyTransactionRepository dailyTransactionRepository;

    private CardCrossReferenceRepository cardCrossReferenceRepository;

    private AccountRepository accountRepository;

    private DailyTransactionReadService service;

    @BeforeEach
    void createService() {
        dailyTransactionRepository = mock(DailyTransactionRepository.class);
        cardCrossReferenceRepository = mock(CardCrossReferenceRepository.class);
        accountRepository = mock(AccountRepository.class);
        service = new DailyTransactionReadService(dailyTransactionRepository,
                cardCrossReferenceRepository, accountRepository, new AbendService());
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures. Widths match the 350-byte layout of app/cpy/CVTRA06Y.cpy field for field.
    // ---------------------------------------------------------------------------------------------

    private static DailyTransaction transaction(final String id, final String cardNumber,
            final BigDecimal amount) {
        return new DailyTransaction(id, "01", "0005", "POS TERM", "PURCHASE", amount,
                "123456789", "MERCHANT NAME", "MERCHANT CITY", "12345", cardNumber,
                "2022-07-19 00:00:00.000000", "2022-07-19 00:00:00.000000");
    }

    private static DailyTransaction transaction(final String id, final String cardNumber) {
        return transaction(id, cardNumber, new BigDecimal("10.00"));
    }

    private static CardCrossReference crossReference(final String cardNumber,
            final String accountId) {
        return new CardCrossReference(cardNumber, "000000001", accountId);
    }

    private static Account account(final String accountId) {
        return new Account(accountId, "Y", new BigDecimal("1000.00"), new BigDecimal("5000.00"),
                new BigDecimal("1000.00"), "2022-01-01", "2030-01-01", "2022-01-01",
                new BigDecimal("0.00"), new BigDecimal("0.00"), "12345", "A");
    }

    /** Wires the happy path for one card: the cross-reference resolves and the account exists. */
    private void givenResolvable(final String cardNumber, final String accountId) {
        when(cardCrossReferenceRepository.findById(cardNumber))
                .thenReturn(Optional.of(crossReference(cardNumber, accountId)));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId)));
    }

    @Nested
    @DisplayName("End of file terminates the read loop normally and never abends")
    final class EndOfFileIsNotAnError {

        @Test
        @DisplayName("a populated input completes without raising anything at all")
        void populatedInputCompletesNormally() {
            givenResolvable("0000000000000001", "00000000001");
            List<DailyTransaction> input = List.of(transaction("1", "0000000000000001"));

            assertThatNoException().isThrownBy(() -> service.execute(input));
        }

        @Test
        @DisplayName("an empty input completes without raising, exhausting the source immediately")
        void emptyInputCompletesNormally() {
            when(cardCrossReferenceRepository.findById(any())).thenReturn(Optional.empty());

            DailyTransactionReadService.DailyTransactionReadResult result =
                    service.execute(List.of());

            assertAll(
                    () -> assertThat(result.recordsRead()).isZero(),
                    () -> assertThat(result.recordsVerified()).isZero(),
                    () -> assertThat(result.returnCode()).isZero());
        }

        @Test
        @DisplayName("the terminal result value is zero, the value the last close leaves behind")
        void terminalResultValueIsZero() {
            givenResolvable("0000000000000001", "00000000001");

            DailyTransactionReadService.DailyTransactionReadResult result =
                    service.execute(List.of(transaction("1", "0000000000000001")));

            assertThat(result.returnCode()).isZero();
        }
    }

    @Nested
    @DisplayName("Counts report exactly what was read")
    final class Counts {

        @Test
        @DisplayName("records read and records verified both equal the input's record count exactly")
        void countsMatchTheInputRecordCount() {
            givenResolvable("0000000000000001", "00000000001");
            givenResolvable("0000000000000002", "00000000002");
            givenResolvable("0000000000000003", "00000000003");
            List<DailyTransaction> input = List.of(
                    transaction("1", "0000000000000001"),
                    transaction("2", "0000000000000002"),
                    transaction("3", "0000000000000003"));

            DailyTransactionReadService.DailyTransactionReadResult result = service.execute(input);

            assertAll(
                    () -> assertThat(result.recordsRead()).isEqualTo(input.size()),
                    () -> assertThat(result.recordsVerified()).isEqualTo(input.size()),
                    () -> assertThat(result.allRecordsVerified()).isTrue());
        }

        /**
         * The verification block at lines 170 to 184 sits outside the end-of-file test at line 167, so
         * the loop runs it once more over the record area the previous read left in place. The extra
         * pass is real behaviour and is asserted as such; what must not drift is the read count.
         */
        @Test
        @DisplayName("the loop makes one extra verification pass after end of file, over the last record")
        void oneTrailingVerificationPassAfterEndOfFile() {
            givenResolvable("0000000000000001", "00000000001");
            givenResolvable("0000000000000002", "00000000002");
            List<DailyTransaction> input = List.of(
                    transaction("1", "0000000000000001"),
                    transaction("2", "0000000000000002"));

            DailyTransactionReadService.DailyTransactionReadResult result = service.execute(input);

            DailyTransactionReadService.DailyTransactionVerification trailing =
                    result.verifications().get(result.verifications().size() - 1);
            assertAll(
                    () -> assertThat(result.verificationPasses()).isEqualTo(input.size() + 1),
                    () -> assertThat(result.recordsRead()).isEqualTo(input.size()),
                    () -> assertThat(trailing.afterEndOfFile()).isTrue(),
                    () -> assertThat(trailing.dalytranId()).isEqualTo("2"),
                    () -> assertThat(result.verifications()).hasSize(input.size() + 1));
        }

        @Test
        @DisplayName("an empty input still makes the one trailing pass, over an unfilled record area")
        void emptyInputStillMakesTheTrailingPass() {
            when(cardCrossReferenceRepository.findById(any())).thenReturn(Optional.empty());

            DailyTransactionReadService.DailyTransactionReadResult result =
                    service.execute(List.of());

            assertAll(
                    () -> assertThat(result.verificationPasses()).isEqualTo(1),
                    () -> assertThat(result.cardsNotVerified()).isEqualTo(1),
                    () -> assertThat(result.verifications()).hasSize(1));
        }

        @Test
        @DisplayName("a pass with an unverifiable card does not report every record verified")
        void unverifiableCardIsReportedInTheSummary() {
            when(cardCrossReferenceRepository.findById(any())).thenReturn(Optional.empty());

            DailyTransactionReadService.DailyTransactionReadResult result =
                    service.execute(List.of(transaction("1", "0000000000000009")));

            assertAll(
                    () -> assertThat(result.allRecordsVerified()).isFalse(),
                    () -> assertThat(result.cardsNotVerified()).isEqualTo(2),
                    () -> assertThat(result.accountsNotFound()).isZero(),
                    () -> assertThat(result.recordsRead()).isEqualTo(1));
        }

        @Test
        @DisplayName("a pass whose account is missing does not report every record verified")
        void missingAccountIsReportedInTheSummary() {
            when(cardCrossReferenceRepository.findById("0000000000000001"))
                    .thenReturn(Optional.of(crossReference("0000000000000001", "00000000001")));
            when(accountRepository.findById("00000000001")).thenReturn(Optional.empty());

            DailyTransactionReadService.DailyTransactionReadResult result =
                    service.execute(List.of(transaction("1", "0000000000000001")));

            assertAll(
                    () -> assertThat(result.allRecordsVerified()).isFalse(),
                    () -> assertThat(result.accountsNotFound()).isEqualTo(2),
                    () -> assertThat(result.cardsNotVerified()).isZero());
        }

        @Test
        @DisplayName("the returned verification list is immutable")
        void verificationListIsImmutable() {
            givenResolvable("0000000000000001", "00000000001");

            DailyTransactionReadService.DailyTransactionReadResult result =
                    service.execute(List.of(transaction("1", "0000000000000001")));

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> result.verifications().clear());
        }
    }

    @Nested
    @DisplayName("A row that is not there is a reported outcome, never an exception")
    final class MissingRowsAreReportedNotThrown {

        @Test
        @DisplayName("an empty cross-reference result takes the invalid-key arm and does not throw")
        void absentCrossReferenceIsTheNotFoundPath() {
            when(cardCrossReferenceRepository.findById("0000000000000009"))
                    .thenReturn(Optional.empty());

            DailyTransactionReadService.DailyTransactionVerification verification =
                    service.verify(transaction("9", "0000000000000009"));

            assertAll(
                    () -> assertThat(verification.cardVerified()).isFalse(),
                    () -> assertThat(verification.xrefReadStatus())
                            .isEqualTo(ORACLE_READ_STATUS_INVALID_KEY),
                    () -> assertThat(verification.accountLookupAttempted()).isFalse(),
                    () -> assertThat(verification.xrefAcctId()).isNull());
        }

        @Test
        @DisplayName("the account is never read for a card the cross-reference could not resolve")
        void accountIsNotReadWhenTheCardIsUnresolved() {
            when(cardCrossReferenceRepository.findById(any())).thenReturn(Optional.empty());

            service.verify(transaction("9", "0000000000000009"));

            verify(accountRepository, never()).findById(any());
        }

        @Test
        @DisplayName("an empty account result takes the invalid-key arm and does not throw")
        void absentAccountIsTheNotFoundPath() {
            when(cardCrossReferenceRepository.findById("0000000000000001"))
                    .thenReturn(Optional.of(crossReference("0000000000000001", "00000000001")));
            when(accountRepository.findById("00000000001")).thenReturn(Optional.empty());

            DailyTransactionReadService.DailyTransactionVerification verification =
                    service.verify(transaction("1", "0000000000000001"));

            assertAll(
                    () -> assertThat(verification.cardVerified()).isTrue(),
                    () -> assertThat(verification.accountLookupAttempted()).isTrue(),
                    () -> assertThat(verification.accountFound()).isFalse(),
                    () -> assertThat(verification.acctReadStatus())
                            .isEqualTo(ORACLE_READ_STATUS_INVALID_KEY),
                    () -> assertThat(verification.xrefAcctId()).isEqualTo("00000000001"));
        }

        @Test
        @DisplayName("a resolvable card reports both reads successful")
        void resolvableCardReportsBothReadsSuccessful() {
            givenResolvable("0000000000000001", "00000000001");

            DailyTransactionReadService.DailyTransactionVerification verification =
                    service.verify(transaction("1", "0000000000000001"));

            assertAll(
                    () -> assertThat(verification.cardVerified()).isTrue(),
                    () -> assertThat(verification.accountFound()).isTrue(),
                    () -> assertThat(verification.xrefReadStatus())
                            .isEqualTo(ORACLE_READ_STATUS_OK),
                    () -> assertThat(verification.acctReadStatus()).isEqualTo(ORACLE_READ_STATUS_OK),
                    () -> assertThat(verification.afterEndOfFile()).isFalse());
        }

        /**
         * A key of spaces cannot match in the legacy program, and an absent key cannot be handed to a
         * keyed read here, so neither reaches the repository at all.
         */
        @Test
        @DisplayName("an absent card number takes the invalid-key arm without reaching the repository")
        void absentKeyDoesNotReachTheRepository() {
            DailyTransactionReadService.DailyTransactionVerification verification =
                    service.verify(transaction("1", "   "));

            assertAll(
                    () -> assertThat(verification.cardVerified()).isFalse(),
                    () -> verify(cardCrossReferenceRepository, never()).findById(any()));
        }

        /**
         * A cross-reference row whose account identifier is blank names no account, so the account
         * paragraph takes its invalid-key arm without issuing a keyed read on an unmatchable key.
         */
        @Test
        @DisplayName("a blank account identifier takes the invalid-key arm without a keyed read")
        void blankAccountIdentifierTakesTheInvalidKeyArm() {
            when(cardCrossReferenceRepository.findById("0000000000000001"))
                    .thenReturn(Optional.of(crossReference("0000000000000001", "   ")));

            DailyTransactionReadService.DailyTransactionVerification verification =
                    service.verify(transaction("1", "0000000000000001"));

            assertAll(
                    () -> assertThat(verification.cardVerified()).isTrue(),
                    () -> assertThat(verification.accountFound()).isFalse(),
                    () -> assertThat(verification.acctReadStatus())
                            .isEqualTo(ORACLE_READ_STATUS_INVALID_KEY),
                    () -> verify(accountRepository, never()).findById(any()));
        }

        @Test
        @DisplayName("a null record behaves as an unfilled record area rather than failing")
        void nullRecordIsToleratedAsAnUnfilledRecordArea() {
            DailyTransactionReadService.DailyTransactionVerification verification =
                    service.verify(null);

            assertAll(
                    () -> assertThat(verification.cardVerified()).isFalse(),
                    () -> assertThat(verification.dalytranId()).isNull(),
                    () -> assertThat(verification.dalytranCardNum()).isNull());
        }
    }

    @Nested
    @DisplayName("Every one of the eighteen paragraphs is reached from the driving operation")
    final class ParagraphsAreReachable {

        @Test
        @DisplayName("all six files are opened and all six closed, in one pass")
        void allTwelveOpenAndCloseParagraphsRun() {
            givenResolvable("0000000000000001", "00000000001");

            service.execute(List.of(transaction("1", "0000000000000001")));

            // The three files this layer holds a gateway for are probed twice each: once by the open
            // paragraph and once by the close. The three the member opens but never reads have no
            // gateway to probe.
            assertAll(
                    () -> verify(dailyTransactionRepository, times(2)).count(),
                    () -> verify(cardCrossReferenceRepository, times(2)).count(),
                    () -> verify(accountRepository, times(2)).count());
        }

        @Test
        @DisplayName("the no-argument form scans the input ordered by record identity ascending")
        void noArgumentFormScansWithAnExplicitAscendingSort() {
            when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of());
            when(cardCrossReferenceRepository.findById(any())).thenReturn(Optional.empty());

            service.execute();

            ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
            verify(dailyTransactionRepository).findAll(sort.capture());
            assertThat(sort.getValue())
                    .isEqualTo(Sort.by(Sort.Direction.ASC, ORACLE_SORT_PROPERTY));
        }

        @Test
        @DisplayName("the read, cross-reference and account paragraphs all run for each record")
        void readAndBothLookupParagraphsRunPerRecord() {
            givenResolvable("0000000000000001", "00000000001");

            service.execute(List.of(transaction("1", "0000000000000001")));

            assertAll(
                    // Twice: once for the record itself and once for the trailing pass the loop makes
                    // after end of file over the same record area.
                    () -> verify(cardCrossReferenceRepository, times(2))
                            .findById("0000000000000001"),
                    () -> verify(accountRepository, times(2)).findById("00000000001"));
        }
    }

    @Nested
    @DisplayName("Constructor refuses a missing collaborator")
    final class ConstructorContract {

        @Test
        @DisplayName("each of the four collaborators is required")
        void everyCollaboratorIsRequired() {
            AbendService abend = new AbendService();
            assertAll(
                    () -> assertThatThrownBy(() -> new DailyTransactionReadService(
                            null, cardCrossReferenceRepository, accountRepository, abend))
                            .isInstanceOf(NullPointerException.class),
                    () -> assertThatThrownBy(() -> new DailyTransactionReadService(
                            dailyTransactionRepository, null, accountRepository, abend))
                            .isInstanceOf(NullPointerException.class),
                    () -> assertThatThrownBy(() -> new DailyTransactionReadService(
                            dailyTransactionRepository, cardCrossReferenceRepository, null, abend))
                            .isInstanceOf(NullPointerException.class),
                    () -> assertThatThrownBy(() -> new DailyTransactionReadService(
                            dailyTransactionRepository, cardCrossReferenceRepository,
                            accountRepository, null))
                            .isInstanceOf(NullPointerException.class));
        }

        @Test
        @DisplayName("the supplied-source form refuses a null source")
        void suppliedSourceFormRefusesNull() {
            assertThatThrownBy(() -> service.execute(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("The result record refuses values it cannot have produced")
    final class ResultContract {

        @Test
        @DisplayName("negative counts are rejected")
        void negativeCountsAreRejected() {
            assertThatThrownBy(() -> new DailyTransactionReadService.DailyTransactionReadResult(
                    -1, 0, 0, 0, 0, List.of(), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recordsRead");
        }

        @Test
        @DisplayName("more records verified than read is rejected")
        void moreVerifiedThanReadIsRejected() {
            assertThatThrownBy(() -> new DailyTransactionReadService.DailyTransactionReadResult(
                    1, 2, 2, 0, 0, List.of(), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recordsVerified");
        }

        @Test
        @DisplayName("fewer verification passes than records verified is rejected")
        void fewerPassesThanVerifiedIsRejected() {
            assertThatThrownBy(() -> new DailyTransactionReadService.DailyTransactionReadResult(
                    2, 2, 1, 0, 0, List.of(), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("verificationPasses");
        }

        @Test
        @DisplayName("a null verification list is rejected")
        void nullVerificationListIsRejected() {
            assertThatThrownBy(() -> new DailyTransactionReadService.DailyTransactionReadResult(
                    0, 0, 0, 0, 0, null, 0))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("an unattempted account read never reports the account as found")
        void unattemptedAccountReadIsNotFound() {
            DailyTransactionReadService.DailyTransactionVerification verification =
                    new DailyTransactionReadService.DailyTransactionVerification(
                            "1", "0000000000000001", null, ORACLE_READ_STATUS_INVALID_KEY,
                            ORACLE_READ_STATUS_OK, false, false);

            assertAll(
                    () -> assertThat(verification.cardVerified()).isFalse(),
                    () -> assertThat(verification.accountFound()).isFalse());
        }
    }

    /**
     * The diagnostics, captured from both this service and the abend service through one appender so
     * that the relative order of their events is the order they were emitted in.
     */
    @Nested
    @DisplayName("Diagnostics: emitted in the legacy order, and before any abend")
    final class Diagnostics {

        private Logger serviceLogger;

        private Logger abendLogger;

        private ListAppender<ILoggingEvent> recorder;

        private Level originalServiceLevel;

        private Level originalAbendLevel;

        @BeforeEach
        void attachRecorder() {
            serviceLogger = (Logger) LoggerFactory.getLogger(DailyTransactionReadService.class);
            abendLogger = (Logger) LoggerFactory.getLogger(AbendService.class);
            originalServiceLevel = serviceLogger.getLevel();
            originalAbendLevel = abendLogger.getLevel();
            // Set both levels explicitly rather than inheriting whatever the surrounding
            // configuration happens to hold, so these tests assert the service's ordering and not
            // the ambient logging setup.
            serviceLogger.setLevel(Level.TRACE);
            abendLogger.setLevel(Level.TRACE);
            recorder = new ListAppender<>();
            recorder.setContext(serviceLogger.getLoggerContext());
            recorder.start();
            serviceLogger.addAppender(recorder);
            abendLogger.addAppender(recorder);
        }

        @AfterEach
        void detachRecorder() {
            serviceLogger.detachAppender(recorder);
            abendLogger.detachAppender(recorder);
            recorder.stop();
            serviceLogger.setLevel(originalServiceLevel);
            abendLogger.setLevel(originalAbendLevel);
        }

        private List<String> recorded() {
            return recorder.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }

        private int indexOfContaining(final String fragment) {
            List<String> messages = recorded();
            for (int index = 0; index < messages.size(); index++) {
                if (messages.get(index).contains(fragment)) {
                    return index;
                }
            }
            return -1;
        }

        @Test
        @DisplayName("the run is bracketed by the start and end literals")
        void startAndEndLiteralsBracketTheRun() {
            givenResolvable("0000000000000001", "00000000001");

            service.execute(List.of(transaction("1", "0000000000000001")));

            assertAll(
                    () -> assertThat(recorded()).contains(ORACLE_START, ORACLE_END),
                    () -> assertThat(indexOfContaining(ORACLE_START))
                            .isLessThan(indexOfContaining(ORACLE_END)));
        }

        @Test
        @DisplayName("the successful keyed reads report their own literals")
        void successfulReadsReportTheirLiterals() {
            givenResolvable("0000000000000001", "00000000001");

            service.verify(transaction("1", "0000000000000001"));

            assertThat(recorded())
                    .contains(ORACLE_SUCCESSFUL_READ_OF_XREF, ORACLE_SUCCESSFUL_READ_OF_ACCOUNT);
        }

        @Test
        @DisplayName("the invalid-key arms report their own literals")
        void invalidKeyArmsReportTheirLiterals() {
            when(cardCrossReferenceRepository.findById(any())).thenReturn(Optional.empty());

            service.verify(transaction("9", "0000000000000009"));

            assertThat(recorded()).contains(ORACLE_INVALID_CARD_FOR_XREF);
        }

        @Test
        @DisplayName("the account invalid-key arm reports both its literal and the not-found message")
        void accountInvalidKeyArmReportsBothMessages() {
            when(cardCrossReferenceRepository.findById("0000000000000001"))
                    .thenReturn(Optional.of(crossReference("0000000000000001", "00000000001")));
            when(accountRepository.findById("00000000001")).thenReturn(Optional.empty());

            service.verify(transaction("1", "0000000000000001"));

            assertAll(
                    () -> assertThat(recorded()).contains(ORACLE_INVALID_ACCOUNT),
                    () -> assertThat(recorded()).contains("ACCOUNT 00000000001 NOT FOUND"));
        }

        /**
         * The decisive ordering assertion. The failing open displays its literal, then the raw
         * two-character status, and only then aborts; the abend is caught here, so every event in the
         * recorder was emitted before the raise, and their order in the recorder is their order of
         * emission.
         */
        @Test
        @DisplayName("a failing open logs its literal, then the raw status, then abends - in that order")
        void failingOpenLogsLiteralThenRawStatusThenAbends() {
            when(dailyTransactionRepository.count())
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.execute(List.of()));

            int literal = indexOfContaining(ORACLE_ERROR_OPENING_DALYTRAN);
            int status = indexOfContaining(ORACLE_STATUS_PREFIX);
            int abending = indexOfContaining(ORACLE_ABENDING);
            assertAll(
                    () -> assertThat(literal).isNotNegative(),
                    () -> assertThat(status).isGreaterThan(literal),
                    () -> assertThat(abending).isGreaterThan(status));
        }

        @Test
        @DisplayName("the status the abend reports is the raw two-character code, not a rendered value")
        void abendReportsTheRawTwoCharacterStatus() {
            when(dailyTransactionRepository.count())
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.execute(List.of()));

            assertThat(recorded())
                    .anySatisfy(message -> assertThat(message)
                            .contains(ORACLE_STATUS_PREFIX)
                            .contains("fileStatus=" + ORACLE_PERMANENT_ERROR_STATUS));
        }

        @Test
        @DisplayName("the abend names the member as culprit and carries the batch abend code")
        void abendNamesTheMemberAndCarriesTheBatchCode() {
            when(dailyTransactionRepository.count())
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));

            assertThatThrownBy(() -> service.execute(List.of()))
                    .isInstanceOf(AbendException.class);

            assertThat(recorded())
                    .anySatisfy(message -> assertThat(message)
                            .contains(ORACLE_ABENDING)
                            .contains("abendCode=" + ORACLE_BATCH_ABEND_CODE)
                            .contains("culprit=" + ORACLE_CULPRIT));
        }

        @Test
        @DisplayName("a failing cross-reference open reports the cross-reference literal")
        void failingCrossReferenceOpenReportsItsOwnLiteral() {
            when(cardCrossReferenceRepository.count())
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.execute(List.of()));

            assertThat(recorded()).contains(ORACLE_ERROR_OPENING_XREF);
        }

        /**
         * The daily-transaction close carries the customer file's literal - a copy of the paragraph
         * that follows it in the source. Reaching it requires an open that succeeds and a close that
         * does not, which is why the probe is made to fail only on its second call.
         */
        @Test
        @DisplayName("the daily-transaction close reports the customer file's literal, as the source does")
        void dalytranCloseCarriesTheCustomerFileLiteral() {
            when(dailyTransactionRepository.count())
                    .thenReturn(0L)
                    .thenThrow(new DataAccessResourceFailureException("gateway unavailable"));
            when(cardCrossReferenceRepository.findById(any())).thenReturn(Optional.empty());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.execute(List.of()));

            assertAll(
                    () -> assertThat(recorded()).contains(ORACLE_ERROR_CLOSING_CUSTOMER),
                    () -> assertThat(recorded()).doesNotContain("ERROR CLOSING DAILY TRANSACTION FILE"));
        }

        /**
         * No rounding clause exists anywhere in the estate, so a store into a two-decimal field
         * truncates toward zero. Four fractional digits must therefore lose two rather than round up.
         */
        @Test
        @DisplayName("the record diagnostic truncates a surplus fraction rather than rounding it")
        void recordDiagnosticTruncatesRatherThanRounds() {
            givenResolvable("0000000000000001", "00000000001");

            service.execute(List.of(
                    transaction("1", "0000000000000001", new BigDecimal("12.3499"))));

            assertAll(
                    () -> assertThat(recorded()).anySatisfy(message -> assertThat(message)
                            .contains("amt=12.34")),
                    () -> assertThat(recorded()).noneSatisfy(message -> assertThat(message)
                            .contains("amt=12.35")));
        }

        @Test
        @DisplayName("the record diagnostic carries the prefixed daily-transaction merchant fields")
        void recordDiagnosticCarriesThePrefixedMerchantFields() {
            givenResolvable("0000000000000001", "00000000001");

            service.execute(List.of(transaction("1", "0000000000000001")));

            assertThat(recorded()).anySatisfy(message -> assertThat(message)
                    .contains("DALYTRAN-RECORD")
                    .contains("merchantId=123456789")
                    .contains("merchantName=MERCHANT NAME")
                    .contains("merchantCity=MERCHANT CITY")
                    .contains("merchantZip=12345")
                    .contains("cardNum=0000000000000001"));
        }

        @Test
        @DisplayName("the record diagnostic is emitted once per record read and not for the trailing pass")
        void recordDiagnosticIsEmittedOncePerRecordRead() {
            givenResolvable("0000000000000001", "00000000001");
            givenResolvable("0000000000000002", "00000000002");

            service.execute(List.of(
                    transaction("1", "0000000000000001"),
                    transaction("2", "0000000000000002")));

            assertThat(recorded().stream().filter(m -> m.contains("DALYTRAN-RECORD")).count())
                    .isEqualTo(2L);
        }

        @Test
        @DisplayName("the record diagnostic is skipped entirely when the level would discard it")
        void recordDiagnosticIsSkippedWhenTheLevelWouldDiscardIt() {
            serviceLogger.setLevel(Level.WARN);
            givenResolvable("0000000000000001", "00000000001");

            service.execute(List.of(transaction("1", "0000000000000001")));

            assertThat(recorded()).noneSatisfy(message -> assertThat(message)
                    .contains("DALYTRAN-RECORD"));
        }

        @Test
        @DisplayName("an absent amount is reported as absent rather than failing the diagnostic")
        void absentAmountIsReportedAsAbsent() {
            givenResolvable("0000000000000001", "00000000001");

            service.execute(List.of(transaction("1", "0000000000000001", null)));

            assertThat(recorded()).anySatisfy(message -> assertThat(message)
                    .contains("DALYTRAN-RECORD")
                    .contains("amt=(none)"));
        }

        /**
         * A source element carrying no record image is neither a record nor end of file, so the read
         * paragraph has only its error arm left for it - and that arm displays, reports the status and
         * abends, exactly as a failing read does.
         */
        @Test
        @DisplayName("a source element with no record image takes the read paragraph's error arm")
        void elementWithoutARecordImageTakesTheReadErrorArm() {
            List<DailyTransaction> input = java.util.Arrays.asList((DailyTransaction) null);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.execute(input));

            assertThat(recorded()).contains("ERROR READING DAILY TRANSACTION FILE");
        }
    }
}
