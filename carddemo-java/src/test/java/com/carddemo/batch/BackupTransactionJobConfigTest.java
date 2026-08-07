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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig.ConditionCodeGate;
import com.carddemo.domain.Transaction;
import com.carddemo.exception.AbendException;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionScanRepository;
import com.carddemo.util.TransactionRecordMapper;
import com.carddemo.support.OrderedTransactionScan;
import io.awspring.cloud.s3.Location;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Verifies the transaction backup job: the condition-code ceiling it selects, the archive it writes,
 * the clear it performs, and the postures it is required to keep.
 *
 * <p><strong>Why the ceiling is asserted first and most.</strong> The gate on the legacy member this
 * job translates is the fourth and last of the estate's four condition-code step gates, and the
 * migration plan is the frozen contract for how those four translate: <em>all</em> four are the strict
 * form, admitting a prior return code of zero and nothing above it. Loosening this one to admit a
 * warning would compile, would read like faithfulness to the member's own literal, and would run the
 * reset behind a nonzero prior code that the plan holds the gate against. That loosening is the single
 * most plausible defect in this configuration, so this suite pins the selection both behaviourally,
 * through the ceiling the gate admits and the codes it refuses, and textually, so that a future edit to
 * the constant name cannot pass unnoticed. The differently spelled literal on the legacy member is
 * recorded in {@code docs/decision-log.md} entry DL-145 and is deliberately not asserted as behaviour
 * anywhere: a test that protected the looser reading would make a departure from the plan look correct.
 *
 * <p><strong>Why the widths are asserted in encoded bytes.</strong> The archive is a fixed-width
 * record image, so a suite that compared parsed fields would pass while a record was a byte wide of
 * its allocation or a trailing space run had been trimmed - and the combine job that reads the
 * archive back would then fail instead. Every width here is measured on bytes.
 *
 * <p><strong>What is driven rather than inspected.</strong> Both steps are executed for real, through
 * the framework's own step contract, against a mocked repository and a mocked object store. That is
 * what lets the suite assert the properties an operator depends on: that exactly one object is
 * written, that no bucket is ever created or probed, that the object name differs between two
 * executions even when the clock does not, that clearing an already-empty master succeeds, and that a
 * failure to store the archive abends rather than completing quietly. No reflection is used and no
 * static call is mocked, in keeping with the rest of the test estate.
 *
 * <p>Provenance of the expectations: the legacy job member, its unload wrapper and control member,
 * and the generation-base declaration, read at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@DisplayName("BackupTransactionJobConfig - the strict ceiling, the 350-byte archive and the clear")
final class BackupTransactionJobConfigTest {

    /** A destination deliberately unlike the shipped default, so a hardcoded name cannot pass. */
    private static final String BUCKET = "unit-test-staging-bucket";

    /** A region deliberately unlike the shipped default, for the same reason. */
    private static final String REGION = "eu-west-2";

    /**
     * The number of records the archive assertions drive through the step.
     *
     * <p>The committed golden archive carries exactly this many records, and one of the assertions below
     * checks that it does, so raising this figure without regenerating the fixture fails loudly rather
     * than quietly comparing against the wrong expectation.
     */
    private static final int MASTER_SIZE = 3;

    /**
     * The committed golden archive: the exact external bytes one archive of {@link #MASTER_SIZE} records
     * must have.
     *
     * <p>Sits beside the module's other fixed-width golden files, which pin the 430-, 133-, 100- and
     * 80-byte formats; this one pins the 350-byte archive that {@code app/jcl/COMBTRAN.jcl:24} reads
     * back.
     */
    private static final String GOLDEN_ARCHIVE_FIXTURE =
            "/fixtures/expected/transaction-archive.b64";

    /** The configuration's own source, read for the postures that are textual by nature. */
    private static final Path SOURCE = Path.of("src", "main", "java", "com", "carddemo", "batch",
            "BackupTransactionJobConfig.java");

    /** The timer name both steps are recorded under. */
    private static final String STEP_TIMER = "carddemo.batch.job.step";

    private TransactionRepository transactionRepository;

    private TransactionScanRepository transactionScanRepository;

    private S3Operations objectStore;

    private MeterRegistry meterRegistry;

    private BackupTransactionJobConfig config;

    /**
     * The staging root this test's archive is composed in.
     *
     * <p>The archive is streamed to a file and uploaded from it, so the test needs a real directory. A
     * per-test temporary one also proves the local copy is removed after publication: nothing may be
     * left in it once the step has closed.
     */
    @TempDir
    private Path stagingDirectory;

