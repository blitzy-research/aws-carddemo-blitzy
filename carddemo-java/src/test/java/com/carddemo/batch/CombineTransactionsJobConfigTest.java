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
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.step.CombineTransactionsProcessor;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.domain.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.TransactionRecordMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.DefaultJobParametersValidator;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Unit specification for {@link CombineTransactionsJobConfig}, the migrated combine-transactions job.
 *
 * <p>Every expectation here is measured from the legacy estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is transcribed.
 *
 * <p>The four properties these cases exist to protect, each of which would otherwise be a defect that
 * compiles and passes a test written under the same misunderstanding:
 *
 * <ul>
 *   <li>the ordering is <strong>lexicographic on the sixteen-character identifier, ascending</strong>,
 *       never numeric and never zoned-decimal;</li>
 *   <li>records sharing an identifier keep the <strong>concatenation order</strong> - backup stream
 *       before synthesized stream - because the legacy specification names no equal-key option and a
 *       stable sort is the deliberate resolution;</li>
 *   <li>the job holds <strong>exactly two steps and no failure-ending transition</strong>, because the
 *       measured member carries no condition-code gate;</li>
 *   <li>the load stage writes through the repository, so <strong>no process is ever launched</strong>
 *       where the legacy step invoked a utility.</li>
 * </ul>
 */
@DisplayName("CombineTransactionsJobConfig - two steps, no gate, and a job-local ascending order")
class CombineTransactionsJobConfigTest {

    /** The record width, taken from the layout's authority rather than restated as a literal. */
    private static final int RECORD_WIDTH = TransactionRecordMapper.RECORD_LENGTH;

    /** Origin descriptor of a point-of-sale purchase, at its contractual ten characters. */
    private static final String POS_SOURCE = "POS TERM  ";

    /**
     * Marker distinguishing the first concatenated stream inside a test.
     *
     * <p>It is carried in the description field because <strong>the layout has no origin field</strong>;
     * it is a test device standing in for stream origin, never a claim that the estate records origin.
     */
    private static final String BACKUP_MARKER = "BACKUP";

    /** Companion of {@link #BACKUP_MARKER} for the second concatenated stream. */
    private static final String SYNTHESIZED_MARKER = "SYNTHESIZED";

    /** A representative amount at the layout's scale of two. */
    private static final BigDecimal AMOUNT = new BigDecimal("42.75");

    /** An unstamped processing timestamp: twenty-six spaces, which the layout permits. */
    private static final String UNSTAMPED_TIMESTAMP = " ".repeat(26);

    /** A stamped origination timestamp at the layout's twenty-six characters. */
    private static final String ORIGIN_TIMESTAMP = "2022-07-19-23.23.05.000000";

    /** Builds the readers over the transaction master layout; stateless, so one instance serves all. */
    private final FixedWidthFlatFileReaderFactory readerFactory = new FixedWidthFlatFileReaderFactory();

