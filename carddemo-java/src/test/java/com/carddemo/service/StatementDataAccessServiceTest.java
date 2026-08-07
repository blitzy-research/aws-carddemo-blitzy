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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Limit;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.AccountScanRepository;
import com.carddemo.repository.CardCrossReferenceScanRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.service.StatementDataAccessService.StatementFileRequest;
import com.carddemo.service.StatementDataAccessService.StatementFileResponse;
import com.carddemo.support.TestDataFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Surefire unit test for {@link StatementDataAccessService}, the statement feature's file-handling
 * subprogram translated from {@code [app/cbl/CBSTM03B.CBL]} - 230 lines, 14 paragraphs, four files,
 * six declared operation codes and one shared parameter object.
 *
 * <p>Legacy provenance: AWS CardDemo z/OS estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL, JCL, copybook or map source line is
 * transcribed here: member names, paragraph names, line numbers, DD names, field names, declared widths
 * and raw two-character status codes are cited as metadata, and nothing else.
 *
 * <h2>The fourteen paragraph units this class covers</h2>
 *
 * <p>One test method group per unit, so the traceability matrix can cite a class and a method for every
 * row this member contributes:
 *
 * <ul>
 *   <li>{@code 0000-START} at L116 with its DD-name selection at L118-L128, and {@code 9999-GOBACK} at
 *       L130 - covered by the dispatcher group</li>
 *   <li>{@code 1000-TRNXFILE-PROC} at L133, {@code 1900-EXIT} at L151 and {@code 1999-EXIT} at L154 -
 *       covered by the transaction-file group</li>
 *   <li>{@code 2000-XREFFILE-PROC} at L157, {@code 2900-EXIT} at L175 and {@code 2999-EXIT} at L178 -
 *       covered by the cross-reference group</li>
 *   <li>{@code 3000-CUSTFILE-PROC} at L181, {@code 3900-EXIT} at L200 and {@code 3999-EXIT} at L203 -
 *       covered by the customer group</li>
 *   <li>{@code 4000-ACCTFILE-PROC} at L206, {@code 4900-EXIT} at L225 and {@code 4999-EXIT} at L228 -
 *       covered by the account group</li>
 * </ul>
 *
 * <h2>Why this is a unit test and not an integration test</h2>
 *
 * <p>All four repositories are Mockito mocks and the two sequential sources are hand-written fakes, so
 * this test opens no connection, starts no container, binds no port and reaches no network. A real
 * exercise of these repositories against PostgreSQL belongs to the sibling integration tier; what is
 * under test here is the subprogram's control flow, its status contract and its field semantics, none of
 * which needs a database to be observed.
 *
 * <h2>Every expected value is written here, never computed by the code under test</h2>
 *
 * <p>No expected payload, status, key or width below is produced by calling the service, a record
 * mapper, a codec, a formatter or a template class. Statuses are two-character literals, widths are
 * literals taken from the picture clauses, padding is written as {@code " ".repeat(n)} so the counts are
 * visible, and every fixed-width comparison is measured on encoded bytes rather than trimmed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementDataAccessService: CBSTM03B file handling over four files and six operations")
final class StatementDataAccessServiceTest {

    // --------------------------------------------------------------------------------------------
    // The linkage area's declared widths, transcribed from the picture clauses at
    // [app/cbl/CBSTM03B.CBL:L101-L112] rather than read back from the class under test.
    // --------------------------------------------------------------------------------------------

    /** {@code LK-M03B-DD}, {@code PIC X(08)}. */
    private static final int DD_NAME_FIELD_WIDTH = 8;

    /** {@code LK-M03B-OPER}, {@code PIC X(01)}. */
    private static final int OPERATION_FIELD_WIDTH = 1;

    /** {@code LK-M03B-RC}, {@code PIC X(02)}. */
    private static final int RETURN_CODE_FIELD_WIDTH = 2;

    /** {@code LK-M03B-KEY}, {@code PIC X(25)}. */
    private static final int KEY_FIELD_WIDTH = 25;

    /** {@code LK-M03B-FLDT}, {@code PIC X(1000)}. */
    private static final int PAYLOAD_FIELD_WIDTH = 1000;

    // --------------------------------------------------------------------------------------------
    // The four DD names matched at [app/cbl/CBSTM03B.CBL:L119, L121, L123, L125] and the six
    // operation codes declared as condition names at [app/cbl/CBSTM03B.CBL:L103-L108].
    // --------------------------------------------------------------------------------------------

    private static final String TRANSACTION_DD = "TRNXFILE";

    private static final String CROSS_REFERENCE_DD = "XREFFILE";

    private static final String CUSTOMER_DD = "CUSTFILE";

    private static final String ACCOUNT_DD = "ACCTFILE";

    private static final String OPEN = "O";

    private static final String CLOSE = "C";

    private static final String READ = "R";

    private static final String READ_KEYED = "K";

    /** Declared at L107 and tested by no handler: dead, and required to stay dead. */
    private static final String WRITE = "W";

    /** Declared at L108 and tested by no handler: dead, and required to stay dead. */
    private static final String REWRITE = "Z";

    // --------------------------------------------------------------------------------------------
    // The raw two-character statuses. Written as literals, because the whole point of this member is
    // that it publishes the file's own status without normalising it into anything coarser.
    // --------------------------------------------------------------------------------------------

    private static final String SUCCESS_STATUS = "00";

    private static final String END_OF_FILE_STATUS = "10";

    private static final String RECORD_NOT_FOUND_STATUS = "23";

    private static final String PERMANENT_ERROR_STATUS = "31";

    /**
     * A status no file operation in this member ever produces, so observing it in a response proves the
     * value the caller carried on entry survived the call untouched.
     */
    private static final String STALE_STATUS = "77";

    // --------------------------------------------------------------------------------------------
    // Bounded-access expectations. The legacy opens a key-sequenced cluster and reads it forward; the
    // relational equivalent is one bounded keyset page per advance and one bounded probe per open.
    // --------------------------------------------------------------------------------------------

    /** Rows one bounded cross-reference page may hold. */
    private static final int CROSS_REFERENCE_PAGE_ROWS = 256;

    /** Rows a reachability probe reads: one is enough to answer whether, and the row is discarded. */
    private static final int REACHABILITY_PROBE_ROWS = 1;

    /** Exclusive lower key bound of a walk's first page and of both reachability probes. */
    private static final String LOWEST_KEY = "";

    // --------------------------------------------------------------------------------------------
    // Record widths, taken from the test tier's own verified layout catalogue rather than from any
    // production mapper, and the field fixtures the four files serve.
    // --------------------------------------------------------------------------------------------

    /** 300 bytes: an 11-character key plus the 289-character remainder declared at L78. */
    private static final int ACCOUNT_RECORD_WIDTH = TestDataFactory.ACCOUNT.recordLength();

    /** 500 bytes, of which the trailing 168 are filler. */
    private static final int CUSTOMER_RECORD_WIDTH = TestDataFactory.CUSTOMER.recordLength();

    /** 50 bytes: 36 of data and a 14-byte filler run. */
    private static final int CROSS_REFERENCE_RECORD_WIDTH =
            TestDataFactory.CARD_CROSS_REFERENCE_DATASET.recordLength();

    /** Card number of the first fixture cross-reference record, exactly 16 characters. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** Card number of the second, which sorts strictly after the first. */
    private static final String NEXT_CARD_NUMBER = "4111111111111112";

    /** Customer identifier, exactly 9 characters with a significant leading zero run. */
    private static final String CUSTOMER_ID = "000000123";

    /** Account identifier, exactly 11 characters with a significant leading zero run. */
    private static final String ACCOUNT_ID = "00000000456";

    /**
     * Runtime significant key length a caller states for a customer read: 9, the width of
     * {@code FD-CUST-ID} declared {@code PIC X(09)} at {@code [app/cbl/CBSTM03B.CBL:L72]}.
     */
    private static final int CUSTOMER_KEY_LENGTH = 9;

    /**
     * Runtime significant key length a caller states for an account read: 11, the width of
     * {@code FD-ACCT-ID} declared {@code PIC 9(11)} at {@code [app/cbl/CBSTM03B.CBL:L77]}.
     */
    private static final int ACCOUNT_KEY_LENGTH = 11;

    /** Runtime significant key length of a card number: 16, the cross-reference record key. */
    private static final int CARD_NUMBER_LENGTH = 16;

    /** Character length of the protected-value envelope marker. */
    private static final int ENVELOPE_PREFIX_LENGTH = 5;

    /**
     * Cleartext of the fixture customer's regulated national identifier, chosen so that it occurs in a
     * rendered record image only where that identifier is placed. Observing it in a payload therefore
     * proves the caller's revealing function was consulted.
     */
    private static final String CUSTOMER_SSN_CLEARTEXT = "987654321";

    /** Structural marker the customer entity requires of a protected value. */
    private static final String ENVELOPE_PREFIX = "ENC1:";

    /** Smallest decoded body the customer entity accepts for a protected value. */
    private static final int ENVELOPE_MINIMUM_BYTES = 28;

    /**
     * A frozen transaction-work image wide enough to exercise the payload field's padding, and
     * distinctive enough that a residue of it would be unmistakable in a later payload.
     */
    private static final String LONG_WORK_IMAGE =
            "FIRSTREAD" + "-".repeat(340) + "ENDOFFIRSTREAD";

    /** A deliberately short second image, so a residue of the first would show as trailing content. */
    private static final String SHORT_WORK_IMAGE = "SECONDREAD";

    @Mock
    private CardCrossReferenceScanRepository cardCrossReferenceScanRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountScanRepository accountScanRepository;

    private StatementDataAccessService service;

    private FrozenTransactionWork transactionSource;

    private StatementCrossReferenceSource crossReferenceSource;

    @BeforeEach
    void bindServiceToItsMockedRepositories() {
        this.service = new StatementDataAccessService(this.cardCrossReferenceScanRepository,
                this.customerRepository, this.accountRepository, this.accountScanRepository);
        this.transactionSource = new FrozenTransactionWork();
        // One walk per run, exactly as the statement generator acquires one per run. Rebuilt for every
        // test so that no test can observe another test's position.
        this.crossReferenceSource = this.service.openCrossReferenceSource();
    }