    /**
     * Bodies the store received, drained <em>while the stream was open</em>.
     *
     * <p>The upload streams from the composed file and closes the stream as it returns, so a captured
     * argument cannot be read afterwards. An observer that must see the bytes has to read them during
     * the call, which is what this answer does - and it is also what a real object store does.
     */
    private List<byte[]> uploadedBodies;

    /**
     * The keys the double has accepted, in upload order, so that a listing can report them.
     *
     * <p>Recorded because the generation number is allocated against what the base already holds; a
     * double whose listing did not grow would make every execution allocate the same generation.
     */
    private List<String> uploadedKeys;

    @BeforeEach
    void buildConfigurationOverMockedCollaborators() throws IOException {
        this.transactionRepository = mock(TransactionRepository.class);
        this.transactionScanRepository = new OrderedTransactionScan(
                () -> this.transactionRepository.findAll(Sort.by(Sort.Direction.ASC, "tranId")));
        this.objectStore = mock(S3Operations.class);
        this.uploadedBodies = new ArrayList<>();
        this.uploadedKeys = new ArrayList<>();
        doAnswer(invocation -> {
            this.uploadedKeys.add(invocation.getArgument(1, String.class));
            this.uploadedBodies.add(
                    ((InputStream) invocation.getArgument(2)).readAllBytes());
            return null;
        }).when(this.objectStore).upload(anyString(), anyString(), any(InputStream.class));
        // A listing that reflects what has been uploaded, because the durable generation number is
        // allocated as one more than the highest generation the base already holds (DL-210). A double
        // that always reported an empty base would let every execution allocate generation one and would
        // therefore have hidden the very collision this suite asserts the absence of.
        when(this.objectStore.listObjects(anyString(), anyString())).thenAnswer(invocation -> {
            final String bucket = invocation.getArgument(0, String.class);
            final String prefix = invocation.getArgument(1, String.class);
            final List<S3Resource> present = new ArrayList<>();
            for (final String key : this.uploadedKeys) {
                if (key.startsWith(prefix)) {
                    final S3Resource resource = mock(S3Resource.class);
                    when(resource.getLocation()).thenReturn(Location.of(bucket, key));
                    present.add(resource);
                }
            }
            return present;
        });
        this.meterRegistry = new SimpleMeterRegistry();
        this.config = configWithBucket(BUCKET);
    }

    /**
     * Builds the configuration over the already-created mocks, aimed at a nominated bucket.
     *
     * @param bucket the destination the settings bean carries
     * @return the configuration under test
     */
    private BackupTransactionJobConfig configWithBucket(final String bucket) {
        final JobRepository jobRepository = mock(JobRepository.class);
        final JobExecutionListener boundaryListener = mock(JobExecutionListener.class);
        final JobParametersIncrementer incrementer = new RunIdIncrementer();
        final PlatformTransactionManager transactionManager = new ResourcelessTransactionManager();
        final AwsProperties awsProperties = new AwsProperties(REGION, null,
                new AwsProperties.S3(bucket),
                new AwsProperties.Sqs("JOBS.fifo", "JOBS"),
                new AwsProperties.Sns("carddemo-job-notifications"));
        final Clock fixed = Clock.fixed(Instant.parse("2026-08-04T07:48:12.34Z"), ZoneOffset.UTC);

        return new BackupTransactionJobConfig(jobRepository, transactionManager,
                boundaryListener, incrementer, this.transactionRepository,
                this.transactionScanRepository, this.objectStore,
                // Runs each publication directly. This test drives one publication at a time from one
                // thread, so there is nothing to serialize; the lock's acquisition ordering and its
                // failure-to-acquire behaviour are asserted in its own test.
                (bases, publication) -> publication.run(),
                awsProperties, this.meterRegistry, fixed,
                this.stagingDirectory.toString());
    }

    // ----------------------------------------------------------------------------------------
    // The condition-code ceiling
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The gate demands a prior zero, the same strict rule as the estate's other three")
    final class TheGateDemandsAPriorZero {

