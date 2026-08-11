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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.batch.step.AdvisoryGenerationPublicationLock;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.domain.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.DateValidationService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.util.TransactionRecordMapper;

import io.awspring.cloud.s3.S3Operations;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration specification for {@link CombineTransactionsJobConfig}, run against a real
 * PostgreSQL 16 server with the module's own migrations already applied.
 *
 * <h2>Why this test exists alongside the unit specification</h2>
 *
 * <p>The unit specification proves the ordering, the equal-key retention and the shape of the job by
 * calling the configuration's factory methods directly. Three properties of this job cannot be reached
 * that way, and they are exactly the ones that break silently:
 *
 * <ul>
 *   <li>the ordering reader and the combined generation are <strong>scoped beans</strong>, so they are
 *       injected into their singleton steps through proxies. A proxy that failed to expose the stream
 *       contract would leave the reader unopened, and a proxy resolved from the wrong scope would give
 *       the load step an empty generation. Only a genuine execution resolves either;</li>
 *   <li>the job is launched <strong>by name</strong> through the registry the framework populates, so
 *       registration under {@link CombineTransactionsJobConfig#JOB_NAME} is part of the contract;</li>
 *   <li><strong>nothing may fire when the context starts.</strong> A legacy batch job was submitted
 *       deliberately, so this context must contribute no execution before the explicit launch below.
 *       The shared metadata store may already contain executions from an earlier specification, which
 *       makes an absolute-empty assertion an ordering dependency rather than an inertness assertion.</li>
 * </ul>
 *
 * <p>The run is also the load step's own proof that it writes through the repository: three rows appear
 * in the transaction master, and no process, command or external utility is involved in putting them
 * there - which is the property the legacy copy-utility step makes most tempting to get wrong.
 *
 * <h2>How the context is scoped</h2>
 *
 * <p>{@link JobContext} boots the batch, persistence and migration infrastructure this job needs and
 * nothing else. The full application entry point is deliberately not used: this job touches no
 * endpoint, no queue and no object store, so booting those would make the test depend on emulators it
 * has no assertion about. Schema evolution is switched off for the same reason - the shared server this
 * class inherits has already been migrated, so migrating again would be a second opinion about the
 * schema rather than a test of this job.
 *
 * <h2>Fixtures</h2>
 *
 * <p>The two inputs are written as sequential datasets of fixed-width record images, one record per
 * line, exactly as the legacy generations were. Every record's card number is one the reference seed
 * already carries, because the master requires an existing card, and every identifier is drawn from a
 * reserved range of sixteen-digit values so that the shared server's seeded data is neither read nor
 * disturbed.
 *
 * <p><strong>The rows this test loads are removed again.</strong> The server is shared by every
 * integration test in the run, the reference seed inserts no transaction at all, and a neighbouring
 * specification asserts both that emptiness and that no row follows its own highest reserved
 * identifier. A loaded row left behind would fail that specification rather than this one - a failure
 * whose cause is a test that already passed - so the reserved range is restored after the run whatever
 * its outcome.
 *
 * <p>No legacy source text is transcribed here.
 */
@SpringBootTest(classes = CombineTransactionsJobIT.JobContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false"})
@ActiveProfiles("test")
@DisplayName("CombineTransactionsJobIT - registered by name, inert at start-up, and loads on demand")
class CombineTransactionsJobIT extends AbstractPostgresIT {

    /**
     * The directory the staged-input allow-list resolves a relative location against.
     *
     * <p>Created beneath the platform's temporary location by this test, so a fixture written here is
     * nameable and nothing outside it is. Both fixtures are removed after the test whatever its outcome.
     *
     * <p><strong>The name must be unique per run, and a fixed name is not.</strong> The platform temporary
     * directory is shared by every process on the host, so a fixed child of it is shared by every build on
     * the host - including every concurrently executing run of this same class, whose own clean-up removes
     * both fixtures by name. One such run removing them between this run's context build and its step
     * execution leaves the reader with nothing to open, and the step then fails on a resource this
     * specification did stage: the allow-list falls back to a class-path lookup for a bare name, so the
     * observed refusal names a class-path resource that never existed. Naming the directory for the owning
     * process removes the sharing rather than trying to time around it, which is the same approach the
     * interest job's test takes.
     *
     * <p>Resolved once, in a static initialiser, so the registration below, the fixtures written through it
     * and the clean-up cannot disagree about which directory is being used. A per-test temporary directory
     * could not serve: the locations are bound while the context is built, which is before any test method
     * runs.
     */
    private static final Path STAGING_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"),
            "carddemo-combine-transactions-it-" + ProcessHandle.current().pid());

    /**
     * A card the reference seed already carries.
     *
     * <p>The master constrains every posted transaction to name an existing card, so a fixture that
     * invented a card number would fail on the foreign key rather than on anything this test asserts.
     */
    private static final String SEEDED_CARD = "0500024453765740";

    /** File name of the first concatenated fixture, staged beneath the configured staging directory. */
    private static final String BACKUP_DATASET = "transaction-backup.txt";

    /** File name of the second concatenated fixture. */
    private static final String SYNTHESIZED_DATASET = "synthesized-transactions.txt";

    /** Identifier of the record this test expects to sort first. */
    private static final String FIRST_ID = "9890000000000001";

    /** Identifier of the record this test expects to sort second, and the only synthesized one. */
    private static final String SECOND_ID = "9890000000000003";

    /** Identifier of the record this test expects to sort last. */
    private static final String THIRD_ID = "9890000000000005";

    /** How many records the two inputs carry between them. */
    private static final long RECORD_COUNT = 3L;

    /**
     * The whole of the range this test reserves in the shared master.
     *
     * <p>Deliberately below the range the repository specification reserves, so that even a row that
     * escaped the restore below could not sit above that specification's highest identifier - the
     * position its last-page assertion depends on.
     */
    private static final List<String> RESERVED_IDS = List.of(FIRST_ID, SECOND_ID, THIRD_ID);

    /** The registry the framework populates, and the surface a caller launches this job through. */
    @Autowired
    private JobRegistry jobRegistry;

    /** Used to observe that no instance of this job existed before the test launched one. */
    @Autowired
    private JobExplorer jobExplorer;

    /** Launches the job on demand, which is the only way this job ever starts. */
    @Autowired
    private JobLauncher jobLauncher;

    /** The transaction master, read back to confirm the load actually persisted rows. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** The job under test, injected by the name the configuration publishes. */
    @Autowired
    private Job combineTransactionsJob;

    /**
     * Builds a record whose every field already sits at its contractual width.
     *
     * @param  tranId the sixteen-digit identifier, carried through verbatim
     * @return an entity identical to one parsed from a well-formed record image
     */
    private static Transaction record(final String tranId) {
        return TransactionRecordMapper.fromRecord(TransactionRecordMapper.toRecordBytes(
                new Transaction(tranId, "01", "0005", "POS TERM  ", pad("COMBINED INPUT", 100),
                        new BigDecimal("42.75"), "000123456", pad("ACME MERCHANT", 50),
                        pad("SEATTLE", 50), "98101-0001", SEEDED_CARD,
                        "2022-07-19-23.23.05.000000", " ".repeat(26))));
    }

    /** Left-justifies a value into a field of the given width, space padded as the layout is. */
    private static String pad(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Writes one sequential dataset of fixed-width record images and returns its location.
     *
     * <p>Each image's width is asserted in <strong>encoded bytes</strong> before it is written, never as
     * a character count, because a character count is not a width.
     *
     * @param  directory the configured staging directory to write into, which the allow-list resolves
     *                   a relative name against
     * @param  name      the dataset's file name
     * @param  records   the records to render, in file order
     * @return the location the deployment property carries
     * @throws IOException if the dataset cannot be written
     */
    private static String dataset(final Path directory, final String name,
            final List<Transaction> records) throws IOException {
        Files.createDirectories(directory);
        final StringBuilder images = new StringBuilder();
        for (final Transaction record : records) {
            final byte[] image = TransactionRecordMapper.toRecordBytes(record);
            assertThat(image).as("every record of a legacy generation is a fixed encoded width")
                    .hasSize(TransactionRecordMapper.RECORD_LENGTH);
            images.append(new String(image, StandardCharsets.US_ASCII));
        }
        Files.writeString(directory.resolve(name), images.toString(), StandardCharsets.US_ASCII);
        return name;
    }

    /**
     * Publishes the two staged input locations as configuration, which is where this job binds them.
     *
     * <p><strong>Not job parameters, deliberately.</strong> The legacy member declares both inputs inside
     * itself and the submission named neither, so the migrated job resolves them from the deployment's own
     * configuration and a launch carries no location at all. This method therefore stands in for the
     * environment that provisions the storage: it writes the two generations and names them on the two
     * properties the configuration binds. Registering them any other way - as job parameters on the launch
     * below - would test a seam the production configuration does not have.
     *
     * <p>The datasets are written here rather than in a per-test temporary directory because these values
     * are bound while the context is built, which happens before any test method runs. The directory they
     * are written into is published on the same registry and for the same reason: its name is computed - it
     * carries the owning process identifier, for the reason given on {@link #STAGING_DIRECTORY} - and an
     * annotation attribute cannot compute one, because it has to be a compile-time constant.
     *
     * <p>This publishes keys the shared base class does not, so it competes with nothing: the data-source
     * contract stays that class's alone.
     *
     * @param  registry the registry the framework supplies before the context is refreshed
     */
    @DynamicPropertySource
    static void registerStagedInputs(final DynamicPropertyRegistry registry) {
        registry.add(CombineTransactionsJobConfig.STAGING_DIRECTORY_PROPERTY,
                STAGING_DIRECTORY::toString);
        registry.add(CombineTransactionsJobConfig.BACKUP_RESOURCE_PROPERTY,
                () -> stagedDataset(BACKUP_DATASET,
                        List.of(record(THIRD_ID), record(FIRST_ID))));
        registry.add(CombineTransactionsJobConfig.SYNTHESIZED_RESOURCE_PROPERTY,
                () -> stagedDataset(SYNTHESIZED_DATASET, List.of(record(SECOND_ID))));
    }

    /**
     * Writes one staged dataset into this run's own directory and answers its location.
     *
     * <p>Wraps the checked failure a property supplier may not throw, and reports it as a state failure so
     * a run whose fixtures could not be written fails while the context is being built rather than
     * mid-step.
     *
     * @param  name    the dataset's file name
     * @param  records the records to render, in file order
     * @return the location the configuration property carries
     */
    private static String stagedDataset(final String name, final List<Transaction> records) {
        try {
            return dataset(STAGING_DIRECTORY, name, records);
        } catch (final IOException cannotStage) {
            throw new IllegalStateException("the staged input " + name + " could not be written",
                    cannotStage);
        }
    }

    @Test
    @DisplayName("the job is registered by name, holds exactly two ungated steps, fires nothing at "
            + "start-up, and loads the combined stream into the master when launched")
    void theJobIsRegisteredInertAtStartUpAndLoadsOnDemand() throws Exception {
        assertThat(jobRegistry.getJobNames())
                .as("the job is launched by name, so it must be registered under that name")
                .contains(CombineTransactionsJobConfig.JOB_NAME);

        final long instancesBeforeLaunch =
                jobExplorer.getJobInstanceCount(CombineTransactionsJobConfig.JOB_NAME);
        assertThat(jobExplorer.findRunningJobExecutions(CombineTransactionsJobConfig.JOB_NAME))
                .as("bringing this context up must not start a combine execution")
                .isEmpty();

        assertThat(combineTransactionsJob)
                .as("a plain sequence, not a flow job - a failure-ending transition would have "
                        + "produced the latter, and the measured member carries no gate")
                .isExactlyInstanceOf(SimpleJob.class);
        assertThat(((SimpleJob) combineTransactionsJob).getStepNames()).containsExactly(
                CombineTransactionsJobConfig.ORDER_STEP_NAME,
                CombineTransactionsJobConfig.LOAD_STEP_NAME);

        // No location is supplied here, and that absence is the specification: both inputs were bound
        // from configuration before this context was built, exactly as the legacy member declared them
        // inside itself. A launch that could name an input would be a launch that could name any
        // readable thing.
        final JobExecution execution = jobLauncher.run(combineTransactionsJob,
                new JobParametersBuilder().toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jobExplorer.getJobInstanceCount(CombineTransactionsJobConfig.JOB_NAME))
                .as("the explicit launch contributes exactly one instance")
                .isEqualTo(instancesBeforeLaunch + 1L);
        assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName)
                .as("both steps ran, in the legacy order")
                .containsExactly(CombineTransactionsJobConfig.ORDER_STEP_NAME,
                        CombineTransactionsJobConfig.LOAD_STEP_NAME);
        for (final StepExecution step : execution.getStepExecutions()) {
            assertThat(step.getReadCount())
                    .as("%s read every record of both inputs", step.getStepName())
                    .isEqualTo(RECORD_COUNT);
            assertThat(step.getWriteCount())
                    .as("%s wrote every record it read - nothing was filtered", step.getStepName())
                    .isEqualTo(RECORD_COUNT);
            assertThat(step.getFilterCount())
                    .as("%s filtered nothing, because a filtered record is a lost record",
                            step.getStepName())
                    .isZero();
        }

        for (final String reserved : RESERVED_IDS) {
            assertThat(transactionRepository.findById(reserved))
                    .as("%s was loaded through the repository, not through a utility", reserved)
                    .isPresent();
        }
        // Sealed as a local file and not held as an array: the generation is a sequential file, so its
        // size is bounded by the volume it is written to rather than by the heap. It is named beneath the
        // legacy generation-group base the measured member declares, in the legacy generation form, which
        // is what puts it under one canonical object key and inside the generation retention pass
        // (DL-212).
        final Path combinedGeneration = StagedGenerationStore.generationPath(STAGING_DIRECTORY,
                CombineTransactionsJobConfig.COMBINED_DATASET_BASE, execution.getId());
        assertThat(combinedGeneration)
                .as("the sealed generation survives the step that wrote it, because the job-boundary"
                        + " listener publishes it after the whole submission completed and not the"
                        + " ordering step itself")
                .exists();
        assertThat(Files.size(combinedGeneration))
                .as("and it carries every record of both inputs at the declared record width with"
                        + " NOTHING between two records, because the legacy DD declares this dataset"
                        + " record-format blocked and a consumer frames it by width (DL-213)")
                .isEqualTo(RECORD_COUNT * (long) TransactionRecordMapper.RECORD_LENGTH);
        assertThat(Files.readString(combinedGeneration, StandardCharsets.US_ASCII))
                .as("and no separator survives anywhere in it")
                .doesNotContain("\n");
        Files.deleteIfExists(combinedGeneration);
    }

    /**
     * Restores the reserved range, so the master is left exactly as the reference seed leaves it.
     *
     * <p>Runs whatever the test's outcome, because a partially loaded range is as disruptive to the
     * neighbouring specification as a fully loaded one. Removing an absent identifier is a no-operation,
     * so a run that failed before loading anything restores cleanly too.
     */
    @AfterEach
    void restoreTheReservedRange() {
        RESERVED_IDS.forEach(transactionRepository::deleteById);
    }

    /**
     * Removes the two staged fixtures, so the configured staging directory is left as it was found.
     *
     * <p>Removing an absent fixture is a no-operation, so a run that failed before either was written
     * still leaves cleanly.
     *
     * @throws IOException if a fixture cannot be removed
     */
    @AfterEach
    void removeTheStagedFixtures() throws IOException {
        Files.deleteIfExists(STAGING_DIRECTORY.resolve(BACKUP_DATASET));
        Files.deleteIfExists(STAGING_DIRECTORY.resolve(SYNTHESIZED_DATASET));
    }

    /**
     * Removes this run's own staging directory, so the platform's temporary location is left as it was
     * found.
     *
     * <p>Safe to attempt here and not in the per-test clean-up above: the directory carries this process's
     * identifier, so it belongs to this run alone and removing it cannot disturb a concurrently executing
     * run of the same class - which is precisely what a fixed name could not promise. Only the two entries
     * this specification creates are named, one level deep and never recursively, and a directory still
     * holding anything is left in place for the platform to reclaim rather than emptied blindly.
     *
     * @throws IOException if the directory's own entries cannot be read
     */
    @AfterAll
    static void removeTheStagingDirectory() throws IOException {
        if (!Files.isDirectory(STAGING_DIRECTORY)) {
            return;
        }
        Files.deleteIfExists(STAGING_DIRECTORY.resolve(BACKUP_DATASET));
        Files.deleteIfExists(STAGING_DIRECTORY.resolve(SYNTHESIZED_DATASET));
        try (Stream<Path> remaining = Files.list(STAGING_DIRECTORY)) {
            if (remaining.findAny().isEmpty()) {
                Files.deleteIfExists(STAGING_DIRECTORY);
            }
        }
    }

    /**
     * The narrowest context this job needs: batch orchestration, persistence and the two collaborators
     * the configuration is built against.
     *
     * <p>No enabling annotation for batch appears here or anywhere in the module: under this framework
     * generation the batch auto-configuration backs off when that annotation is present, so adding it
     * would switch off the very infrastructure this context depends on.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({CombineTransactionsJobConfig.class, BatchConfig.class,
            FixedWidthFlatFileReaderFactory.class, JobParameterValidators.class,
            DateValidationService.class, StagedGenerationStore.class,
            AdvisoryGenerationPublicationLock.class})
    @EnableConfigurationProperties(AwsProperties.class)
    @EnableJpaRepositories(basePackageClasses = TransactionRepository.class)
    @EntityScan(basePackageClasses = Transaction.class)
    static class JobContext {

        /**
         * Publishes the shared staging boundary without adding an object-store dependency to this
         * PostgreSQL-focused slice.
         *
         * @return observable staging collaborator
         */
        @Bean
        BatchStagingArea batchStagingArea() {
            return mock(BatchStagingArea.class);
        }

        /**
         * Keeps the durable-publication path real up to the external object-store boundary.
         *
         * <p>The generation store is imported rather than stood in for, because the ordering step
         * registers its combined generation with it and the job-boundary listener publishes what is
         * registered; a context without the store would fail this job at its boundary for want of a
         * publisher rather than for anything this specification is about (DL-212). Only the remote
         * service is replaced, and it reports an empty base so the generation allocation has a defined
         * starting point (DL-210).
         *
         * @return an object-store edge that accepts uploads and reports no existing generation
         */
        @Bean
        S3Operations objectStore() {
            final S3Operations objectStore = mock(S3Operations.class);
            when(objectStore.listObjects(anyString(), anyString())).thenReturn(List.of());
            return objectStore;
        }
    }
}