    // ------------------------------------------------------------------------------------------------
    // Hand-written doubles. The two sequential sources are functional interfaces the caller owns, so a
    // fake beats a mock here: the test needs to choose the record image byte for byte.
    // ------------------------------------------------------------------------------------------------

    /**
     * Stands in for the frozen, already-projected transaction-work snapshot the statement job
     * materialises before generation begins. Records which positions were requested, so a test can
     * assert that the position carried in the parameter object is the position actually read.
     */
    private static final class FrozenTransactionWork implements StatementTransactionSource {

        private final Map<Integer, String> imagesByPosition = new HashMap<>();

        private final List<Integer> requestedPositions = new ArrayList<>();

        private RuntimeException failure;

        void place(final int position, final String recordImage) {
            this.imagesByPosition.put(Integer.valueOf(position), recordImage);
        }

        void failWith(final RuntimeException thrown) {
            this.failure = thrown;
        }

        @Override
        public Optional<String> readAt(final int position) {
            if (position < 0) {
                throw new IllegalArgumentException("position must not be negative: " + position);
            }
            this.requestedPositions.add(Integer.valueOf(position));
            if (this.failure != null) {
                throw this.failure;
            }
            return Optional.ofNullable(this.imagesByPosition.get(Integer.valueOf(position)));
        }

