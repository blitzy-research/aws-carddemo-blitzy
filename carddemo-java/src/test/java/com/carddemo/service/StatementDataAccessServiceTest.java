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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.StatementDataAccessService.StatementFileRequest;
import com.carddemo.service.StatementDataAccessService.StatementFileResponse;
import com.carddemo.support.SeededRecordFixture;
import com.carddemo.util.AccountRecordMapper;
import com.carddemo.util.CardXrefRecordMapper;
import com.carddemo.util.CustomerRecordMapper;
import com.carddemo.util.TransactionRecordMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link StatementDataAccessService}, the statement feature's file-handling subprogram
 * translated from {@code [app/cbl/CBSTM03B.CBL]}.
 *
 * <p>The test is organised around the three properties of the legacy program that a plausible but wrong
 * translation would lose, because those are the properties worth asserting: the unguarded
 * consecutive-check shape that makes an unhandled operation return the caller's own status rather than an
 * error, the publication of a status on every path out of a handler including the path that did nothing,
 * and the honouring of the caller's runtime key length together with the two <em>different</em> key
 * paddings the two random files' picture clauses demand.
 */
@DisplayName("StatementDataAccessService - CBSTM03B file handling")
class StatementDataAccessServiceTest {

    /** A status no handler ever produces, so observing it proves the caller's value survived untouched. */
    private static final String STALE_STATUS = "77";

    /** Structural marker the customer entity requires of a protected value. */
    private static final String ENVELOPE_PREFIX = "ENC1:";

    /** Smallest decoded body the customer entity accepts for a protected value. */
    private static final int ENVELOPE_MINIMUM_BYTES = 28;

    private TransactionRepository transactionRepository;

    private CardCrossReferenceRepository cardCrossReferenceRepository;

    private CustomerRepository customerRepository;

    private AccountRepository accountRepository;

    private StatementDataAccessService service;

    @BeforeEach
    void setUp() {
        this.transactionRepository = mock(TransactionRepository.class);
        this.cardCrossReferenceRepository = mock(CardCrossReferenceRepository.class);
        this.customerRepository = mock(CustomerRepository.class);
        this.accountRepository = mock(AccountRepository.class);
        this.service = new StatementDataAccessService(this.transactionRepository,
                this.cardCrossReferenceRepository, this.customerRepository, this.accountRepository);
    }

    // ------------------------------------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------------------------------------

    /** Width at which the customer record is held, both in the copybook and in the seed fixture. */
    private static final int CUSTOMER_RECORD_WIDTH = 500;

    /**
     * Returns the first record of a seed fixture. The fixture's ordinals are one-based, and the
     * cross-reference fixture is deliberately loaded at its 36-byte data width rather than the 50-byte
     * canonical width, because that is how the ASCII seed writes it - the 14-byte filler is absent.
     *
     * @param fileName    the fixture file name
     * @param recordWidth the width at which that fixture holds its records
     * @return the first record image
     */
    private static String firstFixtureRecord(final String fileName, final int recordWidth) {
        return SeededRecordFixture.load(fileName, recordWidth).record(1);
    }

    private static Account seededAccount() {
        return AccountRecordMapper.fromRecord(firstFixtureRecord("acctdata.txt",
                AccountRecordMapper.RECORD_LENGTH));
    }

    private static CardCrossReference seededCrossReference() {
        return CardXrefRecordMapper.fromRecord(firstFixtureRecord("cardxref.txt",
                CardXrefRecordMapper.DATA_RECORD_LENGTH));
    }

    private static Transaction seededTransaction() {
        return TransactionRecordMapper.fromRecord(firstFixtureRecord("dailytran.txt",
                TransactionRecordMapper.RECORD_LENGTH));
    }

    private static Customer seededCustomer() {
        return CustomerRecordMapper.fromRecord(
                firstFixtureRecord("custdata.txt", CUSTOMER_RECORD_WIDTH),
                StatementDataAccessServiceTest::seal);
    }

