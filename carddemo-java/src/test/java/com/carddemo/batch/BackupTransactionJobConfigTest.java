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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig.ConditionCodeGate;
import com.carddemo.domain.Transaction;
import com.carddemo.exception.AbendException;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.TransactionRecordMapper;
import io.awspring.cloud.s3.S3Operations;
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
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobInterruptedException;
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
 * job translates is the estate's <em>only</em> non-strict one: it admits a prior return code of up to
 * and including four, because the posting job that runs earlier in the same cycle reports four
 * whenever it rejected a record - a partial success rather than a failure. Substituting the strict
 * zero-only ceiling used by the statement job would compile, would read like hardening, and would
 * abort the archive-and-reset cycle on a tolerated warning. That substitution is the single most
 * plausible defect in this configuration, so this suite pins the selection both behaviourally,
 * through the ceiling the gate admits, and textually, so that a future edit to the constant name
 * cannot pass unnoticed.
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
@DisplayName("BackupTransactionJobConfig - the tolerant ceiling, the 350-byte archive and the clear")
final class BackupTransactionJobConfigTest {

    /** A destination deliberately unlike the shipped default, so a hardcoded name cannot pass. */
    private static final String BUCKET = "unit-test-staging-bucket";

    /** A region deliberately unlike the shipped default, for the same reason. */
    private static final String REGION = "eu-west-2";

    /** The number of records the archive assertions drive through the step. */
    private static final int MASTER_SIZE = 3;

    /** The width of the batch timestamp the object name carries. */
    private static final int BATCH_TIMESTAMP_WIDTH = 26;

    /** The batch timestamp form: hyphens at the date breaks and before the hour, dots in the time. */
    private static final String BATCH_TIMESTAMP_PATTERN =
            "\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000";

    /** The configuration's own source, read for the postures that are textual by nature. */
    private static final Path SOURCE = Path.of("src", "main", "java", "com", "carddemo", "batch",
            "BackupTransactionJobConfig.java");

    /** The timer name both steps are recorded under. */
    private static final String STEP_TIMER = "carddemo.batch.job.step";

    private TransactionRepository transactionRepository;

    private S3Operations objectStore;

    private MeterRegistry meterRegistry;

    private BackupTransactionJobConfig config;