        List<Integer> requestedPositions() {
            return List.copyOf(this.requestedPositions);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Fixtures. Every entity is built from literals through its own public constructor, so no
    // production mapper, codec or fixture loader participates in producing anything this test asserts.
    // ------------------------------------------------------------------------------------------------

    /**
     * Builds a cross-reference record with the three exact-width identifiers the layout declares.
     *
     * @param cardNumber the 16-character card number, which is the record key
     * @return a cross-reference entity carrying that key and the fixture customer and account
     */
    private static CardCrossReference crossReference(final String cardNumber) {
        return new CardCrossReference(cardNumber, CUSTOMER_ID, ACCOUNT_ID);
    }

    /**
     * The 50-byte cross-reference record image, written out field by field: the 16-character card
     * number at offset 0, the 9-digit customer identifier at offset 16, the 11-digit account identifier
     * at offset 25 and the 14-byte filler run at offset 36. All three identifiers are supplied at their
     * exact declared widths, so no justification or padding rule can change the result.
     *
     * @param cardNumber the card number the record carries
     * @return the expected 50-character image
     */
    private static String crossReferenceImage(final String cardNumber) {
        return cardNumber + CUSTOMER_ID + ACCOUNT_ID + " ".repeat(14);
    }

    /** Builds the fixture account, whose twelve attributes are all present as the layout requires. */
    private static Account account() {
        return new Account(ACCOUNT_ID, "Y", new BigDecimal("1000.00"), new BigDecimal("5000.00"),
                new BigDecimal("500.00"), "2020-01-01", "2030-01-01", "2025-06-30",
                new BigDecimal("100.00"), new BigDecimal("50.00"), "0000012345", "ZEROAPR");
    }

    /**
     * Builds the fixture customer with both regulated identifiers sealed into the envelope the entity
     * insists on, so that the keyed read has something to reveal.
     */
    private static Customer customer() {
        return new Customer(CUSTOMER_ID, "MARY", "ANN", "SMITH", "1 FIRST STREET", "APARTMENT 2",
                "THIRD LINE", "NY", "USA", "10001", "(212)555-1111", "(212)555-2222",
                seal(CUSTOMER_SSN_CLEARTEXT), seal("NY-DL-00000001"), "1980-01-01", "0000012345",
                "Y", "750");
    }

    /**
     * Reversibly wraps a cleartext value in the envelope shape the customer entity requires, so a test
     * can seal a fixture and reveal it again while holding no key material of any kind.
     *
     * @param cleartext the value to wrap; its encoded length must fit in one leading length byte
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
     * The exact inverse of {@link #seal(String)}, and the revealing function a customer keyed read is
     * given.
     *
     * @param envelope the envelope to unwrap
     * @return the original cleartext
     */
    private static String reveal(final String envelope) {
        final byte[] body = Base64.getDecoder().decode(envelope.substring(ENVELOPE_PREFIX_LENGTH));
        return new String(body, 1, body[0], StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------------------------------------
    // Measurement and assembly helpers. Widths are measured on encoded bytes, never with a character
    // count and never after trimming, and padding is always spelled out so the count is visible.
    // ------------------------------------------------------------------------------------------------

    /**
     * Measures a fixed-width value the way the record image is measured: in encoded bytes.
     *
     * @param value the value to measure
     * @return its US-ASCII encoded length
     */
    private static int encodedWidthOf(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Places a record image in the payload field: left-justified, space-padded to the declared width.
     *
     * @param recordImage the image to place
     * @return the expected 1000-character payload field
     */
    private static String payloadOf(final String recordImage) {
        return recordImage + " ".repeat(PAYLOAD_FIELD_WIDTH - encodedWidthOf(recordImage));
    }

    /** The payload field as the caller blanks it before every read call. */
    private static String blankPayload() {
        return " ".repeat(PAYLOAD_FIELD_WIDTH);
    }

    /**
     * Slices a region out of a payload field so a region can be asserted without trimming anything.
     *
     * @param payload the payload field
     * @param from    inclusive start offset
     * @param to      exclusive end offset
     * @return the region's bytes as a string
     */
    private static String regionOf(final String payload, final int from, final int to) {
        return new String(payload.getBytes(StandardCharsets.US_ASCII), from, to - from,
                StandardCharsets.US_ASCII);
    }

    /**
     * Builds a request that carries the stale sentinel status, a blank payload and position zero.
     *
     * @param ddName    the selector
     * @param operation the operation code
     * @return the request
     */
    private static StatementFileRequest request(final String ddName, final String operation) {
        return new StatementFileRequest(ddName, operation, STALE_STATUS, "", 1, blankPayload(), 0);
    }

    /**
     * Builds a keyed-read request carrying the test's own revealing function, as a caller that intends
     * to render a customer image must.
     *
     * @param ddName    the selector
     * @param key       the key field's content
     * @param keyLength the runtime significant length of that key
     * @return the request
     */
    private static StatementFileRequest keyedRequest(final String ddName, final String key,
                                                     final int keyLength) {
        return new StatementFileRequest(ddName, READ_KEYED, STALE_STATUS, key, keyLength,
                blankPayload(), 0, StatementDataAccessServiceTest::reveal);
    }

    /** A store failure of the kind every guarded retrieval in the service translates into a status. */
    private static DataAccessResourceFailureException unreachable() {
        return new DataAccessResourceFailureException("the cluster could not be reached");
    }

    /**
     * Invokes the subprogram with this run's two sources, which is the only entry point it has.
     *
     * @param request the parameter object
     * @return the written-back components
     */
    private StatementFileResponse execute(final StatementFileRequest request) {
        return this.service.execute(request, this.transactionSource, this.crossReferenceSource);
    }

    /**
     * Stubs the cross-reference walk to deliver the given records as its first bounded page and then to
     * report exhaustion, which is what the bounded cursor's loader contract requires.
     *
     * @param records the records the first page holds, in key order
     */
    private void crossReferencePage(final CardCrossReference... records) {
        when(this.cardCrossReferenceScanRepository
                .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                .thenReturn(List.of(records))
                .thenReturn(List.of());
    }

    /** Asserts that not one of the four repositories was consulted at all. */
    private void assertNoRepositoryTouched() {
        verifyNoInteractions(this.cardCrossReferenceScanRepository, this.customerRepository,
                this.accountRepository, this.accountScanRepository);
    }

    // ------------------------------------------------------------------------------------------------
    // The exit-path table. Each handler is three paragraphs and the middle one publishes a status, so
    // every way out of a handler must leave a status behind - including the way out that did nothing.
    // ------------------------------------------------------------------------------------------------

    /** One way out of a handler. */
    private enum ExitPath {

        /** The first of the three consecutive checks matched. */
        OPEN,

        /** The middle check matched and found a record. */
        SUCCESSFUL_READ,

        /** The middle check matched and found nothing: exhausted for a sequential file, absent for a
         * keyed one. */
        EXHAUSTED_OR_ABSENT,

        /** The middle check matched and the store failed for a technical reason. */
        FAILED_RETRIEVAL,

        /** The last of the three consecutive checks matched. */
        CLOSE,

        /** None of the three checks matched, so the handler fell through having performed nothing. */
        NO_ACTION
    }

    /**
     * Every handler paired with every exit path that handler actually has.
     *
     * <p>Twenty-three rows rather than a tidy twenty-four: the transaction handler has no failed
     * retrieval, because its open touches no store at all and its read consumes the frozen snapshot the
     * job's preceding steps already materialised. That absence is asserted directly by its own test
     * rather than papered over with a row that could only pass by asserting the wrong thing.
     *
     * @return the handler-and-path pairs
     */
    private static Stream<Arguments> everyHandlerAndEveryExitPath() {
        return Stream.of(TRANSACTION_DD, CROSS_REFERENCE_DD, CUSTOMER_DD, ACCOUNT_DD)
                .flatMap(ddName -> Stream.of(ExitPath.values())
                        .filter(path -> path != ExitPath.FAILED_RETRIEVAL
                                || !TRANSACTION_DD.equals(ddName))
                        .map(path -> Arguments.of(ddName, path)));
    }

    /**
     * Builds the request that drives one handler down one exit path.
     *
     * @param ddName the selector
     * @param path   the exit path
     * @return the request
     */
    private static StatementFileRequest requestFor(final String ddName, final ExitPath path) {
        return switch (path) {
            case OPEN -> request(ddName, OPEN);
            case CLOSE -> request(ddName, CLOSE);
            case NO_ACTION -> request(ddName, WRITE);
            case SUCCESSFUL_READ, EXHAUSTED_OR_ABSENT, FAILED_RETRIEVAL -> switch (ddName) {
                case CUSTOMER_DD -> keyedRequest(CUSTOMER_DD, CUSTOMER_ID, CUSTOMER_KEY_LENGTH);
                case ACCOUNT_DD -> keyedRequest(ACCOUNT_DD, ACCOUNT_ID, ACCOUNT_KEY_LENGTH);
                default -> request(ddName, READ);
            };
        };
    }

    /**
     * Arranges the collaborators for one handler-and-path pair and states, as a literal, the raw
     * two-character status that path must publish.
     *
     * <p>Only the collaborator the path actually reaches is stubbed, so strict stubbing keeps the table
     * honest: a row that stubs something it never uses fails.
     *
     * @param ddName the selector
     * @param path   the exit path
     * @return the status the path is required to publish
     */
    private String arrangeAndExpect(final String ddName, final ExitPath path) {
        return switch (path) {
            // An open or a close reports success on all four files, and an unstubbed probe returns an
            // empty page, which is an empty but perfectly readable cluster.
            case OPEN, CLOSE -> SUCCESS_STATUS;
            case NO_ACTION -> STALE_STATUS;
            case SUCCESSFUL_READ -> {
                arrangeSuccessfulRead(ddName);
                yield SUCCESS_STATUS;
            }
            // Nothing is stubbed here: an unstubbed page is empty and an unstubbed keyed read is
            // absent, which is exactly the arrangement this path needs.
            case EXHAUSTED_OR_ABSENT -> isRandomlyAccessed(ddName)
                    ? RECORD_NOT_FOUND_STATUS
                    : END_OF_FILE_STATUS;
            case FAILED_RETRIEVAL -> {
                arrangeFailedRetrieval(ddName);
                yield PERMANENT_ERROR_STATUS;
            }
        };
    }

    /**
     * Reports whether a selector names one of the two files declared random access at
     * {@code [app/cbl/CBSTM03B.CBL:L45, L51]}, which are the two read by key.
     *
     * @param ddName the selector
     * @return {@code true} for the customer and account files
     */
    private static boolean isRandomlyAccessed(final String ddName) {
        return CUSTOMER_DD.equals(ddName) || ACCOUNT_DD.equals(ddName);
    }

    /**
     * Arranges the one collaborator whose read succeeds for the named file.
     *
     * @param ddName the selector
     */
    private void arrangeSuccessfulRead(final String ddName) {
        switch (ddName) {
            case TRANSACTION_DD -> this.transactionSource.place(0, LONG_WORK_IMAGE);
            case CROSS_REFERENCE_DD -> crossReferencePage(crossReference(CARD_NUMBER));
            case CUSTOMER_DD -> when(this.customerRepository.findById(any()))
                    .thenReturn(Optional.of(customer()));
            default -> when(this.accountRepository.findById(any()))
                    .thenReturn(Optional.of(account()));
        }
    }

    /**
     * Arranges the one collaborator whose retrieval fails for the named file. The transaction file is
     * never passed here, because it has no store to fail.
     *
     * @param ddName the selector
     */
    private void arrangeFailedRetrieval(final String ddName) {
        switch (ddName) {
            case CROSS_REFERENCE_DD -> when(this.cardCrossReferenceScanRepository
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                    .thenThrow(unreachable());
            case CUSTOMER_DD -> when(this.customerRepository.findById(any()))
                    .thenThrow(unreachable());
            default -> when(this.accountRepository.findById(any())).thenThrow(unreachable());
        }
    }

    @Nested
    @DisplayName("0000-START [L116] and 9999-GOBACK [L130]: the DD-name selection")
    class DispatcherParagraph {

        @Test
        @DisplayName("the transaction arm [L120] consults no repository, because the work snapshot is "
                + "frozen before this member runs")
        void transactionArmIsReachedAndConsultsNoRepository() {
            final StatementFileResponse actual = execute(request(TRANSACTION_DD, OPEN));

            assertThat(actual.returnCode()).isEqualTo(SUCCESS_STATUS);
            assertNoRepositoryTouched();
        }

        @Test
        @DisplayName("the cross-reference arm [L122] consults the cross-reference scan alone")
        void crossReferenceArmIsReachedAndConsultsOnlyItsOwnRepository() {
            execute(request(CROSS_REFERENCE_DD, OPEN));

            verify(cardCrossReferenceScanRepository)
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any());
            verifyNoInteractions(customerRepository, accountRepository, accountScanRepository);
        }

        @Test
        @DisplayName("the customer arm [L124] consults the customer repository alone")
        void customerArmIsReachedAndConsultsOnlyItsOwnRepository() {
            execute(request(CUSTOMER_DD, OPEN));

            verify(customerRepository).findByCustIdGreaterThanOrderByCustIdAsc(any(), any());
            verifyNoInteractions(cardCrossReferenceScanRepository, accountRepository,
                    accountScanRepository);
        }

        @Test
        @DisplayName("the account arm [L126] consults the account scan alone, never the keyed view")
        void accountArmIsReachedAndConsultsOnlyItsOwnRepository() {
            execute(request(ACCOUNT_DD, OPEN));

            verify(accountScanRepository).findByAcctIdGreaterThanOrderByAcctIdAsc(any(), any());
            verifyNoInteractions(cardCrossReferenceScanRepository, customerRepository,
                    accountRepository);
        }

        @Test
        @DisplayName("the selection matches the whole eight-character field in clause order, so no arm "
                + "can shadow another and a prefix or a case variant matches none of them")
        void matchesTheWholeSelectorFieldSoNoArmCanShadowAnother() {
            crossReferencePage(crossReference(CARD_NUMBER));
            when(customerRepository.findById(any())).thenReturn(Optional.of(customer()));
            when(accountRepository.findById(any())).thenReturn(Optional.of(account()));
            transactionSource.place(0, LONG_WORK_IMAGE);

            final StatementFileResponse transaction = execute(request(TRANSACTION_DD, READ));
            final StatementFileResponse crossReference = execute(request(CROSS_REFERENCE_DD, READ));
            final StatementFileResponse customer =
                    execute(keyedRequest(CUSTOMER_DD, CUSTOMER_ID, CUSTOMER_KEY_LENGTH));
            final StatementFileResponse account =
                    execute(keyedRequest(ACCOUNT_DD, ACCOUNT_ID, ACCOUNT_KEY_LENGTH));
            final StatementFileResponse prefix = execute(request("TRNX", READ));
            final StatementFileResponse lowercase = execute(request("trnxfile", READ));

            assertAll(
                    () -> assertThat(transaction.returnCode()).isEqualTo(SUCCESS_STATUS),
                    () -> assertThat(crossReference.returnCode()).isEqualTo(SUCCESS_STATUS),
                    () -> assertThat(customer.returnCode()).isEqualTo(SUCCESS_STATUS),
                    () -> assertThat(account.returnCode()).isEqualTo(SUCCESS_STATUS),
                    () -> assertThat(transaction.payload()).isEqualTo(payloadOf(LONG_WORK_IMAGE)),
                    () -> assertThat(crossReference.payload())
                            .isEqualTo(payloadOf(crossReferenceImage(CARD_NUMBER))),
                    () -> assertThat(prefix.returnCode())
                            .as("a seven-character prefix of the first arm matches nothing")
                            .isEqualTo(STALE_STATUS),
                    () -> assertThat(lowercase.returnCode())
                            .as("the comparison is not case folded")
                            .isEqualTo(STALE_STATUS));
        }

        @ParameterizedTest
        @ValueSource(strings = {"NOSUCHDD", "trnxfile", "TRNX", "", "ACCTFIL", "ACCTFILES", "  "})
        @DisplayName("an unrecognised selector leaves the caller's STALE status byte-identical: the "
                + "legacy WHEN OTHER [L127-L128] transfers straight to GOBACK and performs no action, "
                + "so this is deliberately NOT an error path")
        void unrecognisedSelectorLeavesTheStaleStatusByteIdentical(final String ddName) {
            final StatementFileRequest given = new StatementFileRequest(ddName, READ, STALE_STATUS,
                    "SOMEKEY", 7, payloadOf("CARRIED-BY-THE-CALLER"), 4);

            final StatementFileResponse actual = execute(given);

            assertAll(
                    () -> assertThat(actual).isNotNull(),
                    () -> assertThat(actual.returnCode())
                            .as("the status is the caller's own, not a synthesised error")
                            .isEqualTo(STALE_STATUS),
                    () -> assertThat(encodedWidthOf(actual.returnCode()))
                            .isEqualTo(RETURN_CODE_FIELD_WIDTH),
                    () -> assertThat(actual.payload())
                            .isEqualTo(payloadOf("CARRIED-BY-THE-CALLER")),
                    () -> assertThat(actual.sequentialPosition()).isEqualTo(4));
            assertNoRepositoryTouched();
            assertThat(transactionSource.requestedPositions()).isEmpty();
        }

        @Test
        @DisplayName("GOBACK [L130] writes nothing, so the selector field is echoed back too")
        void goBackEchoesEveryComponentIncludingTheSelector() {
            final StatementFileResponse actual = execute(request("NOSUCHDD", OPEN));

            assertThat(actual.ddName()).isEqualTo("NOSUCHDD");
            assertThat(encodedWidthOf(actual.ddName())).isEqualTo(DD_NAME_FIELD_WIDTH);
        }

        @Test
        @DisplayName("the entry point refuses a null parameter object")
        void refusesNullParameterObject() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> execute(null));
        }

        @Test
        @DisplayName("the entry point refuses an absent frozen transaction source")
        void refusesNullTransactionSource() {
            final StatementFileRequest given = request(TRANSACTION_DD, OPEN);

            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> service.execute(given, null, crossReferenceSource));
        }

        @Test
        @DisplayName("the entry point refuses an absent cross-reference walk, because the run owns one")
        void refusesNullCrossReferenceSource() {
            final StatementFileRequest given = request(CROSS_REFERENCE_DD, OPEN);

            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> service.execute(given, transactionSource, null));
        }

        @Test
        @DisplayName("openCrossReferenceSource hands out a fresh independent walk and issues no query")
        void openCrossReferenceSourceIssuesNoQueryAndIsPerRun() {
            final StatementCrossReferenceSource first = service.openCrossReferenceSource();
            final StatementCrossReferenceSource second = service.openCrossReferenceSource();

            assertAll(
                    () -> assertThat(first).isNotNull(),
                    () -> assertThat(second).isNotNull().isNotSameAs(first));
            assertNoRepositoryTouched();
        }

        @Test
        @DisplayName("a walk refuses a negative position and refuses to skip a position, because a "
                + "forward cursor can do neither")
        void theWalkRefusesNegativeAndSkippedPositions() {
            final StatementCrossReferenceSource walk = service.openCrossReferenceSource();

            assertAll(
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> walk.readAt(-1)),
                    () -> assertThatExceptionOfType(IllegalStateException.class)
                            .isThrownBy(() -> walk.readAt(2)));
        }
    }

