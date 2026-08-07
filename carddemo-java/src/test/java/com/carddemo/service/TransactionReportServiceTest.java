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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategory;
import com.carddemo.domain.TransactionType;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.domain.id.TransactionCategoryId;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.support.TestDataFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertAll;
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
 * Surefire unit suite for {@link TransactionReportService}, the Java translation of the batch
 * transaction detail report program {@code app/cbl/CBTRN03C.cbl}.
 *
 * <h2>Provenance</h2>
 *
 * <p>Checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}; upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p>The authority is <strong>649 lines</strong> long. Its {@code PROCEDURE DIVISION} carries
 * <strong>26 named paragraph labels</strong> plus the <strong>unnamed driving body at lines
 * 160-217</strong>, giving <strong>27 paragraph units</strong>. Both counts are true of different
 * things and the project specification records only the smaller one, so both are stated here: the
 * plan's figure of 26 counts labels alone, while a census that also counts the unlabelled body -
 * the unit that actually sequences the program - gives 27. This class contributes
 * <strong>27 traceability rows</strong>, and the map below names the covering group for each unit.
 *
 * <h2>Paragraph unit coverage map</h2>
 *
 * <ol>
 *   <li>the unnamed driving body, lines 160-217 - {@code TheDrivingBody}</li>
 *   <li>{@code 0550-DATEPARM-READ}, line 220 - {@code TheInclusiveDateBounds},
 *       {@code NullAndBoundaryInput}</li>
 *   <li>{@code 1000-TRANFILE-GET-NEXT}, line 248 - {@code TheFrozenOrderedInput}</li>
 *   <li>{@code 1100-WRITE-TRANSACTION-REPORT}, line 274 - {@code ThePaginationArithmetic}</li>
 *   <li>{@code 1110-WRITE-PAGE-TOTALS}, line 293 - {@code TheAccumulationChain}</li>
 *   <li>{@code 1120-WRITE-ACCOUNT-TOTALS}, line 306 - {@code TheAccumulationChain},
 *       {@code TheReproducedLegacyDefects}</li>
 *   <li>{@code 1110-WRITE-GRAND-TOTALS}, line 318 - {@code TheAccumulationChain}</li>
 *   <li>{@code 1120-WRITE-HEADERS}, line 324 - {@code TheHeaderBlock}</li>
 *   <li>{@code 1111-WRITE-REPORT-REC}, line 343, the single writer - {@code TheSingleWriter}</li>
 *   <li>{@code 1120-WRITE-DETAIL}, line 361 - {@code TheRecordWidthContract},
 *       {@code TheTwoAmountMasks}</li>
 *   <li>{@code 0000-TRANFILE-OPEN}, line 376 - {@code TheDrivingBody}</li>
 *   <li>{@code 0100-REPTFILE-OPEN}, line 394 - {@code TheDrivingBody}</li>
 *   <li>{@code 0200-CARDXREF-OPEN}, line 412 - {@code TheDrivingBody}</li>
 *   <li>{@code 0300-TRANTYPE-OPEN}, line 430 - {@code TheDrivingBody}</li>
 *   <li>{@code 0400-TRANCATG-OPEN}, line 448 - {@code TheDrivingBody}</li>
 *   <li>{@code 0500-DATEPARM-OPEN}, line 466 - {@code TheDrivingBody}</li>
 *   <li>{@code 1500-A-LOOKUP-XREF}, line 484 - {@code TheReferenceLookupAbends},
 *       {@code TheLookupKeyShapes}</li>
 *   <li>{@code 1500-B-LOOKUP-TRANTYPE}, line 494 - {@code TheReferenceLookupAbends},
 *       {@code TheLookupKeyShapes}</li>
 *   <li>{@code 1500-C-LOOKUP-TRANCATG}, line 504 - {@code TheReferenceLookupAbends},
 *       {@code TheLookupKeyShapes}</li>
 *   <li>{@code 9000-TRANFILE-CLOSE}, line 514 - {@code TheDrivingBody}</li>
 *   <li>{@code 9100-REPTFILE-CLOSE}, line 532 - {@code TheDrivingBody}</li>
 *   <li>{@code 9200-CARDXREF-CLOSE}, line 551 - {@code TheDrivingBody}</li>
 *   <li>{@code 9300-TRANTYPE-CLOSE}, line 569 - {@code TheDrivingBody}</li>
 *   <li>{@code 9400-TRANCATG-CLOSE}, line 587 - {@code TheDrivingBody}</li>
 *   <li>{@code 9500-DATEPARM-CLOSE}, line 605 - {@code TheDrivingBody}</li>
 *   <li>{@code 9999-ABEND-PROGRAM}, line 626, whose abort call sits at line 630 -
 *       {@code TheReferenceLookupAbends}</li>
 *   <li>{@code 9910-DISPLAY-IO-STATUS}, line 633 - {@code TheFileStatusLevels}</li>
 * </ol>
 *
 * <h2>What this suite pins, and why each assertion can fail</h2>
 *
 * <p><strong>No arithmetic is introduced anywhere.</strong> A verb census over the whole authority
 * found <strong>zero computation statements</strong>. The member performs <strong>sixteen
 * additions</strong> and <strong>two subtractions</strong>, and both subtractions operate on an
 * integer status value rather than on money. Every expected total in this class is therefore
 * hand-computed by addition alone and written as a {@code BigDecimal} literal.
 *
 * <p><strong>The accumulation chain is the decisive assertion.</strong> A detail amount reaches the
 * account total and the page total; the page total, and nothing else, reaches the grand total.
 * Account totals never roll up. The multi-page multi-account fixture is built so that a direct
 * detail-to-grand-total feed and an account-total roll-up each produce a <em>different</em> number
 * from the faithful one, which is the only construction under which the assertion is able to fail.
 *
 * <p><strong>Pagination is a modulo test over every written line, with per-caller increments.</strong>
 * The single writer never increments; its callers do, by four for the header block, two for a page
 * total, two for an account total, one for a detail line and <em>zero</em> for the grand total.
 * The arithmetic consequence, asserted rather than inferred, is that page one holds sixteen detail
 * rows and a steady-state page holds fourteen.
 *
 * <p><strong>Three legacy defects are reproduced and pinned as defects.</strong> Because the
 * account-total block advances the counter by two at once it can step <em>past</em> a multiple of
 * the page size, so a page break is missed outright. The non-matching arm of the range filter
 * escapes the whole driving loop rather than skipping one record, so a single out-of-window record
 * truncates the entire report. And at end of file the last amount is accumulated a second time
 * while no account total is written at all. A future "fix" to any of the three is a parity
 * regression, and each is asserted here so that it registers as one.
 *
 * <p><strong>Every emitted record is exactly 133 encoded bytes</strong> - the blank line and the
 * hyphen rule line included, because both are full-width records rather than short ones. Widths are
 * measured on encoded bytes and never on characters, and nothing is trimmed before comparison.
 *
 * <p><strong>The two fifteen-character amount masks are distinct.</strong> The detail mask carries a
 * leading minus or a space and is zero-suppressed; the three total masks are always signed. The same
 * positive value therefore renders differently in a detail line and in a total line. A negative
 * value renders identically under both, because both carry the minus, and a zero blanks all fifteen
 * positions under both - so those two cases are asserted as equalities, which is the contract rather
 * than a weaker assertion.
 *
 * <h2>Independent oracle</h2>
 *
 * <p>No expected value in this class is produced by a production artefact. Every record literal is
 * declared here with its padding written as an explicit repetition so that each width is visible to
 * a reviewer, the hyphen rule is written as a repetition of one hyphen, both mask renderings are
 * declared as literals, and every total is hand-computed. In particular
 * {@code ReportLineFormatter} is never called: doing so would make the byte-parity assertions
 * tautological.
 *
 * <h2>Harness</h2>
 *
 * <p>A pure unit suite. No container, no application context, no batch infrastructure, no database
 * connection, no port and no network. The three reference stores, the abend path, the record sink
 * and the transaction store are Mockito mocks under strict stubbing. The service under test declares
 * no clock, so no clock is injected; every date in this class is an explicit ten-character literal
 * or an explicit {@code LocalDate}, and no ambient time source is consulted anywhere. The report
 * window brackets the module's pinned instant of 2022-06-10 19:27:53.000000.
 *
 * @see TransactionReportService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionReportService: the 133-byte transaction detail report, its pagination "
        + "arithmetic, its accumulation chain and its three reproduced legacy defects")
class TransactionReportServiceTest {

    // =============================================================================================
    // Contract literals. Every one of these is a legacy formatting or layout contract value read
    // from the authority and its report copybook, declared here rather than imported so that this
    // suite is an independent oracle. None of them is a tuning parameter.
    // =============================================================================================

    /** The fixed report record width in encoded bytes. */
    private static final int RECORD_WIDTH = 133;

    /** The page size the modulo test divides by. */
    private static final int PAGE_SIZE = 20;

    /** The records the header block emits. */
    private static final int HEADER_BLOCK_LINES = 4;

    /** The offset at which every amount field starts, in every group that carries one. */
    private static final int AMOUNT_OFFSET = 97;

    /** The width of both amount masks. */
    private static final int AMOUNT_MASK_WIDTH = 15;

    /** The width of both date bounds, everywhere they appear. */
    private static final int DATE_WIDTH = 10;

    /** The offset of the lower bound inside the report name header. */
    private static final int HEADER_START_DATE_OFFSET = 91;

    /** The offset of the upper bound inside the report name header. */
    private static final int HEADER_END_DATE_OFFSET = 105;

    /** The offset of the resolved account identifier inside a detail line. */
    private static final int DETAIL_ACCOUNT_ID_OFFSET = 17;

    /** The width of the resolved account identifier inside a detail line. */
    private static final int DETAIL_ACCOUNT_ID_WIDTH = 11;

    /** The width of the transaction identifier, which is also the width of a detail line's key. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** Detail rows on the first page: the four header records consume four of the twenty. */
    private static final int FIRST_PAGE_DETAIL_ROWS = 16;

    /** Detail rows on a steady-state page: four header and two page-total records consume six. */
    private static final int STEADY_STATE_DETAIL_ROWS = 14;

    // =============================================================================================
    // Fixture values. The window brackets the module's pinned instant so that a record carrying the
    // seeded original timestamp lies inside it.
    // =============================================================================================

    /** The inclusive lower bound, ten characters. */
    private static final String WINDOW_START = "2022-06-01";

    /** The inclusive upper bound, ten characters. */
    private static final String WINDOW_END = "2022-06-30";

    /** The inclusive lower bound as a date, for a record that must land exactly on it. */
    private static final LocalDate WINDOW_START_DATE = LocalDate.of(2022, 6, 1);

    /** The inclusive upper bound as a date, for a record that must land exactly on it. */
    private static final LocalDate WINDOW_END_DATE = LocalDate.of(2022, 6, 30);

    /** A processing date comfortably inside the window; it is the module's pinned instant's date. */
    private static final LocalDate INSIDE_WINDOW_DATE = LocalDate.of(2022, 6, 10);

    /** The module's pinned instant, carried as a record's twenty-six character origination stamp. */
    private static final String PINNED_ORIGINATION_TIMESTAMP = "2022-06-10 19:27:53.000000";

    private static final String CARD_A = "4111111111111111";

    private static final String CARD_B = "4222222222222222";

    private static final String CUSTOMER_A = "000000001";

    private static final String CUSTOMER_B = "000000002";

    private static final String ACCOUNT_A = "00000000011";

    private static final String ACCOUNT_B = "00000000022";

    private static final String TYPE_CODE = "01";

    private static final String TYPE_DESCRIPTION = "PURCHASE";

    private static final String CATEGORY_CODE = "0005";

    private static final String CATEGORY_DESCRIPTION = "RESTAURANT";

    private static final String SOURCE = "POS TERM";

    /** The abend culprit is the legacy program name, eight characters exactly. */
    private static final String PROGRAM_NAME = "CBTRN03C";

    /** The failing operation every reference lookup reports. */
    private static final String OPERATION_READ = "READ";

    private static final String DD_CARDXREF = "CARDXREF";

    private static final String DD_TRANTYPE = "TRANTYPE";

    private static final String DD_TRANCATG = "TRANCATG";

    /**
     * The three raw two-byte file statuses this feature actually compares. No other literal is
     * referenced anywhere in this class, because no other literal is compared anywhere in the
     * estate.
     */
    private static final String STATUS_SUCCESS = "00";

    private static final String STATUS_END_OF_FILE = "10";

    private static final String STATUS_RECORD_NOT_FOUND = "23";

    // =============================================================================================
    // Expected record literals. Padding is written as an explicit repetition so that every width is
    // visible; none of these is obtained from a production formatter.
    // =============================================================================================

    /** The hyphen rule line: one hyphen repeated across the whole record. */
    private static final String RULE_LINE = "-".repeat(RECORD_WIDTH);

