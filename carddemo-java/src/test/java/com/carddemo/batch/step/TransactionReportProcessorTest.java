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
package com.carddemo.batch.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.carddemo.service.AbendService;
import com.carddemo.service.TransactionReportService;
import com.carddemo.service.TransactionReportService.TransactionReportResult;
import com.carddemo.util.ReportLineFormatter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

/**
 * Verifies the per-item contract of the transaction detail report stage: that one date-parameter
 * card produces one whole report, that the inclusive reporting range is applied as a character
 * comparison at both bounds, that the exact record sequence and the accumulation chain of the legacy
 * program survive the delegation, and that every record handed on is exactly
 * {@link ReportLineFormatter#REPORT_RECORD_WIDTH} encoded US-ASCII bytes wide.
 *
 * <p>The suite drives a <strong>real</strong> {@link TransactionReportService} over mocked
 * repositories rather than a mocked service, because the properties under test - bound inclusivity,
 * header order, the page break and the accumulation chain - are properties of the report that the
 * processor is required to preserve, and a mocked generator would assert nothing about them. A
 * mocked generator is used only where a real one cannot reach the condition: the width and purity
 * postconditions, an absent result and a failed generation.
 *
 * <p>Widths are asserted on <strong>encoded bytes</strong> and never on character counts, because a
 * report record is a byte contract: a single character outside the seven-bit range satisfies a
 * character count while breaching the width the fixed-length dataset declares.
 *
 * <p>The daily-transaction fixture shipped with the estate carries a single processing date and
 * therefore cannot demonstrate either bound, so the range fixtures here are constructed with
 * deliberately varied processing dates - one exactly on the start bound, one exactly on the end
 * bound, one just below and one just above.
 *
 * <p>No reflection is used anywhere and no static call is mocked, in keeping with the rest of the
 * test estate. That the stage declares no comparator, no card-number parser, no secondary sort key
 * and no mutable state is therefore proved behaviourally: the order handed out equals the order
 * supplied, and two consecutive invocations over one card produce byte-identical reports.
 *
 * <p>Provenance of the expectations: the report program, its report layout copybook, the report job
 * and the report procedure at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is
 * transcribed here.
 */
@DisplayName("TransactionReportProcessor - inclusive range, exact line sequence and 133-byte records")
class TransactionReportProcessorTest {

    /** Inclusive lower bound of every fixture range, ten characters in fixed ISO form. */
    private static final String START = "2022-07-01";

    /** Inclusive upper bound of every fixture range, ten characters in fixed ISO form. */
    private static final String END = "2022-07-31";

    /** First card of the fixtures, sixteen characters and treated as characters throughout. */
    private static final String CARD_A = "4111111111111111";

    /** Second card of the fixtures, used to provoke the account break. */
    private static final String CARD_B = "4222222222222222";

    /** The one transaction type the fixtures use. */
    private static final String TYPE_CODE = "01";

    /** The one transaction category the fixtures use. */
    private static final String CATEGORY_CODE = "0005";

    /** The contracted record width, taken from the layout authority rather than restated. */
    private static final int RECORD_WIDTH = ReportLineFormatter.REPORT_RECORD_WIDTH;

    /**
     * Number of details the first page of the report holds.
     *
     * <p>Derived rather than guessed: the header block advances the line counter by four before the
     * first detail is written, and the page break fires when the counter modulo the layout's page
     * size is zero, so the first page carries the page size less those four records.
     */
    private static final int DETAILS_ON_FIRST_PAGE =
            ReportLineFormatter.PAGE_SIZE - ReportLineFormatter.HEADER_BLOCK_RECORD_COUNT;

    private final TransactionRepository transactions = mock(TransactionRepository.class);

    private final CardCrossReferenceRepository crossReferences =
            mock(CardCrossReferenceRepository.class);

    private final TransactionTypeRepository types = mock(TransactionTypeRepository.class);

    private final TransactionCategoryRepository categories =
            mock(TransactionCategoryRepository.class);

    private final AbendService abendService = mock(AbendService.class);

    private final TransactionReportService service = new TransactionReportService(
            transactions, crossReferences, types, categories, abendService);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final TransactionReportProcessor processor =
            new TransactionReportProcessor(service, registry);

    /** The twenty-one byte structured form of the date-parameter card. */
    private static String structuredCard() {
        return ReportLineFormatter.buildDateParameterRecord(START, END);
    }