    @Nested
    @DisplayName("1000-TRNXFILE-PROC [L133] -> 1900-EXIT [L151] -> 1999-EXIT [L154]")
    class TransactionFileHandler {

        @Test
        @DisplayName("OPEN [L136] reports success, positions at the first record and touches no store")
        void openReportsSuccessAndPositionsAtTheFirstRecord() {
            final StatementFileRequest given = new StatementFileRequest(TRANSACTION_DD, OPEN,
                    STALE_STATUS, "", 1, blankPayload(), 9);

            final StatementFileResponse actual = execute(given);

            assertAll(
                    () -> assertThat(actual.returnCode()).isEqualTo(SUCCESS_STATUS),
                    () -> assertThat(actual.sequentialPosition()).isZero(),
                    () -> assertThat(actual.payload()).isEqualTo(blankPayload()));
            assertNoRepositoryTouched();
        }

        @Test
        @DisplayName("the sequential READ [L141] reads the position the parameter object carries and "
                + "advances it by exactly one")
        void sequentialReadReadsTheCarriedPositionAndAdvancesByOne() {
            transactionSource.place(3, LONG_WORK_IMAGE);
            final StatementFileRequest given = new StatementFileRequest(TRANSACTION_DD, READ,
                    STALE_STATUS, "", 1, blankPayload(), 3);

            final StatementFileResponse actual = execute(given);

            assertAll(
                    () -> assertThat(actual.returnCode()).isEqualTo(SUCCESS_STATUS),
                    () -> assertThat(actual.payload()).isEqualTo(payloadOf(LONG_WORK_IMAGE)),
                    () -> assertThat(actual.sequentialPosition()).isEqualTo(4),
                    () -> assertThat(transactionSource.requestedPositions())
                            .containsExactly(Integer.valueOf(3)));
        }

        @Test
        @DisplayName("an exhausted snapshot yields the at-end status and changes nothing else")
        void exhaustedSnapshotYieldsTheAtEndStatus() {
            final StatementFileRequest given = new StatementFileRequest(TRANSACTION_DD, READ,
                    STALE_STATUS, "", 1, payloadOf("PRIOR"), 7);

            final StatementFileResponse actual = execute(given);

            assertAll(
                    () -> assertThat(actual.returnCode()).isEqualTo(END_OF_FILE_STATUS),
                    () -> assertThat(actual.payload()).isEqualTo(payloadOf("PRIOR")),
                    () -> assertThat(actual.sequentialPosition()).isEqualTo(7));
        }

        @Test
        @DisplayName("CLOSE [L147] reports success and discards the file position")
        void closeReportsSuccessAndDiscardsThePosition() {
            final StatementFileRequest given = new StatementFileRequest(TRANSACTION_DD, CLOSE,
                    STALE_STATUS, "", 1, blankPayload(), 9);

            final StatementFileResponse actual = execute(given);

            assertThat(actual.returnCode()).isEqualTo(SUCCESS_STATUS);
            assertThat(actual.sequentialPosition()).isZero();
            assertNoRepositoryTouched();
        }

        @Test
        @DisplayName("no keyed read is offered, because the file is declared sequential access [L33], "
                + "so a keyed request falls through all three checks and reads nothing")
        void offersNoKeyedRead() {
            final StatementFileResponse actual =
                    execute(keyedRequest(TRANSACTION_DD, ACCOUNT_ID, ACCOUNT_KEY_LENGTH));

            assertThat(actual.returnCode()).isEqualTo(STALE_STATUS);
            assertThat(transactionSource.requestedPositions()).isEmpty();
            assertNoRepositoryTouched();
        }

        @Test
        @DisplayName("this is the one handler with no failed-retrieval status, because its open touches "
                + "no store and its read consumes a snapshot the job's earlier steps already froze")
        void hasNoFailedRetrievalStatusBecauseItTouchesNoStore() {
            transactionSource.failWith(unreachable());

            assertThatExceptionOfType(DataAccessResourceFailureException.class)
                    .isThrownBy(() -> execute(request(TRANSACTION_DD, READ)));
            assertNoRepositoryTouched();
        }
    }

    @Nested
    @DisplayName("2000-XREFFILE-PROC [L157] -> 2900-EXIT [L175] -> 2999-EXIT [L178]")
    class CrossReferenceFileHandler {

        @Test
        @DisplayName("OPEN [L160] loads the walk's first bounded page, bounded to the page size and "
                + "starting strictly after the lowest key, so no offset and no count is ever requested")
        void openLoadsTheFirstBoundedPage() {
            crossReferencePage(crossReference(CARD_NUMBER));

            execute(request(CROSS_REFERENCE_DD, OPEN));

            final ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Limit> bound = ArgumentCaptor.forClass(Limit.class);
            verify(cardCrossReferenceScanRepository)
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(cursor.capture(),
                            bound.capture());
            assertAll(
                    () -> assertThat(cursor.getValue()).isEqualTo(LOWEST_KEY),
                    () -> assertThat(bound.getValue().max()).isEqualTo(CROSS_REFERENCE_PAGE_ROWS),
                    () -> assertThat(bound.getValue().isLimited()).isTrue());
        }

        @Test
        @DisplayName("the page the open loaded IS the page the first read consumes, so establishing "
                + "reachability costs nothing beyond the first read's own work")
        void theOpenedPageIsThePageTheFirstReadConsumes() {
            crossReferencePage(crossReference(CARD_NUMBER));

            execute(request(CROSS_REFERENCE_DD, OPEN));
            final StatementFileResponse read = execute(request(CROSS_REFERENCE_DD, READ));

            assertThat(read.returnCode()).isEqualTo(SUCCESS_STATUS);
            assertThat(read.payload()).isEqualTo(payloadOf(crossReferenceImage(CARD_NUMBER)));
            verify(cardCrossReferenceScanRepository, times(1))
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any());
        }

        @Test
        @DisplayName("the sequential READ [L165] returns the complete fifty-byte image, filler run "
                + "included, and advances the position by one")
        void sequentialReadReturnsTheCompleteFiftyByteImage() {
            crossReferencePage(crossReference(CARD_NUMBER));

            final StatementFileResponse actual = execute(request(CROSS_REFERENCE_DD, READ));

            assertAll(
                    () -> assertThat(actual.returnCode()).isEqualTo(SUCCESS_STATUS),
                    () -> assertThat(actual.payload())
                            .isEqualTo(payloadOf(crossReferenceImage(CARD_NUMBER))),
                    () -> assertThat(encodedWidthOf(crossReferenceImage(CARD_NUMBER)))
                            .isEqualTo(CROSS_REFERENCE_RECORD_WIDTH),
                    () -> assertThat(actual.sequentialPosition()).isOne());
        }

        @Test
        @DisplayName("a page holding more than one record yields the FIRST of them on the first read")
        void aMultiRecordPageYieldsTheFirstRecordFirst() {
            crossReferencePage(crossReference(CARD_NUMBER), crossReference(NEXT_CARD_NUMBER));

            final StatementFileResponse actual = execute(request(CROSS_REFERENCE_DD, READ));

            assertThat(actual.payload())
                    .isEqualTo(payloadOf(crossReferenceImage(CARD_NUMBER)))
                    .isNotEqualTo(payloadOf(crossReferenceImage(NEXT_CARD_NUMBER)));
        }

        @Test
        @DisplayName("an empty page is the legacy not-found path: the at-end status, no exception and "
                + "no index error")
        void anEmptyPageIsTheAtEndPathAndNotAnIndexError() {
            final StatementFileResponse actual = execute(request(CROSS_REFERENCE_DD, READ));

            assertThat(actual.returnCode()).isEqualTo(END_OF_FILE_STATUS);
            assertThat(actual.payload()).isEqualTo(blankPayload());
        }

