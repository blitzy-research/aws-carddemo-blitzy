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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategory;
import com.carddemo.domain.TransactionType;
import com.carddemo.domain.id.TransactionCategoryId;
import com.carddemo.exception.AbendException;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.util.ReportLineFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


/**
 * Verifies {@link TransactionReportService}, the translation of the transaction detail report program
 * {@code app/cbl/CBTRN03C.cbl}.
 *
 * <p><strong>Why a parity suite rather than a behavioural one.</strong> Five of this program's
 * behaviours are counter-intuitive, and each of the five has a plausible, compiling, wrong
 * alternative that no compiler and no naive test would reject. This class exists to reject them.
 * Each nested group below names the alternative it rules out, so that a later change which
 * "simplifies" one of them fails here with a reason rather than silently altering the report.
 *
 * <p><strong>The decisive assertion is the accumulation chain.</strong> The detail line of lines
 * 287-288 adds the transaction amount to the page total and the account total and to nothing else;
 * the grand total grows only at line 297, from the page total. The fixture in the first group is
 * built so that a direct detail-to-grand-total feed produces a <em>different</em> number, which is
 * the only way an assertion on that chain can be trusted.
 *
 * <p><strong>Two legacy defects are asserted as defects.</strong> The non-matching arm of the range
 * filter at lines 173-178 uses {@code NEXT SENTENCE}, and because the next sentence-terminating
 * period in the member is the one on the loop's scope terminator at line 206, that arm leaves the whole
 * driving loop rather than skipping one record. Separately, the at-end path of lines 197-203 re-adds
 * the stale amount of the last record, so the final transaction is accumulated twice. Both are
 * reproduced deliberately, so both are pinned here: a future "fix" to either would be a parity
 * regression, not an improvement.
 *
 * <p>The three lookup repositories are mocked because the subject under test is the report's
 * counting, accumulation and break logic. The ordered transaction input is supplied through a
 * service-owned frozen source, which lets the suite prove that this service consumes the snapshot in
 * positional order without querying or re-sorting live master data.
 */
class TransactionReportServiceParityTest {

    private static final String START = "2022-07-01";

    private static final String END = "2022-07-31";

    private static final String CARD_A = "4111111111111111";

    private static final String CARD_B = "4222222222222222";

    private static final int RECORD_WIDTH = 133;

    private final CardCrossReferenceRepository crossReferences =
            mock(CardCrossReferenceRepository.class);

    private final TransactionTypeRepository types = mock(TransactionTypeRepository.class);

    private final TransactionCategoryRepository categories =
            mock(TransactionCategoryRepository.class);

    private final AbendService abendService = mock(AbendService.class);

    private final TransactionReportService reportService = new TransactionReportService(
            crossReferences, types, categories, abendService);

    private ReportTransactionSource transactionSource = sourceOf(List.of());

    private final ReportHarness service = new ReportHarness();

    private Logger serviceLogger;