    /**
     * Reversibly wraps a cleartext value in the envelope shape the customer entity insists on, so that a
     * test can seal a fixture and reveal it again without holding real key material.
     *
     * @param cleartext the value to wrap
     * @return an envelope the entity accepts
     */
    private static String seal(final String cleartext) {
        final byte[] clear = cleartext.getBytes(StandardCharsets.US_ASCII);
        final byte[] body = new byte[Math.max(ENVELOPE_MINIMUM_BYTES, clear.length + 1)];
        body[0] = (byte) clear.length;
        System.arraycopy(clear, 0, body, 1, clear.length);
        return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(body);
    }

    /**
     * The exact inverse of {@link #seal(String)}.
     *
     * @param envelope the envelope to unwrap
     * @return the original cleartext
     */
    private static String reveal(final String envelope) {
        final byte[] body =
                Base64.getDecoder().decode(envelope.substring(ENVELOPE_PREFIX.length()));
        return new String(body, 1, body[0], StandardCharsets.US_ASCII);
    }

    private static String payloadOf(final String recordImage) {
        return recordImage
                + " ".repeat(StatementDataAccessService.PAYLOAD_WIDTH - recordImage.length());
    }

    private static String blankPayload() {
        return " ".repeat(StatementDataAccessService.PAYLOAD_WIDTH);
    }

    private static StatementFileRequest request(final String ddName, final String operation) {
        return new StatementFileRequest(ddName, operation, STALE_STATUS, "", 1, blankPayload(), 0);
    }

    private static StatementFileRequest keyedRequest(final String ddName, final String key,
                                                     final int keyLength) {
        return new StatementFileRequest(ddName, StatementDataAccessService.OPERATION_READ_KEYED,
                STALE_STATUS, key, keyLength, blankPayload(), 0,
                StatementDataAccessServiceTest::reveal);
    }

    private static Page<Transaction> pageOf(final Transaction transaction) {
        return new PageImpl<>(List.of(transaction));
    }

    private static Page<CardCrossReference> pageOf(final CardCrossReference crossReference) {
        return new PageImpl<>(List.of(crossReference));
    }

    private void verifyNoRepositoryTouched() {
        verifyNoInteractions(this.transactionRepository, this.cardCrossReferenceRepository,
                this.customerRepository, this.accountRepository);
    }

    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("0000-START dispatcher [L116-L128]")
    class Dispatcher {

        @Test
        @DisplayName("rejects a null parameter object")
        void rejectsNullRequest() {
            assertThatNullPointerException()
                    .isThrownBy(() -> StatementDataAccessServiceTest.this.service.execute(null));
        }

        @ParameterizedTest
        @ValueSource(strings = {"NOSUCHDD", "trnxfile", "TRNX", "", "ACCTFIL"})
        @DisplayName("an unrecognised DD name performs no file action and changes nothing [L127-L128]")
        void unrecognisedDdNamePerformsNoAction(final String ddName) {
            final StatementFileRequest given = new StatementFileRequest(ddName,
                    StatementDataAccessService.OPERATION_READ, STALE_STATUS, "KEY", 3,
                    payloadOf("CARRIED"), 4);

            final StatementFileResponse actual =
                    StatementDataAccessServiceTest.this.service.execute(given);

            assertThat(actual.returnCode()).isEqualTo(STALE_STATUS);
            assertThat(actual.payload()).isEqualTo(payloadOf("CARRIED"));
            assertThat(actual.sequentialPosition()).isEqualTo(4);
            StatementDataAccessServiceTest.this.verifyNoRepositoryTouched();
        }

        @ParameterizedTest
        @CsvSource({"TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE"})
        @DisplayName("every declared DD name is routed to a handler that opens successfully")
        void everyDeclaredDdNameIsRouted(final String ddName) {
            final StatementFileResponse actual = StatementDataAccessServiceTest.this.service
                    .execute(request(ddName, StatementDataAccessService.OPERATION_OPEN));

            assertThat(actual.returnCode()).isEqualTo(FileStatus.SUCCESS.getCode());
            assertThat(actual.ddName()).isEqualTo(ddName);
        }
    }

    @Nested
    @DisplayName("The unguarded consecutive-check shape [L135-L149, L159-L173, L183-L198, L208-L223]")
    class FallThroughShape {

