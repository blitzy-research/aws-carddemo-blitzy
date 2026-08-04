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
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategory;
import com.carddemo.domain.TransactionType;
import com.carddemo.domain.id.TransactionCategoryId;
import com.carddemo.exception.AbendException;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.util.ReportLineFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
 * period in the member is the one on the {@code END-PERFORM} at line 206, that arm leaves the whole
 * driving loop rather than skipping one record. Separately, the at-end path of lines 197-203 re-adds
 * the stale amount of the last record, so the final transaction is accumulated twice. Both are
 * reproduced deliberately, so both are pinned here: a future "fix" to either would be a parity
 * regression, not an improvement.
 *
 * <p>The repositories are mocked because the subject under test is the report's ordering, counting,
 * accumulation and break logic. The range predicate and the ordering belong to the repository and
 * are verified against a real database elsewhere; what is verified here is that this service calls
 * the finder exactly once and then leaves the result alone.
 */
class TransactionReportServiceParityTest {

    private static final String START = "2022-07-01";

    private static final String END = "2022-07-31";

    private static final String CARD_A = "4111111111111111";

    private static final String CARD_B = "4222222222222222";

    private static final int RECORD_WIDTH = 133;

    private final TransactionRepository transactions = mock(TransactionRepository.class);

    private final CardCrossReferenceRepository crossReferences =
            mock(CardCrossReferenceRepository.class);

    private final TransactionTypeRepository types = mock(TransactionTypeRepository.class);

    private final TransactionCategoryRepository categories =
            mock(TransactionCategoryRepository.class);

    private final AbendService abendService = mock(AbendService.class);

    private final TransactionReportService service = new TransactionReportService(
            transactions, crossReferences, types, categories, abendService);

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

