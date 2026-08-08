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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;

import com.carddemo.domain.Account;
import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.enums.FileStatus;
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
import com.carddemo.support.TestDataFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies {@code FileMaintenanceService}: the four sequential member readers of the batch tier, the
 * category-balance unload that serves a job stream rather than a member, and the two-level I/O status
 * model the whole batch estate shares.
 *
 * <h2>Why this class carries the estate's most consequential assertion</h2>
 *
 * <p>Four legacy members converge on the class under test - {@code app/cbl/CBACT01C.cbl} with six
 * paragraphs and {@code app/cbl/CBACT02C.cbl}, {@code app/cbl/CBACT03C.cbl} and
 * {@code app/cbl/CBCUS01C.cbl} with five apiece, twenty-one paragraph units in total. The first is the
 * exemplar: its read paragraph at lines 92-116 normalises the raw two-byte file status into a coarser
 * result - success to zero, end of file to sixteen, anything else to twelve - and then branches on that
 * coarse value through two condition names rather than on the raw code. A third coarse value, twelve,
 * is the error sentinel and carries no condition name; a fourth, eight, is armed immediately before
 * every open and close so a path that reports nothing cannot be read as a success. The coarse variable
 * is referenced on roughly 223 lines of the estate, and nine further batch members repeat this shape.
 *
 * <p><strong>Collapsing end of file into the error arm is the failure mode these tests exist to
 * catch.</strong> Every sequential read loop in the estate terminates on the end-of-file arm, so a
 * translation that folded the two together would turn every successful job into an abend while still
 * looking correct in isolation. The separation is therefore asserted from both sides: the end-of-file
 * arm must return a summary with the abend collaborator untouched, and any other non-success status
 * must reach the abend path carrying the raw two-byte code.
 *
 * <p><strong>The second load-bearing property is ordering.</strong> The legacy sequence at a failing
 * I/O site is to display the member's own failure literal, move the raw status into the display field,
 * display it, and only then abend. Neither error type carries a logger, so the emission is the
 * service's obligation. These tests prove the ordering rather than mere co-occurrence: the abend
 * collaborator snapshots the diagnostic channel at the instant it is invoked, so an implementation
 * that logged from a caller's {@code catch} would fail even though every other assertion still passed.
 *
 * <h2>Harness</h2>
 *
 * <p>A surefire unit test. Every collaborator is a Mockito mock - the four keyed repositories, the
 * three bounded scan views, the category-balance repository and the abend service - so no container,
 * no Spring context, no connection and no file system is involved. Fixtures come from the module's
 * test data factory, so a record here carries the same values and the same widths a seeded row does.
 * Expected counts, statuses, literals and rendered strings are declared here as literals: no expected
 * value is produced by asking the class under test, a codec, a formatter or a record mapper for it.
 *
 * <h2>Two places where the production code corrects earlier planning material</h2>
 *
 * <p>First, {@code CBACT03C} reads the card cross-reference cluster under the {@code XREFFILE} DD, not
 * the category-balance cluster: its record key is the cross-reference card number and all three of its
 * failure literals name that DD. The category-balance pass in the class under test therefore translates
 * no member at all - it stands for the copy step of a job stream, reports that step's name rather than
 * a program name, and is excluded from the twenty-one paragraph units below. Second, no repository in
 * this module declares an ordering and none is handed a sort specification: ordering is supplied by an
 * ascending-ordered keyset finder over a dedicated scan view, addressed by the last business key already
 * delivered and bounded by an explicit limit. These tests capture that cursor progression and that
 * bound, which is the same guarantee a captured sort specification would have given.
 *
 * <h2>Traceability - all twenty-one paragraph units</h2>
 *
 * <table>
 *   <caption>Legacy paragraph unit to covering test method</caption>
 *   <tr><th>Member</th><th>Paragraph</th><th>Covering test</th></tr>
 *   <tr><td>CBACT01C</td><td>(unnamed mainline)</td>
 *       <td>{@code cbact01cMainlineReadsTheAccountClusterToEndOfFile}</td></tr>
 *   <tr><td>CBACT01C</td><td>0000-ACCTFILE-OPEN</td>
 *       <td>{@code cbact01cOpenAcctFileIsTheFirstBoundedPageAndAbendsWhenItFails}</td></tr>
 *   <tr><td>CBACT01C</td><td>1000-ACCTFILE-GET-NEXT</td>
 *       <td>{@code cbact01cAcctFileGetNextAcceptsEverySuccessfulReadExactlyOnce}</td></tr>
 *   <tr><td>CBACT01C</td><td>1100-DISPLAY-ACCT-RECORD</td>
 *       <td>{@code cbact01cDisplayAcctRecordEmitsOnlyTheStatusAndACorrelationToken}</td></tr>
 *   <tr><td>CBACT01C</td><td>9000-ACCTFILE-CLOSE</td>
 *       <td>{@code cbact01cCloseAcctFileRunsAfterTheLoopUnderItsOwnResourceName}</td></tr>
 *   <tr><td>CBACT01C</td><td>9999-ABEND-PROGRAM</td>
 *       <td>{@code theAbendCarriesTheMemberNameTheLiteralAndTheRawStatus}</td></tr>
 *   <tr><td>CBACT01C</td><td>9910-DISPLAY-IO-STATUS</td>
 *       <td>{@code theNumericArmZeroFillsTheFourCharacterField}</td></tr>
 *   <tr><td>CBACT02C</td><td>(unnamed mainline)</td>
 *       <td>{@code cbact02cMainlineReadsTheCardClusterToEndOfFile}</td></tr>
 *   <tr><td>CBACT02C</td><td>0000-CARDFILE-OPEN</td>
 *       <td>{@code cbact02cOpenCardFileAbendsUnderItsOwnOpenLiteral}</td></tr>
 *   <tr><td>CBACT02C</td><td>1000-CARDFILE-GET-NEXT</td>
 *       <td>{@code cbact02cCardFileGetNextEmitsOneImagePerRecordBecauseItsOwnDisplayIsCommentedOut}</td></tr>
 *   <tr><td>CBACT02C</td><td>9000-CARDFILE-CLOSE</td>
 *       <td>{@code cbact02cCloseCardFileRunsAfterTheLoopUnderItsOwnResourceName}</td></tr>
 *   <tr><td>CBACT02C</td><td>9999-ABEND-PROGRAM</td>
 *       <td>{@code cbact02cOpenCardFileAbendsUnderItsOwnOpenLiteral}</td></tr>
 *   <tr><td>CBACT02C</td><td>9910-DISPLAY-IO-STATUS</td>
 *       <td>{@code theImplementorDefinedArmRendersTheSecondBytesValue}</td></tr>
 *   <tr><td>CBACT03C</td><td>(unnamed mainline)</td>
 *       <td>{@code cbact03cMainlineReadsTheCrossReferenceClusterToEndOfFile}</td></tr>
 *   <tr><td>CBACT03C</td><td>0000-XREFFILE-OPEN</td>
 *       <td>{@code cbact03cOpenXrefFileAbendsUnderItsOwnOpenLiteral}</td></tr>
 *   <tr><td>CBACT03C</td><td>1000-XREFFILE-GET-NEXT</td>
 *       <td>{@code cbact03cXrefFileGetNextEmitsEachRecordTwiceExactlyAsTheMemberDoes}</td></tr>
 *   <tr><td>CBACT03C</td><td>9000-XREFFILE-CLOSE</td>
 *       <td>{@code cbact03cCloseXrefFileRunsAfterTheLoopUnderItsOwnResourceName}</td></tr>
 *   <tr><td>CBACT03C</td><td>9999-ABEND-PROGRAM</td>
 *       <td>{@code cbact03cOpenXrefFileAbendsUnderItsOwnOpenLiteral}</td></tr>
 *   <tr><td>CBACT03C</td><td>9910-DISPLAY-IO-STATUS</td>
 *       <td>{@code aStatusOutsideTheDeclaredVocabularyIsReportedRatherThanRejected}</td></tr>
 *   <tr><td>CBCUS01C</td><td>(unnamed mainline)</td>
 *       <td>{@code cbcus01cMainlineReadsTheCustomerClusterToEndOfFile}</td></tr>
 *   <tr><td>CBCUS01C</td><td>0000-CUSTFILE-OPEN</td>
 *       <td>{@code cbcus01cOpenCustFileAbendsUnderItsOwnOpenLiteral}</td></tr>
 *   <tr><td>CBCUS01C</td><td>1000-CUSTFILE-GET-NEXT</td>
 *       <td>{@code cbcus01cCustFileGetNextEmitsEachRecordTwiceAndRevealsNoPersonalField}</td></tr>
 *   <tr><td>CBCUS01C</td><td>9000-CUSTFILE-CLOSE</td>
 *       <td>{@code cbcus01cCloseCustFileRunsAfterTheLoopUnderItsOwnResourceName}</td></tr>
 *   <tr><td>CBCUS01C</td><td>Z-ABEND-PROGRAM</td>
 *       <td>{@code cbcus01cOpenCustFileAbendsUnderItsOwnOpenLiteral}</td></tr>
 *   <tr><td>CBCUS01C</td><td>Z-DISPLAY-IO-STATUS</td>
 *       <td>{@code anAbsentStatusAndAbsentContextRenderAsMarkersWithoutThrowing}</td></tr>
 * </table>
 *
 * <p>The table holds twenty-five rows for twenty-one paragraph units, and the arithmetic is worth
 * stating so a reader can check it. Twenty-one is the count of <em>named</em> paragraphs - six in the
 * account member and five in each of the other three. The four remaining rows are the unnamed
 * mainlines, listed because each is a translated unit of behaviour with a covering test even though a
 * procedure division body is not a paragraph. The two paragraphs every member shares have one Java
 * owner apiece, so the abend row and the status-display row of the four members are covered by the
 * same tests; both legacy spellings of the customer member's two paragraphs are recorded above so the
 * mapping stays findable from either name.
 *
 * <p>Migrated from the AWS CardDemo mainframe estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileMaintenanceService: four member readers, one job-step unload, one status model in "
        + "which end of file is a normal completion and never an error")
class FileMaintenanceServiceTest {

    /** Raw status of a successful operation, declared here rather than borrowed from the code. */
    private static final String RAW_SUCCESS = "00";

    /** Raw status reporting no next logical record, which ends a read loop normally. */
    private static final String RAW_END_OF_FILE = "10";

    /** Raw status a failed repository operation reports; one of the statuses the source compares. */
    private static final String RAW_PERMANENT_ERROR = "31";

    /** A second, distinct error status from the source vocabulary, proving the arm is a catch-all. */
    private static final String RAW_RECORD_NOT_FOUND = "23";

    /** A status outside the declared vocabulary, which a live data set may legitimately return. */
    private static final String RAW_UNDECLARED = "77";

    /** Coarse result of a successful operation: the first of the two values with a condition name. */
    private static final int COARSE_ALL_OK = 0;

    /** Coarse result armed before every open, read and close, and bound to no condition name. */
    private static final int COARSE_PENDING = 8;

    /** Coarse result of a failed operation, which the members reach by elimination. */
    private static final int COARSE_ERROR = 12;

    /** Coarse result of end of file: the second of the two values with a condition name. */
    private static final int COARSE_END_OF_FILE = 16;

    /** Legacy member name of the account reader. */
    private static final String CBACT01C = "CBACT01C";

    /** Legacy member name of the card reader. */
    private static final String CBACT02C = "CBACT02C";

    /** Legacy member name of the cross-reference reader. */
    private static final String CBACT03C = "CBACT03C";

    /** Legacy member name of the customer reader. */
    private static final String CBCUS01C = "CBCUS01C";

    /** Legacy step name the category-balance unload reports, because it translates no member. */
    private static final String UNLOAD_STEP = "STEP05R";

    /** DD name of the account cluster. */
    private static final String DD_ACCTFILE = "ACCTFILE";

    /** DD name of the card cluster. */
    private static final String DD_CARDFILE = "CARDFILE";

    /** DD name of the cross-reference cluster. */
    private static final String DD_XREFFILE = "XREFFILE";

    /** DD name of the category-balance cluster. */
    private static final String DD_TCATBALF = "TCATBALF";

    /** DD name of the customer cluster. */
    private static final String DD_CUSTFILE = "CUSTFILE";

