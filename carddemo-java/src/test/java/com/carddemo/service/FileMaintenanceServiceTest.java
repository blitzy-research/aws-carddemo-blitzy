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
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;

import com.carddemo.domain.Account;
import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.AccountScanRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CardCrossReferenceScanRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardScanRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@code FileMaintenanceService}, the four member-owned sequential readers, the neutral
 * category-balance unload and the two-level I/O status model that the batch estate shares.
 *
 * <p>Four legacy members converge on the class under test: {@code app/cbl/CBACT01C.cbl} with six
 * paragraphs, and {@code app/cbl/CBACT02C.cbl}, {@code app/cbl/CBACT03C.cbl} and
 * {@code app/cbl/CBCUS01C.cbl} with five apiece. The first is the exemplar: lines 92-116 normalise the raw
 * two-byte file status into a coarser result - success to zero, end of file to sixteen, anything else to
 * twelve - and branch on that coarse value rather than on the raw code, a pattern nine further batch
 * members repeat and which is referenced on roughly 223 lines of the estate.
 *
 * <p><strong>The decisive property is that end of file is not an error.</strong> Every sequential read loop
 * in the estate terminates on the end-of-file arm, so a translation that folded it into the error arm would
 * turn every successful job into an abend. The tests below therefore assert both halves of that separation
 * explicitly: a status of end of file must end a loop and return a summary, while any other non-success
 * status must reach the abend path instead.
 *
 * <p><strong>The second decisive property is ordering.</strong> The legacy sequence at a failing I/O site is
 * to display the member's own failure literal, then the raw status, and only then to abend. These tests
 * capture the log and assert the index of each record, because an implementation that logged from a
 * {@code catch} block would satisfy every other assertion here and still lose the diagnostic an operator
 * needs while the run is failing.
 *
 * <p>The abend service is the real one rather than a double, so the log-then-raise ordering is verified end
 * to end across both classes. The repositories are doubles, because the point of these tests is what the
 * reader does with a status, not whether a database is reachable.
 *
 * <p>Migrated from the AWS CardDemo mainframe estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("FileMaintenanceService: four member readers and one unload over one status model")
final class FileMaintenanceServiceTest {

    /** The raw status a failed repository operation reports; source-observed, unlike 22 and 35. */
    private static final String PERMANENT_ERROR = "31";

    /** The rendered status field a permanent error produces, appended to the display literal. */
    private static final String PERMANENT_ERROR_LINE = "FILE STATUS IS: NNNN0031";

    /** The banner the abend paragraph displays immediately before it aborts. */
    private static final String ABENDING_PROGRAM = "ABENDING PROGRAM";

    private AccountRepository accountRepository;

    private AccountScanRepository accountScanRepository;

    private CardRepository cardRepository;

    private CardScanRepository cardScanRepository;

    private CardCrossReferenceRepository cardCrossReferenceRepository;

    private CardCrossReferenceScanRepository cardCrossReferenceScanRepository;

    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    private CustomerRepository customerRepository;

    private FileMaintenanceService service;

    private Logger serviceLogger;

    private Logger abendLogger;

    private ListAppender<ILoggingEvent> appender;

    private Level previousServiceLevel;

