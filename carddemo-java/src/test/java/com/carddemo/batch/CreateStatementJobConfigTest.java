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
package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.CreateStatementJobConfig.ClearStatementOutputsProgram;
import com.carddemo.batch.CreateStatementJobConfig.GenerateStatementsProgram;
import com.carddemo.batch.CreateStatementJobConfig.InMemoryTransactionWorkResource;
import com.carddemo.batch.CreateStatementJobConfig.LoadWorkResourceProgram;
import com.carddemo.batch.CreateStatementJobConfig.OrderAndReprojectProgram;
import com.carddemo.batch.CreateStatementJobConfig.StatementOutput;
import com.carddemo.batch.CreateStatementJobConfig.TransactionWorkResource;
import com.carddemo.batch.step.StatementProcessor;
import com.carddemo.domain.Transaction;
import com.carddemo.exception.AbendException;
import com.carddemo.repository.TransactionScanRepository;
import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.service.StatementGenerationService;
import com.carddemo.service.StatementGenerationService.StatementRun;
import com.carddemo.service.StatementLineSummary;
import com.carddemo.service.StatementTransactionSource;
import com.carddemo.util.TransactionRecordMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.flow.FlowJob;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Unit specification for {@link CreateStatementJobConfig}, the migrated customer statement job.
 *
 * <p>Every expectation here is measured from the legacy estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is transcribed.
 *
 * <p>The properties these cases exist to protect, each of which would otherwise be a defect that
 * compiles and passes a test written under the same misunderstanding:
 *
 * <ul>
 *   <li>the job holds <strong>exactly four steps and exactly three failure-ending transitions</strong>,
 *       because the measured member declares five steps of which one is absorbed and gates the last
 *       three;</li>
 *   <li>the ordering is <strong>card number then transaction identifier, both ascending, both compared
 *       as character data</strong> - never numeric, never zoned-decimal, which is the typing the report
 *       job applies to the very same field;</li>
 *   <li>the reprojection keeps <strong>328 of the record's 350 bytes</strong>, truncates
 *       <strong>exactly two</strong> processing-timestamp bytes and drops the twenty-byte trailing
 *       filler - measured in <em>encoded bytes</em>, never in character counts;</li>
 *   <li>the leading <strong>thirty-two bytes</strong> of a projected record are the transient work
 *       resource's key, so the ordering the first step establishes is the key sequence the load step
 *       holds;</li>
 *   <li>the HTML output is <strong>one hundred</strong> encoded bytes per record and the plain-text
 *       output <strong>eighty</strong>, which resolves the record-length conflict the member carries;
 *       and</li>
 *   <li>a terminal input or output failure logs its raw file status and <em>then</em> abends, an
 *       ordering the job configuration delegates rather than re-implements.</li>
 * </ul>
 */
@DisplayName("CreateStatementJobConfig - four steps, three gates, and a job-local character order")
class CreateStatementJobConfigTest {

    /** Origin descriptor of a point-of-sale purchase, at its contractual ten characters. */
    private static final String POS_SOURCE = "POS TERM  ";

    /** A representative amount at the layout's scale of two. */
    private static final BigDecimal AMOUNT = new BigDecimal("42.75");

    /** A stamped origination timestamp at the layout's twenty-six characters. */
    private static final String ORIGIN_TIMESTAMP = "2022-07-19-23.23.05.000000";

    /** A stamped processing timestamp, so the two truncated bytes are observable. */
    private static final String PROCESS_TIMESTAMP = "2022-07-20-01.02.03.040000";

    /** Where the configuration's own source sits, for the structural cases. */
    private static final Path CONFIGURATION_SOURCE =
            Path.of("src/main/java/com/carddemo/batch/CreateStatementJobConfig.java");

    /** The framework's metadata repository, mocked because no step is executed through a launcher. */
    private final JobRepository jobRepository = mock(JobRepository.class);