    @BeforeEach
    void buildConfigurationOverMockedCollaborators() {
        this.transactionRepository = mock(TransactionRepository.class);
        this.objectStore = mock(S3Operations.class);
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
                boundaryListener, incrementer, this.transactionRepository, this.objectStore,
                awsProperties, this.meterRegistry, fixed);
    }

    // ----------------------------------------------------------------------------------------
    // The condition-code ceiling
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The gate admits a warning, which is the whole point of it")
    final class TheGateAdmitsAWarning {

        @Test
        @DisplayName("the ceiling this job selects is four, inclusive, so a prior warning still runs "
                + "the clear")
        void theCeilingIsFourInclusive() {
            assertThat(ConditionCodeGate.WARNINGS_TOLERATED.highestToleratedReturnCode())
                    .as("the legacy gate bypasses its step only when four is below the return code")
                    .isEqualTo(4);
            assertThat(ConditionCodeGate.WARNINGS_TOLERATED.permits(0)).isTrue();
            assertThat(ConditionCodeGate.WARNINGS_TOLERATED.permits(4))
                    .as("four is the code the posting job reports for a partial success")
                    .isTrue();
        }

        @Test
        @DisplayName("anything above four is refused, so a real failure does not reach the clear")
        void anythingAboveFourIsRefused() {
            assertThat(ConditionCodeGate.WARNINGS_TOLERATED.permits(5)).isFalse();
            assertThat(ConditionCodeGate.WARNINGS_TOLERATED.permits(8)).isFalse();
            assertThat(ConditionCodeGate.WARNINGS_TOLERATED.permits(12)).isFalse();
        }

        @Test
        @DisplayName("the strict zero-only ceiling is a different policy and is not the one selected")
        void theStrictCeilingIsADifferentPolicy() {
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.highestToleratedReturnCode())
                    .isZero();
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.permits(4))
                    .as("conflating the two ceilings is the defect this assertion exists to catch")
                    .isFalse();
        }

        @Test
        @DisplayName("the configuration names the tolerant ceiling and never the strict one")
        void theConfigurationNamesTheTolerantCeiling() throws IOException {
            final String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

            assertThat(source).contains("ConditionCodeGate.WARNINGS_TOLERATED");
            assertThat(source)
                    .as("the statement job's ceiling must not appear in the backup job")
                    .doesNotContain("ALL_PRIOR_STEPS_ZERO");
            assertThat(source)
                    .as("both verdicts must be routed, so a refusal is distinguished from a failure")
                    .contains("ConditionCodeGate.PERMITTED")
                    .contains("ConditionCodeGate.REFUSED");
            assertThat(source)
                    .as("routing on the framework's failure status alone would reject the tolerated"
                            + " code as well")
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
        @DisplayName("writes every record as exactly its encoded image, newline separated")
        void writesEveryRecordAtItsExactEncodedWidth() throws Exception {
            final List<Transaction> master = master();
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(master);

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            final byte[] content = capturedBody();
            final int stride = TransactionRecordMapper.RECORD_LENGTH + 1;
            assertThat(content)
                    .as("a stride is one record image plus one separator")
                    .hasSize(master.size() * stride);
            for (int index = 0; index < master.size(); index++) {
                final int from = index * stride;
                final byte[] image = new byte[TransactionRecordMapper.RECORD_LENGTH];
                System.arraycopy(content, from, image, 0, image.length);
                assertThat(image)
                        .as("record %d is the mapper's image, byte for byte", index)
                        .isEqualTo(TransactionRecordMapper.toRecordBytes(master.get(index)));
                assertThat(content[from + TransactionRecordMapper.RECORD_LENGTH])
                        .as("record %d is followed by the separator and nothing else", index)
                        .isEqualTo((byte) '\n');
            }
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
    @DisplayName("The object name: the generation base, the batch timestamp, the generation number")
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
        @DisplayName("carries the batch timestamp form and never the online one")
        void carriesTheBatchTimestampFormAndNeverTheOnlineOne() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of());

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            final String stamp = capturedStamp();
            assertThat(stamp).hasSize(BATCH_TIMESTAMP_WIDTH).matches(BATCH_TIMESTAMP_PATTERN);
            assertThat(stamp.charAt(10))
                    .as("the batch form separates the date from the hour with a hyphen; the online "
                            + "form uses a space")
                    .isEqualTo('-');
            assertThat(stamp.charAt(13)).isEqualTo('.');
            assertThat(stamp.charAt(16)).isEqualTo('.');
            assertThat(stamp.charAt(19)).isEqualTo('.');
            assertThat(stamp)
                    .as("no colon and no space, which is what the online form would contribute")
                    .doesNotContain(":")
                    .doesNotContain(" ");
        }

        @Test
        @DisplayName("needs no second rendering, because every character of the form is admissible "
                + "in an object name unchanged")
        void needsNoSecondRendering() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of());

            runStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            assertThat(capturedStamp())
                    .as("digits, hyphens and dots only - so no key-safe variant of the timestamp has "
                            + "to be derived and no second timestamp format may be invented")
                    .matches("[0-9.\\-]+");
            assertThat(capturedKey())
                    .as("the whole name likewise carries nothing an object name would have to escape")
                    .matches("[A-Za-z0-9./\\-]+");
        }

        @Test
        @DisplayName("is distinct per execution even when the clock is not, because the generation "
                + "number completes it")
        void isDistinctPerExecutionEvenUnderAFixedClock() throws Exception {
            when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of());

            runStepForExecution(BackupTransactionJobConfig.ARCHIVE_STEP_NAME, 41L);
            runStepForExecution(BackupTransactionJobConfig.ARCHIVE_STEP_NAME, 42L);

            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(objectStore, times(2))
                    .upload(eq(BUCKET), key.capture(), any(InputStream.class));
            assertThat(key.getAllValues())
                    .as("an archive that silently replaces its predecessor is a lost archive")
                    .doesNotHaveDuplicates();
            assertThat(key.getAllValues().get(0))
                    .endsWith(BackupTransactionJobConfig.ARCHIVE_GENERATION_INFIX + "41");
            assertThat(key.getAllValues().get(1))
                    .endsWith(BackupTransactionJobConfig.ARCHIVE_GENERATION_INFIX + "42");
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
        void aBlankedDestinationStopsTheArchive() throws JobInterruptedException {
            final BackupTransactionJobConfig blanked = configWithBucket("   ");
            final Step archive = ((StepLocator) blanked.backupTransactionJob())
                    .getStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);
            final JobExecution jobExecution = new JobExecution(
                    new JobInstance(1L, BackupTransactionJobConfig.JOB_NAME), 1L,
                    new JobParameters());
            final StepExecution stepExecution = jobExecution
                    .createStepExecution(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);

            archive.execute(stepExecution);

            assertThat(stepExecution.getFailureExceptions())
                    .hasAtLeastOneElementOfType(IllegalArgumentException.class);
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

    /** @return the body of the single stored object */
    private byte[] capturedBody() throws IOException {
        final ArgumentCaptor<InputStream> body = ArgumentCaptor.forClass(InputStream.class);
        verify(this.objectStore).upload(eq(BUCKET), anyString(), body.capture());
        return body.getValue().readAllBytes();
    }

    /** @return the name of the single stored object */
    private String capturedKey() {
        final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(this.objectStore).upload(eq(BUCKET), key.capture(), any(InputStream.class));
        return key.getValue();
    }

    /** @return the batch timestamp portion of the single stored object's name */
    private String capturedStamp() {
        final String suffix = capturedKey()
                .substring(BackupTransactionJobConfig.ARCHIVE_OBJECT_KEY_PREFIX.length());
        return suffix.substring(0, BATCH_TIMESTAMP_WIDTH);
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