    /** Legacy gerund naming an open. */
    private static final String OPENING = "OPENING";

    /** Legacy gerund naming a read. */
    private static final String READING = "READING";

    /** Legacy gerund naming a close. */
    private static final String CLOSING = "CLOSING";

    /** The exclusive lower bound the first page of every reader is addressed with. */
    private static final String LOW_VALUES = "";

    /** Account-cluster identifiers in ascending key order, as a sequential read delivers them. */
    private static final String FIRST_ACCOUNT = "00000000001";

    private static final String SECOND_ACCOUNT = "00000000002";

    private static final String THIRD_ACCOUNT = "00000000003";

    /** Card-cluster keys in ascending key order. */
    private static final String FIRST_CARD = "0500024453765740";

    private static final String SECOND_CARD = "0500024453765741";

    /** Customer-cluster keys in ascending key order. */
    private static final String FIRST_CUSTOMER = "000000001";

    private static final String SECOND_CUSTOMER = "000000002";

    /** Transaction type and category codes of the category-balance composite key. */
    private static final String TYPE_CODE = "01";

    private static final String FIRST_CATEGORY = "0005";

    private static final String SECOND_CATEGORY = "0006";

    /** A group identifier of ten spaces: present, blank, and never to be trimmed or nulled. */
    private static final String TEN_SPACE_GROUP_ID = "          ";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountScanRepository accountScanRepository;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private CardScanRepository cardScanRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private CardCrossReferenceScanRepository cardCrossReferenceScanRepository;

    @Mock
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private AbendService abendService;

    private FileMaintenanceService service;

    private Logger serviceLogger;

    private ListAppender<ILoggingEvent> appender;

    private Level previousLevel;

    /**
     * What the diagnostic channel already held at the instant the abend collaborator was invoked.
     *
     * <p>This is the ordering oracle. Because the snapshot is taken inside the collaborator rather than
     * after the exception has been caught, an implementation that emitted its diagnostic from a
     * {@code catch} block would leave this empty and fail, while co-occurrence alone would not.
     */
    private List<String> diagnosticsVisibleWhenTheAbendWasRequested = List.of();