    /** The eighty-column card image form, whose leading bytes are the structured form. */
    private static String cardImage() {
        return structuredCard() + " ".repeat(
                ReportLineFormatter.DATE_PARAMETER_CARD_WIDTH
                        - ReportLineFormatter.DATE_PARAMETER_STRUCTURED_WIDTH);
    }

    /**
     * Builds one transaction at the widths the transaction record layout declares. Only the fields
     * the report reads carry meaningful values; the rest are present because the layout requires
     * them.
     */
    private static Transaction transaction(final String id, final String card, final String amount,
            final String processed) {
        return new Transaction(id, TYPE_CODE, CATEGORY_CODE, "POS TERM", "PURCHASE AT MERCHANT",
                new BigDecimal(amount), "000000001", "MERCHANT", "CITY", "1234567890",
                card, processed + "-00.00.00.000000", processed + "-00.00.00.000000");
    }

    /** Zero-filled sixteen-character transaction identifier, as the layout carries it. */
    private static String identifier(final int ordinal) {
        StringBuilder digits = new StringBuilder(Integer.toString(ordinal));
        while (digits.length() < ReportLineFormatter.TRANSACTION_ID_WIDTH) {
            digits.insert(0, '0');
        }
        return digits.toString();
    }

    /** Stubs the three lookups the report performs, so no description is ever invented. */
    private void stubLookups() {
        when(crossReferences.findById(CARD_A)).thenReturn(
                Optional.of(new CardCrossReference(CARD_A, "000000001", "00000000011")));
        when(crossReferences.findById(CARD_B)).thenReturn(
                Optional.of(new CardCrossReference(CARD_B, "000000002", "00000000022")));
        when(types.findById(TYPE_CODE)).thenReturn(
                Optional.of(new TransactionType(TYPE_CODE, "PURCHASE")));
        when(categories.findById(any(TransactionCategoryId.class))).thenReturn(
                Optional.of(new TransactionCategory(TYPE_CODE, CATEGORY_CODE, "RESTAURANT")));
    }

    /** Stubs the ordered range as one complete slice, which is what an unpaged request yields. */
    private void stubRange(final List<Transaction> content) {
        Slice<Transaction> slice = new SliceImpl<>(content, Pageable.unpaged(), false);
        when(transactions.findByProcessingDateRange(eq(START), eq(END), any(Pageable.class)))
                .thenReturn(slice);
    }