        @Test
        @DisplayName("a walk that spans two pages requests them in ascending key order, each bounded, "
                + "the second addressed by the last key the first delivered")
        void aTwoPageWalkRequestsPagesInAscendingKeyOrder() {
            when(cardCrossReferenceScanRepository
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                    .thenReturn(List.of(crossReference(CARD_NUMBER),
                            crossReference(NEXT_CARD_NUMBER)))
                    .thenReturn(List.of());

            final StatementFileResponse first = execute(request(CROSS_REFERENCE_DD, READ));
            final StatementFileResponse second = execute(new StatementFileRequest(
                    CROSS_REFERENCE_DD, READ, STALE_STATUS, "", 1, blankPayload(), 1));
            final StatementFileResponse third = execute(new StatementFileRequest(
                    CROSS_REFERENCE_DD, READ, STALE_STATUS, "", 1, blankPayload(), 2));

            assertAll(
                    () -> assertThat(first.payload())
                            .isEqualTo(payloadOf(crossReferenceImage(CARD_NUMBER))),
                    () -> assertThat(second.payload())
                            .isEqualTo(payloadOf(crossReferenceImage(NEXT_CARD_NUMBER))),
                    () -> assertThat(third.returnCode()).isEqualTo(END_OF_FILE_STATUS));

            final InOrder pages = inOrder(cardCrossReferenceScanRepository);
            final ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
            pages.verify(cardCrossReferenceScanRepository, times(2))
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(cursor.capture(), any());
            pages.verifyNoMoreInteractions();
            assertThat(cursor.getAllValues()).containsExactly(LOWEST_KEY, NEXT_CARD_NUMBER);
        }

        @Test
        @DisplayName("a store failure during OPEN becomes the permanent input-output status, never an "
                + "exception, so the caller's own catch-all arm is the arm that runs")
        void aStoreFailureDuringOpenBecomesThePermanentStatus() {
            when(cardCrossReferenceScanRepository
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                    .thenThrow(unreachable());

            final StatementFileResponse actual = execute(request(CROSS_REFERENCE_DD, OPEN));

            assertThat(actual.returnCode()).isEqualTo(PERMANENT_ERROR_STATUS);
            assertThat(actual.status()).contains(FileStatus.PERMANENT_ERROR);
        }

        @Test
        @DisplayName("a store failure during READ becomes the permanent input-output status and leaves "
                + "the payload and the position exactly as they arrived")
        void aStoreFailureDuringReadBecomesThePermanentStatus() {
            when(cardCrossReferenceScanRepository
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                    .thenThrow(unreachable());
            final StatementFileRequest given = new StatementFileRequest(CROSS_REFERENCE_DD, READ,
                    STALE_STATUS, "", 1, payloadOf("PRIOR"), 0);

            final StatementFileResponse actual = execute(given);

            assertAll(
                    () -> assertThat(actual.returnCode()).isEqualTo(PERMANENT_ERROR_STATUS),
                    () -> assertThat(actual.payload()).isEqualTo(payloadOf("PRIOR")),
                    () -> assertThat(actual.sequentialPosition()).isZero());
        }

        @Test
        @DisplayName("no keyed read is offered, because the file is declared sequential access [L39]")
        void offersNoKeyedRead() {
            final StatementFileResponse actual =
                    execute(keyedRequest(CROSS_REFERENCE_DD, CARD_NUMBER, CARD_NUMBER_LENGTH));

            assertThat(actual.returnCode()).isEqualTo(STALE_STATUS);
            assertNoRepositoryTouched();
        }

        @Test
        @DisplayName("CLOSE [L171] reports success and discards the file position")
        void closeReportsSuccessAndDiscardsThePosition() {
            final StatementFileResponse actual = execute(new StatementFileRequest(
                    CROSS_REFERENCE_DD, CLOSE, STALE_STATUS, "", 1, blankPayload(), 6));

            assertThat(actual.returnCode()).isEqualTo(SUCCESS_STATUS);
            assertThat(actual.sequentialPosition()).isZero();
            assertNoRepositoryTouched();
        }
    }

    @Nested
    @DisplayName("3000-CUSTFILE-PROC [L181] -> 3900-EXIT [L200] -> 3999-EXIT [L203]")
    class CustomerFileHandler {

        @Test
        @DisplayName("OPEN [L184] reads ONE bounded row in key order, never a count and never a page")
        void openReadsOneBoundedRowInKeyOrder() {
            execute(request(CUSTOMER_DD, OPEN));

            final ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Limit> bound = ArgumentCaptor.forClass(Limit.class);
            verify(customerRepository).findByCustIdGreaterThanOrderByCustIdAsc(cursor.capture(),
                    bound.capture());
            verifyNoMoreInteractions(customerRepository);
            assertAll(
                    () -> assertThat(cursor.getValue()).isEqualTo(LOWEST_KEY),
                    () -> assertThat(bound.getValue().max()).isEqualTo(REACHABILITY_PROBE_ROWS));
        }

        @Test
        @DisplayName("the keyed READ [L188] slices the key to the runtime length and then LEFT "
                + "justifies it, space-padded, into the alphanumeric record key declared X(09) at L72")
        void keyedReadLeftJustifiesTheSlicedKeyIntoAnAlphanumericRecordKey() {
            final StatementFileResponse actual =
                    execute(keyedRequest(CUSTOMER_DD, "123456789012345678901234", 3));

            final ArgumentCaptor<String> recordKey = ArgumentCaptor.forClass(String.class);
            verify(customerRepository).findById(recordKey.capture());
            assertAll(
                    () -> assertThat(recordKey.getValue()).isEqualTo("123" + " ".repeat(6)),
                    () -> assertThat(encodedWidthOf(recordKey.getValue())).isEqualTo(9),
                    () -> assertThat(actual.returnCode()).isEqualTo(RECORD_NOT_FOUND_STATUS));
        }

        @Test
        @DisplayName("only the prefix of the runtime length reaches the lookup: two keys that agree on "
                + "that prefix and differ beyond it produce one and the same record key")
        void onlyTheRuntimeLengthPrefixReachesTheLookup() {
            execute(keyedRequest(CUSTOMER_DD, "123AAAAAAAAAAAAAAAAAAAAA", 3));
            execute(keyedRequest(CUSTOMER_DD, "123ZZZZZZZZZZZZZZZZZZZZZ", 3));

            final ArgumentCaptor<String> recordKey = ArgumentCaptor.forClass(String.class);
            verify(customerRepository, times(2)).findById(recordKey.capture());
            assertThat(recordKey.getAllValues())
                    .containsExactly("123" + " ".repeat(6), "123" + " ".repeat(6));
        }

        @Test
        @DisplayName("a found customer is rendered into the payload at the record's declared width, "
                + "space-padded to the payload field, with the caller's revealed cleartext in place")
        void aFoundCustomerIsRenderedAtItsDeclaredWidth() {
            when(customerRepository.findById(any())).thenReturn(Optional.of(customer()));

            final StatementFileResponse actual =
                    execute(keyedRequest(CUSTOMER_DD, CUSTOMER_ID, CUSTOMER_KEY_LENGTH));

            assertAll(
                    () -> assertThat(actual.returnCode()).isEqualTo(SUCCESS_STATUS),
                    () -> assertThat(encodedWidthOf(actual.payload()))
                            .isEqualTo(PAYLOAD_FIELD_WIDTH),
                    () -> assertThat(regionOf(actual.payload(), 0, 9)).isEqualTo(CUSTOMER_ID),
                    () -> assertThat(regionOf(actual.payload(), CUSTOMER_RECORD_WIDTH,
                            PAYLOAD_FIELD_WIDTH))
                            .isEqualTo(" ".repeat(PAYLOAD_FIELD_WIDTH - CUSTOMER_RECORD_WIDTH)),
                    () -> assertThat(actual.payload())
                            .as("the revealing function the caller supplied was consulted")
                            .contains(CUSTOMER_SSN_CLEARTEXT),
                    () -> assertThat(actual.sequentialPosition())
                            .as("a keyed read moves no sequential cursor")
                            .isZero());
        }

        @Test
        @DisplayName("a caller that supplies no revealing function gets a rejection rather than an "
                + "envelope rendered into the payload, because the reveal policy is the caller's")
        void aCallerWithoutARevealingFunctionIsRejected() {
            when(customerRepository.findById(any())).thenReturn(Optional.of(customer()));
            final StatementFileRequest given = new StatementFileRequest(CUSTOMER_DD, READ_KEYED,
                    STALE_STATUS, CUSTOMER_ID, CUSTOMER_KEY_LENGTH, blankPayload(), 0);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> execute(given));
        }

        @Test
        @DisplayName("an absent row yields the record-not-found status, which is NOT the permanent "
                + "input-output status a technical failure yields")
        void anAbsentRowYieldsRecordNotFound() {
            final StatementFileResponse actual =
                    execute(keyedRequest(CUSTOMER_DD, CUSTOMER_ID, CUSTOMER_KEY_LENGTH));

            assertThat(actual.returnCode())
                    .isEqualTo(RECORD_NOT_FOUND_STATUS)
                    .isNotEqualTo(PERMANENT_ERROR_STATUS);
            assertThat(actual.status()).contains(FileStatus.RECORD_NOT_FOUND);
        }

        @Test
        @DisplayName("a store failure during the keyed READ yields the permanent input-output status")
        void aStoreFailureDuringTheKeyedReadYieldsThePermanentStatus() {
            when(customerRepository.findById(any())).thenThrow(unreachable());

            final StatementFileResponse actual =
                    execute(keyedRequest(CUSTOMER_DD, CUSTOMER_ID, CUSTOMER_KEY_LENGTH));

            assertThat(actual.returnCode())
                    .isEqualTo(PERMANENT_ERROR_STATUS)
                    .isNotEqualTo(RECORD_NOT_FOUND_STATUS);
        }

        @Test
        @DisplayName("no sequential read is offered, because the file is declared random access [L45]")
        void offersNoSequentialRead() {
            final StatementFileResponse actual = execute(request(CUSTOMER_DD, READ));

            assertThat(actual.returnCode()).isEqualTo(STALE_STATUS);
            assertNoRepositoryTouched();
        }

        @Test
        @DisplayName("CLOSE [L196] reports success and discards the file position")
        void closeReportsSuccessAndDiscardsThePosition() {
            final StatementFileResponse actual = execute(new StatementFileRequest(
                    CUSTOMER_DD, CLOSE, STALE_STATUS, "", 1, blankPayload(), 5));

            assertThat(actual.returnCode()).isEqualTo(SUCCESS_STATUS);
            assertThat(actual.sequentialPosition()).isZero();
            assertNoRepositoryTouched();
        }
    }

    @Nested
    @DisplayName("4000-ACCTFILE-PROC [L206] -> 4900-EXIT [L225] -> 4999-EXIT [L228]")
    class AccountFileHandler {

        @Test
        @DisplayName("OPEN [L209] reads ONE bounded row in key order through the scan view, leaving the "
                + "keyed view untouched")
        void openReadsOneBoundedRowThroughTheScanView() {
            execute(request(ACCOUNT_DD, OPEN));

            final ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Limit> bound = ArgumentCaptor.forClass(Limit.class);
            verify(accountScanRepository).findByAcctIdGreaterThanOrderByAcctIdAsc(cursor.capture(),
                    bound.capture());
            verifyNoMoreInteractions(accountScanRepository);
            verifyNoInteractions(accountRepository);
            assertAll(
                    () -> assertThat(cursor.getValue()).isEqualTo(LOWEST_KEY),
                    () -> assertThat(bound.getValue().max()).isEqualTo(REACHABILITY_PROBE_ROWS));
        }

        @Test
        @DisplayName("the keyed READ [L213] slices the key to the runtime length and then RIGHT "
                + "justifies it, ZERO filled, into the numeric record key declared 9(11) at L77 - the "
                + "opposite padding to the customer file's alphanumeric key")
        void keyedReadRightJustifiesAndZeroFillsTheSlicedKey() {
            final StatementFileResponse actual =
                    execute(keyedRequest(ACCOUNT_DD, "123456789012345678901234", 3));

            final ArgumentCaptor<String> recordKey = ArgumentCaptor.forClass(String.class);
            verify(accountRepository).findById(recordKey.capture());
            assertAll(
                    () -> assertThat(recordKey.getValue()).isEqualTo("0".repeat(8) + "123"),
                    () -> assertThat(encodedWidthOf(recordKey.getValue())).isEqualTo(11),
                    () -> assertThat(actual.returnCode()).isEqualTo(RECORD_NOT_FOUND_STATUS));
        }

        @Test
        @DisplayName("stating the FULL twenty-five-character field as the runtime length changes the "
                + "record key and yields not-found, which is how the runtime length is proven to be "
                + "genuinely applied rather than quietly replaced by trimming")
        void statingTheFullKeyFieldWidthChangesTheRecordKeyAndFindsNothing() {
            when(accountRepository.findById(any())).thenReturn(Optional.empty());

            final StatementFileResponse atRuntimeLength =
                    execute(keyedRequest(ACCOUNT_DD, ACCOUNT_ID, ACCOUNT_KEY_LENGTH));
            final StatementFileResponse atFullFieldWidth =
                    execute(keyedRequest(ACCOUNT_DD, ACCOUNT_ID, KEY_FIELD_WIDTH));

            final ArgumentCaptor<String> recordKey = ArgumentCaptor.forClass(String.class);
            verify(accountRepository, times(2)).findById(recordKey.capture());
            assertAll(
                    () -> assertThat(recordKey.getAllValues().get(0))
                            .as("the runtime length yields the identifier itself")
                            .isEqualTo(ACCOUNT_ID),
                    () -> assertThat(recordKey.getAllValues().get(1))
                            .as("the full field width drags the trailing padding into the key")
                            .isEqualTo(" ".repeat(11))
                            .isNotEqualTo(ACCOUNT_ID),
                    () -> assertThat(atRuntimeLength.returnCode())
                            .isEqualTo(RECORD_NOT_FOUND_STATUS),
                    () -> assertThat(atFullFieldWidth.returnCode())
                            .isEqualTo(RECORD_NOT_FOUND_STATUS));
        }

        @Test
        @DisplayName("a full-width key passes through unpadded and the found account is rendered at the "
                + "record's declared width, space-padded to the payload field")
        void aFoundAccountIsRenderedAtItsDeclaredWidth() {
            when(accountRepository.findById(any())).thenReturn(Optional.of(account()));

            final StatementFileResponse actual =
                    execute(keyedRequest(ACCOUNT_DD, ACCOUNT_ID, ACCOUNT_KEY_LENGTH));

            final ArgumentCaptor<String> recordKey = ArgumentCaptor.forClass(String.class);
            verify(accountRepository).findById(recordKey.capture());
            assertAll(
                    () -> assertThat(recordKey.getValue()).isEqualTo(ACCOUNT_ID),
                    () -> assertThat(actual.returnCode()).isEqualTo(SUCCESS_STATUS),
                    () -> assertThat(encodedWidthOf(actual.payload()))
                            .isEqualTo(PAYLOAD_FIELD_WIDTH),
                    () -> assertThat(regionOf(actual.payload(), 0, 11)).isEqualTo(ACCOUNT_ID),
                    () -> assertThat(regionOf(actual.payload(), ACCOUNT_RECORD_WIDTH,
                            PAYLOAD_FIELD_WIDTH))
                            .isEqualTo(" ".repeat(PAYLOAD_FIELD_WIDTH - ACCOUNT_RECORD_WIDTH)),
                    () -> assertThat(actual.sequentialPosition())
                            .as("a keyed read moves no sequential cursor")
                            .isZero());
        }

        @Test
        @DisplayName("an absent row yields the record-not-found status, not an exception")
        void anAbsentRowYieldsRecordNotFound() {
            final StatementFileResponse actual =
                    execute(keyedRequest(ACCOUNT_DD, ACCOUNT_ID, ACCOUNT_KEY_LENGTH));

            assertThat(actual.returnCode()).isEqualTo(RECORD_NOT_FOUND_STATUS);
        }

        @Test
        @DisplayName("a store failure during the keyed READ yields the permanent input-output status")
        void aStoreFailureDuringTheKeyedReadYieldsThePermanentStatus() {
            when(accountRepository.findById(any())).thenThrow(unreachable());

            final StatementFileResponse actual =
                    execute(keyedRequest(ACCOUNT_DD, ACCOUNT_ID, ACCOUNT_KEY_LENGTH));

            assertThat(actual.returnCode()).isEqualTo(PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("no sequential read is offered, because the file is declared random access [L51]")
        void offersNoSequentialRead() {
            final StatementFileResponse actual = execute(request(ACCOUNT_DD, READ));

            assertThat(actual.returnCode()).isEqualTo(STALE_STATUS);
            assertNoRepositoryTouched();
        }

        @Test
        @DisplayName("CLOSE [L221] reports success and discards the file position")
        void closeReportsSuccessAndDiscardsThePosition() {
            final StatementFileResponse actual = execute(new StatementFileRequest(
                    ACCOUNT_DD, CLOSE, STALE_STATUS, "", 1, blankPayload(), 8));

            assertThat(actual.returnCode()).isEqualTo(SUCCESS_STATUS);
            assertThat(actual.sequentialPosition()).isZero();
            assertNoRepositoryTouched();
        }
    }

    @Nested
    @DisplayName("The operation vocabulary [L103-L108]: four live codes and two declared but dead")
    class OperationVocabulary {

        @Test
        @DisplayName("the six declared codes are exactly the six condition names, in declaration order")
        void theSixDeclaredCodesAreTheSixConditionNames() {
            assertThat(List.of(StatementDataAccessService.OPERATION_OPEN,
                    StatementDataAccessService.OPERATION_CLOSE,
                    StatementDataAccessService.OPERATION_READ,
                    StatementDataAccessService.OPERATION_READ_KEYED,
                    StatementDataAccessService.OPERATION_WRITE,
                    StatementDataAccessService.OPERATION_REWRITE))
                    .containsExactly(OPEN, CLOSE, READ, READ_KEYED, WRITE, REWRITE)
                    .allSatisfy(code -> assertThat(encodedWidthOf(code))
                            .isEqualTo(OPERATION_FIELD_WIDTH));
        }

        @ParameterizedTest
        @CsvSource({
            "TRNXFILE, W", "TRNXFILE, Z",
            "XREFFILE, W", "XREFFILE, Z",
            "CUSTFILE, W", "CUSTFILE, Z",
            "ACCTFILE, W", "ACCTFILE, Z"
        })
        @DisplayName("the declared but dead write and rewrite codes are inert on every handler: no "
                + "throw, no repository consulted, and the caller's status left exactly as it arrived")
        void theDeadWriteAndRewriteCodesAreInert(final String ddName, final String operation) {
            final StatementFileRequest given = new StatementFileRequest(ddName, operation,
                    STALE_STATUS, "SOMEKEY", 7, payloadOf("UNTOUCHED"), 6);

            final StatementFileResponse actual = execute(given);

            assertAll(
                    () -> assertThat(actual.returnCode()).isEqualTo(STALE_STATUS),
                    () -> assertThat(actual.payload()).isEqualTo(payloadOf("UNTOUCHED")),
                    () -> assertThat(actual.sequentialPosition()).isEqualTo(6));
            assertNoRepositoryTouched();
            assertThat(transactionSource.requestedPositions()).isEmpty();
        }

        @ParameterizedTest
        @CsvSource({
            "TRNXFILE, Q", "TRNXFILE, ' '",
            "XREFFILE, Q", "XREFFILE, ' '",
            "CUSTFILE, Q", "CUSTFILE, ' '",
            "ACCTFILE, Q", "ACCTFILE, ' '"
        })
        @DisplayName("an operation the contract never declared is inert for exactly the same reason: "
                + "three consecutive checks with no ELSE and no catch-all")
        void anUndeclaredOperationIsEquallyInert(final String ddName, final String operation) {
            final StatementFileResponse actual = execute(request(ddName, operation));

            assertThat(actual.returnCode()).isEqualTo(STALE_STATUS);
            assertNoRepositoryTouched();
        }
    }

    @Nested
    @DisplayName("The status contract: raw, un-normalised, and published on every exit path")
    class StatusContract {

        @ParameterizedTest(name = "{0} leaves a status behind on the {1} path")
        @MethodSource("com.carddemo.service.StatementDataAccessServiceTest"
                + "#everyHandlerAndEveryExitPath")
        @DisplayName("every handler publishes a status on every one of its exit paths, because the "
                + "intermediate exit paragraph is reached on all of them - the path that performed no "
                + "action included, where the status published is the caller's own")
        void everyExitPathOfEveryHandlerPublishesAStatus(final String ddName, final ExitPath path) {
            final String expected = arrangeAndExpect(ddName, path);

            final StatementFileResponse actual = execute(requestFor(ddName, path));

            assertAll(
                    () -> assertThat(actual.returnCode()).isEqualTo(expected),
                    () -> assertThat(encodedWidthOf(actual.returnCode()))
                            .isEqualTo(RETURN_CODE_FIELD_WIDTH));
        }

        @Test
        @DisplayName("the two-character status round-trips raw: success stays success, at-end stays "
                + "at-end, not-found stays not-found and a failure stays a failure, with none of them "
                + "folded into a coarser outcome")
        void theTwoCharacterStatusRoundTripsWithoutNormalisation() {
            transactionSource.place(0, LONG_WORK_IMAGE);
            when(customerRepository.findById(any())).thenThrow(unreachable());

            final StatementFileResponse success = execute(request(TRANSACTION_DD, READ));
            final StatementFileResponse atEnd = execute(request(CROSS_REFERENCE_DD, READ));
            final StatementFileResponse notFound =
                    execute(keyedRequest(ACCOUNT_DD, ACCOUNT_ID, ACCOUNT_KEY_LENGTH));
            final StatementFileResponse failed =
                    execute(keyedRequest(CUSTOMER_DD, CUSTOMER_ID, CUSTOMER_KEY_LENGTH));

            assertAll(
                    () -> assertThat(success.returnCode()).isEqualTo(SUCCESS_STATUS),
                    () -> assertThat(atEnd.returnCode()).isEqualTo(END_OF_FILE_STATUS),
                    () -> assertThat(notFound.returnCode()).isEqualTo(RECORD_NOT_FOUND_STATUS),
                    () -> assertThat(failed.returnCode()).isEqualTo(PERMANENT_ERROR_STATUS),
                    () -> assertThat(List.of(success.returnCode(), atEnd.returnCode(),
                            notFound.returnCode(), failed.returnCode()))
                            .as("four outcomes, four distinct codes, no collapsing")
                            .doesNotHaveDuplicates());
        }

        @Test
        @DisplayName("a status one handler produced does not leak into another handler's outcome, "
                + "because each file's status field is its own and this service holds none of them")
        void aStatusFromOneHandlerDoesNotLeakIntoAnother() {
            when(cardCrossReferenceScanRepository
                    .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(any(), any()))
                    .thenThrow(unreachable());

            final StatementFileResponse crossReferenceFailure =
                    execute(request(CROSS_REFERENCE_DD, READ));
            final StatementFileResponse customerAbsent =
                    execute(keyedRequest(CUSTOMER_DD, CUSTOMER_ID, CUSTOMER_KEY_LENGTH));
            final StatementFileResponse transactionNoAction = execute(request(TRANSACTION_DD, WRITE));

            assertAll(
                    () -> assertThat(crossReferenceFailure.returnCode())
                            .isEqualTo(PERMANENT_ERROR_STATUS),
                    () -> assertThat(customerAbsent.returnCode())
                            .as("the customer file's own status, not the cross-reference file's")
                            .isEqualTo(RECORD_NOT_FOUND_STATUS),
                    () -> assertThat(transactionNoAction.returnCode())
                            .as("a fall-through returns the caller's status, never a neighbour's")
                            .isEqualTo(STALE_STATUS));
        }

        @Test
        @DisplayName("the response offers the typed status without replacing the raw one, and an "
                + "undeclared code is a legitimate runtime value rather than an error")
        void theResponseOffersTheTypedStatusWithoutReplacingTheRawOne() {
            assertAll(
                    () -> assertThat(new StatementFileResponse(TRANSACTION_DD, SUCCESS_STATUS, "", 0)
                            .status()).contains(FileStatus.SUCCESS),
                    () -> assertThat(new StatementFileResponse(TRANSACTION_DD, END_OF_FILE_STATUS,
                            "", 0).status()).contains(FileStatus.END_OF_FILE),
                    () -> assertThat(new StatementFileResponse(TRANSACTION_DD,
                            RECORD_NOT_FOUND_STATUS, "", 0).status())
                            .contains(FileStatus.RECORD_NOT_FOUND),
                    () -> assertThat(new StatementFileResponse(TRANSACTION_DD,
                            PERMANENT_ERROR_STATUS, "", 0).status())
                            .contains(FileStatus.PERMANENT_ERROR),
                    () -> assertThat(new StatementFileResponse(TRANSACTION_DD, STALE_STATUS, "", 0)
                            .status()).isEmpty(),
                    () -> assertThat(new StatementFileResponse(TRANSACTION_DD, STALE_STATUS, "", 0)
                            .returnCode()).isEqualTo(STALE_STATUS));
        }
    }

    @Nested
    @DisplayName("The payload field [L112]: declared width, space padding and no residue")
    class PayloadContract {

        @Test
        @DisplayName("a record image is placed left-justified in the thousand-character field and the "
                + "remainder is space padding, measured on encoded bytes and never trimmed")
        void aRecordImageIsPlacedLeftJustifiedAndSpacePadded() {
            crossReferencePage(crossReference(CARD_NUMBER));

            final StatementFileResponse actual = execute(request(CROSS_REFERENCE_DD, READ));

            assertAll(
                    () -> assertThat(encodedWidthOf(actual.payload()))
                            .isEqualTo(PAYLOAD_FIELD_WIDTH),
                    () -> assertThat(regionOf(actual.payload(), 0, CROSS_REFERENCE_RECORD_WIDTH))
                            .isEqualTo(crossReferenceImage(CARD_NUMBER)),
                    () -> assertThat(regionOf(actual.payload(), CROSS_REFERENCE_RECORD_WIDTH,
                            PAYLOAD_FIELD_WIDTH))
                            .isEqualTo(" ".repeat(PAYLOAD_FIELD_WIDTH
                                    - CROSS_REFERENCE_RECORD_WIDTH)));
        }

        @Test
        @DisplayName("the field is written whole on every read, so a long record followed by a shorter "
                + "one leaves no residue of the long one behind")
        void aShorterSecondRecordLeavesNoResidueOfTheFirst() {
            transactionSource.place(0, LONG_WORK_IMAGE);
            transactionSource.place(1, SHORT_WORK_IMAGE);

            final StatementFileResponse first = execute(new StatementFileRequest(TRANSACTION_DD,
                    READ, STALE_STATUS, "", 1, blankPayload(), 0));
            final StatementFileResponse second = execute(new StatementFileRequest(TRANSACTION_DD,
                    READ, STALE_STATUS, "", 1, first.payload(), 1));

            assertAll(
                    () -> assertThat(first.payload()).isEqualTo(payloadOf(LONG_WORK_IMAGE)),
                    () -> assertThat(second.payload()).isEqualTo(payloadOf(SHORT_WORK_IMAGE)),
                    () -> assertThat(encodedWidthOf(second.payload()))
                            .isEqualTo(PAYLOAD_FIELD_WIDTH),
                    () -> assertThat(regionOf(second.payload(),
                            encodedWidthOf(SHORT_WORK_IMAGE), PAYLOAD_FIELD_WIDTH))
                            .as("no byte of the first record survives past the second's own width")
                            .isEqualTo(" ".repeat(PAYLOAD_FIELD_WIDTH
                                    - encodedWidthOf(SHORT_WORK_IMAGE))),
                    () -> assertThat(second.payload()).doesNotContain("ENDOFFIRSTREAD"));
        }

        @ParameterizedTest
        @CsvSource({
            "TRNXFILE, O", "TRNXFILE, C", "TRNXFILE, R", "TRNXFILE, K", "TRNXFILE, W",
            "XREFFILE, O", "XREFFILE, C", "XREFFILE, R", "XREFFILE, K", "XREFFILE, W",
            "CUSTFILE, O", "CUSTFILE, C", "CUSTFILE, R", "CUSTFILE, K", "CUSTFILE, W",
            "ACCTFILE, O", "ACCTFILE, C", "ACCTFILE, R", "ACCTFILE, K", "ACCTFILE, W"
        })
        @DisplayName("no path that reads no record touches the payload: it is handed back exactly as "
                + "the caller supplied it")
        void aPathThatReadsNoRecordHandsThePayloadBackUntouched(final String ddName,
                                                                final String operation) {
            final StatementFileRequest given = new StatementFileRequest(ddName, operation,
                    STALE_STATUS, "", 1, payloadOf("SUPPLIED-BY-THE-CALLER"), 0);

            final StatementFileResponse actual = execute(given);

            assertThat(actual.payload()).isEqualTo(payloadOf("SUPPLIED-BY-THE-CALLER"));
            assertThat(encodedWidthOf(actual.payload())).isEqualTo(PAYLOAD_FIELD_WIDTH);
        }
    }

    @Nested
    @DisplayName("The parameter object mirrors 01 LK-M03B-AREA [L100-L112]")
    class ParameterObjectContract {

        @Test
        @DisplayName("the published widths are the picture-clause widths, all five of them")
        void thePublishedWidthsAreThePictureClauseWidths() {
            assertAll(
                    () -> assertThat(StatementDataAccessService.DD_NAME_WIDTH)
                            .isEqualTo(DD_NAME_FIELD_WIDTH),
                    () -> assertThat(StatementDataAccessService.OPERATION_WIDTH)
                            .isEqualTo(OPERATION_FIELD_WIDTH),
                    () -> assertThat(StatementDataAccessService.RETURN_CODE_WIDTH)
                            .isEqualTo(RETURN_CODE_FIELD_WIDTH),
                    () -> assertThat(StatementDataAccessService.KEY_WIDTH)
                            .isEqualTo(KEY_FIELD_WIDTH),
                    () -> assertThat(StatementDataAccessService.PAYLOAD_WIDTH)
                            .isEqualTo(PAYLOAD_FIELD_WIDTH));
        }

        @Test
        @DisplayName("the four published DD names are the four the selection matches, each exactly "
                + "eight characters so it compares equal to a normalised field without padding")
        void theFourPublishedDdNamesAreTheFourTheSelectionMatches() {
            assertThat(List.of(StatementDataAccessService.DD_TRNXFILE,
                    StatementDataAccessService.DD_XREFFILE, StatementDataAccessService.DD_CUSTFILE,
                    StatementDataAccessService.DD_ACCTFILE))
                    .containsExactly(TRANSACTION_DD, CROSS_REFERENCE_DD, CUSTOMER_DD, ACCOUNT_DD)
                    .allSatisfy(name -> assertThat(encodedWidthOf(name))
                            .isEqualTo(DD_NAME_FIELD_WIDTH));
        }

        @Test
        @DisplayName("every character component is held at its declared width")
        void everyCharacterComponentIsHeldAtItsDeclaredWidth() {
            final StatementFileRequest given =
                    new StatementFileRequest("DD", "O", "0", "K", 1, "P", 0);

            assertAll(
                    () -> assertThat(given.ddName()).isEqualTo("DD" + " ".repeat(6)),
                    () -> assertThat(given.operation()).isEqualTo(OPEN),
                    () -> assertThat(given.returnCode()).isEqualTo("0 "),
                    () -> assertThat(encodedWidthOf(given.key())).isEqualTo(KEY_FIELD_WIDTH),
                    () -> assertThat(encodedWidthOf(given.payload()))
                            .isEqualTo(PAYLOAD_FIELD_WIDTH),
                    () -> assertThat(given.regulatedFieldRevealer()).isNotNull());
        }

        @Test
        @DisplayName("an over-wide value is truncated on the right, as a move into an alphanumeric "
                + "field of that width would be")
        void anOverWideValueIsTruncatedOnTheRight() {
            final StatementFileRequest given = new StatementFileRequest(CUSTOMER_DD, READ_KEYED,
                    SUCCESS_STATUS, "A".repeat(30) + "TAIL", KEY_FIELD_WIDTH, "", 0);

            assertThat(given.key()).isEqualTo("A".repeat(KEY_FIELD_WIDTH));
        }

        @Test
        @DisplayName("a negative sequential position is refused by both halves of the pair")
        void aNegativeSequentialPositionIsRefusedByBothHalves() {
            assertAll(
                    () -> assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                            () -> new StatementFileRequest(CUSTOMER_DD, OPEN, SUCCESS_STATUS, "", 1,
                                    "", -1)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                            () -> new StatementFileResponse(CUSTOMER_DD, SUCCESS_STATUS, "", -1)));
        }

        @Test
        @DisplayName("the two predicates compare whole normalised fields, not prefixes")
        void theTwoPredicatesCompareWholeNormalisedFields() {
            final StatementFileRequest given = request(CUSTOMER_DD, OPEN);

            assertAll(
                    () -> assertThat(given.hasDdName(StatementDataAccessService.DD_CUSTFILE))
                            .isTrue(),
                    () -> assertThat(given.hasDdName(StatementDataAccessService.DD_ACCTFILE))
                            .isFalse(),
                    () -> assertThat(given.hasOperation(StatementDataAccessService.OPERATION_OPEN))
                            .isTrue(),
                    () -> assertThat(given.hasOperation(StatementDataAccessService.OPERATION_CLOSE))
                            .isFalse());
        }

        @Test
        @DisplayName("the seven-component form defaults the revealing function to the identity, which "
                + "is what the three files that hold no regulated identifier need")
        void theSevenComponentFormDefaultsTheRevealingFunctionToTheIdentity() {
            final StatementFileRequest given =
                    new StatementFileRequest(CUSTOMER_DD, OPEN, SUCCESS_STATUS, "", 1, "", 0);

            assertThat(given.regulatedFieldRevealer()).isEqualTo(UnaryOperator.identity());
        }

        @Test
        @DisplayName("the fixture identifiers and the stated runtime key lengths agree with the record "
                + "key widths the two randomly accessed files declare")
        void theFixtureIdentifiersOccupyTheirDeclaredWidths() {
            assertAll(
                    () -> assertThat(encodedWidthOf(CUSTOMER_ID)).isEqualTo(CUSTOMER_KEY_LENGTH),
                    () -> assertThat(encodedWidthOf(ACCOUNT_ID)).isEqualTo(ACCOUNT_KEY_LENGTH),
                    () -> assertThat(encodedWidthOf(CARD_NUMBER)).isEqualTo(CARD_NUMBER_LENGTH),
                    () -> assertThat(encodedWidthOf(NEXT_CARD_NUMBER))
                            .isEqualTo(CARD_NUMBER_LENGTH),
                    () -> assertThat(encodedWidthOf(ENVELOPE_PREFIX))
                            .isEqualTo(ENVELOPE_PREFIX_LENGTH),
                    () -> assertThat(encodedWidthOf(crossReferenceImage(CARD_NUMBER)))
                            .isEqualTo(CROSS_REFERENCE_RECORD_WIDTH));
        }

        @Test
        @DisplayName("the response echoes the selector, so a caller correlating a response with a "
                + "request needs no separate bookkeeping")
        void theResponseEchoesTheSelector() {
            final StatementFileResponse actual = execute(request(CROSS_REFERENCE_DD, OPEN));

            assertThat(actual.ddName()).isEqualTo(CROSS_REFERENCE_DD);
        }

        @Test
        @DisplayName("the service refuses each of its four null collaborators at construction")
        void theServiceRefusesEachNullCollaborator() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new StatementDataAccessService(null, customerRepository,
                                    accountRepository, accountScanRepository)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new StatementDataAccessService(cardCrossReferenceScanRepository,
                                    null, accountRepository, accountScanRepository)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new StatementDataAccessService(cardCrossReferenceScanRepository,
                                    customerRepository, null, accountScanRepository)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new StatementDataAccessService(cardCrossReferenceScanRepository,
                                    customerRepository, accountRepository, null)));
        }
    }

