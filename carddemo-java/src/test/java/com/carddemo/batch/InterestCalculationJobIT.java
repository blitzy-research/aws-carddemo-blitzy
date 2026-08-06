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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.step.AdvisoryGenerationPublicationLock;
import com.carddemo.batch.step.InterestCalculationProcessor;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.domain.Account;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.InterestCalculationService;
import com.carddemo.service.InterestGroupTransactionBoundary;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.RunScopedPerformanceRecorder;
import com.carddemo.util.TransactionRecordMapper;

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.sns.core.SnsOperations;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The interest accrual job, launched by name against a real PostgreSQL server carrying the real
 * migrations, so that the fixed-width generation it writes can be measured rather than described.
 *
 * <p>What this specification proves that a unit test cannot: that the job is reachable under the name a
 * launcher addresses it by and that no instance of it exists until something launches one; that the
 * launch boundary refuses a parameter the run date's cascade refuses; that a completing run leaves an
 * artefact whose size is an exact multiple of the layout's own record width, with no separator anywhere;
 * that the identifier of every synthesized transaction is the ten-character launch parameter used
 * <strong>verbatim</strong> followed by a suffix that increments from one; that the interest is
 * <strong>truncated and not rounded</strong>, which the fixture makes visible by choosing a balance and
 * a rate whose exact quotient carries a third decimal; that the zero-rate gate skips a row entirely;
 * that the control break fires both inside the read loop and <strong>for the final group at end of
 * file</strong>; that both cycle accumulators are left at zero; that the origination and processing
 * timestamps are identical and in the batch form rather than the online one; that INTCALC writes only
 * the SYSTRAN generation and never inserts those rows into the live transaction master; and that a
 * failure in a later account group leaves an earlier completed group committed.
 *
 * <p><strong>The fixture is this specification's own, and the master is put back exactly as it was
 * found.</strong> The driving input is a shared master that neighbouring specifications also write to,
 * and an assertion over "whatever happens to be in it" is an assertion about the order specifications
 * ran in. So the master's contents are captured, replaced by three rows this specification chose, and
 * restored afterwards - whatever the outcome, because a half-restored master is as disruptive to a
 * neighbour as an unrestored one. Account balances are restored on the same principle. The transaction
 * master needs no restoration because this job is forbidden to write it; its unchanged row count is
 * asserted.
 *
 * <p>The three rows are chosen from what the reference seed already makes reachable. The seeded accounts
 * carry a blank group identifier, so every rate resolves through the <em>single</em> default-group probe;
 * the seeded default group holds a non-zero rate for one transaction type and a zero rate for another,
 * so both arms of the rate gate are reached without inventing a disclosure row.
 *
 * <p>Provenance: the legacy interest job member, the interest program and the generation-group
 * definitions at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is transcribed here.
 */
