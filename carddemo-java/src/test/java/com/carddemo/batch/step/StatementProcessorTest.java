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

import com.carddemo.api.dto.StatementSummary;
import com.carddemo.service.StatementGenerationService;
import com.carddemo.service.StatementGenerationService.StatementRun;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The statement assembly stage of the CREASTMT job: the step-scoped wrapper that drives CBSTM03A once
 * per run and refuses to report a completed generation that failed any of the four properties the
 * stage promises.
 *
 * <p>What makes this class worth testing carefully is that it is not a pass-through. It is the place
 * where four structural guarantees about the legacy program's behaviour are checked, and each of them
 * exists because the corresponding defect would otherwise reach a downstream writer and produce a file
 * that is the wrong shape rather than an error:
 *
 * <ul>
 *   <li><em>The item must be the one work resource the preceding steps materialise.</em> The step has
 *       four inputs but only one arrives as an item; the other three the program opens itself. A
 *       differently named resource is a wiring mistake and is refused rather than processed.</li>
 *   <li><em>The dispatcher must have been entered in the one order the program can take</em>, and must
 *       have ended on the catch-all clause rather than on a phase selector - which is how a run that
 *       terminated early is told apart from one that ran to the end.</li>
 *   <li><em>The tabulated counts must respect the card table's bounds</em>, including the relation
 *       between cards and transactions: a card enters the table only when a transaction for it is
 *       read, so a run reporting fewer transactions than cards is impossible.</li>
 *   <li><em>Every record of both streams must be exactly the width its dataset declares</em> - eighty
 *       bytes for the plain statement and one hundred for the HTML one.</li>
 * </ul>
 *
 * <p>All four checks sit inside the timed region and inside the failure guard, so a run that fails one
 * is timed as a failure and counted as nothing. That pairing is asserted directly, because a guard
 * that threw but still incremented the record counters would corrupt the job's own accounting.
 */
@DisplayName("StatementProcessor - the CREASTMT statement assembly stage")
class StatementProcessorTest {

    /** The one work resource that arrives as the step's item. */
    private static final String ITEM = StatementProcessor.INPUT_DD_TRNXFILE;

    private StatementGenerationService statementGenerationService;

    private MeterRegistry meterRegistry;

    private UnaryOperator<String> revealer;

    private UnaryOperator<String> sealer;

    private StatementProcessor processor;

    @BeforeEach
    void setUp() {
        statementGenerationService = mock(StatementGenerationService.class);
        meterRegistry = new SimpleMeterRegistry();
        revealer = value -> "revealed:" + value;
        sealer = value -> "sealed:" + value;
        processor = new StatementProcessor(statementGenerationService, revealer, sealer,
                meterRegistry);
    }

    /**
     * Builds the dispatch trail a complete run leaves: the five phase entries in their only possible
     * order, followed by the catch-all clause that ends the run.
     *
     * @return the dispatch trail
     */
    private static List<String> completeDispatchTrail() {
        final List<String> trail = new ArrayList<>(StatementProcessor.EXPECTED_DISPATCH_SEQUENCE);
        trail.add("END-OF-RUN");
        return trail;
    }

    /**
     * Builds one plain statement record at exactly the declared width.
     *
     * @return an eighty-byte record
     */
    private static String statementRecord() {
        return "S".repeat(StatementProcessor.STATEMENT_RECORD_LENGTH);
    }

    /**
     * Builds one HTML statement record at exactly the declared width.
     *
     * @return a one-hundred-byte record
     */
    private static String htmlRecord() {
        return "H".repeat(StatementProcessor.HTML_RECORD_LENGTH);
    }

    /**
     * Builds a run that satisfies all four properties.
     *
     * @param statementRecords the plain records the run produced
     * @param htmlRecords the HTML records the run produced
     * @param cards how many distinct cards were tabulated
     * @param transactions how many transactions were tabulated
     * @param statements how many statements were written
     * @return the run
     */
    private static StatementRun run(final List<String> statementRecords,
            final List<String> htmlRecords, final int cards, final int transactions,
            final int statements) {
        return new StatementRun(statementRecords, htmlRecords, List.<StatementSummary>of(),
                completeDispatchTrail(), cards, transactions, statements);
    }

    /**
     * Builds a well-formed run of one card, one transaction and one statement.
     *
     * @return the run
     */
    private static StatementRun wellFormedRun() {
        return run(List.of(statementRecord()), List.of(htmlRecord()), 1, 1, 1);
    }