    @Nested
    @DisplayName("Boundary input: nothing escapes as a null-pointer failure")
    class BoundaryInput {

        @Test
        @DisplayName("a null selector is an unset eight-character field, so it matches no arm and "
                + "returns the caller's status rather than raising anything")
        void aNullSelectorMatchesNoArm() {
            final StatementFileRequest given =
                    new StatementFileRequest(null, READ, STALE_STATUS, "", 1, blankPayload(), 0);

            final StatementFileResponse actual = execute(given);

            assertAll(
                    () -> assertThat(actual.ddName()).isEqualTo(" ".repeat(DD_NAME_FIELD_WIDTH)),
                    () -> assertThat(actual.returnCode()).isEqualTo(STALE_STATUS));
            assertNoRepositoryTouched();
        }

        @Test
        @DisplayName("a null operation is an unset one-character field, so no handler check matches "
                + "and the handler falls through having performed nothing")
        void aNullOperationMatchesNoCheck() {
            final StatementFileRequest given = new StatementFileRequest(ACCOUNT_DD, null,
                    STALE_STATUS, ACCOUNT_ID, ACCOUNT_KEY_LENGTH, blankPayload(), 0);

            final StatementFileResponse actual = execute(given);

            assertThat(actual.returnCode()).isEqualTo(STALE_STATUS);
            assertNoRepositoryTouched();
        }

