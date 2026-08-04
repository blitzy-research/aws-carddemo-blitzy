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

import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.config.BatchConfig;
import com.carddemo.domain.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.util.TransactionRecordMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

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
 *       deliberately, and the absence of a start-up launch can only be observed by starting a context
 *       and finding that no instance exists.</li>
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
 * <p>Provenance: the legacy combine job, the generation-group definitions and the transaction copybook
 * at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is transcribed here.
 */
@SpringBootTest(classes = CombineTransactionsJobIT.JobContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none"})
@ActiveProfiles("test")
@DisplayName("CombineTransactionsJobIT - registered by name, inert at start-up, and loads on demand")
class CombineTransactionsJobIT extends AbstractPostgresIT {

    /**
     * A card the reference seed already carries.
     *
     * <p>The master constrains every posted transaction to name an existing card, so a fixture that
     * invented a card number would fail on the foreign key rather than on anything this test asserts.
     */
    private static final String SEEDED_CARD = "0500024453765740";

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
     * @param  directory the temporary directory to write into
     * @param  name      the dataset's file name
     * @param  records   the records to render, in file order
     * @return the location a job parameter carries
     * @throws IOException if the dataset cannot be written
     */
    private static String dataset(final Path directory, final String name,
            final List<Transaction> records) throws IOException {
        final StringBuilder images = new StringBuilder();
        for (final Transaction record : records) {
            final byte[] image = TransactionRecordMapper.toRecordBytes(record);
            assertThat(image).as("every record of a legacy generation is a fixed encoded width")
                    .hasSize(TransactionRecordMapper.RECORD_LENGTH);
            images.append(new String(image, StandardCharsets.US_ASCII)).append('\n');
        }
        final Path dataset = directory.resolve(name);
        Files.writeString(dataset, images.toString(), StandardCharsets.US_ASCII);
        return dataset.toUri().toString();
    }

    @Test
    @DisplayName("the job is registered by name, holds exactly two ungated steps, fires nothing at "
            + "start-up, and loads the combined stream into the master when launched")
    void theJobIsRegisteredInertAtStartUpAndLoadsOnDemand(@TempDir final Path directory)
            throws Exception {
        assertThat(jobRegistry.getJobNames())
                .as("the job is launched by name, so it must be registered under that name")
                .contains(CombineTransactionsJobConfig.JOB_NAME);

        assertThat(jobExplorer.getJobInstances(CombineTransactionsJobConfig.JOB_NAME, 0, 10))
                .as("a legacy job was submitted deliberately; bringing an application up never "
                        + "triggered one, so no instance may exist before this test launches one")
                .isEmpty();

        assertThat(combineTransactionsJob)
                .as("a plain sequence, not a flow job - a failure-ending transition would have "
                        + "produced the latter, and the measured member carries no gate")
                .isExactlyInstanceOf(SimpleJob.class);
        assertThat(((SimpleJob) combineTransactionsJob).getStepNames()).containsExactly(
                CombineTransactionsJobConfig.ORDER_STEP_NAME,
                CombineTransactionsJobConfig.LOAD_STEP_NAME);

        final String backup = dataset(directory, "transaction-backup.txt",
                List.of(record(THIRD_ID), record(FIRST_ID)));
        final String synthesized = dataset(directory, "synthesized-transactions.txt",
                List.of(record(SECOND_ID)));

        final JobExecution execution = jobLauncher.run(combineTransactionsJob,
                new JobParametersBuilder()
                        .addString(CombineTransactionsJobConfig.BACKUP_INPUT_LOCATION, backup)
                        .addString(CombineTransactionsJobConfig.SYNTHESIZED_INPUT_LOCATION,
                                synthesized)
                        .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
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
     * The narrowest context this job needs: batch orchestration, persistence and the two collaborators
     * the configuration is built against.
     *
     * <p>No enabling annotation for batch appears here or anywhere in the module: under this framework
     * generation the batch auto-configuration backs off when that annotation is present, so adding it
     * would switch off the very infrastructure this context depends on.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({CombineTransactionsJobConfig.class, BatchConfig.class,
            FixedWidthFlatFileReaderFactory.class})
    @EnableJpaRepositories(basePackageClasses = TransactionRepository.class)
    @EntityScan(basePackageClasses = Transaction.class)
    static class JobContext {
    }
}