    /**
     * Builds a run whose dispatch trail is the supplied one, with everything else well formed.
     *
     * @param trail the dispatch trail to report
     * @return the run
     */
    private static StatementRun runWithTrail(final List<String> trail) {
        return new StatementRun(List.of(statementRecord()), List.of(htmlRecord()),
                List.<StatementSummary>of(), trail, 1, 1, 1);
    }

    /**
     * Reads a counter's tally.
     *
     * @param metric the counter name
     * @return the tally, as a whole number of items
     */
    private long counted(final String metric) {
        final io.micrometer.core.instrument.Counter counter =
                meterRegistry.find(metric).counter();
        return (counter == null) ? 0L : (long) counter.count();
    }

    /**
     * Reads the count of the stage timer carrying an outcome tag.
     *
     * @param outcome the expected outcome tag
     * @return the number of runs recorded
     */
    private long timed(final String outcome) {
        final io.micrometer.core.instrument.Timer timer =
                meterRegistry.find("carddemo.batch.statement.generation")
                        .tag("resource", StatementProcessor.INPUT_DD_TRNXFILE)
                        .tag("outcome", outcome).timer();
        return (timer == null) ? 0L : timer.count();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("every collaborator is required, and the two regulated-field operations are "
                + "required by name because the identity operation is not a substitute for either")
        void everyCollaboratorIsRequired() {
            assertThatNullPointerException().isThrownBy(() ->
                    new StatementProcessor(null, revealer, sealer, meterRegistry))
                    .withMessageContaining("statement generation service");
            assertThatNullPointerException().isThrownBy(() ->
                    new StatementProcessor(statementGenerationService, null, sealer, meterRegistry))
                    .withMessageContaining("revealing operation");
            assertThatNullPointerException().isThrownBy(() ->
                    new StatementProcessor(statementGenerationService, revealer, null, meterRegistry))
                    .withMessageContaining("sealing operation");
            assertThatNullPointerException().isThrownBy(() ->
                    new StatementProcessor(statementGenerationService, revealer, sealer, null))
                    .withMessageContaining("meter registry");
        }

        @Test
        @DisplayName("the stage names the legacy job, program, subprogram and the four steps it stands "
                + "for, so the traceability back to the job stream is in the type itself")
        void theStageNamesItsLegacyProvenance() {
            assertThat(StatementProcessor.LEGACY_JOB).isEqualTo("CREASTMT");
            assertThat(StatementProcessor.LEGACY_PROGRAM).isEqualTo("CBSTM03A");
            assertThat(StatementProcessor.LEGACY_SUBPROGRAM).isEqualTo("CBSTM03B");
            assertThat(StatementProcessor.LEGACY_SORT_STEP).isEqualTo("STEP010");
            assertThat(StatementProcessor.LEGACY_LOAD_STEP).isEqualTo("STEP020");
            assertThat(StatementProcessor.LEGACY_CLEAR_STEP).isEqualTo("STEP030");
            assertThat(StatementProcessor.LEGACY_STATEMENT_STEP).isEqualTo("STEP040");
        }

        @Test
        @DisplayName("the four inputs and two outputs carry their legacy names at the declared width, "
                + "and the two record widths are the ones the datasets declare")
        void theResourceNamesAndRecordWidthsAreTheLegacyOnes() {
            assertThat(StatementProcessor.INPUT_DD_TRNXFILE).isEqualTo("TRNXFILE");
            assertThat(StatementProcessor.INPUT_DD_XREFFILE).isEqualTo("XREFFILE");
            assertThat(StatementProcessor.INPUT_DD_CUSTFILE).isEqualTo("CUSTFILE");
            assertThat(StatementProcessor.INPUT_DD_ACCTFILE).isEqualTo("ACCTFILE");
            assertThat(StatementProcessor.OUTPUT_DD_STMTFILE).isEqualTo("STMTFILE");
            assertThat(StatementProcessor.OUTPUT_DD_HTMLFILE).isEqualTo("HTMLFILE");
            assertThat(StatementProcessor.DD_NAME_WIDTH).isEqualTo(8);
            assertThat(StatementProcessor.STATEMENT_RECORD_LENGTH).isEqualTo(80);
            assertThat(StatementProcessor.HTML_RECORD_LENGTH).isEqualTo(100);
        }

        @Test
        @DisplayName("the expected dispatch sequence is the five phase entries in their only order, "
                + "and one more entry is expected for the clause that ends the run")
        void theExpectedDispatchSequenceIsDeclaredWithItsTerminalEntry() {
            assertThat(StatementProcessor.EXPECTED_DISPATCH_SEQUENCE).containsExactly("TRNXFILE",
                    "READTRNX", "XREFFILE", "CUSTFILE", "ACCTFILE");
            assertThat(StatementProcessor.EXPECTED_DISPATCH_ENTRY_COUNT).isEqualTo(6);
            assertThat(StatementProcessor.PHASE_SELECTOR_READTRNX).isEqualTo("READTRNX");
        }
    }