        @Test
        @DisplayName("a null status is an unset two-character field and is handed back as such on a "
                + "fall-through, still exactly two bytes wide")
        void aNullStatusIsAnUnsetTwoCharacterField() {
            final StatementFileRequest given = new StatementFileRequest(TRANSACTION_DD, WRITE, null,
                    "", 1, blankPayload(), 0);

            final StatementFileResponse actual = execute(given);

            assertThat(actual.returnCode()).isEqualTo("  ");
            assertThat(encodedWidthOf(actual.returnCode())).isEqualTo(RETURN_CODE_FIELD_WIDTH);
        }

        @Test
        @DisplayName("a null key is an unset twenty-five-character field, so a keyed read looks up a "
                + "blank record key and reports not found rather than raising anything")
        void aNullKeyLooksUpABlankRecordKey() {
            final StatementFileRequest given = new StatementFileRequest(CUSTOMER_DD, READ_KEYED,
                    STALE_STATUS, null, 9, blankPayload(), 0);

            final StatementFileResponse actual = execute(given);

            final ArgumentCaptor<String> recordKey = ArgumentCaptor.forClass(String.class);
            verify(customerRepository).findById(recordKey.capture());
            assertThat(recordKey.getValue()).isEqualTo(" ".repeat(9));
            assertThat(actual.returnCode()).isEqualTo(RECORD_NOT_FOUND_STATUS);
        }