    @BeforeEach
    void setUp() {
        this.accountRepository = mock(AccountRepository.class);
        this.accountScanRepository = (cursor, limit) ->
                afterCursor(
                        this.accountRepository.findAll(
                                Sort.by(Sort.Direction.ASC, "acctId")),
                        cursor, limit.max(), Account::getAcctId);
        this.cardRepository = mock(CardRepository.class);
        this.cardScanRepository = (cursor, limit) ->
                afterCursor(
                        this.cardRepository.findAll(
                                Sort.by(Sort.Direction.ASC, "cardNum")),
                        cursor, limit.max(), Card::getCardNum);
        this.cardCrossReferenceRepository = mock(CardCrossReferenceRepository.class);
        this.cardCrossReferenceScanRepository = (cursor, limit) ->
                afterCursor(
                        this.cardCrossReferenceRepository.findAll(
                                Sort.by(Sort.Direction.ASC, "xrefCardNum")),
                        cursor, limit.max(), CardCrossReference::getXrefCardNum);
        this.transactionCategoryBalanceRepository = mock(
                TransactionCategoryBalanceRepository.class, invocation -> {
                    if (invocation.getMethod().getName().equals("findAfterKey")) {
                        final String cursor = (String) invocation.getArgument(0)
                                + invocation.getArgument(1) + invocation.getArgument(2);
                        final org.springframework.data.domain.Pageable page =
                                invocation.getArgument(3);
                        return afterCursor(
                                this.transactionCategoryBalanceRepository.findAll(
                                        Sort.by(Sort.Direction.ASC, "trancatAcctId",
                                                "trancatTypeCd", "trancatCd")),
                                cursor, page.getPageSize(),
                                balance -> balance.getTrancatAcctId()
                                        + balance.getTrancatTypeCd() + balance.getTrancatCd());
                    }
                    return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                });
        this.customerRepository = mock(CustomerRepository.class, invocation -> {
            if (invocation.getMethod().getName()
                    .equals("findByCustIdGreaterThanOrderByCustIdAsc")) {
                final String cursor = invocation.getArgument(0);
                final org.springframework.data.domain.Limit limit = invocation.getArgument(1);
                return afterCursor(
                        this.customerRepository.findAll(
                                Sort.by(Sort.Direction.ASC, "custId")),
                        cursor, limit.max(), Customer::getCustId);
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        this.service = new FileMaintenanceService(
                this.accountRepository, this.accountScanRepository,
                this.cardRepository, this.cardScanRepository,
                this.cardCrossReferenceRepository, this.cardCrossReferenceScanRepository,
                this.transactionCategoryBalanceRepository, this.customerRepository,
                new AbendService());

        this.appender = new ListAppender<>();
        this.appender.start();
        this.serviceLogger = (Logger) LoggerFactory.getLogger(FileMaintenanceService.class);
        this.abendLogger = (Logger) LoggerFactory.getLogger(AbendService.class);
        this.previousServiceLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.TRACE);
        this.serviceLogger.addAppender(this.appender);
        this.abendLogger.addAppender(this.appender);
    }

    private static <T> List<T> afterCursor(
            final List<T> rows,
            final String cursor,
            final int limit,
            final Function<T, String> keyExtractor) {
        return rows.stream()
                .filter(row -> keyExtractor.apply(row).compareTo(cursor) > 0)
                .limit(limit)
                .toList();
    }

    @AfterEach
    void tearDown() {
        this.serviceLogger.detachAppender(this.appender);
        this.abendLogger.detachAppender(this.appender);
        this.serviceLogger.setLevel(this.previousServiceLevel);
        this.appender.stop();
    }

    private List<String> messages() {
        List<String> rendered = new ArrayList<>();
        for (ILoggingEvent event : this.appender.list) {
            rendered.add(event.getFormattedMessage());
        }
        return rendered;
    }

    private int firstIndexContaining(final String fragment) {
        List<String> rendered = messages();
        for (int index = 0; index < rendered.size(); index++) {
            if (rendered.get(index).contains(fragment)) {
                return index;
            }
        }
        return -1;
    }

    private static Account account(final String acctId) {
        return new Account(acctId, "Y", new BigDecimal("1.05"), new BigDecimal("2.00"),
                new BigDecimal("3.00"), "2020-01-01", "2030-01-01", "2025-01-01",
                new BigDecimal("4.00"), new BigDecimal("5.00"), "12345", "GROUP01");
    }

    private static CardCrossReference crossReference(final String cardNumber) {
        return new CardCrossReference(cardNumber, "000000001", "00000000001");
    }

    private static DataAccessResourceFailureException unreachable() {
        return new DataAccessResourceFailureException("the cluster could not be reached");
    }

    @Nested
    @DisplayName("end of file terminates a read loop normally and is never routed to the error arm")
    class EndOfFileIsNormalCompletion {

        @Test
        @DisplayName("a populated cluster is read to end of file and reports exactly what it read")
        void populatedClusterIsReadToEndOfFile() {
            when(accountRepository.count()).thenReturn(3L);
            when(accountRepository.findAll(any(Sort.class))).thenReturn(List.of(
                    account("00000000001"), account("00000000002"), account("00000000003")));

            FileMaintenanceService.FileReadSummary summary = service.readAccountFile();

            assertAll(
                    () -> assertThat(summary.programName()).isEqualTo("CBACT01C"),
                    () -> assertThat(summary.resourceName()).isEqualTo("ACCTFILE"),
                    () -> assertThat(summary.recordsRead()).isEqualTo(3L),
                    () -> assertThat(summary.terminalFileStatus())
                            .isEqualTo(FileStatusException.STATUS_END_OF_FILE),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> assertThat(messages()).noneMatch(line -> line.contains(ABENDING_PROGRAM)));
        }

        @Test
        @DisplayName("an empty cluster still ends at end of file, with a count of zero and no abend")
        void emptyClusterEndsAtEndOfFileWithoutAbending() {
            when(accountRepository.count()).thenReturn(0L);
            when(accountRepository.findAll(any(Sort.class))).thenReturn(List.of());

            assertThatNoException().isThrownBy(() -> {
                FileMaintenanceService.FileReadSummary summary = service.readAccountFile();
                assertThat(summary.recordsRead()).isZero();
                assertThat(summary.endedAtEndOfFile()).isTrue();
            });
        }

        @Test
        @DisplayName("the opening and closing banners are emitted around the loop, naming the member")
        void openingAndClosingBannersSurroundTheLoop() {
            when(accountRepository.count()).thenReturn(1L);
            when(accountRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(account("00000000001")));

            service.readAccountFile();

            int start = firstIndexContaining("START OF EXECUTION OF PROGRAM CBACT01C");
            int end = firstIndexContaining("END OF EXECUTION OF PROGRAM CBACT01C");
            assertAll(
                    () -> assertThat(start).isNotNegative(),
                    () -> assertThat(end).isNotNegative(),
                    () -> assertThat(start).isLessThan(end));
        }
    }

    @Nested
    @DisplayName("any other status reaches the abend path, and the status is recorded before the raise")
    class ErrorArmAbendsAfterEmitting {

        @Test
        @DisplayName("an unreachable cluster abends on the open arm and never attempts the retrieval")
        void openArmAbendsWithoutRetrieving() {
            when(accountRepository.count()).thenThrow(unreachable());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readAccountFile())
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.culprit()).isEqualTo("CBACT01C"),
                            () -> assertThat(abend.reason()).isEqualTo("ERROR OPENING ACCTFILE"),
                            () -> assertThat(abend.code())
                                    .isEqualTo(AbendException.BATCH_ABEND_CODE)));