    private Level previousLogLevel;

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void attachLogCapture() {
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

    private List<String> loggedMessages() {
        return logCapture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Builds one transaction at the widths the 350-byte record layout of
     * {@code app/cpy/CVTRA05Y.cpy} declares. Only the fields the report reads carry meaningful
     * values; the rest are present because the layout requires them.
     */
    private static Transaction transaction(String id, String card, String amount, String processed) {
        return new Transaction(id, "01", "0005", "POS TERM", "PURCHASE AT MERCHANT",
                new BigDecimal(amount), "000000001", "MERCHANT", "CITY", "1234567890",
                card, processed + "-00.00.00.000000", processed + "-00.00.00.000000");
    }

    /** Repeats one transaction under a different transaction-type code. */
    private static Transaction ofType(final Transaction original, final String typeCode) {
        return new Transaction(original.getTranId(), typeCode, original.getTranCatCd(),
                original.getTranSource(), original.getTranDesc(), original.getTranAmt(),
                original.getMerchantId(), original.getMerchantName(),
                original.getMerchantCity(), original.getMerchantZip(),
                original.getTranCardNum(), original.getTranOrigTs(), original.getTranProcTs());
    }

    /** Repeats one transaction under a different transaction-category code. */
    private static Transaction inCategory(final Transaction original, final String categoryCode) {
        return new Transaction(original.getTranId(), original.getTranTypeCd(), categoryCode,
                original.getTranSource(), original.getTranDesc(), original.getTranAmt(),
                original.getMerchantId(), original.getMerchantName(),
                original.getMerchantCity(), original.getMerchantZip(),
                original.getTranCardNum(), original.getTranOrigTs(), original.getTranProcTs());
    }

    /** The failure a store raises when its cluster cannot be reached. */
    private static DataAccessResourceFailureException unreachable() {
        return new DataAccessResourceFailureException("the cluster could not be reached");
    }

    private static String identifier(int ordinal) {
        StringBuilder digits = new StringBuilder(Integer.toString(ordinal));
        while (digits.length() < 16) {
            digits.insert(0, '0');
        }
        return digits.toString();
    }

    /** Stubs the three lookups the report performs for every record. */
    private void stubLookups() {
        when(crossReferences.findById(CARD_A)).thenReturn(
                Optional.of(new CardCrossReference(CARD_A, "000000001", "00000000011")));
        when(crossReferences.findById(CARD_B)).thenReturn(
                Optional.of(new CardCrossReference(CARD_B, "000000002", "00000000022")));
        when(types.findById("01")).thenReturn(Optional.of(new TransactionType("01", "PURCHASE")));
        when(categories.findById(any(TransactionCategoryId.class))).thenReturn(
                Optional.of(new TransactionCategory("01", "0005", "RESTAURANT")));
    }

    /** Replaces the report's frozen ordered source with the supplied sequence. */
    private void stubRange(List<Transaction> content) {
        this.transactionSource = sourceOf(content);
    }

    /** Creates a detached positional source over the supplied ordered transactions. */
    private static ReportTransactionSource sourceOf(final List<Transaction> content) {
        final List<Transaction> snapshot = List.copyOf(content);
        return position -> {
            if (position < 0) {
                throw new IllegalArgumentException("position must not be negative: " + position);
            }
            return position < snapshot.size()
                    ? Optional.of(snapshot.get(position))
                    : Optional.empty();
        };
    }

    /**
     * One generated report, assembled by the harness for assertion purposes only.
     *
     * <p>The service streams each record to a sink as it composes it and never holds the report, so
     * collecting the records into a list is the <em>test's</em> choice. Production writes each record
     * straight to its destination; see {@code docs/decision-log.md} entry DL-176.
     *
     * @param reportLines       the records the run offered to the sink, in emission order
     * @param grandTotal        the grand total the run reported
     * @param pageCount         the page-total emissions the run reported
     * @param lineCount         the final line-counter value the run reported
     * @param accountBreakCount the account-total emissions the run reported
     */
    private record GeneratedReport(List<String> reportLines,
                                   BigDecimal grandTotal,
                                   int pageCount,
                                   long lineCount,
                                   int accountBreakCount) {

        /**
         * Pairs a run's observations with the records its sink collected, and proves the two agree.
         *
         * @param collected the records the sink received, in emission order
         * @param observed  the run's own observations
         * @return the paired report
         */
        static GeneratedReport of(final List<String> collected,
                final TransactionReportService.TransactionReportResult observed) {
            assertThat(observed.reportRecordCount())
                    .as("the run's own record count must agree with what its sink received")
                    .isEqualTo(collected.size());
            return new GeneratedReport(List.copyOf(collected), observed.grandTotal(),
                    observed.pageCount(), observed.lineCount(), observed.accountBreakCount());
        }
    }

    /** Keeps existing parity assertions focused on report semantics while supplying the frozen input. */
    private final class ReportHarness {

        GeneratedReport generateReport(final String startDate, final String endDate) {
            final List<String> collected = new ArrayList<>();
            return GeneratedReport.of(collected, reportService.generateReport(transactionSource,
                    collected::add, startDate, endDate));
        }

        GeneratedReport generateReportFromDateParameterCard(final String card) {
            final List<String> collected = new ArrayList<>();
            return GeneratedReport.of(collected,
                    reportService.generateReportFromDateParameterCard(transactionSource,
                            collected::add, card));
        }
    }

    /** Twenty transactions of one currency unit each, ten on each of two cards. */
    private static List<Transaction> twentyAcrossTwoCards() {
        List<Transaction> all = new ArrayList<>();
        for (int ordinal = 1; ordinal <= 10; ordinal++) {
            all.add(transaction(identifier(ordinal), CARD_A, "1.00", "2022-07-05"));
        }
        for (int ordinal = 11; ordinal <= 20; ordinal++) {
            all.add(transaction(identifier(ordinal), CARD_B, "1.00", "2022-07-05"));
        }
        return all;
    }

    @Test
    @DisplayName("transaction, card, amount, merchant and date values never enter report diagnostics")
    void sensitiveReportValuesAreRedacted() {
        final String transactionId = identifier(1);
        final String amount = "91.23";
        stubLookups();
        stubRange(List.of(transaction(transactionId, CARD_A, amount, START)));

        service.generateReport(START, END);

        assertThat(loggedMessages())
                .anyMatch(message -> message.matches(
                        "Reporting transaction transactionRef=\\[REDACTED] ref=[0-9a-f]{24} "
                                + "type=01 category=0005"))
                .anyMatch(message -> message.matches(
                        "Reporting range accepted rangeRef=\\[REDACTED] ref=[0-9a-f]{24}"))
                .noneMatch(message -> message.contains(transactionId))
                .noneMatch(message -> message.contains(CARD_A))
                .noneMatch(message -> message.contains(amount))
                .noneMatch(message -> message.contains("MERCHANT"))
                .noneMatch(message -> message.contains(START))
                .noneMatch(message -> message.contains(END));
    }

    /** Reads the fifteen-character masked amount back out of an emitted line. */
    private static BigDecimal amountOn(String line) {
        String masked = line.substring(ReportLineFormatter.AMOUNT_OFFSET,
                ReportLineFormatter.AMOUNT_OFFSET + ReportLineFormatter.AMOUNT_MASK_WIDTH);
        String digits = masked.replace(",", "").replace(" ", "").replace("+", "");
        return digits.isEmpty() ? BigDecimal.ZERO : new BigDecimal(digits);
    }

    @Test
    @DisplayName("debug progress preserves the anomaly without transaction, card or amount values")
    void debugProgressWithholdsProtectedTransactionData() {
        final String transactionId = "9999999999999999";
        final String amount = "12345678.99";
        stubLookups();
        stubRange(List.of(transaction(transactionId, CARD_A, amount, "2022-07-05")));

        service.generateReport(START, END);

        final List<String> messages =
                this.logCapture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages)
                .contains(
                        "Reporting next transaction record",
                        "End of file: re-adding the stale transaction amount (legacy anomaly)")
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain(transactionId, CARD_A, amount, "MERCHANT",
                                "PURCHASE AT MERCHANT"));
    }

    @Nested
    @DisplayName("the accumulation chain: amounts reach the grand total only through page totals")
    class TheAccumulationChain {

        @Test
        @DisplayName("the grand total is the sum of the page totals, not a direct sum of details")
        void grandTotalIsFedOnlyFromPageTotals() {
            stubLookups();
            stubRange(twentyAcrossTwoCards());

            GeneratedReport result =
                    service.generateReport(START, END);

            // Twenty details of one unit each. A detail-fed grand total would read 20.00. The legacy
            // chain folds page totals and re-adds the stale last amount at end of file, so the only
            // faithful value is 21.00 - which is what makes this assertion able to fail.
            assertThat(result.grandTotal()).isEqualByComparingTo(new BigDecimal("21.00"));
            assertThat(result.grandTotal()).isNotEqualByComparingTo(new BigDecimal("20.00"));

            BigDecimal foldedFromPages = BigDecimal.ZERO;
            for (String line : result.reportLines()) {
                if (line.startsWith("Page Total")) {
                    foldedFromPages = foldedFromPages.add(amountOn(line));
                }
            }
            assertThat(foldedFromPages).isEqualByComparingTo(result.grandTotal());
        }

        @Test
        @DisplayName("an account break resets the account total and leaves the grand total alone")
        void accountBreakDoesNotFeedTheGrandTotal() {
            stubLookups();
            stubRange(twentyAcrossTwoCards());

            GeneratedReport result =
                    service.generateReport(START, END);

            // One card change over the fixture, so one account total is written. Its ten units are
            // NOT added to the grand total: had they been, the grand total would read 31.00.
            assertThat(result.accountBreakCount()).isEqualTo(1);
            assertThat(result.reportLines().stream().filter(l -> l.startsWith("Account Total"))
                    .count()).isEqualTo(1L);
            assertThat(result.grandTotal()).isEqualByComparingTo(new BigDecimal("21.00"));
        }

