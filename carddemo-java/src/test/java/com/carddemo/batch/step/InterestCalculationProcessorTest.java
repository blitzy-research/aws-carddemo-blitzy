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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.domain.Account;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.id.DisclosureGroupId;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import com.carddemo.exception.AbendException;
import com.carddemo.service.InterestCalculationService;
import com.carddemo.util.TransactionRecordMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;

/**
 * Parity and contract suite for {@link InterestCalculationProcessor}, the per-account control break of
 * the interest run translated from {@code app/cbl/CBACT04C.cbl}.
 *
 * <p>A pure unit test: no Spring context, no connection, no container. The interest service is a mock,
 * because every behaviour it owns has its own suite; what is asserted here is the read loop the stage
 * owns - the control break, the mandatory final flush, the suffix threading, preservation of the
 * service's rate-gate decision, and the fixed-width output contract the stage guards before handing a
 * group on.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@DisplayName("InterestCalculationProcessor - the interest run's account control break")
final class InterestCalculationProcessorTest {

    /** The run date the job stream supplies at {@code app/jcl/INTCALC.jcl:L22}. */
    private static final String RUN_DATE = "2022071800";

    /** First account of the fixture stream. */
    private static final String ACCOUNT_A = "00000000011";

    /** Second account of the fixture stream. */
    private static final String ACCOUNT_B = "00000000022";

    /** The account group identifier every row of the fixture probes the disclosure group with. */
    private static final String GROUP_ID = "A         ";

    /** A sixteen-character card number, as the cross-reference supplies it. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** Transaction type code of the fixture rows. */
    private static final String TYPE_CD = "01";

    /** Transaction category code of the fixture rows. */
    private static final String CATEGORY_CD = "0005";

    /**
     * The batch timestamp the fixed clock produces, in the batch tier's twenty-six-character format:
     * hyphen between date and time, dots inside the time, two-digit hundredths, four-character tail.
     */
    private static final String FIXED_TIMESTAMP = "2022-07-18-23.23.06.120000";

    /** The instant the fixed clock reports, chosen to render {@link #FIXED_TIMESTAMP}. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-18T23:23:06.120Z");

    /**
     * The interest of a balance of {@code 1000.00} at a rate of {@code 5.25} when the source's operand
     * order is honoured: multiply first, giving {@code 5250.0000}, then divide by {@code 1200}, giving
     * {@code 4.375}, then truncate into the two-decimal receiving field.
     */
    private static final BigDecimal MULTIPLY_THEN_DIVIDE = new BigDecimal("4.37");

    /**
     * What the same figures produce when the expression is rearranged to divide the rate first: the
     * quotient truncates into the two-decimal field before the multiplication, so the whole accrual
     * vanishes. The two results are not merely different roundings of one another, which is why the
     * source's operand order may never be rearranged.
     */
    private static final BigDecimal DIVIDE_THEN_MULTIPLY = new BigDecimal("0.00");

    /** The stubbed interest service; every per-row behaviour it owns is tested in its own suite. */
    private InterestCalculationService service;

    /** A real registry, so the supplementary meters are genuinely registered. */
    private MeterRegistry meterRegistry;

    /** The stage under test. */
    private InterestCalculationProcessor processor;

    /** The step execution the stage is opened against. */
    private StepExecution stepExecution;

    @BeforeEach
    void setUp() {
        this.service = mock(InterestCalculationService.class);
        this.meterRegistry = new SimpleMeterRegistry();
        this.processor = new InterestCalculationProcessor(this.service, this.meterRegistry,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        this.stepExecution = stepExecutionWith(RUN_DATE);
    }

    // ----------------------------------------------------------------------------------------
    // Fixtures
    // ----------------------------------------------------------------------------------------

    private static StepExecution stepExecutionWith(final String runDate) {
        final JobParametersBuilder parameters = new JobParametersBuilder();
        if (runDate != null) {
            parameters.addString(InterestCalculationProcessor.PARM_DATE_KEY, runDate);
        }
        return MetaDataInstanceFactory.createStepExecution(parameters.toJobParameters());
    }

    private static StepExecution stepExecutionWithNoParameters() {
        return MetaDataInstanceFactory.createStepExecution(new JobParameters());
    }

    private static TransactionCategoryBalance row(final String accountId, final String categoryCd) {
        return new TransactionCategoryBalance(accountId, TYPE_CD, categoryCd,
                new BigDecimal("1000.00"));
    }

    private static Account postedAccount(final String accountId) {
        return new Account(accountId, "Y", new BigDecimal("1004.37"), new BigDecimal("5000.00"),
                new BigDecimal("500.00"), "2020-01-01", "2030-01-01", "2025-01-01",
                new BigDecimal("0.00"), new BigDecimal("0.00"), "12345", GROUP_ID);
    }

    private static Transaction interestTransaction(final String accountId, final long suffix,
            final BigDecimal amount) {
        return new Transaction(InterestCalculationProcessor.interestTranId(RUN_DATE, suffix),
                InterestCalculationProcessor.INTEREST_TRAN_TYPE_CD,
                InterestCalculationProcessor.INTEREST_TRAN_CAT_CD,
                InterestCalculationProcessor.INTEREST_TRAN_SOURCE,
                InterestCalculationProcessor.interestDescriptionFor(accountId), amount,
                InterestCalculationProcessor.INTEREST_MERCHANT_ID, blank(50), blank(50), blank(10),
                CARD_NUMBER, FIXED_TIMESTAMP, FIXED_TIMESTAMP);
    }

    private static String blank(final int width) {
        return " ".repeat(width);
    }

    private static InterestCalculationService.CategoryInterest accruedRow(final String accountId,
            final String categoryCd, final BigDecimal interest, final Transaction transaction) {
        return new InterestCalculationService.CategoryInterest(accountId, TYPE_CD, categoryCd,
                new BigDecimal("1000.00"), new BigDecimal("5.25"), interest, false, false,
                transaction);
    }

    private static InterestCalculationService.CategoryInterest gatedRow(final String accountId,
            final String categoryCd) {
        return new InterestCalculationService.CategoryInterest(accountId, TYPE_CD, categoryCd,
                new BigDecimal("1000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"), true,
                false, null);
    }

    private static InterestCalculationService.GroupInterestResult group(final String accountId,
            final List<InterestCalculationService.CategoryInterest> rows, final BigDecimal total,
            final long lastSuffix, final Account account) {
        final List<Transaction> transactions = new ArrayList<>();
        for (final InterestCalculationService.CategoryInterest each : rows) {
            if (each.interestTransaction() != null) {
                transactions.add(each.interestTransaction());
            }
        }
        boolean gated = false;
        boolean fellBack = false;
        for (final InterestCalculationService.CategoryInterest each : rows) {
            gated |= each.rateGateSkipped();
            fellBack |= each.defaultGroupUsed();
        }
        return new InterestCalculationService.GroupInterestResult(accountId, total, rows, transactions,
                account, gated, fellBack, rows.size(), lastSuffix);
    }

    /** One account, one row, one transaction: the smallest group that posts interest. */
    private static InterestCalculationService.GroupInterestResult singleRowGroup(
            final String accountId, final long initialSuffix) {
        return sizedGroup(accountId, 1, initialSuffix);
    }

    /**
     * A well-formed group of {@code rowCount} accruing rows, continuing the identifier suffix from
     * {@code initialSuffix} exactly as the run threads it.
     *
     * @param  accountId     the account the group keys on
     * @param  rowCount      how many rows the group contains, all accruing
     * @param  initialSuffix the suffix the group continues from
     * @return the group result the stubbed service reports
     */
    private static InterestCalculationService.GroupInterestResult sizedGroup(final String accountId,
            final int rowCount, final long initialSuffix) {
        final List<InterestCalculationService.CategoryInterest> rows = new ArrayList<>(rowCount);
        BigDecimal total = new BigDecimal("0.00");
        long suffix = initialSuffix;
        for (int index = 0; index < rowCount; index++) {
            suffix++;
            final String categoryCd = String.valueOf(5 + index);
            rows.add(accruedRow(accountId, "000" + categoryCd, MULTIPLY_THEN_DIVIDE,
                    interestTransaction(accountId, suffix, MULTIPLY_THEN_DIVIDE)));
            total = total.add(MULTIPLY_THEN_DIVIDE);
        }
        return group(accountId, rows, total, suffix, postedAccount(accountId));
    }

    private void stubGroup(final String accountId,
            final InterestCalculationService.GroupInterestResult result) {
        when(this.service.calculateGroupInterest(anyString(), eq(accountId), anyList(), anyLong(), any()))
                .thenReturn(result);
    }

    /**
     * Stubs the accrual so it answers from the arguments it is given, which is what a stream whose
     * groups repeat or whose suffix advances across several groups needs.
     */
    private void stubGroupsFromArguments() {
        when(this.service.calculateGroupInterest(anyString(), anyString(), anyList(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    final String accountId = invocation.getArgument(1);
                    final List<?> buffered = invocation.getArgument(2);
                    final long initialSuffix = invocation.getArgument(3);
                    return sizedGroup(accountId, buffered.size(), initialSuffix);
                });
    }

    // ----------------------------------------------------------------------------------------
    // The control break
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The synthesized-record writer this stage binds for the step that owns the generation")
    final class TheBoundRecordWriter {

        /** Creates the nest. */
        TheBoundRecordWriter() {
        }

        /**
         * Stubs the accrual so that it writes through the writer it is handed, which is what the real
         * service does at {@code app/cbl/CBACT04C.cbl:L500}.
         *
         * @param accountId the account the group keys on
         */
        private void stubGroupThatWrites(final String accountId) {
            when(service.calculateGroupInterest(anyString(), eq(accountId), anyList(), anyLong(),
                    any())).thenAnswer(invocation -> {
                        final InterestCalculationService.GroupInterestResult group =
                                sizedGroup(accountId, 1, invocation.getArgument(3));
                        final Consumer<Transaction> writer = invocation.getArgument(4);
                        group.interestTransactions().forEach(writer);
                        return group;
                    });
        }

        @Test
        @DisplayName("a bound writer receives every record the closing group synthesized, in order")
        void aBoundWriterReceivesTheGroupsRecords() {
            final List<String> written = new ArrayList<>();
            stubGroupThatWrites(ACCOUNT_A);
            processor.bindSynthesizedTransactionWriter(
                    synthesized -> written.add(synthesized.getTranId()));
            processor.beforeStep(stepExecution);

            processor.process(row(ACCOUNT_A, "0005"));
            processor.afterStep(stepExecution);

            assertThat(written).containsExactly(
                    InterestCalculationProcessor.interestTranId(RUN_DATE, 1L));
        }

        @Test
        @DisplayName("synthesizing a record with NO writer bound fails loudly, because a discarding "
                + "default would post every balance into a run whose records went nowhere")
        void anUnboundWriterFailsRatherThanDiscarding() {
            stubGroupThatWrites(ACCOUNT_A);
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining(InterestCalculationProcessor.OUTPUT_RESOURCE)
                    .withMessageContaining("before the first row is read");
        }

        @Test
        @DisplayName("unbinding releases it, so a record synthesized after the generation closed fails "
                + "rather than being written into a closed resource")
        void unbindingReleasesTheWriter() {
            final List<String> written = new ArrayList<>();
            stubGroupThatWrites(ACCOUNT_A);
            processor.bindSynthesizedTransactionWriter(
                    synthesized -> written.add(synthesized.getTranId()));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));
            processor.afterStep(stepExecution);
            assertThat(written).hasSize(1);

            processor.unbindSynthesizedTransactionWriter();
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> processor.afterStep(stepExecution));
            assertThat(written).as("nothing further reached the released writer").hasSize(1);
        }

        @Test
        @DisplayName("a null binding is refused, because unbinding is its own operation and a null one "
                + "is therefore always a mistake")
        void aNullBindingIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> processor.bindSynthesizedTransactionWriter(null))
                    .withMessageContaining("writer must not be null");
        }

        @Test
        @DisplayName("whatever the writer raises propagates untranslated, so the write-error arm of the "
                + "legacy reaches the caller rather than being reported as a business outcome")
        void aWriterFailurePropagatesUntranslated() {
            final AbendException writeFailure = new AbendException("CBACT04C",
                    "ERROR WRITING TRANSACTION RECORD");
            stubGroupThatWrites(ACCOUNT_A);
            processor.bindSynthesizedTransactionWriter(synthesized -> {
                throw writeFailure;
            });
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> processor.afterStep(stepExecution))
                    .isSameAs(writeFailure);
        }
    }
    @Nested
    @DisplayName("The account control break")
    final class TheAccountControlBreak {

        @Test
        @DisplayName("the first row opens a group and closes none, because the first-time flag "
                + "suppresses exactly one flush")
        void theFirstRowOpensAGroupAndClosesNone() {
            processor.beforeStep(stepExecution);

            assertThat(processor.process(row(ACCOUNT_A, "0005"))).isNull();

            verifyNoMoreInteractions(service);
        }

        @Test
        @DisplayName("a row of the same account joins the open group and closes nothing")
        void aRowOfTheSameAccountClosesNothing() {
            processor.beforeStep(stepExecution);

            assertThat(processor.process(row(ACCOUNT_A, "0005"))).isNull();
            assertThat(processor.process(row(ACCOUNT_A, "0006"))).isNull();

            verifyNoMoreInteractions(service);
        }

        @Test
        @DisplayName("a change of account closes the PREVIOUS group, with every row it buffered, "
                + "before the new group opens")
        void aChangeOfAccountClosesThePreviousGroup() {
            stubGroup(ACCOUNT_A, singleRowGroup(ACCOUNT_A, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));

            final InterestCalculationProcessor.AccruedAccountGroup closed =
                    processor.process(row(ACCOUNT_B, "0005"));

            assertThat(closed).isNotNull();
            assertThat(closed.accountId()).isEqualTo(ACCOUNT_A);
            assertThat(closed.parameterDate()).isEqualTo(RUN_DATE);
            verify(service).calculateGroupInterest(eq(RUN_DATE), eq(ACCOUNT_A),
                    eq(List.of(row(ACCOUNT_A, "0005"))), eq(0L), any());
        }

        @Test
        @DisplayName("every buffered row of a group reaches the accrual, in the order it arrived")
        void everyBufferedRowReachesTheAccrualInOrder() {
            stubGroup(ACCOUNT_A, sizedGroup(ACCOUNT_A, 2, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));
            processor.process(row(ACCOUNT_A, "0006"));

            final InterestCalculationProcessor.AccruedAccountGroup closed =
                    processor.process(row(ACCOUNT_B, "0005"));

            verify(service).calculateGroupInterest(eq(RUN_DATE), eq(ACCOUNT_A),
                    eq(List.of(row(ACCOUNT_A, "0005"), row(ACCOUNT_A, "0006"))), eq(0L), any());
            assertThat(closed).isNotNull();
            assertThat(closed.rows()).extracting(accrued -> accrued.rowKey().getTrancatCd())
                    .containsExactly("0005", "0006");
            assertThat(closed.transactionCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("the FINAL group is flushed after the last row, because it has no successor "
                + "row to break on and omitting it would lose the account silently")
        void theFinalGroupIsFlushedAfterTheLastRow() {
            stubGroup(ACCOUNT_A, singleRowGroup(ACCOUNT_A, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));

            processor.afterStep(stepExecution);

            verify(service).calculateGroupInterest(eq(RUN_DATE), eq(ACCOUNT_A),
                    eq(List.of(row(ACCOUNT_A, "0005"))), eq(0L), any());
            assertThat(processor.finalAccruedGroup())
                    .map(InterestCalculationProcessor.AccruedAccountGroup::accountId)
                    .contains(ACCOUNT_A);
        }

        @Test
        @DisplayName("the final flush does NOT run when the step failed, because an abend is "
                + "terminal and the legacy never reaches its end-of-file arm")
        void theFinalFlushIsSkippedWhenTheStepFailed() {
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));
            stepExecution.setStatus(BatchStatus.FAILED);

            processor.afterStep(stepExecution);

            assertThat(processor.finalAccruedGroup()).isEmpty();
        }

        @Test
        @DisplayName("a run that reads no row closes no group at all")
        void aRunThatReadsNoRowClosesNoGroup() {
            processor.beforeStep(stepExecution);

            processor.afterStep(stepExecution);

            verifyNoMoreInteractions(service);
            assertThat(processor.finalAccruedGroup()).isEmpty();
        }

        @Test
        @DisplayName("the identifier suffix is threaded from one group into the next, because it "
                + "belongs to the run and not to any one group")
        void theSuffixIsThreadedFromOneGroupIntoTheNext() {
            stubGroup(ACCOUNT_A, singleRowGroup(ACCOUNT_A, 0L));
            stubGroup(ACCOUNT_B, singleRowGroup(ACCOUNT_B, 1L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));
            processor.process(row(ACCOUNT_B, "0005"));

            processor.afterStep(stepExecution);

            verify(service).calculateGroupInterest(eq(RUN_DATE), eq(ACCOUNT_A),
                    eq(List.of(row(ACCOUNT_A, "0005"))), eq(0L), any());
            verify(service).calculateGroupInterest(eq(RUN_DATE), eq(ACCOUNT_B),
                    eq(List.of(row(ACCOUNT_B, "0005"))), eq(1L), any());
            assertThat(processor.finalAccruedGroup())
                    .map(InterestCalculationProcessor.AccruedAccountGroup::lastTranIdSuffix)
                    .contains(2L);
        }

        @Test
        @DisplayName("rows out of key order are grouped exactly as the legacy would group them, "
                + "which is why ordering is the job configuration's responsibility")
        void rowsOutOfKeyOrderBreakTwice() {
            stubGroupsFromArguments();
            processor.beforeStep(stepExecution);

            processor.process(row(ACCOUNT_A, "0005"));
            processor.process(row(ACCOUNT_B, "0005"));
            processor.process(row(ACCOUNT_A, "0006"));
            processor.afterStep(stepExecution);

            verify(service, times(2)).calculateGroupInterest(anyString(), eq(ACCOUNT_A), anyList(),
                    anyLong(), any());
            assertThat(stepExecution.getExecutionContext()
                    .getInt(InterestCalculationProcessor.CONTEXT_GROUPS_CLOSED)).isEqualTo(3);
        }

        @Test
        @DisplayName("the run's counters and flags reach the execution context, and nothing "
                + "financial or card-bearing does")
        void theCountersReachTheExecutionContext() {
            stubGroup(ACCOUNT_A, sizedGroup(ACCOUNT_A, 2, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));
            processor.process(row(ACCOUNT_A, "0006"));

            processor.afterStep(stepExecution);

            assertThat(stepExecution.getExecutionContext()
                    .getInt(InterestCalculationProcessor.CONTEXT_GROUPS_CLOSED)).isEqualTo(1);
            assertThat(stepExecution.getExecutionContext()
                    .getInt(InterestCalculationProcessor.CONTEXT_ROWS_READ)).isEqualTo(2);
            assertThat(stepExecution.getExecutionContext()
                    .getInt(InterestCalculationProcessor.CONTEXT_TRANSACTIONS)).isEqualTo(2);
            assertThat(stepExecution.getExecutionContext()
                    .getLong(InterestCalculationProcessor.CONTEXT_LAST_TRAN_ID_SUFFIX)).isEqualTo(2L);
            assertThat(stepExecution.getExecutionContext()
                    .getString(InterestCalculationProcessor.CONTEXT_RATE_GATE_SKIPPED))
                    .isEqualTo("false");
            assertThat(stepExecution.getExecutionContext()
                    .getString(InterestCalculationProcessor.CONTEXT_DEFAULT_GROUP_USED))
                    .isEqualTo("false");
            assertThat(stepExecution.getExecutionContext().toMap().keySet())
                    .allSatisfy(key -> assertThat(key).startsWith("carddemo.interest."));
        }

        @Test
        @DisplayName("the supplementary meters are registered and never replace whole-step timing")
        void theSupplementaryMetersAreRegistered() {
            stubGroup(ACCOUNT_A, singleRowGroup(ACCOUNT_A, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));
            processor.afterStep(stepExecution);

            assertThat(meterRegistry.get("carddemo.batch.interest.rows").counter().count())
                    .isEqualTo(1.0d);
            assertThat(meterRegistry.get("carddemo.batch.interest.transactions").counter().count())
                    .isEqualTo(1.0d);
            assertThat(meterRegistry.get("carddemo.batch.interest.group")
                    .tag("outcome", "COMPLETED").timer().count()).isEqualTo(1L);
        }
    }

    // ----------------------------------------------------------------------------------------
    // The lifecycle contract
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The step lifecycle contract")
    final class TheStepLifecycleContract {

        @Test
        @DisplayName("accruing before the step is opened is refused, because the run date and the "
                + "control-break state are installed by the listener")
        void accruingBeforeTheStepIsOpenedIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> processor.process(row(ACCOUNT_A, "0005")))
                    .withMessageContaining("has no open execution");
        }

        @Test
        @DisplayName("accruing after the step has ended is refused")
        void accruingAfterTheStepHasEndedIsRefused() {
            processor.beforeStep(stepExecution);
            processor.afterStep(stepExecution);

            assertThatIllegalStateException()
                    .isThrownBy(() -> processor.process(row(ACCOUNT_A, "0005")))
                    .withMessageContaining("has already ended");
        }

        @Test
        @DisplayName("closing a step that was never opened is tolerated and reported")
        void closingAStepThatWasNeverOpenedIsTolerated() {
            assertThat(processor.afterStep(stepExecution)).isNotNull();
            assertThat(processor.finalAccruedGroup()).isEmpty();
            assertThat(processor.parameterDate()).isEmpty();
        }

        @Test
        @DisplayName("a second execution starts from the legacy's own initial values, so no figure "
                + "survives from the previous one")
        void aSecondExecutionStartsClean() {
            stubGroup(ACCOUNT_A, singleRowGroup(ACCOUNT_A, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));
            processor.afterStep(stepExecution);

            final StepExecution second = stepExecutionWith(RUN_DATE);
            processor.beforeStep(second);
            processor.process(row(ACCOUNT_A, "0005"));
            processor.afterStep(second);

            assertThat(second.getExecutionContext()
                    .getInt(InterestCalculationProcessor.CONTEXT_ROWS_READ)).isEqualTo(1);
            assertThat(second.getExecutionContext()
                    .getLong(InterestCalculationProcessor.CONTEXT_LAST_TRAN_ID_SUFFIX)).isEqualTo(1L);
            verify(service, times(2)).calculateGroupInterest(eq(RUN_DATE), eq(ACCOUNT_A),
                    eq(List.of(row(ACCOUNT_A, "0005"))), eq(0L), any());
        }

        @Test
        @DisplayName("a null step execution is refused by both listener callbacks")
        void aNullStepExecutionIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> processor.beforeStep(null));
            assertThatNullPointerException().isThrownBy(() -> processor.afterStep(null));
        }

        @Test
        @DisplayName("a null row is refused, because the framework contract forbids one")
        void aNullRowIsRefused() {
            processor.beforeStep(stepExecution);

            assertThatNullPointerException().isThrownBy(() -> processor.process(null))
                    .withMessageContaining("null");
        }

        @Test
        @DisplayName("a row without an account key is refused, because the control break would "
                + "otherwise compare against nothing and merge unrelated accounts")
        void aRowWithoutAnAccountKeyIsRefused() {
            processor.beforeStep(stepExecution);
            final TransactionCategoryBalance keyless =
                    new TransactionCategoryBalance(null, TYPE_CD, CATEGORY_CD, BigDecimal.ZERO);

            assertThatNullPointerException().isThrownBy(() -> processor.process(keyless))
                    .withMessageContaining("account identifier");
        }
    }

    // ----------------------------------------------------------------------------------------
    // The job parameter
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The ten-character run date")
    final class TheRunDate {

        @Test
        @DisplayName("it is required, because it supplies the first ten characters of every "
                + "identifier the run mints")
        void itIsRequired() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> processor.beforeStep(stepExecutionWithNoParameters()))
                    .withMessageContaining(InterestCalculationProcessor.PARM_DATE_KEY);
        }

        @Test
        @DisplayName("it must be exactly ten encoded bytes")
        void itMustBeExactlyTenEncodedBytes() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> processor.beforeStep(stepExecutionWith("20220718")))
                    .withMessageContaining("10 encoded bytes");
        }

        @Test
        @DisplayName("it carries no separators, so a hyphenated date is refused")
        void aHyphenatedDateIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> processor.beforeStep(stepExecutionWith("2022-07-18")))
                    .withMessageContaining("no separators");
        }

        @Test
        @DisplayName("a character above the digit range is refused just as a separator is")
        void aCharacterAboveTheDigitRangeIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> processor.beforeStep(stepExecutionWith("202207180A")))
                    .withMessageContaining("no separators")
                    .withMessageContaining("position 10");
        }

        @Test
        @DisplayName("it is never parsed, converted or reformatted: it is reported back verbatim")
        void itIsReportedBackVerbatim() {
            processor.beforeStep(stepExecutionWith("2022071800"));

            assertThat(processor.parameterDate()).contains("2022071800");
        }
    }

    // ----------------------------------------------------------------------------------------
    // The account flush
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The account flush")
    final class TheAccountFlush {

        @Test
        @DisplayName("the closed group carries the posted total and the rewritten account with "
                + "BOTH cycle accumulators zeroed")
        void theClosedGroupCarriesThePostedTotalAndTheRewrittenAccount() {
            stubGroup(ACCOUNT_A, singleRowGroup(ACCOUNT_A, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));
            processor.afterStep(stepExecution);

            final InterestCalculationProcessor.AccruedAccountGroup closed =
                    processor.finalAccruedGroup().orElseThrow();
            assertThat(closed.totalInterest()).isEqualByComparingTo(MULTIPLY_THEN_DIVIDE);
            assertThat(closed.updatedAccount().getAcctCurrCycCredit()).isEqualByComparingTo("0.00");
            assertThat(closed.updatedAccount().getAcctCurrCycDebit()).isEqualByComparingTo("0.00");
            assertThat(closed.recordCount()).isEqualTo(1);
            assertThat(closed.transactionCount()).isEqualTo(1);
            assertThat(closed.accruedAt()).isEqualTo(FIXED_TIMESTAMP);
        }

        @Test
        @DisplayName("a cycle CREDIT left un-zeroed is refused, because a half-closed cycle "
                + "compounds silently in every later run")
        void aCycleCreditLeftUnZeroedIsRefused() {
            final Account halfClosed = postedAccount(ACCOUNT_A);
            halfClosed.setAcctCurrCycCredit(new BigDecimal("12.34"));
            stubGroup(ACCOUNT_A, group(ACCOUNT_A,
                    List.of(accruedRow(ACCOUNT_A, CATEGORY_CD, MULTIPLY_THEN_DIVIDE,
                            interestTransaction(ACCOUNT_A, 1L, MULTIPLY_THEN_DIVIDE))),
                    MULTIPLY_THEN_DIVIDE, 1L, halfClosed));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("un-zeroed");
        }

        @Test
        @DisplayName("a cycle DEBIT left un-zeroed is refused for the same reason")
        void aCycleDebitLeftUnZeroedIsRefused() {
            final Account halfClosed = postedAccount(ACCOUNT_A);
            halfClosed.setAcctCurrCycDebit(new BigDecimal("12.34"));
            stubGroup(ACCOUNT_A, group(ACCOUNT_A,
                    List.of(accruedRow(ACCOUNT_A, CATEGORY_CD, MULTIPLY_THEN_DIVIDE,
                            interestTransaction(ACCOUNT_A, 1L, MULTIPLY_THEN_DIVIDE))),
                    MULTIPLY_THEN_DIVIDE, 1L, halfClosed));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("un-zeroed");
        }

        @Test
        @DisplayName("a group closed for another account than the one buffered is refused")
        void aGroupClosedForAnotherAccountIsRefused() {
            stubGroup(ACCOUNT_A, singleRowGroup(ACCOUNT_B, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("reported account");
        }

        @Test
        @DisplayName("a group whose row count disagrees with the rows buffered is refused")
        void aGroupWithADisagreeingRowCountIsRefused() {
            stubGroup(ACCOUNT_A, singleRowGroup(ACCOUNT_A, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));
            processor.process(row(ACCOUNT_A, "0006"));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("buffered 2 rows");
        }

        @Test
        @DisplayName("a group whose counted rows agree but whose detailed rows do not is refused, "
                + "because both figures describe the same group")
        void aGroupWithADisagreeingDetailCountIsRefused() {
            final Transaction transaction = interestTransaction(ACCOUNT_A, 1L, MULTIPLY_THEN_DIVIDE);
            stubGroup(ACCOUNT_A, new InterestCalculationService.GroupInterestResult(ACCOUNT_A,
                    MULTIPLY_THEN_DIVIDE,
                    List.of(accruedRow(ACCOUNT_A, "0005", MULTIPLY_THEN_DIVIDE, transaction),
                            accruedRow(ACCOUNT_A, "0006", MULTIPLY_THEN_DIVIDE, transaction)),
                    List.of(transaction), postedAccount(ACCOUNT_A), false, false, 1, 1L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("2 detailed");
        }

        @Test
        @DisplayName("a failed accrual is measured under its own outcome tag and propagates "
                + "untranslated, so no abend is turned into a business skip")
        void aFailedAccrualPropagatesUntranslated() {
            final AbendException abend = new AbendException("CBACT04C", "STATUS 23 READING DISCGRP");
            when(service.calculateGroupInterest(anyString(), anyString(),
                    anyList(), anyLong(), any())).thenThrow(abend);
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> processor.afterStep(stepExecution)).isSameAs(abend);
            assertThat(meterRegistry.get("carddemo.batch.interest.group")
                    .tag("outcome", "ABENDED").timer().count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("the single default-group fallback is not retried by this stage: one accrual "
                + "call per group, and a second miss abends without a third attempt")
        void aSecondMissAbendsWithoutAThirdAttempt() {
            when(service.calculateGroupInterest(anyString(), anyString(),
                    anyList(), anyLong(), any()))
                    .thenThrow(new AbendException("CBACT04C", "STATUS 23 READING DISCGRP"));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> processor.afterStep(stepExecution));

            verify(service, times(1)).calculateGroupInterest(anyString(), anyString(),
                    anyList(), anyLong(), any());
        }
    }

    // ----------------------------------------------------------------------------------------
    // The disclosure key
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The disclosure-group key")
    final class TheDisclosureGroupKey {

        @Test
        @DisplayName("the primary key is ordered group, then TYPE, then CATEGORY - which is NOT "
                + "the order the source moves the parts in")
        void thePrimaryKeyIsOrderedGroupThenTypeThenCategory() {
            final DisclosureGroupId key =
                    InterestCalculationProcessor.primaryDisclosureKey(GROUP_ID, "01", "0005");

            assertThat(key.getDisAcctGroupId()).isEqualTo(GROUP_ID);
            assertThat(key.getDisTranTypeCd()).isEqualTo("01");
            assertThat(key.getDisTranCatCd()).isEqualTo("0005");
        }

        @Test
        @DisplayName("the fallback key carries the TEN-character padded default literal, whose "
                + "three trailing spaces are part of the value")
        void theFallbackKeyCarriesTheTenCharacterPaddedLiteral() {
            final DisclosureGroupId key =
                    InterestCalculationProcessor.defaultDisclosureKey("01", "0005");

            assertThat(InterestCalculationProcessor.DEFAULT_ACCOUNT_GROUP_ID)
                    .isEqualTo("DEFAULT   ")
                    .hasSize(InterestCalculationProcessor.ACCT_GROUP_ID_WIDTH);
            assertThat(key.getDisAcctGroupId()).isEqualTo("DEFAULT   ");
            assertThat(key.getDisTranTypeCd()).isEqualTo("01");
            assertThat(key.getDisTranCatCd()).isEqualTo("0005");
        }

        @Test
        @DisplayName("every part of a key is required")
        void everyPartOfAKeyIsRequired() {
            assertThatNullPointerException().isThrownBy(
                    () -> InterestCalculationProcessor.primaryDisclosureKey(null, "01", "0005"));
            assertThatNullPointerException().isThrownBy(
                    () -> InterestCalculationProcessor.primaryDisclosureKey(GROUP_ID, null, "0005"));
            assertThatNullPointerException().isThrownBy(
                    () -> InterestCalculationProcessor.primaryDisclosureKey(GROUP_ID, "01", null));
        }

        @Test
        @DisplayName("the emitted row carries the key it was FIRST probed with, and reports the "
                + "default key as effective only when the fallback resolved it")
        void theEmittedRowCarriesTheProbeKeyAndTheEffectiveKey() {
            final Transaction transaction = interestTransaction(ACCOUNT_A, 1L, MULTIPLY_THEN_DIVIDE);
            final InterestCalculationService.CategoryInterest fellBack =
                    new InterestCalculationService.CategoryInterest(ACCOUNT_A, TYPE_CD, CATEGORY_CD,
                            new BigDecimal("1000.00"), new BigDecimal("5.25"), MULTIPLY_THEN_DIVIDE,
                            false, true, transaction);
            stubGroup(ACCOUNT_A, group(ACCOUNT_A, List.of(fellBack), MULTIPLY_THEN_DIVIDE, 1L,
                    postedAccount(ACCOUNT_A)));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));
            processor.afterStep(stepExecution);

            final InterestCalculationProcessor.AccruedCategoryRow accrued =
                    processor.finalAccruedGroup().orElseThrow().rows().get(0);
            assertThat(accrued.primaryDisclosureKey().getDisAcctGroupId()).isEqualTo(GROUP_ID);
            assertThat(accrued.effectiveDisclosureKey().getDisAcctGroupId())
                    .isEqualTo("DEFAULT   ");
            assertThat(accrued.defaultGroupUsed()).isTrue();
            assertThat(meterRegistry.get("carddemo.batch.interest.default.group.fallbacks")
                    .counter().count()).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("a row that resolved directly reports its primary key as effective")
        void aDirectHitReportsItsPrimaryKeyAsEffective() {
            stubGroup(ACCOUNT_A, singleRowGroup(ACCOUNT_A, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));
            processor.afterStep(stepExecution);

            final InterestCalculationProcessor.AccruedCategoryRow accrued =
                    processor.finalAccruedGroup().orElseThrow().rows().get(0);
            assertThat(accrued.defaultGroupUsed()).isFalse();
            assertThat(accrued.effectiveDisclosureKey()).isEqualTo(accrued.primaryDisclosureKey());
            assertThat(meterRegistry.find("carddemo.batch.interest.default.group.fallbacks")
                    .counter().count()).isEqualTo(0.0d);
        }

        @Test
        @DisplayName("an account without a group identifier cannot be probed and is refused")
        void anAccountWithoutAGroupIdentifierIsRefused() {
            final Account groupless = postedAccount(ACCOUNT_A);
            groupless.setAcctGroupId(null);
            stubGroup(ACCOUNT_A, group(ACCOUNT_A,
                    List.of(accruedRow(ACCOUNT_A, CATEGORY_CD, MULTIPLY_THEN_DIVIDE,
                            interestTransaction(ACCOUNT_A, 1L, MULTIPLY_THEN_DIVIDE))),
                    MULTIPLY_THEN_DIVIDE, 1L, groupless));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));

            assertThatNullPointerException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("group identifier");
        }
    }

    // ----------------------------------------------------------------------------------------
    // The rate gate and the service-owned fee-paragraph decision
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The service-owned non-zero rate gate and fee-paragraph decision")
    final class TheRateGateAndTheFeeParagraph {

        @Test
        @DisplayName("a zero rate emits NO transaction and invokes NO fee, because the gate "
                + "encloses both the computation and the fee call")
        void aZeroRateEmitsNoTransactionAndInvokesNoFee() {
            stubGroup(ACCOUNT_A, group(ACCOUNT_A, List.of(gatedRow(ACCOUNT_A, CATEGORY_CD)),
                    new BigDecimal("0.00"), 0L, postedAccount(ACCOUNT_A)));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));
            processor.afterStep(stepExecution);

            final InterestCalculationProcessor.AccruedAccountGroup closed =
                    processor.finalAccruedGroup().orElseThrow();
            assertThat(closed.transactionCount()).isZero();
            assertThat(closed.rateGateSkipped()).isTrue();
            assertThat(closed.lastTranIdSuffix()).isZero();
            final InterestCalculationProcessor.AccruedCategoryRow accrued = closed.rows().get(0);
            assertThat(accrued.producedTransaction()).isFalse();
            assertThat(accrued.feeParagraphInvoked()).isFalse();
            assertThat(accrued.tranIdSuffix())
                    .isEqualTo(InterestCalculationProcessor.NO_TRAN_ID_SUFFIX);
        }

        @Test
        @DisplayName("the processor preserves the service's per-row fee decision without invoking a "
                + "second paragraph owner")
        void theServiceFeeDecisionIsPreservedPerRow() {
            final List<InterestCalculationService.CategoryInterest> rows =
                    List.of(accruedRow(ACCOUNT_A, "0005", MULTIPLY_THEN_DIVIDE,
                                    interestTransaction(ACCOUNT_A, 1L, MULTIPLY_THEN_DIVIDE)),
                            gatedRow(ACCOUNT_A, "0006"),
                            accruedRow(ACCOUNT_A, "0007", MULTIPLY_THEN_DIVIDE,
                                    interestTransaction(ACCOUNT_A, 2L, MULTIPLY_THEN_DIVIDE)));
            stubGroup(ACCOUNT_A, group(ACCOUNT_A, rows, new BigDecimal("8.74"), 2L,
                    postedAccount(ACCOUNT_A)));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));
            processor.process(row(ACCOUNT_A, "0006"));
            processor.process(row(ACCOUNT_A, "0007"));
            processor.afterStep(stepExecution);

            assertThat(processor.finalAccruedGroup().orElseThrow().rows())
                    .extracting(InterestCalculationProcessor.AccruedCategoryRow
                            ::feeParagraphInvoked)
                    .containsExactly(true, false, true);
            verify(service).calculateGroupInterest(anyString(), anyString(), anyList(), anyLong(), any());
        }

        @Test
        @DisplayName("a gated row that still carries a transaction is refused, because a zero rate "
                + "mints a record the legacy never writes")
        void aGatedRowCarryingATransactionIsRefused() {
            final InterestCalculationService.CategoryInterest contradiction =
                    new InterestCalculationService.CategoryInterest(ACCOUNT_A, TYPE_CD, CATEGORY_CD,
                            new BigDecimal("1000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                            true, false, interestTransaction(ACCOUNT_A, 1L, BigDecimal.ZERO));
            stubGroup(ACCOUNT_A, new InterestCalculationService.GroupInterestResult(ACCOUNT_A,
                    new BigDecimal("0.00"), List.of(contradiction), List.of(), postedAccount(ACCOUNT_A),
                    true, false, 1, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("yet carried a synthesized transaction");
        }

        @Test
        @DisplayName("a gated row with a non-zero interest is refused")
        void aGatedRowWithNonZeroInterestIsRefused() {
            final InterestCalculationService.CategoryInterest contradiction =
                    new InterestCalculationService.CategoryInterest(ACCOUNT_A, TYPE_CD, CATEGORY_CD,
                            new BigDecimal("1000.00"), new BigDecimal("0.00"), MULTIPLY_THEN_DIVIDE,
                            true, false, null);
            stubGroup(ACCOUNT_A, group(ACCOUNT_A, List.of(contradiction), new BigDecimal("0.00"), 0L,
                    postedAccount(ACCOUNT_A)));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("non-zero monthly interest");
        }

        @Test
        @DisplayName("a row gated as a zero rate that carries a non-zero rate is refused")
        void aGatedRowWithNonZeroRateIsRefused() {
            final InterestCalculationService.CategoryInterest contradiction =
                    new InterestCalculationService.CategoryInterest(ACCOUNT_A, TYPE_CD, CATEGORY_CD,
                            new BigDecimal("1000.00"), new BigDecimal("5.25"), new BigDecimal("0.00"),
                            true, false, null);
            stubGroup(ACCOUNT_A, group(ACCOUNT_A, List.of(contradiction), new BigDecimal("0.00"), 0L,
                    postedAccount(ACCOUNT_A)));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("non-zero disclosed rate");
        }

        @Test
        @DisplayName("a row outside the gate that synthesized no transaction is refused")
        void anUngatedRowWithoutATransactionIsRefused() {
            final InterestCalculationService.CategoryInterest contradiction =
                    new InterestCalculationService.CategoryInterest(ACCOUNT_A, TYPE_CD, CATEGORY_CD,
                            new BigDecimal("1000.00"), new BigDecimal("5.25"), MULTIPLY_THEN_DIVIDE,
                            false, false, null);
            stubGroup(ACCOUNT_A, group(ACCOUNT_A, List.of(contradiction), MULTIPLY_THEN_DIVIDE, 1L,
                    postedAccount(ACCOUNT_A)));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("synthesized no transaction");
        }
    }

    // ----------------------------------------------------------------------------------------
    // The arithmetic
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The multiply-before-divide interest expression")
    final class TheInterestExpression {

        @Test
        @DisplayName("the two operand orders are not two roundings of one result: multiplying "
                + "first yields 4.37 and dividing first yields 0.00")
        void theTwoOperandOrdersDisagree() {
            assertThat(MULTIPLY_THEN_DIVIDE).isNotEqualByComparingTo(DIVIDE_THEN_MULTIPLY);
            assertThat(MULTIPLY_THEN_DIVIDE).isEqualByComparingTo("4.37");
            assertThat(DIVIDE_THEN_MULTIPLY).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a group total that is the multiply-first sum of its truncated row amounts "
                + "is accepted")
        void theMultiplyFirstTotalIsAccepted() {
            stubGroup(ACCOUNT_A, singleRowGroup(ACCOUNT_A, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));

            assertThatCode(() -> processor.afterStep(stepExecution)).doesNotThrowAnyException();
            assertThat(processor.finalAccruedGroup().orElseThrow().totalInterest())
                    .isEqualByComparingTo(MULTIPLY_THEN_DIVIDE);
        }

        @Test
        @DisplayName("a group total that is the divide-first result is refused, so a rearranged "
                + "expression cannot reach the output")
        void theDivideFirstTotalIsRefused() {
            stubGroup(ACCOUNT_A, group(ACCOUNT_A,
                    List.of(accruedRow(ACCOUNT_A, CATEGORY_CD, MULTIPLY_THEN_DIVIDE,
                            interestTransaction(ACCOUNT_A, 1L, MULTIPLY_THEN_DIVIDE))),
                    DIVIDE_THEN_MULTIPLY, 1L, postedAccount(ACCOUNT_A)));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("not the sum of its");
        }

        @Test
        @DisplayName("the total is accumulated one truncated addend at a time, matching the "
                + "source's add-to-running-total statement")
        void theTotalIsAccumulatedOneTruncatedAddendAtATime() {
            final List<InterestCalculationService.CategoryInterest> rows =
                    List.of(accruedRow(ACCOUNT_A, "0005", new BigDecimal("0.01"),
                                    interestTransaction(ACCOUNT_A, 1L, new BigDecimal("0.01"))),
                            accruedRow(ACCOUNT_A, "0006", new BigDecimal("0.02"),
                                    interestTransaction(ACCOUNT_A, 2L, new BigDecimal("0.02"))));
            stubGroup(ACCOUNT_A, group(ACCOUNT_A, rows, new BigDecimal("0.03"), 2L,
                    postedAccount(ACCOUNT_A)));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));
            processor.process(row(ACCOUNT_A, "0006"));

            assertThatCode(() -> processor.afterStep(stepExecution)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a suffix that advanced by more than one per transaction is refused")
        void aSuffixThatJumpedIsRefused() {
            stubGroup(ACCOUNT_A, group(ACCOUNT_A,
                    List.of(accruedRow(ACCOUNT_A, CATEGORY_CD, MULTIPLY_THEN_DIVIDE,
                            interestTransaction(ACCOUNT_A, 1L, MULTIPLY_THEN_DIVIDE))),
                    MULTIPLY_THEN_DIVIDE, 7L, postedAccount(ACCOUNT_A)));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("advanced the identifier suffix");
        }

        @Test
        @DisplayName("a transaction count that disagrees with the identifiers minted is refused")
        void aDisagreeingTransactionCountIsRefused() {
            final Transaction transaction = interestTransaction(ACCOUNT_A, 1L, MULTIPLY_THEN_DIVIDE);
            stubGroup(ACCOUNT_A, new InterestCalculationService.GroupInterestResult(ACCOUNT_A,
                    MULTIPLY_THEN_DIVIDE,
                    List.of(accruedRow(ACCOUNT_A, CATEGORY_CD, MULTIPLY_THEN_DIVIDE, transaction)),
                    List.of(transaction, transaction), postedAccount(ACCOUNT_A), false, false, 1, 1L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("minted an identifier for");
        }
    }

    // ----------------------------------------------------------------------------------------
    // The synthesized transaction
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The synthesized interest transaction")
    final class TheSynthesizedInterestTransaction {

        @Test
        @DisplayName("every literal the source stores is the literal this stage requires")
        void everyLiteralIsTheLiteralTheSourceStores() {
            assertThat(InterestCalculationProcessor.INTEREST_TRAN_TYPE_CD).isEqualTo("01");
            assertThat(InterestCalculationProcessor.INTEREST_TRAN_CAT_CD).isEqualTo("0005")
                    .hasSize(4);
            assertThat(InterestCalculationProcessor.INTEREST_TRAN_SOURCE).isEqualTo("System    ")
                    .hasSize(10);
            assertThat(InterestCalculationProcessor.INTEREST_DESCRIPTION_PREFIX)
                    .isEqualTo("Int. for a/c ").endsWith(" ");
            assertThat(InterestCalculationProcessor.INTEREST_MERCHANT_ID).isEqualTo("000000000")
                    .hasSize(9);
        }

        @Test
        @DisplayName("the identifier is the run date copied verbatim followed by the zero-filled "
                + "six-digit suffix, at exactly sixteen encoded bytes")
        void theIdentifierIsTheRunDateFollowedByTheZeroFilledSuffix() {
            final String first = InterestCalculationProcessor.interestTranId(RUN_DATE, 1L);

            assertThat(first).isEqualTo("2022071800000001").startsWith(RUN_DATE);
            assertThat(first.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(InterestCalculationProcessor.TRAN_ID_WIDTH);
            assertThat(InterestCalculationProcessor.interestTranId(RUN_DATE, 999999L))
                    .isEqualTo("2022071800999999");
        }

        @Test
        @DisplayName("a suffix past six digits sheds its high-order digits, exactly as the legacy "
                + "field would")
        void aSuffixPastSixDigitsShedsItsHighOrderDigits() {
            assertThat(InterestCalculationProcessor.interestTranId(RUN_DATE, 1234567L))
                    .isEqualTo("2022071800234567");
        }

        @Test
        @DisplayName("the identifier refuses a suffix below one and a run date of the wrong width")
        void theIdentifierRefusesABadSuffixOrRunDate() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> InterestCalculationProcessor.interestTranId(RUN_DATE, 0L))
                    .withMessageContaining("counts from one");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> InterestCalculationProcessor.interestTranId("2022", 1L))
                    .withMessageContaining("encoded bytes");
            assertThatNullPointerException()
                    .isThrownBy(() -> InterestCalculationProcessor.interestTranId(null, 1L));
        }

        @Test
        @DisplayName("the description is the literal prefix followed by the account identifier at "
                + "its full eleven digits, and is not padded out by the source")
        void theDescriptionRendersTheAccountIdentifierAtElevenDigits() {
            assertThat(InterestCalculationProcessor.interestDescriptionFor(ACCOUNT_A))
                    .isEqualTo("Int. for a/c 00000000011").hasSize(24);
            assertThat(InterestCalculationProcessor.interestDescriptionFor("11"))
                    .isEqualTo("Int. for a/c 00000000011");
            assertThatNullPointerException()
                    .isThrownBy(() -> InterestCalculationProcessor.interestDescriptionFor(null));
        }

        @Test
        @DisplayName("a well-formed transaction reaches the emitted group with both timestamps "
                + "identical and the card number taken from the cross-reference")
        void aWellFormedTransactionReachesTheEmittedGroup() {
            stubGroup(ACCOUNT_A, singleRowGroup(ACCOUNT_A, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));
            processor.afterStep(stepExecution);

            final Transaction emitted = processor.finalAccruedGroup().orElseThrow()
                    .interestTransactions().get(0);
            assertThat(emitted.getTranId()).isEqualTo("2022071800000001");
            assertThat(emitted.getTranTypeCd()).isEqualTo("01");
            assertThat(emitted.getTranCatCd()).isEqualTo("0005");
            assertThat(emitted.getTranSource()).isEqualTo("System    ");
            assertThat(emitted.getTranDesc()).isEqualTo("Int. for a/c 00000000011");
            assertThat(emitted.getMerchantId()).isEqualTo("000000000");
            assertThat(emitted.getMerchantName()).isBlank();
            assertThat(emitted.getMerchantCity()).isBlank();
            assertThat(emitted.getMerchantZip()).isBlank();
            assertThat(emitted.getTranCardNum()).isEqualTo(CARD_NUMBER)
                    .isNotEqualTo(ACCOUNT_A);
            assertThat(emitted.getTranOrigTs()).isEqualTo(emitted.getTranProcTs())
                    .isEqualTo(FIXED_TIMESTAMP);
            assertThat(emitted.getTranOrigTs().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(InterestCalculationProcessor.BATCH_TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("a description padded out to the field width is still accepted, because the "
                + "source's own assembly leaves the remainder untouched")
        void aPaddedDescriptionIsStillAccepted() {
            final Transaction padded = interestTransaction(ACCOUNT_A, 1L, MULTIPLY_THEN_DIVIDE);
            padded.setTranDesc(InterestCalculationProcessor.interestDescriptionFor(ACCOUNT_A)
                    + blank(InterestCalculationProcessor.TRAN_DESC_WIDTH - 24));
            stubGroup(ACCOUNT_A, group(ACCOUNT_A,
                    List.of(accruedRow(ACCOUNT_A, CATEGORY_CD, MULTIPLY_THEN_DIVIDE, padded)),
                    MULTIPLY_THEN_DIVIDE, 1L, postedAccount(ACCOUNT_A)));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));

            assertThatCode(() -> processor.afterStep(stepExecution)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the rendered record image is exactly 350 encoded bytes, and the width is "
                + "taken from the mapper rather than declared here")
        void theRenderedRecordImageIsExactlyThreeHundredAndFiftyEncodedBytes() {
            final Transaction transaction =
                    interestTransaction(ACCOUNT_A, 1L, MULTIPLY_THEN_DIVIDE);

            assertThat(InterestCalculationProcessor.INTEREST_RECORD_LENGTH)
                    .isEqualTo(TransactionRecordMapper.RECORD_LENGTH).isEqualTo(350);
            assertThat(InterestCalculationProcessor.interestRecordImageBytes(transaction))
                    .hasSize(350);
            assertThat(InterestCalculationProcessor.interestRecordImage(transaction)
                    .getBytes(StandardCharsets.US_ASCII)).hasSize(350);
            assertThatNullPointerException().isThrownBy(
                    () -> InterestCalculationProcessor.interestRecordImageBytes(null));
            assertThatNullPointerException().isThrownBy(
                    () -> InterestCalculationProcessor.interestRecordImage(null));
        }

        @Test
        @DisplayName("a mis-sized record image is refused, and the width is measured on encoded "
                + "bytes rather than on a character count")
        void aMisSizedRecordImageIsRefused() {
            assertThatIllegalStateException().isThrownBy(() -> InterestCalculationProcessor
                    .requireInterestRecordWidth(new byte[349], "2022071800000001"))
                    .withMessageContaining("349").withMessageContaining("350");
            assertThatCode(() -> InterestCalculationProcessor
                    .requireInterestRecordWidth(new byte[350], "2022071800000001"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an identifier that does not carry the expected suffix is refused")
        void anIdentifierWithTheWrongSuffixIsRefused() {
            final Transaction wrong = interestTransaction(ACCOUNT_A, 9L, MULTIPLY_THEN_DIVIDE);
            stubGroup(ACCOUNT_A, group(ACCOUNT_A,
                    List.of(accruedRow(ACCOUNT_A, CATEGORY_CD, MULTIPLY_THEN_DIVIDE, wrong)),
                    MULTIPLY_THEN_DIVIDE, 1L, postedAccount(ACCOUNT_A)));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("TRAN-ID");
        }

        @Test
        @DisplayName("a wrong type code, category code or source is refused")
        void aWrongTypeCategoryOrSourceIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> accrueWith(field -> field.setTranTypeCd("02")))
                    .withMessageContaining("TRAN-TYPE-CD");
            assertThatIllegalStateException()
                    .isThrownBy(() -> accrueWith(field -> field.setTranCatCd("05")))
                    .withMessageContaining("TRAN-CAT-CD");
            assertThatIllegalStateException()
                    .isThrownBy(() -> accrueWith(field -> field.setTranSource("System")))
                    .withMessageContaining("TRAN-SOURCE");
        }

        @Test
        @DisplayName("a wrong description or merchant identifier is refused")
        void aWrongDescriptionOrMerchantIdentifierIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> accrueWith(field -> field.setTranDesc("Interest")))
                    .withMessageContaining("TRAN-DESC");
            assertThatIllegalStateException()
                    .isThrownBy(() -> accrueWith(field -> field.setMerchantId("000000001")))
                    .withMessageContaining("TRAN-MERCHANT-ID");
            assertThatNullPointerException()
                    .isThrownBy(() -> accrueWith(field -> field.setTranDesc(null)))
                    .withMessageContaining("no description");
        }

        @Test
        @DisplayName("a description that carries anything but padding after the account identifier "
                + "is refused, even though it starts correctly")
        void aDescriptionWithTrailingContentIsRefused() {
            assertThatIllegalStateException().isThrownBy(() -> accrueWith(field -> field.setTranDesc(
                    InterestCalculationProcessor.interestDescriptionFor(ACCOUNT_A) + "X")))
                    .withMessageContaining("only field padding");
        }

        @Test
        @DisplayName("a description wider than its hundred-character layout is refused, even when "
                + "everything after the account identifier is padding")
        void aDescriptionWiderThanItsLayoutIsRefused() {
            assertThatIllegalStateException().isThrownBy(() -> accrueWith(field -> field.setTranDesc(
                    InterestCalculationProcessor.interestDescriptionFor(ACCOUNT_A) + blank(80))))
                    .withMessageContaining("wider than TRAN-DESC");
        }

        @Test
        @DisplayName("a blank merchant field wider than its layout is refused, because a blank "
                + "field of the wrong width is still the wrong width")
        void aBlankMerchantFieldWiderThanItsLayoutIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> accrueWith(field -> field.setMerchantZip(blank(11))))
                    .withMessageContaining("wider than its layout at 10");
        }

        @Test
        @DisplayName("a numeric field rendered right-justified has its remaining spaces replaced "
                + "by zeros, exactly as the legacy move does")
        void aRightJustifiedNumericFieldHasItsSpacesReplacedByZeros() {
            assertThat(InterestCalculationProcessor.interestDescriptionFor(" 1"))
                    .isEqualTo("Int. for a/c 00000000001");
        }

        @Test
        @DisplayName("a non-blank merchant name, city or postal code is refused, because the "
                + "source moves spaces into all three")
        void aNonBlankMerchantFieldIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> accrueWith(field -> field.setMerchantName("ACME")))
                    .withMessageContaining("TRAN-MERCHANT-NAME");
            assertThatIllegalStateException()
                    .isThrownBy(() -> accrueWith(field -> field.setMerchantCity("SEATTLE")))
                    .withMessageContaining("TRAN-MERCHANT-CITY");
            assertThatIllegalStateException()
                    .isThrownBy(() -> accrueWith(field -> field.setMerchantZip("98109")))
                    .withMessageContaining("TRAN-MERCHANT-ZIP");
            assertThatNullPointerException()
                    .isThrownBy(() -> accrueWith(field -> field.setMerchantName(null)))
                    .withMessageContaining("TRAN-MERCHANT-NAME");
        }

        @Test
        @DisplayName("an amount that is not the interest the row computed is refused, and neither "
                + "figure appears in the message")
        void aMismatchedAmountIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> accrueWith(field -> field.setTranAmt(new BigDecimal("9.99"))))
                    .withMessageContaining("not the monthly interest")
                    .withMessageNotContaining("9.99");
            assertThatNullPointerException()
                    .isThrownBy(() -> accrueWith(field -> field.setTranAmt(null)))
                    .withMessageContaining("carries no amount");
        }

        @Test
        @DisplayName("timestamps that are not the same value are refused, because one value is "
                + "built once and moved into both fields")
        void timestampsThatDifferAreRefused() {
            assertThatIllegalStateException().isThrownBy(() -> accrueWith(
                    field -> field.setTranProcTs("2022-07-18-23.23.06.130000")))
                    .withMessageContaining("byte-identical");
            assertThatIllegalStateException()
                    .isThrownBy(() -> accrueWith(field -> field.setTranOrigTs("2022-07-18")))
                    .withMessageContaining("TRAN-ORIG-TS");
        }

        @Test
        @DisplayName("a card number of the wrong width is refused, and its value never appears in "
                + "the message because it is a primary account number")
        void aCardNumberOfTheWrongWidthIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> accrueWith(field -> field.setTranCardNum("4111")))
                    .withMessageContaining("TRAN-CARD-NUM")
                    .withMessageNotContaining("4111");
        }

        @Test
        @DisplayName("a card number that changes inside one group is refused, because the "
                + "cross-reference is read once per group")
        void aCardNumberThatChangesInsideAGroupIsRefused() {
            final Transaction first = interestTransaction(ACCOUNT_A, 1L, MULTIPLY_THEN_DIVIDE);
            final Transaction second = interestTransaction(ACCOUNT_A, 2L, MULTIPLY_THEN_DIVIDE);
            second.setTranCardNum("4111111111111112");
            stubGroup(ACCOUNT_A, group(ACCOUNT_A,
                    List.of(accruedRow(ACCOUNT_A, "0005", MULTIPLY_THEN_DIVIDE, first),
                            accruedRow(ACCOUNT_A, "0006", MULTIPLY_THEN_DIVIDE, second)),
                    new BigDecimal("8.74"), 2L, postedAccount(ACCOUNT_A)));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, "0005"));
            processor.process(row(ACCOUNT_A, "0006"));

            assertThatIllegalStateException().isThrownBy(() -> processor.afterStep(stepExecution))
                    .withMessageContaining("differs from the one every earlier transaction");
        }

        @Test
        @DisplayName("the emitted group defends its collections against later mutation")
        void theEmittedGroupDefendsItsCollections() {
            stubGroup(ACCOUNT_A, singleRowGroup(ACCOUNT_A, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));
            processor.afterStep(stepExecution);
            final InterestCalculationProcessor.AccruedAccountGroup closed =
                    processor.finalAccruedGroup().orElseThrow();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> closed.rows().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> closed.interestTransactions().clear());
        }

        /**
         * Accrues a single-row group whose transaction has been mutated by the supplied change, so a
         * per-field refusal can be provoked without repeating the fixture eleven times.
         *
         * @param change the mutation to apply to the otherwise well-formed transaction
         */
        private void accrueWith(final Consumer<Transaction> change) {
            final Transaction transaction =
                    interestTransaction(ACCOUNT_A, 1L, MULTIPLY_THEN_DIVIDE);
            change.accept(transaction);
            stubGroup(ACCOUNT_A, group(ACCOUNT_A,
                    List.of(accruedRow(ACCOUNT_A, CATEGORY_CD, MULTIPLY_THEN_DIVIDE, transaction)),
                    MULTIPLY_THEN_DIVIDE, 1L, postedAccount(ACCOUNT_A)));
            final StepExecution execution = stepExecutionWith(RUN_DATE);
            processor.beforeStep(execution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));
            processor.afterStep(execution);
        }
    }

    // ----------------------------------------------------------------------------------------
    // The emitted row record
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The emitted per-row record")
    final class TheEmittedPerRowRecord {

        @Test
        @DisplayName("it carries the row's three-part key, its balance, its rate and its interest")
        void itCarriesTheRowsKeyBalanceRateAndInterest() {
            stubGroup(ACCOUNT_A, singleRowGroup(ACCOUNT_A, 0L));
            processor.beforeStep(stepExecution);
            processor.process(row(ACCOUNT_A, CATEGORY_CD));
            processor.afterStep(stepExecution);

            final InterestCalculationProcessor.AccruedCategoryRow accrued =
                    processor.finalAccruedGroup().orElseThrow().rows().get(0);
            assertThat(accrued.rowKey().getTrancatAcctId()).isEqualTo(ACCOUNT_A);
            assertThat(accrued.rowKey().getTrancatTypeCd()).isEqualTo(TYPE_CD);
            assertThat(accrued.rowKey().getTrancatCd()).isEqualTo(CATEGORY_CD);
            assertThat(accrued.categoryBalance()).isEqualByComparingTo("1000.00");
            assertThat(accrued.disclosedRate()).isEqualByComparingTo("5.25");
            assertThat(accrued.monthlyInterest()).isEqualByComparingTo(MULTIPLY_THEN_DIVIDE);
            assertThat(accrued.tranIdSuffix()).isEqualTo(1L);
            assertThat(accrued.producedTransaction()).isTrue();
            assertThat(accrued.interestTransaction()).isNotNull();
        }

        @Test
        @DisplayName("every measured value of a row is required, and the transaction is not, "
                + "because its absence is the rate gate's own signal")
        void everyMeasuredValueIsRequiredAndTheTransactionIsNot() {
            final DisclosureGroupId key =
                    InterestCalculationProcessor.primaryDisclosureKey(GROUP_ID, TYPE_CD, CATEGORY_CD);

            assertThatNullPointerException().isThrownBy(
                    () -> new InterestCalculationProcessor.AccruedCategoryRow(null, key,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, false, false,
                            0L, null));
            assertThatCode(() -> new InterestCalculationProcessor.AccruedCategoryRow(
                    new TransactionCategoryBalanceId(ACCOUNT_A, TYPE_CD, CATEGORY_CD),
                    key, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, false, false, 0L,
                    null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("every value of a closed group is required")
        void everyValueOfAClosedGroupIsRequired() {
            assertThatNullPointerException().isThrownBy(
                    () -> new InterestCalculationProcessor.AccruedAccountGroup(null, RUN_DATE,
                            BigDecimal.ZERO, List.of(), List.of(), postedAccount(ACCOUNT_A), false,
                            false, 0, 0L, FIXED_TIMESTAMP));
            assertThatNullPointerException().isThrownBy(
                    () -> new InterestCalculationProcessor.AccruedAccountGroup(ACCOUNT_A, RUN_DATE,
                            BigDecimal.ZERO, List.of(), List.of(), null, false, false, 0, 0L,
                            FIXED_TIMESTAMP));
            assertThatNullPointerException().isThrownBy(
                    () -> new InterestCalculationProcessor.AccruedAccountGroup(ACCOUNT_A, RUN_DATE,
                            BigDecimal.ZERO, List.of(), List.of(), postedAccount(ACCOUNT_A), false,
                            false, 0, 0L, null));
        }
    }

    // ----------------------------------------------------------------------------------------
    // The legacy identity and the recorded anomalies
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The legacy identity and the recorded job-stream anomaly")
    final class TheLegacyIdentity {

        @Test
        @DisplayName("the legacy program, job and step are named, so a traceability row can cite "
                + "them without restating a literal")
        void theLegacyProgramJobAndStepAreNamed() {
            assertThat(InterestCalculationProcessor.LEGACY_PROGRAM).isEqualTo("CBACT04C");
            assertThat(InterestCalculationProcessor.LEGACY_JOB).isEqualTo("INTCALC");
            assertThat(InterestCalculationProcessor.LEGACY_STEP).isEqualTo("STEP15");
            assertThat(InterestCalculationProcessor.OUTPUT_RESOURCE).isEqualTo("TRANSACT");
        }

        @Test
        @DisplayName("the data definition the job stream allocates and the program never "
                + "references stays recorded, and stays a name and nothing more")
        void theUnreferencedDataDefinitionStaysRecorded() {
            assertThat(InterestCalculationProcessor.LEGACY_UNREFERENCED_DD).isEqualTo("XREFFIL1")
                    .isNotEqualTo(InterestCalculationProcessor.OUTPUT_RESOURCE);
        }

        @Test
        @DisplayName("every declared width matches the record layouts the copybooks fix")
        void everyDeclaredWidthMatchesTheCopybooks() {
            assertThat(InterestCalculationProcessor.PARM_DATE_WIDTH).isEqualTo(10);
            assertThat(InterestCalculationProcessor.TRAN_ID_SUFFIX_WIDTH).isEqualTo(6);
            assertThat(InterestCalculationProcessor.TRAN_ID_WIDTH).isEqualTo(16);
            assertThat(InterestCalculationProcessor.ACCT_ID_WIDTH).isEqualTo(11);
            assertThat(InterestCalculationProcessor.ACCT_GROUP_ID_WIDTH).isEqualTo(10);
            assertThat(InterestCalculationProcessor.CARD_NUM_WIDTH).isEqualTo(16);
            assertThat(InterestCalculationProcessor.MERCHANT_NAME_WIDTH).isEqualTo(50);
            assertThat(InterestCalculationProcessor.MERCHANT_CITY_WIDTH).isEqualTo(50);
            assertThat(InterestCalculationProcessor.MERCHANT_ZIP_WIDTH).isEqualTo(10);
            assertThat(InterestCalculationProcessor.TRAN_DESC_WIDTH).isEqualTo(100);
            assertThat(InterestCalculationProcessor.BATCH_TIMESTAMP_WIDTH).isEqualTo(26);
            assertThat(InterestCalculationProcessor.NO_TRAN_ID_SUFFIX).isZero();
        }
    }

    // ----------------------------------------------------------------------------------------
    // The batch timestamp gateway
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The batch timestamp gateway")
    final class TheBatchTimestampGateway {

        @Test
        @DisplayName("it produces the BATCH twenty-six-character form: a hyphen before the time, "
                + "dots inside it, two-digit hundredths and a four-character tail")
        void itProducesTheBatchForm() {
            final InterestCalculationProcessor.BatchTimestampGateway gateway =
                    new InterestCalculationProcessor.BatchTimestampGateway(meterRegistry,
                            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

            final String stamp = gateway.batchTimestamp();

            assertThat(stamp).isEqualTo(FIXED_TIMESTAMP);
            assertThat(stamp.getBytes(StandardCharsets.US_ASCII)).hasSize(26);
            assertThat(InterestCalculationProcessor.BatchTimestampGateway.timestampWidth())
                    .isEqualTo(26);
        }

        @Test
        @DisplayName("it refuses to be driven as a tasklet, so a zero-record interest run cannot "
                + "report success")
        void itRefusesToBeDrivenAsATasklet() {
            final InterestCalculationProcessor.BatchTimestampGateway gateway =
                    new InterestCalculationProcessor.BatchTimestampGateway(meterRegistry,
                            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

            assertThatIllegalStateException().isThrownBy(gateway::run)
                    .withMessageContaining("wiring error");
        }

        @Test
        @DisplayName("each of the four template hooks refuses individually, so no partial "
                + "lifecycle can be driven through it either")
        void eachTemplateHookRefusesIndividually() {
            final InterestCalculationProcessor.BatchTimestampGateway gateway =
                    new InterestCalculationProcessor.BatchTimestampGateway(meterRegistry,
                            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

            assertThatIllegalStateException().isThrownBy(gateway::openResources)
                    .withMessageContaining("open family");
            assertThatIllegalStateException().isThrownBy(gateway::readNextRecord)
                    .withMessageContaining("read family");
            assertThatIllegalStateException()
                    .isThrownBy(() -> gateway.processRecord(row(ACCOUNT_A, CATEGORY_CD)))
                    .withMessageContaining("process family");
            assertThatIllegalStateException().isThrownBy(gateway::closeResources)
                    .withMessageContaining("close family");
        }
    }

    // ----------------------------------------------------------------------------------------
    // Test doubles
    // ----------------------------------------------------------------------------------------

}