    /** Resolves the two job-parameter locations exactly as the application context would. */
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    /** The transaction master, mocked so that a load can be observed without a database. */
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);

    /** The configuration under test. */
    private final CombineTransactionsJobConfig config = new CombineTransactionsJobConfig(
            readerFactory, resourceLoader, transactionRepository);

    /**
     * Builds a transaction whose every field already sits at its contractual width, then rounds it
     * through the mapper so the entity is exactly the one a reader would deliver.
     *
     * @param  tranId identifier, sixteen characters, carried through verbatim
     * @param  marker stream-origin marker for the description field, a test device only
     * @return an entity identical to one parsed from a well-formed record image
     */
    private static Transaction record(final String tranId, final String marker) {
        return TransactionRecordMapper.fromRecord(TransactionRecordMapper.toRecordBytes(
                new Transaction(tranId, "01", "0005", POS_SOURCE, pad(marker, 100), AMOUNT,
                        "000123456", pad("ACME MERCHANT", 50), pad("SEATTLE", 50), "98101-0001",
                        "4111111111111111", ORIGIN_TIMESTAMP, UNSTAMPED_TIMESTAMP)));
    }

    /** Left-justifies a value into a field of the given width, space padded as the layout is. */
    private static String pad(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Writes a sequential dataset of fixed-width record images, one per line, and returns its location.
     *
     * @param  directory the temporary directory to write into
     * @param  fileName  the dataset's file name
     * @param  records   the records to render, in file order
     * @return the location string a job parameter would carry
     * @throws IOException if the dataset cannot be written
     */
    private static String dataset(final Path directory, final String fileName,
            final List<Transaction> records) throws IOException {
        final StringBuilder images = new StringBuilder();
        for (final Transaction record : records) {
            final byte[] image = TransactionRecordMapper.toRecordBytes(record);
            assertThat(image).as("every rendered record is exactly the layout's encoded width")
                    .hasSize(RECORD_WIDTH);
            images.append(new String(image, StandardCharsets.US_ASCII)).append('\n');
        }
        final Path dataset = directory.resolve(fileName);
        Files.writeString(dataset, images.toString(), StandardCharsets.US_ASCII);
        return dataset.toUri().toString();
    }

    /** Reads the description field out of an entity, which carries this test's origin marker. */
    private static String markerOf(final Transaction record) {
        return record.getTranDesc().strip();
    }

    /** Drains a reader to exhaustion, so that a null terminator is proved rather than assumed. */
    private static List<Transaction> drain(final ItemStreamReader<Transaction> reader)
            throws Exception {
        final List<Transaction> served = new ArrayList<>();
        Transaction next = reader.read();
        while (next != null) {
            served.add(next);
            next = reader.read();
        }
        return served;
    }

    /** A step execution detached from any repository, sufficient to drive a listener. */
    private static StepExecution stepExecution(final String stepName) {
        final JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, CombineTransactionsJobConfig.JOB_NAME), 1L,
                new JobParameters());
        return new StepExecution(stepName, jobExecution);
    }

    // ----------------------------------------------------------------------------------------
    // The published contract
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the names a caller launches and measures this job by are stable")
    final class ThePublishedNamesAreStable {

        @Test
        @DisplayName("the job and both steps carry distinct stable names, so each stage is separately "
                + "measurable on the metrics endpoint")
        void theJobAndBothStepsCarryDistinctStableNames() {
            assertThat(CombineTransactionsJobConfig.JOB_NAME).isEqualTo("combineTransactionsJob");
            assertThat(CombineTransactionsJobConfig.ORDER_STEP_NAME)
                    .isEqualTo("combineTransactionsOrderStep");
            assertThat(CombineTransactionsJobConfig.LOAD_STEP_NAME)
                    .isEqualTo("combineTransactionsLoadStep");
            assertThat(CombineTransactionsJobConfig.ORDER_STEP_NAME)
                    .isNotEqualTo(CombineTransactionsJobConfig.LOAD_STEP_NAME);
        }

        @Test
        @DisplayName("both inputs are named by their own job parameter, because a combine job that read "
                + "one input instead of two would look perfectly well formed")
        void bothInputsAreNamedByTheirOwnJobParameter() {
            assertThat(CombineTransactionsJobConfig.BACKUP_INPUT_LOCATION)
                    .isEqualTo("transactionBackupCurrentGeneration");
            assertThat(CombineTransactionsJobConfig.SYNTHESIZED_INPUT_LOCATION)
                    .isEqualTo("synthesizedTransactionCurrentGeneration");
            assertThat(CombineTransactionsJobConfig.BACKUP_INPUT_LOCATION)
                    .isNotEqualTo(CombineTransactionsJobConfig.SYNTHESIZED_INPUT_LOCATION);
        }

        @Test
        @DisplayName("the record width this job carries is the layout's own, not a second copy of it")
        void theRecordWidthIsTheLayoutsOwn() {
            assertThat(CombineTransactionsProcessor.COMBINED_RECORD_LENGTH).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("every collaborator is required, so a half-wired configuration cannot be built")
        void everyCollaboratorIsRequired() {
            assertThatThrownBy(() -> new CombineTransactionsJobConfig(null, resourceLoader,
                    transactionRepository)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CombineTransactionsJobConfig(readerFactory, null,
                    transactionRepository)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CombineTransactionsJobConfig(readerFactory, resourceLoader,
                    null)).isInstanceOf(NullPointerException.class);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Two steps, and no gate
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the job sequences exactly two steps and gates neither of them")
    final class TheJobSequencesExactlyTwoSteps {

        /** A repository stand-in: the builders only record it, so no behaviour is stubbed. */
        private final JobRepository jobRepository = mock(JobRepository.class);

        /** A transaction manager stand-in, recorded by the step builders in the same way. */
        private final PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);

        /** The incrementer the batch infrastructure publishes for a job configuration to attach. */
        private final JobParametersIncrementer runIncrementer = new RunIdIncrementer();

        /** The boundary listener the batch infrastructure publishes, mocked to observe attachment. */
        private final JobExecutionListener boundaryListener = mock(JobExecutionListener.class);

        private Job buildJob() {
            final Step orderStep = config.combineTransactionsOrderStep(jobRepository,
                    transactionManager,
                    config.combineTransactionsOrderedReader("classpath:absent-backup.txt",
                            "classpath:absent-synthesized.txt"),
                    config.combineTransactionsCombinedGeneration());
            final Step loadStep = config.combineTransactionsLoadStep(jobRepository, transactionManager,
                    config.combineTransactionsCombinedGeneration(),
                    config.combineTransactionsProcessor(),
                    config.combineTransactionsMasterWriter());
            return config.combineTransactionsJob(jobRepository, runIncrementer, boundaryListener,
                    orderStep, loadStep);
        }

        @Test
        @DisplayName("the job is a plain sequence of the ordering step then the load step")
        void theJobIsAPlainSequenceOfTwoSteps() {
            final Job job = buildJob();

            assertThat(job.getName()).isEqualTo(CombineTransactionsJobConfig.JOB_NAME);
            assertThat(job).isInstanceOf(SimpleJob.class);
            assertThat(((SimpleJob) job).getStepNames()).containsExactly(
                    CombineTransactionsJobConfig.ORDER_STEP_NAME,
                    CombineTransactionsJobConfig.LOAD_STEP_NAME);
        }

        @Test
        @DisplayName("a plain sequence is itself the proof that no failure-ending transition exists, "
                + "because a transition would have produced a flow job instead")
        void aPlainSequenceProvesNoFailureEndingTransitionExists() {
            assertThat(buildJob()).isExactlyInstanceOf(SimpleJob.class);
        }

        @Test
        @DisplayName("the run incrementer is attached, so re-submitting the same logical work is a new "
                + "instance rather than a refusal")
        void theRunIncrementerIsAttached() {
            assertThat(buildJob().getJobParametersIncrementer()).isSameAs(runIncrementer);
        }

        @Test
        @DisplayName("this job attaches no validator of its own, so the framework's permissive default "
                + "stands: the measured member carries no parameter card to validate")
        void noParameterValidatorOfItsOwnIsAttached() {
            final JobParametersValidator validator = buildJob().getJobParametersValidator();

            assertThat(validator)
                    .as("a validator of this job's own would impose a contract the estate never had")
                    .isInstanceOf(DefaultJobParametersValidator.class);
            assertThatNoException()
                    .as("the default requires no key, so a submission carrying none is accepted")
                    .isThrownBy(() -> validator.validate(new JobParameters()));
        }

        @Test
        @DisplayName("both steps are built and carry the published names")
        void bothStepsAreBuiltAndCarryThePublishedNames() {
            final Job job = buildJob();

            assertThat(((SimpleJob) job).getStep(CombineTransactionsJobConfig.ORDER_STEP_NAME))
                    .isNotNull();
            assertThat(((SimpleJob) job).getStep(CombineTransactionsJobConfig.LOAD_STEP_NAME))
                    .isNotNull();
        }
    }

    // ----------------------------------------------------------------------------------------
    // Ordering
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the two concatenated inputs are ordered by the identifier, ascending")
    final class TheInputsAreOrderedAscending {

        @Test
        @DisplayName("records from both inputs arrive in ascending identifier order, whichever input "
                + "each came from")
        void recordsFromBothInputsArriveInAscendingOrder(@TempDir final Path directory)
                throws Exception {
            final String backup = dataset(directory, "backup.txt",
                    List.of(record("0000000000000009", BACKUP_MARKER),
                            record("0000000000000001", BACKUP_MARKER)));
            final String synthesized = dataset(directory, "synthesized.txt",
                    List.of(record("0000000000000005", SYNTHESIZED_MARKER),
                            record("0000000000000003", SYNTHESIZED_MARKER)));

            final ItemStreamReader<Transaction> reader =
                    config.combineTransactionsOrderedReader(backup, synthesized);
            reader.open(new ExecutionContext());
            final List<Transaction> ordered = drain(reader);
            reader.close();

            assertThat(ordered).extracting(Transaction::getTranId).containsExactly(
                    "0000000000000001", "0000000000000003", "0000000000000005",
                    "0000000000000009");
        }

        @Test
        @DisplayName("the order is lexicographic and not numeric, so a shorter-looking identifier does "
                + "not sort as a smaller number")
        void theOrderIsLexicographicAndNotNumeric(@TempDir final Path directory) throws Exception {
            final String backup = dataset(directory, "backup.txt",
                    List.of(record("2000000000000000", BACKUP_MARKER),
                            record("10000000000000  ", BACKUP_MARKER)));
            final String synthesized = dataset(directory, "synthesized.txt", List.of());

            final ItemStreamReader<Transaction> reader =
                    config.combineTransactionsOrderedReader(backup, synthesized);
            reader.open(new ExecutionContext());
            final List<Transaction> ordered = drain(reader);
            reader.close();

            assertThat(ordered).extracting(Transaction::getTranId)
                    .as("character order puts the identifier beginning with one first; a numeric "
                            + "reading of the same two values would not")
                    .containsExactly("10000000000000  ", "2000000000000000");
        }

        @Test
        @DisplayName("records sharing an identifier keep the concatenation order - backup stream "
                + "before synthesized stream - because the ordering is stable")
        void equalIdentifiersKeepTheConcatenationOrder(@TempDir final Path directory)
                throws Exception {
            final String shared = "0000000000000007";
            final String backup = dataset(directory, "backup.txt",
                    List.of(record(shared, BACKUP_MARKER)));
            final String synthesized = dataset(directory, "synthesized.txt",
                    List.of(record(shared, SYNTHESIZED_MARKER)));

            final ItemStreamReader<Transaction> reader =
                    config.combineTransactionsOrderedReader(backup, synthesized);
            reader.open(new ExecutionContext());
            final List<Transaction> ordered = drain(reader);
            reader.close();

            assertThat(ordered).hasSize(2);
            assertThat(markerOf(ordered.get(0))).isEqualTo(BACKUP_MARKER);
            assertThat(markerOf(ordered.get(1))).isEqualTo(SYNTHESIZED_MARKER);
        }

        @Test
        @DisplayName("nothing is deduplicated, summed or dropped: every record of both inputs reaches "
                + "the output, because the legacy specification names no equal-key option")
        void nothingIsDeduplicatedOrDropped(@TempDir final Path directory) throws Exception {
            final String shared = "0000000000000007";
            final String backup = dataset(directory, "backup.txt",
                    List.of(record(shared, BACKUP_MARKER), record(shared, BACKUP_MARKER)));
            final String synthesized = dataset(directory, "synthesized.txt",
                    List.of(record(shared, SYNTHESIZED_MARKER)));

            final ItemStreamReader<Transaction> reader =
                    config.combineTransactionsOrderedReader(backup, synthesized);
            reader.open(new ExecutionContext());

            assertThat(drain(reader)).hasSize(3);
            reader.close();
        }

        @Test
        @DisplayName("an empty input is exhaustion rather than error, so a job with nothing to combine "
                + "still completes")
        void anEmptyInputIsExhaustionRatherThanError(@TempDir final Path directory)
                throws Exception {
            final String backup = dataset(directory, "backup.txt", List.of());
            final String synthesized = dataset(directory, "synthesized.txt", List.of());

            final ItemStreamReader<Transaction> reader =
                    config.combineTransactionsOrderedReader(backup, synthesized);
            reader.open(new ExecutionContext());

            assertThat(reader.read()).isNull();
            reader.close();
        }

        @Test
        @DisplayName("re-opening the stream re-reads it rather than appending a second pass to the "
                + "first")
        void reOpeningTheStreamReReadsIt(@TempDir final Path directory) throws Exception {
            final String backup = dataset(directory, "backup.txt",
                    List.of(record("0000000000000001", BACKUP_MARKER)));
            final String synthesized = dataset(directory, "synthesized.txt",
                    List.of(record("0000000000000002", SYNTHESIZED_MARKER)));

            final ItemStreamReader<Transaction> reader =
                    config.combineTransactionsOrderedReader(backup, synthesized);
            reader.open(new ExecutionContext());
            assertThat(drain(reader)).hasSize(2);
            reader.open(new ExecutionContext());

            assertThat(drain(reader)).as("a second open reads the same two records, not four")
                    .hasSize(2);
            reader.close();
        }

        @Test
        @DisplayName("no position is recorded, because a resumed ordering pass would order a different "
                + "set of records than the attempt that failed")
        void noPositionIsRecorded(@TempDir final Path directory) throws Exception {
            final String backup = dataset(directory, "backup.txt",
                    List.of(record("0000000000000001", BACKUP_MARKER)));
            final String synthesized = dataset(directory, "synthesized.txt", List.of());
            final ExecutionContext context = new ExecutionContext();

            final ItemStreamReader<Transaction> reader =
                    config.combineTransactionsOrderedReader(backup, synthesized);
            reader.open(context);
            assertThat(drain(reader)).hasSize(1);
            reader.update(context);

            assertThat(context.isEmpty()).isTrue();
            reader.close();
        }

        @Test
        @DisplayName("closing releases the ordered records, so nothing survives the step that read "
                + "them")
        void closingReleasesTheOrderedRecords(@TempDir final Path directory) throws Exception {
            final String backup = dataset(directory, "backup.txt",
                    List.of(record("0000000000000001", BACKUP_MARKER)));
            final String synthesized = dataset(directory, "synthesized.txt", List.of());

            final ItemStreamReader<Transaction> reader =
                    config.combineTransactionsOrderedReader(backup, synthesized);
            reader.open(new ExecutionContext());
            reader.close();

            assertThat(reader.read()).isNull();
        }
    }

    // ----------------------------------------------------------------------------------------
    // Refusals
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("an input that cannot be resolved or read is refused rather than worked around")
    final class AnUnresolvableInputIsRefused {

        @Test
        @DisplayName("a missing location is refused, and no default is substituted for it")
        void aMissingLocationIsRefused() {
            assertThatThrownBy(() -> config.combineTransactionsOrderedReader(null, "file:/tmp/x.txt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(CombineTransactionsJobConfig.BACKUP_INPUT_LOCATION);
            assertThatThrownBy(() -> config.combineTransactionsOrderedReader("file:/tmp/x.txt", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(CombineTransactionsJobConfig.SYNTHESIZED_INPUT_LOCATION);
        }

        @Test
        @DisplayName("a blank location is refused for the same reason a missing one is")
        void aBlankLocationIsRefused() {
            assertThatThrownBy(() -> config.combineTransactionsOrderedReader("   ", "file:/tmp/x.txt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(CombineTransactionsJobConfig.BACKUP_INPUT_LOCATION);
        }

        @Test
        @DisplayName("an absent dataset fails on open rather than yielding an apparently successful "
                + "empty run")
        void anAbsentDatasetFailsOnOpen(@TempDir final Path directory) throws IOException {
            final String present = dataset(directory, "backup.txt", List.of());
            final String absent = directory.resolve("never-created.txt").toUri().toString();

            final ItemStreamReader<Transaction> reader =
                    config.combineTransactionsOrderedReader(present, absent);

            assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                    .isInstanceOf(ItemStreamException.class)
                    .hasMessageContaining("could not be read in full");
        }

        @Test
        @DisplayName("a record of the wrong width fails the read rather than being mis-sliced, because "
                + "the layout's authority validates it")
        void aRecordOfTheWrongWidthFailsTheRead(@TempDir final Path directory) throws IOException {
            final Path malformed = directory.resolve("malformed.txt");
            Files.writeString(malformed, "SHORT RECORD\n", StandardCharsets.US_ASCII);
            final String synthesized = dataset(directory, "synthesized.txt", List.of());

            final ItemStreamReader<Transaction> reader = config.combineTransactionsOrderedReader(
                    malformed.toUri().toString(), synthesized);

            assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                    .isInstanceOf(ItemStreamException.class);
        }
    }

    // ----------------------------------------------------------------------------------------
    // The combined generation
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the combined generation preserves what was written to it, in that order, once each")
    final class TheCombinedGenerationPreservesOrder {

        @Test
        @DisplayName("records are served in the order they were written, once each, then exhaustion")
        void recordsAreServedInWrittenOrderOnceEach() throws Exception {
            final CombineTransactionsJobConfig.CombinedGeneration combined =
                    config.combineTransactionsCombinedGeneration();
            final Transaction first = record("0000000000000001", BACKUP_MARKER);
            final Transaction second = record("0000000000000002", SYNTHESIZED_MARKER);

            combined.write(Chunk.of(first));
            combined.write(Chunk.of(second));

            assertThat(combined.read()).isSameAs(first);
            assertThat(combined.read()).isSameAs(second);
            assertThat(combined.read()).isNull();
        }

        @Test
        @DisplayName("an empty generation reports exhaustion immediately rather than failing")
        void anEmptyGenerationReportsExhaustion() throws Exception {
            assertThat(config.combineTransactionsCombinedGeneration().read()).isNull();
        }

        @Test
        @DisplayName("each job execution receives its own generation, so one execution's records "
                + "cannot reach another's")
        void eachExecutionReceivesItsOwnGeneration() throws Exception {
            final CombineTransactionsJobConfig.CombinedGeneration first =
                    config.combineTransactionsCombinedGeneration();
            first.write(Chunk.of(record("0000000000000001", BACKUP_MARKER)));

            assertThat(config.combineTransactionsCombinedGeneration().read()).isNull();
        }

        @Test
        @DisplayName("a group is required, because its absence would mean the framework contract was "
                + "breached rather than that the group was empty")
        void aGroupIsRequired() {
            final CombineTransactionsJobConfig.CombinedGeneration combined =
                    config.combineTransactionsCombinedGeneration();

            assertThatThrownBy(() -> combined.write(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ----------------------------------------------------------------------------------------
    // The load
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the load writes through the repository, and launches nothing")
    final class TheLoadWritesThroughTheRepository {

        @Test
        @DisplayName("each group is saved into the transaction master")
        void eachGroupIsSavedIntoTheMaster() throws Exception {
            final ItemWriter<Transaction> writer = config.combineTransactionsMasterWriter();
            final Transaction loaded = record("0000000000000001", BACKUP_MARKER);

            writer.write(Chunk.of(loaded));

            verify(transactionRepository).saveAll(List.of(loaded));
        }

        @Test
        @DisplayName("an empty group is a no-operation rather than an empty save")
        void anEmptyGroupIsANoOperation() throws Exception {
            config.combineTransactionsMasterWriter().write(new Chunk<>(List.of()));

            verify(transactionRepository, never()).saveAll(anyIterable());
        }

        @Test
        @DisplayName("a group is required, so a breached framework contract is reported rather than "
                + "silently treated as nothing to load")
        void aGroupIsRequired() {
            final ItemWriter<Transaction> writer = config.combineTransactionsMasterWriter();

            assertThatThrownBy(() -> writer.write(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the per-record stage of the load returns the very record it was given, so no "
                + "record the legacy job loaded is filtered here")
        void thePerRecordStageReturnsTheVeryRecordItWasGiven() {
            final Transaction loaded = record("0000000000000001", BACKUP_MARKER);

            assertThat(config.combineTransactionsProcessor().process(loaded)).isSameAs(loaded);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Diagnostics
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("a step's diagnostic observes its outcome and never alters it")
    final class AStepDiagnosticNeverAltersTheOutcome {

        @Test
        @DisplayName("the ordering step's diagnostic contributes no exit status")
        void theOrderingDiagnosticContributesNoExitStatus() {
            final StepExecutionListener listener = CombineTransactionsJobConfig
                    .stepCompletionDiagnostics(CombineTransactionsJobConfig.ORDER_STEP_NAME,
                            CombineTransactionsProcessor.LEGACY_SORT_STEP);

            final ExitStatus contributed = listener.afterStep(
                    stepExecution(CombineTransactionsJobConfig.ORDER_STEP_NAME));

            assertThat(contributed).isNull();
        }

        @Test
        @DisplayName("the load step's diagnostic contributes no exit status either")
        void theLoadDiagnosticContributesNoExitStatus() {
            final StepExecutionListener listener = CombineTransactionsJobConfig
                    .stepCompletionDiagnostics(CombineTransactionsJobConfig.LOAD_STEP_NAME,
                            CombineTransactionsProcessor.LEGACY_LOAD_STEP);

            assertThat(listener.afterStep(
                    stepExecution(CombineTransactionsJobConfig.LOAD_STEP_NAME))).isNull();
        }

        @Test
        @DisplayName("a step execution is required, because a diagnostic with nothing to report means "
                + "the framework contract was breached")
        void aStepExecutionIsRequired() {
            final StepExecutionListener listener = CombineTransactionsJobConfig
                    .stepCompletionDiagnostics(CombineTransactionsJobConfig.LOAD_STEP_NAME,
                            CombineTransactionsProcessor.LEGACY_LOAD_STEP);

            assertThatThrownBy(() -> listener.afterStep(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Releasing an input
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("a failure to release an input is reported, never allowed to mask the outcome")
    final class AFailureToReleaseAnInputIsReported {

        @Test
        @DisplayName("an input that will not close cleanly does not fail the pass, because by the time "
                + "it is closed its records have already been read in full")
        void anInputThatWillNotCloseCleanlyDoesNotFailThePass() throws Exception {
            final FixedWidthFlatFileReaderFactory closesBadly =
                    mock(FixedWidthFlatFileReaderFactory.class);
            when(closesBadly.transactionReader(any())).thenReturn(new ClosesBadly());
            final CombineTransactionsJobConfig configuration = new CombineTransactionsJobConfig(
                    closesBadly, resourceLoader, transactionRepository);

            final ItemStreamReader<Transaction> reader = configuration
                    .combineTransactionsOrderedReader("classpath:first.txt", "classpath:second.txt");
            reader.open(new ExecutionContext());

            assertThat(reader.read())
                    .as("both inputs were read to exhaustion and neither close failure surfaced")
                    .isNull();
            reader.close();
        }
    }

    // ----------------------------------------------------------------------------------------
    // The comparator's confinement, asserted structurally
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the ordering specification stays inside the job that owns it")
    final class TheOrderingSpecificationStaysInsideThisJob {

        /** The configuration's own source, read so that a structural claim can be checked. */
        private String source() throws IOException {
            final Path source = Path.of("src", "main", "java", "com", "carddemo", "batch",
                    "CombineTransactionsJobConfig.java");
            assertThat(source).as("the production source is read from the module's own tree").exists();
            return Files.readString(source, StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("the comparator is declared private, static and final, so no other job can reach "
                + "it and no execution can replace it")
        void theComparatorIsDeclaredPrivateStaticAndFinal() throws IOException {
            assertThat(source())
                    .as("a sibling job types the same physical field as zoned decimal, so a shared "
                            + "comparator would hand one job the other's semantics and still compile")
                    .contains("private static final Comparator<Transaction> TRAN_ID_ASCENDING");
        }

        @Test
        @DisplayName("the only comparator type in scope is the platform's, so none is imported from a "
                + "neighbouring job or from a shared package")
        void theOnlyComparatorTypeInScopeIsThePlatforms() throws IOException {
            final List<String> comparatorImports = source().lines()
                    .filter(line -> line.startsWith("import "))
                    .filter(line -> line.contains("Comparator"))
                    .toList();

            assertThat(comparatorImports).containsExactly("import java.util.Comparator;");
        }

        @Test
        @DisplayName("no member publishes the comparator, so it cannot be borrowed by another job even "
                + "within this package")
        void noMemberPublishesTheComparator() throws IOException {
            final List<String> publishing = source().lines()
                    .map(String::strip)
                    .filter(line -> line.contains("Comparator<"))
                    .filter(line -> !line.startsWith("private static final Comparator"))
                    .filter(line -> !line.startsWith("*"))
                    .toList();

            assertThat(publishing)
                    .as("the declaration is the only place the type appears outside documentation")
                    .isEmpty();
        }

        @Test
        @DisplayName("the batch tier names no transport record and no configuration class, so the "
                + "layering direction holds for this file on its own")
        void theBatchTierNamesNoTransportRecordOrConfiguration() throws IOException {
            final List<String> upward = source().lines()
                    .filter(line -> line.startsWith("import com.carddemo."))
                    .filter(line -> line.startsWith("import com.carddemo.api")
                            || line.startsWith("import com.carddemo.config"))
                    .toList();

            assertThat(upward).isEmpty();
        }
    }

    /**
     * A delegate whose records read cleanly and whose release always fails.
     *
     * <p>Stands in for the one condition the production code reports rather than raises: an input whose
     * every record has already been read and which then refuses to close. Raising there would replace
     * the outcome already on its way out with a secondary failure and lose the diagnosis.
     */
    private static final class ClosesBadly extends FlatFileItemReader<Transaction> {

        @Override
        public void open(final ExecutionContext executionContext) {
            // Nothing to open: this delegate stands in for an input, not for a file.
        }

        @Override
        public Transaction read() {
            return null;
        }

        @Override
        public void close() {
            throw new ItemStreamException("the input refused to close");
        }
    }
}