        @ParameterizedTest
        @CsvSource({
            "TRNXFILE, W", "TRNXFILE, Z", "TRNXFILE, Q", "TRNXFILE, ' '",
            "XREFFILE, W", "XREFFILE, Z", "XREFFILE, Q", "XREFFILE, ' '",
            "CUSTFILE, W", "CUSTFILE, Z", "CUSTFILE, Q", "CUSTFILE, ' '",
            "ACCTFILE, W", "ACCTFILE, Z", "ACCTFILE, Q", "ACCTFILE, ' '"
        })
        @DisplayName("an operation no handler tests returns the STALE status, never an error")
        void unhandledOperationReturnsStaleStatus(final String ddName, final String operation) {
            final StatementFileRequest given = new StatementFileRequest(ddName, operation,
                    STALE_STATUS, "KEY", 3, payloadOf("UNTOUCHED"), 6);

            final StatementFileResponse actual =
                    StatementDataAccessServiceTest.this.service.execute(given);

            assertThat(actual.returnCode())
                    .as("the status published on a fall-through is the caller's own")
                    .isEqualTo(STALE_STATUS);
            assertThat(actual.payload()).isEqualTo(payloadOf("UNTOUCHED"));
            assertThat(actual.sequentialPosition()).isEqualTo(6);
            StatementDataAccessServiceTest.this.verifyNoRepositoryTouched();
        }

        @Test
        @DisplayName("the declared but dead write and rewrite codes acquire no behaviour")
        void writeAndRewriteAreDead() {
            assertThat(StatementDataAccessService.OPERATION_WRITE).isEqualTo("W");
            assertThat(StatementDataAccessService.OPERATION_REWRITE).isEqualTo("Z");

            for (final String operation : List.of(StatementDataAccessService.OPERATION_WRITE,
                    StatementDataAccessService.OPERATION_REWRITE)) {
                final StatementFileResponse actual = StatementDataAccessServiceTest.this.service
                        .execute(request("TRNXFILE", operation));
                assertThat(actual.returnCode()).isEqualTo(STALE_STATUS);
            }
            StatementDataAccessServiceTest.this.verifyNoRepositoryTouched();
        }

        @ParameterizedTest
        @CsvSource({"TRNXFILE", "XREFFILE"})
        @DisplayName("a sequentially accessed file offers no keyed read [L33, L39]")
        void sequentialFilesRefuseKeyedRead(final String ddName) {
            final StatementFileResponse actual = StatementDataAccessServiceTest.this.service
                    .execute(request(ddName, StatementDataAccessService.OPERATION_READ_KEYED));

            assertThat(actual.returnCode()).isEqualTo(STALE_STATUS);
            StatementDataAccessServiceTest.this.verifyNoRepositoryTouched();
        }

        @ParameterizedTest
        @CsvSource({"CUSTFILE", "ACCTFILE"})
        @DisplayName("a randomly accessed file offers no sequential read [L45, L51]")
        void randomFilesRefuseSequentialRead(final String ddName) {
            final StatementFileResponse actual = StatementDataAccessServiceTest.this.service
                    .execute(request(ddName, StatementDataAccessService.OPERATION_READ));

            assertThat(actual.returnCode()).isEqualTo(STALE_STATUS);
            StatementDataAccessServiceTest.this.verifyNoRepositoryTouched();
        }
    }

    @Nested
    @DisplayName("Open and close [L136/L147, L160/L171, L184/L196, L209/L221]")
    class OpenAndClose {

        @ParameterizedTest
        @CsvSource({"TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE"})
        @DisplayName("an open reports success and positions at the first record")
        void openPositionsAtFirstRecord(final String ddName) {
            final StatementFileRequest given = new StatementFileRequest(ddName,
                    StatementDataAccessService.OPERATION_OPEN, STALE_STATUS, "", 1, blankPayload(), 9);

            final StatementFileResponse actual =
                    StatementDataAccessServiceTest.this.service.execute(given);

            assertThat(actual.returnCode()).isEqualTo(FileStatus.SUCCESS.getCode());
            assertThat(actual.sequentialPosition()).isZero();
            assertThat(actual.payload()).isEqualTo(blankPayload());
        }