@SpringBootTest(classes = InterestCalculationJobIT.JobContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false"})
@ActiveProfiles("test")
@TestPropertySource(properties = "carddemo.batch.interest-calculation.staging-directory="
        + "${java.io.tmpdir}/carddemo-interest-calculation-it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("InterestCalculationJobIT - one pass, account-group commits, one SYSTRAN generation")
class InterestCalculationJobIT extends AbstractPostgresIT {

    /** The ten-character run date, with no separator, exactly as the legacy step supplies one. */
    private static final String RUN_DATE = "2022071800";

    /** A distinct valid run date for the late-group failure scenario. */
    private static final String FAILURE_RUN_DATE = "2022071801";

    /** A third distinct valid run date, so the measured baseline run is its own job instance. */
    private static final String BASELINE_RUN_DATE = "2022071802";

    /** An account the reference seed carries, together with a cross-reference that names its card. */
    private static final String FIRST_ACCOUNT = "00000000001";

    /** A second seeded account, so a control break fires inside the read loop as well as at its end. */
    private static final String SECOND_ACCOUNT = "00000000002";

    /** A transaction type the seeded default group discloses at a non-zero rate. */
    private static final String EARNING_TYPE = "01";

    /** A transaction type the seeded default group discloses at a zero rate. */
    private static final String ZERO_RATE_TYPE = "02";

    /** The category both types are disclosed under. */
    private static final String CATEGORY = "0001";

    /**
     * The balance the fixture carries, chosen so the accrual's exact quotient has a third decimal.
     *
     * <p>At the seeded non-zero rate the product is 15010.8000 and the quotient is exactly 12.509, so a
     * truncating store yields 12.50 and a rounding one would yield 12.51. That one cent is the whole
     * point of the choice: it is the difference the legacy's absence of any rounding request produces,
     * and it is invisible to a fixture whose quotient happens to terminate at two decimals.
     */
    private static final BigDecimal EARNING_BALANCE = new BigDecimal("1000.72");

    /** What the truncating store must yield from that balance and that rate. */
    private static final BigDecimal TRUNCATED_INTEREST = new BigDecimal("12.50");

    /** What a rounding store would have yielded, asserted against so the mode cannot drift. */
    private static final BigDecimal ROUNDED_INTEREST = new BigDecimal("12.51");

    /** A balance on the zero-rate row, large enough that a leaked computation would be obvious. */
    private static final BigDecimal SKIPPED_BALANCE = new BigDecimal("5000.00");

    /** How many rows the fixture presents to the read loop. */
    private static final int FIXTURE_ROWS = 3;

    /** How many account groups those rows form. */
    private static final int FIXTURE_GROUPS = 2;

    /** How many transactions the fixture synthesizes, the zero-rate row producing none. */
    private static final int FIXTURE_TRANSACTIONS = 2;

    /** The three account amounts this run mutates, captured so they can be put back. */
    private record Balances(String acctId, BigDecimal balance, BigDecimal cycleCredit,
            BigDecimal cycleDebit) {
    }

    @Autowired
    private JobRegistry jobRegistry;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job interestCalculationJob;

    @Autowired
    private InterestCalculationJobConfig config;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private S3Operations objectStore;

    /** The driving input as it was found, so it can be put back. */
    private final List<TransactionCategoryBalance> displacedRows = new ArrayList<>();

    /** The account amounts as they were found. */
    private final List<Balances> displacedBalances = new ArrayList<>();

    /** The live transaction-master row count before the job, which INTCALC must not change. */
    private long transactionCountBefore;

    @BeforeEach
    void installTheFixture() throws Exception {
        clearStagedGenerations();
        this.displacedRows.clear();
        this.displacedRows.addAll(this.categoryBalanceRepository.findAll());

        this.displacedBalances.clear();
        for (final Account account : this.accountRepository.findAll()) {
            this.displacedBalances.add(new Balances(account.getAcctId(), account.getAcctCurrBal(),
                    account.getAcctCurrCycCredit(), account.getAcctCurrCycDebit()));
        }
        this.transactionCountBefore = this.transactionRepository.count();

        this.categoryBalanceRepository.deleteAllInBatch();
        this.categoryBalanceRepository.saveAll(List.of(
                new TransactionCategoryBalance(FIRST_ACCOUNT, EARNING_TYPE, CATEGORY,
                        EARNING_BALANCE),
                new TransactionCategoryBalance(FIRST_ACCOUNT, ZERO_RATE_TYPE, CATEGORY,
                        SKIPPED_BALANCE),
                new TransactionCategoryBalance(SECOND_ACCOUNT, EARNING_TYPE, CATEGORY,
                        EARNING_BALANCE)));
    }

    /**
     * Removes completed and in-progress generations left by a prior test JVM.
     *
     * <p>The integration database is recreated between Maven runs, so its execution identifiers start
     * again at one while the platform temporary directory survives. Without this cleanup, a failed
     * execution can appear to own an old completed file that merely reused its identifier.
     *
     * @throws Exception if the namespaced staging directory cannot be inspected or cleaned
     */
    private static void clearStagedGenerations() throws Exception {
        final Path directory = Path.of(System.getProperty("java.io.tmpdir"),
                "carddemo-interest-calculation-it");
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> contents = Files.list(directory)) {
            for (final Path artifact : contents.toList()) {
                if (Files.isRegularFile(artifact)) {
                    Files.deleteIfExists(artifact);
                }
            }
        }
    }

    @AfterEach
    void restoreWhatWasDisplaced() {
        this.categoryBalanceRepository.deleteAllInBatch();
        this.categoryBalanceRepository.saveAll(this.displacedRows);

        for (final Balances snapshot : this.displacedBalances) {
            this.accountRepository.findById(snapshot.acctId()).ifPresent(current -> {
                current.setAcctCurrBal(snapshot.balance());
                current.setAcctCurrCycCredit(snapshot.cycleCredit());
                current.setAcctCurrCycDebit(snapshot.cycleDebit());
                this.accountRepository.save(current);
            });
        }
    }

    /**
     * Every live-master transaction carrying a run-date prefix.
     *
     * <p>The correct INTCALC result is an empty list: synthesized rows belong only to the SYSTRAN
     * generation until COMBTRAN loads them later.
     *
     * @param runDate the ten-character identifier prefix
     * @return matching rows in the live master
     */
    private List<Transaction> liveMasterTransactionsFor(final String runDate) {
        final List<Transaction> minted = new ArrayList<>();
        for (final Transaction candidate : this.transactionRepository.findAll()) {
            if (candidate.getTranId().startsWith(runDate)) {
                minted.add(candidate);
            }
        }
        return minted;
    }

    /**
     * Parses every fixed-width record in one generation through the canonical transaction mapper.
     *
     * @param artefact the complete fixed-length-unblocked generation
     * @return the records in generation order
     */
    private static List<Transaction> generationTransactions(final byte[] artefact) {
        final List<Transaction> records = new ArrayList<>();
        for (int offset = 0; offset < artefact.length;
                offset += InterestCalculationJobConfig.TRANSACT_RECORD_LENGTH) {
            records.add(TransactionRecordMapper.fromRecord(artefact, offset));
        }
        return records;
    }

    /** The balance the fixture found on one account. */
    private BigDecimal displacedBalanceOf(final String accountId) {
        return this.displacedBalances.stream()
                .filter(candidate -> candidate.acctId().equals(accountId))
                .map(Balances::balance)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "the reference seed must carry account " + accountId));
    }

    @Test
    @Order(1)
    @DisplayName("the job is registered under its own name and no instance exists until one is "
            + "launched")
    void theJobIsRegisteredAndNothingRanAtStartUp() throws Exception {
        assertThat(this.jobRegistry.getJobNames())
                .contains(InterestCalculationJobConfig.JOB_NAME);
        assertThat(this.interestCalculationJob.getName())
                .isEqualTo(InterestCalculationJobConfig.JOB_NAME);
        assertThat(this.jobExplorer.getJobInstanceCount(InterestCalculationJobConfig.JOB_NAME))
                .as("launch-on-start is disabled and this configuration adds no trigger of its own")
                .isZero();
    }

    @Test
    @Order(2)
    @DisplayName("the launch boundary refuses a run date the cascade refuses")
    void theLaunchBoundaryRefusesABadRunDate() {
        assertThatThrownBy(() -> this.jobLauncher.run(this.interestCalculationJob,
                new JobParametersBuilder()
                        .addString(InterestCalculationJobConfig.PARM_DATE_KEY, "2022-07-18")
                        .toJobParameters()))
                .hasMessageContaining(InterestCalculationJobConfig.PARM_DATE_KEY);

        assertThatThrownBy(() -> this.jobLauncher.run(this.interestCalculationJob,
                new JobParameters()))
                .hasMessageContaining(InterestCalculationJobConfig.PARM_DATE_KEY);
    }

    @Test
    @Order(3)
    @DisplayName("a completing run closes both groups, truncates the accrual, skips the zero-rate row "
            + "and writes an exact multiple of the record width")
    void aCompletingRunWritesTheGeneration() throws Exception {
        final BigDecimal firstBalanceBefore = displacedBalanceOf(FIRST_ACCOUNT);
        final BigDecimal secondBalanceBefore = displacedBalanceOf(SECOND_ACCOUNT);

        final JobExecution execution = this.jobLauncher.run(this.interestCalculationJob,
                new JobParametersBuilder()
                        .addString(InterestCalculationJobConfig.PARM_DATE_KEY, RUN_DATE)
                        .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions())
                .as("the legacy member declares one application step and no condition-code gate")
                .hasSize(1);

        final StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getStepName()).isEqualTo(InterestCalculationJobConfig.STEP_NAME);
        assertThat(step.getExecutionContext()
                .getInt(InterestCalculationProcessor.CONTEXT_ROWS_READ))
                .isEqualTo(FIXTURE_ROWS);
        assertThat(step.getExecutionContext()
                .getInt(InterestCalculationProcessor.CONTEXT_GROUPS_CLOSED))
                .as("one break fires inside the read loop and one fires for the final group at end "
                        + "of file; without the second the last account's interest would be lost")
                .isEqualTo(FIXTURE_GROUPS);
        assertThat(step.getExecutionContext()
                .getString(InterestCalculationProcessor.CONTEXT_DEFAULT_GROUP_USED))
                .as("the seeded accounts carry a blank group identifier, so the single default probe "
                        + "is what resolves the rate")
                .isEqualTo("true");
        assertThat(step.getExecutionContext()
                .getString(InterestCalculationProcessor.CONTEXT_RATE_GATE_SKIPPED))
                .as("the zero-rate row skips the computation and the fee paragraph together")
                .isEqualTo("true");

        final int synthesized = step.getExecutionContext()
                .getInt(InterestCalculationProcessor.CONTEXT_TRANSACTIONS);
        assertThat(synthesized)
                .as("the zero-rate row synthesizes nothing, so two rows produce two transactions")
                .isEqualTo(FIXTURE_TRANSACTIONS);

        final Path generation = this.config.transactGeneration(execution.getId().longValue());
        assertThat(generation).exists();
        final byte[] artefact = Files.readAllBytes(generation);
        assertThat(artefact.length % InterestCalculationJobConfig.TRANSACT_RECORD_LENGTH)
                .as("the generation is fixed-length unblocked, so no record separator may appear")
                .isZero();
        assertThat(artefact.length)
                .isEqualTo(synthesized * InterestCalculationJobConfig.TRANSACT_RECORD_LENGTH);
        final String durableKey = "AWS.M2.CARDDEMO.SYSTRAN/"
                + String.format(Locale.ROOT, "G%010dV00", execution.getId().longValue());
        verify(this.objectStore).upload(
                eq("carddemo-batch-staging"),
                eq(durableKey),
                any(InputStream.class));
        assertThat(StagedGenerationStore.registeredArtifactCount(execution))
                .as("a successful terminal publication removes the serializable registry so a "
                        + "repeated callback cannot upload the same generation twice")
                .isZero();

        final List<Transaction> minted = generationTransactions(artefact);
        assertThat(minted).hasSize(synthesized);

        long expectedSuffix = 1L;
        for (final Transaction record : minted) {
            assertThat(record.getTranId())
                    .as("the ten-character parameter is used verbatim and the suffix increments")
                    .isEqualTo(InterestCalculationProcessor.interestTranId(RUN_DATE,
                            expectedSuffix));
            expectedSuffix++;

            assertThat(record.getTranTypeCd())
                    .isEqualTo(InterestCalculationProcessor.INTEREST_TRAN_TYPE_CD);
            assertThat(record.getTranCatCd())
                    .as("the category field is four digits wide, so the value fills all four")
                    .isEqualTo(InterestCalculationProcessor.INTEREST_TRAN_CAT_CD);
            assertThat(record.getTranSource())
                    .isEqualTo(InterestCalculationProcessor.INTEREST_TRAN_SOURCE);
            assertThat(record.getTranDesc())
                    .as("the trailing space inside the literal is part of the contract text")
                    .startsWith(InterestCalculationProcessor.INTEREST_DESCRIPTION_PREFIX);
            assertThat(record.getMerchantId())
                    .as("the merchant identifier is zero-filled for a synthesized transaction")
                    .isEqualTo(InterestCalculationProcessor.INTEREST_MERCHANT_ID);
            assertThat(record.getMerchantName()).isBlank();
            assertThat(record.getMerchantCity()).isBlank();
            assertThat(record.getMerchantZip()).isBlank();
            assertThat(record.getTranAmt())
                    .as("the store truncates: a rounding mode would have produced %s",
                            ROUNDED_INTEREST)
                    .isEqualByComparingTo(TRUNCATED_INTEREST)
                    .isNotEqualByComparingTo(ROUNDED_INTEREST);
            assertThat(record.getTranAmt().scale())
                    .as("every monetary field carries the receiving field's own two decimals")
                    .isEqualTo(2);
            assertThat(record.getTranCardNum())
                    .as("the card number is the one resolved through the cross-reference")
                    .hasSize(16);
            assertThat(record.getTranOrigTs())
                    .as("both timestamps are the one instant the control break took")
                    .isEqualTo(record.getTranProcTs());
            assertThat(record.getTranOrigTs())
                    .as("the batch form carries a hyphen where the online form carries a space, and "
                            + "a two-digit fraction where the online form carries six")
                    .matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000");
        }

        assertThat(this.accountRepository.findById(FIRST_ACCOUNT).orElseThrow().getAcctCurrBal())
                .as("the control break adds the group's accrued total to the current balance")
                .isEqualByComparingTo(firstBalanceBefore.add(TRUNCATED_INTEREST));
        assertThat(this.accountRepository.findById(SECOND_ACCOUNT).orElseThrow().getAcctCurrBal())
                .as("the final group is posted too, which is what the end-of-file break is for")
                .isEqualByComparingTo(secondBalanceBefore.add(TRUNCATED_INTEREST));

        for (final String posted : List.of(FIRST_ACCOUNT, SECOND_ACCOUNT)) {
            final Account account = this.accountRepository.findById(posted).orElseThrow();
            assertThat(account.getAcctCurrCycCredit())
                    .as("a control break resets both cycle accumulators, not one")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(account.getAcctCurrCycDebit())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        assertThat(this.transactionRepository.count())
                .as("INTCALC writes SYSTRAN only; COMBTRAN is the later master-load boundary")
                .isEqualTo(this.transactionCountBefore);
        assertThat(liveMasterTransactionsFor(RUN_DATE)).isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("a later account failure preserves the earlier account-group commit and its "
            + "in-progress generation record without publishing a completed generation")
    void aLateGroupFailureDoesNotRollBackAnEarlierGroup() throws Exception {
        this.categoryBalanceRepository.deleteAllInBatch();
        this.categoryBalanceRepository.saveAll(List.of(
                new TransactionCategoryBalance(FIRST_ACCOUNT, EARNING_TYPE, CATEGORY,
                        EARNING_BALANCE),
                new TransactionCategoryBalance(SECOND_ACCOUNT, "99", "9999",
                        EARNING_BALANCE)));

        final BigDecimal firstBalanceBefore = displacedBalanceOf(FIRST_ACCOUNT);
        final BigDecimal secondBalanceBefore = displacedBalanceOf(SECOND_ACCOUNT);

        final JobExecution execution = this.jobLauncher.run(this.interestCalculationJob,
                new JobParametersBuilder()
                        .addString(InterestCalculationJobConfig.PARM_DATE_KEY, FAILURE_RUN_DATE)
                        .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(this.accountRepository.findById(FIRST_ACCOUNT).orElseThrow().getAcctCurrBal())
                .as("the first control break committed before the second account abended")
                .isEqualByComparingTo(firstBalanceBefore.add(TRUNCATED_INTEREST));
        assertThat(this.accountRepository.findById(SECOND_ACCOUNT).orElseThrow().getAcctCurrBal())
                .as("the failing group never reaches its account rewrite")
                .isEqualByComparingTo(secondBalanceBefore);

        final Path completedGeneration =
                this.config.transactGeneration(execution.getId().longValue());
        final Path generation = StagedGenerationStore.workingPath(completedGeneration);
        assertThat(completedGeneration)
                .as("a failed job must not advertise its partial generation as completed")
                .doesNotExist();
        assertThat(generation).exists();
        final List<Transaction> records = generationTransactions(Files.readAllBytes(generation));
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getTranId())
                    .isEqualTo(InterestCalculationProcessor.interestTranId(FAILURE_RUN_DATE, 1L));
            assertThat(record.getTranAmt()).isEqualByComparingTo(TRUNCATED_INTEREST);
        });

        assertThat(this.transactionRepository.count()).isEqualTo(this.transactionCountBefore);
        assertThat(liveMasterTransactionsFor(FAILURE_RUN_DATE)).isEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("the Gate 3 figures are measured over this run itself - records, its own elapsed time, "
            + "and the JVM's own peak - and no threshold is asserted over any of them")
    void theGateThreeFiguresAreMeasuredOverOneRun() throws Exception {
        // WHY THIS IS MEASURED HERE AND NOT READ OFF A PANEL. Gate 3 names three figures, and until this
        // run existed two of them had no source that produced what they were called. The dashboard's
        // records-per-second panel is rate(counter[window]), which divides by the RATE WINDOW rather than
        // by the run's elapsed time - three hundred records in four seconds inside a one-minute window
        // reads as five per second, not seventy-five. Its peak-memory panel is max_over_time over scraped
        // samples, so a peak that rises and falls between two scrapes is invisible, and a run this short
        // can fall between two scrapes entirely. Those panels are honest visualisations of a running
        // system and are now labelled as such; the quotable figures come from here.
        //
        // NOTHING BELOW IS A THRESHOLD. No latency, throughput or capacity figure exists anywhere in the
        // legacy estate, so there is nothing to compare against and this gate establishes the first Java
        // baseline instead. Every assertion here is about the measurement being WELL FORMED - a positive
        // elapsed time, the fixture's own record count, a peak the platform actually reported - and none
        // is about a figure being fast enough. Adding such an assertion would invent a service level the
        // migration is expressly forbidden from inventing.
        final RunScopedPerformanceRecorder recorder = new RunScopedPerformanceRecorder();

        final JobExecution execution = recorder.measure(
                InterestCalculationJobConfig.JOB_NAME,
                FIXTURE_ROWS + " transaction-category-balance rows forming " + FIXTURE_GROUPS
                        + " account groups, resolved against the seeded default disclosure group; "
                        + "reference seed of 50 accounts, 50 category balances and 51 disclosure rows",
                measured -> measured.getStepExecutions().iterator().next().getExecutionContext()
                        .getInt(InterestCalculationProcessor.CONTEXT_ROWS_READ),
                () -> this.jobLauncher.run(this.interestCalculationJob,
                        new JobParametersBuilder()
                                .addString(InterestCalculationJobConfig.PARM_DATE_KEY, BASELINE_RUN_DATE)
                                .toJobParameters()));

        assertThat(execution.getStatus())
                .as("a baseline may only be taken from a run that actually completed")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(recorder.baselines()).singleElement().satisfies(baseline -> {
            assertThat(baseline.records())
                    .as("the record count is the run's own, read from the step that did the reading, so "
                            + "the quotient below is records-of-this-run over elapsed-of-this-run")
                    .isEqualTo(FIXTURE_ROWS);
            assertThat(baseline.elapsedNanos())
                    .as("elapsed time is wall clock across the launch and must be positive for the "
                            + "quotient to exist at all")
                    .isPositive();
            assertThat(baseline.peakHeapBytes())
                    .as("the peak is read from the platform's own per-pool accounting after a reset "
                            + "immediately before the launch, so it does not depend on a scrape interval")
                    .isPositive();
            assertThat(baseline.recordsPerSecond())
                    .as("records divided by this run's own elapsed seconds - the quotient Gate 3 names")
                    .isGreaterThan(BigDecimal.ZERO);
            assertThat(baseline.fixtureNote())
                    .as("a figure without the volumes it was measured over is not a baseline")
                    .contains("account groups");
        });

        final Path published = recorder.publish("gate3-interest-calculation.md");
        assertThat(published)
                .as("the figures are written into the build output for an operator to copy into "
                        + "docs/gate-evidence.md beside the date and the machine; a test must not edit "
                        + "the documentation tree, or the repository's content would depend on the "
                        + "hardware of whoever last ran the suite")
                .isRegularFile();
        assertThat(Files.readString(published, StandardCharsets.UTF_8))
                .contains("Records/second")
                .contains(InterestCalculationJobConfig.JOB_NAME)
                .contains("measurements, not thresholds");
    }

    /**
     * The narrowest context the interest job needs: batch orchestration, persistence, the launch-boundary
     * parameter contract and the translated program with its own collaborators.
     *
     * <p>No enabling annotation for batch appears here or anywhere in the module: under this framework
     * generation the batch auto-configuration backs off when that annotation is present, so adding it
     * would switch off the very infrastructure this context depends on.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    // AdvisoryGenerationPublicationLock is imported because the generation store now requires the
    // per-base publication lock, and this slice has the PostgreSQL server the production lock needs.
    // Importing the real one rather than a direct-run stand-in keeps the slice faithful: this job's
    // publication is serialized here exactly as it is in the application.
    @Import({InterestCalculationJobConfig.class, BatchConfig.class, JobParameterValidators.class,
            BatchStagingArea.class, StagedGenerationStore.class,
            AdvisoryGenerationPublicationLock.class, DateValidationService.class,
            InterestCalculationService.class, InterestGroupTransactionBoundary.class,
            AbendService.class})
    @EnableConfigurationProperties(AwsProperties.class)
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @EntityScan(basePackageClasses = Account.class)
    static class JobContext {

        /**
         * The clock the batch timestamp is read from, published here because this slice does not import
         * the persistence-auditing configuration that publishes it in the application.
         *
         * @return the system clock in coordinated universal time
         */
        @Bean
        Clock systemClock() {
            return Clock.systemUTC();
        }

        /**
         * Keeps this PostgreSQL-focused job test deterministic at the object-store edge. The store
         * implementation itself and the listener's publication path remain real; only the external S3
         * service is replaced here, while {@link BatchAwsIntegrationIT} proves the same operations
         * against LocalStack.
         *
         * @return an object-store edge that accepts uploads and reports no older generations
         */
        @Bean
        S3Operations objectStore() {
            final S3Operations objectStore = mock(S3Operations.class);
            when(objectStore.listObjects(anyString(), anyString())).thenReturn(List.of());
            return objectStore;
        }

        /**
         * Prevents an unrelated notification endpoint from becoming a prerequisite of this
         * PostgreSQL-focused test. The notification contract is exercised against LocalStack by
         * {@link BatchAwsIntegrationIT}.
         *
         * @return a successful notification edge
         */
        @Bean
        SnsOperations notifications() {
            return mock(SnsOperations.class);
        }
    }
}