        @Test
        @DisplayName("the run reports the page and account break counts it observed")
        void runReportsItsObservations() {
            stubLookups();
            stubRange(twentyAcrossTwoCards());

            GeneratedReport result =
                    service.generateReport(START, END);

            assertThat(result.pageCount()).isEqualTo(2);
            assertThat(result.accountBreakCount()).isEqualTo(1);
            assertThat(result.lineCount()).isEqualTo(34L);
            assertThat(result.reportLines()).hasSize(35);
        }
    }

    @Nested
    @DisplayName("page break placement: twenty counted lines, and two increments per page total")
    class PageBreakPlacement {

        @Test
        @DisplayName("the break fires after exactly twenty counted lines")
        void breakFiresAfterExactlyTwentyCountedLines() {
            stubLookups();
            List<Transaction> seventeen = new ArrayList<>();
            for (int ordinal = 1; ordinal <= 17; ordinal++) {
                seventeen.add(transaction(identifier(ordinal), CARD_A, "1.00", "2022-07-05"));
            }
            stubRange(seventeen);

            GeneratedReport result =
                    service.generateReport(START, END);

            // The page size is a legacy formatting contract, not a tuning value.
            assertThat(ReportLineFormatter.PAGE_SIZE).isEqualTo(20);

            // Four header records plus sixteen details is twenty counted lines; the seventeenth
            // record is the one that finds the counter on a page boundary.
            assertThat(result.reportLines().get(20)).startsWith("Page Total");
            assertThat(result.reportLines().get(21)).isEqualTo(ReportLineFormatter.buildRuleLine());
            assertThat(result.reportLines().get(22))
                    .isEqualTo(ReportLineFormatter.buildReportNameHeader(START, END));
        }

        @Test
        @DisplayName("the page-total routine increments twice and the grand-total line not at all")
        void pageTotalIncrementsTwiceAndGrandTotalNotAtAll() {
            stubLookups();
            List<Transaction> seventeen = new ArrayList<>();
            for (int ordinal = 1; ordinal <= 17; ordinal++) {
                seventeen.add(transaction(identifier(ordinal), CARD_A, "1.00", "2022-07-05"));
            }
            stubRange(seventeen);

            GeneratedReport result =
                    service.generateReport(START, END);

            // Thirty records are emitted but only twenty-nine are counted. The one difference is the
            // grand-total line, which the source writes without an increment because nothing can
            // follow it. Both page-total increments are needed for these two numbers to agree.
            assertThat(result.reportLines()).hasSize(30);
            assertThat(result.lineCount()).isEqualTo(29L);
            assertThat(result.reportLines().get(29)).startsWith("Grand Total");
        }

        @Test
        @DisplayName("the first record never emits a spurious leading page or account total")
        void firstRecordEmitsNoSpuriousTotal() {
            stubLookups();
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));

            GeneratedReport result =
                    service.generateReport(START, END);