        @ParameterizedTest
        @CsvSource({"TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE"})
        @DisplayName("a close reports success and discards the file position")
        void closeDiscardsPosition(final String ddName) {
            final StatementFileRequest given = new StatementFileRequest(ddName,
                    StatementDataAccessService.OPERATION_CLOSE, STALE_STATUS, "", 1, blankPayload(), 9);

            final StatementFileResponse actual =
                    StatementDataAccessServiceTest.this.service.execute(given);

            assertThat(actual.returnCode()).isEqualTo(FileStatus.SUCCESS.getCode());
            assertThat(actual.sequentialPosition()).isZero();
        }
    }

    @Nested
    @DisplayName("Sequential reads [L141, L165]")
    class SequentialReads {

        @Test
        @DisplayName("a transaction read returns the record image, advances the position and orders by "
                + "the record key")
        void transactionReadReturnsImageAndAdvances() {
            final Transaction transaction = seededTransaction();
            when(StatementDataAccessServiceTest.this.transactionRepository.findAll(any(Pageable.class)))
                    .thenReturn(pageOf(transaction));

            final StatementFileRequest given = new StatementFileRequest("TRNXFILE",
                    StatementDataAccessService.OPERATION_READ, STALE_STATUS, "", 1, blankPayload(), 3);
            final StatementFileResponse actual =
                    StatementDataAccessServiceTest.this.service.execute(given);

            assertThat(actual.returnCode()).isEqualTo(FileStatus.SUCCESS.getCode());
            assertThat(actual.payload())
                    .hasSize(StatementDataAccessService.PAYLOAD_WIDTH)
                    .isEqualTo(payloadOf(TransactionRecordMapper.toRecord(transaction)));
            assertThat(actual.sequentialPosition()).isEqualTo(4);

            final Pageable used = capturedTransactionPageable();
            assertThat(used.getPageNumber()).isEqualTo(3);
            assertThat(used.getPageSize()).isOne();
            assertThat(used.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "tranCardNum", "tranId"));
        }

        @Test
        @DisplayName("an exhausted transaction file yields the at-end status and changes nothing else")
        void transactionReadAtEnd() {
            when(StatementDataAccessServiceTest.this.transactionRepository.findAll(any(Pageable.class)))
                    .thenReturn(Page.empty());

            final StatementFileRequest given = new StatementFileRequest("TRNXFILE",
                    StatementDataAccessService.OPERATION_READ, STALE_STATUS, "", 1,
                    payloadOf("PRIOR"), 7);
            final StatementFileResponse actual =
                    StatementDataAccessServiceTest.this.service.execute(given);

            assertThat(actual.returnCode()).isEqualTo(FileStatus.END_OF_FILE.getCode());
            assertThat(actual.payload()).isEqualTo(payloadOf("PRIOR"));
            assertThat(actual.sequentialPosition()).isEqualTo(7);
        }

