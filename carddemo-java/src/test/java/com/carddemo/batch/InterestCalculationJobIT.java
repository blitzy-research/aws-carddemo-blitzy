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

import com.carddemo.batch.step.InterestCalculationProcessor;
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
import com.carddemo.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
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
 * file</strong>; that both cycle accumulators are left at zero; and that the origination and processing
 * timestamps are identical and in the batch form rather than the online one.
 *
 * <p><strong>The fixture is this specification's own, and the master is put back exactly as it was
 * found.</strong> The driving input is a shared master that neighbouring specifications also write to,
 * and an assertion over "whatever happens to be in it" is an assertion about the order specifications
 * ran in. So the master's contents are captured, replaced by three rows this specification chose, and
 * restored afterwards - whatever the outcome, because a half-restored master is as disruptive to a
 * neighbour as an unrestored one. Account balances and the transactions the run minted are restored on
 * the same principle.
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
                "spring.jpa.hibernate.ddl-auto=none"})
@ActiveProfiles("test")
@TestPropertySource(properties = "carddemo.batch.interest-calculation.staging-directory="
        + "${java.io.tmpdir}/carddemo-interest-calculation-it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("InterestCalculationJobIT - launched by name, one pass, one fixed-width generation")
class InterestCalculationJobIT extends AbstractPostgresIT {

    /** The ten-character run date, with no separator, exactly as the legacy step supplies one. */
    private static final String RUN_DATE = "2022071800";

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

    /** The driving input as it was found, so it can be put back. */
    private final List<TransactionCategoryBalance> displacedRows = new ArrayList<>();

    /** The account amounts as they were found. */
    private final List<Balances> displacedBalances = new ArrayList<>();

    @BeforeEach
    void installTheFixture() {
        this.displacedRows.clear();
        this.displacedRows.addAll(this.categoryBalanceRepository.findAll());

        this.displacedBalances.clear();
        for (final Account account : this.accountRepository.findAll()) {
            this.displacedBalances.add(new Balances(account.getAcctId(), account.getAcctCurrBal(),
                    account.getAcctCurrCycCredit(), account.getAcctCurrCycDebit()));
        }

        this.categoryBalanceRepository.deleteAllInBatch();
        this.categoryBalanceRepository.saveAll(List.of(
                new TransactionCategoryBalance(FIRST_ACCOUNT, EARNING_TYPE, CATEGORY,
                        EARNING_BALANCE),
                new TransactionCategoryBalance(FIRST_ACCOUNT, ZERO_RATE_TYPE, CATEGORY,
                        SKIPPED_BALANCE),
                new TransactionCategoryBalance(SECOND_ACCOUNT, EARNING_TYPE, CATEGORY,
                        EARNING_BALANCE)));
    }

    @AfterEach
    void restoreWhatWasDisplaced() {
        for (final Transaction minted : synthesizedTransactions()) {
            this.transactionRepository.deleteById(minted.getTranId());
        }

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
     * Every transaction this run minted, in identifier order.
     *
     * <p>Selected by the run date prefix, which is the one thing every identifier of a run shares, so a
     * neighbouring specification's own rows are never touched.
     *
     * @return the run's transactions, ordered by identifier
     */
    private List<Transaction> synthesizedTransactions() {
        final List<Transaction> minted = new ArrayList<>();
        for (final Transaction candidate : this.transactionRepository.findAll()) {
            if (candidate.getTranId().startsWith(RUN_DATE)) {
                minted.add(candidate);
            }
        }
        minted.sort(Comparator.comparing(Transaction::getTranId));
        return minted;
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

        final List<Transaction> minted = synthesizedTransactions();
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
    @EnableAutoConfiguration
    @Import({InterestCalculationJobConfig.class, BatchConfig.class, JobParameterValidators.class,
            DateValidationService.class, InterestCalculationService.class, AbendService.class})
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
    }
}