    /** The blank line of the header block: a real full-width record, not padding. */
    private static final String BLANK_LINE = " ".repeat(RECORD_WIDTH);

    /** The report name header for this suite's window: 8 + 30 + 24 + 17 + 12 + 10 + 4 + 10 + 18. */
    private static final String NAME_HEADER_LINE = "DALYREPT" + " ".repeat(30)
            + "Daily Transaction Report" + " ".repeat(17)
            + "Date Range: " + WINDOW_START + " to " + WINDOW_END + " ".repeat(18);

    /** The column header: 14 + 3 + 10 + 2 + 16 + 3 + 13 + 22 + 11 + 3 + 1 + 14 + 2 + 19. */
    private static final String COLUMN_HEADER_LINE = "Transaction ID" + " ".repeat(3)
            + "Account ID" + " ".repeat(2)
            + "Transaction Type" + " ".repeat(3)
            + "Tran Category" + " ".repeat(22)
            + "Tran Source" + " ".repeat(3)
            + " "
            + "        Amount" + " ".repeat(2)
            + " ".repeat(19);

    /** The page-total label occupies eleven bytes, so its dot fill is eighty-six. */
    private static final String PAGE_TOTAL_PREFIX = "Page Total" + " " + ".".repeat(86);

    /** The account-total label occupies thirteen bytes, so its dot fill is eighty-four. */
    private static final String ACCOUNT_TOTAL_PREFIX = "Account Total" + ".".repeat(84);

    /** The grand-total label occupies eleven bytes, so its dot fill is eighty-six. */
    private static final String GRAND_TOTAL_PREFIX = "Grand Total" + ".".repeat(86);

    /** Trailing filler common to all three total groups: 112 native bytes padded to 133. */
    private static final String TOTAL_LINE_SUFFIX = " ".repeat(21);

    /** One currency unit under the leading-minus, zero-suppressed detail mask. */
    private static final String DETAIL_MASK_ONE_UNIT = " ".repeat(11) + "1.00";

    /** One currency unit under the always-signed total mask. */
    private static final String TOTAL_MASK_ONE_UNIT = "+" + " ".repeat(10) + "1.00";

    /** Two currency units under the detail mask. */
    private static final String DETAIL_MASK_TWO_UNITS = " ".repeat(11) + "2.00";

    /** Two currency units under the always-signed total mask. */
    private static final String TOTAL_MASK_TWO_UNITS = "+" + " ".repeat(10) + "2.00";

    /** The grouped positive amount used by the mask comparisons, under the detail mask. */
    private static final String DETAIL_MASK_GROUPED_POSITIVE = " ".repeat(7) + "1,234.56";

    /** The same value under the total mask: a plus sign where the detail mask carries a space. */
    private static final String TOTAL_MASK_GROUPED_POSITIVE = "+" + " ".repeat(6) + "1,234.56";

    /** The negated value under the detail mask. */
    private static final String DETAIL_MASK_GROUPED_NEGATIVE = "-" + " ".repeat(6) + "1,234.56";

    /** The negated value under the total mask, which is byte-identical to the detail rendering. */
    private static final String TOTAL_MASK_GROUPED_NEGATIVE = "-" + " ".repeat(6) + "1,234.56";

    /** Zero blanks all fifteen positions under both masks. */
    private static final String BLANK_AMOUNT_MASK = " ".repeat(AMOUNT_MASK_WIDTH);

    /** Zero at the monetary scale, which is what every accumulator's value clause establishes. */
    private static final BigDecimal ZERO_AT_MONETARY_SCALE = new BigDecimal("0.00");

    // =============================================================================================
    // Collaborators. Every one is a mock; strict stubbing makes an unused stubbing a failure.
    // =============================================================================================

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private TransactionTypeRepository transactionTypeRepository;

    @Mock
    private TransactionCategoryRepository transactionCategoryRepository;

    @Mock
    private AbendService abendService;

    /**
     * The report record destination, mocked so that the number of writer interactions can be
     * compared against the number of records the run reports.
     */
    @Mock
    private Consumer<String> reportRecordSink;

    /**
     * Present so that the suite can prove a negative: the report never touches the transaction
     * store. The store's only declared query beyond its inherited surface is the maximum-identifier
     * finder, which belongs to the bill-payment feature alone; the report's input arrives as a
     * frozen ordered generation from the preceding batch step, so no live query happens here at all.
     */
    @Mock
    private TransactionRepository transactionRepository;

    private TransactionReportService reportService;

    private Logger serviceLogger;