        @Test
        @DisplayName("a cross-reference read returns the fifty-byte image and orders by the card number")
        void crossReferenceReadReturnsImage() {
            final CardCrossReference crossReference = seededCrossReference();
            when(StatementDataAccessServiceTest.this.cardCrossReferenceRepository
                    .findAll(any(Pageable.class))).thenReturn(pageOf(crossReference));

            final StatementFileResponse actual = StatementDataAccessServiceTest.this.service
                    .execute(request("XREFFILE", StatementDataAccessService.OPERATION_READ));

            assertThat(actual.returnCode()).isEqualTo(FileStatus.SUCCESS.getCode());
            assertThat(actual.payload())
                    .isEqualTo(payloadOf(CardXrefRecordMapper.toRecord(crossReference)));
            assertThat(actual.sequentialPosition()).isOne();

            final Pageable used = capturedCrossReferencePageable();
            assertThat(used.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "xrefCardNum"));
        }

        @Test
        @DisplayName("an exhausted cross-reference file yields the at-end status")
        void crossReferenceReadAtEnd() {
            when(StatementDataAccessServiceTest.this.cardCrossReferenceRepository
                    .findAll(any(Pageable.class))).thenReturn(Page.empty());

            final StatementFileResponse actual = StatementDataAccessServiceTest.this.service
                    .execute(request("XREFFILE", StatementDataAccessService.OPERATION_READ));

            assertThat(actual.returnCode()).isEqualTo(FileStatus.END_OF_FILE.getCode());
        }

        private Pageable capturedTransactionPageable() {
            final org.mockito.ArgumentCaptor<Pageable> captor =
                    org.mockito.ArgumentCaptor.forClass(Pageable.class);
            verify(StatementDataAccessServiceTest.this.transactionRepository)
                    .findAll(captor.capture());
            return captor.getValue();
        }

        private Pageable capturedCrossReferencePageable() {
            final org.mockito.ArgumentCaptor<Pageable> captor =
                    org.mockito.ArgumentCaptor.forClass(Pageable.class);
            verify(StatementDataAccessServiceTest.this.cardCrossReferenceRepository)
                    .findAll(captor.capture());
            return captor.getValue();
        }
    }

    @Nested
    @DisplayName("Keyed reads and the runtime key length [L189, L214]")
    class KeyedReads {

        @Test
        @DisplayName("the customer key is sliced to the runtime length then LEFT justified into "
                + "PIC X(09) [L72, L189]")
        void customerKeyIsLeftJustified() {
            when(StatementDataAccessServiceTest.this.customerRepository.findById("123      "))
                    .thenReturn(Optional.empty());

            final StatementFileResponse actual = StatementDataAccessServiceTest.this.service
                    .execute(keyedRequest("CUSTFILE", "123456789012345678901234", 3));

            assertThat(actual.returnCode()).isEqualTo(FileStatus.RECORD_NOT_FOUND.getCode());
            verify(StatementDataAccessServiceTest.this.customerRepository).findById("123      ");
        }

        @Test
        @DisplayName("the account key is sliced to the runtime length then RIGHT justified and ZERO "
                + "filled into PIC 9(11) [L77, L214]")
        void accountKeyIsRightJustifiedAndZeroFilled() {
            when(StatementDataAccessServiceTest.this.accountRepository.findById("00000000123"))
                    .thenReturn(Optional.empty());

            final StatementFileResponse actual = StatementDataAccessServiceTest.this.service
                    .execute(keyedRequest("ACCTFILE", "123456789012345678901234", 3));

            assertThat(actual.returnCode()).isEqualTo(FileStatus.RECORD_NOT_FOUND.getCode());
            verify(StatementDataAccessServiceTest.this.accountRepository).findById("00000000123");
        }

        @Test
        @DisplayName("a full-width account key passes through unpadded")
        void accountKeyAtFullWidth() {
            final Account account = seededAccount();
            final String acctId = account.getAcctId();
            when(StatementDataAccessServiceTest.this.accountRepository.findById(acctId))
                    .thenReturn(Optional.of(account));

            final StatementFileResponse actual = StatementDataAccessServiceTest.this.service
                    .execute(keyedRequest("ACCTFILE", acctId, acctId.length()));

            assertThat(actual.returnCode()).isEqualTo(FileStatus.SUCCESS.getCode());
            assertThat(actual.payload()).isEqualTo(payloadOf(AccountRecordMapper.toRecord(account)));
            assertThat(actual.sequentialPosition())
                    .as("a keyed read moves no sequential cursor")
                    .isZero();
        }

        @Test
        @DisplayName("a customer keyed read consults the caller's revealing function")
        void customerReadUsesTheCallerSuppliedRevealer() {
            final Customer customer = seededCustomer();
            when(StatementDataAccessServiceTest.this.customerRepository
                    .findById(customer.getCustId())).thenReturn(Optional.of(customer));

            final StatementFileResponse actual = StatementDataAccessServiceTest.this.service
                    .execute(keyedRequest("CUSTFILE", customer.getCustId(),
                            customer.getCustId().length()));

            assertThat(actual.returnCode()).isEqualTo(FileStatus.SUCCESS.getCode());
            assertThat(actual.payload()).isEqualTo(payloadOf(CustomerRecordMapper.toRecord(customer,
                    StatementDataAccessServiceTest::reveal)));
        }

        @Test
        @DisplayName("a missing account yields the record-not-found status, not an exception")
        void missingAccountYieldsNotFound() {
            when(StatementDataAccessServiceTest.this.accountRepository.findById(any()))
                    .thenReturn(Optional.empty());

            final StatementFileResponse actual = StatementDataAccessServiceTest.this.service
                    .execute(keyedRequest("ACCTFILE", "00000000099", 11));

            assertThat(actual.returnCode()).isEqualTo(FileStatus.RECORD_NOT_FOUND.getCode());
            assertThat(actual.status()).contains(FileStatus.RECORD_NOT_FOUND);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, 26, 100})
        @DisplayName("a runtime key length outside the key field is rejected, as a bounds-checked "
                + "reference modification would be")
        void outOfRangeKeyLengthIsRejected(final int keyLength) {
            final StatementFileRequest given = keyedRequest("ACCTFILE", "12345", keyLength);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StatementDataAccessServiceTest.this.service.execute(given));
        }
    }

    @Nested
    @DisplayName("The parameter object mirrors 01 LK-M03B-AREA [L100-L112]")
    class ParameterObject {

        @Test
        @DisplayName("declared widths match the picture clauses")
        void declaredWidths() {
            assertThat(StatementDataAccessService.DD_NAME_WIDTH).isEqualTo(8);
            assertThat(StatementDataAccessService.OPERATION_WIDTH).isOne();
            assertThat(StatementDataAccessService.RETURN_CODE_WIDTH).isEqualTo(2);
            assertThat(StatementDataAccessService.KEY_WIDTH).isEqualTo(25);
            assertThat(StatementDataAccessService.PAYLOAD_WIDTH).isEqualTo(1000);
        }

        @Test
        @DisplayName("every character component is held at its picture width")
        void componentsAreNormalisedToPictureWidth() {
            final StatementFileRequest given =
                    new StatementFileRequest("DD", "OO", "0", "K", 1, "P", 0);

            assertThat(given.ddName()).isEqualTo("DD      ");
            assertThat(given.operation()).isEqualTo("O");
            assertThat(given.returnCode()).isEqualTo("0 ");
            assertThat(given.key()).hasSize(StatementDataAccessService.KEY_WIDTH);
            assertThat(given.payload()).hasSize(StatementDataAccessService.PAYLOAD_WIDTH);
            assertThat(given.regulatedFieldRevealer()).isNotNull();
        }

        @Test
        @DisplayName("a null component is treated as an unset field of spaces")
        void nullComponentsBecomeSpaces() {
            final StatementFileRequest given =
                    new StatementFileRequest(null, null, null, null, 1, null, 0, null);

            assertThat(given.ddName()).isEqualTo(" ".repeat(8));
            assertThat(given.returnCode()).isEqualTo("  ");
            assertThat(given.regulatedFieldRevealer()).isNotNull();
        }

        @Test
        @DisplayName("an over-wide key is truncated on the right, as a move into PIC X(25) would be")
        void overWideKeyIsTruncated() {
            final String tooWide = "A".repeat(30) + "TAIL";

            final StatementFileRequest given =
                    new StatementFileRequest("CUSTFILE", "K", "00", tooWide, 25, "", 0);

            assertThat(given.key()).isEqualTo("A".repeat(25));
        }

        @Test
        @DisplayName("a negative sequential position is refused by both halves of the pair")
        void negativePositionIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    new StatementFileRequest("CUSTFILE", "O", "00", "", 1, "", -1));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    new StatementFileResponse("CUSTFILE", "00", "", -1));
        }

        @Test
        @DisplayName("the predicates compare whole normalised fields")
        void predicatesCompareWholeFields() {
            final StatementFileRequest given = request("CUSTFILE",
                    StatementDataAccessService.OPERATION_OPEN);

            assertThat(given.hasDdName(StatementDataAccessService.DD_CUSTFILE)).isTrue();
            assertThat(given.hasDdName(StatementDataAccessService.DD_ACCTFILE)).isFalse();
            assertThat(given.hasOperation(StatementDataAccessService.OPERATION_OPEN)).isTrue();
            assertThat(given.hasOperation(StatementDataAccessService.OPERATION_CLOSE)).isFalse();
        }

        @Test
        @DisplayName("the response offers the typed status without replacing the raw one")
        void responseOffersTypedStatus() {
            assertThat(new StatementFileResponse("TRNXFILE", "00", "", 0).status())
                    .contains(FileStatus.SUCCESS);
            assertThat(new StatementFileResponse("TRNXFILE", "10", "", 0).status())
                    .contains(FileStatus.END_OF_FILE);
            assertThat(new StatementFileResponse("TRNXFILE", STALE_STATUS, "", 0).status()).isEmpty();
            assertThat(new StatementFileResponse("TRNXFILE", "23", "", 0).returnCode())
                    .isEqualTo(FileStatus.RECORD_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("the seven-component form defaults the revealing function to the identity")
        void sevenComponentFormDefaultsTheRevealer() {
            final StatementFileRequest given =
                    new StatementFileRequest("CUSTFILE", "O", "00", "", 1, "", 0);

            assertThat(given.regulatedFieldRevealer()).isEqualTo(UnaryOperator.identity());
        }

        @Test
        @DisplayName("the declared DD names and operation codes are the six the linkage declares")
        void declaredConstants() {
            assertThat(List.of(StatementDataAccessService.DD_TRNXFILE,
                    StatementDataAccessService.DD_XREFFILE, StatementDataAccessService.DD_CUSTFILE,
                    StatementDataAccessService.DD_ACCTFILE))
                    .containsExactly("TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE")
                    .allSatisfy(name -> assertThat(name)
                            .hasSize(StatementDataAccessService.DD_NAME_WIDTH));
            assertThat(List.of(StatementDataAccessService.OPERATION_OPEN,
                    StatementDataAccessService.OPERATION_CLOSE,
                    StatementDataAccessService.OPERATION_READ,
                    StatementDataAccessService.OPERATION_READ_KEYED,
                    StatementDataAccessService.OPERATION_WRITE,
                    StatementDataAccessService.OPERATION_REWRITE))
                    .containsExactly("O", "C", "R", "K", "W", "Z");
        }
    }

    @Nested
    @DisplayName("Statelessness")
    class Statelessness {

        @Test
        @DisplayName("the service rejects null collaborators")
        void rejectsNullCollaborators() {
            assertThatNullPointerException().isThrownBy(() -> new StatementDataAccessService(null,
                    StatementDataAccessServiceTest.this.cardCrossReferenceRepository,
                    StatementDataAccessServiceTest.this.customerRepository,
                    StatementDataAccessServiceTest.this.accountRepository));
            assertThatNullPointerException().isThrownBy(() -> new StatementDataAccessService(
                    StatementDataAccessServiceTest.this.transactionRepository, null,
                    StatementDataAccessServiceTest.this.customerRepository,
                    StatementDataAccessServiceTest.this.accountRepository));
            assertThatNullPointerException().isThrownBy(() -> new StatementDataAccessService(
                    StatementDataAccessServiceTest.this.transactionRepository,
                    StatementDataAccessServiceTest.this.cardCrossReferenceRepository, null,
                    StatementDataAccessServiceTest.this.accountRepository));
            assertThatNullPointerException().isThrownBy(() -> new StatementDataAccessService(
                    StatementDataAccessServiceTest.this.transactionRepository,
                    StatementDataAccessServiceTest.this.cardCrossReferenceRepository,
                    StatementDataAccessServiceTest.this.customerRepository, null));
        }

        @Test
        @DisplayName("two interleaved sequential reads at different positions do not influence each "
                + "other, because no cursor is held by the service")
        void interleavedReadsAreIndependent() {
            final Transaction transaction = seededTransaction();
            when(StatementDataAccessServiceTest.this.transactionRepository.findAll(any(Pageable.class)))
                    .thenReturn(pageOf(transaction));

            final StatementFileResponse first = StatementDataAccessServiceTest.this.service
                    .execute(new StatementFileRequest("TRNXFILE",
                            StatementDataAccessService.OPERATION_READ, "00", "", 1, blankPayload(), 0));
            final StatementFileResponse second = StatementDataAccessServiceTest.this.service
                    .execute(new StatementFileRequest("TRNXFILE",
                            StatementDataAccessService.OPERATION_READ, "00", "", 1, blankPayload(), 40));

            assertThat(first.sequentialPosition()).isOne();
            assertThat(second.sequentialPosition()).isEqualTo(41);
        }
    }
}