            // The header block runs before the page test, so the counter is four and not zero when
            // the modulo is evaluated. Were the order reversed, a counter of zero would satisfy the
            // test and line one of the report would be a page total for a page with no content.
            assertThat(result.reportLines().get(0))
                    .isEqualTo(ReportLineFormatter.buildReportNameHeader(START, END));
            assertThat(result.reportLines().get(4)).startsWith(identifier(1));
            assertThat(result.accountBreakCount()).isZero();
        }
    }

    @Nested
    @DisplayName("the record width contract: every emitted line is 133 encoded bytes")
    class TheRecordWidthContract {

        @Test
        @DisplayName("every line measures 133 encoded bytes, blank line and rule line included")
        void everyLineMeasures133EncodedBytes() {
            stubLookups();
            stubRange(twentyAcrossTwoCards());

            GeneratedReport result =
                    service.generateReport(START, END);

            assertThat(result.reportLines()).isNotEmpty();
            for (String line : result.reportLines()) {
                // Measured in bytes and not in characters: the width is a byte contract, and the two
                // differ for any value outside US-ASCII.
                assertThat(line.getBytes(StandardCharsets.US_ASCII)).hasSize(RECORD_WIDTH);
            }
            assertThat(result.reportLines()).contains(ReportLineFormatter.BLANK_LINE);
            assertThat(result.reportLines()).contains(ReportLineFormatter.buildRuleLine());
        }

        @Test
        @DisplayName("the returned line list cannot be modified by a caller")
        void returnedLinesAreUnmodifiable() {
            stubLookups();
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));

            GeneratedReport result =
                    service.generateReport(START, END);
            List<String> lines = result.reportLines();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> lines.add("anything"));
        }
    }

    @Nested
    @DisplayName("the two masks: the detail amount and the totals are rendered differently")
    class TheTwoMasks {

        @Test
        @DisplayName("the same value renders differently under the detail and total masks")
        void sameValueRendersDifferentlyUnderEachMask() {
            BigDecimal one = new BigDecimal("1.00");

            // The detail mask carries a minus or a space; the totals mask is always signed. A single
            // shared mask would render one of the two wrongly and break byte parity.
            assertThat(ReportLineFormatter.renderDetailAmount(one))
                    .isNotEqualTo(ReportLineFormatter.renderTotalAmount(one));
            assertThat(ReportLineFormatter.renderTotalAmount(one)).startsWith("+");
            assertThat(ReportLineFormatter.renderDetailAmount(one)).startsWith(" ");
        }

        @Test
        @DisplayName("the detail line uses the detail mask and the totals use the total mask")
        void eachLineUsesItsOwnMask() {
            stubLookups();
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));

            GeneratedReport result =
                    service.generateReport(START, END);

            String detail = result.reportLines().get(4);
            String pageTotal = result.reportLines().get(5);
            assertThat(detail.substring(ReportLineFormatter.AMOUNT_OFFSET,
                    ReportLineFormatter.AMOUNT_OFFSET + ReportLineFormatter.AMOUNT_MASK_WIDTH))
                    .isEqualTo(ReportLineFormatter.renderDetailAmount(new BigDecimal("1.00")));
            assertThat(pageTotal).startsWith("Page Total");
            assertThat(pageTotal.substring(ReportLineFormatter.AMOUNT_OFFSET,
                    ReportLineFormatter.AMOUNT_OFFSET + ReportLineFormatter.AMOUNT_MASK_WIDTH))
                    .isEqualTo(ReportLineFormatter.renderTotalAmount(new BigDecimal("2.00")));
        }
    }

    @Nested
    @DisplayName("the date range: inclusive at both bounds, read through the shared formatter")
    class TheInclusiveDateRange {

        @Test
        @DisplayName("a record processed on either boundary date is reported")
        void bothBoundsAreInclusive() {
            stubLookups();
            stubRange(List.of(
                    transaction(identifier(1), CARD_A, "1.00", START),
                    transaction(identifier(2), CARD_A, "2.00", END)));

            GeneratedReport result =
                    service.generateReport(START, END);

            // Both survived the guard, so the loop was never truncated: four headers, two details,
            // then the at-end page total, its rule line and the grand total.
            assertThat(result.reportLines()).hasSize(9);
            assertThat(result.grandTotal()).isEqualByComparingTo(new BigDecimal("5.00"));
        }

        @Test
        @DisplayName("an eighty-character card image is accepted as the parameter record")
        void eightyCharacterCardIsAccepted() {
            stubLookups();
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));

            String card = ReportLineFormatter.buildDateParameterRecord(START, END) + " ".repeat(59);
            assertThat(card).hasSize(ReportLineFormatter.DATE_PARAMETER_CARD_WIDTH);

            GeneratedReport result =
                    service.generateReportFromDateParameterCard(card);

            assertThat(result.reportLines()).hasSize(8);
        }

        @Test
        @DisplayName("an absent parameter card is end of file, so the report is empty")
        void absentParameterCardProducesAnEmptyReport() {
            final ReportTransactionSource untouched = mock(ReportTransactionSource.class);

            final List<String> collected = new ArrayList<>();
            final GeneratedReport result = GeneratedReport.of(collected,
                    reportService.generateReportFromDateParameterCard(untouched, collected::add,
                            null));

            assertThat(result.reportLines()).isEmpty();
            assertThat(result.lineCount()).isZero();
            assertThat(result.grandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            verifyNoInteractions(untouched);
        }
    }

    @Nested
    @DisplayName("the frozen ordered source: sequential reads only, with no live re-query")
    class TheFrozenOrderedSource {

        @Test
        @DisplayName("the source is read once at each sequential position through end of file")
        void sourceIsReadSequentiallyThroughEndOfFile() {
            stubLookups();
            final ReportTransactionSource source = mock(ReportTransactionSource.class);
            final Transaction first = transaction(identifier(1), CARD_A, "1.00", "2022-07-05");
            when(source.readAt(0)).thenReturn(Optional.of(first));
            when(source.readAt(1)).thenReturn(Optional.empty());

            reportService.generateReport(source, record -> { }, START, END);

            InOrder reads = inOrder(source);
            reads.verify(source).readAt(0);
            reads.verify(source).readAt(1);
            verify(source, times(1)).readAt(0);
            verify(source, times(1)).readAt(1);
        }

        @Test
        @DisplayName("the supplied generation ordering is not re-applied")
        void orderingIsNotReapplied() {
            stubLookups();
            // Deliberately not in card order. The preceding batch step owns ordering; a service that
            // re-sorted the frozen generation would emit these two the other way round.
            stubRange(List.of(
                    transaction(identifier(9), CARD_B, "1.00", "2022-07-05"),
                    transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));

            GeneratedReport result =
                    service.generateReport(START, END);

            List<String> emittedIdentifiers = new ArrayList<>();
            for (String line : result.reportLines()) {
                if (line.startsWith("00000000000000")) {
                    emittedIdentifiers.add(line.substring(0,
                            ReportLineFormatter.TRANSACTION_ID_WIDTH));
                }
            }
            assertThat(emittedIdentifiers).containsExactly(identifier(9), identifier(1));
        }

        @Test
        @DisplayName("a source that reports no Optional is refused rather than silently truncated")
        void absentReadResultIsRefused() {
            final ReportTransactionSource broken = position -> null;

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> reportService.generateReport(broken, record -> { },
                            START, END))
                    .withMessageContaining("Optional");
        }
    }

    @Nested
    @DisplayName("the header block: four records, in the one legal order")
    class TheHeaderBlock {

        @Test
        @DisplayName("the block is exactly four records and matches the shared formatter's block")
        void blockIsExactlyFourRecords() {
            stubLookups();
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));

            GeneratedReport result =
                    service.generateReport(START, END);

            assertThat(ReportLineFormatter.HEADER_BLOCK_RECORD_COUNT).isEqualTo(4);
            assertThat(result.reportLines().subList(0, 4))
                    .isEqualTo(ReportLineFormatter.buildHeaderBlock(START, END));
        }

        @Test
        @DisplayName("a repeated block carries the same bounds, so every page header is identical")
        void repeatedBlockIsIdentical() {
            stubLookups();
            List<Transaction> seventeen = new ArrayList<>();
            for (int ordinal = 1; ordinal <= 17; ordinal++) {
                seventeen.add(transaction(identifier(ordinal), CARD_A, "1.00", "2022-07-05"));
            }
            stubRange(seventeen);

            GeneratedReport result =
                    service.generateReport(START, END);

            assertThat(result.reportLines().subList(22, 26))
                    .isEqualTo(result.reportLines().subList(0, 4));
        }
    }

    @Nested
    @DisplayName("the legacy anomalies, pinned so that a later fix registers as a regression")
    class TheLegacyAnomalies {

        @Test
        @DisplayName("an out-of-range record leaves the whole loop rather than skipping one record")
        void outOfRangeRecordTruncatesTheWholeLoop() {
            stubLookups();
            stubRange(List.of(
                    transaction(identifier(1), CARD_A, "1.00", "2022-07-05"),
                    transaction(identifier(2), CARD_A, "9.00", "2030-01-01")));

            GeneratedReport result =
                    service.generateReport(START, END);

            // Four headers and one detail, and then nothing at all: no page total, no rule line and
            // no grand total, because control left the loop for the closes. A per-record skip would
            // have produced the at-end totals.
            assertThat(result.reportLines()).hasSize(5);
            assertThat(result.lineCount()).isEqualTo(5L);
            assertThat(result.pageCount()).isZero();
            assertThat(result.grandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.reportLines()).noneMatch(line -> line.startsWith("Grand Total"));
        }

        @Test
        @DisplayName("the at-end path re-adds the stale amount of the last record")
        void atEndPathReAddsTheStaleAmount() {
            stubLookups();
            stubRange(List.of(
                    transaction(identifier(1), CARD_A, "10.00", "2022-07-05"),
                    transaction(identifier(2), CARD_A, "5.00", "2022-07-05")));

            GeneratedReport result =
                    service.generateReport(START, END);

            // Ten and five is fifteen; the stale five is added again at end of file.
            assertThat(result.grandTotal()).isEqualByComparingTo(new BigDecimal("20.00"));
        }

        @Test
        @DisplayName("an empty range still emits the at-end page total, rule line and grand total")
        void emptyRangeStillEmitsTheAtEndTotals() {
            stubLookups();
            stubRange(List.of());

            GeneratedReport result =
                    service.generateReport(START, END);

            // No record was ever read, so there is no stale amount to re-add and no header block was
            // written; the at-end arm still runs, exactly as the legacy loop does.
            assertThat(result.reportLines()).hasSize(3);
            assertThat(result.reportLines().get(0)).startsWith("Page Total");
            assertThat(result.reportLines().get(1)).isEqualTo(ReportLineFormatter.buildRuleLine());
            assertThat(result.reportLines().get(2)).startsWith("Grand Total");
            assertThat(result.grandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("the terminal path: diagnose, then abend")
    class TheTerminalPath {

        @Test
        @DisplayName("an absent cross-reference emits the status before abending")
        void absentCrossReferenceEmitsThenAbends() {
            when(types.findById("01")).thenReturn(
                    Optional.of(new TransactionType("01", "PURCHASE")));
            when(crossReferences.findById(CARD_A)).thenReturn(Optional.empty());
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));
            doThrow(new AbendException("CBTRN03C", "FILE STATUS 23"))
                    .when(abendService).abendBatch(any(), any(), any(), any(), any());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.generateReport(START, END));

            assertThat(logCapture.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message).doesNotContain(CARD_A));
            InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus("23", "READ", "CARDXREF");
            order.verify(abendService).abendBatch(eq("CBTRN03C"), any(), eq("23"), eq("READ"),
                    eq("CARDXREF"));
            assertThat(loggedMessages())
                    .anyMatch(message -> message.matches(
                            "INVALID CARD NUMBER cardRef=\\[REDACTED] ref=[0-9a-f]{24}"))
                    .noneMatch(message -> message.contains(CARD_A));
        }

        @Test
        @DisplayName("an absent transaction type emits the status before abending")
        void absentTransactionTypeEmitsThenAbends() {
            when(crossReferences.findById(CARD_A)).thenReturn(
                    Optional.of(new CardCrossReference(CARD_A, "000000001", "00000000011")));
            when(types.findById("01")).thenReturn(Optional.empty());
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));
            doThrow(new AbendException("CBTRN03C", "FILE STATUS 23"))
                    .when(abendService).abendBatch(any(), any(), any(), any(), any());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.generateReport(START, END));

            InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus("23", "READ", "TRANTYPE");
            order.verify(abendService).abendBatch(eq("CBTRN03C"), any(), eq("23"), eq("READ"),
                    eq("TRANTYPE"));
        }

        @Test
        @DisplayName("an absent transaction category emits the status before abending")
        void absentTransactionCategoryEmitsThenAbends() {
            when(crossReferences.findById(CARD_A)).thenReturn(
                    Optional.of(new CardCrossReference(CARD_A, "000000001", "00000000011")));
            when(types.findById("01")).thenReturn(
                    Optional.of(new TransactionType("01", "PURCHASE")));
            when(categories.findById(any(TransactionCategoryId.class)))
                    .thenReturn(Optional.empty());
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));
            doThrow(new AbendException("CBTRN03C", "FILE STATUS 23"))
                    .when(abendService).abendBatch(any(), any(), any(), any(), any());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.generateReport(START, END));

            InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus("23", "READ", "TRANCATG");
            order.verify(abendService).abendBatch(eq("CBTRN03C"), any(), eq("23"), eq("READ"),
                    eq("TRANCATG"));
        }
    }

    @Nested
    @DisplayName("the reference reads: bounded by distinct references, never by record count")
    class TheBoundedReferenceReads {

        @Test
        @DisplayName("twenty records over two cards, one type and one category issue two, one and one "
                + "reference reads - not twenty each, which is the one-query-per-row shape this replaces")
        void referenceReadsAreBoundedByDistinctReferences() {
            stubLookups();
            stubRange(twentyAcrossTwoCards());

            service.generateReport(START, END);

            assertAll(
                    () -> verify(crossReferences, times(1)).findById(CARD_A),
                    () -> verify(crossReferences, times(1)).findById(CARD_B),
                    () -> verify(types, times(1)).findById("01"),
                    () -> verify(categories, times(1))
                            .findById(any(TransactionCategoryId.class)));
        }

        @Test
        @DisplayName("doubling the record count does not change the number of reference reads, which is "
                + "what makes the cost independent of the input size")
        void referenceReadCountDoesNotGrowWithRecordCount() {
            stubLookups();
            stubRange(twentyAcrossTwoCards());
            service.generateReport(START, END);
            final int afterTwenty = referenceReadCount();

            stubRange(twentyAcrossTwoCards());
            service.generateReport(START, END);
            final int afterForty = referenceReadCount();

            // Two runs, not one long one: each run resolves its own references, so the second run's four
            // reads are the only growth and no run's cost depends on how many records it reported.
            assertThat(afterForty - afterTwenty).isEqualTo(afterTwenty);
        }

        @Test
        @DisplayName("a distinct category key is resolved on its own, so memoization keys on the whole "
                + "composite key and not on the type code alone")
        void eachDistinctCategoryKeyIsResolvedOnItsOwn() {
            when(crossReferences.findById(CARD_A)).thenReturn(
                    Optional.of(new CardCrossReference(CARD_A, "000000001", "00000000011")));
            when(types.findById("01")).thenReturn(Optional.of(new TransactionType("01", "PURCHASE")));
            when(categories.findById(new TransactionCategoryId("01", "0005")))
                    .thenReturn(Optional.of(new TransactionCategory("01", "0005", "RESTAURANT")));
            when(categories.findById(new TransactionCategoryId("01", "0006")))
                    .thenReturn(Optional.of(new TransactionCategory("01", "0006", "GROCERY")));
            stubRange(List.of(
                    transaction(identifier(1), CARD_A, "1.00", "2022-07-05"),
                    inCategory(transaction(identifier(2), CARD_A, "1.00", "2022-07-05"), "0006"),
                    transaction(identifier(3), CARD_A, "1.00", "2022-07-05")));

            service.generateReport(START, END);

            assertAll(
                    () -> verify(categories, times(1))
                            .findById(new TransactionCategoryId("01", "0005")),
                    () -> verify(categories, times(1))
                            .findById(new TransactionCategoryId("01", "0006")));
        }

        @Test
        @DisplayName("one run's resolved references are invisible to the next, because a cache that "
                + "outlived a run would be a snapshot the legacy step never had")
        void resolvedReferencesDoNotOutliveTheirRun() {
            stubLookups();
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));
            service.generateReport(START, END);

            stubRange(List.of(transaction(identifier(2), CARD_A, "1.00", "2022-07-05")));
            service.generateReport(START, END);

            assertAll(
                    () -> verify(crossReferences, times(2)).findById(CARD_A),
                    () -> verify(types, times(2)).findById("01"));
        }

        @Test
        @DisplayName("the FIRST missing reference is still the one that fails, on the record that first "
                + "presents it, so memoization has not deferred or masked an absent row")
        void theFirstMissingReferenceIsStillTheOneThatFails() {
            when(crossReferences.findById(CARD_A)).thenReturn(
                    Optional.of(new CardCrossReference(CARD_A, "000000001", "00000000011")));
            when(types.findById("01")).thenReturn(Optional.of(new TransactionType("01", "PURCHASE")));
            when(types.findById("02")).thenReturn(Optional.empty());
            when(categories.findById(any(TransactionCategoryId.class))).thenReturn(
                    Optional.of(new TransactionCategory("01", "0005", "RESTAURANT")));
            stubRange(List.of(
                    transaction(identifier(1), CARD_A, "1.00", "2022-07-05"),
                    ofType(transaction(identifier(2), CARD_A, "1.00", "2022-07-05"), "02"),
                    ofType(transaction(identifier(3), CARD_A, "1.00", "2022-07-05"), "02")));
            doThrow(new AbendException("CBTRN03C", "FILE STATUS 23"))
                    .when(abendService).abendBatch(any(), any(), any(), any(), any());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.generateReport(START, END));

            assertAll(
                    () -> verify(abendService, times(1))
                            .displayIoStatus("23", "READ", "TRANTYPE"),
                    () -> verify(types, times(1)).findById("02"));
        }

        /** Counts every reference read the three mocked lookups have received so far. */
        private int referenceReadCount() {
            return (int) Mockito.mockingDetails(crossReferences).getInvocations().stream().count()
                    + (int) Mockito.mockingDetails(types).getInvocations().stream().count()
                    + (int) Mockito.mockingDetails(categories).getInvocations().stream().count();
        }
    }

    /**
     * Proves the reference reads stay bounded when the number of DISTINCT keys is large.
     *
     * <p>The nest above proves the memo removes the one-query-per-<em>row</em> shape. It cannot prove the
     * remaining shape is bounded, because two cards, one type and one category are four reads whichever
     * strategy is used. The cost that was left was one query per distinct <em>key</em>, and the distinct-key
     * count grows with the report: a window over a large card estate resolves as many cross-references as it
     * has cards.
     *
     * <p>The three references are therefore treated according to what each is. Transaction types and
     * transaction categories are <strong>closed vocabularies</strong> - seven and eighteen rows, sized by
     * the estate and not by the report - so each is read in full, once, at the open of its own resource,
     * which is where the legacy member opens that file. The cross-reference cluster <strong>grows with the
     * card estate</strong>, so it is read in bounded batches over the cards the run's buffered lookahead
     * names. Recorded as {@code DL-294}.
     *
     * <p>What must not change, and is asserted separately above and below: an absent reference still abends
     * on the record that first presents it, because absence is never memoised.
     */
    @Nested
    @DisplayName("the reference reads at high distinct-key cardinality: bounded by the reference "
            + "vocabulary and by a fixed lookahead, never by the number of distinct keys")
    class TheBoundedReferenceReadsUnderCardinality {

        /** Cards the wide fixture spans, comfortably beyond one lookahead's worth of records. */
        private static final int DISTINCT_CARDS = 300;

        /** Records per card, so the fixture's record count is twice its card count. */
        private static final int RECORDS_PER_CARD = 2;

        /** Distinct type-and-category pairs, which is the whole transaction-category vocabulary. */
        private static final int DISTINCT_CATEGORY_KEYS = 18;

        /** Distinct type codes among those pairs, which is the whole transaction-type vocabulary. */
        private static final int DISTINCT_TYPE_CODES = 7;

        /**
         * The greatest number of batched cross-reference reads the wide fixture may issue.
         *
         * <p>Derived, not chosen: the run buffers at most 256 records, and the first miss inside a buffered
         * window resolves every card that window names, so the batch count is the record count divided by
         * the buffer depth. Six hundred records give three, and the bound is stated at twice that so an
         * incidental extra fill is not a failure while a per-distinct-key shape - three hundred of them -
         * is.
         */
        private static final int MAX_BATCHED_READS = 6;

        @Test
        @DisplayName("three hundred distinct cards, seven types and eighteen categories are resolved in a "
                + "handful of reads: two full reference reads and at most six batches, with NOT ONE keyed "
                + "read of any of the three")
        void referenceReadsDoNotGrowWithTheNumberOfDistinctKeys() {
            stubBoundedVocabularies();
            stubBatchedCrossReferences();
            final List<Transaction> wide = wideFixture();
            stubRange(wide);

            final GeneratedReport report = service.generateReport(START, END);

            assertAll(
                    () -> verify(types, times(1)).findAll(),
                    () -> verify(categories, times(1)).findAll(),
                    () -> verify(types, never()).findById(anyString()),
                    () -> verify(categories, never()).findById(any(TransactionCategoryId.class)),
                    () -> verify(crossReferences, never()).findById(anyString()));

            assertThat(batchedCrossReferenceReadCount())
                    .as("the batch count follows the buffer depth and the record count, not the card "
                            + "count: %s cards resolved in at most %s reads",
                            Integer.valueOf(DISTINCT_CARDS), Integer.valueOf(MAX_BATCHED_READS))
                    .isBetween(1, MAX_BATCHED_READS);

            final int distinctKeys = DISTINCT_CARDS + DISTINCT_TYPE_CODES + DISTINCT_CATEGORY_KEYS;
            assertThat(referenceReadCount())
                    .as("THE MEASUREMENT THAT MATTERS. One query per distinct key would be %s reads for "
                            + "this fixture. Every read the three stores received, of every kind, must be "
                            + "an order of magnitude below that, or the cost still grows with the report.",
                            Integer.valueOf(distinctKeys))
                    .isLessThan(distinctKeys / 10);

            // And the report is complete: every record was resolved and reported, so the bound was not
            // achieved by resolving fewer references than the run needed.
            final String emitted = String.join("", report.reportLines());
            assertThat(wide).allSatisfy(record ->
                    assertThat(emitted).contains(record.getTranId()));
        }

        @Test
        @DisplayName("a run reporting six hundred records and a run reporting one cost the same three "
                + "reads, and neither inherits the other's snapshot")
        void everyRunPaysTheSameBoundedCostAndInheritsNothing() {
            stubBoundedVocabularies();
            stubBatchedCrossReferences();

            stubRange(wideFixture());
            service.generateReport(START, END);
            final int afterTheWideRun = referenceReadCount();

            stubRange(List.of(vocabularyRecord(identifier(1), card(0), 0)));
            service.generateReport(START, END);

            assertAll(
                    () -> verify(types, times(2)).findAll(),
                    () -> verify(categories, times(2)).findAll());
            assertThat(referenceReadCount() - afterTheWideRun)
                    .as("EXACTLY three reads for the second run: each closed vocabulary once, and one "
                            + "batch for its single card. Two reads would mean a vocabulary was inherited "
                            + "from the first run - a snapshot the legacy step never had - and four or "
                            + "more would mean a keyed read survived.")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a type code the preloaded vocabulary does not carry still abends on the record that "
                + "presents it, because a preload memoises presence and never absence")
        void aCodeOutsideThePreloadedVocabularyStillAbends() {
            stubBoundedVocabularies();
            stubBatchedCrossReferences();
            when(types.findById("99")).thenReturn(Optional.empty());
            stubRange(List.of(
                    ofType(vocabularyRecord(identifier(1), card(0), 0), "99")));
            doThrow(new AbendException("CBTRN03C", "FILE STATUS 23"))
                    .when(abendService).abendBatch(any(), any(), any(), any(), any());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.generateReport(START, END));

            final InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus("23", "READ", "TRANTYPE");
            order.verify(abendService).abendBatch(eq("CBTRN03C"), eq("INVALID TRANSACTION TYPE"),
                    eq("23"), eq("READ"), eq("TRANTYPE"));
            // The keyed read still happened, which is the whole point: a code the preload did not supply
            // is looked up, found absent, and fails on its own record with its own resource's arm.
            verify(types, times(1)).findById("99");
        }

        /** Reads the whole transaction-type and transaction-category vocabularies, as the preload does. */
        private void stubBoundedVocabularies() {
            final List<TransactionType> allTypes = new ArrayList<>();
            for (int ordinal = 1; ordinal <= DISTINCT_TYPE_CODES; ordinal++) {
                allTypes.add(new TransactionType(typeCode(ordinal - 1), "TYPE " + ordinal));
            }
            final List<TransactionCategory> allCategories = new ArrayList<>();
            for (int ordinal = 0; ordinal < DISTINCT_CATEGORY_KEYS; ordinal++) {
                allCategories.add(new TransactionCategory(typeCode(ordinal), categoryCode(ordinal),
                        "CATEGORY " + ordinal));
            }
            when(types.findAll()).thenReturn(allTypes);
            when(categories.findAll()).thenReturn(allCategories);
        }

        /** Answers a batched cross-reference read with a row for every key the cluster holds. */
        private void stubBatchedCrossReferences() {
            when(crossReferences.findAllById(any())).thenAnswer(invocation -> {
                final Iterable<String> requested = invocation.getArgument(0);
                final List<CardCrossReference> found = new ArrayList<>();
                for (final String cardNumber : requested) {
                    found.add(new CardCrossReference(cardNumber, "000000001", "00000000011"));
                }
                return found;
            });
        }

        /** How many batched cross-reference reads the store has received. */
        private int batchedCrossReferenceReadCount() {
            return (int) Mockito.mockingDetails(crossReferences).getInvocations().stream()
                    .filter(invocation -> "findAllById".equals(invocation.getMethod().getName()))
                    .count();
        }

        /** Counts every read the three stores have received, of every kind. */
        private int referenceReadCount() {
            return Mockito.mockingDetails(crossReferences).getInvocations().size()
                    + Mockito.mockingDetails(types).getInvocations().size()
                    + Mockito.mockingDetails(categories).getInvocations().size();
        }

        /**
         * The wide fixture: {@value #RECORDS_PER_CARD} records on each of {@value #DISTINCT_CARDS} cards,
         * grouped by card as the job's own ordering delivers them, cycling through the whole
         * type-and-category vocabulary.
         *
         * @return the records, in card order
         */
        private List<Transaction> wideFixture() {
            final List<Transaction> records = new ArrayList<>();
            for (int cardOrdinal = 0; cardOrdinal < DISTINCT_CARDS; cardOrdinal++) {
                for (int repeat = 0; repeat < RECORDS_PER_CARD; repeat++) {
                    final int ordinal = records.size();
                    records.add(vocabularyRecord(identifier(ordinal + 1), card(cardOrdinal),
                            ordinal % DISTINCT_CATEGORY_KEYS));
                }
            }
            return List.copyOf(records);
        }

        /**
         * One record whose type and category are the pair at the given vocabulary position, so that the
         * preloaded vocabularies resolve it and no keyed read is provoked by a fixture accident.
         *
         * @param  transactionId the sixteen-character identifier
         * @param  cardNumber    the sixteen-character card-number field image
         * @param  position      the position in the eighteen-entry vocabulary, from zero
         * @return the record
         */
        private Transaction vocabularyRecord(final String transactionId, final String cardNumber,
                final int position) {

            return inCategory(
                    ofType(transaction(transactionId, cardNumber, "1.00", "2022-07-05"),
                            typeCode(position)),
                    categoryCode(position));
        }
    }

    /**
     * A sixteen-digit card number for the wide fixture.
     *
     * @param  ordinal the card's position, from zero
     * @return the sixteen-character field image
     */
    private static String card(final int ordinal) {
        return String.format(Locale.ROOT, "%016d", Long.valueOf(4_000_000_000_000_000L + ordinal));
    }

    /**
     * The two-character transaction-type code the given vocabulary position carries.
     *
     * @param  position the position in the eighteen-entry category vocabulary, from zero
     * @return the two-character code
     */
    private static String typeCode(final int position) {
        return String.format(Locale.ROOT, "%02d", Integer.valueOf(position % 7 + 1));
    }

    /**
     * The four-character transaction-category code the given vocabulary position carries.
     *
     * @param  position the position in the eighteen-entry category vocabulary, from zero
     * @return the four-character code
     */
    private static String categoryCode(final int position) {
        return String.format(Locale.ROOT, "%04d", Integer.valueOf(position + 1));
    }

    @Nested
    @DisplayName("a reference read that does not complete: raw status 31, then the paragraph's own "
            + "display-then-abend sequence")
    class ATechnicalReferenceFailure {

        @Test
        @DisplayName("the cross-reference read reports 31, displays the status and then abends, and the "
                + "card number still never reaches a diagnostic")
        void crossReferenceFailureReportsThirtyOne() {
            when(types.findById("01")).thenReturn(Optional.of(new TransactionType("01", "PURCHASE")));
            when(crossReferences.findById(CARD_A)).thenThrow(unreachable());
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));
            doThrow(new AbendException("CBTRN03C", "FILE STATUS 31"))
                    .when(abendService).abendBatch(any(), any(), any(), any(), any());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.generateReport(START, END));

            InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus("31", "READ", "CARDXREF");
            order.verify(abendService).abendBatch(eq("CBTRN03C"), eq("INVALID CARD NUMBER"), eq("31"),
                    eq("READ"), eq("CARDXREF"));
            assertThat(loggedMessages())
                    .anyMatch(message -> message.contains("Reference read of CARDXREF did not complete")
                            && message.contains("failureChain="))
                    .anyMatch(message -> message.matches(
                            "INVALID CARD NUMBER cardRef=\\[REDACTED] ref=[0-9a-f]{24}"))
                    .noneMatch(message -> message.contains(CARD_A));
        }

        @Test
        @DisplayName("the transaction-type read reports 31, displays the status and then abends")
        void transactionTypeFailureReportsThirtyOne() {
            when(crossReferences.findById(CARD_A)).thenReturn(
                    Optional.of(new CardCrossReference(CARD_A, "000000001", "00000000011")));
            when(types.findById("01")).thenThrow(unreachable());
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));
            doThrow(new AbendException("CBTRN03C", "FILE STATUS 31"))
                    .when(abendService).abendBatch(any(), any(), any(), any(), any());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.generateReport(START, END));

            InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus("31", "READ", "TRANTYPE");
            order.verify(abendService).abendBatch(eq("CBTRN03C"), eq("INVALID TRANSACTION TYPE"),
                    eq("31"), eq("READ"), eq("TRANTYPE"));
        }

        @Test
        @DisplayName("the transaction-category read reports 31, displays the status and then abends")
        void transactionCategoryFailureReportsThirtyOne() {
            when(crossReferences.findById(CARD_A)).thenReturn(
                    Optional.of(new CardCrossReference(CARD_A, "000000001", "00000000011")));
            when(types.findById("01")).thenReturn(Optional.of(new TransactionType("01", "PURCHASE")));
            when(categories.findById(any(TransactionCategoryId.class))).thenThrow(unreachable());
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));
            doThrow(new AbendException("CBTRN03C", "FILE STATUS 31"))
                    .when(abendService).abendBatch(any(), any(), any(), any(), any());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.generateReport(START, END));

            InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus("31", "READ", "TRANCATG");
            order.verify(abendService).abendBatch(eq("CBTRN03C"), eq("INVALID TRAN CATG KEY"),
                    eq("31"), eq("READ"), eq("TRANCATG"));
        }

        @Test
        @DisplayName("a read that did not complete is NOT reported as a missing record, because the two "
                + "conditions send an operator to different places")
        void aFailedReadIsNotReportedAsAMissingRecord() {
            when(crossReferences.findById(CARD_A)).thenThrow(unreachable());
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));
            doThrow(new AbendException("CBTRN03C", "FILE STATUS 31"))
                    .when(abendService).abendBatch(any(), any(), any(), any(), any());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.generateReport(START, END));

            verify(abendService, never()).displayIoStatus(eq("23"), any(), any());
        }

        @Test
        @DisplayName("the failure narrative never reaches a diagnostic whole; only its type chain does")
        void theFailureNarrativeNeverReachesADiagnostic() {
            when(crossReferences.findById(CARD_A))
                    .thenThrow(new DataAccessResourceFailureException(
                            "select * from card_xref_record where xref_card_num = '" + CARD_A + "'"));
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));
            doThrow(new AbendException("CBTRN03C", "FILE STATUS 31"))
                    .when(abendService).abendBatch(any(), any(), any(), any(), any());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.generateReport(START, END));

            assertThat(loggedMessages())
                    .noneMatch(message -> message.contains("select * from"))
                    .noneMatch(message -> message.contains(CARD_A));
        }
    }
    @Nested
    @DisplayName("statelessness: no run can influence another")
    class Statelessness {

        @Test
        @DisplayName("two successive runs on one instance produce identical reports")
        void successiveRunsAreIndependent() {
            stubLookups();
            stubRange(twentyAcrossTwoCards());

            final GeneratedReport first = service.generateReport(START, END);
            final GeneratedReport second = service.generateReport(START, END);

            // A counter or accumulator held as a field would make the second run differ from the
            // first, which is precisely the defect this asserts against.
            assertThat(second.reportLines()).isEqualTo(first.reportLines());
            assertThat(second.grandTotal()).isEqualByComparingTo(first.grandTotal());
            assertThat(second.lineCount()).isEqualTo(first.lineCount());
            assertThat(second.pageCount()).isEqualTo(first.pageCount());
            assertThat(second.accountBreakCount()).isEqualTo(first.accountBreakCount());
        }

        @Test
        @DisplayName("every collaborator is required at construction")
        void everyCollaboratorIsRequired() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionReportService(null, types, categories,
                            abendService));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionReportService(crossReferences, null, categories,
                            abendService));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionReportService(crossReferences, types, null,
                            abendService));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionReportService(crossReferences, types, categories,
                            null));
        }

        @Test
        @DisplayName("the result refuses an absent grand total or an impossible record count")
        void resultRefusesAnAbsentComponent() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionReportService.TransactionReportResult(0L, null,
                            0, 0L, 0));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    new TransactionReportService.TransactionReportResult(-1L, BigDecimal.ZERO,
                            0, 0L, 0));
        }

        @Test
        @DisplayName("a run refuses to start without a destination for its records")
        void aRunRefusesToStartWithoutADestination() {
            final ReportTransactionSource untouched = mock(ReportTransactionSource.class);

            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    reportService.generateReport(untouched, null, START, END))
                    .withMessageContaining("reportRecordSink");
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    reportService.generateReportFromDateParameterCard(untouched, null,
                            ReportLineFormatter.buildDateParameterRecord(START, END)))
                    .withMessageContaining("reportRecordSink");
            verifyNoInteractions(untouched);
        }
    }
}