    @BeforeEach
    void setUp() {
        this.service = new FileMaintenanceService(
                this.accountRepository, this.accountScanRepository,
                this.cardRepository, this.cardScanRepository,
                this.cardCrossReferenceRepository, this.cardCrossReferenceScanRepository,
                this.transactionCategoryBalanceRepository, this.customerRepository,
                this.abendService);
        this.diagnosticsVisibleWhenTheAbendWasRequested = List.of();
        this.appender = new ListAppender<>();
        this.appender.start();
        this.serviceLogger = (Logger) LoggerFactory.getLogger(FileMaintenanceService.class);
        this.previousLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.TRACE);
        this.serviceLogger.addAppender(this.appender);
    }

    @AfterEach
    void tearDown() {
        this.serviceLogger.detachAppender(this.appender);
        this.serviceLogger.setLevel(this.previousLevel);
        this.appender.stop();
    }

    /**
     * Arms the abend collaborator to behave as the real one does - record what is already on the
     * diagnostic channel, then raise - so that every error arm can be driven to completion and the
     * emit-then-abend ordering can be asserted rather than assumed.
     */
    private void abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted() {
        doAnswer(invocation -> {
            this.diagnosticsVisibleWhenTheAbendWasRequested = renderedDiagnostics();
            throw new AbendException(AbendException.BATCH_ABEND_CODE,
                    invocation.<String>getArgument(0), invocation.<String>getArgument(1),
                    AbendException.DEFAULT_MESSAGE);
        }).when(this.abendService).abendBatch(any(), any(), any(), any(), any());
    }

    /**
     * @return every diagnostic the service has emitted, in emission order, formatted as an appender
     *         would render it
     */
    private List<String> renderedDiagnostics() {
        List<String> rendered = new ArrayList<>();
        for (ILoggingEvent event : this.appender.list) {
            rendered.add(event.getFormattedMessage());
        }
        return List.copyOf(rendered);
    }

    /**
     * @param fragment the text to look for
     * @return the position of the first diagnostic containing the fragment, or minus one
     */
    private int firstIndexContaining(final String fragment) {
        List<String> rendered = renderedDiagnostics();
        for (int index = 0; index < rendered.size(); index++) {
            if (rendered.get(index).contains(fragment)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * @param rawFileStatus the raw two-character status the failing operation reported
     * @return the status line the status-display paragraph produces for it, prefix and rendered field
     *         concatenated exactly as the legacy display concatenated them
     */
    private static String statusLineFor(final String rawFileStatus) {
        return "FILE STATUS IS: NNNN00" + rawFileStatus;
    }

    /**
     * @return the failure a repository reports when its cluster cannot be reached
     */
    private static DataAccessResourceFailureException unreachableCluster() {
        return new DataAccessResourceFailureException("the cluster could not be reached");
    }

    private static Account accountRecord(final String acctId) {
        return TestDataFactory.account().acctId(acctId).build();
    }

    private static Card cardRecord(final String cardNumber) {
        return TestDataFactory.card().cardNumber(cardNumber).build();
    }

    private static CardCrossReference crossReferenceRecord(final String cardNumber) {
        return TestDataFactory.cardCrossReference().cardNumber(cardNumber).build();
    }

    private static Customer customerRecord(final String custId) {
        return TestDataFactory.customer().customerId(custId).build();
    }

    private static TransactionCategoryBalance categoryBalanceRecord(final String accountId,
            final String categoryCode, final String balance) {
        return TestDataFactory.transactionCategoryBalance()
                .accountId(accountId)
                .typeCode(TYPE_CODE)
                .categoryCode(categoryCode)
                .balance(new BigDecimal(balance))
                .build();
    }

    /**
     * @param accountId    the account part of the composite key
     * @param categoryCode the category part
     * @return the concatenated key image a cursor carries, composed here rather than by asking the
     *         production key codec for it
     */
    private static String categoryBalanceKeyImage(final String accountId, final String categoryCode) {
        return accountId + TYPE_CODE + categoryCode;
    }

    /**
     * @param value the value to measure
     * @return the encoded length of the value, measured on bytes rather than on characters and never
     *         on a trimmed copy
     */
    private static int encodedLengthOf(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    @Nested
    @DisplayName("the two-level status model: end of file is a normal completion, and collapsing it "
            + "into the error arm would turn every successful job into an abend")
    class TheTwoLevelStatusModel {

        @ParameterizedTest(name = "raw {0} normalises to coarse {1}")
        @CsvSource({
            "00, 0, SUCCESS",
            "10, 16, END_OF_FILE",
            "23, 12, RECORD_NOT_FOUND",
            "31, 12, PERMANENT_ERROR",
        })
        @DisplayName("each raw status normalises to the coarse result the members branch on, and only "
                + "the all-clear and end-of-file values carry a condition name of their own")
        void rawStatusNormalisesToTheCoarseResultTheMembersBranchOn(final String rawStatus,
                final int coarseResult, final String declaredName) {
            FileStatus declared = FileStatus.fromCode(rawStatus).orElseThrow();

            service.displayIoStatus(rawStatus, READING, DD_ACCTFILE);

            assertAll(
                    () -> assertThat(declared.name()).isEqualTo(declaredName),
                    () -> assertThat(declared.getCode()).isEqualTo(rawStatus),
                    () -> assertThat(declared.isSuccess())
                            .as("only the coarse all-clear value reports success")
                            .isEqualTo(coarseResult == COARSE_ALL_OK),
                    () -> assertThat(declared.isEndOfFile())
                            .as("only the coarse end-of-file value reports end of file")
                            .isEqualTo(coarseResult == COARSE_END_OF_FILE),
                    () -> assertThat(renderedDiagnostics()).singleElement().asString()
                            .contains(FileStatusException.DISPLAY_PREFIX)
                            .contains("fileStatus=" + rawStatus)
                            .contains("statusName=" + declaredName));
        }

        @Test
        @DisplayName("end of file ends the read loop and returns a summary: nothing is raised and the "
                + "abend collaborator is never approached, which is the distinction this file exists "
                + "to protect")
        void endOfFileEndsTheLoopAndNeverReachesTheAbendPath() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readAccountFile();

            assertAll(
                    () -> assertThat(summary.terminalFileStatus()).isEqualTo(RAW_END_OF_FILE),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> assertThat(summary.recordsRead()).isZero(),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("a success continues the loop, so every record of a populated cluster is read and "
                + "the loop still ends at end of file rather than at the last record")
        void aSuccessContinuesTheLoopUntilEndOfFile() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT), accountRecord(SECOND_ACCOUNT),
                            accountRecord(THIRD_ACCOUNT)))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readAccountFile();

            assertAll(
                    () -> assertThat(summary.recordsRead()).isEqualTo(3L),
                    () -> assertThat(summary.terminalFileStatus()).isEqualTo(RAW_END_OF_FILE),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("any other status reaches the abend path, which receives the member name, the "
                + "member's own failure literal, the raw two-byte code, the operation and the resource")
        void anyOtherStatusReachesTheAbendPathCarryingTheRawCode() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readAccountFile());

            ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
            verify(abendService).abendBatch(arguments.capture(), arguments.capture(),
                    arguments.capture(), arguments.capture(), arguments.capture());
            assertThat(arguments.getAllValues()).containsExactly(
                    CBACT01C, "ERROR OPENING ACCTFILE", RAW_PERMANENT_ERROR, OPENING, DD_ACCTFILE);
        }

        @Test
        @DisplayName("the pre-operation sentinel is armed before every open, read and close, and is "
                + "mistaken neither for the all-clear value nor for end of file")
        void thePreOperationSentinelIsArmedBeforeEveryOperation() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT)))
                    .thenReturn(List.of());

            service.readAccountFile();

            List<String> armed = renderedDiagnostics().stream()
                    .filter(line -> line.startsWith("armed "))
                    .toList();
            assertAll(
                    () -> assertThat(armed)
                            .as("one arming trace per operation: the open, each read and the close")
                            .isNotEmpty()
                            .allMatch(line -> line.contains("applResult=" + COARSE_PENDING)),
                    () -> assertThat(armed).anyMatch(line -> line.contains("operation=" + OPENING)),
                    () -> assertThat(armed).anyMatch(line -> line.contains("operation=" + READING)),
                    () -> assertThat(armed).anyMatch(line -> line.contains("operation=" + CLOSING)),
                    () -> assertThat(armed).noneMatch(
                            line -> line.contains("applResult=" + COARSE_ALL_OK)),
                    () -> assertThat(armed).noneMatch(
                            line -> line.contains("applResult=" + COARSE_END_OF_FILE)));
        }

        @Test
        @DisplayName("a failed operation reports the coarse error value, so the sentinel it was armed "
                + "with is never the value the member branches on")
        void aFailedOperationReportsTheCoarseErrorValue() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readAccountFile());

            assertThat(renderedDiagnostics())
                    .anyMatch(line -> line.startsWith("ERROR OPENING ACCTFILE")
                            && line.contains("fileStatus=" + RAW_PERMANENT_ERROR)
                            && line.contains("applResult=" + COARSE_ERROR));
        }

        @ParameterizedTest(name = "status {0} is an error and may be carried as one")
        @ValueSource(strings = {RAW_RECORD_NOT_FOUND, RAW_PERMANENT_ERROR, RAW_UNDECLARED})
        @DisplayName("every value that is neither success nor end of file is the error arm, whether the "
                + "declared vocabulary holds it or not, and the raw two bytes round-trip unchanged")
        void everyOtherValueIsTheErrorArmAndRoundTripsItsRawCode(final String rawStatus) {
            FileStatusException failure = new FileStatusException(rawStatus, READING, DD_ACCTFILE);

            assertAll(
                    () -> assertThat(failure.code()).isEqualTo(rawStatus),
                    () -> assertThat(encodedLengthOf(failure.code()))
                            .isEqualTo(FileStatusException.CODE_LENGTH),
                    () -> assertThat(failure.firstByte()).isEqualTo(rawStatus.charAt(0)),
                    () -> assertThat(failure.secondByte()).isEqualTo(rawStatus.charAt(1)),
                    () -> assertThat(failure.operation()).isEqualTo(READING),
                    () -> assertThat(failure.resourceName()).isEqualTo(DD_ACCTFILE),
                    () -> assertThat(failure).isInstanceOf(RuntimeException.class));
        }

        @ParameterizedTest(name = "status {0} is not an error and is refused")
        @ValueSource(strings = {RAW_SUCCESS, RAW_END_OF_FILE})
        @DisplayName("the two statuses that are not errors cannot be carried as a file failure at all, "
                + "which is where the end-of-file distinction is enforced structurally")
        void theTwoNonErrorStatusesCannotBeCarriedAsAFileFailure(final String rawStatus) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FileStatusException(rawStatus, READING, DD_ACCTFILE))
                    .withMessageContaining(rawStatus);
        }

        @Test
        @DisplayName("an unlisted status is the error arm too, so the arm is a genuine catch-all rather "
                + "than a pair of hard-coded codes")
        void anUnlistedStatusIsTheErrorArmToo() {
            assertAll(
                    () -> assertThat(FileStatus.fromCode(RAW_UNDECLARED))
                            .as("the vocabulary genuinely does not hold this code")
                            .isEmpty(),
                    () -> assertThatNoException().isThrownBy(
                            () -> new FileStatusException(RAW_UNDECLARED, READING, DD_ACCTFILE)),
                    () -> assertThat(
                            new FileStatusException(RAW_UNDECLARED, READING, DD_ACCTFILE).code())
                            .isEqualTo(RAW_UNDECLARED));
        }

        @Test
        @DisplayName("the raw vocabulary the error type publishes is the vocabulary asserted here, so "
                + "these tests and the code cannot drift apart silently")
        void thePublishedRawVocabularyAgreesWithTheValuesAssertedHere() {
            assertAll(
                    () -> assertThat(FileStatusException.STATUS_SUCCESS).isEqualTo(RAW_SUCCESS),
                    () -> assertThat(FileStatusException.STATUS_END_OF_FILE).isEqualTo(RAW_END_OF_FILE),
                    () -> assertThat(FileStatusException.CODE_LENGTH).isEqualTo(2),
                    () -> assertThat(FileStatusException.DISPLAY_PREFIX)
                            .isEqualTo("FILE STATUS IS: NNNN"),
                    () -> assertThat(FileStatus.fromCode(RAW_SUCCESS).orElseThrow().isSuccess())
                            .isTrue(),
                    () -> assertThat(FileStatus.fromCode(RAW_END_OF_FILE).orElseThrow().isEndOfFile())
                            .isTrue());
        }
    }

    @Nested
    @DisplayName("the abend path: the failure literal and the raw status are emitted BEFORE the raise, "
            + "never from a caller that has already unwound past them")
    class TheAbendPath {

        @Test
        @DisplayName("the raw two-byte status is already on the diagnostic channel at the instant the "
                + "abend is requested, which proves ordering rather than co-occurrence")
        void theRawStatusIsEmittedBeforeTheAbendIsRequested() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readAccountFile());

            assertAll(
                    () -> assertThat(diagnosticsVisibleWhenTheAbendWasRequested)
                            .as("the diagnostic channel was not empty when the abend was requested")
                            .isNotEmpty(),
                    () -> assertThat(diagnosticsVisibleWhenTheAbendWasRequested)
                            .anyMatch(line -> line.contains("fileStatus=" + RAW_PERMANENT_ERROR)),
                    () -> assertThat(diagnosticsVisibleWhenTheAbendWasRequested)
                            .anyMatch(line -> line.contains(statusLineFor(RAW_PERMANENT_ERROR))),
                    () -> assertThat(diagnosticsVisibleWhenTheAbendWasRequested)
                            .anyMatch(line -> line.startsWith("ERROR OPENING ACCTFILE")));
        }

        @Test
        @DisplayName("literal first, then the rendered status line, then the raise: the legacy order, "
                + "asserted by position on the diagnostic channel")
        void theFailureLiteralPrecedesTheStatusLine() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readAccountFile());

            int literal = firstIndexContaining("ERROR OPENING ACCTFILE program=");
            int statusLine = firstIndexContaining(statusLineFor(RAW_PERMANENT_ERROR));
            assertAll(
                    () -> assertThat(literal).isNotNegative(),
                    () -> assertThat(statusLine).isNotNegative(),
                    () -> assertThat(literal).isLessThan(statusLine),
                    () -> assertThat(statusLine)
                            .as("the status line is the last thing emitted before the raise")
                            .isEqualTo(diagnosticsVisibleWhenTheAbendWasRequested.size() - 1));
        }

        @Test
        @DisplayName("the abend carries the member name as its culprit, the member's own literal as its "
                + "reason and the batch abend code")
        void theAbendCarriesTheMemberNameTheLiteralAndTheRawStatus() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readAccountFile())
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.culprit()).isEqualTo(CBACT01C),
                            () -> assertThat(abend.reason()).isEqualTo("ERROR OPENING ACCTFILE"),
                            () -> assertThat(abend.code())
                                    .isEqualTo(AbendException.BATCH_ABEND_CODE),
                            () -> assertThat(encodedLengthOf(abend.culprit()))
                                    .as("the culprit field is exactly a member name wide")
                                    .isEqualTo(AbendException.CULPRIT_LENGTH)));
        }

        @Test
        @DisplayName("both levels of the status model reach the diagnostic channel, and neither the "
                + "failure object nor its message does")
        void bothStatusLevelsAreRecordedAndNoFailureObjectIs() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readAccountFile());

            assertAll(
                    () -> assertThat(renderedDiagnostics())
                            .anyMatch(line -> line.contains("applResult=" + COARSE_ERROR)),
                    () -> assertThat(renderedDiagnostics())
                            .anyMatch(line -> line.contains("fileStatus=" + RAW_PERMANENT_ERROR)
                                    && line.contains("statusName=PERMANENT_ERROR")),
                    () -> assertThat(renderedDiagnostics())
                            .anyMatch(line -> line.contains(
                                    "failureChain=DataAccessResourceFailureException")),
                    () -> assertThat(renderedDiagnostics())
                            .noneMatch(line -> line.contains("the cluster could not be reached")),
                    () -> assertThat(appender.list)
                            .as("no throwable is handed to an appender, so no frame can leak")
                            .allMatch(event -> event.getThrowableProxy() == null));
        }

        @Test
        @DisplayName("the raise belongs to the abend service alone: the open arm emits and delegates and "
                + "holds no throw of its own, which is proven by driving it with a collaborator that "
                + "returns instead of raising")
        void theOpenArmDelegatesTheRaiseRatherThanOwningIt() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenThrow(unreachableCluster())
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readAccountFile();

            ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
            verify(abendService, times(1)).abendBatch(arguments.capture(), arguments.capture(),
                    arguments.capture(), arguments.capture(), arguments.capture());
            assertAll(
                    () -> assertThat(arguments.getAllValues()).containsExactly(
                            CBACT01C, "ERROR OPENING ACCTFILE", RAW_PERMANENT_ERROR, OPENING,
                            DD_ACCTFILE),
                    () -> assertThat(renderedDiagnostics())
                            .anyMatch(line -> line.startsWith("ERROR OPENING ACCTFILE")),
                    () -> assertThat(renderedDiagnostics())
                            .anyMatch(line -> line.contains(statusLineFor(RAW_PERMANENT_ERROR))),
                    () -> assertThat(summary.recordsRead())
                            .as("a failed open read nothing, whatever the collaborator decided to do")
                            .isZero());
        }

        @Test
        @DisplayName("the read arm delegates its raise the same way, under its own literal, and the "
                + "records already accepted stay accepted")
        void theReadArmDelegatesTheRaiseTheSameWay() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT)))
                    .thenThrow(unreachableCluster())
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readAccountFile();

            ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
            verify(abendService, times(1)).abendBatch(arguments.capture(), arguments.capture(),
                    arguments.capture(), arguments.capture(), arguments.capture());
            assertAll(
                    () -> assertThat(arguments.getAllValues()).containsExactly(
                            CBACT01C, "ERROR READING ACCOUNT FILE", RAW_PERMANENT_ERROR, READING,
                            DD_ACCTFILE),
                    () -> assertThat(summary.recordsRead())
                            .as("the record read before the failure was accepted and stays counted")
                            .isEqualTo(1L),
                    () -> assertThat(renderedDiagnostics())
                            .anyMatch(line -> line.startsWith("ERROR READING ACCOUNT FILE")
                                    && line.contains("applResult=" + COARSE_ERROR)));
        }
    }

    @Nested
    @DisplayName("the status-display paragraph: one Java method for both legacy spellings, both "
            + "rendering arms, and a prefix an operator can still match on")
    class TheStatusDisplayParagraph {

        @ParameterizedTest(name = "raw {0} renders as {1}")
        @CsvSource({
            "31, FILE STATUS IS: NNNN0031",
            "10, FILE STATUS IS: NNNN0010",
            "23, FILE STATUS IS: NNNN0023",
            "77, FILE STATUS IS: NNNN0077",
        })
        @DisplayName("a two-digit status is zero-filled and occupies the last two positions of the "
                + "four-character display field")
        void theNumericArmZeroFillsTheFourCharacterField(final String rawStatus,
                final String expectedLine) {
            service.displayIoStatus(rawStatus, READING, DD_ACCTFILE);

            assertAll(
                    () -> assertThat(renderedDiagnostics()).singleElement().asString()
                            .startsWith(FileStatusException.DISPLAY_PREFIX)
                            .contains(expectedLine)
                            .contains("operation=" + READING)
                            .contains("resource=" + DD_ACCTFILE),
                    () -> assertThat(expectedLine)
                            .as("prefix plus exactly four rendered characters, concatenated with no "
                                    + "separator as the legacy display concatenated them")
                            .hasSize(FileStatusException.DISPLAY_PREFIX.length() + 4));
        }

        @ParameterizedTest(name = "raw {0} renders as {1}")
        @CsvSource({
            "93, FILE STATUS IS: NNNN9051",
            "9A, FILE STATUS IS: NNNN9065",
            "A1, FILE STATUS IS: NNNNA049",
        })
        @DisplayName("the implementor-defined class and the non-numeric arm place the first byte "
                + "verbatim and render the second byte's value in the remaining three digits")
        void theImplementorDefinedArmRendersTheSecondBytesValue(final String rawStatus,
                final String expectedLine) {
            service.displayIoStatus(rawStatus, READING, DD_CARDFILE);

            assertThat(renderedDiagnostics()).singleElement().asString().contains(expectedLine);
        }

        @Test
        @DisplayName("a status the declared vocabulary does not hold is reported rather than rejected, "
                + "because a live data set may legitimately return one")
        void aStatusOutsideTheDeclaredVocabularyIsReportedRatherThanRejected() {
            service.displayIoStatus(RAW_UNDECLARED, READING, DD_XREFFILE);

            assertThat(renderedDiagnostics()).singleElement().asString()
                    .contains("fileStatus=" + RAW_UNDECLARED)
                    .contains("statusName=(outside declared vocabulary)");
        }

        @Test
        @DisplayName("an absent status and absent context render as markers and nothing is thrown, so a "
                + "diagnostic never fails the run it is diagnosing")
        void anAbsentStatusAndAbsentContextRenderAsMarkersWithoutThrowing() {
            assertThatNoException().isThrownBy(() -> service.displayIoStatus(null, null, null));

            assertThat(renderedDiagnostics()).singleElement().asString()
                    .contains("FILE STATUS IS: NNNN????")
                    .contains("fileStatus=(none)")
                    .contains("statusName=(none)")
                    .contains("operation=(none)")
                    .contains("resource=(none)");
        }

        @Test
        @DisplayName("a blank context value renders as a marker rather than as an empty field")
        void aBlankContextValueRendersAsAMarker() {
            service.displayIoStatus(RAW_PERMANENT_ERROR, "   ", "");

            assertThat(renderedDiagnostics()).singleElement().asString()
                    .contains("operation=(none)")
                    .contains("resource=(none)");
        }

        @Test
        @DisplayName("a status of the wrong length could not have reached the legacy two-byte field, and "
                + "the rendering says so instead of pretending otherwise")
        void aStatusOfTheWrongLengthRendersTheMarker() {
            assertThatNoException().isThrownBy(() -> service.displayIoStatus("1", READING, DD_CUSTFILE));

            assertThat(renderedDiagnostics()).singleElement().asString()
                    .contains("FILE STATUS IS: NNNN????")
                    .contains("resource=" + DD_CUSTFILE);
        }

        @Test
        @DisplayName("the paragraph emits and returns: it never raises, so a caller that has not yet "
                + "decided to abend may use it")
        void theParagraphEmitsAndReturnsWithoutRaising() {
            assertThatNoException().isThrownBy(
                    () -> service.displayIoStatus(RAW_PERMANENT_ERROR, CLOSING, DD_TCATBALF));

            assertAll(
                    () -> assertThat(renderedDiagnostics()).hasSize(1),
                    () -> verifyNoInteractions(abendService));
        }
    }

    @Nested
    @DisplayName("CBACT01C - the account master reader, six paragraphs, the status-model exemplar")
    class TheAccountReaderCbact01c {

        @Test
        @DisplayName("the mainline opens, reads the cluster to end of file, closes and reports the "
                + "member name, the DD name, the exact record count and the terminal status")
        void cbact01cMainlineReadsTheAccountClusterToEndOfFile() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT), accountRecord(SECOND_ACCOUNT),
                            accountRecord(THIRD_ACCOUNT)))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readAccountFile();

            assertAll(
                    () -> assertThat(summary.programName()).isEqualTo(CBACT01C),
                    () -> assertThat(summary.resourceName()).isEqualTo(DD_ACCTFILE),
                    () -> assertThat(summary.recordsRead()).isEqualTo(3L),
                    () -> assertThat(summary.terminalFileStatus()).isEqualTo(RAW_END_OF_FILE),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> assertThat(firstIndexContaining("START OF EXECUTION OF PROGRAM " + CBACT01C))
                            .isNotNegative(),
                    () -> assertThat(firstIndexContaining("START OF EXECUTION OF PROGRAM " + CBACT01C))
                            .isLessThan(firstIndexContaining(
                                    "END OF EXECUTION OF PROGRAM " + CBACT01C)),
                    () -> verifyNoInteractions(accountRepository, cardRepository, cardScanRepository,
                            cardCrossReferenceRepository, cardCrossReferenceScanRepository,
                            transactionCategoryBalanceRepository, customerRepository, abendService));
        }

        @Test
        @DisplayName("an empty cluster still ends at end of file, with a count of zero, exactly one "
                + "bounded retrieval and no abend")
        void cbact01cAnEmptyClusterEndsAtEndOfFileWithACountOfZero() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readAccountFile();

            assertAll(
                    () -> assertThat(summary.recordsRead()).isZero(),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> verify(accountScanRepository, times(1))
                            .findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("the open IS the first bounded page, addressed by the low-values cursor: when that "
                + "page fails the open arm abends under the member's own open literal, and no "
                + "whole-cluster count is ever issued")
        void cbact01cOpenAcctFileIsTheFirstBoundedPageAndAbendsWhenItFails() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readAccountFile())
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("ERROR OPENING ACCTFILE"));

            ArgumentCaptor<String> cursors = ArgumentCaptor.forClass(String.class);
            verify(accountScanRepository, times(1))
                    .findByAcctIdGreaterThanOrderByAcctIdAsc(cursors.capture(), any());
            assertAll(
                    () -> assertThat(cursors.getValue())
                            .as("the open reads one bounded page from the low-values bound")
                            .isEqualTo(LOW_VALUES),
                    () -> verify(accountRepository, never()).count(),
                    () -> assertThat(renderedDiagnostics())
                            .anyMatch(line -> line.contains("operation=" + OPENING)
                                    && line.contains("resource=" + DD_ACCTFILE)));
        }

        @Test
        @DisplayName("a retrieval that fails after the open abends on the read arm instead, under the "
                + "read paragraph's own literal")
        void cbact01cAFailureAfterTheOpenAbendsOnTheReadArm() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT)))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readAccountFile())
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("ERROR READING ACCOUNT FILE"));

            assertThat(renderedDiagnostics())
                    .anyMatch(line -> line.startsWith("ERROR READING ACCOUNT FILE")
                            && line.contains("operation=" + READING));
        }

        @Test
        @DisplayName("the read paragraph accepts every successful read exactly once, so the count is "
                + "the number of records and each record is processed a single time")
        void cbact01cAcctFileGetNextAcceptsEverySuccessfulReadExactlyOnce() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT), accountRecord(SECOND_ACCOUNT),
                            accountRecord(THIRD_ACCOUNT)))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readAccountFile();

            long displayParagraphLines = renderedDiagnostics().stream()
                    .filter(line -> line.startsWith("recordType=account recordRef="))
                    .filter(line -> line.endsWith("status=Y"))
                    .count();
            assertAll(
                    () -> assertThat(summary.recordsRead()).isEqualTo(3L),
                    () -> assertThat(displayParagraphLines)
                            .as("the display paragraph runs once per successfully read record")
                            .isEqualTo(3L));
        }

        @Test
        @DisplayName("the cluster is read in ascending key order: the first page is addressed by the "
                + "low-values cursor, every later page by the last key already delivered, and every "
                + "page is bounded by the same limit")
        void cbact01cReadsInAscendingKeyOrderAddressedByTheLastKeyDelivered() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT), accountRecord(SECOND_ACCOUNT),
                            accountRecord(THIRD_ACCOUNT)))
                    .thenReturn(List.of());

            service.readAccountFile();

            ArgumentCaptor<String> cursors = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Limit> limits = ArgumentCaptor.forClass(Limit.class);
            verify(accountScanRepository, times(2))
                    .findByAcctIdGreaterThanOrderByAcctIdAsc(cursors.capture(), limits.capture());
            assertAll(
                    () -> assertThat(cursors.getAllValues())
                            .as("no repository declares an ordering, so the reader supplies both the "
                                    + "ascending finder and the exclusive lower bound")
                            .containsExactly(LOW_VALUES, THIRD_ACCOUNT),
                    () -> assertThat(limits.getAllValues().get(0).isLimited()).isTrue(),
                    () -> assertThat(limits.getAllValues().get(0).max()).isPositive(),
                    () -> assertThat(limits.getAllValues().get(1).max())
                            .as("one bound governs every page of one traversal")
                            .isEqualTo(limits.getAllValues().get(0).max()));
        }

        @Test
        @DisplayName("the display paragraph emits only a correlation token and the non-sensitive status "
                + "label: not one of the eleven values the legacy paragraph displayed reaches the log")
        void cbact01cDisplayAcctRecordEmitsOnlyTheStatusAndACorrelationToken() {
            Account record = TestDataFactory.account()
                    .acctId(FIRST_ACCOUNT)
                    .currentBalance(new BigDecimal("1234.56"))
                    .creditLimit(new BigDecimal("2345.67"))
                    .cashCreditLimit(new BigDecimal("345.67"))
                    .openDate("2020-01-01")
                    .expirationDate("2030-01-01")
                    .reissueDate("2025-01-01")
                    .cycleCredit(new BigDecimal("11.11"))
                    .cycleDebit(new BigDecimal("44.19"))
                    .build();
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(record))
                    .thenReturn(List.of());

            service.readAccountFile();

            List<String> rendered = renderedDiagnostics();
            assertAll(
                    () -> assertThat(rendered).anyMatch(line -> line.matches(
                            "recordType=account recordRef=\\[REDACTED] ref=[0-9a-f]{24} status=Y")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains(FIRST_ACCOUNT)),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("1234.56")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("2345.67")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("345.67")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("2020-01-01")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("2030-01-01")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("2025-01-01")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("11.11")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("ACCT-ID")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("ACCT-CURR-BAL")),
                    () -> assertThat(rendered).noneMatch(line -> line.contains("ACCT-ADDR-ZIP")));
        }

        @Test
        @DisplayName("with the diagnostic level raised above the record channel the read still completes "
                + "and returns the same count, emitting no record line at all")
        void cbact01cTheReadCompletesWhenTheRecordChannelIsClosed() {
            serviceLogger.setLevel(Level.INFO);
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT)))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readAccountFile();

            assertAll(
                    () -> assertThat(summary.recordsRead()).isEqualTo(1L),
                    () -> assertThat(renderedDiagnostics())
                            .noneMatch(line -> line.startsWith("recordType=account")));
        }

        @Test
        @DisplayName("the close paragraph runs after the loop and before the closing banner, under the "
                + "member's own resource name")
        void cbact01cCloseAcctFileRunsAfterTheLoopUnderItsOwnResourceName() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT)))
                    .thenReturn(List.of());

            service.readAccountFile();

            int recordLine = firstIndexContaining("recordType=account recordRef=");
            int closeArmed = firstIndexContaining(
                    "operation=" + CLOSING + " applResult=" + COARSE_PENDING);
            int closingBanner = firstIndexContaining("END OF EXECUTION OF PROGRAM " + CBACT01C);
            assertAll(
                    () -> assertThat(closeArmed).isNotNegative(),
                    () -> assertThat(recordLine).isNotNegative(),
                    () -> assertThat(recordLine)
                            .as("the close follows the loop rather than running inside it")
                            .isLessThan(closeArmed),
                    () -> assertThat(closeArmed).isLessThan(closingBanner),
                    () -> assertThat(renderedDiagnostics().get(closeArmed))
                            .contains("resource=" + DD_ACCTFILE),
                    () -> verifyNoInteractions(abendService));
        }
    }

    @Nested
    @DisplayName("CBACT02C - the card master reader, five paragraphs, with no display paragraph of its "
            + "own because the member comments that display out")
    class TheCardReaderCbact02c {

        @Test
        @DisplayName("the mainline opens, reads the card cluster to end of file, closes and reports its "
                + "own member name, its own DD name and the exact record count")
        void cbact02cMainlineReadsTheCardClusterToEndOfFile() {
            when(cardScanRepository.findByCardNumGreaterThanOrderByCardNumAsc(any(), any()))
                    .thenReturn(List.of(cardRecord(FIRST_CARD), cardRecord(SECOND_CARD)))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readCardFile();

            assertAll(
                    () -> assertThat(summary.programName()).isEqualTo(CBACT02C),
                    () -> assertThat(summary.resourceName()).isEqualTo(DD_CARDFILE),
                    () -> assertThat(summary.recordsRead()).isEqualTo(2L),
                    () -> assertThat(summary.terminalFileStatus()).isEqualTo(RAW_END_OF_FILE),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> verifyNoInteractions(cardRepository, accountRepository,
                            accountScanRepository, cardCrossReferenceRepository,
                            cardCrossReferenceScanRepository, transactionCategoryBalanceRepository,
                            customerRepository, abendService));
        }

        @Test
        @DisplayName("an empty card cluster ends at end of file with a count of zero and no abend")
        void cbact02cAnEmptyClusterEndsAtEndOfFileWithACountOfZero() {
            when(cardScanRepository.findByCardNumGreaterThanOrderByCardNumAsc(any(), any()))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readCardFile();

            assertAll(
                    () -> assertThat(summary.recordsRead()).isZero(),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("the open arm abends under this member's own open literal, and the read arm under "
                + "its own read literal, so an operator's joblog names the operation that failed")
        void cbact02cOpenCardFileAbendsUnderItsOwnOpenLiteral() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(cardScanRepository.findByCardNumGreaterThanOrderByCardNumAsc(any(), any()))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readCardFile())
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.culprit()).isEqualTo(CBACT02C),
                            () -> assertThat(abend.reason()).isEqualTo("ERROR OPENING CARDFILE")));

            ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
            verify(abendService).abendBatch(arguments.capture(), arguments.capture(),
                    arguments.capture(), arguments.capture(), arguments.capture());
            assertThat(arguments.getAllValues()).containsExactly(
                    CBACT02C, "ERROR OPENING CARDFILE", RAW_PERMANENT_ERROR, OPENING, DD_CARDFILE);
        }

        @Test
        @DisplayName("a failure after the open abends on the read arm under this member's read literal")
        void cbact02cAFailureAfterTheOpenAbendsOnTheReadArm() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(cardScanRepository.findByCardNumGreaterThanOrderByCardNumAsc(any(), any()))
                    .thenReturn(List.of(cardRecord(FIRST_CARD)))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readCardFile())
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("ERROR READING CARDFILE"));
        }

        @Test
        @DisplayName("each record reaches the diagnostic channel exactly once, because this member's "
                + "inner display is commented out and only its mainline emits - which is also why it "
                + "has five paragraphs where the account reader has six")
        void cbact02cCardFileGetNextEmitsOneImagePerRecordBecauseItsOwnDisplayIsCommentedOut() {
            when(cardScanRepository.findByCardNumGreaterThanOrderByCardNumAsc(any(), any()))
                    .thenReturn(List.of(cardRecord(FIRST_CARD), cardRecord(SECOND_CARD)))
                    .thenReturn(List.of());

            service.readCardFile();

            List<String> recordLines = renderedDiagnostics().stream()
                    .filter(line -> line.startsWith("recordType=card recordRef="))
                    .toList();
            assertAll(
                    () -> assertThat(recordLines)
                            .as("one emission per record, not two")
                            .hasSize(2)
                            .allMatch(line -> line.matches(
                                    "recordType=card recordRef=\\[REDACTED] ref=[0-9a-f]{24}")),
                    () -> assertThat(renderedDiagnostics())
                            .as("neither the card number nor the verification code may be logged")
                            .noneMatch(line -> line.contains(FIRST_CARD))
                            .noneMatch(line -> line.contains("Aniya Von")));
        }

        @Test
        @DisplayName("the card cluster is read in ascending card-number order, each page addressed by "
                + "the last card number already delivered")
        void cbact02cReadsInAscendingCardNumberOrder() {
            when(cardScanRepository.findByCardNumGreaterThanOrderByCardNumAsc(any(), any()))
                    .thenReturn(List.of(cardRecord(FIRST_CARD), cardRecord(SECOND_CARD)))
                    .thenReturn(List.of());

            service.readCardFile();

            ArgumentCaptor<String> cursors = ArgumentCaptor.forClass(String.class);
            verify(cardScanRepository, times(2))
                    .findByCardNumGreaterThanOrderByCardNumAsc(cursors.capture(), any());
            assertThat(cursors.getAllValues()).containsExactly(LOW_VALUES, SECOND_CARD);
        }

        @Test
        @DisplayName("the close paragraph runs after the loop under this member's own resource name")
        void cbact02cCloseCardFileRunsAfterTheLoopUnderItsOwnResourceName() {
            when(cardScanRepository.findByCardNumGreaterThanOrderByCardNumAsc(any(), any()))
                    .thenReturn(List.of(cardRecord(FIRST_CARD)))
                    .thenReturn(List.of());

            service.readCardFile();

            int closeArmed = firstIndexContaining(
                    "operation=" + CLOSING + " applResult=" + COARSE_PENDING);
            assertAll(
                    () -> assertThat(closeArmed).isNotNegative(),
                    () -> assertThat(renderedDiagnostics().get(closeArmed))
                            .contains("program=" + CBACT02C)
                            .contains("resource=" + DD_CARDFILE),
                    () -> assertThat(closeArmed).isLessThan(
                            firstIndexContaining("END OF EXECUTION OF PROGRAM " + CBACT02C)));
        }
    }

    @Nested
    @DisplayName("CBACT03C - the card cross-reference reader, five paragraphs: the member selects the "
            + "cross-reference DD, keys on the cross-reference card number and names that DD in all "
            + "three of its failure literals")
    class TheCrossReferenceReaderCbact03c {

        @Test
        @DisplayName("the mainline opens, reads the cross-reference cluster to end of file, closes and "
                + "reports its own member name and its own DD name")
        void cbact03cMainlineReadsTheCrossReferenceClusterToEndOfFile() {
            when(cardCrossReferenceScanRepository
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                    .thenReturn(List.of(crossReferenceRecord(FIRST_CARD),
                            crossReferenceRecord(SECOND_CARD)))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readCardCrossReferenceFile();

            assertAll(
                    () -> assertThat(summary.programName()).isEqualTo(CBACT03C),
                    () -> assertThat(summary.resourceName()).isEqualTo(DD_XREFFILE),
                    () -> assertThat(summary.recordsRead()).isEqualTo(2L),
                    () -> assertThat(summary.terminalFileStatus()).isEqualTo(RAW_END_OF_FILE),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> verifyNoInteractions(cardCrossReferenceRepository, accountRepository,
                            accountScanRepository, cardRepository, cardScanRepository,
                            transactionCategoryBalanceRepository, customerRepository, abendService));
        }

        @Test
        @DisplayName("an empty cross-reference cluster ends at end of file with a count of zero")
        void cbact03cAnEmptyClusterEndsAtEndOfFileWithACountOfZero() {
            when(cardCrossReferenceScanRepository
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readCardCrossReferenceFile();

            assertAll(
                    () -> assertThat(summary.recordsRead()).isZero(),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("the open arm abends under this member's own cross-reference open literal, which is "
                + "what proves the reader is bound to the cluster the member names")
        void cbact03cOpenXrefFileAbendsUnderItsOwnOpenLiteral() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(cardCrossReferenceScanRepository
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readCardCrossReferenceFile())
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.culprit()).isEqualTo(CBACT03C),
                            () -> assertThat(abend.reason()).isEqualTo("ERROR OPENING XREFFILE")));

            ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
            verify(abendService).abendBatch(arguments.capture(), arguments.capture(),
                    arguments.capture(), arguments.capture(), arguments.capture());
            assertThat(arguments.getAllValues()).containsExactly(
                    CBACT03C, "ERROR OPENING XREFFILE", RAW_PERMANENT_ERROR, OPENING, DD_XREFFILE);
        }

        @Test
        @DisplayName("a failure after the open abends on the read arm under this member's read literal")
        void cbact03cAFailureAfterTheOpenAbendsOnTheReadArm() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(cardCrossReferenceScanRepository
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                    .thenReturn(List.of(crossReferenceRecord(FIRST_CARD)))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readCardCrossReferenceFile())
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("ERROR READING XREFFILE"));
        }

        @Test
        @DisplayName("each record reaches the diagnostic channel twice, because this member emits from "
                + "its read paragraph as well as from its mainline - the duplication is in the source "
                + "and is reproduced rather than tidied away, and neither emission reveals a key")
        void cbact03cXrefFileGetNextEmitsEachRecordTwiceExactlyAsTheMemberDoes() {
            when(cardCrossReferenceScanRepository
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                    .thenReturn(List.of(crossReferenceRecord(FIRST_CARD)))
                    .thenReturn(List.of());

            service.readCardCrossReferenceFile();

            assertAll(
                    () -> assertThat(renderedDiagnostics())
                            .filteredOn(line -> line.startsWith(
                                    "recordType=card-cross-reference recordRef="))
                            .as("the read paragraph and the mainline each emit every record")
                            .hasSize(2)
                            .allMatch(line -> line.matches("recordType=card-cross-reference "
                                    + "recordRef=\\[REDACTED] ref=[0-9a-f]{24}")),
                    () -> assertThat(renderedDiagnostics())
                            .noneMatch(line -> line.contains(FIRST_CARD)),
                    () -> assertThat(renderedDiagnostics())
                            .noneMatch(line -> line.contains("000000050")));
        }

        @Test
        @DisplayName("the cross-reference cluster is read in ascending cross-reference key order")
        void cbact03cReadsInAscendingCrossReferenceKeyOrder() {
            when(cardCrossReferenceScanRepository
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                    .thenReturn(List.of(crossReferenceRecord(FIRST_CARD),
                            crossReferenceRecord(SECOND_CARD)))
                    .thenReturn(List.of());

            service.readCardCrossReferenceFile();

            ArgumentCaptor<String> cursors = ArgumentCaptor.forClass(String.class);
            verify(cardCrossReferenceScanRepository, times(2))
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(cursors.capture(), any());
            assertThat(cursors.getAllValues()).containsExactly(LOW_VALUES, SECOND_CARD);
        }

        @Test
        @DisplayName("the close paragraph runs after the loop under this member's own resource name")
        void cbact03cCloseXrefFileRunsAfterTheLoopUnderItsOwnResourceName() {
            when(cardCrossReferenceScanRepository
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                    .thenReturn(List.of(crossReferenceRecord(FIRST_CARD)))
                    .thenReturn(List.of());

            service.readCardCrossReferenceFile();

            int closeArmed = firstIndexContaining(
                    "operation=" + CLOSING + " applResult=" + COARSE_PENDING);
            assertAll(
                    () -> assertThat(closeArmed).isNotNegative(),
                    () -> assertThat(renderedDiagnostics().get(closeArmed))
                            .contains("program=" + CBACT03C)
                            .contains("resource=" + DD_XREFFILE),
                    () -> assertThat(closeArmed).isLessThan(
                            firstIndexContaining("END OF EXECUTION OF PROGRAM " + CBACT03C)));
        }
    }

    @Nested
    @DisplayName("CBCUS01C - the customer master reader, five paragraphs, whose abend and status "
            + "paragraphs carry a different legacy name for the same behaviour")
    class TheCustomerReaderCbcus01c {

        @Test
        @DisplayName("the mainline opens, reads the customer cluster to end of file, closes and reports "
                + "its own member name and its own DD name")
        void cbcus01cMainlineReadsTheCustomerClusterToEndOfFile() {
            when(customerRepository.findByCustIdGreaterThanOrderByCustIdAsc(any(), any()))
                    .thenReturn(List.of(customerRecord(FIRST_CUSTOMER),
                            customerRecord(SECOND_CUSTOMER)))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readCustomerFile();

            assertAll(
                    () -> assertThat(summary.programName()).isEqualTo(CBCUS01C),
                    () -> assertThat(summary.resourceName()).isEqualTo(DD_CUSTFILE),
                    () -> assertThat(summary.recordsRead()).isEqualTo(2L),
                    () -> assertThat(summary.terminalFileStatus()).isEqualTo(RAW_END_OF_FILE),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> verifyNoInteractions(accountRepository, accountScanRepository,
                            cardRepository, cardScanRepository, cardCrossReferenceRepository,
                            cardCrossReferenceScanRepository, transactionCategoryBalanceRepository,
                            abendService));
        }

        @Test
        @DisplayName("an empty customer cluster ends at end of file with a count of zero")
        void cbcus01cAnEmptyClusterEndsAtEndOfFileWithACountOfZero() {
            when(customerRepository.findByCustIdGreaterThanOrderByCustIdAsc(any(), any()))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readCustomerFile();

            assertAll(
                    () -> assertThat(summary.recordsRead()).isZero(),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("the open arm abends under this member's own open literal and the read arm under "
                + "its own, which the member spells out in full rather than by its DD name")
        void cbcus01cOpenCustFileAbendsUnderItsOwnOpenLiteral() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(customerRepository.findByCustIdGreaterThanOrderByCustIdAsc(any(), any()))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readCustomerFile())
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.culprit()).isEqualTo(CBCUS01C),
                            () -> assertThat(abend.reason()).isEqualTo("ERROR OPENING CUSTFILE")));

            ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
            verify(abendService).abendBatch(arguments.capture(), arguments.capture(),
                    arguments.capture(), arguments.capture(), arguments.capture());
            assertThat(arguments.getAllValues()).containsExactly(
                    CBCUS01C, "ERROR OPENING CUSTFILE", RAW_PERMANENT_ERROR, OPENING, DD_CUSTFILE);
        }

        @Test
        @DisplayName("a failure after the open abends on the read arm under the member's own read "
                + "literal, which names the file rather than the DD")
        void cbcus01cAFailureAfterTheOpenAbendsOnTheReadArm() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(customerRepository.findByCustIdGreaterThanOrderByCustIdAsc(any(), any()))
                    .thenReturn(List.of(customerRecord(FIRST_CUSTOMER)))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readCustomerFile())
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("ERROR READING CUSTOMER FILE"));
        }

        @Test
        @DisplayName("each record reaches the diagnostic channel twice, as this member's read paragraph "
                + "and mainline both emit, and not one personal field appears in either emission")
        void cbcus01cCustFileGetNextEmitsEachRecordTwiceAndRevealsNoPersonalField() {
            when(customerRepository.findByCustIdGreaterThanOrderByCustIdAsc(any(), any()))
                    .thenReturn(List.of(customerRecord(FIRST_CUSTOMER)))
                    .thenReturn(List.of());

            service.readCustomerFile();

            assertAll(
                    () -> assertThat(renderedDiagnostics())
                            .filteredOn(line -> line.startsWith("recordType=customer recordRef="))
                            .hasSize(2)
                            .allMatch(line -> line.matches(
                                    "recordType=customer recordRef=\\[REDACTED] ref=[0-9a-f]{24}")),
                    () -> assertThat(renderedDiagnostics())
                            .noneMatch(line -> line.contains(FIRST_CUSTOMER)),
                    () -> assertThat(renderedDiagnostics())
                            .noneMatch(line -> line.contains("Immanuel")),
                    () -> assertThat(renderedDiagnostics())
                            .noneMatch(line -> line.contains("Kessler")));
        }

        @Test
        @DisplayName("the customer cluster is read in ascending customer-identifier order")
        void cbcus01cReadsInAscendingCustomerIdentifierOrder() {
            when(customerRepository.findByCustIdGreaterThanOrderByCustIdAsc(any(), any()))
                    .thenReturn(List.of(customerRecord(FIRST_CUSTOMER),
                            customerRecord(SECOND_CUSTOMER)))
                    .thenReturn(List.of());

            service.readCustomerFile();

            ArgumentCaptor<String> cursors = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Limit> limits = ArgumentCaptor.forClass(Limit.class);
            verify(customerRepository, times(2))
                    .findByCustIdGreaterThanOrderByCustIdAsc(cursors.capture(), limits.capture());
            assertAll(
                    () -> assertThat(cursors.getAllValues())
                            .containsExactly(LOW_VALUES, SECOND_CUSTOMER),
                    () -> assertThat(limits.getAllValues().get(0).isLimited()).isTrue(),
                    () -> assertThat(limits.getAllValues().get(0).max()).isPositive());
        }

        @Test
        @DisplayName("the close paragraph runs after the loop under this member's own resource name")
        void cbcus01cCloseCustFileRunsAfterTheLoopUnderItsOwnResourceName() {
            when(customerRepository.findByCustIdGreaterThanOrderByCustIdAsc(any(), any()))
                    .thenReturn(List.of(customerRecord(FIRST_CUSTOMER)))
                    .thenReturn(List.of());

            service.readCustomerFile();

            int closeArmed = firstIndexContaining(
                    "operation=" + CLOSING + " applResult=" + COARSE_PENDING);
            assertAll(
                    () -> assertThat(closeArmed).isNotNegative(),
                    () -> assertThat(renderedDiagnostics().get(closeArmed))
                            .contains("program=" + CBCUS01C)
                            .contains("resource=" + DD_CUSTFILE),
                    () -> assertThat(closeArmed).isLessThan(
                            firstIndexContaining("END OF EXECUTION OF PROGRAM " + CBCUS01C)));
        }
    }

    @Nested
    @DisplayName("the category-balance unload: a job stream's copy step, which translates no member and "
            + "therefore reports a step name and no execution banner")
    class TheCategoryBalanceUnload {

        @Test
        @DisplayName("the pass reports the legacy step name and the DD name rather than inventing a "
                + "member name, and emits an unload banner rather than an execution banner")
        void theUnloadReportsItsStepAndNeverAProgramBanner() {
            when(transactionCategoryBalanceRepository.findAfterKey(any(), any(), any(), any()))
                    .thenReturn(List.of(categoryBalanceRecord(FIRST_ACCOUNT, FIRST_CATEGORY, "0.00")))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary =
                    service.readTransactionCategoryBalanceFile(record -> { });

            assertAll(
                    () -> assertThat(summary.programName()).isEqualTo(UNLOAD_STEP),
                    () -> assertThat(summary.resourceName()).isEqualTo(DD_TCATBALF),
                    () -> assertThat(summary.recordsRead()).isEqualTo(1L),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> assertThat(renderedDiagnostics())
                            .anyMatch(line -> line.equals(UNLOAD_STEP
                                    + " BEGINNING SEQUENTIAL UNLOAD OF " + DD_TCATBALF)),
                    () -> assertThat(renderedDiagnostics())
                            .anyMatch(line -> line.equals(UNLOAD_STEP
                                    + " COMPLETED SEQUENTIAL UNLOAD OF " + DD_TCATBALF)),
                    () -> assertThat(renderedDiagnostics())
                            .as("no member ran, so no execution banner may name one")
                            .noneMatch(line -> line.contains("EXECUTION OF PROGRAM")),
                    () -> verifyNoInteractions(accountRepository, accountScanRepository,
                            cardRepository, cardScanRepository, cardCrossReferenceRepository,
                            cardCrossReferenceScanRepository, customerRepository, abendService));
        }

        @Test
        @DisplayName("every record is delivered to the destination as it is read, in composite-key "
                + "order, so one traversal both reads and writes")
        void everyRecordIsDeliveredToTheDestinationInKeyOrder() {
            when(transactionCategoryBalanceRepository.findAfterKey(any(), any(), any(), any()))
                    .thenReturn(List.of(
                            categoryBalanceRecord(FIRST_ACCOUNT, FIRST_CATEGORY, "0.00"),
                            categoryBalanceRecord(FIRST_ACCOUNT, SECOND_CATEGORY, "0.00"),
                            categoryBalanceRecord(SECOND_ACCOUNT, FIRST_CATEGORY, "0.00")))
                    .thenReturn(List.of());
            List<String> delivered = new ArrayList<>();

            FileMaintenanceService.FileReadSummary summary =
                    service.readTransactionCategoryBalanceFile(record -> delivered.add(
                            categoryBalanceKeyImage(record.getTrancatAcctId(),
                                    record.getTrancatCd())));

            assertAll(
                    () -> assertThat(delivered).containsExactly(
                            categoryBalanceKeyImage(FIRST_ACCOUNT, FIRST_CATEGORY),
                            categoryBalanceKeyImage(FIRST_ACCOUNT, SECOND_CATEGORY),
                            categoryBalanceKeyImage(SECOND_ACCOUNT, FIRST_CATEGORY)),
                    () -> assertThat(summary.recordsRead())
                            .as("the count is the count of the one traversal that fed the destination")
                            .isEqualTo(3L));
        }

        @Test
        @DisplayName("each page is addressed by all three parts of the last composite key delivered, "
                + "and the first page by the low-values bound, page zero and a bounded size")
        void eachPageIsAddressedByTheWholeCompositeKeyOfTheLastRecordDelivered() {
            when(transactionCategoryBalanceRepository.findAfterKey(any(), any(), any(), any()))
                    .thenReturn(List.of(
                            categoryBalanceRecord(FIRST_ACCOUNT, FIRST_CATEGORY, "0.00"),
                            categoryBalanceRecord(FIRST_ACCOUNT, SECOND_CATEGORY, "0.00")))
                    .thenReturn(List.of());

            service.readTransactionCategoryBalanceFile(record -> { });

            ArgumentCaptor<String> accountIds = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> typeCodes = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> categoryCodes = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
            verify(transactionCategoryBalanceRepository, times(2)).findAfterKey(
                    accountIds.capture(), typeCodes.capture(), categoryCodes.capture(),
                    pages.capture());
            assertAll(
                    () -> assertThat(accountIds.getAllValues())
                            .containsExactly(LOW_VALUES, FIRST_ACCOUNT),
                    () -> assertThat(typeCodes.getAllValues())
                            .containsExactly(LOW_VALUES, TYPE_CODE),
                    () -> assertThat(categoryCodes.getAllValues())
                            .containsExactly(LOW_VALUES, SECOND_CATEGORY),
                    () -> assertThat(pages.getAllValues())
                            .as("a key predicate advances the scan, never an offset")
                            .allMatch(page -> page.getPageNumber() == 0),
                    () -> assertThat(pages.getAllValues()).allMatch(page -> page.getPageSize() > 0),
                    () -> assertThat(pages.getAllValues().get(1).getPageSize())
                            .isEqualTo(pages.getAllValues().get(0).getPageSize()));
        }

        @Test
        @DisplayName("the destination is mandatory, because the pass stands for a copy statement and a "
                + "copy with no destination is never an intent")
        void theDestinationIsMandatory() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.readTransactionCategoryBalanceFile(null))
                    .withMessageContaining("recordSink");
        }

        @Test
        @DisplayName("the cancellation-aware overload also requires a destination")
        void theCancellationAwareOverloadAlsoRequiresADestination() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.readTransactionCategoryBalanceFile(() -> false, null))
                    .withMessageContaining("recordSink");
        }

        @Test
        @DisplayName("a destination that fails stops the pass at that record rather than deferring the "
                + "failure until the whole cluster has been traversed")
        void aFailingDestinationStopsThePassAtThatRecord() {
            when(transactionCategoryBalanceRepository.findAfterKey(any(), any(), any(), any()))
                    .thenReturn(List.of(
                            categoryBalanceRecord(FIRST_ACCOUNT, FIRST_CATEGORY, "0.00"),
                            categoryBalanceRecord(FIRST_ACCOUNT, SECOND_CATEGORY, "0.00")));
            List<String> delivered = new ArrayList<>();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.readTransactionCategoryBalanceFile(record -> {
                        delivered.add(record.getTrancatCd());
                        throw new IllegalStateException("the destination refused the record");
                    }))
                    .withMessage("the destination refused the record");

            assertAll(
                    () -> assertThat(delivered)
                            .as("only the first record was attempted")
                            .containsExactly(FIRST_CATEGORY),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("an empty cluster ends at end of file with a count of zero and one retrieval")
        void anEmptyClusterEndsAtEndOfFileWithACountOfZero() {
            when(transactionCategoryBalanceRepository.findAfterKey(any(), any(), any(), any()))
                    .thenReturn(List.of());
            List<TransactionCategoryBalance> delivered = new ArrayList<>();

            FileMaintenanceService.FileReadSummary summary =
                    service.readTransactionCategoryBalanceFile(delivered::add);

            assertAll(
                    () -> assertThat(summary.recordsRead()).isZero(),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> assertThat(delivered).isEmpty(),
                    () -> verify(transactionCategoryBalanceRepository, times(1))
                            .findAfterKey(any(), any(), any(), any()),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("the unload abends under its own literals, which name the DD the job step reads "
                + "rather than borrowing a member's literal for a different data set")
        void theUnloadAbendsUnderItsOwnLiterals() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(transactionCategoryBalanceRepository.findAfterKey(any(), any(), any(), any()))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readTransactionCategoryBalanceFile(record -> { }))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.culprit()).isEqualTo(UNLOAD_STEP),
                            () -> assertThat(abend.reason()).isEqualTo("ERROR OPENING TCATBALF")));

            ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
            verify(abendService).abendBatch(arguments.capture(), arguments.capture(),
                    arguments.capture(), arguments.capture(), arguments.capture());
            assertThat(arguments.getAllValues()).containsExactly(
                    UNLOAD_STEP, "ERROR OPENING TCATBALF", RAW_PERMANENT_ERROR, OPENING, DD_TCATBALF);
        }

        @Test
        @DisplayName("a failure after the open abends under the unload's own read literal")
        void aFailureAfterTheOpenAbendsUnderTheUnloadsReadLiteral() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(transactionCategoryBalanceRepository.findAfterKey(any(), any(), any(), any()))
                    .thenReturn(List.of(categoryBalanceRecord(FIRST_ACCOUNT, FIRST_CATEGORY, "0.00")))
                    .thenThrow(unreachableCluster());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readTransactionCategoryBalanceFile(record -> { }))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("ERROR READING TCATBALF"));
        }

        @Test
        @DisplayName("the record image the unload emits carries only a correlation token: neither the "
                + "composite key nor the balance reaches the diagnostic channel")
        void theUnloadWithholdsItsKeyAndItsBalance() {
            when(transactionCategoryBalanceRepository.findAfterKey(any(), any(), any(), any()))
                    .thenReturn(List.of(
                            categoryBalanceRecord(FIRST_ACCOUNT, FIRST_CATEGORY, "98765.43")))
                    .thenReturn(List.of());

            service.readTransactionCategoryBalanceFile(record -> { });

            assertAll(
                    () -> assertThat(renderedDiagnostics())
                            .filteredOn(line -> line.startsWith(
                                    "recordType=transaction-category-balance recordRef="))
                            .as("a copy utility emitted no record image at all, so one line is already "
                                    + "more than the step produced and a second would be an invention")
                            .hasSize(1)
                            .allMatch(line -> line.matches(
                                    "recordType=transaction-category-balance "
                                            + "recordRef=\\[REDACTED] ref=[0-9a-f]{24}")),
                    () -> assertThat(renderedDiagnostics())
                            .noneMatch(line -> line.contains(FIRST_ACCOUNT)),
                    () -> assertThat(renderedDiagnostics())
                            .noneMatch(line -> line.contains("98765.43")));
        }

        @Test
        @DisplayName("a delivered balance carries two fraction digits truncated toward zero: a third "
                + "digit is dropped rather than rounded, which is the whole difference between this "
                + "translation and a half-even one")
        void aDeliveredBalanceIsTruncatedTowardZeroAtTwoFractionDigits() {
            when(transactionCategoryBalanceRepository.findAfterKey(any(), any(), any(), any()))
                    .thenReturn(List.of(
                            categoryBalanceRecord(FIRST_ACCOUNT, FIRST_CATEGORY, "1.015"),
                            categoryBalanceRecord(FIRST_ACCOUNT, SECOND_CATEGORY, "7.999")))
                    .thenReturn(List.of());
            List<TransactionCategoryBalance> delivered = new ArrayList<>();

            service.readTransactionCategoryBalanceFile(delivered::add);

            assertAll(
                    () -> assertThat(delivered).hasSize(2),
                    () -> assertThat(delivered.get(0).getTranCatBal())
                            .as("a half-even or half-up policy would have produced one more cent")
                            .isEqualTo(new BigDecimal("1.01")),
                    () -> assertThat(delivered.get(0).getTranCatBal().scale()).isEqualTo(2),
                    () -> assertThat(delivered.get(1).getTranCatBal())
                            .isEqualTo(new BigDecimal("7.99")),
                    () -> assertThat(delivered.get(1).getTranCatBal().scale()).isEqualTo(2));
        }
    }

    @Nested
    @DisplayName("record-layout fidelity: the identity of every record is its own business key, and no "
            + "surrogate exists anywhere for a reader to address a page with")
    class RecordLayoutFidelity {

        @Test
        @DisplayName("the account record is three hundred encoded bytes, an eleven-character key "
                + "followed by two hundred and eighty-nine bytes of remainder, and the identifier of "
                + "the entity is that same key")
        void theAccountRecordIsThreeHundredBytesWithItsKeyLeading() {
            TestDataFactory.AccountBuilder builder = TestDataFactory.account().acctId(FIRST_ACCOUNT);
            String image = builder.image();
            Account entity = builder.build();

            assertAll(
                    () -> assertThat(encodedLengthOf(image)).isEqualTo(300),
                    () -> assertThat(image.substring(0, 11)).isEqualTo(FIRST_ACCOUNT),
                    () -> assertThat(encodedLengthOf(image.substring(11))).isEqualTo(289),
                    () -> assertThat(entity.getAcctId()).isEqualTo(image.substring(0, 11)),
                    () -> assertThat(entity.getAcctId()).isEqualTo(FIRST_ACCOUNT));
        }

        @Test
        @DisplayName("the card record is one hundred and fifty encoded bytes and its identifier is the "
                + "sixteen-character card number that leads it")
        void theCardRecordIsOneHundredAndFiftyBytes() {
            TestDataFactory.CardBuilder builder = TestDataFactory.card().cardNumber(FIRST_CARD);
            String image = builder.image();

            assertAll(
                    () -> assertThat(encodedLengthOf(image)).isEqualTo(150),
                    () -> assertThat(image.substring(0, 16)).isEqualTo(FIRST_CARD),
                    () -> assertThat(builder.build().getCardNum()).isEqualTo(FIRST_CARD));
        }

        @Test
        @DisplayName("the customer record is five hundred encoded bytes and its identifier is the "
                + "nine-character customer identifier that leads it")
        void theCustomerRecordIsFiveHundredBytes() {
            TestDataFactory.CustomerBuilder builder =
                    TestDataFactory.customer().customerId(FIRST_CUSTOMER);
            String image = builder.image();

            assertAll(
                    () -> assertThat(encodedLengthOf(image)).isEqualTo(500),
                    () -> assertThat(image.substring(0, 9)).isEqualTo(FIRST_CUSTOMER),
                    () -> assertThat(builder.build().getCustId()).isEqualTo(FIRST_CUSTOMER));
        }

        @Test
        @DisplayName("the category-balance record is fifty encoded bytes and its identity is the whole "
                + "seventeen-character composite key that leads it")
        void theCategoryBalanceRecordIsFiftyBytes() {
            TestDataFactory.TransactionCategoryBalanceBuilder builder =
                    TestDataFactory.transactionCategoryBalance()
                            .accountId(FIRST_ACCOUNT)
                            .typeCode(TYPE_CODE)
                            .categoryCode(FIRST_CATEGORY);
            String image = builder.image();
            TransactionCategoryBalance entity = builder.build();

            assertAll(
                    () -> assertThat(encodedLengthOf(image)).isEqualTo(50),
                    () -> assertThat(image.substring(0, 17))
                            .isEqualTo(categoryBalanceKeyImage(FIRST_ACCOUNT, FIRST_CATEGORY)),
                    () -> assertThat(entity.getTrancatAcctId()).isEqualTo(FIRST_ACCOUNT),
                    () -> assertThat(entity.getTrancatTypeCd()).isEqualTo(TYPE_CODE),
                    () -> assertThat(entity.getTrancatCd()).isEqualTo(FIRST_CATEGORY));
        }

        @Test
        @DisplayName("the cross-reference record is thirty-six data bytes in the delivered fixture and "
                + "fifty in the mainframe data set, the difference being fourteen bytes of filler, and "
                + "its identifier is the sixteen-character card number that leads both forms")
        void theCrossReferenceRecordCarriesBothOfItsMeasuredWidths() {
            TestDataFactory.CardCrossReferenceBuilder builder =
                    TestDataFactory.cardCrossReference().cardNumber(FIRST_CARD);
            String fixtureImage = builder.fixtureImage();
            String datasetImage = builder.datasetImage();

            assertAll(
                    () -> assertThat(encodedLengthOf(fixtureImage)).isEqualTo(36),
                    () -> assertThat(encodedLengthOf(datasetImage)).isEqualTo(50),
                    () -> assertThat(encodedLengthOf(datasetImage) - encodedLengthOf(fixtureImage))
                            .as("the difference is filler, not data")
                            .isEqualTo(14),
                    () -> assertThat(datasetImage.substring(0, 36)).isEqualTo(fixtureImage),
                    () -> assertThat(fixtureImage.substring(0, 16)).isEqualTo(FIRST_CARD),
                    () -> assertThat(builder.build().getXrefCardNum()).isEqualTo(FIRST_CARD));
        }

        @Test
        @DisplayName("the next page is addressed by the business key of the last record delivered, so "
                + "there is no surrogate identifier anywhere for the reader to have used instead")
        void theNextPageIsAddressedByTheBusinessKeyItself() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT), accountRecord(SECOND_ACCOUNT)))
                    .thenReturn(List.of());

            service.readAccountFile();

            ArgumentCaptor<String> cursors = ArgumentCaptor.forClass(String.class);
            verify(accountScanRepository, times(2))
                    .findByAcctIdGreaterThanOrderByAcctIdAsc(cursors.capture(), any());
            assertAll(
                    () -> assertThat(cursors.getAllValues().get(1))
                            .isEqualTo(accountRecord(SECOND_ACCOUNT).getAcctId()),
                    () -> assertThat(encodedLengthOf(cursors.getAllValues().get(1)))
                            .as("eleven characters, the width of the account key in the record image")
                            .isEqualTo(11));
        }
    }

    @Nested
    @DisplayName("fields that are present but blank, and fields that are legitimately absent")
    class BlankAndAbsentFields {

        @Test
        @DisplayName("a group identifier of ten spaces is carried untrimmed and is never treated as "
                + "absent: the record is read and counted like any other, and the value keeps its width")
        void aTenSpaceGroupIdentifierIsCarriedUntrimmed() {
            TestDataFactory.AccountBuilder builder = TestDataFactory.account()
                    .acctId(FIRST_ACCOUNT)
                    .groupId(TEN_SPACE_GROUP_ID);
            Account record = builder.build();
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(record))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readAccountFile();

            assertAll(
                    () -> assertThat(summary.recordsRead())
                            .as("a blank group identifier does not make the record skippable")
                            .isEqualTo(1L),
                    () -> assertThat(record.getAcctGroupId()).isEqualTo(TEN_SPACE_GROUP_ID),
                    () -> assertThat(encodedLengthOf(record.getAcctGroupId())).isEqualTo(10),
                    () -> assertThat(record.getAcctGroupId()).isNotNull().isNotEmpty(),
                    () -> assertThat(builder.image().substring(112, 122))
                            .as("ten blank bytes at the group identifier's own offset in the record "
                                    + "image, present rather than absent")
                            .isEqualTo(TEN_SPACE_GROUP_ID),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("the seeded group identifier is exactly that ten-space value, so a test that "
                + "trimmed it would be asserting something the seed never contains")
        void theSeededGroupIdentifierIsTenSpaces() {
            assertAll(
                    () -> assertThat(TestDataFactory.SEEDED_ACCOUNT_GROUP_ID)
                            .isEqualTo(TEN_SPACE_GROUP_ID),
                    () -> assertThat(encodedLengthOf(TestDataFactory.SEEDED_ACCOUNT_GROUP_ID))
                            .isEqualTo(10));
        }

        @Test
        @DisplayName("a customer whose national identifier is absent is read without throwing, because "
                + "that column is the schema's only nullable one and the reference seed leaves it absent")
        void aCustomerWithAnAbsentNationalIdentifierIsReadWithoutThrowing() {
            Customer record = TestDataFactory.customer().customerId(FIRST_CUSTOMER).build();
            when(customerRepository.findByCustIdGreaterThanOrderByCustIdAsc(any(), any()))
                    .thenReturn(List.of(record))
                    .thenReturn(List.of());

            assertThatNoException().isThrownBy(() -> {
                FileMaintenanceService.FileReadSummary summary = service.readCustomerFile();
                assertThat(summary.recordsRead()).isEqualTo(1L);
            });

            assertAll(
                    () -> assertThat(record.getCustSsn()).isNull(),
                    () -> assertThat(renderedDiagnostics())
                            .anyMatch(line -> line.startsWith("recordType=customer recordRef=")),
                    () -> verifyNoInteractions(abendService));
        }
    }

    @Nested
    @DisplayName("absent and boundary input at every public entry point, and cooperative cancellation")
    class AbsentInputAndCancellation {

        @Test
        @DisplayName("every reader refuses an absent cancellation probe by name, because a reader with "
                + "no probe is a wiring error rather than a request to read everything")
        void everyReaderRefusesAnAbsentCancellationProbe() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> service.readAccountFile(null))
                            .withMessageContaining("stopRequested"),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> service.readCardFile(null))
                            .withMessageContaining("stopRequested"),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> service.readCardCrossReferenceFile(null))
                            .withMessageContaining("stopRequested"),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> service.readCustomerFile(null))
                            .withMessageContaining("stopRequested"),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> service.readTransactionCategoryBalanceFile(
                                    null, record -> { }))
                            .withMessageContaining("stopRequested"),
                    () -> verifyNoInteractions(accountScanRepository, cardScanRepository,
                            cardCrossReferenceScanRepository, transactionCategoryBalanceRepository,
                            customerRepository, abendService));
        }

        @Test
        @DisplayName("an absent status and absent context do not fail the status display, and an empty "
                + "status renders the marker rather than raising")
        void absentAndEmptyStatusValuesNeverFailTheStatusDisplay() {
            assertAll(
                    () -> assertThatNoException()
                            .isThrownBy(() -> service.displayIoStatus(null, null, null)),
                    () -> assertThatNoException()
                            .isThrownBy(() -> service.displayIoStatus("", READING, DD_ACCTFILE)),
                    () -> assertThatNoException()
                            .isThrownBy(() -> service.displayIoStatus(RAW_PERMANENT_ERROR, null, null)));

            assertThat(renderedDiagnostics())
                    .hasSize(3)
                    .allMatch(line -> line.startsWith(FileStatusException.DISPLAY_PREFIX));
        }

        @Test
        @DisplayName("a stop requested before the first record ends the run before any retrieval is "
                + "issued, and a cancellation is never routed to the abend path")
        void aStopRequestedBeforeTheFirstRecordPreventsEveryRetrieval() {
            assertAll(
                    () -> assertThatExceptionOfType(CancellationException.class)
                            .isThrownBy(() -> service.readAccountFile(() -> true)),
                    () -> assertThatExceptionOfType(CancellationException.class)
                            .isThrownBy(() -> service.readCardFile(() -> true)),
                    () -> assertThatExceptionOfType(CancellationException.class)
                            .isThrownBy(() -> service.readCardCrossReferenceFile(() -> true)),
                    () -> assertThatExceptionOfType(CancellationException.class)
                            .isThrownBy(() -> service.readCustomerFile(() -> true)),
                    () -> assertThatExceptionOfType(CancellationException.class)
                            .isThrownBy(() -> service.readTransactionCategoryBalanceFile(
                                    () -> true, record -> { })),
                    () -> verifyNoInteractions(accountScanRepository, cardScanRepository,
                            cardCrossReferenceScanRepository, transactionCategoryBalanceRepository,
                            customerRepository, abendService));
        }

        @Test
        @DisplayName("a stop requested between records ends the loop after the open and before the "
                + "record is accepted, leaving the abend path untouched")
        void aStopRequestedBetweenRecordsEndsTheLoopWithoutAbending() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT)));
            AtomicInteger probes = new AtomicInteger();
            BooleanSupplier stopAfterTheOpen = () -> probes.getAndIncrement() > 0;

            assertThatExceptionOfType(CancellationException.class)
                    .isThrownBy(() -> service.readAccountFile(stopAfterTheOpen));

            assertAll(
                    () -> verify(accountScanRepository, times(1))
                            .findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()),
                    () -> assertThat(renderedDiagnostics())
                            .noneMatch(line -> line.startsWith("recordType=account")),
                    () -> assertThat(renderedDiagnostics())
                            .noneMatch(line -> line.contains("END OF EXECUTION OF PROGRAM")),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("a probe that never stops lets the cancellation-aware overload read exactly what "
                + "the plain overload would")
        void aProbeThatNeverStopsReadsTheWholeCluster() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT), accountRecord(SECOND_ACCOUNT)))
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary summary = service.readAccountFile(() -> false);

            assertAll(
                    () -> assertThat(summary.recordsRead()).isEqualTo(2L),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("the cancellation-aware card, cross-reference, customer and unload overloads each "
                + "read their own cluster to end of file when nothing stops them")
        void theRemainingCancellationAwareOverloadsReadTheirOwnClusters() {
            when(cardScanRepository.findByCardNumGreaterThanOrderByCardNumAsc(any(), any()))
                    .thenReturn(List.of(cardRecord(FIRST_CARD)))
                    .thenReturn(List.of());
            when(cardCrossReferenceScanRepository
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                    .thenReturn(List.of(crossReferenceRecord(FIRST_CARD)))
                    .thenReturn(List.of());
            when(customerRepository.findByCustIdGreaterThanOrderByCustIdAsc(any(), any()))
                    .thenReturn(List.of(customerRecord(FIRST_CUSTOMER)))
                    .thenReturn(List.of());
            when(transactionCategoryBalanceRepository.findAfterKey(any(), any(), any(), any()))
                    .thenReturn(List.of(categoryBalanceRecord(FIRST_ACCOUNT, FIRST_CATEGORY, "0.00")))
                    .thenReturn(List.of());
            List<TransactionCategoryBalance> delivered = new ArrayList<>();

            assertAll(
                    () -> assertThat(service.readCardFile(() -> false).programName())
                            .isEqualTo(CBACT02C),
                    () -> assertThat(service.readCardCrossReferenceFile(() -> false).programName())
                            .isEqualTo(CBACT03C),
                    () -> assertThat(service.readCustomerFile(() -> false).programName())
                            .isEqualTo(CBCUS01C),
                    () -> assertThat(service.readTransactionCategoryBalanceFile(
                                    () -> false, delivered::add).programName())
                            .isEqualTo(UNLOAD_STEP),
                    () -> assertThat(delivered).hasSize(1),
                    () -> verifyNoInteractions(abendService));
        }
    }

    @Nested
    @DisplayName("the construction contract and the returned summary refuse what they could not have "
            + "produced")
    class ContractsRefuseWhatTheyCannotCarry {

        @Test
        @DisplayName("every collaborator is required, because a reader with a missing one could not fail "
                + "in any way an operator would recognise")
        void everyCollaboratorIsRequiredAtConstruction() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(null, accountScanRepository,
                                    cardRepository, cardScanRepository, cardCrossReferenceRepository,
                                    cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, customerRepository,
                                    abendService))
                            .withMessageContaining("accountRepository"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(accountRepository, null,
                                    cardRepository, cardScanRepository, cardCrossReferenceRepository,
                                    cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, customerRepository,
                                    abendService))
                            .withMessageContaining("accountScanRepository"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(accountRepository, accountScanRepository,
                                    null, cardScanRepository, cardCrossReferenceRepository,
                                    cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, customerRepository,
                                    abendService))
                            .withMessageContaining("cardRepository"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(accountRepository, accountScanRepository,
                                    cardRepository, null, cardCrossReferenceRepository,
                                    cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, customerRepository,
                                    abendService))
                            .withMessageContaining("cardScanRepository"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(accountRepository, accountScanRepository,
                                    cardRepository, cardScanRepository, null,
                                    cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, customerRepository,
                                    abendService))
                            .withMessageContaining("cardCrossReferenceRepository"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(accountRepository, accountScanRepository,
                                    cardRepository, cardScanRepository, cardCrossReferenceRepository,
                                    null, transactionCategoryBalanceRepository, customerRepository,
                                    abendService))
                            .withMessageContaining("cardCrossReferenceScanRepository"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(accountRepository, accountScanRepository,
                                    cardRepository, cardScanRepository, cardCrossReferenceRepository,
                                    cardCrossReferenceScanRepository, null, customerRepository,
                                    abendService))
                            .withMessageContaining("transactionCategoryBalanceRepository"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(accountRepository, accountScanRepository,
                                    cardRepository, cardScanRepository, cardCrossReferenceRepository,
                                    cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, null, abendService))
                            .withMessageContaining("customerRepository"),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService(accountRepository, accountScanRepository,
                                    cardRepository, cardScanRepository, cardCrossReferenceRepository,
                                    cardCrossReferenceScanRepository,
                                    transactionCategoryBalanceRepository, customerRepository, null))
                            .withMessageContaining("abendService"));
        }

        @Test
        @DisplayName("the summary refuses an absent name, an absent terminal status and a negative "
                + "count, none of which a completed read could have produced")
        void theSummaryRefusesValuesItCouldNotHaveProduced() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService.FileReadSummary(
                                    null, DD_ACCTFILE, 0L, RAW_END_OF_FILE)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService.FileReadSummary(
                                    CBACT01C, null, 0L, RAW_END_OF_FILE)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new FileMaintenanceService.FileReadSummary(
                                    CBACT01C, DD_ACCTFILE, 0L, null)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                            () -> new FileMaintenanceService.FileReadSummary(
                                    CBACT01C, DD_ACCTFILE, -1L, RAW_END_OF_FILE)));
        }

        @Test
        @DisplayName("a terminal status other than end of file is not a normal ending, which is how a "
                + "caller distinguishes the one route out of a read loop that returns")
        void aTerminalStatusOtherThanEndOfFileIsNotANormalEnding() {
            FileMaintenanceService.FileReadSummary endedAtSuccess =
                    new FileMaintenanceService.FileReadSummary(CBACT01C, DD_ACCTFILE, 5L, RAW_SUCCESS);
            FileMaintenanceService.FileReadSummary endedAtEndOfFile =
                    new FileMaintenanceService.FileReadSummary(
                            CBACT01C, DD_ACCTFILE, 5L, RAW_END_OF_FILE);

            assertAll(
                    () -> assertThat(endedAtSuccess.endedAtEndOfFile()).isFalse(),
                    () -> assertThat(endedAtEndOfFile.endedAtEndOfFile()).isTrue(),
                    () -> assertThat(endedAtEndOfFile.recordsRead()).isEqualTo(5L),
                    () -> assertThat(endedAtEndOfFile.programName()).isEqualTo(CBACT01C),
                    () -> assertThat(endedAtEndOfFile.resourceName()).isEqualTo(DD_ACCTFILE),
                    () -> assertThat(endedAtEndOfFile.terminalFileStatus())
                            .isEqualTo(RAW_END_OF_FILE),
                    () -> assertThat(endedAtEndOfFile).isNotEqualTo(endedAtSuccess));
        }

        @Test
        @DisplayName("a count of zero is a legitimate summary, because an empty cluster is a normal "
                + "outcome rather than a failure")
        void aCountOfZeroIsALegitimateSummary() {
            // Zero is the one count that the guard above could plausibly have refused along with the
            // negative one, so what matters is not merely that construction is admitted but that the
            // summary it produces still reports a NORMAL ENDING. An empty cluster reaches end of file
            // without reading a record, and a caller distinguishes that from a failure by the predicate
            // rather than by the count.
            final FileMaintenanceService.FileReadSummary emptyCluster =
                    new FileMaintenanceService.FileReadSummary(
                            UNLOAD_STEP, DD_TCATBALF, 0L, RAW_END_OF_FILE);

            assertAll(
                    () -> assertThat(emptyCluster.programName()).isEqualTo(UNLOAD_STEP),
                    () -> assertThat(emptyCluster.resourceName()).isEqualTo(DD_TCATBALF),
                    () -> assertThat(emptyCluster.recordsRead())
                            .as("zero is carried through as zero, neither rejected nor normalised")
                            .isZero(),
                    () -> assertThat(emptyCluster.terminalFileStatus()).isEqualTo(RAW_END_OF_FILE),
                    () -> assertThat(emptyCluster.endedAtEndOfFile())
                            .as("an empty cluster ended normally: the read loop reached end of file "
                                    + "without a record, which is not the error route out of it")
                            .isTrue(),
                    () -> assertThat(emptyCluster)
                            .as("and a zero-count summary is a distinct value from an otherwise "
                                    + "identical one that read a record")
                            .isNotEqualTo(new FileMaintenanceService.FileReadSummary(
                                    UNLOAD_STEP, DD_TCATBALF, 1L, RAW_END_OF_FILE)));
        }
    }

    @Nested
    @DisplayName("the service is stateless, so no count, cursor or status carries over between "
            + "invocations or between readers")
    class TheServiceIsStateless {

        @Test
        @DisplayName("a second read of the same cluster reports its own count rather than accumulating "
                + "the first, and addresses its first page from the low-values bound again")
        void countsDoNotAccumulateAcrossInvocations() {
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT), accountRecord(SECOND_ACCOUNT)))
                    .thenReturn(List.of())
                    .thenReturn(List.of());

            FileMaintenanceService.FileReadSummary first = service.readAccountFile();
            FileMaintenanceService.FileReadSummary second = service.readAccountFile();

            ArgumentCaptor<String> cursors = ArgumentCaptor.forClass(String.class);
            verify(accountScanRepository, times(3))
                    .findByAcctIdGreaterThanOrderByAcctIdAsc(cursors.capture(), any());
            assertAll(
                    () -> assertThat(first.recordsRead()).isEqualTo(2L),
                    () -> assertThat(second.recordsRead()).isZero(),
                    () -> assertThat(cursors.getAllValues())
                            .containsExactly(LOW_VALUES, SECOND_ACCOUNT, LOW_VALUES),
                    () -> verifyNoInteractions(abendService));
        }

        @Test
        @DisplayName("an abend on one reader leaves every other reader able to complete normally, "
                + "because the count and the status live in the invocation and not in the service")
        void anAbendOnOneReaderDoesNotDisableAnother() {
            abendServiceRaisesAfterRecordingWhatWasAlreadyEmitted();
            when(cardScanRepository.findByCardNumGreaterThanOrderByCardNumAsc(any(), any()))
                    .thenThrow(unreachableCluster());
            when(accountScanRepository.findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any()))
                    .thenReturn(List.of(accountRecord(FIRST_ACCOUNT)))
                    .thenReturn(List.of());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.readCardFile());

            FileMaintenanceService.FileReadSummary summary = service.readAccountFile();
            assertAll(
                    () -> assertThat(summary.recordsRead()).isEqualTo(1L),
                    () -> assertThat(summary.endedAtEndOfFile()).isTrue(),
                    () -> assertThat(summary.programName()).isEqualTo(CBACT01C));
        }
    }

}