        @Test
        @DisplayName("a null payload is an unset thousand-character field of spaces")
        void aNullPayloadIsAnUnsetThousandCharacterField() {
            final StatementFileRequest given =
                    new StatementFileRequest(TRANSACTION_DD, WRITE, STALE_STATUS, "", 1, null, 0);

            assertThat(given.payload()).isEqualTo(blankPayload());
            assertThat(encodedWidthOf(given.payload())).isEqualTo(PAYLOAD_FIELD_WIDTH);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -1000, 26, 100})
        @DisplayName("a runtime key length that lies outside the key field is refused before any "
                + "lookup is attempted, which is what a bounds-checked reference modification does")
        void aRuntimeKeyLengthOutsideTheKeyFieldIsRefused(final int keyLength) {
            final StatementFileRequest given = keyedRequest(ACCOUNT_DD, ACCOUNT_ID, keyLength);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> execute(given));
            assertNoRepositoryTouched();
        }

        @Test
        @DisplayName("a runtime key length of exactly one, and of exactly the field width, are both "
                + "inside the field and are both honoured")
        void theExtremeRuntimeKeyLengthsInsideTheFieldAreHonoured() {
            final StatementFileResponse atOne =
                    execute(keyedRequest(CUSTOMER_DD, CUSTOMER_ID, 1));
            final StatementFileResponse atFieldWidth =
                    execute(keyedRequest(CUSTOMER_DD, CUSTOMER_ID, KEY_FIELD_WIDTH));

            final ArgumentCaptor<String> recordKey = ArgumentCaptor.forClass(String.class);
            verify(customerRepository, times(2)).findById(recordKey.capture());
            assertAll(
                    () -> assertThat(recordKey.getAllValues().get(0))
                            .isEqualTo("0" + " ".repeat(8)),
                    () -> assertThat(recordKey.getAllValues().get(1)).isEqualTo(CUSTOMER_ID),
                    () -> assertThat(atOne.returnCode()).isEqualTo(RECORD_NOT_FOUND_STATUS),
                    () -> assertThat(atFieldWidth.returnCode())
                            .isEqualTo(RECORD_NOT_FOUND_STATUS));
        }
    }
}
