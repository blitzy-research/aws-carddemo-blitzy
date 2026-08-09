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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.step.CombineTransactionsProcessor;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.domain.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.DateValidationService;
import com.carddemo.util.ExternalStringSorter;
import com.carddemo.util.TransactionRecordMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersInvalidException;
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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DuplicateKeyException;
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

    /** A representative execution identifier used to name the per-run combined generation. */
    private static final long JOB_EXECUTION_ID = 42L;

    /** Builds the readers over the transaction master layout; stateless, so one instance serves all. */
    private final FixedWidthFlatFileReaderFactory readerFactory = new FixedWidthFlatFileReaderFactory();

    /** Resolves the two configured locations exactly as the application context would. */
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    /** The transaction master, mocked so that a load can be observed without a database. */
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);

    /** The shared object-store staging boundary, mocked so publication remains observable. */
    private final BatchStagingArea stagingArea = mock(BatchStagingArea.class);

    /**
     * The durable generation store, mocked so that current-generation resolution is a lever rather than
     * a dependency on a bucket. Answering empty is the default: a base that holds no generation is what
     * every fixture here means by "not in the durable store" (DL-214).
     */
    private final StagedGenerationStore generationStore = mock(StagedGenerationStore.class);

    /** Local fallback root; file-URI fixtures bypass it while logical-name tests exercise it. */
    private static final String STAGING_DIRECTORY = System.getProperty("java.io.tmpdir");

    /**
     * Removes any combined generation a test left in the shared staging root.
     *
     * <p>The production path removes its own local copy once the load step has served it, but a test
     * that stops half way through the two roles deliberately does not reach that point, so the suite
     * cleans up after itself rather than accumulating one file per run.
     *
     * @throws IOException if a leftover file cannot be removed
     */
    @AfterEach
    void removeStagedGenerations() throws IOException {
        for (long identifier : new long[] {JOB_EXECUTION_ID, JOB_EXECUTION_ID + 1}) {
            final Path generation = generationFile(identifier);
            Files.deleteIfExists(generation);
            Files.deleteIfExists(generation.resolveSibling(generation.getFileName() + ".part"));
        }
    }

    /**
     * @param  jobExecutionId the execution whose generation is being named
     * @return the local file that execution's combined generation occupies
     */
    private static Path generationFile(final long jobExecutionId) {
        return StagedGenerationStore.generationPath(
                Path.of(STAGING_DIRECTORY).toAbsolutePath().normalize(),
                CombineTransactionsJobConfig.COMBINED_DATASET_BASE, jobExecutionId);
    }

    /**
     * A job execution carrying the identifier the generation is named from and the registry the
     * generation records its publication on.
     *
     * @param  jobExecutionId the framework identifier
     * @return the execution
     */
    private static JobExecution execution(final long jobExecutionId) {
        return new JobExecution(new JobInstance(jobExecutionId + 100,
                CombineTransactionsJobConfig.JOB_NAME), jobExecutionId, new JobParameters());
    }

    /** The configuration under test. */
    private final CombineTransactionsJobConfig config = new CombineTransactionsJobConfig(
            readerFactory, resourceLoader, stagingArea, generationStore, transactionRepository,
            STAGING_DIRECTORY, "backup.txt", "synthesized.txt");

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
     * <p>The location returned is a file URI supplied as deployment configuration to the test
     * configuration. It is never a launch parameter and therefore never caller-controlled.
     *
     * @param  directory the staging directory to write into, which is the configuration's own
     * @param  fileName  the dataset's file name
     * @param  records   the records to render, in file order
     * @return the deployment-owned location of the staged dataset
     * @throws IOException if the dataset cannot be written
     */
    private static String dataset(final Path directory, final String fileName,
            final List<Transaction> records) throws IOException {
        final StringBuilder images = new StringBuilder();
        for (final Transaction record : records) {
            final byte[] image = TransactionRecordMapper.toRecordBytes(record);
            assertThat(image).as("every rendered record is exactly the layout's encoded width")
                    .hasSize(RECORD_WIDTH);
            images.append(new String(image, StandardCharsets.US_ASCII));
        }
        Files.writeString(directory.resolve(fileName), images.toString(), StandardCharsets.US_ASCII);
        return directory.resolve(fileName).toUri().toString();
    }

    /** Reads the description field out of an entity, which carries this test's origin marker. */
    private static String markerOf(final Transaction record) {
        return record.getTranDesc().strip();
    }

    /**
     * Builds a stream-origin marker carrying its position within that stream.
     *
     * <p>The ordinal is fixed width so that the marker sorts and reads in ordinal order, which makes a
     * failure diagnostic legible. It is a test device in the description field, exactly as the plain
     * marker is: the layout has no origin field and no sequence field.
     *
     * @param  stream  the stream marker
     * @param  ordinal the record's position within that stream
     * @return the marker, at most the description field's width
     */
    private static String ordinalMarker(final String stream, final int ordinal) {
        return stream + String.format(Locale.ROOT, "-%06d", ordinal);
    }

    /**
     * Reports the first index at which two sequences differ, or {@code -1} when they are identical.
     *
     * <p>Used instead of an element-by-element assertion on a sixteen-thousand-element sequence, whose
     * failure message would render both sequences in full and be unreadable. The index alone identifies
     * where the order broke, which is what a reader needs.
     *
     * @param  expected the sequence expected
     * @param  actual   the sequence produced
     * @return the first differing index, or {@code -1}
     */
    private static int firstDifference(final List<String> expected, final List<String> actual) {
        final int shared = Math.min(expected.size(), actual.size());
        for (int index = 0; index < shared; index++) {
            if (!expected.get(index).equals(actual.get(index))) {
                return index;
            }
        }
        return expected.size() == actual.size() ? -1 : shared;
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

    /**
     * Builds a configuration whose two deployment-owned input locations point at the supplied datasets.
     *
     * <p>The values are constructor configuration, not job parameters. That distinction is the security
     * boundary this integrated design preserves: callers can launch the job but cannot choose what it
     * reads.
     */
    private ItemStreamReader<Transaction> orderedReaderOver(
            final String backup, final String synthesized) {
        return new CombineTransactionsJobConfig(readerFactory, resourceLoader, stagingArea,
                generationStore, transactionRepository, STAGING_DIRECTORY, backup, synthesized)
                .combineTransactionsOrderedReader();
    }

    /**
     * Builds a configuration whose local staging root is the supplied directory rather than the shared
     * temporary one.
     *
     * <p>Every case that exercises a local rung of the resolution ladder needs a root of its own, for
     * two reasons that are both load-bearing. A local staged artefact is only accepted when it is a
     * regular file this process owns inside a root nothing outside its owner can write to, and the
     * shared temporary directory is world-writable on every host this suite runs on, so a case staged
     * there would be refused for a reason the case is not about. And the current local generation of a
     * base is whichever generation of it the root holds, so two cases sharing a root would see each
     * other's generations.
     *
     * @param  stagingRoot  the local staging root this configuration resolves logical names against
     * @param  backup       the first configured location
     * @param  synthesized  the second configured location
     * @return the ordered reader over the two resolved inputs
     */
    private ItemStreamReader<Transaction> orderedReaderStagedIn(final Path stagingRoot,
            final String backup, final String synthesized) {
        return new CombineTransactionsJobConfig(readerFactory, resourceLoader, stagingArea,
                generationStore, transactionRepository, stagingRoot.toString(), backup, synthesized)
                .combineTransactionsOrderedReader();
    }

    /**
     * Renders records into a resource served from memory, standing for a durable staged object.
     *
     * <p>The durable rungs of the ladder hand back whatever the staging area returns for an object key,
     * and what makes a case about the durable store rather than about a filesystem is that the resource
     * it yields is not a file at all. Framing is identical to a staged object's: fixed-width images
     * concatenated with no separator, exactly as {@link #dataset} writes them.
     *
     * @param  records the records the object holds, in object order
     * @return the object's content as a readable resource
     */
    private static Resource durableObject(final List<Transaction> records) {
        final StringBuilder images = new StringBuilder();
        for (final Transaction record : records) {
            final byte[] image = TransactionRecordMapper.toRecordBytes(record);
            assertThat(image).as("every rendered record is exactly the layout's encoded width")
                    .hasSize(RECORD_WIDTH);
            images.append(new String(image, StandardCharsets.US_ASCII));
        }
        return new ByteArrayResource(images.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Writes an empty dataset and returns its location, for the input a case is not examining.
     *
     * <p>Both inputs are resolved when the reader is built and both are read when it is opened, so a
     * case about one input still has to give the other something that resolves and opens. An empty
     * dataset contributes no record and therefore cannot be mistaken for the input under examination.
     *
     * @param  directory the directory to write into
     * @return the neutral input's location, as a file URI
     * @throws IOException if the dataset cannot be written
     */
    private static String neutralInput(final Path directory) throws IOException {
        return dataset(directory, "neutral.txt", List.of());
    }

    /**
     * Stages one record under a plain name in a root this process owns exclusively.
     *
     * @param  root   the staging root, which is left owner-only
     * @param  name   the plain dataset name, which is also the configured location
     * @param  record the single record the dataset holds
     * @return the name, unchanged, for use as the configured location
     * @throws IOException if the dataset cannot be written
     */
    private static String stagedUnder(final Path root, final String name, final Transaction record)
            throws IOException {
        dataset(root, name, List.of(record));
        makeOwnerOnly(root);
        makeOwnerOnly(root.resolve(name));
        return name;
    }

    /**
     * Stages one record as a named generation of a base, using the store's own naming.
     *
     * <p>The name is composed by {@link StagedGenerationStore#generationPath} rather than spelled here,
     * so a case cannot pass by agreeing with a spelling this suite invented.
     *
     * @param  root       the staging root, which is left owner-only
     * @param  base       the logical generation base
     * @param  generation the generation number
     * @param  record     the single record the generation holds
     * @return the staged generation's path
     * @throws IOException if the generation cannot be written
     */
    private static Path stagedGeneration(final Path root, final String base, final long generation,
            final Transaction record) throws IOException {
        final Path staged = StagedGenerationStore.generationPath(root, base, generation);
        final Path name = staged.getFileName();
        assertThat(name).as("a staged generation is always named").isNotNull();
        dataset(root, name.toString(), List.of(record));
        makeOwnerOnly(root);
        makeOwnerOnly(staged);
        return staged;
    }

    /**
     * Withdraws every permission outside the owner's, which is what makes a staged artefact trustable.
     *
     * <p>Applied explicitly rather than relied upon: the permissions a newly created file carries are
     * the process umask's, and a host whose umask grants the group write access would make every local
     * rung of the ladder decline for a reason unrelated to the case. On a filesystem that carries no
     * such permission set the trust check does not consult one either, so there is nothing to withdraw
     * and nothing to fail.
     *
     * @param  path the file or directory to restrict
     * @throws IOException if the permissions cannot be written
     */
    private static void makeOwnerOnly(final Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    Files.isDirectory(path) ? "rwx------" : "rw-------"));
        } catch (final UnsupportedOperationException noPosixPermissions) {
            assertThat(noPosixPermissions).as("a filesystem without POSIX permissions is not asserted"
                    + " against, because the trust check does not consult them there either")
                    .isNotNull();
        }
    }

    /** Reads this suite's origin markers out of a served sequence, in served order. */
    private static List<String> markersOf(final List<Transaction> records) {
        return records.stream().map(CombineTransactionsJobConfigTest::markerOf).toList();
    }

    /**
     * Opens a reader, drains it to exhaustion and closes it, whatever the drain does.
     *
     * @param  reader the reader to exercise
     * @return the markers of the records served, in served order
     * @throws Exception if the reader fails to open or read
     */
    private static List<String> markersServedBy(final ItemStreamReader<Transaction> reader)
            throws Exception {
        reader.open(new ExecutionContext());
        try {
            return markersOf(drain(reader));
        } finally {
            reader.close();
        }
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
        @DisplayName("both inputs are named by their own configuration property, because a combine job "
                + "that read one input instead of two would look perfectly well formed")
        void bothInputsAreNamedByTheirOwnConfigurationProperty() {
            assertThat(CombineTransactionsJobConfig.BACKUP_RESOURCE_PROPERTY)
                    .isEqualTo("carddemo.batch.combine-transactions.transaction-backup");
            assertThat(CombineTransactionsJobConfig.SYNTHESIZED_RESOURCE_PROPERTY)
                    .isEqualTo("carddemo.batch.combine-transactions.synthesized-transaction");
            assertThat(CombineTransactionsJobConfig.BACKUP_RESOURCE_PROPERTY)
                    .isNotEqualTo(CombineTransactionsJobConfig.SYNTHESIZED_RESOURCE_PROPERTY);
        }

        @Test
        @DisplayName("both input properties sit under this job's own configuration prefix, so neither can "
                + "be supplied with a launch")
        void bothInputPropertiesSitUnderThisJobsOwnPrefix() {
            assertThat(CombineTransactionsJobConfig.RESOURCE_PROPERTY_PREFIX)
                    .isEqualTo("carddemo.batch.combine-transactions.");
            assertThat(CombineTransactionsJobConfig.BACKUP_RESOURCE_PROPERTY)
                    .startsWith(CombineTransactionsJobConfig.RESOURCE_PROPERTY_PREFIX);
            assertThat(CombineTransactionsJobConfig.SYNTHESIZED_RESOURCE_PROPERTY)
                    .startsWith(CombineTransactionsJobConfig.RESOURCE_PROPERTY_PREFIX);
        }

        @Test
        @DisplayName("the reader takes no argument at all, so there is no seam through which a caller's "
                + "location could reach the resource loader")
        void theReaderTakesNoArgument() throws NoSuchMethodException {
            assertThat(CombineTransactionsJobConfig.class
                    .getMethod("combineTransactionsOrderedReader").getParameterCount())
                    .as("a location parameter here would be a caller-supplied read")
                    .isZero();
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
                    stagingArea, generationStore, transactionRepository, STAGING_DIRECTORY,
                    "backup.txt", "synthesized.txt"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CombineTransactionsJobConfig(readerFactory, null,
                    stagingArea, generationStore, transactionRepository, STAGING_DIRECTORY,
                    "backup.txt", "synthesized.txt"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CombineTransactionsJobConfig(readerFactory, resourceLoader,
                    null, generationStore, transactionRepository, STAGING_DIRECTORY,
                    "backup.txt", "synthesized.txt"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CombineTransactionsJobConfig(readerFactory, resourceLoader,
                    stagingArea, null, transactionRepository, STAGING_DIRECTORY,
                    "backup.txt", "synthesized.txt"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CombineTransactionsJobConfig(readerFactory, resourceLoader,
                    stagingArea, generationStore, null, STAGING_DIRECTORY,
                    "backup.txt", "synthesized.txt"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CombineTransactionsJobConfig(readerFactory, resourceLoader,
                    stagingArea, generationStore, transactionRepository, null,
                    "backup.txt", "synthesized.txt"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CombineTransactionsJobConfig(readerFactory, resourceLoader,
                    stagingArea, generationStore, transactionRepository, STAGING_DIRECTORY,
                    null, "synthesized.txt"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CombineTransactionsJobConfig(readerFactory, resourceLoader,
                    stagingArea, generationStore, transactionRepository, STAGING_DIRECTORY,
                    "backup.txt", null))
                    .isInstanceOf(NullPointerException.class);
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
            final CombineTransactionsJobConfig.CombinedGeneration combinedGeneration =
                    config.combineTransactionsCombinedGeneration(execution(JOB_EXECUTION_ID));
            final Step orderStep = config.combineTransactionsOrderStep(jobRepository,
                    transactionManager,
                    orderedReaderOver("classpath:absent-backup.txt",
                            "classpath:absent-synthesized.txt"),
                    combinedGeneration);
            final Step loadStep = config.combineTransactionsLoadStep(jobRepository, transactionManager,
                    combinedGeneration,
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
                    orderedReaderOver(backup, synthesized);
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
                    orderedReaderOver(backup, synthesized);
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
                    orderedReaderOver(backup, synthesized);
            reader.open(new ExecutionContext());
            final List<Transaction> ordered = drain(reader);
            reader.close();

            assertThat(ordered).hasSize(2);
            assertThat(markerOf(ordered.get(0))).isEqualTo(BACKUP_MARKER);
            assertThat(markerOf(ordered.get(1))).isEqualTo(SYNTHESIZED_MARKER);
        }

        /**
         * The equal-key contract where it is actually decided: across spilled runs and merge levels.
         *
         * <p><strong>Why the small case above is not enough, stated precisely.</strong> Backup-before-
         * synthesized order among equal identifiers is declared a byte-level contract, and it rests on two
         * mechanisms: a stable in-memory sort within one run, and the run index as the tie-break when
         * several runs are merged. A two-record or three-record case never spills, so it exercises the
         * first mechanism and not the second - removing or reversing the run-index tie-break leaves every
         * such case green while making the delivered order depend on the merge heap's internal
         * arrangement, which is the definition of a byte-different output on identical input.
         *
         * <p>This case crosses both thresholds through the reader the job's ordering step actually uses.
         * The record count exceeds the production run size, so runs are spilled; it exceeds the run size
         * multiplied by the merge fan-in, so the runs cannot be merged in one pass and a second merge level
         * is genuinely reached. <strong>Every record shares one identifier</strong>, so the comparator can
         * distinguish none of them and the emitted order is entirely the tie-break's work. Each record
         * carries its stream and its ordinal in the description field, so the emitted order is readable
         * without inspecting anything the layout does not have.
         *
         * <p>The expectation is the whole concatenation in order: every backup record in the order the
         * backup dataset holds them, then every synthesized record in the order the synthesized dataset
         * holds them. The comparison reports the first position that differs rather than rendering two
         * sixteen-thousand-element lists, because a diagnostic nobody can read is not a diagnostic.
         *
         * @param  directory the staging directory the two datasets are written into
         * @throws Exception if a dataset cannot be written or the reader cannot be drained
         */
        @Test
        @DisplayName("equal identifiers keep the concatenation order across the spill threshold AND the "
                + "merge fan-in, which is where the tie-break rather than the sort decides it")
        void equalIdentifiersKeepTheConcatenationOrderAcrossSpillAndFanIn(
                @TempDir final Path directory) throws Exception {
            // One more than the fan-in's worth of runs, so a single merge pass cannot serve it.
            final int runsRequired = ExternalStringSorter.MAX_MERGE_FAN_IN + 1;
            final int totalRecords = runsRequired * ExternalStringSorter.DEFAULT_RECORDS_PER_RUN;
            final int perStream = totalRecords / 2;
            final String shared = "0000000000000007";

            final List<Transaction> backupRecords = new ArrayList<>(perStream);
            final List<Transaction> synthesizedRecords = new ArrayList<>(perStream);
            final List<String> expectedMarkers = new ArrayList<>(totalRecords);
            for (int ordinal = 0; ordinal < perStream; ordinal++) {
                backupRecords.add(record(shared, ordinalMarker(BACKUP_MARKER, ordinal)));
                expectedMarkers.add(ordinalMarker(BACKUP_MARKER, ordinal));
            }
            for (int ordinal = 0; ordinal < perStream; ordinal++) {
                synthesizedRecords.add(record(shared, ordinalMarker(SYNTHESIZED_MARKER, ordinal)));
            }
            for (int ordinal = 0; ordinal < perStream; ordinal++) {
                expectedMarkers.add(ordinalMarker(SYNTHESIZED_MARKER, ordinal));
            }

            final String backup = dataset(directory, "backup-equal-keys.txt", backupRecords);
            final String synthesized =
                    dataset(directory, "synthesized-equal-keys.txt", synthesizedRecords);

            final ItemStreamReader<Transaction> reader = orderedReaderOver(backup, synthesized);
            reader.open(new ExecutionContext());
            final List<Transaction> ordered = drain(reader);
            reader.close();

            final List<String> actualMarkers = ordered.stream().map(
                    CombineTransactionsJobConfigTest::markerOf).collect(Collectors.toList());
            assertThat(actualMarkers)
                    .as("nothing is deduplicated, summed or dropped at any volume: %d records in, %d out",
                            totalRecords, actualMarkers.size())
                    .hasSize(totalRecords);
            assertThat(firstDifference(expectedMarkers, actualMarkers))
                    .as("the emitted order must be the concatenation order. The first differing "
                            + "position is reported rather than both %d-element sequences; a difference "
                            + "here means equal-key order is decided by the merge heap's internal "
                            + "arrangement rather than by the run index, and the combined stream is "
                            + "byte-different from one run to the next on identical input", totalRecords)
                    .isEqualTo(-1);
            assertThat(ordered)
                    .as("and every record still carries the one shared identifier, so the comparator "
                            + "genuinely decided none of the order above")
                    .allSatisfy(served -> assertThat(served.getTranId()).isEqualTo(shared));
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
                    orderedReaderOver(backup, synthesized);
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
                    orderedReaderOver(backup, synthesized);
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
                    orderedReaderOver(backup, synthesized);
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
                    orderedReaderOver(backup, synthesized);
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
                    orderedReaderOver(backup, synthesized);
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
        @DisplayName("an unconfigured location is refused as a deployment fault, and no default is "
                + "substituted for it")
        void anUnconfiguredLocationIsRefused() {
            assertThatThrownBy(() -> orderedReaderOver("", "file:/tmp/x.txt"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(CombineTransactionsJobConfig.BACKUP_RESOURCE_PROPERTY);
            assertThatThrownBy(() -> orderedReaderOver("file:/tmp/x.txt", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(CombineTransactionsJobConfig.SYNTHESIZED_RESOURCE_PROPERTY);
        }

        @Test
        @DisplayName("a blank location is refused for the same reason an unconfigured one is")
        void aBlankLocationIsRefused() {
            assertThatThrownBy(() -> orderedReaderOver("   ", "file:/tmp/x.txt"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(CombineTransactionsJobConfig.BACKUP_RESOURCE_PROPERTY);
        }

        @Test
        @DisplayName("an absent dataset fails on open rather than yielding an apparently successful "
                + "empty run")
        void anAbsentDatasetFailsOnOpen(@TempDir final Path directory) throws IOException {
            final String present = dataset(directory, "backup.txt", List.of());
            final String absent = "never-created.txt";

            final ItemStreamReader<Transaction> reader =
                    orderedReaderOver(present, absent);

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

            final ItemStreamReader<Transaction> reader = orderedReaderOver(
                    malformed.toUri().toString(), synthesized);

            assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                    .isInstanceOf(ItemStreamException.class);
        }
    }

    // ----------------------------------------------------------------------------------------
    // The staged-input resolution ladder
    // ----------------------------------------------------------------------------------------

    /** The base both inputs default to is a legacy dataset name, so the cases below use one. */
    private static final String LOGICAL_BASE = CombineTransactionsJobConfig.DEFAULT_BACKUP_DATASET_BASE;

    /** Marker of a record served from an exact durable object key. */
    private static final String DURABLE_EXACT = "DURABLE-EXACT";

    /** Marker of a record served from the durable current generation of a base. */
    private static final String DURABLE_CURRENT = "DURABLE-CURRENT";

    /** Marker of a record served from a local staged dataset named exactly as configured. */
    private static final String LOCAL_EXACT = "LOCAL-EXACT";

    /** Marker of a record served from the current local generation of a base. */
    private static final String LOCAL_CURRENT = "LOCAL-CURRENT";

    /** Marker of a record served from an explicit resource location through the loader. */
    private static final String EXPLICIT_LOCATION = "EXPLICIT-LOCATION";

    @Nested
    @DisplayName("a configured location is resolved down a fixed ladder, one rung at a time")
    final class TheStagedInputResolutionLadder {

        @Test
        @DisplayName("a trailing current-generation suffix is stripped before any rung is tried, so the "
                + "spelling the legacy job stream itself uses is accepted verbatim")
        void aTrailingCurrentGenerationSuffixIsStrippedFirst(@TempDir final Path directory)
                throws Exception {
            final String asTheJobStreamWritesIt =
                    LOGICAL_BASE + CombineTransactionsJobConfig.CURRENT_GENERATION_SUFFIX;
            when(stagingArea.holds(LOGICAL_BASE)).thenReturn(true);
            when(stagingArea.stagedInput(LOGICAL_BASE)).thenReturn(
                    durableObject(List.of(record("0000000000000001", DURABLE_EXACT))));

            final List<String> served = markersServedBy(orderedReaderStagedIn(directory,
                    asTheJobStreamWritesIt, neutralInput(directory)));

            assertThat(served).as("the stripped base is what the store was asked for")
                    .containsExactly(DURABLE_EXACT);
            verify(stagingArea, never()).holds(asTheJobStreamWritesIt);
        }

        @Test
        @DisplayName("any other parenthesised suffix is left exactly as configured and refused by name "
                + "validation, rather than being silently reinterpreted as the current generation")
        void anyOtherRelativeGenerationIsNotReinterpreted(@TempDir final Path directory)
                throws IOException {
            final String otherGeneration = LOGICAL_BASE + "(1)";
            final String neutral = neutralInput(directory);

            assertThatThrownBy(() -> orderedReaderStagedIn(directory, otherGeneration, neutral))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside letters, digits, dots, hyphens and underscores");

            verify(stagingArea).holds(otherGeneration);
            verify(stagingArea, never()).holds(LOGICAL_BASE);
        }

        @Test
        @DisplayName("an exact durable object key is served from the store, and no generation of it is "
                + "resolved because none needs to be")
        void anExactDurableKeyIsServedWithoutResolvingAGeneration(@TempDir final Path directory)
                throws Exception {
            final String exactKey = LOGICAL_BASE + "/G0000000004V00";
            when(stagingArea.holds(exactKey)).thenReturn(true);
            when(stagingArea.stagedInput(exactKey)).thenReturn(
                    durableObject(List.of(record("0000000000000002", DURABLE_EXACT))));

            final List<String> served = markersServedBy(
                    orderedReaderStagedIn(directory, exactKey, neutralInput(directory)));

            assertThat(served).containsExactly(DURABLE_EXACT);
            verify(generationStore, never()).currentGenerationKey(anyString());
        }

        @Test
        @DisplayName("a base the store holds no exact object for resolves to the durable current "
                + "generation the catalog would have resolved the relative generation to")
        void aBaseResolvesToItsDurableCurrentGeneration(@TempDir final Path directory)
                throws Exception {
            final String currentGeneration = LOGICAL_BASE + "/G0000000007V00";
            when(stagingArea.holds(LOGICAL_BASE)).thenReturn(false);
            when(generationStore.currentGenerationKey(LOGICAL_BASE))
                    .thenReturn(Optional.of(currentGeneration));
            when(stagingArea.stagedInput(currentGeneration)).thenReturn(
                    durableObject(List.of(record("0000000000000003", DURABLE_CURRENT))));

            final List<String> served = markersServedBy(
                    orderedReaderStagedIn(directory, LOGICAL_BASE, neutralInput(directory)));

            assertThat(served).containsExactly(DURABLE_CURRENT);
            verify(stagingArea).stagedInput(currentGeneration);
        }

        @Test
        @DisplayName("a trusted local dataset named exactly as configured is accepted when the durable "
                + "store holds nothing, which is the deployment whose predecessor staged locally")
        void aTrustedLocalDatasetIsAcceptedWhenNothingIsDurable(@TempDir final Path directory)
                throws Exception {
            final Path root = Files.createDirectory(directory.resolve("staging"));
            final String staged = stagedUnder(root, LOGICAL_BASE,
                    record("0000000000000004", LOCAL_EXACT));

            final List<String> served = markersServedBy(
                    orderedReaderStagedIn(root, staged, neutralInput(directory)));

            assertThat(served).containsExactly(LOCAL_EXACT);
            verify(stagingArea).holds(LOGICAL_BASE);
            verify(generationStore).currentGenerationKey(LOGICAL_BASE);
        }

        @Test
        @DisplayName("a local entry that is not a regular file this process owns is refused with a "
                + "diagnostic and the scan continues to the next rung, rather than being repaired")
        void anUntrustedLocalEntryIsRefusedAndTheScanContinues(@TempDir final Path directory)
                throws Exception {
            final Path root = Files.createDirectory(directory.resolve("staging"));
            // Named exactly as configured and therefore a candidate, but a directory rather than a
            // regular file - which is one of the several ways a local actor can satisfy the name
            // without being this deployment's staged output.
            Files.createDirectory(root.resolve(LOGICAL_BASE));
            stagedGeneration(root, LOGICAL_BASE, 3L, record("0000000000000005", LOCAL_CURRENT));
            final Logger resolution =
                    (Logger) LoggerFactory.getLogger(CombineTransactionsJobConfig.class);
            final ListAppender<ILoggingEvent> recorder = new ListAppender<>();
            recorder.setContext(resolution.getLoggerContext());
            recorder.start();
            resolution.addAppender(recorder);

            final List<String> served;
            try {
                served = markersServedBy(
                        orderedReaderStagedIn(root, LOGICAL_BASE, neutralInput(directory)));
            } finally {
                resolution.detachAppender(recorder);
                recorder.stop();
            }

            assertThat(served).as("the refusal falls through to the next rung rather than failing")
                    .containsExactly(LOCAL_CURRENT);
            assertThat(recorder.list).anySatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARN);
                assertThat(record.getFormattedMessage())
                        .contains(CombineTransactionsJobConfig.BACKUP_RESOURCE_PROPERTY)
                        .contains(LOGICAL_BASE)
                        .contains("not a regular file this process owns");
            });
        }

        @Test
        @DisplayName("the current local generation of a base is the highest generation the root holds, "
                + "which is the state the chain is in while it runs")
        void theCurrentLocalGenerationIsTheHighestOneHeld(@TempDir final Path directory)
                throws Exception {
            final Path root = Files.createDirectory(directory.resolve("staging"));
            stagedGeneration(root, LOGICAL_BASE, 2L, record("0000000000000006", "SUPERSEDED"));
            stagedGeneration(root, LOGICAL_BASE, 5L, record("0000000000000007", LOCAL_CURRENT));

            final List<String> served = markersServedBy(
                    orderedReaderStagedIn(root, LOGICAL_BASE, neutralInput(directory)));

            assertThat(served).containsExactly(LOCAL_CURRENT);
        }

        @Test
        @DisplayName("an explicit resource location goes to the application resource loader and skips "
                + "every staging rung, because it names one resource and not a base")
        void anExplicitResourceLocationSkipsEveryStagingRung(@TempDir final Path directory)
                throws Exception {
            final Path root = Files.createDirectory(directory.resolve("staging"));
            // Staged under the root as well, so that a rung which ran anyway would be visible: it would
            // serve this marker instead of the explicitly located one.
            stagedUnder(root, LOGICAL_BASE, record("0000000000000008", LOCAL_EXACT));
            final String explicit = dataset(directory, "explicit.txt",
                    List.of(record("0000000000000009", EXPLICIT_LOCATION)));

            final List<String> served = markersServedBy(
                    orderedReaderStagedIn(root, explicit, neutralInput(directory)));

            assertThat(served).containsExactly(EXPLICIT_LOCATION);
            verify(generationStore, never()).currentGenerationKey(anyString());
        }

        @Test
        @DisplayName("an unconfigured deployment resolves the legacy dataset names, because each "
                + "input's binding default is the base the legacy job stream itself declares")
        void anUnconfiguredDeploymentResolvesTheLegacyDatasetNames() {
            assertThat(CombineTransactionsJobConfig.DEFAULT_BACKUP_DATASET_BASE)
                    .as("the measured job stream declares this base on its first input's DD statement")
                    .isEqualTo("AWS.M2.CARDDEMO.TRANSACT.BKUP");
            assertThat(CombineTransactionsJobConfig.DEFAULT_SYNTHESIZED_DATASET_BASE)
                    .as("and this base on its second, which the interest job mints a generation of")
                    .isEqualTo("AWS.M2.CARDDEMO.SYSTRAN");

            final List<String> boundExpressions =
                    Arrays.stream(CombineTransactionsJobConfig.class.getDeclaredConstructors())
                            .flatMap(constructor -> Arrays.stream(constructor.getParameters()))
                            .map(parameter -> parameter.getAnnotation(Value.class))
                            .filter(Objects::nonNull)
                            .map(Value::value)
                            .toList();

            assertThat(boundExpressions)
                    .as("an unset property binds to the legacy base rather than to nothing, which is"
                            + " what makes the blank refusal a deployment fault and not the ordinary"
                            + " case; a location therefore only arrives blank when somebody emptied it")
                    .contains("${" + CombineTransactionsJobConfig.BACKUP_RESOURCE_PROPERTY + ":"
                                    + CombineTransactionsJobConfig.DEFAULT_BACKUP_DATASET_BASE + "}",
                            "${" + CombineTransactionsJobConfig.SYNTHESIZED_RESOURCE_PROPERTY + ":"
                                    + CombineTransactionsJobConfig.DEFAULT_SYNTHESIZED_DATASET_BASE
                                    + "}");
        }

        @Test
        @DisplayName("a base no rung can resolve fails on open rather than yielding an apparently "
                + "successful empty run, and every rung is consulted before it does")
        void aBaseNoRungResolvesFailsOnOpen(@TempDir final Path directory) throws IOException {
            final Path root = Files.createDirectory(directory.resolve("staging"));
            final String neutral = neutralInput(directory);

            final ItemStreamReader<Transaction> reader =
                    orderedReaderStagedIn(root, LOGICAL_BASE, neutral);

            assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                    .isInstanceOf(ItemStreamException.class)
                    .hasMessageContaining("could not be read in full");
            verify(stagingArea).holds(LOGICAL_BASE);
            verify(generationStore).currentGenerationKey(LOGICAL_BASE);
            verify(stagingArea, never()).stagedInput(anyString());
        }
    }

    @Nested
    @DisplayName("where two rungs could both answer, the higher one does")
    final class TheLadderRungsTakePrecedenceInOrder {

        @Test
        @DisplayName("an exact durable object outranks the durable current generation of the same base")
        void anExactDurableObjectOutranksTheDurableCurrentGeneration(@TempDir final Path directory)
                throws Exception {
            when(stagingArea.holds(LOGICAL_BASE)).thenReturn(true);
            when(stagingArea.stagedInput(LOGICAL_BASE)).thenReturn(
                    durableObject(List.of(record("0000000000000010", DURABLE_EXACT))));
            when(generationStore.currentGenerationKey(LOGICAL_BASE))
                    .thenReturn(Optional.of(LOGICAL_BASE + "/G0000000009V00"));

            final List<String> served = markersServedBy(
                    orderedReaderStagedIn(directory, LOGICAL_BASE, neutralInput(directory)));

            assertThat(served).containsExactly(DURABLE_EXACT);
            verify(generationStore, never()).currentGenerationKey(anyString());
        }

        @Test
        @DisplayName("the durable current generation outranks a trusted local dataset of the same name")
        void theDurableCurrentGenerationOutranksATrustedLocalDataset(@TempDir final Path directory)
                throws Exception {
            final Path root = Files.createDirectory(directory.resolve("staging"));
            stagedUnder(root, LOGICAL_BASE, record("0000000000000011", LOCAL_EXACT));
            final String currentGeneration = LOGICAL_BASE + "/G0000000012V00";
            when(generationStore.currentGenerationKey(LOGICAL_BASE))
                    .thenReturn(Optional.of(currentGeneration));
            when(stagingArea.stagedInput(currentGeneration)).thenReturn(
                    durableObject(List.of(record("0000000000000012", DURABLE_CURRENT))));

            final List<String> served = markersServedBy(
                    orderedReaderStagedIn(root, LOGICAL_BASE, neutralInput(directory)));

            assertThat(served).containsExactly(DURABLE_CURRENT);
        }

        @Test
        @DisplayName("a trusted local dataset named exactly as configured outranks the current local "
                + "generation of the same base")
        void aTrustedLocalDatasetOutranksTheCurrentLocalGeneration(@TempDir final Path directory)
                throws Exception {
            final Path root = Files.createDirectory(directory.resolve("staging"));
            stagedUnder(root, LOGICAL_BASE, record("0000000000000013", LOCAL_EXACT));
            stagedGeneration(root, LOGICAL_BASE, 9L, record("0000000000000014", LOCAL_CURRENT));

            final List<String> served = markersServedBy(
                    orderedReaderStagedIn(root, LOGICAL_BASE, neutralInput(directory)));

            assertThat(served).containsExactly(LOCAL_EXACT);
        }

        @Test
        @DisplayName("the two inputs are resolved independently, so one may come from the durable store "
                + "while the other comes from the local root, and the concatenation order still holds")
        void eachInputIsResolvedIndependentlyOfTheOther(@TempDir final Path directory)
                throws Exception {
            final Path root = Files.createDirectory(directory.resolve("staging"));
            final String synthesizedBase =
                    CombineTransactionsJobConfig.DEFAULT_SYNTHESIZED_DATASET_BASE;
            when(stagingArea.holds(LOGICAL_BASE)).thenReturn(true);
            when(stagingArea.stagedInput(LOGICAL_BASE)).thenReturn(
                    durableObject(List.of(record("0000000000000015", DURABLE_EXACT))));
            stagedUnder(root, synthesizedBase, record("0000000000000015", LOCAL_EXACT));

            final List<String> served = markersServedBy(
                    orderedReaderStagedIn(root, LOGICAL_BASE, synthesizedBase));

            assertThat(served).as("equal identifiers keep the concatenation order across two rungs")
                    .containsExactly(DURABLE_EXACT, LOCAL_EXACT);
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
                    config.combineTransactionsCombinedGeneration(execution(JOB_EXECUTION_ID));
            final Transaction first = record("0000000000000001", BACKUP_MARKER);
            final Transaction second = record("0000000000000002", SYNTHESIZED_MARKER);

            combined.write(Chunk.of(first));
            combined.write(Chunk.of(second));

            // Value equality and not identity: the generation is a sequential file, so a served record
            // is the record image parsed back, exactly as the legacy load step read the dataset the
            // ordering step wrote. Every field must survive that round trip, which is more than
            // identity would have proved.
            assertThat(combined.read()).isEqualTo(first);
            assertThat(combined.read()).isEqualTo(second);
            assertThat(combined.read()).isNull();
        }

        @Test
        @DisplayName("every field of a record survives the round trip through the generation, so the "
                + "sequential file is a faithful stand-in for holding the entity")
        void everyFieldSurvivesTheRoundTrip() throws Exception {
            final CombineTransactionsJobConfig.CombinedGeneration combined =
                    config.combineTransactionsCombinedGeneration(execution(JOB_EXECUTION_ID));
            final Transaction written = record("0000000000000042", BACKUP_MARKER);

            combined.write(Chunk.of(written));
            final Transaction served = combined.read();

            assertThat(served).usingRecursiveComparison().isEqualTo(written);
        }

        @Test
        @DisplayName("the generation is composed in a file and never held as a list, so the job's cost "
                + "does not grow with the size of the master it combines")
        void theGenerationIsComposedInAFile() throws Exception {
            final CombineTransactionsJobConfig.CombinedGeneration combined =
                    config.combineTransactionsCombinedGeneration(execution(JOB_EXECUTION_ID));
            combined.write(Chunk.of(record("0000000000000001", BACKUP_MARKER)));
            combined.close();

            final Path generation = generationFile(JOB_EXECUTION_ID);
            assertThat(generation).exists();
            assertThat(Files.readAllBytes(generation))
                    .as("one record image and nothing else: the combined dataset is fixed-length"
                            + " blocked, so no byte is written between two records (DL-213)")
                    .hasSize(CombineTransactionsProcessor.COMBINED_RECORD_LENGTH);
            assertThat(Files.readString(generation, StandardCharsets.US_ASCII))
                    .as("and no separator survives anywhere in it")
                    .doesNotContain("\n");

            final String source = Files.readString(
                    Path.of("src", "main", "java", "com", "carddemo", "batch",
                            "CombineTransactionsJobConfig.java"),
                    StandardCharsets.UTF_8);
            assertThat(source)
                    .doesNotContain("ByteArrayOutputStream")
                    .doesNotContain("List<Transaction> records");
        }

        @Test
        @DisplayName("an empty generation reports exhaustion immediately rather than failing")
        void anEmptyGenerationReportsExhaustion() throws Exception {
            assertThat(config.combineTransactionsCombinedGeneration(execution(JOB_EXECUTION_ID)).read())
                    .isNull();
        }

        @Test
        @DisplayName("each job execution receives its own generation, so one execution's records "
                + "cannot reach another's")
        void eachExecutionReceivesItsOwnGeneration() throws Exception {
            final CombineTransactionsJobConfig.CombinedGeneration first =
                    config.combineTransactionsCombinedGeneration(execution(JOB_EXECUTION_ID));
            first.write(Chunk.of(record("0000000000000001", BACKUP_MARKER)));

            assertThat(config.combineTransactionsCombinedGeneration(execution(JOB_EXECUTION_ID + 1)).read())
                    .isNull();
        }

        @Test
        @DisplayName("a group is required, because its absence would mean the framework contract was "
                + "breached rather than that the group was empty")
        void aGroupIsRequired() {
            final CombineTransactionsJobConfig.CombinedGeneration combined =
                    config.combineTransactionsCombinedGeneration(execution(JOB_EXECUTION_ID));

            assertThatThrownBy(() -> combined.write(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("closing registers the execution generation once for durable publication, under the "
                + "legacy generation-group base, and uploads nothing itself")
        void closingRegistersTheExecutionGenerationOnce() throws Exception {
            final JobExecution jobExecution = execution(JOB_EXECUTION_ID);
            final CombineTransactionsJobConfig.CombinedGeneration combined =
                    config.combineTransactionsCombinedGeneration(jobExecution);
            combined.write(Chunk.of(record("0000000000000001", BACKUP_MARKER)));

            combined.close();
            combined.close();

            // ONE registration for two closes, and no upload from the step at all. The job-boundary
            // listener publishes what is registered, once the whole submission has completed, so a
            // submission that fails afterwards leaves nothing in the bucket - and there is exactly one
            // key family per generation because there is exactly one publisher (DL-212).
            assertThat(StagedGenerationStore.registeredArtifactCount(jobExecution)).isEqualTo(1);
            assertThat(generationFile(JOB_EXECUTION_ID))
                    .as("the registration names the sealed local file, which must therefore still be"
                            + " there when the publication runs")
                    .exists();
        }

        @Test
        @DisplayName("the sealed generation survives being served in full, because the publication that "
                + "uploads it has not run yet when the load step closes")
        void theSealedGenerationSurvivesBeingServed() throws Exception {
            final CombineTransactionsJobConfig.CombinedGeneration combined =
                    config.combineTransactionsCombinedGeneration(execution(JOB_EXECUTION_ID));
            combined.write(Chunk.of(record("0000000000000001", BACKUP_MARKER)));
            combined.close();
            assertThat(generationFile(JOB_EXECUTION_ID)).exists();

            combined.open(new ExecutionContext());
            while (combined.read() != null) {
                // Drain, exactly as the load step does.
            }
            combined.close();

            assertThat(generationFile(JOB_EXECUTION_ID))
                    .as("removing it here left the registered publication with nothing to upload; a"
                            + " submission that does not complete has its own generation swept by the"
                            + " store's abnormal disposition instead (DL-211)")
                    .exists();
        }

        @Test
        @DisplayName("an absent job execution cannot name a staged generation")
        void anAbsentExecutionIdentifierIsRefused() {
            assertThatThrownBy(() -> config.combineTransactionsCombinedGeneration(null))
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
        @DisplayName("each record is inserted and flushed into the transaction master")
        void eachRecordIsInsertedAndFlushedIntoTheMaster() throws Exception {
            final ItemWriter<Transaction> writer = config.combineTransactionsMasterWriter();
            final Transaction loaded = record("0000000000000001", BACKUP_MARKER);

            writer.write(Chunk.of(loaded));

            verify(transactionRepository).insertAndFlush(loaded);
        }

        @Test
        @DisplayName("an empty group is a no-operation rather than an empty insert")
        void anEmptyGroupIsANoOperation() throws Exception {
            config.combineTransactionsMasterWriter().write(new Chunk<>(List.of()));

            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("a duplicate key fails the writer instead of merging over the existing row")
        void aDuplicateKeyFailsTheWriter() {
            final Transaction loaded = record("0000000000000001", BACKUP_MARKER);
            final DuplicateKeyException duplicate =
                    new DuplicateKeyException("duplicate transaction identifier");
            when(transactionRepository.insertAndFlush(loaded)).thenThrow(duplicate);

            assertThatThrownBy(() -> config.combineTransactionsMasterWriter()
                    .write(Chunk.of(loaded)))
                    .isSameAs(duplicate);
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
            when(closesBadly.fixedTransactionReader(any())).thenReturn(new ClosesBadly());
            final CombineTransactionsJobConfig configuration = new CombineTransactionsJobConfig(
                    closesBadly, resourceLoader, stagingArea, generationStore, transactionRepository,
                    STAGING_DIRECTORY, "backup.txt", "synthesized.txt");

            final ItemStreamReader<Transaction> reader =
                    configuration.combineTransactionsOrderedReader();
            reader.open(new ExecutionContext());

            assertThat(reader.read())
                    .as("both inputs were read to exhaustion and neither close failure surfaced")
                    .isNull();
            reader.close();
        }

        @Test
        @DisplayName("the ordered work file and the per-execution directory minted to hold it are both "
                + "removed, so a run leaves nothing behind on the host")
        void bothTheWorkFileAndItsDirectoryAreRemoved() throws Exception {
            final Set<Path> before = orderingWorkAreas();
            final FixedWidthFlatFileReaderFactory empty = mock(FixedWidthFlatFileReaderFactory.class);
            when(empty.fixedTransactionReader(any())).thenReturn(new ClosesBadly());
            final ItemStreamReader<Transaction> reader = new CombineTransactionsJobConfig(empty,
                    resourceLoader, stagingArea, generationStore, transactionRepository, STAGING_DIRECTORY,
                    "backup.txt", "synthesized.txt").combineTransactionsOrderedReader();

            reader.open(new ExecutionContext());
            final Set<Path> during = orderingWorkAreas();
            reader.close();

            assertThat(during)
                    .as("the pass mints exactly one work area of its own")
                    .hasSize(before.size() + 1);
            assertThat(orderingWorkAreas())
                    .as("a work area minted per execution and never removed accumulates one empty "
                            + "directory per run for the life of the host")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("and closing twice releases nothing a second time and raises nothing, because the "
                + "framework may close a stream it has already closed")
        void closingTwiceIsSilent() throws Exception {
            final FixedWidthFlatFileReaderFactory empty = mock(FixedWidthFlatFileReaderFactory.class);
            when(empty.fixedTransactionReader(any())).thenReturn(new ClosesBadly());
            final ItemStreamReader<Transaction> reader = new CombineTransactionsJobConfig(empty,
                    resourceLoader, stagingArea, generationStore, transactionRepository, STAGING_DIRECTORY,
                    "backup.txt", "synthesized.txt").combineTransactionsOrderedReader();
            reader.open(new ExecutionContext());
            reader.close();

            assertThatNoException().isThrownBy(reader::close);
        }

        @Test
        @DisplayName("a cleanup that cannot complete reports its first failure and carries the later one "
                + "beneath it, having attempted both rather than stopping at the first")
        void aCleanupFailureCarriesTheLaterOneBeneathIt() throws Exception {
            final Set<Path> before = orderingWorkAreas();
            final FixedWidthFlatFileReaderFactory empty = mock(FixedWidthFlatFileReaderFactory.class);
            when(empty.fixedTransactionReader(any())).thenReturn(new ClosesBadly());
            final ItemStreamReader<Transaction> reader = new CombineTransactionsJobConfig(empty,
                    resourceLoader, stagingArea, generationStore, transactionRepository, STAGING_DIRECTORY,
                    "backup.txt", "synthesized.txt").combineTransactionsOrderedReader();
            reader.open(new ExecutionContext());
            final Set<Path> minted = new HashSet<>(orderingWorkAreas());
            minted.removeAll(before);
            final Path area = minted.iterator().next();
            final Path work = onlyEntryOf(area);
            // The work file's path is made undeletable by making it a non-empty directory, which every
            // account including a privileged one is refused. The work area then cannot be removed
            // either, because it still holds that directory - and the second failure exists at all only
            // if the release carried on past the first, which is the property under test.
            Files.delete(work);
            Files.createDirectory(work);
            Files.writeString(work.resolve("occupant"), "x", StandardCharsets.US_ASCII);
            try {
                final Throwable reported = catchThrowable(reader::close);

                assertThat(reported)
                        .isInstanceOf(ItemStreamException.class)
                        .hasMessageContaining("could not be fully released");
                assertThat(reported.getCause())
                        .as("the first cleanup failure is the one reported")
                        .isInstanceOf(IOException.class);
                assertThat(reported.getSuppressed())
                        .as("the release carried on past the first failure and the second is attached "
                                + "beneath the first rather than replacing it or being discarded")
                        .hasSize(1);
                assertThat(reported.getSuppressed()[0]).isInstanceOf(IOException.class);
                assertThat(catchThrowable(reader::close))
                        .as("every handle was cleared, so a later close finds nothing")
                        .isNull();
            } finally {
                Files.deleteIfExists(work.resolve("occupant"));
                Files.deleteIfExists(work);
                Files.deleteIfExists(area);
            }
        }

        /**
         * @param  directory the directory to read
         * @return the single entry it holds
         * @throws IOException if it cannot be listed
         */
        private Path onlyEntryOf(final Path directory) throws IOException {
            try (var held = Files.list(directory)) {
                final List<Path> entries = held.toList();
                assertThat(entries).as("the work area holds exactly the ordered work file").hasSize(1);
                return entries.get(0);
            }
        }

        @Test
        @DisplayName("and a preparation failure is reported as itself, never replaced by whatever the "
                + "cleanup that follows it happens to find")
        void aPreparationFailureIsNotReplacedByACleanupFailure() throws Exception {
            final Set<Path> before = orderingWorkAreas();
            final FixedWidthFlatFileReaderFactory refuses =
                    mock(FixedWidthFlatFileReaderFactory.class);
            when(refuses.fixedTransactionReader(any()))
                    .thenThrow(new IllegalStateException("the input could not be allocated"));
            final ItemStreamReader<Transaction> reader = new CombineTransactionsJobConfig(refuses,
                    resourceLoader, stagingArea, generationStore, transactionRepository, STAGING_DIRECTORY,
                    "backup.txt", "synthesized.txt").combineTransactionsOrderedReader();

            assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                    .as("the reason the step failed is the preparation failure and not the release")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("could not be allocated");

            assertThat(orderingWorkAreas())
                    .as("a failed preparation still releases what it had minted")
                    .isEqualTo(before);
        }

        /**
         * The ordering work areas currently present in the shared staging root.
         *
         * @return their paths, which the pass mints and removes one per execution
         * @throws IOException if the root cannot be listed
         */
        private Set<Path> orderingWorkAreas() throws IOException {
            final Path root = Path.of(System.getProperty("java.io.tmpdir"));
            try (var held = Files.list(root)) {
                return held.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString()
                                .startsWith("carddemo-combine-order-"))
                        .collect(Collectors.toCollection(HashSet::new));
            }
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
        @DisplayName("the batch tier names no transport record and no controller, so the layering "
                + "direction holds for this file on its own")
        void theBatchTierNamesNoTransportRecordOrController() throws IOException {
            final List<String> upward = source().lines()
                    .filter(line -> line.startsWith("import com.carddemo.api"))
                    .toList();

            assertThat(upward).isEmpty();
        }

        @Test
        @DisplayName("this file names no configuration type at all, which is stricter than the one edge "
                + "the module's layering would have permitted")
        void theFileNamesNoConfigurationType() throws IOException {
            final List<String> configurationImports = source().lines()
                    .filter(line -> line.startsWith("import com.carddemo.config"))
                    .toList();

            assertThat(configurationImports)
                    .as("the staged-input allow-list is owned by JobParameterValidators, which resolves "
                            + "the provisioned bucket itself, so this file has no reason to name the "
                            + "bound cloud settings and does not - and a configuration import that no "
                            + "declaration uses is exactly the dead reference this assertion now forbids")
                    .isEmpty();
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