    /** The transaction manager, mocked for the same reason. */
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);

    /** The shared job-boundary diagnostic. */
    private final JobExecutionListener boundaryListener = mock(JobExecutionListener.class);

    /** The shared run incrementer, real because its identity is asserted. */
    private final JobParametersIncrementer runIncrementer = new RunIdIncrementer();

    /** The transaction master, mocked so an ordering pass can be observed without a database. */
    private final com.carddemo.repository.TransactionRepository transactionRepository =
            mock(com.carddemo.repository.TransactionRepository.class);

    /** Bounded scan adapter over the existing repository double. */
    private final TransactionScanRepository transactionScanRepository = (cursor, limit) ->
            transactionRepository.findAll(Sort.by(Sort.Direction.ASC, "tranId"))
                    .stream()
                    .filter(record -> record.getTranId().compareTo(cursor) > 0)
                    .sorted(java.util.Comparator.comparing(Transaction::getTranId))
                    .limit(limit.max())
                    .toList();

    /** The statement generator, mocked so one whole run can be handed back as a fixture. */
    private final StatementGenerationService generationService =
            mock(StatementGenerationService.class);

    /** The field-protection owner; the two operations it backs are never exercised on this path. */
    private final SensitiveFieldEncryptionService fieldEncryption =
            mock(SensitiveFieldEncryptionService.class);

    /** A real registry, because every step is timed and the timing must not be suppressed. */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /** A fixed clock, so lifecycle boundaries are reproducible. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC);

    /**
     * Builds the configuration under test against a temporary staging area.
     *
     * @param  staging the temporary staging directory
     * @return the configuration, wired exactly as the container wires it
     */
    private CreateStatementJobConfig config(final Path staging) {
        return new CreateStatementJobConfig(jobRepository, transactionManager, boundaryListener,
                runIncrementer, transactionScanRepository, generationService, fieldEncryption,
                meterRegistry, clock, staging.toString(), "AWS.M2.CARDDEMO.TRXFL.SEQ",
                "AWS.M2.CARDDEMO.STATEMNT.PS", "AWS.M2.CARDDEMO.STATEMNT.HTML");
    }

    /**
     * Builds a transaction whose every field already sits at its contractual width, then rounds it
     * through the mapper so the entity is exactly the one a reader would deliver.
     *
     * @param  tranId  identifier, sixteen characters, carried through verbatim
     * @param  cardNum card number, sixteen characters, carried through verbatim
     * @return an entity identical to one parsed from a well-formed record image
     */
    private static Transaction record(final String tranId, final String cardNum) {
        return TransactionRecordMapper.fromRecord(TransactionRecordMapper.toRecordBytes(
                new Transaction(tranId, "01", "0005", POS_SOURCE, pad("DESCRIPTION " + tranId, 100),
                        AMOUNT, "000123456", pad("ACME MERCHANT", 50), pad("SEATTLE", 50),
                        "98101-0001", cardNum, ORIGIN_TIMESTAMP, PROCESS_TIMESTAMP)));
    }

    /**
     * Left-justifies a value into a field of the given width, space padded as the layout is.
     *
     * @param  value the value to place
     * @param  width the field's width
     * @return the padded value
     */
    private static String pad(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Measures a record the way the record length declares it: in encoded bytes.
     *
     * @param  value the record
     * @return its encoded byte count
     */
    private static int encoded(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Projects one posted transaction into the service-owned statement-line carrier.
     *
     * <p>The batch tier consumes this type directly because {@code batch -> service} is the intended
     * package direction. There is no API adapter on this path: no controller publishes a statement
     * line, while the wire-side {@code StatementSummary} remains a separately published schema.
     *
     * @param tranId  transaction identifier, exactly sixteen characters
     * @param cardNum card number, exactly sixteen characters
     * @return the statement projection that the generation service would return
     */
    private static StatementLineSummary statementSummary(final String tranId,
            final String cardNum) {
        final Transaction transaction = record(tranId, cardNum);
        return new StatementLineSummary(transaction.getTranCardNum(), transaction.getTranId(),
                transaction.getTranTypeCd(), transaction.getTranCatCd(),
                transaction.getTranSource(), transaction.getTranDesc(), transaction.getTranAmt(),
                transaction.getMerchantId(), transaction.getMerchantName(),
                transaction.getMerchantCity(), transaction.getMerchantZip(),
                transaction.getTranOrigTs(), transaction.getTranProcTs());
    }

    /**
     * A statement run that satisfies every proof the statement stage applies to one, so that a
     * generation lifecycle can be exercised without a database.
     *
     * @return a run of two records per stream at their two declared widths
     */
    private static StatementRun completedRun() {
        final List<String> dispatched = new ArrayList<>(StatementProcessor.EXPECTED_DISPATCH_SEQUENCE);
        // The terminal entry is the catch-all clause, which is by definition not a phase selector.
        dispatched.add("TERMINATED");
        return new StatementRun(
                List.of(pad("STATEMENT LINE ONE", CreateStatementJobConfig.STATEMENT_RECORD_LENGTH),
                        pad("STATEMENT LINE TWO", CreateStatementJobConfig.STATEMENT_RECORD_LENGTH)),
                List.of(pad("<p>HTML LINE ONE</p>", CreateStatementJobConfig.HTML_RECORD_LENGTH),
                        pad("<p>HTML LINE TWO</p>", CreateStatementJobConfig.HTML_RECORD_LENGTH)),
                List.of(
                        statementSummary("TRAN000000000001", "4111111111111111"),
                        statementSummary("TRAN000000000002", "4111111111111111"),
                        statementSummary("TRAN000000000003", "5555555555554444")),
                dispatched, 2, 3, 2);
    }

    @Nested
    @DisplayName("The measured inventory: five legacy steps, four migrated, three gates")
    class MeasuredInventory {

        @Test
        @DisplayName("one legacy step is absorbed, so four remain behind three failure-ending gates")
        void stepAndGateCountsAreTheMeasuredFigures() {
            assertThat(CreateStatementJobConfig.LEGACY_STEP_COUNT).isEqualTo(5);
            assertThat(CreateStatementJobConfig.ABSORBED_LEGACY_STEP_COUNT).isOne();
            assertThat(CreateStatementJobConfig.STEP_COUNT).isEqualTo(4);
            assertThat(CreateStatementJobConfig.CONDITION_CODE_GATE_COUNT).isEqualTo(3);
            assertThat(CreateStatementJobConfig.STEP_COUNT)
                    .isEqualTo(CreateStatementJobConfig.LEGACY_STEP_COUNT
                            - CreateStatementJobConfig.ABSORBED_LEGACY_STEP_COUNT);
        }

        @Test
        @DisplayName("the gate outcomes are the framework's own failure status and a catch-all")
        void gateOutcomesAreTheFrameworkStatusAndACatchAll() {
            assertThat(CreateStatementJobConfig.GATE_FAILURE_OUTCOME).isEqualTo("FAILED");
            assertThat(CreateStatementJobConfig.GATE_ONWARD_OUTCOME).isEqualTo("*");
        }

        @Test
        @DisplayName("the job and its four steps carry stable names")
        void namesAreStable() {
            assertThat(CreateStatementJobConfig.JOB_NAME).isEqualTo("createStatementJob");
            assertThat(CreateStatementJobConfig.ORDER_AND_REPROJECT_STEP_NAME)
                    .isEqualTo("createStatementOrderAndReprojectStep");
            assertThat(CreateStatementJobConfig.LOAD_WORK_RESOURCE_STEP_NAME)
                    .isEqualTo("createStatementLoadWorkResourceStep");
            assertThat(CreateStatementJobConfig.CLEAR_STATEMENT_OUTPUTS_STEP_NAME)
                    .isEqualTo("createStatementClearOutputsStep");
            assertThat(CreateStatementJobConfig.GENERATE_STATEMENTS_STEP_NAME)
                    .isEqualTo("createStatementGenerateStatementsStep");
        }
    }

    @Nested
    @DisplayName("The record geometry, every figure derived from the layout owner")
    class RecordGeometry {

        @Test
        @DisplayName("the two ordering keys sit where the specification declares them")
        void orderingKeyPositionsAreTheDeclaredOnes() {
            assertThat(CreateStatementJobConfig.CARD_NUMBER_SORT_POSITION).isEqualTo(263);
            assertThat(CreateStatementJobConfig.CARD_NUMBER_SORT_LENGTH).isEqualTo(16);
            assertThat(CreateStatementJobConfig.TRANSACTION_ID_SORT_POSITION).isOne();
            assertThat(CreateStatementJobConfig.TRANSACTION_ID_SORT_LENGTH).isEqualTo(16);
        }

        @Test
        @DisplayName("the three segments add up to 328 content bytes inside a 350-byte record")
        void projectedWidthsAddUp() {
            assertThat(CreateStatementJobConfig.LEADING_SEGMENT_LENGTH).isEqualTo(262);
            assertThat(CreateStatementJobConfig.TIMESTAMP_SEGMENT_LENGTH).isEqualTo(50);
            assertThat(CreateStatementJobConfig.PROJECTED_CONTENT_LENGTH).isEqualTo(328);
            assertThat(CreateStatementJobConfig.WORK_RECORD_LENGTH).isEqualTo(350);
            assertThat(CreateStatementJobConfig.BLANK_PAD_LENGTH).isEqualTo(22);
            assertThat(CreateStatementJobConfig.CARD_NUMBER_SORT_LENGTH
                    + CreateStatementJobConfig.LEADING_SEGMENT_LENGTH
                    + CreateStatementJobConfig.TIMESTAMP_SEGMENT_LENGTH
                    + CreateStatementJobConfig.BLANK_PAD_LENGTH)
                    .isEqualTo(CreateStatementJobConfig.WORK_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the key is thirty-two bytes at offset zero, and exactly two bytes are truncated")
        void keyAndTruncationAreTheMeasuredFigures() {
            assertThat(CreateStatementJobConfig.WORK_RESOURCE_KEY_OFFSET).isZero();
            assertThat(CreateStatementJobConfig.WORK_RESOURCE_KEY_LENGTH).isEqualTo(32);
            assertThat(CreateStatementJobConfig.TRUNCATED_PROCESSING_TIMESTAMP_BYTES).isEqualTo(2);
            assertThat(CreateStatementJobConfig.DROPPED_TRAILING_FILLER_LENGTH).isEqualTo(20);
        }

        @Test
        @DisplayName("the HTML output resolves to one hundred bytes and the plain text to eighty")
        void outputWidthsResolveTheMemberConflict() {
            assertThat(CreateStatementJobConfig.STATEMENT_RECORD_LENGTH).isEqualTo(80);
            assertThat(CreateStatementJobConfig.HTML_RECORD_LENGTH).isEqualTo(100);
            assertThat(CreateStatementJobConfig.SUPERSEDED_HTML_RECORD_LENGTH).isEqualTo(80);
            assertThat(CreateStatementJobConfig.HTML_RECORD_LENGTH)
                    .isNotEqualTo(CreateStatementJobConfig.SUPERSEDED_HTML_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the card table bounds are structural, not tuning figures")
        void cardTableBoundsAreTheLegacyCapacity() {
            assertThat(CreateStatementJobConfig.MAX_CARD_ENTRIES).isEqualTo(51);
            assertThat(CreateStatementJobConfig.MAX_TRANSACTIONS_PER_CARD).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("The reprojection: 328 of 350 bytes, two truncated, the filler dropped")
    class Reprojection {

        @Test
        @DisplayName("the card number moves to the front and the leading portion follows it")
        void segmentsArePlacedWhereTheSpecificationDeclares() {
            final String source = TransactionRecordMapper.toRecord(
                    record("TRAN000000000001", "4111111111111111"));
            assertThat(encoded(source)).isEqualTo(350);

            final String projected = CreateStatementJobConfig.reproject(source);

            assertThat(encoded(projected)).isEqualTo(350);
            assertThat(projected.substring(0, 16)).isEqualTo("4111111111111111");
            assertThat(projected.substring(16, 278)).isEqualTo(source.substring(0, 262));
            assertThat(projected.substring(278, 328)).isEqualTo(source.substring(278, 328));
            assertThat(encoded(projected.substring(0, 328))).isEqualTo(328);
        }

        @Test
        @DisplayName("exactly two processing-timestamp bytes are truncated and the filler is dropped")
        void twoTimestampBytesAreTruncatedAndTheFillerIsDropped() {
            final String projected = CreateStatementJobConfig.reproject(
                    TransactionRecordMapper.toRecord(record("TRAN000000000002", "4111111111111112")));

            assertThat(projected.substring(278, 304)).isEqualTo(ORIGIN_TIMESTAMP);
            assertThat(projected.substring(304, 328))
                    .isEqualTo(PROCESS_TIMESTAMP.substring(0, 24))
                    .hasSize(PROCESS_TIMESTAMP.length()
                            - CreateStatementJobConfig.TRUNCATED_PROCESSING_TIMESTAMP_BYTES);
            assertThat(projected).doesNotContain(PROCESS_TIMESTAMP);
            assertThat(projected.substring(328)).isBlank()
                    .hasSize(CreateStatementJobConfig.BLANK_PAD_LENGTH);
        }

        @Test
        @DisplayName("the leading thirty-two bytes are the work resource's key")
        void theLeadingThirtyTwoBytesAreTheClusterKey() {
            final String projected = CreateStatementJobConfig.reproject(
                    TransactionRecordMapper.toRecord(record("TRAN000000000003", "4111111111111113")));
            final String key = CreateStatementJobConfig.workResourceKey(projected);

            assertThat(encoded(key)).isEqualTo(32);
            assertThat(key).isEqualTo("4111111111111113TRAN000000000003");
            assertThat(key).isEqualTo(projected.substring(0, 32));
        }

        @Test
        @DisplayName("a mis-sized image is refused rather than padded or truncated to fit")
        void aMisSizedImageIsRefused() {
            assertThatThrownBy(() -> CreateStatementJobConfig.reproject("too short"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("The ordering: card number then identifier, ascending, as character data")
    class Ordering {

        @Test
        @DisplayName("both keys order ascending and the comparison is on characters, not numbers")
        void bothKeysOrderAscendingAsCharacters(@TempDir final Path staging) {
            // Scrambled on purpose, and including a card number that is shorter than the field: as
            // character data it sorts before every sixteen-digit value beginning with a higher digit,
            // which a numeric reading would not reproduce.
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of(
                    record("TRAN000000000009", "9111111111111111"),
                    record("TRAN000000000002", "4111111111111111"),
                    record("TRAN000000000001", "4111111111111111"),
                    record("TRAN000000000005", "10111111111111  ")));

            final CreateStatementJobConfig config = config(staging);
            final OrderAndReprojectProgram program =
                    config.newOrderAndReprojectProgram(config.transactionWorkSequentialResource());
            program.run();

            final List<String> orderedKeys = program.orderedRecords().stream()
                    .map(image -> image.substring(0, 32))
                    .toList();
            assertThat(orderedKeys).containsExactly(
                    "10111111111111  TRAN000000000005",
                    "4111111111111111TRAN000000000001",
                    "4111111111111111TRAN000000000002",
                    "9111111111111111TRAN000000000009");
            assertThat(orderedKeys).isSorted();
            assertThat(program.recordsProjected()).isEqualTo(4L);
        }

        @Test
        @DisplayName("the ordered pass writes projected records at the declared width")
        void theOrderedPassWritesAtTheDeclaredWidth(@TempDir final Path staging) throws IOException {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of(
                    record("TRAN000000000002", "4111111111111111"),
                    record("TRAN000000000001", "4111111111111111")));

            final CreateStatementJobConfig config = config(staging);
            final Path written = config.transactionWorkSequentialResource();
            config.newOrderAndReprojectProgram(written).run();

            final List<String> lines = Files.readAllLines(written, StandardCharsets.US_ASCII);
            assertThat(lines).hasSize(2);
            assertThat(lines).allSatisfy(line -> assertThat(encoded(line)).isEqualTo(350));
            assertThat(lines.get(0).substring(16, 32)).isEqualTo("TRAN000000000001");
            assertThat(lines.get(1).substring(16, 32)).isEqualTo("TRAN000000000002");
        }

        @Test
        @DisplayName("an empty master produces an empty ordered result rather than a failure")
        void anEmptyMasterProducesAnEmptyResult(@TempDir final Path staging) throws IOException {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of());

            final CreateStatementJobConfig config = config(staging);
            final Path written = config.transactionWorkSequentialResource();
            final OrderAndReprojectProgram program = config.newOrderAndReprojectProgram(written);
            program.run();

            assertThat(program.recordsProjected()).isZero();
            assertThat(program.orderedRecords()).isEmpty();
            assertThat(Files.readAllLines(written, StandardCharsets.US_ASCII)).isEmpty();
        }
    }

    @Nested
    @DisplayName("The transient work resource: key sequence, one record per key, discarded per run")
    class WorkResource {

        @Test
        @DisplayName("records come back in key sequence, keyed at thirty-two bytes")
        void recordsComeBackInKeySequence() {
            final InMemoryTransactionWorkResource resource = new InMemoryTransactionWorkResource();
            resource.load(CreateStatementJobConfig.reproject(TransactionRecordMapper.toRecord(
                    record("TRAN000000000002", "9111111111111111"))));
            resource.load(CreateStatementJobConfig.reproject(TransactionRecordMapper.toRecord(
                    record("TRAN000000000001", "4111111111111111"))));

            assertThat(resource.recordCount()).isEqualTo(2);
            assertThat(resource.orderedKeys()).containsExactly(
                    "4111111111111111TRAN000000000001", "9111111111111111TRAN000000000002");
            assertThat(resource.orderedKeys())
                    .allSatisfy(key -> assertThat(encoded(key)).isEqualTo(32));
            assertThat(resource.orderedRecords())
                    .allSatisfy(image -> assertThat(encoded(image)).isEqualTo(350));
        }

        @Test
        @DisplayName("a generation snapshot is frozen even if the mutable work resource is loaded "
                + "again afterwards")
        void generationSnapshotIsFrozen() {
            final InMemoryTransactionWorkResource resource = new InMemoryTransactionWorkResource();
            final String first = TransactionRecordMapper.toStatementWorkRecord(
                    record("TRAN000000000001", "4111111111111111"));
            final String later = TransactionRecordMapper.toStatementWorkRecord(
                    record("TRAN000000000002", "9111111111111111"));
            resource.load(first);

            final StatementTransactionSource frozen = resource.snapshot();
            resource.load(later);

            assertThat(frozen.readAt(0)).contains(first);
            assertThat(frozen.readAt(1)).isEmpty();
            assertThat(resource.recordCount()).isEqualTo(2);
            assertThatThrownBy(() -> frozen.readAt(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a second record for one key is refused and the resource keeps what it held")
        void aDuplicateKeyIsRefused() {
            final InMemoryTransactionWorkResource resource = new InMemoryTransactionWorkResource();
            final String projected = CreateStatementJobConfig.reproject(
                    TransactionRecordMapper.toRecord(record("TRAN000000000001", "4111111111111111")));
            resource.load(projected);

            assertThatThrownBy(() -> resource.load(projected))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(CreateStatementJobConfig.TRANSIENT_WORK_RESOURCE_NAME)
                    // The key opens with a card number, so it is withheld from the diagnostic.
                    .hasMessageNotContaining("4111111111111111");
            assertThat(resource.recordCount()).isOne();
        }

        @Test
        @DisplayName("a mis-sized record is refused before it is admitted")
        void aMisSizedRecordIsRefused() {
            final InMemoryTransactionWorkResource resource = new InMemoryTransactionWorkResource();
            assertThatThrownBy(() -> resource.load("short"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(resource.recordCount()).isZero();
        }

        @Test
        @DisplayName("the load step materialises the ordered result the previous step wrote")
        void theLoadStepMaterialisesTheOrderedResult(@TempDir final Path staging) {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of(
                    record("TRAN000000000002", "9111111111111111"),
                    record("TRAN000000000001", "4111111111111111")));

            final CreateStatementJobConfig config = config(staging);
            final Path projected = config.transactionWorkSequentialResource();
            config.newOrderAndReprojectProgram(projected).run();

            final TransactionWorkResource resource = new InMemoryTransactionWorkResource();
            final LoadWorkResourceProgram load = config.newLoadWorkResourceProgram(projected,
                    resource);
            load.run();

            assertThat(load.recordsLoaded()).isEqualTo(2L);
            assertThat(resource.recordCount()).isEqualTo(2);
            assertThat(resource.orderedKeys()).containsExactly(
                    "4111111111111111TRAN000000000001", "9111111111111111TRAN000000000002");
        }

        @Test
        @DisplayName("a work resource that already holds records logs its status and then abends")
        void aNonEmptyWorkResourceIsRefusedWithTheDisplayThenAbendOrder(@TempDir final Path staging)
                throws IOException {

            final CreateStatementJobConfig config = config(staging);
            final Path projected = config.transactionWorkSequentialResource();
            Files.createDirectories(projected.getParent());
            Files.writeString(projected, "", StandardCharsets.US_ASCII);

            final InMemoryTransactionWorkResource resource = new InMemoryTransactionWorkResource();
            resource.load(CreateStatementJobConfig.reproject(TransactionRecordMapper.toRecord(
                    record("TRAN000000000001", "4111111111111111"))));

            assertThatThrownBy(() -> config.newLoadWorkResourceProgram(projected, resource).run())
                    .isInstanceOf(AbendException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("The scratch step: both outputs allocated, then scratched at deallocation")
    class ScratchStep {

        @Test
        @DisplayName("both outputs are enrolled in declaration order at their resolved widths")
        void bothOutputsAreEnrolledAtTheirResolvedWidths(@TempDir final Path staging)
                throws IOException {

            final CreateStatementJobConfig config = config(staging);
            final Path statement = config.statementOutputResource();
            Files.createDirectories(statement.getParent());
            Files.writeString(statement, "previous run", StandardCharsets.US_ASCII);
            assertThat(config.htmlStatementOutputResource()).doesNotExist();

            final ClearStatementOutputsProgram program = config.newClearStatementOutputsProgram();
            program.run();

            assertThat(statement).doesNotExist();
            assertThat(program.resourcesScratched()).isOne();
            assertThat(program.enrolledAllocations().stream().map(StatementOutput::ddName).toList())
                    .containsExactly(StatementProcessor.OUTPUT_DD_HTMLFILE,
                            StatementProcessor.OUTPUT_DD_STMTFILE);
            assertThat(program.enrolledAllocations().stream()
                    .map(StatementOutput::recordLength).toList())
                    .containsExactly(CreateStatementJobConfig.HTML_RECORD_LENGTH,
                            CreateStatementJobConfig.STATEMENT_RECORD_LENGTH);
        }

        @Test
        @DisplayName("an allocation list that is not the measured pair is refused")
        void anAllocationListThatIsNotThePairIsRefused(@TempDir final Path staging) {
            assertThatThrownBy(() -> new ClearStatementOutputsProgram(meterRegistry, clock,
                    List.of(new StatementOutput(StatementProcessor.OUTPUT_DD_STMTFILE,
                            staging.resolve("one"), CreateStatementJobConfig
                                    .STATEMENT_RECORD_LENGTH))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an allocation validates its own name, resource and record length")
        void anAllocationValidatesItsComponents(@TempDir final Path staging) {
            assertThatThrownBy(() -> new StatementOutput(" ", staging.resolve("one"), 80))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                    () -> new StatementOutput(StatementProcessor.OUTPUT_DD_STMTFILE,
                            staging.resolve("one"), 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatNoException().isThrownBy(
                    () -> new StatementOutput(StatementProcessor.OUTPUT_DD_STMTFILE,
                            staging.resolve("one"), 80));
        }
    }

    @Nested
    @DisplayName("The generation step: two outputs, two widths, one item for one whole run")
    class GenerationStep {

        @Test
        @DisplayName("both streams reach their resources at eighty and one hundred encoded bytes")
        void bothStreamsReachTheirResourcesAtTheirDeclaredWidths(@TempDir final Path staging)
                throws IOException {

            when(generationService.generate(any(), any(), any())).thenReturn(completedRun());

            final CreateStatementJobConfig config = config(staging);
            final TransactionWorkResource resource = new InMemoryTransactionWorkResource();
            final String projected = TransactionRecordMapper.toStatementWorkRecord(
                    record("TRAN000000000001", "4111111111111111"));
            resource.load(projected);
            final GenerateStatementsProgram program = config.newGenerateStatementsProgram(
                    config.createStatementProcessor(), resource);
            resource.load(TransactionRecordMapper.toStatementWorkRecord(
                    record("TRAN000000000002", "9111111111111111")));
            program.run();

            assertThat(program.statementRecordsWritten()).isEqualTo(2L);
            assertThat(program.htmlRecordsWritten()).isEqualTo(2L);
            assertThat(program.statementRun()).isNotNull();
            assertThat(program.statementRun().transactionSummaries())
                    .as("the batch stage consumes the service-owned statement carrier directly; no "
                            + "transport DTO or cross-layer adapter participates in this path")
                    .hasSize(3)
                    .extracting(StatementLineSummary::transactionId)
                    .containsExactly("TRAN000000000001", "TRAN000000000002", "TRAN000000000003");

            final List<String> statements = Files.readAllLines(config.statementOutputResource(),
                    StandardCharsets.US_ASCII);
            final List<String> html = Files.readAllLines(config.htmlStatementOutputResource(),
                    StandardCharsets.US_ASCII);
            assertThat(statements).hasSize(2).allSatisfy(
                    line -> assertThat(encoded(line))
                            .isEqualTo(CreateStatementJobConfig.STATEMENT_RECORD_LENGTH));
            assertThat(html).hasSize(2).allSatisfy(
                    line -> assertThat(encoded(line))
                            .isEqualTo(CreateStatementJobConfig.HTML_RECORD_LENGTH));

            final org.mockito.ArgumentCaptor<StatementTransactionSource> source =
                    org.mockito.ArgumentCaptor.forClass(StatementTransactionSource.class);
            verify(generationService).generate(source.capture(), any(), any());
            assertThat(source.getValue().readAt(0)).contains(projected);
            assertThat(source.getValue().readAt(1)).isEmpty();
            assertThat(resource.recordCount())
                    .as("the program captured its source before this later load")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("an empty run still allocates both outputs and writes no record to either")
        void anEmptyRunIsNotAFailure(@TempDir final Path staging) throws IOException {
            final List<String> dispatched =
                    new ArrayList<>(StatementProcessor.EXPECTED_DISPATCH_SEQUENCE);
            dispatched.add("TERMINATED");
            when(generationService.generate(any(), any(), any())).thenReturn(new StatementRun(List.of(),
                    List.of(), List.of(), dispatched, 0, 0, 0));

            final CreateStatementJobConfig config = config(staging);
            final GenerateStatementsProgram program = config.newGenerateStatementsProgram(
                    config.createStatementProcessor(), new InMemoryTransactionWorkResource());
            program.run();

            assertThat(program.statementRecordsWritten()).isZero();
            assertThat(program.htmlRecordsWritten()).isZero();
            assertThat(Files.readAllLines(config.statementOutputResource(),
                    StandardCharsets.US_ASCII)).isEmpty();
            assertThat(Files.readAllLines(config.htmlStatementOutputResource(),
                    StandardCharsets.US_ASCII)).isEmpty();
        }
    }

    @Nested
    @DisplayName("The job, its four steps, and what it deliberately does not carry")
    class JobWiring {

        @Test
        @DisplayName("four steps are registered under their stable names and the job under its own")
        void fourStepsAreRegisteredUnderTheirNames(@TempDir final Path staging) {
            final CreateStatementJobConfig config = config(staging);
            final TransactionWorkResource resource = new InMemoryTransactionWorkResource();

            final Step order = config.createStatementOrderAndReprojectStep();
            final Step load = config.createStatementLoadWorkResourceStep(resource);
            final Step clear = config.createStatementClearOutputsStep();
            final Step generate = config.createStatementGenerateStatementsStep(
                    config.createStatementProcessor(), resource, mock(BatchStagingArea.class));

            assertThat(order.getName())
                    .isEqualTo(CreateStatementJobConfig.ORDER_AND_REPROJECT_STEP_NAME);
            assertThat(load.getName())
                    .isEqualTo(CreateStatementJobConfig.LOAD_WORK_RESOURCE_STEP_NAME);
            assertThat(clear.getName())
                    .isEqualTo(CreateStatementJobConfig.CLEAR_STATEMENT_OUTPUTS_STEP_NAME);
            assertThat(generate.getName())
                    .isEqualTo(CreateStatementJobConfig.GENERATE_STATEMENTS_STEP_NAME);

            final Job job = config.createStatementJob(order, load, clear, generate);
            assertThat(job.getName()).isEqualTo(CreateStatementJobConfig.JOB_NAME);
            assertThat(job).isInstanceOf(FlowJob.class);
            assertThat(((FlowJob) job).getStepNames())
                    .hasSize(CreateStatementJobConfig.STEP_COUNT)
                    .containsExactlyInAnyOrder(order.getName(), load.getName(), clear.getName(),
                            generate.getName());
            assertThat(job.getJobParametersIncrementer()).isSameAs(runIncrementer);
        }

        @Test
        @DisplayName("a step is refused rather than silently omitted")
        void anAbsentStepIsRefused(@TempDir final Path staging) {
            final CreateStatementJobConfig config = config(staging);
            final Step order = config.createStatementOrderAndReprojectStep();
            final Step clear = config.createStatementClearOutputsStep();
            assertThatThrownBy(() -> config.createStatementJob(order, null, clear, clear))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a blank logical resource name is refused rather than resolved to the directory")
        void aBlankLogicalNameIsRefused(@TempDir final Path staging) {
            assertThatThrownBy(() -> new CreateStatementJobConfig(jobRepository, transactionManager,
                    boundaryListener, runIncrementer, transactionScanRepository, generationService,
                    fieldEncryption, meterRegistry, clock, staging.toString(), " ",
                    "AWS.M2.CARDDEMO.STATEMNT.PS", "AWS.M2.CARDDEMO.STATEMNT.HTML"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the transient work resource is published empty, one per job execution")
        void theWorkResourceIsPublishedEmpty(@TempDir final Path staging) {
            final TransactionWorkResource first =
                    config(staging).createStatementTransactionWorkResource();
            final TransactionWorkResource second =
                    config(staging).createStatementTransactionWorkResource();

            assertThat(first.recordCount()).isZero();
            assertThat(first.orderedRecords()).isEmpty();
            assertThat(first.orderedKeys()).isEmpty();
            assertThat(first).isNotSameAs(second);
        }
    }

    @Nested
    @DisplayName("The shared proofs and the structural guarantees")
    class SharedProofs {

        @Test
        @DisplayName("the width proof measures encoded bytes and names the resource it refused for")
        void theWidthProofMeasuresEncodedBytes() {
            assertThatNoException().isThrownBy(() -> CreateStatementJobConfig.requireEncodedWidth(
                    "abcd", 4, StatementProcessor.OUTPUT_DD_STMTFILE));
            assertThatThrownBy(() -> CreateStatementJobConfig.requireEncodedWidth("abc", 4,
                    StatementProcessor.OUTPUT_DD_STMTFILE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(StatementProcessor.OUTPUT_DD_STMTFILE);
        }

        @Test
        @DisplayName("releasing a handle after a failure tolerates absence and a failing close")
        void releasingAHandleToleratesAbsenceAndFailure() {
            assertThatNoException().isThrownBy(() -> CreateStatementJobConfig.releaseQuietly(null,
                    StatementProcessor.OUTPUT_DD_STMTFILE));
            assertThatNoException().isThrownBy(() -> CreateStatementJobConfig.releaseQuietly(() -> {
                throw new IOException("the handle cannot be released");
            }, StatementProcessor.OUTPUT_DD_STMTFILE));
        }

        @Test
        @DisplayName("the configuration declares exactly three failure-ending transitions")
        void exactlyThreeFailureEndingTransitionsAreDeclared() throws IOException {
            final String source = Files.readString(CONFIGURATION_SOURCE, StandardCharsets.UTF_8);
            assertThat(source.split("\\.on\\(GATE_FAILURE_OUTCOME\\)\\.end\\(\\)", -1).length - 1)
                    .isEqualTo(CreateStatementJobConfig.CONDITION_CODE_GATE_COUNT);
            assertThat(source.split("\\.on\\(GATE_ONWARD_OUTCOME\\)\\.to\\(", -1).length - 1)
                    .isEqualTo(CreateStatementJobConfig.CONDITION_CODE_GATE_COUNT);
        }

        @Test
        @DisplayName("nothing in the configuration can run work at context start")
        void nothingCanRunAtContextStart() throws IOException {
            final String source = Files.readString(CONFIGURATION_SOURCE, StandardCharsets.UTF_8);
            assertThat(source).doesNotContain("CommandLineRunner", "ApplicationRunner",
                    "PostConstruct", "SmartLifecycle", "EnableScheduling", "@Scheduled",
                    "spring.batch.job.name", "EnableBatchProcessing", "JobBuilderFactory",
                    "StepBuilderFactory", "TaskExecutor");
        }

        @Test
        @DisplayName("the configuration carries no comparator import and no forbidden construct")
        void noForbiddenConstructIsPresent() throws IOException {
            final String source = Files.readString(CONFIGURATION_SOURCE, StandardCharsets.UTF_8);
            assertThat(source).doesNotContain("javax.", "@SuppressWarnings", "java.lang.reflect",
                    "Class.forName", "Runtime.getRuntime", "ProcessBuilder", "createNativeQuery",
                    "System.out", "System.err", "printStackTrace", "com.cardemo", "setScale",
                    "HALF_EVEN", "HALF_UP", "import static", "Xmx", "Xms");
            // The comparator is declared here and imported from nowhere, which is what keeps this
            // job's character typing separate from the report job's zoned-decimal typing.
            assertThat(source).contains("private static final Comparator<String>")
                    .doesNotContain("TransactionReportJobConfig");
        }
    }
}