    /** {@code count} transactions of one currency unit each, all on one card and one date. */
    private static List<Transaction> onOneCard(final int count, final String processed) {
        List<Transaction> all = new ArrayList<>();
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            all.add(transaction(identifier(ordinal), CARD_A, "1.00", processed));
        }
        return all;
    }

    /** The records of a report whose first sixteen characters are a transaction identifier. */
    private static List<String> detailsOf(final TransactionReportResult result) {
        List<String> details = new ArrayList<>();
        for (String line : result.reportLines()) {
            if (Character.isDigit(line.charAt(0))) {
                details.add(line);
            }
        }
        return details;
    }

    /** The records of a report that begin with the supplied label. */
    private static List<String> labelled(final TransactionReportResult result, final String label) {
        List<String> matches = new ArrayList<>();
        for (String line : result.reportLines()) {
            if (line.startsWith(label)) {
                matches.add(line);
            }
        }
        return matches;
    }

    /** The fifteen-character masked amount field of an emitted record. */
    private static String amountField(final String line) {
        return line.substring(ReportLineFormatter.AMOUNT_OFFSET,
                ReportLineFormatter.AMOUNT_OFFSET + ReportLineFormatter.AMOUNT_MASK_WIDTH);
    }

    /** The signed decimal value carried by a masked amount field. */
    private static BigDecimal amountOn(final String line) {
        String digits = amountField(line).replace(",", "").replace(" ", "").replace("+", "");
        return digits.isEmpty() ? BigDecimal.ZERO : new BigDecimal(digits);
    }

    /** A generator that yields exactly the supplied records, used for postcondition tests. */
    private TransactionReportProcessor processorYielding(final List<String> reportLines) {
        TransactionReportService stubbed = mock(TransactionReportService.class);
        when(stubbed.generateReportFromDateParameterCard(any())).thenReturn(
                new TransactionReportResult(reportLines, BigDecimal.ZERO, 0, 0L, 0));
        return new TransactionReportProcessor(stubbed, registry);
    }

    @Nested
    @DisplayName("the item contract: one card in, one whole report out, never filtered")
    class TheItemContract {

        @Test
        @DisplayName("a null card is rejected, because an empty dataset yields no item at all")
        void nullCardIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> processor.process(null))
                    .withMessageContaining(TransactionReportProcessor.LEGACY_DD_DATEPARM);
        }

        @Test
        @DisplayName("a card of an illegal width fails through the established reader, untruncated")
        void malformedCardWidthIsRejectedBeforeAnyGeneration() {
            TransactionReportService untouched = mock(TransactionReportService.class);
            TransactionReportProcessor guarded =
                    new TransactionReportProcessor(untouched, registry);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> guarded.process(START + " " + END.substring(0, 9)));

            verifyNoInteractions(untouched);
        }

        @Test
        @DisplayName("both legal card widths produce byte-identical reports")
        void structuredRecordAndCardImageAgree() {
            stubLookups();
            stubRange(onOneCard(3, "2022-07-05"));

            TransactionReportResult fromStructured = processor.process(structuredCard());
            TransactionReportResult fromImage = processor.process(cardImage());

            assertThat(fromImage.reportLines()).isEqualTo(fromStructured.reportLines());
        }

        @Test
        @DisplayName("a result is always returned, never null, so the report is never filtered")
        void resultIsNeverNull() {
            stubLookups();
            stubRange(onOneCard(1, "2022-07-05"));

            assertThat(processor.process(structuredCard())).isNotNull();
        }

        @Test
        @DisplayName("a range no transaction falls into still produces its totals, not a failure")
        void emptyRangeIsNotAFailure() {
            stubLookups();
            stubRange(List.of());

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(detailsOf(result)).isEmpty();
            assertThat(labelled(result, "Grand Total")).hasSize(1);
        }

        @Test
        @DisplayName("a generator that reports no result at all is a breach, not an empty report")
        void absentResultIsRejected() {
            TransactionReportService silent = mock(TransactionReportService.class);
            when(silent.generateReportFromDateParameterCard(any())).thenReturn(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionReportProcessor(silent, registry)
                            .process(structuredCard()))
                    .withMessageContaining(START)
                    .withMessageContaining(END);
        }

        @Test
        @DisplayName("an unresolved lookup abends rather than inventing a description")
        void unresolvedLookupPropagatesRatherThanDefaulting() {
            when(types.findById(TYPE_CODE)).thenReturn(
                    Optional.of(new TransactionType(TYPE_CODE, "PURCHASE")));
            when(crossReferences.findById(CARD_A)).thenReturn(Optional.empty());
            stubRange(onOneCard(1, "2022-07-05"));
            doThrow(new AbendException(TransactionReportProcessor.LEGACY_PROGRAM,
                    "FILE STATUS 23"))
                    .when(abendService).abendBatch(any(), any(), any(), any(), any());

            // The terminal path is the delegate's: it diagnoses the raw status and then abends. This
            // stage neither catches it, nor translates it, nor substitutes a default description for
            // the record it could not resolve.
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> processor.process(structuredCard()));
        }

        @Test
        @DisplayName("both collaborators are required at construction")
        void collaboratorsAreRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionReportProcessor(null, registry));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionReportProcessor(service, null));
        }
    }

    @Nested
    @DisplayName("the reporting range: character comparison, inclusive at both bounds")
    class TheReportingRange {

        @Test
        @DisplayName("a transaction dated exactly on the start bound is included")
        void startBoundIsInclusive() {
            stubLookups();
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", START)));

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(detailsOf(result)).hasSize(1);
            assertThat(detailsOf(result).get(0)).startsWith(identifier(1));
        }

        @Test
        @DisplayName("a transaction dated exactly on the end bound is included")
        void endBoundIsInclusive() {
            stubLookups();
            stubRange(List.of(transaction(identifier(2), CARD_A, "1.00", END)));

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(detailsOf(result)).hasSize(1);
            assertThat(detailsOf(result).get(0)).startsWith(identifier(2));
        }

        @Test
        @DisplayName("both bounds and an interior date are reported together, in supplied order")
        void bothBoundsAndTheInteriorAreReported() {
            stubLookups();
            stubRange(List.of(
                    transaction(identifier(1), CARD_A, "1.00", START),
                    transaction(identifier(2), CARD_A, "1.00", "2022-07-15"),
                    transaction(identifier(3), CARD_A, "1.00", END)));

            TransactionReportResult result = processor.process(structuredCard());

            List<String> details = detailsOf(result);
            assertThat(details).hasSize(3);
            assertThat(details.get(0)).startsWith(identifier(1));
            assertThat(details.get(1)).startsWith(identifier(2));
            assertThat(details.get(2)).startsWith(identifier(3));
        }

        @Test
        @DisplayName("a transaction one day above the end bound is excluded")
        void justAboveTheEndBoundIsExcluded() {
            stubLookups();
            stubRange(List.of(
                    transaction(identifier(1), CARD_A, "1.00", "2022-07-15"),
                    transaction(identifier(9), CARD_A, "1.00", "2022-08-01")));

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(result.reportLines()).noneMatch(line -> line.startsWith(identifier(9)));
            assertThat(detailsOf(result)).hasSize(1);
        }

        @Test
        @DisplayName("a transaction one day below the start bound is excluded")
        void justBelowTheStartBoundIsExcluded() {
            stubLookups();
            stubRange(List.of(transaction(identifier(8), CARD_A, "1.00", "2022-06-30")));

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(result.reportLines()).noneMatch(line -> line.startsWith(identifier(8)));
            assertThat(detailsOf(result)).isEmpty();
        }

        @Test
        @DisplayName("the bounds are echoed into the header exactly as the card carried them")
        void boundsReachTheHeaderUnaltered() {
            stubLookups();
            stubRange(onOneCard(1, "2022-07-05"));

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(result.reportLines().get(0))
                    .isEqualTo(ReportLineFormatter.buildReportNameHeader(START, END));
        }
    }

    @Nested
    @DisplayName("the exact record sequence: four-record header block, page break, account break")
    class TheRecordSequence {

        @Test
        @DisplayName("the report opens with the four header records in their one legal order")
        void headerBlockOpensTheReportInOrder() {
            stubLookups();
            stubRange(onOneCard(1, "2022-07-05"));

            TransactionReportResult result = processor.process(structuredCard());
            List<String> block = ReportLineFormatter.buildHeaderBlock(START, END);

            assertThat(result.reportLines()
                    .subList(0, ReportLineFormatter.HEADER_BLOCK_RECORD_COUNT)).isEqualTo(block);
            assertThat(block).hasSize(4);
            assertThat(block.get(0)).startsWith("DALYREPT");
            assertThat(block.get(1)).isBlank();
            assertThat(block.get(2)).startsWith("Transaction ID");
            assertThat(block.get(3)).isEqualTo("-".repeat(RECORD_WIDTH));
        }

        @Test
        @DisplayName("a page that exactly fills does not break, so one header block is emitted")
        void aFullFirstPageDoesNotBreak() {
            stubLookups();
            stubRange(onOneCard(DETAILS_ON_FIRST_PAGE, "2022-07-05"));

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(detailsOf(result)).hasSize(DETAILS_ON_FIRST_PAGE);
            assertThat(labelled(result, "DALYREPT")).hasSize(1);
            // The single page total is the end-of-input flush, not a break.
            assertThat(result.pageCount()).isEqualTo(1);
            assertThat(labelled(result, "Page Total")).hasSize(1);
        }

        @Test
        @DisplayName("one detail past the boundary breaks the page and re-emits the header block")
        void oneDetailPastTheBoundaryBreaksThePage() {
            stubLookups();
            stubRange(onOneCard(DETAILS_ON_FIRST_PAGE + 1, "2022-07-05"));

            TransactionReportResult result = processor.process(structuredCard());
            List<String> lines = result.reportLines();
            List<String> block = ReportLineFormatter.buildHeaderBlock(START, END);
            int firstBreak = ReportLineFormatter.HEADER_BLOCK_RECORD_COUNT + DETAILS_ON_FIRST_PAGE;

            assertThat(result.pageCount()).isEqualTo(2);
            assertThat(labelled(result, "DALYREPT")).hasSize(2);
            assertThat(lines.get(firstBreak)).startsWith("Page Total");
            assertThat(lines.get(firstBreak + 1)).isEqualTo(ReportLineFormatter.buildRuleLine());
            assertThat(lines.subList(firstBreak + 2, firstBreak + 2 + block.size()))
                    .isEqualTo(block);
            assertThat(lines.get(lines.size() - 1)).startsWith("Grand Total");
        }

        @Test
        @DisplayName("a change of card number emits the prior account total before the new card")
        void accountTotalPrecedesTheNewCard() {
            stubLookups();
            List<Transaction> both = new ArrayList<>(onOneCard(2, "2022-07-05"));
            both.add(transaction(identifier(3), CARD_B, "1.00", "2022-07-05"));
            stubRange(both);

            TransactionReportResult result = processor.process(structuredCard());
            List<String> lines = result.reportLines();
            int accountTotalIndex = -1;
            int newCardDetailIndex = -1;
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).startsWith("Account Total")) {
                    accountTotalIndex = index;
                }
                if (lines.get(index).startsWith(identifier(3))) {
                    newCardDetailIndex = index;
                }
            }

            assertThat(result.accountBreakCount()).isEqualTo(1);
            assertThat(accountTotalIndex).isGreaterThan(0);
            assertThat(accountTotalIndex).isLessThan(newCardDetailIndex);
            assertThat(lines.get(accountTotalIndex + 1))
                    .isEqualTo(ReportLineFormatter.buildRuleLine());
        }

        @Test
        @DisplayName("the first card never emits a leading account total for nothing")
        void theFirstCardEmitsNoLeadingAccountTotal() {
            stubLookups();
            stubRange(onOneCard(3, "2022-07-05"));

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(result.accountBreakCount()).isZero();
            assertThat(labelled(result, "Account Total")).isEmpty();
        }

        @Test
        @DisplayName("the end of the input flushes the final page total and then the grand total")
        void endOfInputFlushesTheFinalTotals() {
            stubLookups();
            stubRange(onOneCard(2, "2022-07-05"));

            TransactionReportResult result = processor.process(structuredCard());
            List<String> lines = result.reportLines();

            assertThat(lines.get(lines.size() - 3)).startsWith("Page Total");
            assertThat(lines.get(lines.size() - 2))
                    .isEqualTo(ReportLineFormatter.buildRuleLine());
            assertThat(lines.get(lines.size() - 1)).startsWith("Grand Total");
        }

        @Test
        @DisplayName("records leave in the order they were supplied; nothing is re-sorted here")
        void suppliedOrderSurvives() {
            stubLookups();
            stubRange(List.of(
                    transaction(identifier(3), CARD_A, "1.00", "2022-07-05"),
                    transaction(identifier(1), CARD_A, "1.00", "2022-07-06"),
                    transaction(identifier(2), CARD_A, "1.00", "2022-07-07")));

            List<String> details = detailsOf(processor.process(structuredCard()));

            assertThat(details.get(0)).startsWith(identifier(3));
            assertThat(details.get(1)).startsWith(identifier(1));
            assertThat(details.get(2)).startsWith(identifier(2));
        }
    }

    @Nested
    @DisplayName("the accumulation chain: amounts reach the grand total only through page totals")
    class TheAccumulationChain {

        @Test
        @DisplayName("the grand total equals the sum of the page totals, not the sum of details")
        void grandTotalIsFedOnlyFromPageTotals() {
            stubLookups();
            stubRange(onOneCard(DETAILS_ON_FIRST_PAGE + 1, "2022-07-05"));

            TransactionReportResult result = processor.process(structuredCard());

            BigDecimal foldedFromPages = BigDecimal.ZERO;
            for (String line : labelled(result, "Page Total")) {
                foldedFromPages = foldedFromPages.add(amountOn(line));
            }

            assertThat(foldedFromPages).isEqualByComparingTo(result.grandTotal());
            // Seventeen details of one unit each. A grand total fed straight from details would read
            // 17.00; the faithful chain folds page totals and re-adds the stale last amount at the
            // end of the input, which is what makes this assertion able to fail.
            assertThat(result.grandTotal()).isEqualByComparingTo(new BigDecimal("18.00"));
            assertThat(result.grandTotal()).isNotEqualByComparingTo(new BigDecimal("17.00"));
            assertThat(amountOn(labelled(result, "Grand Total").get(0)))
                    .isEqualByComparingTo(result.grandTotal());
        }

        @Test
        @DisplayName("an account total resets only itself and never reaches the grand total")
        void accountTotalNeverReachesTheGrandTotal() {
            stubLookups();
            List<Transaction> both = new ArrayList<>(onOneCard(10, "2022-07-05"));
            for (int ordinal = 11; ordinal <= 20; ordinal++) {
                both.add(transaction(identifier(ordinal), CARD_B, "1.00", "2022-07-05"));
            }
            stubRange(both);

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(amountOn(labelled(result, "Account Total").get(0)))
                    .isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(result.grandTotal()).isEqualByComparingTo(new BigDecimal("21.00"));
            // Had the account total been folded in as well, the grand total would read 31.00.
            assertThat(result.grandTotal()).isNotEqualByComparingTo(new BigDecimal("31.00"));
        }

        @Test
        @DisplayName("a page total is emitted at the scale the amount fields carry, never rescaled")
        void pageTotalKeepsTheAmountScale() {
            stubLookups();
            stubRange(List.of(transaction(identifier(1), CARD_A, "12.34", "2022-07-05")));

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(amountOn(detailsOf(result).get(0)))
                    .isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(result.grandTotal().scale()).isEqualTo(2);
            assertThat(result.grandTotal()).isEqualByComparingTo(new BigDecimal("24.68"));
        }
    }

    @Nested
    @DisplayName("the fixed-width proof: every record exactly 133 encoded US-ASCII bytes")
    class TheFixedWidthProof {

        @Test
        @DisplayName("every record of a multi-page, multi-card report measures the contracted width")
        void everyRecordMeasuresTheContractedWidth() {
            stubLookups();
            List<Transaction> both = new ArrayList<>(onOneCard(10, "2022-07-05"));
            for (int ordinal = 11; ordinal <= 20; ordinal++) {
                both.add(transaction(identifier(ordinal), CARD_B, "1.00", "2022-07-05"));
            }
            stubRange(both);

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(result.reportLines()).isNotEmpty();
            for (String line : result.reportLines()) {
                assertThat(line.getBytes(StandardCharsets.US_ASCII).length)
                        .isEqualTo(TransactionReportProcessor.REPORT_RECORD_LENGTH);
            }
            assertThat(TransactionReportProcessor.REPORT_RECORD_LENGTH).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("a mis-sized record is refused rather than handed on")
        void misSizedRecordIsRefused() {
            TransactionReportProcessor narrow =
                    processorYielding(List.of(" ".repeat(RECORD_WIDTH - 1)));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> narrow.process(structuredCard()))
                    .withMessageContaining(Integer.toString(RECORD_WIDTH - 1))
                    .withMessageContaining(Integer.toString(RECORD_WIDTH));
        }

        @Test
        @DisplayName("a record the charset cannot represent is refused, though its byte count fits")
        void impureRecordIsRefused() {
            String impure = "\u00e9" + " ".repeat(RECORD_WIDTH - 1);
            TransactionReportProcessor tainted = processorYielding(List.of(impure));

            // The substitution byte keeps the count right, which is why purity is proved first.
            assertThat(impure.getBytes(StandardCharsets.US_ASCII).length).isEqualTo(RECORD_WIDTH);
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> tainted.process(structuredCard()))
                    .withMessageContaining("US-ASCII")
                    .withMessageContaining("position 0");
        }

        @Test
        @DisplayName("a zero amount blanks the whole amount field rather than rendering a zero")
        void zeroAmountBlanksTheField() {
            stubLookups();
            stubRange(List.of(transaction(identifier(1), CARD_A, "0.00", "2022-07-05")));

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(amountField(detailsOf(result).get(0))).isBlank();
            assertThat(amountField(labelled(result, "Grand Total").get(0)))
                    .isBlank()
                    .doesNotContain("0.00");
        }

        @Test
        @DisplayName("a total is always signed while a non-negative detail carries a space")
        void signBehaviourDiffersBetweenDetailAndTotal() {
            stubLookups();
            stubRange(List.of(transaction(identifier(1), CARD_A, "1.00", "2022-07-05")));

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(amountField(detailsOf(result).get(0)).charAt(0)).isEqualTo(' ');
            assertThat(amountField(labelled(result, "Page Total").get(0)).charAt(0))
                    .isEqualTo('+');
            assertThat(amountField(labelled(result, "Grand Total").get(0)).charAt(0))
                    .isEqualTo('+');
        }

        @Test
        @DisplayName("a negative detail carries a leading minus and drives a negative grand total")
        void negativeAmountsKeepTheirSign() {
            stubLookups();
            stubRange(List.of(transaction(identifier(1), CARD_A, "-5.00", "2022-07-05")));

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(amountField(detailsOf(result).get(0)).charAt(0)).isEqualTo('-');
            assertThat(amountField(labelled(result, "Grand Total").get(0)).charAt(0))
                    .isEqualTo('-');
            assertThat(result.grandTotal()).isEqualByComparingTo(new BigDecimal("-10.00"));
        }
    }

    @Nested
    @DisplayName("instrumentation and statelessness")
    class InstrumentationAndStatelessness {

        /** Published identity of the generation timer, asserted rather than assumed. */
        private static final String GENERATION_TIMER = "carddemo.batch.report.generation";

        /** Published identity of the record counter. */
        private static final String RECORD_COUNTER = "carddemo.batch.report.records";

        /** The step template's own lifecycle timer, which this stage must not stand in for. */
        private static final String STEP_TIMER = "carddemo.batch.cobol.step";

        @Test
        @DisplayName("a completed generation is timed and its records counted")
        void completedGenerationIsTimedAndCounted() {
            stubLookups();
            stubRange(onOneCard(3, "2022-07-05"));

            TransactionReportResult result = processor.process(structuredCard());

            assertThat(registry.find(GENERATION_TIMER)
                    .tag("resource", TransactionReportProcessor.LEGACY_DD_TRANREPT)
                    .tag("outcome", "COMPLETED")
                    .timer())
                    .isNotNull()
                    .extracting(timer -> timer.count())
                    .isEqualTo(1L);
            assertThat(registry.find(RECORD_COUNTER).counter())
                    .isNotNull()
                    .extracting(counter -> counter.count())
                    .isEqualTo((double) result.reportLines().size());
        }

        @Test
        @DisplayName("a failed generation is timed as a failure and the failure propagates intact")
        void failedGenerationIsTimedAsFailure() {
            TransactionReportService failing = mock(TransactionReportService.class);
            IllegalStateException raised = new IllegalStateException("range unavailable");
            when(failing.generateReportFromDateParameterCard(any())).thenThrow(raised);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new TransactionReportProcessor(failing, registry)
                            .process(structuredCard()))
                    .isSameAs(raised);

            assertThat(registry.find(GENERATION_TIMER).tag("outcome", "FAILED").timer())
                    .isNotNull();
            assertThat(registry.find(GENERATION_TIMER).tag("outcome", "COMPLETED").timer())
                    .isNull();
        }

        @Test
        @DisplayName("whole-step timing is left to the step template, not replaced here")
        void wholeStepTimingIsNotReplaced() {
            stubLookups();
            stubRange(onOneCard(1, "2022-07-05"));

            processor.process(structuredCard());

            assertThat(registry.find(STEP_TIMER).timer()).isNull();
        }

        @Test
        @DisplayName("two consecutive runs of one card produce identical reports, so nothing leaks")
        void consecutiveRunsDoNotAccumulate() {
            stubLookups();
            stubRange(onOneCard(4, "2022-07-05"));

            TransactionReportResult first = processor.process(structuredCard());
            TransactionReportResult second = processor.process(structuredCard());

            assertThat(second.reportLines()).isEqualTo(first.reportLines());
            assertThat(second.grandTotal()).isEqualByComparingTo(first.grandTotal());
            assertThat(second.lineCount()).isEqualTo(first.lineCount());
            assertThat(second.pageCount()).isEqualTo(first.pageCount());
            assertThat(second.accountBreakCount()).isEqualTo(first.accountBreakCount());
        }

        @Test
        @DisplayName("the published legacy names record the duplicate step name they were taken from")
        void publishedLegacyNamesRecordTheSourceDefect() {
            assertThat(TransactionReportProcessor.LEGACY_JOB).isEqualTo("TRANREPT");
            assertThat(TransactionReportProcessor.LEGACY_PROGRAM).isEqualTo("CBTRN03C");
            assertThat(TransactionReportProcessor.LEGACY_REPORT_STEP).isEqualTo("STEP10R");
            assertThat(TransactionReportProcessor.LEGACY_PROCEDURE).isEqualTo("REPROC");
            assertThat(TransactionReportProcessor.LEGACY_UNLOAD_STEP)
                    .isEqualTo(TransactionReportProcessor.LEGACY_SORT_STEP);
        }
    }
}