            verify(accountRepository, never()).findAll(any(Sort.class));
        }

        @Test
        @DisplayName("a failed retrieval abends on the read arm, under the read paragraph's own literal")
        void readArmAbendsUnderItsOwnLiteral() {
            when(accountRepository.count()).thenReturn(1L);
            when(accountRepository.findAll(any(Sort.class))).thenThrow(unreachable());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readAccountFile())
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("ERROR READING ACCOUNT FILE"));

            assertThat(messages()).anyMatch(line -> line.contains("operation=READING"));
        }

        @Test
        @DisplayName("literal, then raw status, then abend: the legacy order, asserted by log position")
        void diagnosticsPrecedeTheRaiseInLegacyOrder() {
            when(accountRepository.count()).thenThrow(unreachable());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readAccountFile());

            int literal = firstIndexContaining("ERROR OPENING ACCTFILE");
            int statusLine = firstIndexContaining(PERMANENT_ERROR_LINE);
            int abending = firstIndexContaining(ABENDING_PROGRAM);
            assertAll(
                    () -> assertThat(literal).isNotNegative(),
                    () -> assertThat(statusLine).isNotNegative(),
                    () -> assertThat(abending).isNotNegative(),
                    () -> assertThat(literal).isLessThan(statusLine),
                    () -> assertThat(statusLine).isLessThan(abending));
        }

        @Test
        @DisplayName("both levels of the status model are recorded: the raw code and the coarse result")
        void bothLevelsOfTheStatusModelAreRecorded() {
            when(accountRepository.count()).thenThrow(unreachable());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readAccountFile());

            assertAll(
                    () -> assertThat(messages()).anyMatch(line -> line.contains("applResult=12")),
                    () -> assertThat(messages()).anyMatch(line -> line.contains("applResult=8")),
                    () -> assertThat(messages()).anyMatch(line -> line.contains("fileStatus=31")
                            && line.contains("statusName=PERMANENT_ERROR")),
                    () -> assertThat(messages()).anyMatch(line -> line.contains(
                            "failureChain=DataAccessResourceFailureException")),
                    () -> assertThat(messages()).noneMatch(
                            line -> line.contains("the cluster could not be reached")),
                    () -> assertThat(this.noThrowableWasHandedToTheAppender()).isTrue());
        }

        private boolean noThrowableWasHandedToTheAppender() {
            for (ILoggingEvent event : appender.list) {
                if (event.getThrowableProxy() != null) {
                    return false;
                }
            }
            return true;
        }

        @Test
        @DisplayName("the two statuses that are not errors cannot be raised as a file failure at all")
        void successAndEndOfFileCannotBecomeAFileFailure() {
            assertAll(
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new FileStatusException(
                                    FileStatusException.STATUS_SUCCESS, "READING", "ACCTFILE")),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new FileStatusException(
                                    FileStatusException.STATUS_END_OF_FILE, "READING", "ACCTFILE")),
                    () -> assertThatNoException().isThrownBy(() -> new FileStatusException(
                            PERMANENT_ERROR, "READING", "ACCTFILE")));
        }
    }

    @Nested
    @DisplayName("the status-display paragraph: one Java method for both legacy spellings, both arms")
    class StatusDisplayParagraph {

        @ParameterizedTest
        @CsvSource({
            "31, FILE STATUS IS: NNNN0031",
            "10, FILE STATUS IS: NNNN0010",
            "77, FILE STATUS IS: NNNN0077",
        })
        @DisplayName("a two-digit status is zero-filled and occupies the last two display positions")
        void numericArmZeroFillsTheField(final String rawStatus, final String expected) {
            service.displayIoStatus(rawStatus, "READING", "ACCTFILE");

            assertThat(messages()).singleElement().asString().contains(expected);
        }

        @ParameterizedTest
        @CsvSource({
            "93, FILE STATUS IS: NNNN9051",
            "9A, FILE STATUS IS: NNNN9065",
            "A1, FILE STATUS IS: NNNNA049",
        })
        @DisplayName("the implementor-defined and non-numeric arm renders the second byte's value")
        void specialArmRendersTheSecondBytesBinaryValue(final String rawStatus, final String expected) {
            service.displayIoStatus(rawStatus, "READING", "ACCTFILE");

            assertThat(messages()).singleElement().asString().contains(expected);
        }

        @Test
        @DisplayName("a status the declared vocabulary does not hold is reported rather than rejected")
        void undeclaredStatusIsReportedNotRejected() {
            service.displayIoStatus("77", "READING", "ACCTFILE");

            assertThat(messages()).singleElement().asString()
                    .contains("statusName=(outside declared vocabulary)");
        }

        @Test
        @DisplayName("an absent status and absent context render as markers, and nothing is thrown")
        void absentValuesRenderAsMarkers() {
            assertThatNoException().isThrownBy(() -> service.displayIoStatus(null, null, null));

            assertThat(messages()).singleElement().asString()
                    .contains("FILE STATUS IS: NNNN????")
                    .contains("statusName=(none)")
                    .contains("operation=(none)")
                    .contains("resource=(none)");
        }

        @Test
        @DisplayName("a blank context value renders as a marker rather than as an empty field")
        void blankContextRendersAsAMarker() {
            service.displayIoStatus(PERMANENT_ERROR, "   ", "");

            assertThat(messages()).singleElement().asString()
                    .contains("operation=(none)")
                    .contains("resource=(none)");
        }

        @Test
        @DisplayName("a status of the wrong length could not have reached the legacy field and says so")
        void wrongLengthStatusRendersTheMarker() {
            service.displayIoStatus("1", "READING", "ACCTFILE");

            assertThat(messages()).singleElement().asString().contains("FILE STATUS IS: NNNN????");
        }
    }

    @Nested
    @DisplayName("key order is supplied explicitly, because no repository declares one")
    class KeyOrderIsSupplied {

        @Test
        @DisplayName("the account reader orders by the eleven-character account identifier")
        void accountReaderOrdersByAccountIdentifier() {
            when(accountRepository.count()).thenReturn(0L);
            when(accountRepository.findAll(any(Sort.class))).thenReturn(List.of());

            service.readAccountFile();

            ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
            verify(accountRepository).findAll(sort.capture());
            assertThat(sort.getValue()).isEqualTo(Sort.by(Sort.Direction.ASC, "acctId"));
        }

        @Test
        @DisplayName("the card reader orders by the sixteen-character card number")
        void cardReaderOrdersByCardNumber() {
            when(cardRepository.count()).thenReturn(0L);
            when(cardRepository.findAll(any(Sort.class))).thenReturn(List.of());

            service.readCardFile();

            ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
            verify(cardRepository).findAll(sort.capture());
            assertThat(sort.getValue()).isEqualTo(Sort.by(Sort.Direction.ASC, "cardNum"));
        }

        @Test
        @DisplayName("the cross-reference reader orders by the sixteen-character card-number key")
        void crossReferenceReaderOrdersByCardNumber() {
            when(cardCrossReferenceRepository.count()).thenReturn(0L);
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(List.of());

            service.readCardCrossReferenceFile();

            ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
            verify(cardCrossReferenceRepository).findAll(sort.capture());
            assertThat(sort.getValue()).isEqualTo(Sort.by(Sort.Direction.ASC, "xrefCardNum"));
        }

        @Test
        @DisplayName("the category balance reader orders by all three parts of its composite key")
        void categoryBalanceReaderOrdersByTheWholeCompositeKey() {
            when(transactionCategoryBalanceRepository.count()).thenReturn(0L);
            when(transactionCategoryBalanceRepository.findAll(any(Sort.class))).thenReturn(List.of());

            service.readTransactionCategoryBalanceFile();

            ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
            verify(transactionCategoryBalanceRepository).findAll(sort.capture());
            assertThat(sort.getValue()).isEqualTo(
                    Sort.by(Sort.Direction.ASC, "trancatAcctId", "trancatTypeCd", "trancatCd"));
        }

        @Test
        @DisplayName("the customer reader orders by the nine-character customer identifier")
        void customerReaderOrdersByCustomerIdentifier() {
            when(customerRepository.count()).thenReturn(0L);
            when(customerRepository.findAll(any(Sort.class))).thenReturn(List.of());

            service.readCustomerFile();

            ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
            verify(customerRepository).findAll(sort.capture());
            assertThat(sort.getValue()).isEqualTo(Sort.by(Sort.Direction.ASC, "custId"));
        }
    }

    @Nested
    @DisplayName("each reader carries its own member name, DD name and failure literals")
    class EachReaderCarriesItsOwnIdentity {

        @Test
        @DisplayName("the card reader reports its own member and resource")
        void cardReaderReportsItsOwnIdentity() {
            when(cardRepository.count()).thenReturn(1L);
            when(cardRepository.findAll(any(Sort.class))).thenReturn(List.of(
                    new Card("4111111111111111", "00000000001", "987", "JOHN Q PUBLIC",
                            "2030-01-01", "Y")));

            FileMaintenanceService.FileReadSummary summary = service.readCardFile();

            assertAll(
                    () -> assertThat(summary.programName()).isEqualTo("CBACT02C"),
                    () -> assertThat(summary.resourceName()).isEqualTo("CARDFILE"),
                    () -> assertThat(summary.recordsRead()).isEqualTo(1L),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue());
        }

        @Test
        @DisplayName("the cross-reference reader reports CBACT03C and XREFFILE")
        void crossReferenceReaderReportsItsOwnIdentity() {
            when(cardCrossReferenceRepository.count()).thenReturn(1L);
            when(cardCrossReferenceRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(crossReference("4111111111111111")));

            FileMaintenanceService.FileReadSummary summary =
                    service.readCardCrossReferenceFile();

            assertAll(
                    () -> assertThat(summary.programName()).isEqualTo("CBACT03C"),
                    () -> assertThat(summary.resourceName()).isEqualTo("XREFFILE"),
                    () -> assertThat(summary.recordsRead()).isEqualTo(1L),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue());
        }

        @Test
        @DisplayName("the category-balance unload reports the copy step rather than inventing a member")
        void categoryBalanceUnloadReportsItsStepAndResource() {
            when(transactionCategoryBalanceRepository.count()).thenReturn(1L);
            when(transactionCategoryBalanceRepository.findAll(any(Sort.class))).thenReturn(List.of(
                    new TransactionCategoryBalance("00000000001", "01", "0005",
                            new BigDecimal("10.00"))));

            FileMaintenanceService.FileReadSummary summary =
                    service.readTransactionCategoryBalanceFile();

            assertAll(
                    () -> assertThat(summary.programName()).isEqualTo("STEP05R"),
                    () -> assertThat(summary.resourceName()).isEqualTo("TCATBALF"),
                    () -> assertThat(summary.recordsRead()).isEqualTo(1L));
        }

        @Test
        @DisplayName("the customer reader reports its own member and resource")
        void customerReaderReportsItsOwnIdentity() {
            when(customerRepository.count()).thenReturn(1L);
            when(customerRepository.findAll(any(Sort.class))).thenReturn(List.of(customer()));

            FileMaintenanceService.FileReadSummary summary = service.readCustomerFile();

            assertAll(
                    () -> assertThat(summary.programName()).isEqualTo("CBCUS01C"),
                    () -> assertThat(summary.resourceName()).isEqualTo("CUSTFILE"),
                    () -> assertThat(summary.recordsRead()).isEqualTo(1L));
        }

        @Test
        @DisplayName("every reader abends under its own member name and its own open literal")
        void everyReaderAbendsUnderItsOwnLiteral() {
            when(cardRepository.count()).thenThrow(unreachable());
            when(cardCrossReferenceRepository.count()).thenThrow(unreachable());
            when(transactionCategoryBalanceRepository.count()).thenThrow(unreachable());
            when(customerRepository.count()).thenThrow(unreachable());

            assertAll(
                    () -> assertThatExceptionOfType(AbendException.class)
                            .isThrownBy(() -> service.readCardFile())
                            .satisfies(abend -> {
                                assertThat(abend.culprit()).isEqualTo("CBACT02C");
                                assertThat(abend.reason()).isEqualTo("ERROR OPENING CARDFILE");
                            }),
                    () -> assertThatExceptionOfType(AbendException.class)
                            .isThrownBy(() -> service.readCardCrossReferenceFile())
                            .satisfies(abend -> {
                                assertThat(abend.culprit()).isEqualTo("CBACT03C");
                                assertThat(abend.reason()).isEqualTo("ERROR OPENING XREFFILE");
                            }),
                    () -> assertThatExceptionOfType(AbendException.class)
                            .isThrownBy(() -> service.readTransactionCategoryBalanceFile())
                            .satisfies(abend -> {
                                assertThat(abend.culprit()).isEqualTo("STEP05R");
                                assertThat(abend.reason()).isEqualTo("ERROR OPENING TCATBALF");
                            }),
                    () -> assertThatExceptionOfType(AbendException.class)
                            .isThrownBy(() -> service.readCustomerFile())
                            .satisfies(abend -> {
                                assertThat(abend.culprit()).isEqualTo("CBCUS01C");
                                assertThat(abend.reason()).isEqualTo("ERROR OPENING CUSTFILE");
                            }));
        }

        @Test
        @DisplayName("every reader abends under its own read literal when the retrieval fails")
        void everyReaderAbendsUnderItsOwnReadLiteral() {
            when(cardRepository.count()).thenReturn(1L);
            when(cardRepository.findAll(any(Sort.class))).thenThrow(unreachable());
            when(cardCrossReferenceRepository.count()).thenReturn(1L);
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenThrow(unreachable());
            when(transactionCategoryBalanceRepository.count()).thenReturn(1L);
            when(transactionCategoryBalanceRepository.findAll(any(Sort.class)))
                    .thenThrow(unreachable());
            when(customerRepository.count()).thenReturn(1L);
            when(customerRepository.findAll(any(Sort.class))).thenThrow(unreachable());

            assertAll(
                    () -> assertThatExceptionOfType(AbendException.class)
                            .isThrownBy(() -> service.readCardFile())
                            .satisfies(abend -> assertThat(abend.reason())
                                    .isEqualTo("ERROR READING CARDFILE")),
                    () -> assertThatExceptionOfType(AbendException.class)
                            .isThrownBy(() -> service.readCardCrossReferenceFile())
                            .satisfies(abend -> assertThat(abend.reason())
                                    .isEqualTo("ERROR READING XREFFILE")),
                    () -> assertThatExceptionOfType(AbendException.class)
                            .isThrownBy(() -> service.readTransactionCategoryBalanceFile())
                            .satisfies(abend -> assertThat(abend.reason())
                                    .isEqualTo("ERROR READING TCATBALF")),
                    () -> assertThatExceptionOfType(AbendException.class)
                            .isThrownBy(() -> service.readCustomerFile())
                            .satisfies(abend -> assertThat(abend.reason())
                                    .isEqualTo("ERROR READING CUSTOMER FILE")));
        }
    }

    @Nested
    @DisplayName("CBACT03C reads XREFFILE with its own observable paragraph behaviour")
    class CrossReferenceReaderFidelity {

        @Test
        @DisplayName("the reader opens, reads to end of file, closes and emits each record twice")
        void readsToEndOfFileAndReproducesTheDoubleEmission() {
            when(cardCrossReferenceRepository.count()).thenReturn(2L);
            when(cardCrossReferenceRepository.findAll(any(Sort.class))).thenReturn(List.of(
                    crossReference("4111111111111111"),
                    crossReference("4111111111111112")));

            FileMaintenanceService.FileReadSummary summary =
                    service.readCardCrossReferenceFile();

            long recordImages = messages().stream()
                    .filter(line -> line.startsWith("recordType=card-cross-reference recordRef="))
                    .count();
            assertAll(
                    () -> assertThat(summary.programName()).isEqualTo("CBACT03C"),
                    () -> assertThat(summary.resourceName()).isEqualTo("XREFFILE"),
                    () -> assertThat(summary.recordsRead()).isEqualTo(2L),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> assertThat(recordImages)
                            .as("line 96 and line 78 each emit every successful record")
                            .isEqualTo(4L),
                    () -> assertThat(messages())
                            .anyMatch(line -> line.contains("operation=CLOSING")
                                    && line.contains("resource=XREFFILE")),
                    () -> assertThat(messages())
                            .anyMatch(line -> line.contains("END OF EXECUTION OF PROGRAM CBACT03C")));
        }

        @Test
        @DisplayName("the two record emissions reveal none of the three cross-reference identifiers")
        void bothRecordEmissionsRemainRedacted() {
            when(cardCrossReferenceRepository.count()).thenReturn(1L);
            when(cardCrossReferenceRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(crossReference("4111111111111111")));

            service.readCardCrossReferenceFile();

            assertAll(
                    () -> assertThat(messages())
                            .filteredOn(line ->
                                    line.startsWith("recordType=card-cross-reference recordRef="))
                            .hasSize(2)
                            .allMatch(line -> line.matches(
                                    "recordType=card-cross-reference "
                                            + "recordRef=\\[REDACTED] ref=[0-9a-f]{24}")),
                    () -> assertThat(messages())
                            .noneMatch(line -> line.contains("4111111111111111")),
                    () -> assertThat(messages())
                            .noneMatch(line -> line.contains("000000001")),
                    () -> assertThat(messages())
                            .noneMatch(line -> line.contains("00000000001")));
        }

        @Test
        @DisplayName("the defensive close arm carries CBACT03C's exact XREFFILE literal")
        void closeArmCarriesTheExactLegacyLiteral() throws ReflectiveOperationException {
            Class<?> cursorType =
                    Class.forName(FileMaintenanceService.class.getName() + "$SequentialCursor");
            Constructor<?> constructor =
                    cursorType.getDeclaredConstructor(String.class, String.class, Supplier.class);
            constructor.setAccessible(true);
            Supplier<List<CardCrossReference>> orderedRead = List::of;
            Object unopenedCursor = constructor.newInstance("CBACT03C", "XREFFILE", orderedRead);
            Method closeMethod =
                    FileMaintenanceService.class.getDeclaredMethod("closeXrefFile", cursorType);
            closeMethod.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(() -> closeMethod.invoke(service, unopenedCursor))
                    .satisfies(failure -> assertThat(failure.getCause())
                            .isInstanceOfSatisfying(AbendException.class, abend -> assertAll(
                                    () -> assertThat(abend.culprit()).isEqualTo("CBACT03C"),
                                    () -> assertThat(abend.reason())
                                            .isEqualTo("ERROR CLOSING XREFFILE"),
                                    () -> assertThat(abend.code())
                                            .isEqualTo(AbendException.BATCH_ABEND_CODE))));
        }
    }

    @Nested
    @DisplayName("the account display paragraph preserves its unit without publishing its fields")
    class AccountDisplayParagraph {

        @Test
        @DisplayName("only a correlation token and the non-sensitive status label are emitted")
        void onlyCorrelationAndStatusAreEmitted() {
            when(accountRepository.count()).thenReturn(1L);
            when(accountRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(account("00000000001")));

            service.readAccountFile();

            List<String> rendered = messages();
            assertAll(
                    () -> assertThat(rendered).anyMatch(line -> line.matches(
                            "recordType=account recordRef=\\[REDACTED] ref=[0-9a-f]{24} "
                                    + "status=Y")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("00000000001")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("1.05")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("2.00")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("3.00")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("2020-01-01")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("2030-01-01")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("2025-01-01")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("4.00")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("5.00")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("GROUP01")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("ACCT-CURR-BAL")));
        }

        @Test
        @DisplayName("the postal field the paragraph does not display is not displayed here either")
        void thePostalFieldIsNotDisplayed() {
            when(accountRepository.count()).thenReturn(1L);
            when(accountRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(account("00000000001")));

            service.readAccountFile();

            assertThat(messages()).noneMatch(line -> line.contains("ACCT-ADDR-ZIP"));
        }

        @Test
        @DisplayName("with the diagnostic level raised, the read still completes and emits no labels")
        void labelsAreOmittedWhenTheDiagnosticLevelExcludesThem() {
            serviceLogger.setLevel(Level.INFO);
            when(accountRepository.count()).thenReturn(1L);
            when(accountRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(account("00000000001")));

            FileMaintenanceService.FileReadSummary summary = service.readAccountFile();

            assertAll(
                    () -> assertThat(summary.recordsRead()).isEqualTo(1L),
                    () -> assertThat(messages()).noneMatch(line -> line.contains("ACCT-ID")));
        }
    }

    @Nested
    @DisplayName("cardholder personal data and card credentials are withheld from the diagnostics")
    class ConfidentialValuesAreWithheld {

        @Test
        @DisplayName("neither the card number nor the verification code reaches the log")
        void cardNumberAndVerificationCodeAreWithheld() {
            when(cardRepository.count()).thenReturn(1L);
            when(cardRepository.findAll(any(Sort.class))).thenReturn(List.of(
                    new Card("4111111111111111", "00000000001", "987", "JOHN Q PUBLIC",
                            "2030-01-01", "Y")));

            service.readCardFile();

            assertAll(
                    () -> assertThat(messages()).noneMatch(line -> line.contains("4111111111111111")),
                    () -> assertThat(messages()).noneMatch(line ->
                            java.util.regex.Pattern.compile(
                                    "(?<![0-9A-Fa-f])987(?![0-9A-Fa-f])")
                                    .matcher(line)
                                    .find()),
                    () -> assertThat(messages()).anyMatch(line -> line.matches(
                            "recordType=card recordRef=\\[REDACTED] ref=[0-9a-f]{24}")));
        }

        @Test
        @DisplayName("the customer diagnostic carries only a correlation token")
        void onlyTheCustomerCorrelationTokenIsEmitted() {
            when(customerRepository.count()).thenReturn(1L);
            when(customerRepository.findAll(any(Sort.class))).thenReturn(List.of(customer()));

            service.readCustomerFile();

            assertAll(
                    () -> assertThat(messages()).anyMatch(line -> line.matches(
                            "recordType=customer recordRef=\\[REDACTED] ref=[0-9a-f]{24}")),
                    () -> assertThat(messages()).noneMatch(line -> line.contains("000000123")),
                    () -> assertThat(messages()).noneMatch(line -> line.contains("GRACE")),
                    () -> assertThat(messages()).noneMatch(line -> line.contains("HOPPER")),
                    () -> assertThat(messages()).noneMatch(line -> line.contains("1906-12-09")),
                    () -> assertThat(messages()).noneMatch(line -> line.contains("5551234567")),
                    () -> assertThat(messages()).noneMatch(line -> line.contains("1 NAVY YARD")));
        }

        @Test
        @DisplayName("the category-balance unload withholds its account key and balance")
        void categoryBalanceUnloadWithholdsKeyAndBalance() {
            when(transactionCategoryBalanceRepository.count()).thenReturn(1L);
            when(transactionCategoryBalanceRepository.findAll(any(Sort.class))).thenReturn(List.of(
                    new TransactionCategoryBalance("00000000001", "01", "0005",
                            new BigDecimal("98765.43"))));

            service.readTransactionCategoryBalanceFile();

            assertAll(
                    () -> assertThat(messages()).anyMatch(line -> line.matches(
                            "recordType=transaction-category-balance "
                                    + "recordRef=\\[REDACTED] ref=[0-9a-f]{24}")),
                    () -> assertThat(messages()).noneMatch(line -> line.contains("00000000001")),
                    () -> assertThat(messages()).noneMatch(line -> line.contains("98765.43")));
        }
    }

    @Nested
    @DisplayName("the construction contract and the returned summary refuse what they cannot carry")
    class ContractsRefuseInvalidValues {

        @Test
        @DisplayName("a missing repository or a missing abend service is refused at construction")
        void everyCollaboratorIsRequired() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(
                                    null, accountScanRepository,
                                    cardRepository, cardScanRepository,
                                    cardCrossReferenceRepository, cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, customerRepository,
                                    new AbendService())),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(
                                    accountRepository, null,
                                    cardRepository, cardScanRepository,
                                    cardCrossReferenceRepository, cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, customerRepository,
                                    new AbendService())),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(
                                    accountRepository, accountScanRepository,
                                    null, cardScanRepository,
                                    cardCrossReferenceRepository, cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, customerRepository,
                                    new AbendService())),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(
                                    accountRepository, accountScanRepository,
                                    cardRepository, null,
                                    cardCrossReferenceRepository, cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, customerRepository,
                                    new AbendService())),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(
                                    accountRepository, accountScanRepository,
                                    cardRepository, cardScanRepository,
                                    null, cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, customerRepository,
                                    new AbendService())),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(
                                    accountRepository, accountScanRepository,
                                    cardRepository, cardScanRepository,
                                    cardCrossReferenceRepository, null,
                                    transactionCategoryBalanceRepository, customerRepository,
                                    new AbendService())),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(
                                    accountRepository, accountScanRepository,
                                    cardRepository, cardScanRepository,
                                    cardCrossReferenceRepository, cardCrossReferenceScanRepository,
                                    null, customerRepository, new AbendService())),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(
                                    accountRepository, accountScanRepository,
                                    cardRepository, cardScanRepository,
                                    cardCrossReferenceRepository, cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, null,
                                    new AbendService())),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(
                                    accountRepository, accountScanRepository,
                                    cardRepository, cardScanRepository,
                                    cardCrossReferenceRepository, cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, customerRepository, null)));
        }

        @Test
        @DisplayName("the summary refuses a missing name, a missing status and a negative count")
        void summaryRefusesValuesItCannotHaveProduced() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService.FileReadSummary(null, "ACCTFILE", 0L, "10")),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService.FileReadSummary("CBACT01C", null, 0L, "10")),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService.FileReadSummary("CBACT01C", "ACCTFILE",
                                    0L, null)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                            () -> new FileMaintenanceService.FileReadSummary("CBACT01C", "ACCTFILE",
                                    -1L, "10")));
        }

        @Test
        @DisplayName("a terminal status other than end of file is reported as an abnormal ending")
        void aTerminalStatusOtherThanEndOfFileIsNotANormalEnding() {
            assertThat(new FileMaintenanceService.FileReadSummary("CBACT01C", "ACCTFILE", 5L,
                    FileStatusException.STATUS_SUCCESS).endedAtEndOfFile()).isFalse();
        }
    }

    @Nested
    @DisplayName("the service is stateless, so nothing carries over between invocations")
    class ServiceIsStateless {

        @Test
        @DisplayName("a second read reports its own count rather than accumulating the first")
        void countsDoNotAccumulateAcrossInvocations() {
            when(accountRepository.count()).thenReturn(2L);
            when(accountRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(account("00000000001"), account("00000000002")))
                    .thenReturn(List.of());

            assertAll(
                    () -> assertThat(service.readAccountFile().recordsRead()).isEqualTo(2L),
                    () -> assertThat(service.readAccountFile().recordsRead()).isZero());
        }

        @Test
        @DisplayName("an abend on one reader leaves another reader able to complete normally")
        void anAbendOnOneReaderDoesNotDisableAnother() {
            when(cardRepository.count()).thenThrow(unreachable());
            when(accountRepository.count()).thenReturn(1L);
            when(accountRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(account("00000000001")));

            assertThatExceptionOfType(AbendException.class).isThrownBy(() -> service.readCardFile());

            assertThat(service.readAccountFile().recordsRead()).isEqualTo(1L);
        }
    }

    /**
     * A customer whose every field is recognisable, so that a diagnostic which leaked one would fail an
     * assertion. The two protected values are absent rather than fabricated, because the entity refuses a
     * value that is not a well-formed protected one.
     *
     * @return a customer record carrying no real personal data
     */
    private static Customer customer() {
        return new Customer("000000123", "GRACE", "B", "HOPPER", "1 NAVY YARD", "", "", "NY", "USA",
                "10001", "5551234567", "", null, null, "1906-12-09", "0000000001", "Y", "800");
    }
}