        @Test
        @DisplayName("the ceiling this job selects is zero, so only a wholly clean run reaches the clear")
        void theCeilingIsZero() {
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.highestToleratedReturnCode())
                    .as("the plan defines all four condition-code step gates as the strict form")
                    .isZero();
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.permits(0)).isTrue();
        }

        @ParameterizedTest(name = "a prior return code of {0} is refused")
        @ValueSource(ints = {1, 4, 5, 8, 12})
        @DisplayName("anything above zero is refused, four included, so no nonzero code reaches the clear")
        void anythingAboveZeroIsRefused(final int priorReturnCode) {
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.permits(priorReturnCode))
                    .as("admitting %d here would loosen a gate the plan holds to zero", priorReturnCode)
                    .isFalse();
        }

        @Test
        @DisplayName("one ceiling exists, so there is no looser policy this job could have selected")
        void oneCeilingExists() {
            assertThat(ConditionCodeGate.values())
                    .as("a second constant would describe a gate the plan does not define")
                    .containsExactly(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO);
        }

        @Test
        @DisplayName("the configuration names the strict ceiling and routes both verdicts")
        void theConfigurationNamesTheStrictCeiling() throws IOException {
            final String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

            assertThat(source).contains("ConditionCodeGate.ALL_PRIOR_STEPS_ZERO");
            assertThat(source)
                    .as("both verdicts must be routed, so a refusal is distinguished from a failure")
                    .contains("ConditionCodeGate.PERMITTED")
                    .contains("ConditionCodeGate.REFUSED");
            assertThat(source)
                    .as("routing on the framework's failure status alone would admit a step that "
                            + "completed while stating a nonzero code of its own")
                    .doesNotContain(".on(\"FAILED\")");
        }
    }

    // ----------------------------------------------------------------------------------------
    // The job and its steps
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The job is named, owns both steps and is the configuration's only bean")
    final class TheJobIsNamedAndOwnsBothSteps {

        @Test
        @DisplayName("carries the published job name, which is how it is launched")
        void carriesThePublishedJobName() {
            assertThat(BackupTransactionJobConfig.JOB_NAME).isEqualTo("backupTransactionJob");
            assertThat(config.backupTransactionJob().getName())
                    .isEqualTo(BackupTransactionJobConfig.JOB_NAME);
        }

        @Test
        @DisplayName("locates both steps by their published names, so either can be run alone")
        void locatesBothStepsByName() {
            final Job job = config.backupTransactionJob();

            assertThat(job).isInstanceOf(StepLocator.class);
            assertThat(((StepLocator) job).getStepNames())
                    .containsExactlyInAnyOrder(BackupTransactionJobConfig.ARCHIVE_STEP_NAME,
                            BackupTransactionJobConfig.RESET_STEP_NAME);
        }

        @Test
        @DisplayName("gives the two steps distinct, stable names")
        void givesTheTwoStepsDistinctStableNames() {
            assertThat(BackupTransactionJobConfig.ARCHIVE_STEP_NAME)
                    .isEqualTo("backupTransactionArchiveStep");
            assertThat(BackupTransactionJobConfig.RESET_STEP_NAME)
                    .isEqualTo("backupTransactionResetStep");
            assertThat(BackupTransactionJobConfig.ARCHIVE_STEP_NAME)
                    .isNotEqualTo(BackupTransactionJobConfig.RESET_STEP_NAME);
        }

        @Test
        @DisplayName("is rebuildable, and a second build is an equivalent job rather than a shared one")
        void isRebuildable() {
            assertThat(config.backupTransactionJob())
                    .isNotSameAs(config.backupTransactionJob());
            assertThat(config.backupTransactionJob().getName())
                    .isEqualTo(config.backupTransactionJob().getName());
        }
    }

    // ----------------------------------------------------------------------------------------
    // The archive
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The archive: one object of exact record images, into a bucket it never creates")
    final class TheArchive {

        @Test
        @DisplayName("reads the master in ascending business-key order, which is what a sequential "
                + "read of the legacy cluster delivered")
        void readsInAscendingBusinessKeyOrder() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of());

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            final ArgumentCaptor<Sort> order = ArgumentCaptor.forClass(Sort.class);
            verify(transactionRepository).findAll(order.capture());
            assertThat(order.getValue()).isEqualTo(Sort.by(Sort.Direction.ASC, "tranId"));
        }

        @Test
        @DisplayName("writes every record as exactly its encoded image with no separator byte")
        void writesEveryRecordAtItsExactEncodedWidth() throws Exception {
            final List<Transaction> master = master();
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(master);

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            final byte[] content = capturedBody();
            final int stride = BackupTransactionJobConfig.ARCHIVE_RECORD_STRIDE;
            assertThat(content)
                    .as("a stride is exactly one fixed-unblocked record image")
                    .hasSize(master.size() * stride);
            for (int index = 0; index < master.size(); index++) {
                final int from = index * stride;
                final byte[] image = new byte[BackupTransactionJobConfig.ARCHIVE_RECORD_LENGTH];
                System.arraycopy(content, from, image, 0, image.length);
                assertThat(image)
                        .as("record %d is the mapper's image, byte for byte", index)
                        .isEqualTo(TransactionRecordMapper.toRecordBytes(master.get(index)));
            }
        }

        @Test
        @DisplayName("★ the external bytes of the stored object are exactly the committed golden "
                + "fixture, which is what makes the archive's byte contract enforced rather than "
                + "incidental")
        void theStoredObjectMatchesTheCommittedGoldenFixture() throws Exception {
            final byte[] golden = goldenArchiveFixture();
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(master());

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            assertThat(capturedBody())
                    .as("the stored object is byte-for-byte the committed fixture: %d records of %d "
                            + "bytes at a %d-byte stride, and nothing else",
                            MASTER_SIZE, BackupTransactionJobConfig.ARCHIVE_RECORD_LENGTH,
                            BackupTransactionJobConfig.ARCHIVE_RECORD_STRIDE)
                    .isEqualTo(golden);
        }

        @Test
        @DisplayName("the committed golden fixture is itself a whole number of fixed records, so the "
                + "expected side of the comparison above cannot silently drift")
        void theGoldenFixtureIsAWholeNumberOfFramedRecords() throws Exception {
            final byte[] golden = goldenArchiveFixture();

            assertThat(golden.length % BackupTransactionJobConfig.ARCHIVE_RECORD_STRIDE)
                    .as("the fixture is a whole number of %d-byte records",
                            BackupTransactionJobConfig.ARCHIVE_RECORD_STRIDE)
                    .isZero();
            assertThat(golden.length / BackupTransactionJobConfig.ARCHIVE_RECORD_STRIDE)
                    .as("the fixture carries exactly the master this suite archives")
                    .isEqualTo(MASTER_SIZE);
            assertThat(new String(golden, StandardCharsets.US_ASCII))
                    .as("fixed-unblocked records carry neither line-feed nor carriage-return framing")
                    .doesNotContain("\n")
                    .doesNotContain("\r");
        }

        @Test
        @DisplayName("the stored object ends on a record boundary, so a consumer never meets a partial "
                + "record at the end of the archive")
        void theStoredObjectEndsOnARecordBoundary() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(master());

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            final byte[] content = capturedBody();
            assertThat(content.length % BackupTransactionJobConfig.ARCHIVE_RECORD_STRIDE)
                    .as("an object that is not a whole number of fixed records is unreadable")
                    .isZero();
            assertThat(new String(content, StandardCharsets.US_ASCII).chars()
                    .filter(character -> character == '\n' || character == '\r')
                    .count())
                    .as("there is no transport framing between or after fixed-unblocked records")
                    .isZero();
        }

        @Test
        @DisplayName("the published byte contract agrees with the mapping layer and with itself, so a "
                + "reader of the archive can rely on the fixed record stride")
        void thePublishedByteContractIsInternallyConsistent() {
            assertThat(BackupTransactionJobConfig.ARCHIVE_RECORD_LENGTH)
                    .as("the record length is the mapping layer's, restated nowhere")
                    .isEqualTo(TransactionRecordMapper.RECORD_LENGTH)
                    .as("the legacy declaration is LRECL=350 at app/jcl/TRANBKP.jcl:35")
                    .isEqualTo(350);
            assertThat(BackupTransactionJobConfig.ARCHIVE_RECORD_STRIDE)
                    .as("fixed-unblocked data advances by exactly one record length")
                    .isEqualTo(BackupTransactionJobConfig.ARCHIVE_RECORD_LENGTH);
        }

        @Test
        @DisplayName("writes the records in the order they were read, so the archive is key ordered")
        void writesTheRecordsInReadOrder() throws Exception {
            final List<Transaction> master = master();
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(master);

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            final String content = new String(capturedBody(), StandardCharsets.US_ASCII);
            final List<Integer> positions = new ArrayList<>();
            for (final Transaction record : master) {
                positions.add(content.indexOf(record.getTranId()));
            }
            assertThat(positions).doesNotContain(-1).isSorted();
        }

        @Test
        @DisplayName("stores exactly one object, into the configured bucket")
        void storesExactlyOneObjectIntoTheConfiguredBucket() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(master());

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            verify(objectStore, times(1)).upload(eq(BUCKET), anyString(), any(InputStream.class));
            // Twice, and both are reads of the same prefix: once to allocate the next generation number
            // against what the base already holds, and once to measure depth after the upload (DL-210).
            verify(objectStore, times(2)).listObjects(BUCKET,
                    BackupTransactionJobConfig.ARCHIVE_OBJECT_KEY_PREFIX);
            verifyNoMoreInteractions(objectStore);
        }

        @Test
        @DisplayName("never creates, probes or deletes a bucket, because the platform provisions it")
        void neverCreatesOrProbesABucket() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(master());

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            verify(objectStore, never()).createBucket(anyString());
            verify(objectStore, never()).bucketExists(anyString());
            verify(objectStore, never()).deleteBucket(anyString());
            verify(objectStore, never()).deleteObject(anyString(), anyString());
        }

        @Test
        @DisplayName("clears nothing: the archive step touches the master for reading only")
        void clearsNothing() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(master());

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            verify(transactionRepository, never()).deleteAllInBatch();
            verify(transactionRepository, never()).deleteAll();
        }

        @Test
        @DisplayName("still stores an object when the master is empty, so an empty generation exists "
                + "rather than being skipped")
        void stillStoresAnObjectWhenTheMasterIsEmpty() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of());

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            verify(objectStore, times(1)).upload(eq(BUCKET), anyString(), any(InputStream.class));
            assertThat(capturedBody()).isEmpty();
        }
    }

    // ----------------------------------------------------------------------------------------
    // The object name
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The object name: the generation base and the unwrapped execution identifier")
    final class TheObjectName {

        @Test
        @DisplayName("begins with the legacy generation base, so an operator finds the archive under "
                + "the name the job stream wrote it under")
        void beginsWithTheLegacyGenerationBase() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of());

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            assertThat(BackupTransactionJobConfig.ARCHIVE_OBJECT_KEY_PREFIX)
                    .isEqualTo("AWS.M2.CARDDEMO.TRANSACT.BKUP/");
            assertThat(capturedKey())
                    .startsWith(BackupTransactionJobConfig.ARCHIVE_OBJECT_KEY_PREFIX);
        }

        @Test
        @DisplayName("is the same base the transaction-report job publishes to, spelled once and pruned "
                + "to one depth")
        void theSharedBackupBaseIsOneNameAndOneDepth() {
            // Two jobs publish generations of this base: this job's archive step and the
            // transaction-report job's unload step. The base is the unit retention counts, so two
            // spellings of it would be two retention groups that only looked like one, and two depths
            // would make the retained set depend on which job happened to run last. The report job now
            // reads its default FROM this constant, so the name cannot drift; the depths are two
            // independent measurements of the same LIMIT(5) declaration and are asserted to agree.
            assertThat(TransactionReportJobConfig.TRANSACTION_BACKUP_GENERATION_LIMIT)
                    .as("both publishers of %s must prune it to the same depth",
                            BackupTransactionJobConfig.ARCHIVE_DATASET_BASE)
                    .isEqualTo(StagedGenerationStore.STANDARD_RETENTION_LIMIT);
        }

        @Test
        @DisplayName("renders the framework execution identifier at a ten-digit minimum width")
        void rendersTheExecutionIdentifierWithoutModulo() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of());

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            assertThat(capturedKey())
                    .isEqualTo(BackupTransactionJobConfig.ARCHIVE_OBJECT_KEY_PREFIX
                            + "G0000000001V00");
        }

        @Test
        @DisplayName("uses only key-safe characters without a second escaping convention")
        void needsNoSecondRendering() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of());

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            assertThat(capturedKey())
                    .as("the whole name likewise carries nothing an object name would have to escape")
                    .matches("[A-Za-z0-9./]+");
        }

        @Test
        @DisplayName("advances the generation for every publication, so an archive can never replace "
                + "its predecessor - including when the execution identifiers restart")
        void advancesTheGenerationForEveryPublication() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of());

            // Two runs, and then a THIRD carrying an execution identifier lower than both. That third is
            // what a re-created batch metadata store hands out while the bucket keeps every object, and
            // under a key derived from the execution identifier it silently replaced the first run's
            // archive - observed as a hundred-thousand-byte object becoming a zero-byte latest version.
            runStepForExecution(BackupTransactionJobConfig.ARCHIVE_STEP_NAME, 41L);
            runStepForExecution(BackupTransactionJobConfig.ARCHIVE_STEP_NAME, 42L);
            runStepForExecution(BackupTransactionJobConfig.ARCHIVE_STEP_NAME, 1L);

            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(objectStore, times(3))
                    .upload(eq(BUCKET), key.capture(), any(InputStream.class));
            assertThat(key.getAllValues())
                    .as("an archive that silently replaces its predecessor is a lost archive")
                    .doesNotHaveDuplicates()
                    .containsExactly(
                            BackupTransactionJobConfig.ARCHIVE_OBJECT_KEY_PREFIX + "G0000000001V00",
                            BackupTransactionJobConfig.ARCHIVE_OBJECT_KEY_PREFIX + "G0000000002V00",
                            BackupTransactionJobConfig.ARCHIVE_OBJECT_KEY_PREFIX + "G0000000003V00");
        }
    }

    // ----------------------------------------------------------------------------------------
    // The clear
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The clear: idempotent, through the repository, and never a schema change")
    final class TheClear {

        @Test
        @DisplayName("clears a populated master and completes")
        void clearsAPopulatedMaster() throws Exception {
            when(transactionRepository.count()).thenReturn(300L);

            final StepExecution execution = runStep(BackupTransactionJobConfig.RESET_STEP_NAME);

            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
            verify(transactionRepository, times(1)).deleteAllInBatch();
        }

        @Test
        @DisplayName("clears an already-empty master without failing, which is what the legacy "
                + "condition-code resets existed to achieve")
        void clearsAnAlreadyEmptyMasterWithoutFailing() throws Exception {
            when(transactionRepository.count()).thenReturn(0L);

            final StepExecution execution = runStep(BackupTransactionJobConfig.RESET_STEP_NAME);

            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
            assertThat(execution.getFailureExceptions()).isEmpty();
            verify(transactionRepository, times(1)).deleteAllInBatch();
        }

        @Test
        @DisplayName("is repeatable, so a re-run after a partial failure clears again and still "
                + "succeeds")
        void isRepeatable() {
            when(transactionRepository.count()).thenReturn(0L);

            assertThatNoException().isThrownBy(() -> {
                runStepForExecution(BackupTransactionJobConfig.RESET_STEP_NAME, 7L);
                runStepForExecution(BackupTransactionJobConfig.RESET_STEP_NAME, 8L);
            });
            verify(transactionRepository, times(2)).deleteAllInBatch();
        }

        @Test
        @DisplayName("writes no object, because archiving is the other step's work")
        void writesNoObject() throws Exception {
            when(transactionRepository.count()).thenReturn(1L);

            runStep(BackupTransactionJobConfig.RESET_STEP_NAME);

            verifyNoMoreInteractions(objectStore);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Failure handling
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("A failure to store the archive abends rather than completing quietly")
    final class AFailureAbends {

        @Test
        @DisplayName("a refused store surfaces as an abend, so the cycle stops instead of clearing a "
                + "master that was never archived")
        void aRefusedStoreSurfacesAsAnAbend() {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(master());
            doThrow(new UncheckedIOException(new IOException("the store refused the object")))
                    .when(objectStore)
                    .upload(anyString(), anyString(), any(InputStream.class));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME))
                    .satisfies(abend -> assertThat(abend.culprit())
                            .as("the culprit is the legacy job member this configuration translates")
                            .isEqualTo("TRANBKP"));
        }

        @Test
        @DisplayName("a refused read of the master surfaces as an abend too")
        void aRefusedReadSurfacesAsAnAbend() {
            when(transactionRepository.findAll(any(Sort.class)))
                    .thenThrow(new IllegalStateException("the master could not be read"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME));
            verify(objectStore, never()).upload(anyString(), anyString(), any(InputStream.class));
        }
    }

    // ----------------------------------------------------------------------------------------
    // Observability
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Both steps are timed, and the timer states no target of its own")
    final class BothStepsAreTimed {

        @Test
        @DisplayName("the archive step records a completed timing under its own step tag")
        void theArchiveStepRecordsACompletedTiming() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of());

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            assertThat(timerCount(BackupTransactionJobConfig.ARCHIVE_STEP_NAME, "COMPLETED"))
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the clear step records a completed timing under its own step tag")
        void theClearStepRecordsACompletedTiming() throws Exception {
            when(transactionRepository.count()).thenReturn(0L);

            runStep(BackupTransactionJobConfig.RESET_STEP_NAME);

            assertThat(timerCount(BackupTransactionJobConfig.RESET_STEP_NAME, "COMPLETED"))
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("a failing step is recorded as failed, so a failure is not averaged into the "
                + "successes")
        void aFailingStepIsRecordedAsFailed() {
            when(transactionRepository.findAll(any(Sort.class)))
                    .thenThrow(new IllegalStateException("the master could not be read"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME));

            assertThat(timerCount(BackupTransactionJobConfig.ARCHIVE_STEP_NAME, "FAILED"))
                    .isEqualTo(1L);
            assertThat(timerCount(BackupTransactionJobConfig.ARCHIVE_STEP_NAME, "COMPLETED"))
                    .isZero();
        }

        private long timerCount(final String stepName, final String outcome) {
            final Timer timer = meterRegistry.find(STEP_TIMER)
                    .tag("job", BackupTransactionJobConfig.JOB_NAME)
                    .tag("step", stepName)
                    .tag("outcome", outcome)
                    .timer();
            return timer == null ? 0L : timer.count();
        }
    }

    // ----------------------------------------------------------------------------------------
    // The postures that are textual by nature
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The configuration keeps the postures the migration requires of it")
    final class TheConfigurationKeepsItsPostures {

        @Test
        @DisplayName("nothing in it fires when the context starts")
        void nothingFiresWhenTheContextStarts() throws IOException {
            final String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

            assertThat(source)
                    .doesNotContain("CommandLineRunner")
                    .doesNotContain("ApplicationRunner")
                    .doesNotContain("@PostConstruct")
                    .doesNotContain("SmartLifecycle")
                    .doesNotContain("@EnableScheduling")
                    .doesNotContain("spring.batch.job.name");
        }

        @Test
        @DisplayName("it does not enable batch processing, which would switch off the "
                + "auto-configuration the module depends on")
        void itDoesNotEnableBatchProcessing() throws IOException {
            assertThat(Files.readString(SOURCE, StandardCharsets.UTF_8))
                    .doesNotContain("@EnableBatchProcessing");
        }

        @Test
        @DisplayName("it does not register the settings type a second time, which would clash at "
                + "start-up")
        void itDoesNotRegisterTheSettingsTypeASecondTime() throws IOException {
            assertThat(Files.readString(SOURCE, StandardCharsets.UTF_8))
                    .doesNotContain("@EnableConfigurationProperties");
        }

        @Test
        @DisplayName("the stride invariant is enforced before the object is stored, not after, so a "
                + "partial-record archive can never reach the bucket")
        void theStrideInvariantIsEnforcedBeforeTheObjectIsStored() throws IOException {
            final String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

            assertThat(source)
                    .as("the guard exists and names the stride it enforces")
                    .contains("requireWholeRecordStride")
                    .contains("% ARCHIVE_RECORD_STRIDE != 0");
            assertThat(source.indexOf("requireWholeRecordStride(this.archivedBytes)"))
                    .as("the guard is called before the upload on the close path, so an object that is "
                            + "not a whole number of records is never handed to the store")
                    .isGreaterThan(0)
                    .isLessThan(source.indexOf(
                            "this.generationStore.publishFile(ARCHIVE_DATASET_BASE"));
        }

        @Test
        @DisplayName("record production appends no transport separator to the mapper image")
        void recordProductionAddsNoTransportFraming() throws IOException {
            final String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

            assertThat(source)
                    .contains("this.generation.write(image);")
                    .doesNotContain("ARCHIVE_RECORD_SEPARATOR")
                    .doesNotContain("frameRecord()");
        }

        @Test
        @DisplayName("it names no bucket and no region, so the destination is configuration and not "
                + "source")
        void itNamesNoBucketAndNoRegion() throws IOException {
            final String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

            assertThat(source).doesNotContain("carddemo-batch-staging");
            assertThat(source).doesNotContain("us-east-1");
        }

        @Test
        @DisplayName("a blanked destination stops the archive rather than being defaulted, because a "
                + "defaulted destination is a request against no bucket")
        void aBlankedDestinationStopsTheArchive() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> configWithBucket("   "))
                    .withMessageContaining(AwsProperties.S3.BATCH_STAGING_BUCKET_PROPERTY);
            verify(objectStore, never()).upload(anyString(), anyString(), any(InputStream.class));
        }

        @Test
        @DisplayName("it issues no schema statement, because the migrations own the schema alone")
        void itIssuesNoSchemaStatement() throws IOException {
            final String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

            assertThat(source)
                    .doesNotContain("CREATE TABLE")
                    .doesNotContain("DROP TABLE")
                    .doesNotContain("ALTER TABLE")
                    .doesNotContain("CREATE INDEX")
                    .doesNotContain("createNativeQuery");
        }

        @Test
        @DisplayName("it spawns no process, even though two of the three legacy steps invoked "
                + "external utilities")
        void itSpawnsNoProcess() throws IOException {
            final String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

            assertThat(source)
                    .doesNotContain("Runtime.getRuntime")
                    .doesNotContain("ProcessBuilder");
        }

        @Test
        @DisplayName("it runs strictly sequentially and uses no deprecated builder factory")
        void itRunsStrictlySequentiallyOnCurrentBuilders() throws IOException {
            final String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

            assertThat(source)
                    .doesNotContain("TaskExecutor")
                    .doesNotContain("JobBuilderFactory")
                    .doesNotContain("StepBuilderFactory");
        }

        @Test
        @DisplayName("it scales no decimal of its own, because the codec is the only place that may")
        void itScalesNoDecimalOfItsOwn() throws IOException {
            assertThat(Files.readString(SOURCE, StandardCharsets.UTF_8))
                    .doesNotContain("setScale")
                    .doesNotContain("HALF_EVEN")
                    .doesNotContain("HALF_UP");
        }

        @Test
        @DisplayName("it composes the archive in a file and not in memory, so the cost of an archive is "
                + "bounded by the volume it is written to and not by the heap")
        void itComposesTheArchiveInAFileAndNotInMemory() throws IOException {
            final String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

            assertThat(source)
                    .as("no in-heap accumulator of the whole generation")
                    .doesNotContain("ByteArrayOutputStream")
                    .doesNotContain("toByteArray()");
            assertThat(source)
                    .as("the generation is a stream over a working file that is sealed before it is "
                            + "published")
                    .contains("StagedGenerationStore.workingPath(this.completedGeneration)")
                    .contains("StagedGenerationStore.completeWorkingFile(this.workingGeneration,");
        }
    }

    // ----------------------------------------------------------------------------------------
    // Fixtures and drivers
    // ----------------------------------------------------------------------------------------

    /**
     * Runs one step of the job for a default execution identifier.
     *
     * @param stepName the step to run
     * @return the completed step execution
     * @throws Exception if the step raised
     */
    private StepExecution runStep(final String stepName) throws Exception {
        return runStepForExecution(stepName, 1L);
    }

    /**
     * Runs one step of the job as part of a nominated job execution.
     *
     * <p>The step is executed through the framework's own contract rather than by reaching inside the
     * configuration, so what is asserted afterwards is what a launched job would have done. A failure
     * the framework recorded on the execution is rethrown, because a step that failed must not be
     * mistaken here for one that completed.
     *
     * @param stepName the step to run
     * @param jobExecutionId the execution identifier the archive names its generation with
     * @return the completed step execution
     * @throws Exception if the step raised
     */
    private StepExecution runStepForExecution(final String stepName, final long jobExecutionId)
            throws Exception {
        final Job job = this.config.backupTransactionJob();
        final Step step = ((StepLocator) job).getStep(stepName);
        final JobExecution jobExecution = new JobExecution(
                new JobInstance(jobExecutionId, BackupTransactionJobConfig.JOB_NAME),
                jobExecutionId, new JobParameters());
        final StepExecution stepExecution = jobExecution.createStepExecution(stepName);

        step.execute(stepExecution);

        final List<Throwable> failures = new ArrayList<>(stepExecution.getFailureExceptions());
        if (failures.isEmpty()) {
            return stepExecution;
        }
        final Throwable first = failures.get(0);
        if (first instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw new IllegalStateException("step " + stepName + " failed", first);
    }

    /** @return the body of the single stored object, as it was drained during the upload */
    private byte[] capturedBody() {
        verify(this.objectStore).upload(eq(BUCKET), anyString(), any(InputStream.class));
        assertThat(this.uploadedBodies)
                .as("exactly one body must have been drained during the upload")
                .hasSize(1);
        return this.uploadedBodies.get(0);
    }

    /**
     * Reads the committed golden archive, which is the <strong>expected</strong> side of the byte
     * comparison and is never produced by the code under test.
     *
     * <p>The fixture was authored from the published record layout and the overpunched-sign convention,
     * not snapshotted from a run, so comparing against it proves the placement of every field, the
     * absence of framing and the stride between records rather than merely proving that the step is
     * self-consistent.
     *
     * @return the fixture's bytes, exactly as committed
     * @throws IOException if the fixture cannot be read, which is a broken build rather than a
     *                     behavioural failure
     */
    private static byte[] goldenArchiveFixture() throws IOException {
        try (InputStream fixture = BackupTransactionJobConfigTest.class
                .getResourceAsStream(GOLDEN_ARCHIVE_FIXTURE)) {
            assertThat(fixture)
                    .as("the committed golden archive %s must be on the test classpath",
                            GOLDEN_ARCHIVE_FIXTURE)
                    .isNotNull();
            return Base64.getMimeDecoder().decode(fixture.readAllBytes());
        }
    }

    /** @return the name of the single stored object */
    private String capturedKey() {
        final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(this.objectStore).upload(eq(BUCKET), key.capture(), any(InputStream.class));
        return key.getValue();
    }

    /** @return a small master whose identifiers ascend, as a keyed read would deliver them */
    private static List<Transaction> master() {
        final List<Transaction> records = new ArrayList<>(MASTER_SIZE);
        for (int ordinal = 1; ordinal <= MASTER_SIZE; ordinal++) {
            // Locale.ROOT is mandatory, not decorative. The identifier is the sixteen-character key of a
            // 350-byte record image, and an unqualified format emits the default locale's digits - under a
            // locale whose numbering system is not Latin those are not US-ASCII digits, they encode to two
            // bytes each, and the archive record the step writes is no longer 350 bytes wide.
            records.add(transaction(String.format(Locale.ROOT, "%016d", ordinal)));
        }
        return List.copyOf(records);
    }

    /** @param identifier the sixteen-character transaction identifier
     *  @return one record whose every mapped field is within its declared width */
    private static Transaction transaction(final String identifier) {
        return new Transaction(identifier, "01", "0005", "System",
                "Archive fixture record", new BigDecimal("123.45"), "000000123",
                "Merchant Name", "Merchant City", "0000012345", "4111111111111111",
                "2026-08-04-07.48.12.340000", "2026-08-04-07.48.12.340000");
    }
}