    /**
     * Stubs the range finder to return the supplied content as a single complete slice, which is
     * what an unpaged request yields.
     */
    private void stubRange(List<Transaction> content) {
        Slice<Transaction> slice = new SliceImpl<>(content, Pageable.unpaged(), false);
        when(transactions.findByProcessingDateRange(eq(START), eq(END), any(Pageable.class)))
                .thenReturn(slice);
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

    /** Reads the fifteen-character masked amount back out of an emitted line. */
    private static BigDecimal amountOn(String line) {
        String masked = line.substring(ReportLineFormatter.AMOUNT_OFFSET,
                ReportLineFormatter.AMOUNT_OFFSET + ReportLineFormatter.AMOUNT_MASK_WIDTH);
        String digits = masked.replace(",", "").replace(" ", "").replace("+", "");
        return digits.isEmpty() ? BigDecimal.ZERO : new BigDecimal(digits);
    }

    @Nested
    @DisplayName("the accumulation chain: amounts reach the grand total only through page totals")
    class TheAccumulationChain {

        @Test
        @DisplayName("the grand total is the sum of the page totals, not a direct sum of details")
        void grandTotalIsFedOnlyFromPageTotals() {
            stubLookups();
            stubRange(twentyAcrossTwoCards());

            TransactionReportService.TransactionReportResult result =
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

            TransactionReportService.TransactionReportResult result =
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

            TransactionReportService.TransactionReportResult result =
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

            TransactionReportService.TransactionReportResult result =
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

            TransactionReportService.TransactionReportResult result =
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

            TransactionReportService.TransactionReportResult result =
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

            TransactionReportService.TransactionReportResult result =
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

            TransactionReportService.TransactionReportResult result =
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

            TransactionReportService.TransactionReportResult result =
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

            TransactionReportService.TransactionReportResult result =
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

            TransactionReportService.TransactionReportResult result =
                    service.generateReportFromDateParameterCard(card);

            assertThat(result.reportLines()).hasSize(8);
        }

        @Test
        @DisplayName("an absent parameter card is end of file, so the report is empty")
        void absentParameterCardProducesAnEmptyReport() {
            TransactionReportService.TransactionReportResult result =
                    service.generateReportFromDateParameterCard(null);

            assertThat(result.reportLines()).isEmpty();
            assertThat(result.lineCount()).isZero();
            assertThat(result.grandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(transactions, never()).findByProcessingDateRange(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("the single repository call: one invocation, and the result is left alone")
    class TheSingleRepositoryCall {

        @Test
        @DisplayName("the finder is invoked once with two ten-character bounds")
        void finderIsInvokedOnceWithTenCharacterBounds() {
            stubLookups();
            stubRange(twentyAcrossTwoCards());

            service.generateReport(START, END);

            ArgumentCaptor<String> lower = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> upper = ArgumentCaptor.forClass(String.class);
            verify(transactions, times(1))
                    .findByProcessingDateRange(lower.capture(), upper.capture(),
                            any(Pageable.class));
            assertThat(lower.getValue()).isEqualTo(START).hasSize(ReportLineFormatter.DATE_WIDTH);
            assertThat(upper.getValue()).isEqualTo(END).hasSize(ReportLineFormatter.DATE_WIDTH);
        }

        @Test
        @DisplayName("the repository's ordering is not re-applied")
        void orderingIsNotReapplied() {
            stubLookups();
            // Deliberately not in card order. The repository fixes the ordering; a service that
            // re-sorted would emit these two the other way round.
            stubRange(List.of(
                    transaction(identifier(9), CARD_B, "1.00", "2022-07-05"),
                    transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));

            TransactionReportService.TransactionReportResult result =
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
        @DisplayName("an incomplete slice is refused rather than silently truncating the report")
        void incompleteSliceIsRefused() {
            stubLookups();
            Slice<Transaction> partial = new SliceImpl<>(
                    List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")),
                    Pageable.ofSize(1), true);
            when(transactions.findByProcessingDateRange(eq(START), eq(END), any(Pageable.class)))
                    .thenReturn(partial);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.generateReport(START, END))
                    .withMessageContaining("unpaged");
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

            TransactionReportService.TransactionReportResult result =
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

            TransactionReportService.TransactionReportResult result =
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

            TransactionReportService.TransactionReportResult result =
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

            TransactionReportService.TransactionReportResult result =
                    service.generateReport(START, END);

            // Ten and five is fifteen; the stale five is added again at end of file.
            assertThat(result.grandTotal()).isEqualByComparingTo(new BigDecimal("20.00"));
        }

        @Test
        @DisplayName("an empty range still emits the at-end page total, rule line and grand total")
        void emptyRangeStillEmitsTheAtEndTotals() {
            stubLookups();
            stubRange(List.of());

            TransactionReportService.TransactionReportResult result =
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

            InOrder order = inOrder(abendService);
            order.verify(abendService).displayIoStatus("23", "READ", "CARDXREF");
            order.verify(abendService).abendBatch(eq("CBTRN03C"), any(), eq("23"), eq("READ"),
                    eq("CARDXREF"));
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
    @DisplayName("statelessness: no run can influence another")
    class Statelessness {

        @Test
        @DisplayName("two successive runs on one instance produce identical reports")
        void successiveRunsAreIndependent() {
            stubLookups();
            stubRange(twentyAcrossTwoCards());

            TransactionReportService.TransactionReportResult first =
                    service.generateReport(START, END);
            TransactionReportService.TransactionReportResult second =
                    service.generateReport(START, END);

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
                    new TransactionReportService(null, crossReferences, types, categories,
                            abendService));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionReportService(transactions, null, types, categories,
                            abendService));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionReportService(transactions, crossReferences, null, categories,
                            abendService));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionReportService(transactions, crossReferences, types, null,
                            abendService));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionReportService(transactions, crossReferences, types, categories,
                            null));
        }

        @Test
        @DisplayName("the result refuses an absent line list or grand total")
        void resultRefusesAnAbsentComponent() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionReportService.TransactionReportResult(null, BigDecimal.ZERO,
                            0, 0L, 0));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionReportService.TransactionReportResult(List.of(), null,
                            0, 0L, 0));
        }
    }
}