    @Nested
    @DisplayName("The item the stage accepts")
    class AcceptedItem {

        @Test
        @DisplayName("an absent item is refused, because an absent input is expressed by the reader "
                + "yielding nothing rather than by yielding null")
        void anAbsentItemIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> processor.process(null))
                    .withMessageContaining("null work-resource name");
        }

        @Test
        @DisplayName("a resource that is not the work file is refused, and the message names the three "
                + "inputs the program opens itself so the wiring mistake is diagnosable")
        void aResourceThatIsNotTheWorkFileIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> processor.process(StatementProcessor.INPUT_DD_XREFFILE))
                    .withMessageContaining("TRNXFILE")
                    .withMessageContaining("XREFFILE")
                    .withMessageContaining("CUSTFILE")
                    .withMessageContaining("ACCTFILE");
        }

        @Test
        @DisplayName("a resource of the wrong width is refused and the measured width is reported")
        void aResourceOfTheWrongWidthIsRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> processor.process("TRNX"))
                    .withMessageContaining("4 encoded byte(s)");
        }

        @Test
        @DisplayName("a refused item never reaches the generator")
        void aRefusedItemNeverReachesTheGenerator() {
            assertThatIllegalArgumentException().isThrownBy(() -> processor.process("SOMETHIN"));

            verify(statementGenerationService, org.mockito.Mockito.never()).generate(any(), any());
        }
    }

    @Nested
    @DisplayName("A run that satisfies every property")
    class CompletedRun {

        @Test
        @DisplayName("the generator is driven with the two regulated-field operations the stage holds, "
                + "and its run is handed on unchanged")
        void theGeneratorIsDrivenWithTheRegulatedFieldOperations() {
            StatementRun produced = wellFormedRun();
            when(statementGenerationService.generate(same(revealer), same(sealer)))
                    .thenReturn(produced);

            StatementRun answered = processor.process(ITEM);

            assertThat(answered).isSameAs(produced);
            verify(statementGenerationService).generate(same(revealer), same(sealer));
        }

        @Test
        @DisplayName("the run is timed as completed")
        void theRunIsTimedAsCompleted() {
            when(statementGenerationService.generate(any(), any())).thenReturn(wellFormedRun());

            processor.process(ITEM);

            assertThat(timed("COMPLETED")).isEqualTo(1L);
            assertThat(timed("FAILED")).isZero();
        }

        @Test
        @DisplayName("both record streams and the statement tally are counted, each against the "
                + "resource it belongs to")
        void bothRecordStreamsAndTheStatementTallyAreCounted() {
            when(statementGenerationService.generate(any(), any())).thenReturn(run(
                    List.of(statementRecord(), statementRecord(), statementRecord()),
                    List.of(htmlRecord(), htmlRecord()), 2, 5, 4));

            processor.process(ITEM);

            assertThat(counted("carddemo.batch.statement.records")).isEqualTo(3L);
            assertThat(counted("carddemo.batch.statement.htmlRecords")).isEqualTo(2L);
            assertThat(counted("carddemo.batch.statement.statements")).isEqualTo(4L);
        }

        @Test
        @DisplayName("a run that produced no records at all is still a completed run, and the "
                + "counters simply do not move")
        void aRunThatProducedNoRecordsIsStillCompleted() {
            when(statementGenerationService.generate(any(), any()))
                    .thenReturn(run(List.of(), List.of(), 0, 0, 0));

            assertThat(processor.process(ITEM)).isNotNull();
            assertThat(counted("carddemo.batch.statement.records")).isZero();
            assertThat(counted("carddemo.batch.statement.htmlRecords")).isZero();
            assertThat(timed("COMPLETED")).isEqualTo(1L);
        }

        @Test
        @DisplayName("repeated runs accumulate rather than replacing each other")
        void repeatedRunsAccumulate() {
            when(statementGenerationService.generate(any(), any())).thenReturn(wellFormedRun());

            processor.process(ITEM);
            processor.process(ITEM);

            assertThat(counted("carddemo.batch.statement.records")).isEqualTo(2L);
            assertThat(timed("COMPLETED")).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("The generator's own result must be present")
    class AbsentResult {

        @Test
        @DisplayName("a generator that reported no result at all fails the stage and is timed as a "
                + "failure")
        void anAbsentResultFailsTheStage() {
            when(statementGenerationService.generate(any(), any())).thenReturn(null);

            assertThatNullPointerException().isThrownBy(() -> processor.process(ITEM))
                    .withMessageContaining("CBSTM03A")
                    .withMessageContaining("TRNXFILE");
            assertThat(timed("FAILED")).isEqualTo(1L);
            assertThat(timed("COMPLETED")).isZero();
        }
    }

    @Nested
    @DisplayName("The dispatch-sequence proof")
    class DispatchSequenceProof {

        @Test
        @DisplayName("a trail with too few entries is refused, and the expected count is reported")
        void aTrailWithTooFewEntriesIsRefused() {
            when(statementGenerationService.generate(any(), any())).thenReturn(
                    runWithTrail(List.of("TRNXFILE", "READTRNX", "XREFFILE")));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(ITEM))
                    .withMessageContaining("entered 3 time(s) rather than 6");
        }

        @Test
        @DisplayName("a trail with too many entries is refused")
        void aTrailWithTooManyEntriesIsRefused() {
            List<String> tooMany = completeDispatchTrail();
            tooMany.add("END-OF-RUN");
            when(statementGenerationService.generate(any(), any()))
                    .thenReturn(runWithTrail(tooMany));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(ITEM))
                    .withMessageContaining("entered 7 time(s) rather than 6");
        }

        @Test
        @DisplayName("a trail whose phases ran in the wrong order is refused, naming the entry and "
                + "both the observed and the expected phase")
        void aTrailInTheWrongOrderIsRefused() {
            when(statementGenerationService.generate(any(), any())).thenReturn(runWithTrail(
                    List.of("TRNXFILE", "READTRNX", "CUSTFILE", "XREFFILE", "ACCTFILE",
                            "END-OF-RUN")));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(ITEM))
                    .withMessageContaining("entry 2")
                    .withMessageContaining("'CUSTFILE'")
                    .withMessageContaining("'XREFFILE'");
        }

        @Test
        @DisplayName("a trail that ended on a phase selector rather than the catch-all clause is "
                + "refused, which is how an early termination is told from a complete run")
        void aTrailEndingOnAPhaseSelectorIsRefused() {
            when(statementGenerationService.generate(any(), any())).thenReturn(runWithTrail(
                    List.of("TRNXFILE", "READTRNX", "XREFFILE", "CUSTFILE", "ACCTFILE",
                            "ACCTFILE")));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(ITEM))
                    .withMessageContaining("phase selector 'ACCTFILE'");
        }

        @Test
        @DisplayName("a failed proof is timed as a failure and moves no counter, so the job's "
                + "accounting is never credited for a run it rejected")
        void aFailedProofMovesNoCounter() {
            when(statementGenerationService.generate(any(), any()))
                    .thenReturn(runWithTrail(List.of("TRNXFILE")));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(ITEM));

            assertThat(timed("FAILED")).isEqualTo(1L);
            assertThat(timed("COMPLETED")).isZero();
            assertThat(counted("carddemo.batch.statement.records")).isZero();
            assertThat(counted("carddemo.batch.statement.htmlRecords")).isZero();
            assertThat(counted("carddemo.batch.statement.statements")).isZero();
        }
    }

    @Nested
    @DisplayName("The bounded-grouping proof")
    class BoundedGroupingProof {

        @Test
        @DisplayName("more tabulated cards than the card table holds is refused")
        void tooManyCardsIsRefused() {
            int overCapacity = StatementGenerationService.MAX_CARD_ENTRIES + 1;
            when(statementGenerationService.generate(any(), any())).thenReturn(run(
                    List.of(statementRecord()), List.of(htmlRecord()), overCapacity, overCapacity,
                    1));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(ITEM))
                    .withMessageContaining("card(s) tabulated");
        }

        @Test
        @DisplayName("fewer transactions than cards is refused, because a card enters the table only "
                + "when a transaction for it is read")
        void fewerTransactionsThanCardsIsRefused() {
            when(statementGenerationService.generate(any(), any())).thenReturn(
                    run(List.of(statementRecord()), List.of(htmlRecord()), 5, 3, 1));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(ITEM))
                    .withMessageContaining("transaction(s) tabulated across 5 card(s)");
        }

        @Test
        @DisplayName("more transactions than the tabulated cards could carry is refused")
        void tooManyTransactionsForTheTabulatedCardsIsRefused() {
            int beyondReach = 2 * StatementGenerationService.MAX_TRANSACTIONS_PER_CARD + 1;
            when(statementGenerationService.generate(any(), any())).thenReturn(
                    run(List.of(statementRecord()), List.of(htmlRecord()), 2, beyondReach, 1));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(ITEM))
                    .withMessageContaining("transaction(s) tabulated");
        }

        @Test
        @DisplayName("a negative statement tally is refused")
        void aNegativeStatementTallyIsRefused() {
            when(statementGenerationService.generate(any(), any())).thenReturn(
                    run(List.of(statementRecord()), List.of(htmlRecord()), 1, 1, -1));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(ITEM))
                    .withMessageContaining("statement(s) written");
        }

        @Test
        @DisplayName("a run at exactly the table's capacity is accepted, so the bound is inclusive")
        void aRunAtExactlyCapacityIsAccepted() {
            int cards = StatementGenerationService.MAX_CARD_ENTRIES;
            int transactions = cards * StatementGenerationService.MAX_TRANSACTIONS_PER_CARD;
            when(statementGenerationService.generate(any(), any())).thenReturn(
                    run(List.of(statementRecord()), List.of(htmlRecord()), cards, transactions,
                            cards));

            assertThat(processor.process(ITEM)).isNotNull();
            assertThat(timed("COMPLETED")).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("The record-width proof")
    class RecordWidthProof {

        @Test
        @DisplayName("a plain statement record narrower than eighty bytes is refused")
        void aNarrowStatementRecordIsRefused() {
            when(statementGenerationService.generate(any(), any())).thenReturn(
                    run(List.of("TOO SHORT"), List.of(htmlRecord()), 1, 1, 1));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> processor.process(ITEM))
                    .withMessageContaining("STMTFILE");
        }

        @Test
        @DisplayName("a plain statement record wider than eighty bytes is refused")
        void aWideStatementRecordIsRefused() {
            when(statementGenerationService.generate(any(), any())).thenReturn(
                    run(List.of("S".repeat(StatementProcessor.STATEMENT_RECORD_LENGTH + 1)),
                            List.of(htmlRecord()), 1, 1, 1));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> processor.process(ITEM))
                    .withMessageContaining("STMTFILE");
        }

        @Test
        @DisplayName("an HTML record of the wrong width is refused, and the message names its own "
                + "dataset rather than the plain one")
        void anHtmlRecordOfTheWrongWidthIsRefused() {
            when(statementGenerationService.generate(any(), any())).thenReturn(
                    run(List.of(statementRecord()),
                            List.of("H".repeat(StatementProcessor.HTML_RECORD_LENGTH - 1)), 1, 1, 1));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> processor.process(ITEM))
                    .withMessageContaining("HTMLFILE");
        }

        @Test
        @DisplayName("a bad record in a later position is caught, not only the first one")
        void aBadRecordInALaterPositionIsCaught() {
            when(statementGenerationService.generate(any(), any())).thenReturn(
                    run(List.of(statementRecord(), statementRecord(), "SHORT"),
                            List.of(htmlRecord()), 1, 1, 1));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> processor.process(ITEM));
            assertThat(timed("FAILED")).isEqualTo(1L);
        }

        @Test
        @DisplayName("records at exactly the declared widths are accepted")
        void recordsAtExactlyTheDeclaredWidthsAreAccepted() {
            when(statementGenerationService.generate(any(), any())).thenReturn(wellFormedRun());

            StatementRun answered = processor.process(ITEM);

            assertThat(answered.statementRecords()).allMatch(
                    record -> record.length() == StatementProcessor.STATEMENT_RECORD_LENGTH);
            assertThat(answered.htmlRecords()).allMatch(
                    record -> record.length() == StatementProcessor.HTML_RECORD_LENGTH);
        }
    }
}