    private Level previousLogLevel;

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void buildServiceAndAttachLogCapture() {
        reportService = new TransactionReportService(cardCrossReferenceRepository,
                transactionTypeRepository, transactionCategoryRepository, abendService);

        serviceLogger = (Logger) LoggerFactory.getLogger(TransactionReportService.class);
        previousLogLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.TRACE);
        logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
    }

    @AfterEach
    void detachLogCapture() {
        serviceLogger.detachAppender(logCapture);
        serviceLogger.setLevel(previousLogLevel);
        logCapture.stop();
    }

    // =============================================================================================
    // Fixture and oracle helpers.
    // =============================================================================================

    /** The seven groups the report copybook declares, plus the detail group. */
    private enum LineKind {

        /** The report name header, which carries both ten-character bounds. */
        NAME_HEADER,

        /** The full-width blank record of the header block. */
        BLANK,

        /** The column header. */
        COLUMN_HEADER,

        /** The full-width hyphen rule. */
        RULE,

        /** One transaction detail line. */
        DETAIL,

        /** A page total, always signed. */
        PAGE_TOTAL,

        /** An account total, always signed. */
        ACCOUNT_TOTAL,

        /** The grand total, always signed and never followed by a line-counter increment. */
        GRAND_TOTAL
    }

    /**
     * One generated report, paired with the observations the run reported.
     *
     * <p>The service streams every record to its sink as it composes it and never holds the report,
     * so collecting the records is this suite's choice rather than a production behaviour.
     *
     * @param lines  the records the sink received, in emission order
     * @param result the run's own observations
     */
    private record Report(List<String> lines,
                          TransactionReportService.TransactionReportResult result) {
    }

    /**
     * Classifies one emitted record by the group that produced it, using only literals declared in
     * this class. No production classifier is consulted.
     *
     * @param line the emitted record
     * @return the group that produced it
     */
    private static LineKind kindOf(final String line) {
        if (RULE_LINE.equals(line)) {
            return LineKind.RULE;
        }
        if (BLANK_LINE.equals(line)) {
            return LineKind.BLANK;
        }
        if (line.startsWith("DALYREPT")) {
            return LineKind.NAME_HEADER;
        }
        if (line.startsWith("Transaction ID")) {
            return LineKind.COLUMN_HEADER;
        }
        if (line.startsWith("Page Total")) {
            return LineKind.PAGE_TOTAL;
        }
        if (line.startsWith("Account Total")) {
            return LineKind.ACCOUNT_TOTAL;
        }
        if (line.startsWith("Grand Total")) {
            return LineKind.GRAND_TOTAL;
        }
        return LineKind.DETAIL;
    }

    /**
     * Reads the fifteen-character amount field out of an emitted record without trimming it.
     *
     * @param line the emitted record
     * @return the mask exactly as emitted
     */
    private static String maskOn(final String line) {
        return line.substring(AMOUNT_OFFSET, AMOUNT_OFFSET + AMOUNT_MASK_WIDTH);
    }

    /**
     * Reads an amount back out of a mask with this suite's own parser, so that no production codec
     * or formatter participates in producing an expected value. Group separators, blanks and the
     * always-signed plus are removed; a leading minus is kept; an entirely blank field is the
     * rendering of zero.
     *
     * @param line the emitted record
     * @return the amount the record carries
     */
    private static BigDecimal amountOn(final String line) {
        final String digits = maskOn(line).replace(",", "").replace(" ", "").replace("+", "");
        return digits.isEmpty() ? ZERO_AT_MONETARY_SCALE : new BigDecimal(digits);
    }

    /**
     * Measures a record in encoded bytes, which is the contract, rather than in characters.
     *
     * @param line the emitted record
     * @return its width in encoded bytes
     */
    private static int encodedWidthOf(final String line) {
        return line.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Builds a sixteen-character transaction identifier without any locale-sensitive formatting, so
     * that the fixture is identical under every default locale.
     *
     * @param ordinal the record's ordinal
     * @return the zero-filled sixteen-character identifier
     */
    private static String identifier(final int ordinal) {
        final StringBuilder digits = new StringBuilder(Integer.toString(ordinal));
        // Character counting is correct here and only here: this is building a fixture value out of
        // ASCII digits, not measuring an emitted record. Every width assertion in this class is made
        // on encoded bytes.
        while (digits.length() < TRANSACTION_ID_WIDTH) {
            digits.insert(0, '0');
        }
        return digits.toString();
    }

    /**
     * Builds one posted transaction at the widths the 350-byte record layout declares. Only the
     * seven fields the report reads carry meaningful values.
     *
     * @param ordinal        the record's ordinal, which becomes its identifier
     * @param cardNumber     the sixteen-character card number that drives the account break
     * @param amount         the amount, always written as a scale-two literal
     * @param processingDate the processing date, which the range filter tests
     * @return the transaction
     */
    private static Transaction transaction(final int ordinal, final String cardNumber,
            final String amount, final LocalDate processingDate) {
        return TestDataFactory.transaction()
                .id(identifier(ordinal))
                .typeCode(TYPE_CODE)
                .categoryCode(CATEGORY_CODE)
                .source(SOURCE)
                .amount(new BigDecimal(amount))
                .cardNumber(cardNumber)
                .originalTimestamp(PINNED_ORIGINATION_TIMESTAMP)
                .processingDate(processingDate)
                .build();
    }

    /** A run of records on one card, all of one currency unit, on a date inside the window. */
    private static List<Transaction> unitRecords(final int fromOrdinal, final int toOrdinal,
            final String cardNumber) {
        final List<Transaction> records = new ArrayList<>();
        for (int ordinal = fromOrdinal; ordinal <= toOrdinal; ordinal++) {
            records.add(transaction(ordinal, cardNumber, "1.00", INSIDE_WINDOW_DATE));
        }
        return records;
    }

    /**
     * A record whose processing date falls one day below the inclusive lower bound.
     *
     * @param ordinal the record's ordinal
     * @return a record the range guard must reject
     */
    private static Transaction recordOneDayBeforeTheWindow(final int ordinal) {
        return TestDataFactory.transactionOneDayBefore(WINDOW_START_DATE)
                .id(identifier(ordinal))
                .typeCode(TYPE_CODE)
                .categoryCode(CATEGORY_CODE)
                .source(SOURCE)
                .amount(new BigDecimal("5.00"))
                .cardNumber(CARD_A)
                .originalTimestamp(PINNED_ORIGINATION_TIMESTAMP)
                .build();
    }

    /**
     * A record whose processing date falls one day above the inclusive upper bound.
     *
     * @param ordinal the record's ordinal
     * @return a record the range guard must reject
     */
    private static Transaction recordOneDayAfterTheWindow(final int ordinal) {
        return TestDataFactory.transactionOneDayAfter(WINDOW_END_DATE)
                .id(identifier(ordinal))
                .typeCode(TYPE_CODE)
                .categoryCode(CATEGORY_CODE)
                .source(SOURCE)
                .amount(new BigDecimal("5.00"))
                .cardNumber(CARD_A)
                .originalTimestamp(PINNED_ORIGINATION_TIMESTAMP)
                .build();
    }

    /**
     * The decisive accumulation fixture: eight records on the first card and twenty-six on the
     * second, every one of one currency unit except the last, which is nine.
     *
     * <p>The account break lands at the ninth record, where the line counter stands at twelve, so
     * the two-line account block steps it to fourteen without stepping over a page boundary. Page
     * breaks therefore land where the modulo test puts them, at the fifteenth and twenty-ninth
     * records, and the fixture spans three pages and two accounts. That is what makes the page-total
     * chain and a direct detail feed produce different numbers.
     *
     * @return thirty-four ordered records over two cards
     */
    private static List<Transaction> decisiveGeneration() {
        final List<Transaction> ordered = new ArrayList<>(unitRecords(1, 8, CARD_A));
        for (int ordinal = 9; ordinal <= 34; ordinal++) {
            ordered.add(transaction(ordinal, CARD_B, ordinal == 34 ? "9.00" : "1.00",
                    INSIDE_WINDOW_DATE));
        }
        return List.copyOf(ordered);
    }

    /**
     * The missed-page-break fixture: fifteen records on the first card and nineteen on the second.
     *
     * <p>The account break lands at the sixteenth record, where the line counter stands at nineteen.
     * The two-line account block steps it to twenty-one, stepping <strong>over</strong> the page
     * boundary at twenty, so the modulo test never fires and the break is lost for the whole run.
     *
     * @return thirty-four ordered records over two cards
     */
    private static List<Transaction> missedPageBreakGeneration() {
        final List<Transaction> ordered = new ArrayList<>(unitRecords(1, 15, CARD_A));
        ordered.addAll(unitRecords(16, 34, CARD_B));
        return List.copyOf(ordered);
    }

    /**
     * Creates the frozen, ordered, positional input the batch step hands to the service. The
     * position is zero-based and exhaustion is an empty result, exactly as the interface declares.
     *
     * @param ordered the ordered generation
     * @return a detached positional source over it
     */
    private static ReportTransactionSource frozenSource(final List<Transaction> ordered) {
        final List<Transaction> snapshot = List.copyOf(ordered);
        return position -> {
            if (position < 0) {
                throw new IllegalArgumentException("position must not be negative: " + position);
            }
            return position < snapshot.size()
                    ? Optional.of(snapshot.get(position))
                    : Optional.empty();
        };
    }

    /** Stubs the cross-reference resolution for the first card. */
    private void stubCrossReferenceForCardA() {
        when(cardCrossReferenceRepository.findById(CARD_A))
                .thenReturn(Optional.of(new CardCrossReference(CARD_A, CUSTOMER_A, ACCOUNT_A)));
    }

    /** Stubs the cross-reference resolution for the second card. */
    private void stubCrossReferenceForCardB() {
        when(cardCrossReferenceRepository.findById(CARD_B))
                .thenReturn(Optional.of(new CardCrossReference(CARD_B, CUSTOMER_B, ACCOUNT_B)));
    }

    /**
     * Stubs the type and category resolutions. The category key is stubbed as an equal identifier
     * instance rather than through a wildcard, so that the stub itself asserts the key shape.
     */
    private void stubTypeAndCategory() {
        when(transactionTypeRepository.findById(TYPE_CODE))
                .thenReturn(Optional.of(new TransactionType(TYPE_CODE, TYPE_DESCRIPTION)));
        when(transactionCategoryRepository.findById(new TransactionCategoryId(TYPE_CODE,
                CATEGORY_CODE)))
                .thenReturn(Optional.of(new TransactionCategory(TYPE_CODE, CATEGORY_CODE,
                        CATEGORY_DESCRIPTION)));
    }

    /** Makes the mocked abend path terminate the run, as the real batch abend does. */
    private void stubAbendTerminatesTheRun() {
        doThrow(new AbendException(AbendException.BATCH_ABEND_CODE, PROGRAM_NAME,
                "FILE STATUS " + STATUS_RECORD_NOT_FOUND, AbendException.DEFAULT_MESSAGE))
                .when(abendService).abendBatch(any(), any(), any(), any(), any());
    }

    /** Runs the report over the supplied generation for this suite's window. */
    private Report run(final List<Transaction> ordered) {
        return run(ordered, WINDOW_START, WINDOW_END);
    }

    /** Runs the report over the supplied generation for an explicit pair of bounds. */
    private Report run(final List<Transaction> ordered, final String startDate,
            final String endDate) {
        final List<String> collected = new ArrayList<>();
        final TransactionReportService.TransactionReportResult result = reportService
                .generateReport(frozenSource(ordered), collected::add, startDate, endDate);
        assertThat(result.reportRecordCount())
                .as("the run's own record count must agree with what its sink received")
                .isEqualTo(collected.size());
        return new Report(List.copyOf(collected), result);
    }

    /** The emitted records of one kind, in emission order. */
    private static List<String> linesOfKind(final Report report, final LineKind kind) {
        return report.lines().stream().filter(line -> kindOf(line) == kind).toList();
    }

    /**
     * Counts the detail rows between successive header blocks, which is the report's page geometry.
     *
     * @param report the generated report
     * @return the detail-row count of each page, in page order
     */
    private static List<Integer> detailRowsPerPage(final Report report) {
        final List<Integer> perPage = new ArrayList<>();
        boolean started = false;
        int current = 0;
        for (final String line : report.lines()) {
            final LineKind kind = kindOf(line);
            if (kind == LineKind.NAME_HEADER) {
                if (started) {
                    perPage.add(current);
                }
                started = true;
                current = 0;
            } else if (started && kind == LineKind.DETAIL) {
                current++;
            }
        }
        if (started) {
            perPage.add(current);
        }
        return List.copyOf(perPage);
    }

    /** The formatted diagnostics the service emitted, in emission order. */
    private List<String> loggedMessages() {
        return logCapture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Runs the report expecting the terminal abend path, and returns the abort that was raised.
     *
     * @param ordered the ordered generation to report over
     * @return the abort the run raised
     */
    private AbendException catchAbend(final List<Transaction> ordered) {
        return assertThatExceptionOfType(AbendException.class)
                .isThrownBy(() -> reportService.generateReport(frozenSource(ordered), line -> { },
                        WINDOW_START, WINDOW_END))
                .actual();
    }

    /**
     * Asserts the abend context the terminal path carries: the batch abend code, the program name as
     * the culprit, and the four fixed-width fields at their declared offsets.
     *
     * @param raised the abort the run raised
     */
    private static void assertAbendContext(final AbendException raised) {
        final String context = raised.toFixedWidthContext();
        assertAll(
                () -> assertThat(raised.code())
                        .as("the batch abend code constant, not the online one")
                        .isEqualTo(AbendException.BATCH_ABEND_CODE),
                () -> assertThat(raised.culprit())
                        .as("the culprit is the legacy program name")
                        .isEqualTo(PROGRAM_NAME),
                () -> assertThat(raised.getMessage()).isEqualTo(AbendException.DEFAULT_MESSAGE),
                () -> assertThat(encodedWidthOf(context))
                        .isEqualTo(AbendException.CONTEXT_LENGTH),
                () -> assertThat(context.substring(0, AbendException.CODE_LENGTH).strip())
                        .isEqualTo(AbendException.BATCH_ABEND_CODE),
                () -> assertThat(context.substring(AbendException.CODE_LENGTH,
                        AbendException.CODE_LENGTH + AbendException.CULPRIT_LENGTH))
                        .isEqualTo(PROGRAM_NAME));
    }

    // =============================================================================================
    // Paragraph unit 1, and the twelve open and close units it sequences.
    // =============================================================================================

    @Nested
    @DisplayName("the driving body: six opens, the parameter read, the loop, six closes")
    class TheDrivingBody {

        @Test
        @DisplayName("both public entry points produce the identical report for identical bounds")
        void bothEntryPointsAgree() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();
            final List<Transaction> ordered =
                    List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE));

            final Report byBounds = run(ordered);

            final List<String> byCard = new ArrayList<>();
            final TransactionReportService.TransactionReportResult cardResult = reportService
                    .generateReportFromDateParameterCard(frozenSource(ordered), byCard::add,
                            WINDOW_START + " " + WINDOW_END);

            assertAll(
                    () -> assertThat(byCard).isEqualTo(byBounds.lines()),
                    () -> assertThat(cardResult.grandTotal())
                            .isEqualTo(byBounds.result().grandTotal()),
                    () -> assertThat(cardResult.lineCount())
                            .isEqualTo(byBounds.result().lineCount()),
                    () -> assertThat(cardResult.pageCount())
                            .isEqualTo(byBounds.result().pageCount()),
                    () -> assertThat(cardResult.accountBreakCount())
                            .isEqualTo(byBounds.result().accountBreakCount()));
        }

        @Test
        @DisplayName("the announcements that bracket the sequence are emitted once each, in order")
        void executionIsAnnouncedOnceAtEachEnd() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            run(List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            final List<String> messages = loggedMessages();
            assertAll(
                    () -> assertThat(messages)
                            .filteredOn(message -> message.contains("START OF EXECUTION OF PROGRAM "
                                    + PROGRAM_NAME))
                            .hasSize(1),
                    () -> assertThat(messages)
                            .filteredOn(message -> message.contains("END OF EXECUTION OF PROGRAM "
                                    + PROGRAM_NAME))
                            .hasSize(1),
                    () -> assertThat(messages.indexOf("START OF EXECUTION OF PROGRAM "
                            + PROGRAM_NAME))
                            .isLessThan(messages.indexOf("END OF EXECUTION OF PROGRAM "
                                    + PROGRAM_NAME)));
        }

        @Test
        @DisplayName("a clean run never reaches the abend path, so no diagnostic and no abort occur")
        void aCleanRunNeverAbends() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            run(List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("every collaborator is required at construction, so no half-built instance exists")
        void everyCollaboratorIsRequired() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new TransactionReportService(null,
                                    transactionTypeRepository, transactionCategoryRepository,
                                    abendService)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new TransactionReportService(
                                    cardCrossReferenceRepository, null,
                                    transactionCategoryRepository, abendService)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new TransactionReportService(
                                    cardCrossReferenceRepository, transactionTypeRepository, null,
                                    abendService)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new TransactionReportService(
                                    cardCrossReferenceRepository, transactionTypeRepository,
                                    transactionCategoryRepository, null)));
        }

        @Test
        @DisplayName("two successive runs on one instance are independent, so no state leaks between "
                + "them")
        void successiveRunsAreIndependent() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();
            final List<Transaction> ordered =
                    List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE));

            final Report first = run(ordered);
            final Report second = run(ordered);

            assertAll(
                    () -> assertThat(second.lines()).isEqualTo(first.lines()),
                    () -> assertThat(second.result().grandTotal())
                            .isEqualTo(first.result().grandTotal()),
                    () -> assertThat(second.result().lineCount())
                            .isEqualTo(first.result().lineCount()));
        }
    }

    // =============================================================================================
    // Paragraph unit 10, and the width contract every group shares.
    // =============================================================================================

    @Nested
    @DisplayName("the record width contract: every emitted record is exactly 133 encoded bytes")
    class TheRecordWidthContract {

        @Test
        @DisplayName("every record of a multi-page multi-account report measures 133 encoded bytes, "
                + "the blank record and the hyphen rule included")
        void everyRecordMeasuresTheContractWidthInEncodedBytes() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final Report report = run(decisiveGeneration());

            assertThat(report.lines()).isNotEmpty().allSatisfy(line -> assertThat(
                    encodedWidthOf(line))
                    .as("every record is a fixed-width record measured in encoded bytes")
                    .isEqualTo(RECORD_WIDTH));
            assertAll(
                    () -> assertThat(report.lines())
                            .as("the blank record of the header block is a full-width record")
                            .contains(BLANK_LINE),
                    () -> assertThat(report.lines())
                            .as("the hyphen rule is a full-width record")
                            .contains(RULE_LINE),
                    () -> assertThat(encodedWidthOf(BLANK_LINE)).isEqualTo(RECORD_WIDTH),
                    () -> assertThat(encodedWidthOf(RULE_LINE)).isEqualTo(RECORD_WIDTH));
        }

        @Test
        @DisplayName("each of the seven groups matches its declared literal byte for byte, untrimmed")
        void eachGroupMatchesItsDeclaredLiteral() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final Report report = run(List.of(
                    transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_B, "1.00", INSIDE_WINDOW_DATE)));

            final String expectedDetailForCardA = identifier(1) + " " + ACCOUNT_A + " "
                    + TYPE_CODE + "-" + TYPE_DESCRIPTION + " ".repeat(7) + " "
                    + CATEGORY_CODE + "-" + CATEGORY_DESCRIPTION + " ".repeat(19) + " "
                    + SOURCE + " ".repeat(2) + " ".repeat(4)
                    + DETAIL_MASK_ONE_UNIT + " ".repeat(2) + " ".repeat(19);

            assertAll(
                    () -> assertThat(report.lines().get(0)).isEqualTo(NAME_HEADER_LINE),
                    () -> assertThat(report.lines().get(1)).isEqualTo(BLANK_LINE),
                    () -> assertThat(report.lines().get(2)).isEqualTo(COLUMN_HEADER_LINE),
                    () -> assertThat(report.lines().get(3)).isEqualTo(RULE_LINE),
                    () -> assertThat(report.lines().get(4)).isEqualTo(expectedDetailForCardA),
                    () -> assertThat(report.lines().get(5)).isEqualTo(
                            ACCOUNT_TOTAL_PREFIX + TOTAL_MASK_ONE_UNIT + TOTAL_LINE_SUFFIX),
                    () -> assertThat(report.lines().get(6)).isEqualTo(RULE_LINE),
                    () -> assertThat(report.lines().get(8)).isEqualTo(PAGE_TOTAL_PREFIX
                            + "+" + " ".repeat(10) + "3.00" + TOTAL_LINE_SUFFIX),
                    () -> assertThat(report.lines().get(10)).isEqualTo(GRAND_TOTAL_PREFIX
                            + "+" + " ".repeat(10) + "3.00" + TOTAL_LINE_SUFFIX));
        }

        @Test
        @DisplayName("the detail line carries the account identifier the cross-reference resolved, "
                + "at its declared offset and width")
        void theDetailLineCarriesTheResolvedAccountIdentifier() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            final Report report = run(List.of(
                    transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            assertThat(linesOfKind(report, LineKind.DETAIL))
                    .hasSize(2)
                    .allSatisfy(line -> assertThat(line.substring(DETAIL_ACCOUNT_ID_OFFSET,
                            DETAIL_ACCOUNT_ID_OFFSET + DETAIL_ACCOUNT_ID_WIDTH))
                            .isEqualTo(ACCOUNT_A));
        }
    }

    // =============================================================================================
    // Paragraph unit 8.
    // =============================================================================================

    @Nested
    @DisplayName("the header block: exactly four records, in the one legal order")
    class TheHeaderBlock {

        @Test
        @DisplayName("the block is exactly four records and every one measures 133 encoded bytes")
        void theBlockIsExactlyFourRecords() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            final Report report = run(List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            final List<String> block = report.lines().subList(0, HEADER_BLOCK_LINES);
            assertAll(
                    () -> assertThat(block).hasSize(HEADER_BLOCK_LINES),
                    () -> assertThat(block).containsExactly(NAME_HEADER_LINE, BLANK_LINE,
                            COLUMN_HEADER_LINE, RULE_LINE),
                    () -> assertThat(block).allSatisfy(line -> assertThat(encodedWidthOf(line))
                            .isEqualTo(RECORD_WIDTH)),
                    () -> assertThat(linesOfKind(report, LineKind.NAME_HEADER)).hasSize(1),
                    () -> assertThat(linesOfKind(report, LineKind.BLANK)).hasSize(1),
                    () -> assertThat(linesOfKind(report, LineKind.COLUMN_HEADER)).hasSize(1));
        }

        @Test
        @DisplayName("a repeated block is byte-identical, because both bounds are fixed for the run")
        void aRepeatedBlockIsByteIdentical() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            final Report report = run(unitRecords(1, 44, CARD_A));

            assertThat(linesOfKind(report, LineKind.NAME_HEADER))
                    .hasSize(3)
                    .allSatisfy(line -> assertThat(line).isEqualTo(NAME_HEADER_LINE));
            assertThat(linesOfKind(report, LineKind.COLUMN_HEADER))
                    .hasSize(3)
                    .allSatisfy(line -> assertThat(line).isEqualTo(COLUMN_HEADER_LINE));
        }
    }

    // =============================================================================================
    // Paragraph unit 4, and the per-caller increments of units 5, 6, 7, 8 and 10.
    // =============================================================================================

    @Nested
    @DisplayName("the pagination arithmetic: a modulo test over every written line, with per-caller "
            + "increments of four, two, two, one and zero")
    class ThePaginationArithmetic {

        @Test
        @DisplayName("the first page holds exactly sixteen detail rows and a steady-state page holds "
                + "exactly fourteen, because the counter counts header and total lines too")
        void firstPageHoldsSixteenRowsAndASteadyStatePageHoldsFourteen() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            final Report report = run(unitRecords(1, 44, CARD_A));

            final List<Integer> perPage = detailRowsPerPage(report);
            assertAll(
                    () -> assertThat(perPage).hasSize(3),
                    () -> assertThat(perPage.get(0))
                            .as("four header records consume four of the twenty counted lines")
                            .isEqualTo(FIRST_PAGE_DETAIL_ROWS),
                    () -> assertThat(perPage.get(1))
                            .as("four header and two page-total records consume six")
                            .isEqualTo(STEADY_STATE_DETAIL_ROWS),
                    () -> assertThat(perPage.get(2)).isEqualTo(STEADY_STATE_DETAIL_ROWS),
                    () -> assertThat(linesOfKind(report, LineKind.DETAIL)).hasSize(44));
        }

        @Test
        @DisplayName("the break fires when the counter reaches a multiple of twenty, counted over all "
                + "written lines rather than over detail lines alone")
        void theBreakFiresOnAMultipleOfThePageSize() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            final Report report = run(unitRecords(1, 44, CARD_A));

            // The first page-total record is preceded by exactly one page's worth of counted lines,
            // and that count is a multiple of the page size. Were the counter to count detail lines
            // only, the first break would land after the twentieth detail row instead of the
            // sixteenth.
            final int recordsBeforeFirstPageTotal = report.lines().indexOf(
                    linesOfKind(report, LineKind.PAGE_TOTAL).get(0));
            assertAll(
                    () -> assertThat(recordsBeforeFirstPageTotal)
                            .isEqualTo(HEADER_BLOCK_LINES + FIRST_PAGE_DETAIL_ROWS),
                    () -> assertThat(recordsBeforeFirstPageTotal % PAGE_SIZE).isZero(),
                    () -> assertThat(report.result().pageCount()).isEqualTo(3));
        }

        @Test
        @DisplayName("the header block advances the counter by four and the detail line by one")
        void theHeaderBlockAdvancesByFourAndADetailLineByOne() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            final Report single = run(List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));
            final Report pair = run(List.of(
                    transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            assertAll(
                    // Four header records, one detail record and the two records of the at-end page
                    // total, of which all but the grand total advance the counter.
                    () -> assertThat(single.result().lineCount()).isEqualTo(7L),
                    () -> assertThat(single.result().reportRecordCount()).isEqualTo(8L),
                    () -> assertThat(pair.result().lineCount() - single.result().lineCount())
                            .as("one extra detail record advances the counter by exactly one")
                            .isEqualTo(1L),
                    () -> assertThat(pair.result().reportRecordCount()
                            - single.result().reportRecordCount()).isEqualTo(1L));
        }

        @Test
        @DisplayName("the page-total block advances the counter by two and the grand total by zero")
        void thePageTotalBlockAdvancesByTwoAndTheGrandTotalByZero() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            final Report report = run(List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            // Eight records were written and the counter stands at seven, so exactly one written
            // record advanced it by nothing - the grand total, which is the last record of the
            // report and the only write site the source leaves un-incremented. The value it carries
            // is two units rather than one because the at-end path re-adds the single record's stale
            // amount; that is the third reproduced defect, pinned in its own group.
            assertAll(
                    () -> assertThat(linesOfKind(report, LineKind.PAGE_TOTAL)).hasSize(1),
                    () -> assertThat(linesOfKind(report, LineKind.GRAND_TOTAL)).hasSize(1),
                    () -> assertThat(report.result().reportRecordCount()
                            - report.result().lineCount())
                            .as("the grand total is the only record that advances nothing")
                            .isEqualTo(1L),
                    () -> assertThat(report.lines().get(report.lines().size() - 1))
                            .isEqualTo(GRAND_TOTAL_PREFIX + TOTAL_MASK_TWO_UNITS
                                    + TOTAL_LINE_SUFFIX),
                    () -> assertThat(report.lines().get(report.lines().size() - 3))
                            .as("the page total precedes its rule line, and both advance the counter")
                            .isEqualTo(PAGE_TOTAL_PREFIX + TOTAL_MASK_TWO_UNITS
                                    + TOTAL_LINE_SUFFIX),
                    () -> assertThat(report.lines().get(report.lines().size() - 2))
                            .isEqualTo(RULE_LINE));
        }

        @Test
        @DisplayName("the account-total block advances the counter by two, which two records on two "
                + "cards prove against two records on one card")
        void theAccountTotalBlockAdvancesByTwo() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final Report oneCard = run(List.of(
                    transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_A, "1.00", INSIDE_WINDOW_DATE)));
            final Report twoCards = run(List.of(
                    transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_B, "1.00", INSIDE_WINDOW_DATE)));

            assertAll(
                    () -> assertThat(oneCard.result().accountBreakCount()).isZero(),
                    () -> assertThat(twoCards.result().accountBreakCount()).isEqualTo(1),
                    () -> assertThat(twoCards.result().lineCount() - oneCard.result().lineCount())
                            .as("the account-total block advances the counter by exactly two")
                            .isEqualTo(2L),
                    () -> assertThat(twoCards.result().reportRecordCount()
                            - oneCard.result().reportRecordCount())
                            .as("and writes exactly two records: the total and its rule line")
                            .isEqualTo(2L),
                    () -> assertThat(linesOfKind(twoCards, LineKind.ACCOUNT_TOTAL)).hasSize(1));
        }
    }

    // =============================================================================================
    // Paragraph unit 9: the single writer.
    // =============================================================================================

    @Nested
    @DisplayName("the single writer: every record passes through it, and it increments nothing")
    class TheSingleWriter {

        @Test
        @DisplayName("the writer is reached exactly as many times as the run reports records, so no "
                + "record reaches the report by any other route")
        void everyRecordPassesThroughTheOneWriter() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final TransactionReportService.TransactionReportResult result = reportService
                    .generateReport(frozenSource(decisiveGeneration()), reportRecordSink,
                            WINDOW_START, WINDOW_END);

            final ArgumentCaptor<String> emitted = ArgumentCaptor.forClass(String.class);
            verify(reportRecordSink, times((int) result.reportRecordCount()))
                    .accept(emitted.capture());
            verifyNoMoreInteractions(reportRecordSink);

            assertAll(
                    () -> assertThat(emitted.getAllValues())
                            .hasSize((int) result.reportRecordCount()),
                    () -> assertThat(emitted.getAllValues()).allSatisfy(line -> assertThat(
                            encodedWidthOf(line)).isEqualTo(RECORD_WIDTH)),
                    () -> assertThat(result.reportRecordCount()).isEqualTo(55L));
        }

        @Test
        @DisplayName("the writer itself advances no counter: the record count always exceeds the "
                + "line count by exactly one, whatever the report's shape")
        void theWriterAdvancesNoCounter() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final Report small = run(List.of(
                    transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_B, "1.00", INSIDE_WINDOW_DATE)));
            final Report large = run(decisiveGeneration());

            assertAll(
                    () -> assertThat(small.result().reportRecordCount())
                            .isEqualTo(small.result().lineCount() + 1L),
                    () -> assertThat(large.result().reportRecordCount())
                            .isEqualTo(large.result().lineCount() + 1L),
                    () -> assertThat(small.result().reportRecordCount()).isEqualTo(11L),
                    () -> assertThat(small.result().lineCount()).isEqualTo(10L),
                    () -> assertThat(large.result().reportRecordCount()).isEqualTo(55L),
                    () -> assertThat(large.result().lineCount()).isEqualTo(54L));
        }
    }

    // =============================================================================================
    // Paragraph units 5, 6 and 7: the accumulation chain.
    // =============================================================================================

    @Nested
    @DisplayName("the accumulation chain: amounts reach the grand total only through page totals - a "
            + "direct detail-to-grand-total feed fails every test in this group")
    class TheAccumulationChain {

        @Test
        @DisplayName("over three pages and two accounts the grand total is the sum of the page totals "
                + "and NOT the direct sum of the details - a direct grand-total feed fails this test")
        void theGrandTotalIsTheSumOfThePageTotalsAndNotOfTheDetails() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final Report report = run(decisiveGeneration());

            // Hand-computed candidates. The fixture holds thirty-four details, thirty-three of one
            // currency unit and one of nine, so a DIRECT detail-to-grand-total feed would read 42.00.
            // The faithful chain folds three page totals of 14.00, 14.00 and 23.00 - the last of
            // which carries the at-end re-add of the final nine - and reads 51.00. Rolling the single
            // emitted account total of 8.00 into the grand total as well would read 59.00.
            final BigDecimal pageChainGrandTotal = new BigDecimal("51.00");
            final BigDecimal directDetailSum = new BigDecimal("42.00");
            final BigDecimal withAccountTotalsRolledUp = new BigDecimal("59.00");

            final BigDecimal foldedFromPageTotals = linesOfKind(report, LineKind.PAGE_TOTAL).stream()
                    .map(TransactionReportServiceTest::amountOn)
                    .reduce(ZERO_AT_MONETARY_SCALE, BigDecimal::add);
            final BigDecimal summedFromDetails = linesOfKind(report, LineKind.DETAIL).stream()
                    .map(TransactionReportServiceTest::amountOn)
                    .reduce(ZERO_AT_MONETARY_SCALE, BigDecimal::add);

            assertAll(
                    () -> assertThat(report.result().grandTotal())
                            .as("the grand total is fed only from page totals")
                            .isEqualByComparingTo(pageChainGrandTotal),
                    () -> assertThat(report.result().grandTotal())
                            .as("a direct detail-to-grand-total feed would read a different number")
                            .isNotEqualByComparingTo(directDetailSum),
                    () -> assertThat(report.result().grandTotal())
                            .as("account totals must not be rolled up")
                            .isNotEqualByComparingTo(withAccountTotalsRolledUp),
                    () -> assertThat(foldedFromPageTotals)
                            .isEqualByComparingTo(pageChainGrandTotal),
                    () -> assertThat(summedFromDetails).isEqualByComparingTo(directDetailSum),
                    () -> assertThat(report.result().grandTotal().scale())
                            .as("every monetary value stays at the two-decimal scale")
                            .isEqualTo(2));
        }

        @Test
        @DisplayName("the three page totals carry their own hand-computed values in emission order")
        void thePageTotalsCarryTheirOwnValues() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final Report report = run(decisiveGeneration());

            assertThat(linesOfKind(report, LineKind.PAGE_TOTAL))
                    .hasSize(3)
                    .containsExactly(
                            PAGE_TOTAL_PREFIX + "+" + " ".repeat(9) + "14.00" + TOTAL_LINE_SUFFIX,
                            PAGE_TOTAL_PREFIX + "+" + " ".repeat(9) + "14.00" + TOTAL_LINE_SUFFIX,
                            PAGE_TOTAL_PREFIX + "+" + " ".repeat(9) + "23.00" + TOTAL_LINE_SUFFIX);
            assertThat(report.result().pageCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("the emitted account total is the first card's own subtotal and contributes "
                + "nothing to the grand total")
        void theAccountTotalContributesNothingToTheGrandTotal() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final Report report = run(decisiveGeneration());

            assertAll(
                    () -> assertThat(report.result().accountBreakCount()).isEqualTo(1),
                    () -> assertThat(linesOfKind(report, LineKind.ACCOUNT_TOTAL))
                            .containsExactly(ACCOUNT_TOTAL_PREFIX + "+" + " ".repeat(10) + "8.00"
                                    + TOTAL_LINE_SUFFIX),
                    () -> assertThat(report.result().grandTotal())
                            .isEqualByComparingTo(new BigDecimal("51.00")));
        }

        @Test
        @DisplayName("an account break resets the account total, and the grand total does not move "
                + "until the next page total is emitted")
        void anAccountBreakResetsTheAccountTotalAndLeavesTheGrandTotalAlone() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            // Two accounts on one page: the first card contributes 10.00 and the second 4.00. The
            // only account total emitted is the first card's 10.00; the second card's subtotal is
            // never flushed because no third card follows.
            final Report report = run(List.of(
                    transaction(1, CARD_A, "6.00", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_A, "4.00", INSIDE_WINDOW_DATE),
                    transaction(3, CARD_B, "1.00", INSIDE_WINDOW_DATE),
                    transaction(4, CARD_B, "3.00", INSIDE_WINDOW_DATE)));

            final int accountTotalIndex = report.lines().indexOf(
                    linesOfKind(report, LineKind.ACCOUNT_TOTAL).get(0));
            final int pageTotalIndex = report.lines().indexOf(
                    linesOfKind(report, LineKind.PAGE_TOTAL).get(0));

            assertAll(
                    () -> assertThat(linesOfKind(report, LineKind.ACCOUNT_TOTAL))
                            .as("the reset means the second card's rows never join the first's total")
                            .containsExactly(ACCOUNT_TOTAL_PREFIX + "+" + " ".repeat(9) + "10.00"
                                    + TOTAL_LINE_SUFFIX),
                    () -> assertThat(accountTotalIndex).isLessThan(pageTotalIndex),
                    // 6.00 + 4.00 + 1.00 + 3.00 = 14.00, and the at-end re-add of the final 3.00
                    // gives 17.00. The grand total moved only when the page total was emitted, and
                    // the account break contributed none of its 10.00.
                    () -> assertThat(amountOn(report.lines().get(pageTotalIndex)))
                            .isEqualByComparingTo(new BigDecimal("17.00")),
                    () -> assertThat(report.result().grandTotal())
                            .isEqualByComparingTo(new BigDecimal("17.00")),
                    () -> assertThat(report.result().grandTotal())
                            .as("had the account break fed the grand total it would read 27.00")
                            .isNotEqualByComparingTo(new BigDecimal("27.00")));
        }
    }

    // =============================================================================================
    // The two fifteen-character masks, which are deliberately not unified.
    // =============================================================================================

    @Nested
    @DisplayName("the two amount masks: the detail form and the total forms render the same value "
            + "differently")
    class TheTwoAmountMasks {

        @Test
        @DisplayName("one positive value renders differently in a detail line and in a total line, "
                + "both at fifteen characters")
        void onePositiveValueRendersDifferentlyUnderEachMask() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            // The first card's single record is 1234.56, so the account total the break emits
            // carries exactly the value the detail line above it carries.
            final Report report = run(List.of(
                    transaction(1, CARD_A, "1234.56", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_B, "2.00", INSIDE_WINDOW_DATE)));

            final String detailRendering = maskOn(linesOfKind(report, LineKind.DETAIL).get(0));
            final String totalRendering = maskOn(linesOfKind(report,
                    LineKind.ACCOUNT_TOTAL).get(0));

            assertAll(
                    () -> assertThat(detailRendering).isEqualTo(DETAIL_MASK_GROUPED_POSITIVE),
                    () -> assertThat(totalRendering).isEqualTo(TOTAL_MASK_GROUPED_POSITIVE),
                    () -> assertThat(detailRendering)
                            .as("the detail mask never carries a plus where the total mask does")
                            .isNotEqualTo(totalRendering),
                    () -> assertThat(encodedWidthOf(detailRendering)).isEqualTo(AMOUNT_MASK_WIDTH),
                    () -> assertThat(encodedWidthOf(totalRendering)).isEqualTo(AMOUNT_MASK_WIDTH),
                    () -> assertThat(amountOn(linesOfKind(report, LineKind.DETAIL).get(0)))
                            .isEqualByComparingTo(new BigDecimal("1234.56")),
                    () -> assertThat(amountOn(linesOfKind(report, LineKind.ACCOUNT_TOTAL).get(0)))
                            .isEqualByComparingTo(new BigDecimal("1234.56")));
        }

        @Test
        @DisplayName("a negative value renders identically under both masks, because both carry the "
                + "minus in the leftmost position")
        void aNegativeValueRendersIdenticallyUnderBothMasks() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final Report report = run(List.of(
                    transaction(1, CARD_A, "-1234.56", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_B, "2.00", INSIDE_WINDOW_DATE)));

            final String detailRendering = maskOn(linesOfKind(report, LineKind.DETAIL).get(0));
            final String totalRendering = maskOn(linesOfKind(report,
                    LineKind.ACCOUNT_TOTAL).get(0));

            assertAll(
                    () -> assertThat(detailRendering).isEqualTo(DETAIL_MASK_GROUPED_NEGATIVE),
                    () -> assertThat(totalRendering).isEqualTo(TOTAL_MASK_GROUPED_NEGATIVE),
                    () -> assertThat(detailRendering).isEqualTo(totalRendering),
                    () -> assertThat(encodedWidthOf(detailRendering)).isEqualTo(AMOUNT_MASK_WIDTH),
                    () -> assertThat(encodedWidthOf(totalRendering)).isEqualTo(AMOUNT_MASK_WIDTH),
                    () -> assertThat(amountOn(linesOfKind(report, LineKind.DETAIL).get(0)))
                            .isEqualByComparingTo(new BigDecimal("-1234.56")));
        }

        @Test
        @DisplayName("a value of zero blanks all fifteen positions under both masks")
        void zeroBlanksTheWholeFieldUnderBothMasks() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final Report report = run(List.of(
                    transaction(1, CARD_A, "0.00", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_B, "0.00", INSIDE_WINDOW_DATE)));

            assertAll(
                    () -> assertThat(maskOn(linesOfKind(report, LineKind.DETAIL).get(0)))
                            .isEqualTo(BLANK_AMOUNT_MASK),
                    () -> assertThat(maskOn(linesOfKind(report, LineKind.ACCOUNT_TOTAL).get(0)))
                            .isEqualTo(BLANK_AMOUNT_MASK),
                    () -> assertThat(maskOn(linesOfKind(report, LineKind.PAGE_TOTAL).get(0)))
                            .isEqualTo(BLANK_AMOUNT_MASK),
                    () -> assertThat(maskOn(linesOfKind(report, LineKind.GRAND_TOTAL).get(0)))
                            .isEqualTo(BLANK_AMOUNT_MASK),
                    () -> assertThat(report.result().grandTotal())
                            .isEqualByComparingTo(ZERO_AT_MONETARY_SCALE),
                    () -> assertThat(report.result().grandTotal().scale()).isEqualTo(2),
                    () -> assertThat(report.lines()).allSatisfy(line -> assertThat(
                            encodedWidthOf(line)).isEqualTo(RECORD_WIDTH)));
        }

        @Test
        @DisplayName("all three total groups use the always-signed mask at the one shared offset")
        void allThreeTotalGroupsUseTheSignedMask() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final Report report = run(List.of(
                    transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_B, "1.00", INSIDE_WINDOW_DATE)));

            assertAll(
                    () -> assertThat(maskOn(linesOfKind(report, LineKind.ACCOUNT_TOTAL).get(0)))
                            .isEqualTo(TOTAL_MASK_ONE_UNIT),
                    () -> assertThat(maskOn(linesOfKind(report, LineKind.PAGE_TOTAL).get(0)))
                            .startsWith("+"),
                    () -> assertThat(maskOn(linesOfKind(report, LineKind.GRAND_TOTAL).get(0)))
                            .startsWith("+"),
                    () -> assertThat(maskOn(linesOfKind(report, LineKind.DETAIL).get(0)))
                            .as("the detail mask is zero-suppressed and never carries a plus")
                            .isEqualTo(DETAIL_MASK_ONE_UNIT)
                            .doesNotContain("+"),
                    () -> assertThat(maskOn(linesOfKind(report, LineKind.DETAIL).get(1)))
                            .isEqualTo(DETAIL_MASK_ONE_UNIT));
        }
    }

    // =============================================================================================
    // Paragraph unit 2, and the inclusive range guard the driving body applies.
    // =============================================================================================

    @Nested
    @DisplayName("the date bounds: inclusive at both ends, and ten characters wherever they travel")
    class TheInclusiveDateBounds {

        @Test
        @DisplayName("a record processed on the lower bound and a record processed on the upper bound "
                + "are both reported, so both bounds are inclusive")
        void bothBoundsAreInclusiveAtTheExactBoundaryValues() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            final Report report = run(List.of(
                    transaction(1, CARD_A, "1.00", WINDOW_START_DATE),
                    transaction(2, CARD_A, "2.00", WINDOW_END_DATE)));

            assertAll(
                    () -> assertThat(linesOfKind(report, LineKind.DETAIL))
                            .as("neither boundary record is filtered out")
                            .hasSize(2),
                    () -> assertThat(maskOn(linesOfKind(report, LineKind.DETAIL).get(0)))
                            .isEqualTo(DETAIL_MASK_ONE_UNIT),
                    () -> assertThat(maskOn(linesOfKind(report, LineKind.DETAIL).get(1)))
                            .isEqualTo(DETAIL_MASK_TWO_UNITS),
                    // 1.00 + 2.00 with the at-end re-add of the final 2.00 gives 5.00.
                    () -> assertThat(report.result().grandTotal())
                            .isEqualByComparingTo(new BigDecimal("5.00")));
        }

        @Test
        @DisplayName("a record one day before the lower bound is excluded from the report")
        void aRecordOneDayBeforeTheLowerBoundIsExcluded() {
            final Report report = run(List.of(recordOneDayBeforeTheWindow(1)));

            assertThat(linesOfKind(report, LineKind.DETAIL))
                    .as("the range guard admits nothing below the lower bound")
                    .isEmpty();
        }

        @Test
        @DisplayName("a record one day after the upper bound is excluded from the report")
        void aRecordOneDayAfterTheUpperBoundIsExcluded() {
            final Report report = run(List.of(recordOneDayAfterTheWindow(1)));

            assertThat(linesOfKind(report, LineKind.DETAIL))
                    .as("the range guard admits nothing above the upper bound")
                    .isEmpty();
        }

        @Test
        @DisplayName("both bounds travel through the run as exactly ten encoded bytes, untrimmed, and "
                + "reach the emitted header at their declared offsets")
        void bothBoundsRemainTenEncodedBytesUntrimmed() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            final TransactionReportService.TransactionReportResult result = reportService
                    .generateReport(frozenSource(List.of(transaction(1, CARD_A, "1.00",
                            INSIDE_WINDOW_DATE))), reportRecordSink, WINDOW_START, WINDOW_END);

            final ArgumentCaptor<String> emitted = ArgumentCaptor.forClass(String.class);
            verify(reportRecordSink, times((int) result.reportRecordCount()))
                    .accept(emitted.capture());

            final String nameHeader = emitted.getAllValues().get(0);
            final String capturedLowerBound = nameHeader.substring(HEADER_START_DATE_OFFSET,
                    HEADER_START_DATE_OFFSET + DATE_WIDTH);
            final String capturedUpperBound = nameHeader.substring(HEADER_END_DATE_OFFSET,
                    HEADER_END_DATE_OFFSET + DATE_WIDTH);

            assertAll(
                    () -> assertThat(capturedLowerBound).isEqualTo(WINDOW_START),
                    () -> assertThat(capturedUpperBound).isEqualTo(WINDOW_END),
                    () -> assertThat(encodedWidthOf(capturedLowerBound)).isEqualTo(DATE_WIDTH),
                    () -> assertThat(encodedWidthOf(capturedUpperBound)).isEqualTo(DATE_WIDTH),
                    () -> assertThat(nameHeader.substring(HEADER_START_DATE_OFFSET + DATE_WIDTH,
                            HEADER_END_DATE_OFFSET))
                            .as("the literal that separates the two bounds is untouched")
                            .isEqualTo(" to "),
                    () -> assertThat(nameHeader).isEqualTo(NAME_HEADER_LINE));
        }

        @Test
        @DisplayName("the twenty-one byte structured record and the eighty byte card image are both "
                + "accepted, and produce the identical report")
        void bothLegalParameterWidthsAreAccepted() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();
            final List<Transaction> ordered =
                    List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE));
            final String structured = WINDOW_START + " " + WINDOW_END;
            final String cardImage = structured + " ".repeat(59);

            final List<String> fromStructured = new ArrayList<>();
            reportService.generateReportFromDateParameterCard(frozenSource(ordered),
                    fromStructured::add, structured);
            final List<String> fromCardImage = new ArrayList<>();
            reportService.generateReportFromDateParameterCard(frozenSource(ordered),
                    fromCardImage::add, cardImage);

            assertAll(
                    () -> assertThat(encodedWidthOf(structured)).isEqualTo(21),
                    () -> assertThat(encodedWidthOf(cardImage)).isEqualTo(80),
                    () -> assertThat(fromCardImage).isEqualTo(fromStructured),
                    () -> assertThat(fromStructured.get(0)).isEqualTo(NAME_HEADER_LINE));
        }

        @Test
        @DisplayName("a parameter record of an illegal width is refused rather than silently read")
        void anIllegalParameterWidthIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> reportService.generateReportFromDateParameterCard(
                            frozenSource(List.of()), line -> { }, WINDOW_START));
        }
    }

    // =============================================================================================
    // Paragraph unit 3: the sequential read over the frozen ordered generation.
    // =============================================================================================

    @Nested
    @DisplayName("the frozen ordered input: read once at each position, never re-sorted, never "
            + "re-queried")
    class TheFrozenOrderedInput {

        @Test
        @DisplayName("the input is read once at each sequential position and once more to observe end "
                + "of file")
        void theInputIsReadOnceAtEachPosition() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();
            final List<Transaction> ordered = unitRecords(1, 3, CARD_A);
            final List<Integer> visited = new ArrayList<>();
            final ReportTransactionSource counting = position -> {
                visited.add(position);
                return position < ordered.size()
                        ? Optional.of(ordered.get(position))
                        : Optional.empty();
            };

            reportService.generateReport(counting, line -> { }, WINDOW_START, WINDOW_END);

            assertThat(visited)
                    .as("three positions, then one read that reports exhaustion")
                    .containsExactly(0, 1, 2, 3);
        }

        @Test
        @DisplayName("a deliberately unsorted generation is emitted in the order supplied, proving no "
                + "client-side sort is applied")
        void anUnsortedGenerationIsEmittedInTheOrderSupplied() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            // Card B, then card A, then card B again: an ordering no card-number sort would produce.
            final Report report = run(List.of(
                    transaction(1, CARD_B, "1.00", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_A, "1.00", INSIDE_WINDOW_DATE),
                    transaction(3, CARD_B, "1.00", INSIDE_WINDOW_DATE)));

            assertThat(linesOfKind(report, LineKind.DETAIL))
                    .hasSize(3)
                    .extracting(line -> line.substring(DETAIL_ACCOUNT_ID_OFFSET,
                            DETAIL_ACCOUNT_ID_OFFSET + DETAIL_ACCOUNT_ID_WIDTH))
                    .containsExactly(ACCOUNT_B, ACCOUNT_A, ACCOUNT_B);
            assertThat(linesOfKind(report, LineKind.DETAIL))
                    .extracting(line -> line.substring(0, TRANSACTION_ID_WIDTH))
                    .containsExactly(identifier(1), identifier(2), identifier(3));
        }

        @Test
        @DisplayName("an already ascending generation is emitted unchanged, so ordering is neither "
                + "re-applied nor reversed")
        void anAscendingGenerationIsEmittedUnchanged() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final Report report = run(List.of(
                    transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_A, "1.00", INSIDE_WINDOW_DATE),
                    transaction(3, CARD_B, "1.00", INSIDE_WINDOW_DATE)));

            assertThat(linesOfKind(report, LineKind.DETAIL))
                    .extracting(line -> line.substring(DETAIL_ACCOUNT_ID_OFFSET,
                            DETAIL_ACCOUNT_ID_OFFSET + DETAIL_ACCOUNT_ID_WIDTH))
                    .containsExactly(ACCOUNT_A, ACCOUNT_A, ACCOUNT_B);
        }

        @Test
        @DisplayName("the transaction store is never touched, so the maximum-identifier finder is "
                + "never invoked and no live re-query happens")
        void theTransactionStoreIsNeverTouched() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            run(List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            verify(transactionRepository, never()).findMaxId();
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a source that reports no result at all is refused rather than read as end of "
                + "file")
        void aSourceThatReportsNoResultIsRefused() {
            final ReportTransactionSource broken = position -> null;

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> reportService.generateReport(broken, line -> { },
                            WINDOW_START, WINDOW_END));
        }
    }

    // =============================================================================================
    // Paragraph units 17, 18, 19 and 26: three reference lookups, all of which abend on a miss.
    // =============================================================================================

    @Nested
    @DisplayName("the reference lookups: all three abend on a miss, diagnosing before aborting, and "
            + "the report has no reject path at all")
    class TheReferenceLookupAbends {

        @Test
        @DisplayName("an absent cross-reference logs its own diagnostic, then emits the raw status, "
                + "then abends - in that order")
        void anAbsentCrossReferenceDiagnosesThenAbends() {
            when(cardCrossReferenceRepository.findById(CARD_A)).thenReturn(Optional.empty());
            stubAbendTerminatesTheRun();

            final AbendException raised = catchAbend(
                    List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            assertThat(loggedMessages())
                    .as("the diagnostic is already recorded by the time the abort is raised")
                    .anyMatch(message -> message.startsWith("INVALID CARD NUMBER"));
            final InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus(STATUS_RECORD_NOT_FOUND, OPERATION_READ,
                    DD_CARDXREF);
            order.verify(abendService).abendBatch(eq(PROGRAM_NAME), any(),
                    eq(STATUS_RECORD_NOT_FOUND), eq(OPERATION_READ), eq(DD_CARDXREF));
            assertAbendContext(raised);
        }

        @Test
        @DisplayName("an absent transaction type logs its own diagnostic, then emits the raw status, "
                + "then abends - in that order")
        void anAbsentTransactionTypeDiagnosesThenAbends() {
            stubCrossReferenceForCardA();
            when(transactionTypeRepository.findById(TYPE_CODE)).thenReturn(Optional.empty());
            stubAbendTerminatesTheRun();

            final AbendException raised = catchAbend(
                    List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            assertThat(loggedMessages())
                    .anyMatch(message -> message.startsWith("INVALID TRANSACTION TYPE"));
            final InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus(STATUS_RECORD_NOT_FOUND, OPERATION_READ,
                    DD_TRANTYPE);
            order.verify(abendService).abendBatch(eq(PROGRAM_NAME), any(),
                    eq(STATUS_RECORD_NOT_FOUND), eq(OPERATION_READ), eq(DD_TRANTYPE));
            assertAbendContext(raised);
        }

        @Test
        @DisplayName("an absent transaction category logs its own diagnostic, then emits the raw "
                + "status, then abends - in that order")
        void anAbsentTransactionCategoryDiagnosesThenAbends() {
            stubCrossReferenceForCardA();
            when(transactionTypeRepository.findById(TYPE_CODE))
                    .thenReturn(Optional.of(new TransactionType(TYPE_CODE, TYPE_DESCRIPTION)));
            when(transactionCategoryRepository.findById(new TransactionCategoryId(TYPE_CODE,
                    CATEGORY_CODE))).thenReturn(Optional.empty());
            stubAbendTerminatesTheRun();

            final AbendException raised = catchAbend(
                    List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            assertThat(loggedMessages())
                    .anyMatch(message -> message.startsWith("INVALID TRAN CATG KEY"));
            final InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus(STATUS_RECORD_NOT_FOUND, OPERATION_READ,
                    DD_TRANCATG);
            order.verify(abendService).abendBatch(eq(PROGRAM_NAME), any(),
                    eq(STATUS_RECORD_NOT_FOUND), eq(OPERATION_READ), eq(DD_TRANCATG));
            assertAbendContext(raised);
        }

        @Test
        @DisplayName("a lookup miss produces no report record at all, because the report has no "
                + "reject path and no skip path")
        void aLookupMissProducesNoRejectRecord() {
            when(cardCrossReferenceRepository.findById(CARD_A)).thenReturn(Optional.empty());
            stubAbendTerminatesTheRun();
            final List<String> collected = new ArrayList<>();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> reportService.generateReport(frozenSource(List.of(
                            transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE))), collected::add,
                            WINDOW_START, WINDOW_END));

            assertAll(
                    () -> assertThat(collected)
                            .as("no detail record, no total record and no reject record is produced")
                            .noneMatch(line -> kindOf(line) == LineKind.DETAIL),
                    () -> assertThat(collected)
                            .noneMatch(line -> kindOf(line) == LineKind.PAGE_TOTAL),
                    () -> assertThat(collected)
                            .noneMatch(line -> kindOf(line) == LineKind.GRAND_TOTAL),
                    () -> assertThat(collected)
                            .allSatisfy(line -> assertThat(encodedWidthOf(line))
                                    .isEqualTo(RECORD_WIDTH)));
        }

        @Test
        @DisplayName("the first missing reference is the one that fails, on the record that first "
                + "needs it")
        void theFirstMissingReferenceIsTheOneThatFails() {
            stubCrossReferenceForCardA();
            when(transactionTypeRepository.findById(TYPE_CODE)).thenReturn(Optional.empty());
            stubAbendTerminatesTheRun();

            catchAbend(List.of(
                    transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            assertAll(
                    () -> verify(cardCrossReferenceRepository, times(1)).findById(CARD_A),
                    () -> verify(transactionTypeRepository, times(1)).findById(TYPE_CODE),
                    () -> verify(transactionCategoryRepository, never())
                            .findById(any(TransactionCategoryId.class)));
        }
    }

    // =============================================================================================
    // The shapes of the three lookup keys.
    // =============================================================================================

    @Nested
    @DisplayName("the lookup key shapes: a bare type code, a type-then-category identifier, and a "
            + "list-valued alternate-index finder")
    class TheLookupKeyShapes {

        @Test
        @DisplayName("the type lookup keys on the bare two-character type code, which is the entity's "
                + "own suffix-free identifier property, with no separate identifier class")
        void theTypeLookupKeysOnTheSuffixFreeIdentifierProperty() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            run(List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            // The captor is typed to the store's declared identifier type. That it compiles at all is
            // the structural proof that the type store keys on a plain string rather than on an
            // identifier class of its own.
            final ArgumentCaptor<String> typeKey = ArgumentCaptor.forClass(String.class);
            verify(transactionTypeRepository).findById(typeKey.capture());

            assertAll(
                    () -> assertThat(typeKey.getValue()).isEqualTo(TYPE_CODE),
                    () -> assertThat(encodedWidthOf(typeKey.getValue())).isEqualTo(2),
                    () -> assertThat(new TransactionType(TYPE_CODE, TYPE_DESCRIPTION).getTranType())
                            .as("the identifier property is the suffix-free type code")
                            .isEqualTo(typeKey.getValue()));
        }

        @Test
        @DisplayName("the category identifier is built type code first and category code second, which "
                + "guards against a transposition that would compile and mis-key every lookup")
        void theCategoryIdentifierIsBuiltTypeThenCategory() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            run(List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            final ArgumentCaptor<TransactionCategoryId> categoryKey =
                    ArgumentCaptor.forClass(TransactionCategoryId.class);
            verify(transactionCategoryRepository).findById(categoryKey.capture());

            assertAll(
                    () -> assertThat(categoryKey.getValue().getTranTypeCd()).isEqualTo(TYPE_CODE),
                    () -> assertThat(categoryKey.getValue().getTranCatCd())
                            .isEqualTo(CATEGORY_CODE),
                    () -> assertThat(categoryKey.getValue())
                            .isEqualTo(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE)),
                    () -> assertThat(categoryKey.getValue())
                            .as("the transposed identifier must not be equal to the correct one")
                            .isNotEqualTo(new TransactionCategoryId(CATEGORY_CODE, TYPE_CODE)));
        }

        @Test
        @DisplayName("the alternate-index cross-reference finder returns a list, so an empty list is "
                + "the not-found path and no element access escapes")
        void anEmptyCrossReferenceListIsTheNotFoundPath() {
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_A)).thenReturn(List.of());

            final List<CardCrossReference> found =
                    cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_A);

            assertAll(
                    () -> assertThat(found)
                            .as("the finder is list-valued rather than optional-valued")
                            .isEmpty(),
                    () -> assertThat(found.stream().findFirst())
                            .as("the not-found path yields no element and raises nothing")
                            .isEmpty());
        }

        @Test
        @DisplayName("a multi-element cross-reference list yields its first element as supplied, with "
                + "no re-sorting")
        void aMultiElementCrossReferenceListUsesItsFirstElementAsSupplied() {
            final CardCrossReference second = new CardCrossReference(CARD_B, CUSTOMER_B, ACCOUNT_A);
            final CardCrossReference first = new CardCrossReference(CARD_A, CUSTOMER_A, ACCOUNT_A);
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_A))
                    .thenReturn(List.of(second, first));

            final List<CardCrossReference> found =
                    cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_A);

            assertAll(
                    () -> assertThat(found).hasSize(2),
                    () -> assertThat(found.get(0))
                            .as("the first element as supplied wins, and the list is not re-sorted")
                            .isSameAs(second),
                    () -> assertThat(found.get(0).getXrefCardNum()).isEqualTo(CARD_B));
        }

        @Test
        @DisplayName("each distinct reference is resolved once per run, so the read count is bounded "
                + "by distinct keys rather than by record count")
        void eachDistinctReferenceIsResolvedOncePerRun() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            run(decisiveGeneration());

            assertAll(
                    () -> verify(cardCrossReferenceRepository, times(1)).findById(CARD_A),
                    () -> verify(cardCrossReferenceRepository, times(1)).findById(CARD_B),
                    () -> verify(transactionTypeRepository, times(1)).findById(TYPE_CODE),
                    () -> verify(transactionCategoryRepository, times(1))
                            .findById(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE)));
        }
    }

    // =============================================================================================
    // The three reproduced legacy defects. Each is deliberate. A "fix" to any of them is a parity
    // regression, and each assertion below exists so that the regression registers as one.
    // =============================================================================================

    @Nested
    @DisplayName("the three reproduced legacy defects, pinned so that a later correction registers as "
            + "a parity regression rather than an improvement")
    class TheReproducedLegacyDefects {

        @Test
        @DisplayName("REPRODUCED DEFECT ONE: an account-total block steps the counter PAST a multiple "
                + "of twenty, so the page break is missed outright and is never made up")
        void reproducedDefectOneAnAccountTotalBlockMissesThePageBreak() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            // Fifteen records on the first card leave the counter at nineteen. The account break at
            // the sixteenth record then advances it by two, to twenty-one - stepping over twenty
            // without ever equalling it - so the modulo test never fires. The break that would have
            // landed at the seventeenth record is lost, and because the shift persists the next
            // multiple of twenty is not reached before the generation is exhausted.
            final Report defective = run(missedPageBreakGeneration());

            // The control differs in exactly one respect: the same thirty-four records on one card,
            // so no account-total block intervenes and the break lands where the modulo test puts it.
            final Report control = run(unitRecords(1, 34, CARD_A));

            assertAll(
                    () -> assertThat(defective.result().accountBreakCount()).isEqualTo(1),
                    () -> assertThat(linesOfKind(defective, LineKind.DETAIL)).hasSize(34),
                    () -> assertThat(linesOfKind(defective, LineKind.NAME_HEADER))
                            .as("the missed break means only one header block is ever written")
                            .hasSize(1),
                    () -> assertThat(detailRowsPerPage(defective))
                            .as("all thirty-four rows land on one page instead of sixteen then "
                                    + "eighteen")
                            .containsExactly(34),
                    () -> assertThat(linesOfKind(defective, LineKind.PAGE_TOTAL))
                            .as("the only page total is the one the at-end path writes")
                            .hasSize(1),
                    () -> assertThat(defective.result().pageCount()).isEqualTo(1),
                    () -> assertThat(control.result().accountBreakCount()).isZero(),
                    () -> assertThat(linesOfKind(control, LineKind.NAME_HEADER))
                            .as("without the account block the break fires and repeats the header")
                            .hasSize(3),
                    () -> assertThat(detailRowsPerPage(control))
                            .containsExactly(FIRST_PAGE_DETAIL_ROWS, STEADY_STATE_DETAIL_ROWS, 4),
                    () -> assertThat(control.result().pageCount()).isEqualTo(3));
        }

        @Test
        @DisplayName("REPRODUCED DEFECT TWO, latent in production: the first out-of-window record "
                + "terminates the WHOLE report rather than being skipped")
        void reproducedDefectTwoAnOutOfWindowRecordTerminatesTheWholeReport() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            // The non-matching arm of the inclusive range guard escapes the whole driving loop rather
            // than continuing with the next record, so the third record is never reported and the
            // at-end page and grand totals are never written. The arm is unreachable in production
            // because the preceding batch step applies the same inclusive predicate before it freezes
            // the ordered generation, so no out-of-window record ever reaches this service - which is
            // exactly why the defect is latent and why it is reproduced deliberately rather than
            // corrected.
            final Report report = run(List.of(
                    transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE),
                    recordOneDayAfterTheWindow(2),
                    transaction(3, CARD_A, "7.00", INSIDE_WINDOW_DATE)));

            assertAll(
                    () -> assertThat(report.result().reportRecordCount())
                            .as("four header records and one detail record, and nothing else")
                            .isEqualTo(5L),
                    () -> assertThat(linesOfKind(report, LineKind.DETAIL))
                            .as("the record that follows the out-of-window one is never reported")
                            .hasSize(1),
                    () -> assertThat(report.lines())
                            .noneMatch(line -> line.substring(0, TRANSACTION_ID_WIDTH)
                                    .equals(identifier(3))),
                    () -> assertThat(linesOfKind(report, LineKind.PAGE_TOTAL))
                            .as("the report is truncated before the at-end page total")
                            .isEmpty(),
                    () -> assertThat(linesOfKind(report, LineKind.GRAND_TOTAL))
                            .as("and before the grand total, so the report has no trailer at all")
                            .isEmpty(),
                    () -> assertThat(report.result().pageCount()).isZero(),
                    () -> assertThat(report.result().grandTotal())
                            .isEqualByComparingTo(ZERO_AT_MONETARY_SCALE),
                    () -> assertThat(report.result().grandTotal().scale()).isEqualTo(2));
        }

        @Test
        @DisplayName("REPRODUCED DEFECT TWO, latent in production: an out-of-window FIRST record "
                + "terminates the report before even the header block is written")
        void reproducedDefectTwoAnOutOfWindowFirstRecordSuppressesEverything() {
            final Report report = run(List.of(
                    recordOneDayBeforeTheWindow(1),
                    transaction(2, CARD_A, "4.00", INSIDE_WINDOW_DATE)));

            assertAll(
                    () -> assertThat(report.lines()).isEmpty(),
                    () -> assertThat(report.result().reportRecordCount()).isZero(),
                    () -> assertThat(report.result().lineCount()).isZero(),
                    () -> assertThat(report.result().pageCount()).isZero(),
                    () -> assertThat(report.result().accountBreakCount()).isZero(),
                    () -> assertThat(report.result().grandTotal())
                            .isEqualByComparingTo(ZERO_AT_MONETARY_SCALE));
        }

        @Test
        @DisplayName("REPRODUCED DEFECT THREE: at end of file the last amount is counted TWICE and no "
                + "account total is written at all")
        void reproducedDefectThreeTheLastAmountIsCountedTwiceWithNoAccountTotal() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            // The legacy read leaves the record area holding the previous record at the at-end
            // condition, and the at-end arm then adds that stale amount to the page and account
            // totals a second time. Ten plus twenty-five is thirty-five; the stale twenty-five is
            // added again, so the page total - and therefore the grand total, which is fed only from
            // page totals - reads sixty.
            final Report report = run(List.of(
                    transaction(1, CARD_A, "10.00", INSIDE_WINDOW_DATE),
                    transaction(2, CARD_A, "25.00", INSIDE_WINDOW_DATE)));

            final BigDecimal directDetailSum = new BigDecimal("35.00");
            final BigDecimal staleAmountCountedAgain = new BigDecimal("25.00");
            final BigDecimal withTheDoubleCount = new BigDecimal("60.00");

            assertAll(
                    () -> assertThat(directDetailSum.add(staleAmountCountedAgain))
                            .as("the doubled contribution is reproducible by addition alone")
                            .isEqualByComparingTo(withTheDoubleCount),
                    () -> assertThat(amountOn(linesOfKind(report, LineKind.PAGE_TOTAL).get(0)))
                            .isEqualByComparingTo(withTheDoubleCount),
                    () -> assertThat(report.result().grandTotal())
                            .isEqualByComparingTo(withTheDoubleCount),
                    () -> assertThat(report.result().grandTotal())
                            .as("a run without the double count would read the direct detail sum")
                            .isNotEqualByComparingTo(directDetailSum),
                    () -> assertThat(linesOfKind(report, LineKind.ACCOUNT_TOTAL))
                            .as("the at-end path writes no account-total block at all")
                            .isEmpty(),
                    () -> assertThat(report.result().accountBreakCount()).isZero(),
                    () -> assertThat(linesOfKind(report, LineKind.PAGE_TOTAL)).hasSize(1),
                    () -> assertThat(linesOfKind(report, LineKind.GRAND_TOTAL)).hasSize(1));
        }

        @Test
        @DisplayName("REPRODUCED DEFECT THREE: with no record read at all there is no stale amount, so "
                + "the at-end path adds nothing and still writes its trailer")
        void reproducedDefectThreeAddsNothingWhenNoRecordWasEverRead() {
            final Report report = run(List.of());

            assertAll(
                    () -> assertThat(report.lines()).hasSize(3),
                    () -> assertThat(linesOfKind(report, LineKind.PAGE_TOTAL)).hasSize(1),
                    () -> assertThat(linesOfKind(report, LineKind.RULE)).hasSize(1),
                    () -> assertThat(linesOfKind(report, LineKind.GRAND_TOTAL)).hasSize(1),
                    () -> assertThat(linesOfKind(report, LineKind.NAME_HEADER))
                            .as("no record was reported, so no header block was ever written")
                            .isEmpty(),
                    () -> assertThat(report.result().grandTotal())
                            .isEqualByComparingTo(ZERO_AT_MONETARY_SCALE),
                    () -> assertThat(report.result().lineCount()).isEqualTo(2L));
        }
    }

    // =============================================================================================
    // Paragraph unit 27, and the two-level status model the read paragraphs branch on.
    // =============================================================================================

    @Nested
    @DisplayName("file status handling: the raw two-byte codes this estate actually compares, and the "
            + "coarse outcome exercised through its owner")
    class TheFileStatusLevels {

        @Test
        @DisplayName("the raw status vocabulary this feature depends on is success, end of file and "
                + "record not found, and nothing else")
        void theRawStatusVocabularyIsThreeCodes() {
            assertAll(
                    () -> assertThat(FileStatus.SUCCESS.getCode()).isEqualTo(STATUS_SUCCESS),
                    () -> assertThat(FileStatus.END_OF_FILE.getCode())
                            .isEqualTo(STATUS_END_OF_FILE),
                    () -> assertThat(FileStatus.RECORD_NOT_FOUND.getCode())
                            .isEqualTo(STATUS_RECORD_NOT_FOUND),
                    () -> assertThat(FileStatus.SUCCESS.isSuccess()).isTrue(),
                    () -> assertThat(FileStatus.SUCCESS.isEndOfFile()).isFalse(),
                    () -> assertThat(FileStatus.END_OF_FILE.isEndOfFile()).isTrue(),
                    () -> assertThat(FileStatus.END_OF_FILE.isSuccess()).isFalse(),
                    () -> assertThat(FileStatus.RECORD_NOT_FOUND.isSuccess()).isFalse(),
                    () -> assertThat(FileStatus.RECORD_NOT_FOUND.isEndOfFile()).isFalse(),
                    () -> assertThat(FileStatus.fromCode(STATUS_SUCCESS))
                            .contains(FileStatus.SUCCESS),
                    () -> assertThat(FileStatus.fromCode(STATUS_END_OF_FILE))
                            .contains(FileStatus.END_OF_FILE),
                    () -> assertThat(FileStatus.fromCode(STATUS_RECORD_NOT_FOUND))
                            .contains(FileStatus.RECORD_NOT_FOUND),
                    () -> assertThat(FileStatus.fromCode(null)).isEmpty());
        }

        @Test
        @DisplayName("the coarse outcome is exercised through its owner: the success arm reports, the "
                + "end-of-file arm terminates normally, and the error arm abends")
        void theCoarseOutcomeIsExercisedThroughItsOwner() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            // Success arm: every append reports success and the report is produced.
            final Report success = run(List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));
            // End-of-file arm: exhaustion terminates the read loop normally and never abends.
            final Report endOfFile = run(List.of());

            assertAll(
                    () -> assertThat(success.result().reportRecordCount()).isEqualTo(8L),
                    () -> assertThat(endOfFile.result().reportRecordCount()).isEqualTo(3L),
                    () -> assertThat(endOfFile.result().grandTotal())
                            .as("end of file is a normal outcome, never collapsed into error")
                            .isEqualByComparingTo(ZERO_AT_MONETARY_SCALE));
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("the error arm is the only one that abends, and it carries the record-not-found "
                + "status through the display and the abort alike")
        void theErrorArmCarriesTheRawStatusThroughBothCalls() {
            when(cardCrossReferenceRepository.findById(CARD_A)).thenReturn(Optional.empty());
            stubAbendTerminatesTheRun();

            catchAbend(List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            final ArgumentCaptor<String> displayedStatus = ArgumentCaptor.forClass(String.class);
            verify(abendService).displayIoStatus(displayedStatus.capture(), eq(OPERATION_READ),
                    eq(DD_CARDXREF));
            assertAll(
                    () -> assertThat(displayedStatus.getValue())
                            .isEqualTo(STATUS_RECORD_NOT_FOUND),
                    () -> assertThat(encodedWidthOf(displayedStatus.getValue()))
                            .isEqualTo(FileStatusException.CODE_LENGTH));
        }

        @Test
        @DisplayName("the file-status failure models the error arm only, so success and end of file "
                + "are both refused as errors")
        void theFileStatusFailureModelsTheErrorArmOnly() {
            assertAll(
                    () -> assertThat(FileStatusException.CODE_LENGTH).isEqualTo(2),
                    () -> assertThat(FileStatusException.STATUS_SUCCESS).isEqualTo(STATUS_SUCCESS),
                    () -> assertThat(FileStatusException.STATUS_END_OF_FILE)
                            .isEqualTo(STATUS_END_OF_FILE),
                    () -> assertThat(FileStatusException.DISPLAY_PREFIX)
                            .isEqualTo("FILE STATUS IS: NNNN"),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .as("success is not an error and must not be raised as one")
                            .isThrownBy(() -> new FileStatusException(STATUS_SUCCESS,
                                    OPERATION_READ, DD_CARDXREF)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .as("end of file is a normal outcome and must not be raised as an error")
                            .isThrownBy(() -> new FileStatusException(STATUS_END_OF_FILE,
                                    OPERATION_READ, DD_CARDXREF)));
        }

        @Test
        @DisplayName("the record-not-found status is a legal file-status failure and reports its own "
                + "two bytes, its operation and its resource")
        void theRecordNotFoundStatusIsALegalFailure() {
            final FileStatusException failure = new FileStatusException(STATUS_RECORD_NOT_FOUND,
                    OPERATION_READ, DD_CARDXREF);

            assertAll(
                    () -> assertThat(failure.code()).isEqualTo(STATUS_RECORD_NOT_FOUND),
                    () -> assertThat(failure.firstByte()).isEqualTo('2'),
                    () -> assertThat(failure.secondByte()).isEqualTo('3'),
                    () -> assertThat(failure.operation()).isEqualTo(OPERATION_READ),
                    () -> assertThat(failure.resourceName()).isEqualTo(DD_CARDXREF),
                    () -> assertThat(failure).isInstanceOf(RuntimeException.class));
        }
    }

    // =============================================================================================
    // Null and boundary input, and the guards the result record declares.
    // =============================================================================================

    @Nested
    @DisplayName("null and boundary input: an empty generation, one record, all-zero amounts, an "
            + "absent parameter card and absent text fields")
    class NullAndBoundaryInput {

        @Test
        @DisplayName("an empty generation still writes the at-end trailer, which matters because the "
                + "transaction table starts empty after the reference-data seed")
        void anEmptyGenerationStillWritesItsTrailer() {
            final Report report = run(List.of());

            assertAll(
                    () -> assertThat(report.lines()).hasSize(3),
                    () -> assertThat(report.lines()).allSatisfy(line -> assertThat(
                            encodedWidthOf(line)).isEqualTo(RECORD_WIDTH)),
                    () -> assertThat(maskOn(report.lines().get(0))).isEqualTo(BLANK_AMOUNT_MASK),
                    () -> assertThat(report.lines().get(1)).isEqualTo(RULE_LINE),
                    () -> assertThat(maskOn(report.lines().get(2))).isEqualTo(BLANK_AMOUNT_MASK));
        }

        @Test
        @DisplayName("a single-record generation produces the header block, one detail line and the "
                + "at-end trailer")
        void aSingleRecordGenerationProducesEightRecords() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();

            final Report report = run(List.of(transaction(1, CARD_A, "1.00", INSIDE_WINDOW_DATE)));

            assertAll(
                    () -> assertThat(report.lines()).hasSize(8),
                    () -> assertThat(linesOfKind(report, LineKind.DETAIL)).hasSize(1),
                    () -> assertThat(report.result().accountBreakCount())
                            .as("the first record never emits a leading account total")
                            .isZero(),
                    () -> assertThat(report.result().grandTotal())
                            .isEqualByComparingTo(new BigDecimal("2.00")));
        }

        @Test
        @DisplayName("an absent parameter card is end of file, so the report is empty and nothing at "
                + "all is written")
        void anAbsentParameterCardProducesAnEmptyReport() {
            final List<String> collected = new ArrayList<>();

            final TransactionReportService.TransactionReportResult result = reportService
                    .generateReportFromDateParameterCard(frozenSource(List.of()), collected::add,
                            null);

            assertAll(
                    () -> assertThat(collected).isEmpty(),
                    () -> assertThat(result.reportRecordCount()).isZero(),
                    () -> assertThat(result.lineCount()).isZero(),
                    () -> assertThat(result.pageCount()).isZero(),
                    () -> assertThat(result.accountBreakCount()).isZero(),
                    () -> assertThat(result.grandTotal())
                            .isEqualByComparingTo(ZERO_AT_MONETARY_SCALE),
                    () -> assertThat(result.grandTotal().scale()).isEqualTo(2));
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("an absent bound is refused at the boundary rather than defaulted")
        void anAbsentBoundIsRefused() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> reportService.generateReport(
                                    frozenSource(List.of()), line -> { }, null, WINDOW_END)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> reportService.generateReport(
                                    frozenSource(List.of()), line -> { }, WINDOW_START, null)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("a run cannot start without a destination for its records")
                            .isThrownBy(() -> reportService.generateReport(
                                    frozenSource(List.of()), null, WINDOW_START, WINDOW_END)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> reportService.generateReport(null, line -> { },
                                    WINDOW_START, WINDOW_END)));
        }

        @Test
        @DisplayName("absent merchant and description fields are immaterial, because the report reads "
                + "neither, so no null reference escapes")
        void absentMerchantAndDescriptionFieldsAreImmaterial() {
            stubCrossReferenceForCardA();
            stubTypeAndCategory();
            final Transaction withoutOptionalText = new Transaction(identifier(1), TYPE_CODE,
                    CATEGORY_CODE, SOURCE, null, new BigDecimal("1.00"), null, null, null, null,
                    CARD_A, PINNED_ORIGINATION_TIMESTAMP,
                    INSIDE_WINDOW_DATE.toString() + " ".repeat(16));

            final Report report = run(List.of(withoutOptionalText));

            assertAll(
                    () -> assertThat(report.lines()).hasSize(8),
                    () -> assertThat(report.lines()).allSatisfy(line -> assertThat(
                            encodedWidthOf(line)).isEqualTo(RECORD_WIDTH)),
                    () -> assertThat(linesOfKind(report, LineKind.DETAIL)).hasSize(1),
                    () -> assertThat(withoutOptionalText.getTranDesc()).isNull(),
                    () -> assertThat(withoutOptionalText.getMerchantId()).isNull(),
                    () -> assertThat(withoutOptionalText.getMerchantName()).isNull(),
                    () -> assertThat(withoutOptionalText.getMerchantCity()).isNull(),
                    () -> assertThat(withoutOptionalText.getMerchantZip()).isNull());
        }

        @Test
        @DisplayName("the result refuses an absent grand total or an impossible record count")
        void theResultRefusesAnImpossibleComponent() {
            assertAll(
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new TransactionReportService.TransactionReportResult(
                                    -1L, ZERO_AT_MONETARY_SCALE, 0, 0L, 0)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new TransactionReportService.TransactionReportResult(
                                    0L, null, 0, 0L, 0)));
        }
    }

    // =============================================================================================
    // The additions-only mandate: the authority contains zero computation statements.
    // =============================================================================================

    @Nested
    @DisplayName("the additions-only mandate: every total is reproducible by addition alone, because "
            + "the authority contains zero computation statements")
    class TheAdditionsOnlyMandate {

        @Test
        @DisplayName("each page total is the plain sum of its own page's detail amounts, with the last "
                + "carrying the at-end re-add and nothing else")
        void eachPageTotalIsThePlainSumOfItsOwnPage() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final Report report = run(decisiveGeneration());

            // Fourteen unit records, then fourteen unit records, then five unit records and one of
            // nine - and the nine again at end of file. Every figure below is an addition of the
            // fixture's own amounts; nothing is multiplied, divided or derived.
            final BigDecimal firstPage = new BigDecimal("14.00");
            final BigDecimal secondPage = new BigDecimal("14.00");
            final BigDecimal thirdPage = new BigDecimal("5.00").add(new BigDecimal("9.00"))
                    .add(new BigDecimal("9.00"));

            assertAll(
                    () -> assertThat(amountOn(linesOfKind(report, LineKind.PAGE_TOTAL).get(0)))
                            .isEqualByComparingTo(firstPage),
                    () -> assertThat(amountOn(linesOfKind(report, LineKind.PAGE_TOTAL).get(1)))
                            .isEqualByComparingTo(secondPage),
                    () -> assertThat(amountOn(linesOfKind(report, LineKind.PAGE_TOTAL).get(2)))
                            .isEqualByComparingTo(thirdPage),
                    () -> assertThat(report.result().grandTotal())
                            .isEqualByComparingTo(firstPage.add(secondPage).add(thirdPage)));
        }

        @Test
        @DisplayName("every amount the report emits is one of the fixture's own amounts or a sum of "
                + "them, so no derived figure appears anywhere")
        void everyEmittedAmountIsAnAmountOrASumOfAmounts() {
            stubCrossReferenceForCardA();
            stubCrossReferenceForCardB();
            stubTypeAndCategory();

            final Report report = run(decisiveGeneration());

            final BigDecimal unit = new BigDecimal("1.00");
            final BigDecimal nine = new BigDecimal("9.00");
            assertAll(
                    () -> assertThat(linesOfKind(report, LineKind.DETAIL))
                            .as("thirty-three unit amounts and one of nine, and no other value")
                            .allSatisfy(line -> assertThat(amountOn(line))
                                    .isIn(unit, nine)),
                    () -> assertThat(amountOn(linesOfKind(report, LineKind.ACCOUNT_TOTAL).get(0)))
                            .as("eight unit amounts added together")
                            .isEqualByComparingTo(unit.add(unit).add(unit).add(unit).add(unit)
                                    .add(unit).add(unit).add(unit)),
                    () -> assertThat(report.result().grandTotal().scale())
                            .as("every accumulator stays at the two-decimal scale, never rescaled")
                            .isEqualTo(2));
        }

        @Test
        @DisplayName("the accumulators start at zero on the two-decimal scale, which is what the "
                + "authority's value clauses establish")
        void theAccumulatorsStartAtZeroOnTheMonetaryScale() {
            final Report report = run(List.of());

            assertAll(
                    () -> assertThat(report.result().grandTotal())
                            .isEqualByComparingTo(ZERO_AT_MONETARY_SCALE),
                    () -> assertThat(report.result().grandTotal().scale()).isEqualTo(2),
                    () -> assertThat(amountOn(linesOfKind(report, LineKind.PAGE_TOTAL).get(0)))
                            .isEqualByComparingTo(ZERO_AT_MONETARY_SCALE),
                    () -> assertThat(amountOn(linesOfKind(report, LineKind.GRAND_TOTAL).get(0)))
                            .isEqualByComparingTo(ZERO_AT_MONETARY_SCALE));
        }
    }
}
