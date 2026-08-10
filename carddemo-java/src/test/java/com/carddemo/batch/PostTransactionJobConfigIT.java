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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.batch.step.AdvisoryGenerationPublicationLock;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.RejectRecordWriter;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.batch.step.TransactionValidationProcessor;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.enums.RejectReason;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.PostingStageTransactionBoundary;
import com.carddemo.service.TransactionPostingService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.LegacyRejectReasons;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.sns.core.SnsOperations;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The daily-transaction posting job, launched by name against a real PostgreSQL server carrying the
 * real migrations, so that the fixed-length reject generation it writes can be measured byte for byte
 * rather than described.
 *
 * <p>This is the first job of the pipeline the estate runs in the order posting, interest accrual,
 * consolidation, statements - so it is the producer of the state every later job observes. It
 * translates the single application step of {@code app/jcl/POSTTRAN.jcl}, which runs
 * {@code app/cbl/CBTRN02C.cbl}.
 *
 * <h2>What this specification owns, and what it deliberately leaves alone</h2>
 * This is <strong>per-job</strong> verification: the runtime shape of the job, the wiring of its six
 * data definitions, the four-hundred-and-thirty byte reject contract, the order and guarding of the
 * validation cascade, and the tolerated completion code. The chained four-job run and the comparison
 * against the shared golden output files belong to the end-to-end specification, so no golden file is
 * read here and no later job is launched. The builder-level shape of the job - that its builder
 * attaches one step, no flow transition and no parameter validator - belongs to the unit
 * specification beside it; what is asserted here is the <em>runtime consequence</em> of that shape,
 * read off an execution the framework actually ran.
 *
 * <h2>The job shape, measured from the member</h2>
 * {@code app/jcl/POSTTRAN.jcl} is forty-five lines and declares exactly one application step, named
 * {@code STEP15} at line 23. It carries no condition-code dependency and passes no parameter string.
 * Three absences are therefore asserted rather than assumed: one step and no second, zero
 * failure-ending transitions, and no date parameter of any kind. The only step gates in the whole
 * estate are the three strict ones in the statement job and the one tolerant gate in the backup job,
 * so adding a gate here by analogy would be an invention. The runtime discriminator for the absence
 * of a flow transition is stated on
 * {@link #theJobRunsWithNoParameterAtAllAndReportsTheToleratedCompletionCode()}.
 *
 * <h2>The four-hundred-and-thirty byte reject record</h2>
 * Three independent declarations in the estate agree on the width: the reject file description at
 * {@code app/cbl/CBTRN02C.cbl} lines 81 to 84 splits the record into a three-hundred-and-fifty byte
 * source image and an eighty byte validation trailer; working storage at lines 176 to 182 repeats
 * that split and decomposes the trailer into a four-digit numeric reason code followed by a
 * seventy-six character description; and the reject data definition of the job member declares a
 * record length of four hundred and thirty. <strong>A circulating claim of a five-hundred byte
 * allocation was searched for and NOT found</strong> - neither the file description, nor working
 * storage, nor the data definition carries it - so four hundred and thirty is implemented and the
 * absent conflict is recorded here rather than logged as a phantom anomaly.
 *
 * <p>The record format is fixed-length <em>unblocked</em>, so there is no block padding: the produced
 * artefact is an exact multiple of four hundred and thirty bytes with nothing appended and no
 * separator anywhere. Every width asserted here is measured in encoded US-ASCII bytes, and every
 * comparison is byte equality over whole records - never a trimmed or semantic comparison - so a
 * stray blank or a wrong sign byte fails rather than passing unnoticed.
 *
 * <h2>Why the expectation is built independently of the code under test</h2>
 * Every expected reject image is assembled from the widths by the shared test data factory, which
 * composes them with ordinary string arithmetic and explicitly does not call the writing step. The
 * writer's own image builder, the record mappers and the decimal codec are all deliberately unused as
 * oracles: an expectation produced by the code under test cannot detect a defect in that code, and a
 * codec fault would otherwise produce a matching input and a matching expectation while the system
 * was wrong. For records taken from the delivered fixture the expectation is stronger still - the
 * first three hundred and fifty bytes are compared against the fixture's own bytes, which is the
 * literal reading of "the source image copied unchanged".
 *
 * <h2>The five reject reasons, and the two that this tier cannot reach</h2>
 * Codes 100, 102 and 103 are produced for real here and compared byte for byte. The other two are
 * not, and both reasons are structural rather than an omission:
 * <ul>
 *   <li><strong>109 is inert by control flow.</strong> The member sets it only on the failure arm of
 *       the account rewrite, which runs downstream of the posting mainline - after the mainline has
 *       already branched on a zero reason code and after the category balance was committed - and the
 *       transaction write still proceeds. A record carrying it is therefore a <em>posted</em> record,
 *       and the routing decision is taken on whether the record posted and never on whether it
 *       carries a reason code. So no reject record can ever bear it, which is asserted here as the
 *       absence of any such record in a produced generation.</li>
 *   <li><strong>101 is unreachable in the migrated schema, and that is a documented strengthening
 *       rather than a lost behaviour.</strong> The code fires when the account read finds no row for
 *       the identifier the cross-reference supplied. The landing table carries no foreign key at all,
 *       precisely so a record naming an unresolvable card or account can be stored and then refused -
 *       but the cross-reference table does: {@code V2__create_indexes.sql} adds
 *       {@code fk_card_xref_account}, an immediate, non-deferrable constraint from the
 *       cross-reference's account identifier to the account table. A dangling cross-reference row -
 *       the only state from which the account read can miss - therefore cannot exist, where the
 *       legacy key-sequenced tier permitted one. The branch is nonetheless carried across faithfully
 *       and is exercised where the account repository can be a test double, which is the unit tier;
 *       here the reason is asserted to remain a distinct constant carrying its own four-digit field,
 *       and no constraint is dropped, no replication role is changed and no migration is edited to
 *       manufacture the state, because the migrations are the sole owner of the schema. Recorded as a
 *       decision-log candidate.</li>
 * </ul>
 * 101 and 109 carry identical description text and are distinct codes; folding them together would
 * erase the distinction between a failed read and a failed rewrite, which the four-digit field is the
 * only carrier of.
 *
 * <h2>Determinism</h2>
 * The clock this slice publishes is the pinned one the shared base class owns, so the regenerated
 * processing timestamp is a value that can be asserted exactly. Nothing here reads a system clock, a
 * random source or a wall-clock interval, and no elapsed-time, throughput or capacity figure is
 * asserted anywhere: no such figure exists in the legacy estate, so there is nothing to compare
 * against and inventing a threshold is expressly out of scope. The step timer is read for its
 * presence and its tags alone.
 *
 * <h2>Where this run stages its files, and why not the platform temporary directory</h2>
 * The staging directory is a literal path inside the module's own build output. That keeps it unique
 * per checkout by construction - which is what matters when several builds share a host - without
 * needing a computed directory name, so this class publishes no property source of its own and leaves
 * the shared base class as the only owner of the container's address. The neighbouring job
 * specifications name a directory under the platform temporary directory and disambiguate it by
 * process, which solves the same collision differently; either is sound, and the build-output form is
 * preferred here because it is also removed by a clean.
 *
 * <p>Both logical resource names this run uses are configured rather than defaulted, which is the
 * point: the destination is a distinct generation per job execution, resolved from configuration by
 * logical name, and no path is written into the production configuration. The generation-group
 * definition member for the reject destination carries a defect worth recording and not propagating -
 * its comment banner announces the deletion of a transaction-master cluster while its control stream
 * only defines a generation-data group, and one word of that banner is misspelt. Retention depth
 * belongs to the shared durable store and no retention logic is implemented or asserted here.
 *
 * <h2>Other translation notes raised for the decision log</h2>
 * <ul>
 *   <li>The account expiration field name is misspelt in the copybook at
 *       {@code app/cpy/CVACT01Y.cpy} line 11. The Java property spells it correctly while the record
 *       layout keeps the original spelling, so byte positions are untouched and only the name
 *       differs.</li>
 *   <li>The completion code this job raises for refused records is a <em>partial success</em>. It is
 *       the direct upstream counterpart of the tolerant at-most-four gate the backup job carries:
 *       over-restricting either side would abort a cycle on a tolerated warning, which is a
 *       behavioural regression disguised as hardening.</li>
 *   <li>The regenerated processing timestamp is the batch form and categorically not the online one.
 *       Emitting the online form would produce a value of exactly the right width that fails a byte
 *       comparison, which is the hardest kind of defect to see, so the separators are asserted
 *       position by position.</li>
 * </ul>
 *
 * <p>Provenance: the legacy posting job member, the posting program, the generation-group definition
 * and the record layouts, read as reference at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is a provenance string for the
 * traceability matrix header only: it is not carried by every legacy member, so nothing here asserts
 * it against one. No legacy source text is transcribed in this file; the estate is cited by step
 * name, data-definition name, program name, width, offset, count and reject code alone.
 */
@SpringBootTest(classes = PostTransactionJobConfigIT.JobContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false",
                PostTransactionJobConfigIT.STAGING_DIRECTORY_SETTING,
                PostTransactionJobConfigIT.DALYTRAN_DATASET_SETTING,
                PostTransactionJobConfigIT.DALYREJS_DATASET_BASE_SETTING})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("PostTransactionJobConfigIT - one step, no gate, a 430-byte generation, tolerated code 4")
class PostTransactionJobConfigIT extends AbstractPostgresIT {

    /**
     * The staging directory this specification owns, inside the module's own build output.
     *
     * <p>Relative, and resolved against the module directory because that is the working directory the
     * integration phase runs in - the same convention the shared performance recorder already uses for
     * its evidence directory. Being inside the build output makes it unique per checkout, so two builds
     * on one host cannot write each other's files, and makes it removable by a clean.
     */
    static final String STAGING_DIRECTORY_VALUE = "target/post-transaction-it-staging";

    /** Logical name of the sequential input this run reads, distinct from the shipped default. */
    static final String DALYTRAN_DATASET_VALUE = "CARDDEMO.POSTTRAN.IT.DALYTRAN.PS";

    /** Logical base of the reject generation group this run writes, likewise its own. */
    static final String DALYREJS_DATASET_BASE_VALUE = "CARDDEMO.POSTTRAN.IT.DALYREJS";

    /** Inline setting binding the staging directory, so no property source is declared here. */
    static final String STAGING_DIRECTORY_SETTING =
            PostTransactionJobConfig.STAGING_DIRECTORY_PROPERTY + "=" + STAGING_DIRECTORY_VALUE;

    /** Inline setting binding the logical name of the sequential input. */
    static final String DALYTRAN_DATASET_SETTING =
            PostTransactionJobConfig.DALYTRAN_DATASET_PROPERTY + "=" + DALYTRAN_DATASET_VALUE;

    /** Inline setting binding the logical base of the reject generation group. */
    static final String DALYREJS_DATASET_BASE_SETTING =
            PostTransactionJobConfig.DALYREJS_DATASET_BASE_PROPERTY + "="
                    + DALYREJS_DATASET_BASE_VALUE;

    /** The delivered sequential input fixture, read as fixed-width records rather than as lines. */
    private static final String DAILY_TRANSACTION_FIXTURE = "dailytran.txt";

    /** The delivered account fixture, which is the authority for the seeded account state. */
    private static final String ACCOUNT_FIXTURE = "acctdata.txt";

    /** Width of one landing record, and of the source segment of a reject record. */
    private static final int LANDING_RECORD_WIDTH = 350;

    /** Width of one account record. */
    private static final int ACCOUNT_RECORD_WIDTH = 300;

    /** Width of one whole reject record: the landing image, four digits and seventy-six characters. */
    private static final int REJECT_RECORD_WIDTH = 430;

    /** Width of the four-digit numeric reason field that opens the validation trailer. */
    private static final int REASON_CODE_WIDTH = 4;

    /** Width of the description field that follows it. */
    private static final int REASON_DESCRIPTION_WIDTH = 76;

    /** Width of the whole validation trailer. */
    private static final int VALIDATION_TRAILER_WIDTH = 80;

    /** Width of the twenty-six character timestamp fields of the landing and posted layouts. */
    private static final int TIMESTAMP_WIDTH = 26;

    /**
     * How many leading characters of the origination timestamp the expiry comparison reads.
     *
     * <p>The comparison is lexicographic over exactly this many characters of a zero-padded date, not a
     * comparison of two date values, and no calendar type is constructed on either side of it.
     */
    private static final int EXPIRY_COMPARISON_WIDTH = 10;

    /** Ordinal of the first delivered fixture record used here, which posts through the update arm. */
    private static final int POSTING_PURCHASE_ORDINAL = 0;

    /** Ordinal of the delivered return record, which posts through the create arm. */
    private static final int POSTING_RETURN_ORDINAL = 1;

    /** Ordinal of the delivered record that the seeded account state refuses as over limit. */
    private static final int SEEDED_OVERLIMIT_ORDINAL = 42;

    /** Card of the delivered purchase record, whose category-balance key the seed already carries. */
    private static final String PURCHASE_CARD = "4859452612877065";

    /** Account that card resolves to through the cross-reference. */
    private static final String PURCHASE_ACCOUNT = "00000000007";

    /** Card of the delivered return record, whose category-balance key the seed does not carry. */
    private static final String RETURN_CARD = "0927987108636232";

    /** Account that card resolves to. */
    private static final String RETURN_ACCOUNT = "00000000020";

    /** Card of the delivered over-limit record, on the lowest-limit seeded account. */
    private static final String OVERLIMIT_CARD = "6509230362553816";

    /** Account that card resolves to. */
    private static final String OVERLIMIT_ACCOUNT = "00000000030";

    /** A further seeded card, used for the record refused after account expiry. */
    private static final String EXPIRED_ARRIVAL_CARD = "4011500891777367";

    /** Account that card resolves to. */
    private static final String EXPIRED_ARRIVAL_ACCOUNT = "00000000013";

    /** Amount of the delivered purchase record, in the positive direction. */
    private static final BigDecimal PURCHASE_AMOUNT = new BigDecimal("504.77");

    /** Amount of the delivered return record, in the negative direction. */
    private static final BigDecimal RETURN_AMOUNT = new BigDecimal("-919.00");

    /** Transaction type and category of the delivered purchase record. */
    private static final String PURCHASE_TYPE_CODE = "01";

    /** Category of the delivered purchase record. */
    private static final String PURCHASE_CATEGORY_CODE = "0001";

    /** Transaction type of the delivered return record, which the seeded balance keys do not carry. */
    private static final String RETURN_TYPE_CODE = "03";

    /** Category of the delivered return record. */
    private static final String RETURN_CATEGORY_CODE = "0001";

    /** Identifier of the constructed record whose card resolves to nothing. */
    private static final String UNRESOLVED_CARD_RECORD_ID = "9900000000000100";

    /** Identifier of the constructed record that arrives after its account expired. */
    private static final String EXPIRED_ARRIVAL_RECORD_ID = "9900000000000103";

    /** Identifier of the constructed record that is over limit and past expiry at once. */
    private static final String DOUBLY_INVALID_RECORD_ID = "9900000000000923";

    /** Identifier of the first operand-order probe, which the basis refuses. */
    private static final String BASIS_REFUSED_RECORD_ID = "9900000000000201";

    /** Identifier of the second operand-order probe, which the basis accepts. */
    private static final String BASIS_ACCEPTED_RECORD_ID = "9900000000000202";

    /** A modest amount that no seeded credit limit refuses, for the expiry-only record. */
    private static final BigDecimal MODEST_AMOUNT = new BigDecimal("10.00");

    /** Credit limit both operand-order probes are pinned to. */
    private static final BigDecimal PROBE_CREDIT_LIMIT = new BigDecimal("100.00");

    /** Amount both operand-order probes carry. */
    private static final BigDecimal PROBE_AMOUNT = new BigDecimal("75.00");

    /** The larger cycle accumulator of the operand-order probes. */
    private static final BigDecimal PROBE_LARGER_ACCUMULATOR = new BigDecimal("50.00");

    /** The smaller cycle accumulator of the operand-order probes. */
    private static final BigDecimal PROBE_SMALLER_ACCUMULATOR = new BigDecimal("20.00");

    /** Opening balance both operand-order probes are pinned to. */
    private static final BigDecimal PROBE_OPENING_BALANCE = new BigDecimal("0.00");

    /** Zero at the module's single monetary scale, for the accumulators the seed carries as zero. */
    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");

    /**
     * The processing timestamp the pinned clock produces, in the batch form and nothing else.
     *
     * <p>Four-digit year, hyphen, month, hyphen, day, <strong>hyphen</strong>, hour, dot, minute, dot,
     * second, dot, two-digit hundredths, then the literal four zeroes - twenty-six characters. The
     * online form of the same width differs in four places at once: a space where this has its third
     * hyphen, colons where this has its first two dots, and a six-digit fraction where this has two
     * digits and a literal tail. Both forms appear in one posted row here, which is what makes the
     * contrast assertable: the origination timestamp the input carries is in the other form.
     */
    private static final String EXPECTED_BATCH_PROCESSING_TIMESTAMP = "2022-06-10-19.27.53.000000";

    /** One-based position of the hyphen that separates the day from the hour in the batch form. */
    private static final int BATCH_FORM_THIRD_HYPHEN_POSITION = 11;

    /** The literal tail the batch form ends with, occupying its last four characters. */
    private static final String BATCH_FORM_TAIL = "0000";

    /** Parameter key this specification mints a distinct run identity under. */
    private static final String RUN_KEY = "carddemo.test.postTransactionRun";

    /** Every parameter key the framework or this specification may add to a launch. */
    private static final List<String> ALLOWED_PARAMETER_KEYS = List.of(RUN_KEY, "run.id");

    /** The six data definitions the job member declares, in the order it declares them. */
    private static final List<String> MEMBER_DATA_DEFINITIONS = List.of(
            TransactionPostingService.TRANFILE_DD,
            TransactionPostingService.DALYTRAN_DD,
            TransactionPostingService.XREFFILE_DD,
            TransactionPostingService.DALYREJS_DD,
            TransactionPostingService.ACCTFILE_DD,
            TransactionPostingService.TCATBALF_DD);

    @Autowired
    private JobRegistry jobRegistry;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job postTransactionJob;

    @Autowired
    private PostTransactionJobConfig config;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardCrossReferenceRepository crossReferenceRepository;

    @Autowired
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    /** Every account amount as this specification found it, so all of them can be put back. */
    private final List<AccountAmounts> displacedAccounts = new ArrayList<>();

    /** Every category balance as this specification found it, for the same reason. */
    private final List<TransactionCategoryBalance> displacedCategoryBalances = new ArrayList<>();

    /**
     * Distinct run identities, so each launch is its own job instance rather than a repeat.
     *
     * <p>Held for the class rather than for a test instance, because the framework creates a fresh test
     * instance per method while the job metadata is shared for the whole run: a per-instance counter
     * would hand the same identifying parameter to every launch and the second one would be refused as
     * an already-completed instance.
     */
    private static final AtomicLong RUN_IDENTITY = new AtomicLong();

    /** The six mutable amounts and dates of one account, captured so they can be restored. */
    private record AccountAmounts(String acctId, BigDecimal balance, BigDecimal creditLimit,
            BigDecimal cycleCredit, BigDecimal cycleDebit, String expirationDate) {
    }

    /** Identifiers of the records staged by the current test, so posted rows can be removed again. */
    private final List<String> stagedRecordIds = new ArrayList<>();

    // ---------------------------------------------------------------------------------------------
    // Lifecycle. Nothing is reset by the shared base class, deliberately, so this specification
    // captures what it is about to move and puts all of it back - whatever the outcome, because a
    // half-restored master is as disruptive to a neighbour as an unrestored one.
    // ---------------------------------------------------------------------------------------------

    @BeforeEach
    void captureWhatThisRunWillMove() throws IOException {
        clearStagedFiles();
        this.stagedRecordIds.clear();

        this.displacedAccounts.clear();
        for (final Account account : this.accountRepository.findAll()) {
            this.displacedAccounts.add(new AccountAmounts(account.getAcctId(),
                    account.getAcctCurrBal(), account.getAcctCreditLimit(),
                    account.getAcctCurrCycCredit(), account.getAcctCurrCycDebit(),
                    account.getAcctExpirationDate()));
        }

        this.displacedCategoryBalances.clear();
        this.displacedCategoryBalances.addAll(this.categoryBalanceRepository.findAll());
    }

    @AfterEach
    void restoreWhatThisRunMoved() {
        final List<Transaction> posted = this.transactionRepository.findAllById(this.stagedRecordIds);
        this.transactionRepository.deleteAll(posted);

        this.categoryBalanceRepository.deleteAllInBatch();
        this.categoryBalanceRepository.saveAll(this.displacedCategoryBalances);

        for (final AccountAmounts snapshot : this.displacedAccounts) {
            this.accountRepository.findById(snapshot.acctId()).ifPresent(current -> {
                current.setAcctCurrBal(snapshot.balance());
                current.setAcctCreditLimit(snapshot.creditLimit());
                current.setAcctCurrCycCredit(snapshot.cycleCredit());
                current.setAcctCurrCycDebit(snapshot.cycleDebit());
                current.setAcctExpirationDate(snapshot.expirationDate());
                this.accountRepository.save(current);
            });
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Staging. Every resource is a logical name resolved against the configured directory by the
    // production configuration itself, so no path is composed here.
    // ---------------------------------------------------------------------------------------------

    /**
     * Removes files an earlier test of this run left behind.
     *
     * <p>The integration database is recreated between builds, so its execution identifiers begin again
     * at one while the staging directory survives for the length of the build. Without this, a later
     * execution can appear to own an older completed generation that merely reused its identifier.
     *
     * <p>The sweep is unconditional over everything it finds, which is safe only because the directory
     * lies inside this checkout's own build output and no other specification writes into it.
     *
     * @throws IOException if the directory cannot be inspected or cleaned
     */
    private void clearStagedFiles() throws IOException {
        final Path directory = this.config.dalytranInput().getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> contents = Files.list(directory)) {
            for (final Path artefact : contents.toList()) {
                if (Files.isRegularFile(artefact)) {
                    Files.deleteIfExists(artefact);
                }
            }
        }
    }

    /**
     * Writes the sequential input this run reads, one landing record per line.
     *
     * <p>The reader the factory owns is line-oriented over records of a fixed width, exactly as the
     * delivered fixture is laid out, so the records are separated by a single line feed and nothing
     * else. Each image is asserted to be its declared width in encoded bytes before it is written, so a
     * malformed fixture fails here - naming the record - rather than inside the reader.
     *
     * @param  images the landing record images, in the order the run must read them
     * @throws IOException if the input cannot be written
     */
    private void stageInput(final List<String> images) throws IOException {
        for (final String image : images) {
            assertThat(encodedWidth(image))
                    .as("a staged landing record is %d bytes wide", LANDING_RECORD_WIDTH)
                    .isEqualTo(LANDING_RECORD_WIDTH);
            this.stagedRecordIds.add(landingField(image, "DALYTRAN-ID"));
        }
        final Path input = this.config.dalytranInput();
        final Path directory = input.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Files.write(input, images, StandardCharsets.US_ASCII);
    }

    /**
     * One record of the delivered sequential input fixture, taken verbatim.
     *
     * <p>Read from this module's own test resources and never from the legacy tree: reaching outside the
     * module would couple it to that tree and break the standalone-module requirement. The expected
     * record count is named as well as the width, because a wrong width can still divide evenly.
     *
     * @param  ordinal zero-based position in the fixture
     * @return the landing record image, exactly as delivered
     */
    private static String deliveredLandingRecord(final int ordinal) {
        return TestDataFactory.fixedWidthRecords(DAILY_TRANSACTION_FIXTURE, LANDING_RECORD_WIDTH,
                TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT).get(ordinal);
    }

    /**
     * The delivered account fixture record for one account identifier.
     *
     * @param  accountId the eleven-character account identifier
     * @return the three-hundred byte account record image, exactly as delivered
     */
    private static String deliveredAccountRecord(final String accountId) {
        return TestDataFactory.fixedWidthRecords(ACCOUNT_FIXTURE, ACCOUNT_RECORD_WIDTH,
                        TestDataFactory.SEEDED_FIFTY_ROW_COUNT)
                .stream()
                .filter(image -> accountId.equals(
                        TestDataFactory.ACCOUNT.slice(image, "ACCT-ID")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the delivered account fixture "
                        + TestDataFactory.FIXTURE_DIRECTORY + ACCOUNT_FIXTURE
                        + " must carry account " + accountId
                        + ", which this specification's expectations are derived from"));
    }

    /**
     * Returns one field of a landing record image by its layout name.
     *
     * @param  image     the landing record image
     * @param  fieldName the layout's own field name
     * @return the field, at its declared width and untrimmed
     */
    private static String landingField(final String image, final String fieldName) {
        return TestDataFactory.DAILY_TRANSACTION.slice(image, fieldName);
    }

    /**
     * Returns one field of an account record image by its layout name.
     *
     * @param  image     the account record image
     * @param  fieldName the layout's own field name
     * @return the field, at its declared width and untrimmed
     */
    private static String accountField(final String image, final String fieldName) {
        return TestDataFactory.ACCOUNT.slice(image, fieldName);
    }

    /**
     * Restores one account to exactly the amounts and dates the delivered fixture carries for it.
     *
     * <p>This is what makes the refusal in
     * {@link #theRejectGenerationIsByteExactAndTheCascadeOrderIsPreserved()} a refusal from
     * <em>seeded</em> state rather than from a state this specification invented: the credit limit, the
     * expiration date and both cycle accumulators come from the fixture image, decoded by the shared
     * test factory rather than by the module's own codec. Both accumulators are zero across all fifty
     * delivered rows, which is why the basis reduces to the amount alone on a first posting - and why
     * the operand-order probes have to depart from the seed to say anything at all.
     *
     * @param  accountId the account to restore
     * @return the account as it now stands
     */
    private Account pinAccountToDeliveredSeed(final String accountId) {
        final String image = deliveredAccountRecord(accountId);
        final Account account = this.accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("the reference seed must carry account "
                        + accountId + "; the migrations at " + MIGRATION_LOCATION
                        + " apply the delivered account seed and this specification reads it"));
        account.setAcctCurrBal(TestDataFactory.decodeZonedDecimal(
                accountField(image, "ACCT-CURR-BAL")));
        account.setAcctCreditLimit(TestDataFactory.decodeZonedDecimal(
                accountField(image, "ACCT-CREDIT-LIMIT")));
        account.setAcctCurrCycCredit(TestDataFactory.decodeZonedDecimal(
                accountField(image, "ACCT-CURR-CYC-CREDIT")));
        account.setAcctCurrCycDebit(TestDataFactory.decodeZonedDecimal(
                accountField(image, "ACCT-CURR-CYC-DEBIT")));
        account.setAcctExpirationDate(accountField(image, "ACCT-EXPIRAION-DATE"));
        return this.accountRepository.save(account);
    }

    /**
     * Confirms that a card resolves to the account this specification expects, through the
     * cross-reference the member reads first.
     *
     * <p>Asserted rather than assumed, so that a seed whose cross-reference changed says which card and
     * which account disagreed instead of failing later as a mysterious verdict.
     *
     * @param cardNumber       the sixteen-character card number
     * @param expectedAccount  the account the cross-reference must name
     */
    private void requireCrossReference(final String cardNumber, final String expectedAccount) {
        final Optional<CardCrossReference> found =
                this.crossReferenceRepository.findById(cardNumber);
        assertThat(found)
                .as("%s: the seeded cross-reference must carry card %s",
                        TransactionPostingService.XREFFILE_DD, cardNumber)
                .isPresent();
        assertThat(found.orElseThrow().getXrefAcctId())
                .as("%s: card %s resolves to the account this specification derives its expectations"
                        + " from", TransactionPostingService.XREFFILE_DD, cardNumber)
                .isEqualTo(expectedAccount);
    }

    // ---------------------------------------------------------------------------------------------
    // Launching, and reading back what a launch produced.
    // ---------------------------------------------------------------------------------------------

    /**
     * Launches the job under a distinct run identity.
     *
     * <p>The job is resolved by its own published name constant rather than by a literal, because that
     * is the name a launcher and the operational control surface address it by. A distinct identifying
     * parameter is minted per launch so each is its own job instance: the framework refuses a second
     * instance whose identifying parameters were already used, and the parameter this key carries is
     * this specification's own and is not the job's - the member passes no parameter string at all,
     * which {@link #theJobRunsWithNoParameterAtAllAndReportsTheToleratedCompletionCode()} proves by
     * launching with an entirely empty set.
     *
     * @return the terminal job execution
     * @throws Exception if the launcher refuses the launch
     */
    private JobExecution launch() throws Exception {
        final JobParameters parameters = new JobParametersBuilder()
                .addLong(RUN_KEY, RUN_IDENTITY.incrementAndGet())
                .toJobParameters();
        return this.jobLauncher.run(this.postTransactionJob, parameters);
    }

    /**
     * The one step execution of a terminal job execution, asserted to be the only one.
     *
     * <p>Being the only one is itself part of the contract twice over: the member declares one
     * application step, and a partitioned or multi-threaded step would surface here as further step
     * executions of its own.
     *
     * @param  execution the terminal job execution
     * @return its single step execution
     */
    private static StepExecution soleStepExecution(final JobExecution execution) {
        assertThat(execution.getStepExecutions())
                .as("the member declares exactly ONE application step, %s, and this job is strictly"
                        + " sequential - no partition, no task executor, no parallel flow, each of"
                        + " which would contribute further step executions",
                        TransactionValidationProcessor.LEGACY_STEP)
                .hasSize(1);
        final StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getStepName())
                .as("the single step carries the name the job starts it by")
                .isEqualTo(PostTransactionJobConfig.STEP_NAME);
        return step;
    }

    /**
     * Reads the sealed reject generation one execution owns.
     *
     * <p>The generation is named by the production configuration from the execution identifier the
     * framework assigned, so no path is composed here and two executions cannot resolve one file.
     *
     * @param  execution the completed job execution
     * @return the whole generation as bytes
     * @throws IOException if the generation cannot be read
     */
    private byte[] rejectGeneration(final JobExecution execution) throws IOException {
        final Path generation = this.config.rejectGeneration(execution.getId().longValue());
        assertThat(generation)
                .as("%s: a completed run seals its generation, which is what the durable store then"
                        + " publishes", TransactionPostingService.DALYREJS_DD)
                .isRegularFile();
        return Files.readAllBytes(generation);
    }

    /**
     * Splits a whole reject generation into its records, insisting on an exact multiple of the width.
     *
     * <p>The format is fixed-length <strong>unblocked</strong>, so nothing pads the artefact and no
     * separator divides it. A total that is not an exact multiple therefore means either a separator was
     * written or a record was not its declared width, and both are byte-parity defects.
     *
     * @param  artefact the whole generation
     * @return its records, in the order they were written
     */
    private static List<byte[]> rejectRecordsOf(final byte[] artefact) {
        assertThat(artefact.length % REJECT_RECORD_WIDTH)
                .as("%s is fixed-length UNBLOCKED, so the artefact is an exact multiple of %d bytes"
                        + " with nothing appended and no separator anywhere, but it measures %d",
                        TransactionPostingService.DALYREJS_DD, REJECT_RECORD_WIDTH, artefact.length)
                .isZero();
        final List<byte[]> records = new ArrayList<>(artefact.length / REJECT_RECORD_WIDTH);
        for (int offset = 0; offset < artefact.length; offset += REJECT_RECORD_WIDTH) {
            records.add(Arrays.copyOfRange(artefact, offset, offset + REJECT_RECORD_WIDTH));
        }
        return records;
    }

    /**
     * The expected reject record for one landing image and one reason, derived independently.
     *
     * <p>Assembled by the shared test factory from the three widths - the landing image copied verbatim,
     * a four-digit zero-filled reason code, a seventy-six character blank-padded description - with
     * ordinary string arithmetic. The writer's own image builder, the record mappers and the decimal
     * codec are deliberately not consulted: an expectation produced by the code under test cannot detect
     * a defect in that code, and a codec fault would otherwise produce a matching input and a matching
     * expectation at once.
     *
     * @param  landingImage the landing record image as it was staged
     * @param  reason       the reason the record is expected to be refused with
     * @return the expected four-hundred-and-thirty US-ASCII bytes
     */
    private static byte[] expectedRejectRecord(final String landingImage, final RejectReason reason) {
        final String expected = TestDataFactory.rejectRecordImage(landingImage, reason);
        assertThat(encodedWidth(expected))
                .as("the independently derived expectation is itself %d bytes: %d plus %d plus %d",
                        REJECT_RECORD_WIDTH, LANDING_RECORD_WIDTH, REASON_CODE_WIDTH,
                        REASON_DESCRIPTION_WIDTH)
                .isEqualTo(REJECT_RECORD_WIDTH);
        return expected.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * The encoded width of a value, which is the only width a fixed-width contract is measured in.
     *
     * <p>Character count is not the same measurement and is not used anywhere here: a record width is a
     * count of bytes, and the two diverge the moment a value leaves US-ASCII.
     *
     * @param  value the value to measure
     * @return its length in encoded US-ASCII bytes
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * How many instances of this job the shared server has recorded.
     *
     * <p>The count is read defensively because the server is shared for the whole build: a neighbouring
     * specification may have launched this job before this one ran, and the metadata reports no
     * instances at all as a checked absence rather than as zero. What matters here is never the absolute
     * figure but the difference across one launch, which is what
     * {@link #theJobRunsWithNoParameterAtAllAndReportsTheToleratedCompletionCode()} measures.
     *
     * @return the recorded instance count, or zero when the job has none
     */
    private long recordedJobInstanceCount() {
        try {
            return this.jobExplorer.getJobInstanceCount(PostTransactionJobConfig.JOB_NAME);
        } catch (final NoSuchJobException noInstancesYet) {
            return 0L;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The input this specification stages, and the state it is read against.
    // ---------------------------------------------------------------------------------------------

    /**
     * A constructed record whose card resolves to nothing, carrying an amount that would breach any
     * seeded limit.
     *
     * <p>The amount is deliberately over limit. The account stage is guarded on the reason code still
     * being zero, so it never runs for this record and the amount is never measured against a limit -
     * which is exactly what makes the resulting code a discriminator: an implementation that ran the
     * account stage regardless would set the over-limit code instead.
     *
     * <p>Constructing this record is legitimate only because the landing table carries no referential
     * constraint of any kind - a deliberate absence, so that a row naming an unresolvable card can be
     * stored and then refused, which is the only route to a reject record at all.
     *
     * @return the landing record image
     */
    private static String unresolvedCardRecord() {
        return TestDataFactory.dailyTransactionRejectedBy(RejectReason.INVALID_CARD_NUMBER)
                .id(UNRESOLVED_CARD_RECORD_ID)
                .amount(TestDataFactory.OVERLIMIT_AMOUNT)
                .image();
    }

    /**
     * A constructed record that arrives after its account expired, and is otherwise unremarkable.
     *
     * <p>Its amount is modest, so the over-limit test passes and the only reason available is the expiry
     * one. Every delivered account expires later than the fixture's own origination date, which is why
     * this reason cannot be reached from the delivered records and has to be constructed.
     *
     * @return the landing record image
     */
    private static String expiredArrivalRecord() {
        return TestDataFactory.dailyTransaction()
                .id(EXPIRED_ARRIVAL_RECORD_ID)
                .cardNumber(EXPIRED_ARRIVAL_CARD)
                .amount(MODEST_AMOUNT)
                .originalTimestamp(TestDataFactory.POST_EXPIRY_TIMESTAMP)
                .image();
    }

    /**
     * A constructed record that is over limit <em>and</em> past expiry at once.
     *
     * <p>This is the record the unguarded second check decides. The over-limit code is written first and
     * the expiry code overwrites it, because the two blocks are consecutive and the second is not an
     * alternative of the first. Guarding the second block, or swapping the two, would emit the
     * over-limit code here and the byte comparison would fail.
     *
     * @return the landing record image
     */
    private static String doublyInvalidRecord() {
        return TestDataFactory.dailyTransaction()
                .id(DOUBLY_INVALID_RECORD_ID)
                .cardNumber(OVERLIMIT_CARD)
                .amount(TestDataFactory.OVERLIMIT_AMOUNT)
                .originalTimestamp(TestDataFactory.POST_EXPIRY_TIMESTAMP)
                .image();
    }

    /**
     * The six records this specification's main run reads, in the order it reads them.
     *
     * <p>Three are delivered fixture records taken verbatim - a purchase that posts through the
     * category-balance update arm, a return that posts through its create arm, and the record the seeded
     * account state refuses as over limit. Three are constructed, because the reasons they reach are not
     * reachable from delivered data at all.
     *
     * @return the landing record images in arrival order
     */
    private static List<String> theMixedInput() {
        return List.of(deliveredLandingRecord(POSTING_PURCHASE_ORDINAL),
                deliveredLandingRecord(POSTING_RETURN_ORDINAL),
                deliveredLandingRecord(SEEDED_OVERLIMIT_ORDINAL),
                unresolvedCardRecord(),
                expiredArrivalRecord(),
                doublyInvalidRecord());
    }

    /**
     * Brings the four accounts this run touches to exactly the state the delivered seed carries, and
     * clears the one category-balance key whose creation is asserted.
     *
     * <p>The cross-references are confirmed rather than assumed, so a changed seed says which card
     * disagreed. The card that resolves to nothing is confirmed to resolve to nothing, which is the
     * precondition of the invalid-card reason.
     */
    private void prepareDeliveredSeededState() {
        requireCrossReference(PURCHASE_CARD, PURCHASE_ACCOUNT);
        requireCrossReference(RETURN_CARD, RETURN_ACCOUNT);
        requireCrossReference(OVERLIMIT_CARD, OVERLIMIT_ACCOUNT);
        requireCrossReference(EXPIRED_ARRIVAL_CARD, EXPIRED_ARRIVAL_ACCOUNT);

        assertThat(this.crossReferenceRepository.findById(TestDataFactory.UNKNOWN_CARD_NUMBER))
                .as("%s: the card the invalid-card reason depends on must resolve to nothing",
                        TransactionPostingService.XREFFILE_DD)
                .isEmpty();

        pinAccountToDeliveredSeed(PURCHASE_ACCOUNT);
        pinAccountToDeliveredSeed(RETURN_ACCOUNT);
        pinAccountToDeliveredSeed(OVERLIMIT_ACCOUNT);
        pinAccountToDeliveredSeed(EXPIRED_ARRIVAL_ACCOUNT);

        this.categoryBalanceRepository.deleteAllById(List.of(returnCategoryBalanceKey()));
    }

    /** The category-balance key the return record creates, which the seed does not carry. */
    private static TransactionCategoryBalanceId returnCategoryBalanceKey() {
        return new TransactionCategoryBalanceId(RETURN_ACCOUNT, RETURN_TYPE_CODE,
                RETURN_CATEGORY_CODE);
    }

    /** The category-balance key the purchase record updates, which the seed does carry. */
    private static TransactionCategoryBalanceId purchaseCategoryBalanceKey() {
        return new TransactionCategoryBalanceId(PURCHASE_ACCOUNT, PURCHASE_TYPE_CODE,
                PURCHASE_CATEGORY_CODE);
    }

    /** The account as it now stands, insisting the row is there. */
    private Account accountNow(final String accountId) {
        return this.accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException(
                        TransactionPostingService.ACCTFILE_DD + ": account " + accountId
                                + " must still exist after the run"));
    }

    // =============================================================================================
    // THE SPECIFICATIONS
    // =============================================================================================

    @Test
    @Order(1)
    @DisplayName("the job is reachable under the name a launcher addresses it by, and nothing in this "
            + "context can launch it at start-up")
    void theJobIsReachableByNameAndNothingLaunchesItAtStartUp() {
        assertThat(this.jobRegistry.getJobNames())
                .as("the operational control surface launches and queries by name through the registry,"
                        + " so an unstable name would break that surface rather than fail a compile")
                .contains(PostTransactionJobConfig.JOB_NAME);
        assertThat(this.postTransactionJob.getName())
                .isEqualTo(PostTransactionJobConfig.JOB_NAME);

        assertThat(this.environment.getProperty("spring.batch.job.enabled"))
                .as("launch-on-start is disabled for the whole suite, so refreshing a context runs"
                        + " nothing")
                .isEqualTo("false");
        assertThat(this.environment.getProperty("spring.batch.job.name"))
                .as("no job is nominated to run at start-up, and this configuration never sets one")
                .isNull();

        assertThat(this.applicationContext.getBeanNamesForType(ApplicationRunner.class))
                .as("a start-up runner is the only mechanism by which a refresh could launch a job, and"
                        + " this context publishes none")
                .isEmpty();
        assertThat(this.applicationContext.getBeanNamesForType(CommandLineRunner.class))
                .as("nor the command-line form of the same mechanism")
                .isEmpty();

        assertThat(this.jobExplorer.findRunningJobExecutions(PostTransactionJobConfig.JOB_NAME))
                .as("nothing is running merely because the context refreshed - and had a refresh"
                        + " launched this job it could not even have opened its input, because this"
                        + " specification stages that input inside each test and the reader is strict")
                .isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("a run with no parameter at all completes with one step and reports the tolerated "
            + "completion code for the records it refused")
    void theJobRunsWithNoParameterAtAllAndReportsTheToleratedCompletionCode() throws Exception {
        prepareDeliveredSeededState();
        final List<String> input = theMixedInput();
        stageInput(input);
        final long instancesBefore = recordedJobInstanceCount();

        // The member passes no parameter string and declares no condition-code dependency, so an
        // ENTIRELY EMPTY parameter set has to be accepted. No date, no mode, no run date: had the job
        // acquired a required parameter, this launch would be refused before a record was read.
        final JobExecution execution =
                this.jobLauncher.run(this.postTransactionJob, new JobParameters());

        assertThat(execution.getJobParameters().getParameters())
                .as("the launch carried no parameter of any kind")
                .isEmpty();
        assertThat(recordedJobInstanceCount())
                .as("the launch, and only the launch, brought an instance of this job into existence")
                .isEqualTo(instancesBefore + 1L);

        assertThat(execution.getStatus())
                .as("REFUSED RECORDS ARE A PARTIAL SUCCESS, NOT A FAILURE. The member reaches its"
                        + " completion-code line only after closing every file and reporting both"
                        + " counts, and the run itself ends normally. Failure reasons, if any: %s",
                        execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getAllFailureExceptions())
                .as("nothing is thrown for a refusal; a reject code is a verdict on content")
                .isEmpty();

        final StepExecution step = soleStepExecution(execution);
        assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step.getReadCount()).isEqualTo(input.size());
        assertThat(step.getWriteCount())
                .as("only refusals reach the writer, so its write count is the reject count")
                .isEqualTo(4L);
        assertThat(step.getFilterCount())
                .as("a posted record is filtered rather than written, which is precisely the arm of"
                        + " the legacy branch that emits no reject record")
                .isEqualTo(2L);

        assertThat(step.getExitStatus().getExitCode())
                .as("the completion code travels in the step's exit code, and the digits deliberately"
                        + " spell no framework status name so that they survive being combined")
                .isEqualTo(TransactionValidationProcessor.EXIT_CODE_REJECTS_PRESENT);
        assertThat(execution.getExitStatus().getExitCode())
                .as("THE JOB'S OWN EXIT CODE IS THE STEP'S. That is the runtime discriminator for the"
                        + " absence of a failure-ending flow transition: a job built as a flow with an"
                        + " on-failed end would report the flow's verdict here instead, so the step's"
                        + " tolerated code reaching the job proves nothing intercepted it")
                .isEqualTo(TransactionValidationProcessor.EXIT_CODE_REJECTS_PRESENT);

        final ExecutionContext context = step.getExecutionContext();
        assertThat(context.getLong(
                TransactionValidationProcessor.EXECUTION_CONTEXT_TRANSACTIONS_PROCESSED))
                .as("the counter the mainline increments once per record read")
                .isEqualTo(6L);
        assertThat(context.getLong(
                TransactionValidationProcessor.EXECUTION_CONTEXT_TRANSACTIONS_POSTED))
                .as("the figure the legacy carries implicitly, as the difference of its two counters")
                .isEqualTo(2L);
        assertThat(context.getLong(
                TransactionValidationProcessor.EXECUTION_CONTEXT_TRANSACTIONS_REJECTED))
                .as("the counter incremented once immediately before each reject record is written")
                .isEqualTo(4L);
        assertThat(context.getInt(TransactionValidationProcessor.EXECUTION_CONTEXT_RETURN_CODE))
                .as("the completion code as a number, which is how a gate reasons about one")
                .isEqualTo(TransactionPostingService.RETURN_CODE_REJECTS_PRESENT);

        // WHERE THE BUSINESS RULES LIVE. These four figures and this completion code are published by
        // the per-record stage, not by the job configuration: the configuration's own listener
        // contributes no exit status at all, so an exit code that IS the stage's constant can only have
        // come from the stage. The cascade, the arithmetic and both timestamp forms likewise belong to
        // the translated program. Reflection is not used to inspect the configuration - the run itself
        // says where the decisions were taken.
        assertThat(step.getExitStatus().getExitDescription())
                .as("the stage describes the run it decided, naming the counts it decided it from")
                .contains("4")
                .contains("6");
    }

    @Test
    @Order(3)
    @DisplayName("the reject generation is an exact multiple of 430 unblocked bytes and every record "
            + "matches an independently derived image byte for byte")
    void theRejectGenerationIsByteExactAndTheCascadeOrderIsPreserved() throws Exception {
        prepareDeliveredSeededState();
        final List<String> input = theMixedInput();
        stageInput(input);

        final JobExecution execution = launch();
        assertThat(execution.getStatus())
                .as("failure reasons, if any: %s", execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getJobParameters().getParameters().keySet())
                .as("THE JOB TAKES NO DATE PARAMETER, and no parameter of its own at all: the member"
                        + " passes no parameter string, so the only key a launch can carry here is this"
                        + " specification's own run identity and, were the framework to add one, its"
                        + " own run identifier")
                .isSubsetOf(ALLOWED_PARAMETER_KEYS);

        final byte[] artefact = rejectGeneration(execution);
        final List<byte[]> produced = rejectRecordsOf(artefact);

        assertThat(artefact.length)
                .as("%s carries one %d-byte record per refusal and nothing else - no block padding, no"
                        + " separator, no terminator", TransactionPostingService.DALYREJS_DD,
                        REJECT_RECORD_WIDTH)
                .isEqualTo(REJECT_RECORD_WIDTH * 4);
        assertThat(produced).hasSize(4);

        // ARRIVAL ORDER IS PART OF THE RESULT, which is why the step is strictly sequential. The refusals
        // appear in the order the records were read, and the over-limit verdict measures cycle totals that
        // earlier records of the same run have already moved.
        final List<byte[]> expected = List.of(
                expectedRejectRecord(input.get(2), RejectReason.OVERLIMIT_TRANSACTION),
                expectedRejectRecord(input.get(3), RejectReason.INVALID_CARD_NUMBER),
                expectedRejectRecord(input.get(4),
                        RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION),
                expectedRejectRecord(input.get(5),
                        RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION));

        for (int ordinal = 0; ordinal < expected.size(); ordinal++) {
            assertThat(produced.get(ordinal))
                    .as("reject record %d compared byte for byte over all %d bytes - never trimmed, so"
                            + " a stray blank or a wrong sign byte fails rather than passing"
                            + " unnoticed", ordinal + 1, REJECT_RECORD_WIDTH)
                    .isEqualTo(expected.get(ordinal));
        }

        // The leading segment of the first record is a DELIVERED fixture record, so the strongest
        // reading of "the source image is copied unchanged" is available: compare it against the
        // fixture's own bytes rather than against anything re-rendered.
        assertThat(Arrays.copyOfRange(produced.get(0), 0, LANDING_RECORD_WIDTH))
                .as("bytes 1 to %d are the delivered landing image copied UNCHANGED",
                        LANDING_RECORD_WIDTH)
                .isEqualTo(input.get(2).getBytes(StandardCharsets.US_ASCII));

        final List<String> reasonFields = produced.stream().map(PostTransactionJobConfigIT::reasonField)
                .toList();
        assertThat(reasonFields)
                .as("the four-digit reason fields, in arrival order: the seeded over-limit refusal,"
                        + " then the unresolved card, then the two records that arrived after their"
                        + " account expired")
                .containsExactly("0102", "0100", "0103", "0103");

        assertThat(reasonFields.get(1))
                .as("THE ACCOUNT STAGE IS SHORT-CIRCUITED. The account lookup is guarded on the reason"
                        + " code still being zero, so a record whose card resolves to nothing carries"
                        + " the invalid-card code and none of the codes the account stage can set -"
                        + " even though this record's amount would breach any seeded limit")
                .isEqualTo(fourDigitForm(RejectReason.INVALID_CARD_NUMBER));

        assertThat(reasonFields.get(3))
                .as("A DOUBLY INVALID RECORD ENDS AS THE EXPIRY CODE. The two blocks are consecutive"
                        + " and UNGUARDED: the over-limit code is written first and this one overwrites"
                        + " it. Guarding the second block, or swapping the two, would emit %s here",
                        fourDigitForm(RejectReason.OVERLIMIT_TRANSACTION))
                .isEqualTo(fourDigitForm(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION))
                .isNotEqualTo(fourDigitForm(RejectReason.OVERLIMIT_TRANSACTION));

        assertThat(reasonFields)
                .as("NO REJECT RECORD CAN EVER BEAR THE ACCOUNT-REWRITE CODE. It is set downstream of"
                        + " the posting mainline, on a record that has already posted, and the routing"
                        + " decision is taken on whether the record posted rather than on whether it"
                        + " carries a reason code. That arm is entered POSITIVELY in"
                        + " RejectReasonArmsIT, which watches the run set the code and then post the"
                        + " record anyway - so this absence is a consequence of inertness rather than"
                        + " of the arm never being reached")
                .doesNotContain(fourDigitForm(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE));
        assertThat(reasonFields)
                .as("and none bears the account-read code either, because the cross-reference table's"
                        + " foreign key to the account table makes the dangling row impossible in the"
                        + " migrated schema - a documented strengthening rather than a lost behaviour."
                        + " Note precisely what that argues: the SCHEMA cannot produce the state, not"
                        + " that the CODE PATH is unreachable. RejectReasonArmsIT enters it by making"
                        + " the account read itself report the absence the legacy INVALID KEY arm"
                        + " reported, and watches a real 430-byte record carrying %s leave the system",
                        fourDigitForm(RejectReason.ACCOUNT_NOT_FOUND_ON_READ))
                .doesNotContain(fourDigitForm(RejectReason.ACCOUNT_NOT_FOUND_ON_READ));

        // A REFUSED RECORD POSTS NOTHING. The posting mainline runs only when the reason code is zero,
        // so neither refused account moved and no posted row appeared for a refused identifier.
        assertThat(accountNow(OVERLIMIT_ACCOUNT).getAcctCurrCycCredit())
                .as("%s: the over-limit account's cycle credit is untouched",
                        TransactionPostingService.ACCTFILE_DD)
                .isEqualByComparingTo(ZERO_MONEY);
        assertThat(this.transactionRepository.findById(
                landingField(input.get(2), "DALYTRAN-ID")))
                .as("%s: a refused record writes no posted row",
                        TransactionPostingService.TRANFILE_DD)
                .isEmpty();
        assertThat(this.transactionRepository.findById(DOUBLY_INVALID_RECORD_ID)).isEmpty();
        assertThat(this.transactionRepository.findById(UNRESOLVED_CARD_RECORD_ID)).isEmpty();
        assertThat(this.transactionRepository.findById(EXPIRED_ARRIVAL_RECORD_ID)).isEmpty();
    }

    /**
     * The four-digit reason field of one produced reject record.
     *
     * @param  record the whole four-hundred-and-thirty byte record
     * @return its four-digit numeric reason field
     */
    private static String reasonField(final byte[] record) {
        return new String(record, LANDING_RECORD_WIDTH, REASON_CODE_WIDTH,
                StandardCharsets.US_ASCII);
    }

    /**
     * The four-digit zero-filled form of one reason code, taken from the transcription of the legacy
     * source rather than from the shipped constant that was passed in.
     *
     * <p>This helper used to read {@code reason.getReasonCode()}, which made every comparison built on it
     * a comparison of the implementation against itself: a code recorded wrongly in the enumeration
     * produced a wrongly-agreeing expectation and a passing test over a file no consumer could read. It now
     * names the constant and asks {@link LegacyRejectReasons} which four digits the legacy source moves at
     * the site that constant stands for, so a drift in the enumeration changes the produced field without
     * moving the expectation.
     *
     * @param  reason the reason, used to name the transcribed entry rather than to supply its value
     * @return its four-digit form as the legacy source sets it
     */
    private static String fourDigitForm(final RejectReason reason) {
        return TestDataFactory.transcribedReasonFor(reason).fourDigitCode();
    }

    @Test
    @Order(4)
    @DisplayName("a posting run runs its three stages in order, copies the origination timestamp "
            + "verbatim, regenerates the processing timestamp in the batch form, and drives the cycle "
            + "debit negative with the amount unchanged")
    void aPostingRunPostsInOrderAndStampsBothTimestampsCorrectly() throws Exception {
        prepareDeliveredSeededState();
        final String purchaseImage = deliveredLandingRecord(POSTING_PURCHASE_ORDINAL);
        final String returnImage = deliveredLandingRecord(POSTING_RETURN_ORDINAL);
        stageInput(List.of(purchaseImage, returnImage));

        // THE INPUT'S PROCESSING TIMESTAMP IS BLANK BEFORE THE RUN, on every delivered record. Only the
        // origination timestamp carries a value, which is why the posting stage has to regenerate the
        // other one and why a window applied to it would select nothing from delivered data.
        assertThat(landingField(purchaseImage, "DALYTRAN-PROC-TS"))
                .as("%s: the twenty-six byte processing timestamp is entirely blank on input",
                        TransactionPostingService.DALYTRAN_DD)
                .isEqualTo(TestDataFactory.BLANK_PROCESSING_TIMESTAMP);
        assertThat(landingField(returnImage, "DALYTRAN-PROC-TS"))
                .isEqualTo(TestDataFactory.BLANK_PROCESSING_TIMESTAMP);

        final String purchaseId = landingField(purchaseImage, "DALYTRAN-ID");
        final String returnId = landingField(returnImage, "DALYTRAN-ID");
        final BigDecimal purchaseBalanceBefore = accountNow(PURCHASE_ACCOUNT).getAcctCurrBal();
        final BigDecimal returnBalanceBefore = accountNow(RETURN_ACCOUNT).getAcctCurrBal();
        final BigDecimal purchaseCategoryBalanceBefore =
                this.categoryBalanceRepository.findById(purchaseCategoryBalanceKey())
                        .map(TransactionCategoryBalance::getTranCatBal)
                        .orElseThrow(() -> new IllegalStateException(
                                TransactionPostingService.TCATBALF_DD + ": the reference seed must"
                                        + " carry the category balance the update arm needs"));
        assertThat(this.categoryBalanceRepository.findById(returnCategoryBalanceKey()))
                .as("%s: the key the create arm is about to create must be absent beforehand, or the"
                        + " creation is not what is being observed",
                        TransactionPostingService.TCATBALF_DD)
                .isEmpty();

        final JobExecution execution = launch();
        assertThat(execution.getStatus())
                .as("failure reasons, if any: %s", execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        final StepExecution step = soleStepExecution(execution);
        assertThat(step.getReadCount()).isEqualTo(2L);
        assertThat(step.getWriteCount()).isZero();
        assertThat(step.getExitStatus().getExitCode())
                .as("A RUN THAT REFUSED NOTHING RAISES NO COMPLETION CODE. The member moves the"
                        + " warning-level code only when the reject count exceeds zero, so the step"
                        + " keeps the plain completed verdict here - the exact contrast with the run"
                        + " that did refuse records")
                .isEqualTo(BatchStatus.COMPLETED.name());
        assertThat(step.getExecutionContext()
                .getInt(TransactionValidationProcessor.EXECUTION_CONTEXT_RETURN_CODE))
                .isEqualTo(TransactionPostingService.RETURN_CODE_SUCCESS);

        final byte[] artefact = rejectGeneration(execution);
        assertThat(rejectRecordsOf(artefact))
                .as("%s: a run that refused nothing leaves an empty generation, and empty means"
                        + " exactly zero bytes - a fixed-length unblocked dataset is not padded up to a"
                        + " block", TransactionPostingService.DALYREJS_DD)
                .isEmpty();
        assertThat(artefact).isEmpty();

        // ---- POSTING ORDER: category balance, then account, then the transaction file. ----
        final TransactionCategoryBalance createdBalance =
                this.categoryBalanceRepository.findById(returnCategoryBalanceKey())
                        .orElseThrow(() -> new AssertionError(
                                TransactionPostingService.TCATBALF_DD + ": a key that matched no row"
                                        + " is CREATED rather than treated as an error - the read"
                                        + " status for a missing key is a non-error status in the"
                                        + " member"));
        assertThat(createdBalance.getTranCatBal())
                .as("the created row opens at the amount of the record that created it")
                .isEqualByComparingTo(RETURN_AMOUNT);
        assertThat(this.categoryBalanceRepository.findById(purchaseCategoryBalanceKey())
                .orElseThrow().getTranCatBal())
                .as("%s: an existing row is updated in place by the same amount",
                        TransactionPostingService.TCATBALF_DD)
                .isEqualByComparingTo(purchaseCategoryBalanceBefore.add(PURCHASE_AMOUNT));

        final Transaction postedPurchase = this.transactionRepository.findById(purchaseId)
                .orElseThrow(() -> new AssertionError(TransactionPostingService.TRANFILE_DD
                        + ": the third posting stage writes the posted record"));
        final Transaction postedReturn = this.transactionRepository.findById(returnId)
                .orElseThrow(() -> new AssertionError(TransactionPostingService.TRANFILE_DD
                        + ": the return record posts as well as the purchase"));

        // The three stages run in that fixed order, each in a durable unit of its own, so a posted row can
        // exist only if the category balance and the account rewrite that precede it both completed and
        // committed. Observing the row is therefore observing the whole ordered cascade. What it does NOT
        // imply is the converse - the two earlier stores can exist without the posted row, which is the
        // property the refused-final-store specification below measures directly.
        assertThat(this.categoryBalanceRepository.findById(returnCategoryBalanceKey()))
                .as("the category balance exists whenever the posted row does, which is the observable"
                        + " form of the ordering")
                .isPresent();

        assertThat(SensitiveValues.fingerprint(postedPurchase.getTranCardNum())).isEqualTo(SensitiveValues.fingerprint(PURCHASE_CARD));
        assertThat(postedPurchase.getTranAmt()).isEqualByComparingTo(PURCHASE_AMOUNT);
        assertThat(postedReturn.getTranAmt()).isEqualByComparingTo(RETURN_AMOUNT);

        // ---- THE ORIGINATION TIMESTAMP IS COPIED VERBATIM. ----
        final String deliveredOrigination = landingField(purchaseImage, "DALYTRAN-ORIG-TS");
        assertThat(postedPurchase.getTranOrigTs())
                .as("the origination timestamp records when the transaction happened, which posting"
                        + " does not change, so it is carried across character for character and is"
                        + " never parsed, normalised, re-zoned or reformatted")
                .isEqualTo(deliveredOrigination)
                .isEqualTo(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP);
        assertThat(encodedWidth(postedPurchase.getTranOrigTs())).isEqualTo(TIMESTAMP_WIDTH);

        // ---- THE PROCESSING TIMESTAMP IS REGENERATED, IN THE BATCH FORM. ----
        final String processing = postedPurchase.getTranProcTs();
        assertThat(encodedWidth(processing))
                .as("exactly %d encoded bytes", TIMESTAMP_WIDTH)
                .isEqualTo(TIMESTAMP_WIDTH);
        assertThat(processing)
                .as("the pinned clock makes the regenerated value deterministic, so it is asserted"
                        + " exactly rather than by shape alone")
                .isEqualTo(EXPECTED_BATCH_PROCESSING_TIMESTAMP);
        assertThat(processing.charAt(BATCH_FORM_THIRD_HYPHEN_POSITION - 1))
                .as("POSITION %d IS A HYPHEN IN THE BATCH FORM. The online form of the same width"
                        + " carries a space here, so this one character is the difference between two"
                        + " values that both look right", BATCH_FORM_THIRD_HYPHEN_POSITION)
                .isEqualTo('-');
        assertThat(processing)
                .as("the batch form ends with a literal tail rather than with a six-digit fraction,"
                        + " and separates its time parts with dots rather than colons")
                .endsWith(BATCH_FORM_TAIL)
                .doesNotContain(":")
                .doesNotContain(" ");
        assertThat(processing)
                .as("and it is NOT the value the record arrived with, which is in the other form")
                .isNotEqualTo(deliveredOrigination);
        assertThat(postedReturn.getTranProcTs())
                .as("every record of one run is stamped from the same clock reading")
                .isEqualTo(EXPECTED_BATCH_PROCESSING_TIMESTAMP);

        // ---- THE SIGN TRAP. ----
        final Account purchaseAccount = accountNow(PURCHASE_ACCOUNT);
        assertThat(purchaseAccount.getAcctCurrBal())
                .isEqualByComparingTo(purchaseBalanceBefore.add(PURCHASE_AMOUNT));
        assertThat(purchaseAccount.getAcctCurrCycCredit())
                .as("a non-negative amount joins the cycle CREDIT")
                .isEqualByComparingTo(PURCHASE_AMOUNT);
        assertThat(purchaseAccount.getAcctCurrCycDebit())
                .as("and leaves the cycle debit alone")
                .isEqualByComparingTo(ZERO_MONEY);

        final Account returnAccount = accountNow(RETURN_ACCOUNT);
        assertThat(returnAccount.getAcctCurrBal())
                .as("the amount joins the balance in its own direction, so a return reduces it")
                .isEqualByComparingTo(returnBalanceBefore.add(RETURN_AMOUNT));
        assertThat(returnAccount.getAcctCurrCycDebit())
                .as("A NEGATIVE AMOUNT JOINS THE CYCLE DEBIT UNCHANGED - not negated, not made"
                        + " absolute - so the debit total goes NEGATIVE. Fifty of the three hundred"
                        + " delivered records are in this direction, so the behaviour is reached by"
                        + " delivered data and not only by construction")
                .isEqualByComparingTo(RETURN_AMOUNT)
                .isNegative();
        assertThat(returnAccount.getAcctCurrCycCredit())
                .as("and leaves the cycle credit alone")
                .isEqualByComparingTo(ZERO_MONEY);

        // ---- THE STEP TIMER. Presence and shape only: no elapsed-time, throughput or capacity figure
        // is asserted, because none exists in the legacy estate to compare against. ----
        final List<Meter> stepTimers = this.meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getType() == Meter.Type.TIMER)
                .filter(meter -> PostTransactionJobConfig.JOB_NAME.equals(
                        meter.getId().getTag("batchJob")))
                .filter(meter -> PostTransactionJobConfig.STEP_NAME.equals(
                        meter.getId().getTag("step")))
                .toList();
        assertThat(stepTimers)
                .as("the step publishes an elapsed-time observation tagged with the job and the step,"
                        + " which is the measurement surface a baseline is read from")
                .isNotEmpty();
        assertThat(stepTimers)
                .allSatisfy(meter -> assertThat(meter.getId().getTag("outcome"))
                        .as("each observation carries the terminal status it belongs to")
                        .isNotNull());
    }

    @Test
    @Order(5)
    @DisplayName("the over-limit basis is cycle credit less cycle debit plus the amount, evaluated "
            + "strictly left to right, and the pair of verdicts pins every operand's sign")
    void theOverLimitBasisIsEvaluatedStrictlyLeftToRight() throws Exception {
        requireCrossReference(OVERLIMIT_CARD, OVERLIMIT_ACCOUNT);
        requireCrossReference(EXPIRED_ARRIVAL_CARD, EXPIRED_ARRIVAL_ACCOUNT);

        // BOTH DELIVERED CYCLE ACCUMULATORS ARE ZERO ON ALL FIFTY SEEDED ROWS, so the basis reduces to
        // the amount alone and delivered state cannot distinguish one operand order from another. These
        // two probes therefore depart from the seed deliberately, and they depart in the one way that
        // makes the arithmetic observable: the two accumulators are swapped between them while the limit
        // and the amount stay identical.
        //
        //   probe A: 50.00 - 20.00 + 75.00 = 105.00 > 100.00  -> REFUSED
        //   probe B: 20.00 - 50.00 + 75.00 =  45.00 <= 100.00 -> POSTED
        //
        // A reading of debit-less-credit would refuse B and accept A, inverting BOTH verdicts. A reading
        // that added the two accumulators would compute 145.00 for both and refuse both. Asserting the
        // PAIR is what pins each operand's sign; asserting either alone would not.
        pinProbeAccount(OVERLIMIT_ACCOUNT, PROBE_LARGER_ACCUMULATOR, PROBE_SMALLER_ACCUMULATOR);
        pinProbeAccount(EXPIRED_ARRIVAL_ACCOUNT, PROBE_SMALLER_ACCUMULATOR, PROBE_LARGER_ACCUMULATOR);

        final String refusedProbe = probeRecord(BASIS_REFUSED_RECORD_ID, OVERLIMIT_CARD);
        final String acceptedProbe = probeRecord(BASIS_ACCEPTED_RECORD_ID, EXPIRED_ARRIVAL_CARD);
        stageInput(List.of(refusedProbe, acceptedProbe));

        final JobExecution execution = launch();
        assertThat(execution.getStatus())
                .as("failure reasons, if any: %s", execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        final List<byte[]> produced = rejectRecordsOf(rejectGeneration(execution));
        assertThat(produced)
                .as("exactly one of the two probes is refused; if both or neither were, the operand"
                        + " order is wrong")
                .hasSize(1);
        assertThat(produced.get(0))
                .as("and the refused one is the probe whose credit exceeds its debit, refused with the"
                        + " over-limit reason")
                .isEqualTo(expectedRejectRecord(refusedProbe, RejectReason.OVERLIMIT_TRANSACTION));

        assertThat(this.transactionRepository.findById(BASIS_ACCEPTED_RECORD_ID))
                .as("%s: the probe the basis accepts posts", TransactionPostingService.TRANFILE_DD)
                .isPresent();
        assertThat(this.transactionRepository.findById(BASIS_REFUSED_RECORD_ID))
                .as("%s: the probe the basis refuses does not",
                        TransactionPostingService.TRANFILE_DD)
                .isEmpty();

        final Account refusedAccount = accountNow(OVERLIMIT_ACCOUNT);
        assertThat(refusedAccount.getAcctCurrCycCredit())
                .as("a refused record leaves its account exactly as it found it")
                .isEqualByComparingTo(PROBE_LARGER_ACCUMULATOR);
        assertThat(refusedAccount.getAcctCurrBal()).isEqualByComparingTo(PROBE_OPENING_BALANCE);

        final Account acceptedAccount = accountNow(EXPIRED_ARRIVAL_ACCOUNT);
        assertThat(acceptedAccount.getAcctCurrCycCredit())
                .as("the accepted record's amount joins its cycle credit at the module's single"
                        + " monetary scale, with no rescaling performed anywhere in this file")
                .isEqualByComparingTo(PROBE_SMALLER_ACCUMULATOR.add(PROBE_AMOUNT));
        assertThat(acceptedAccount.getAcctCurrCycDebit())
                .as("and its cycle debit is untouched")
                .isEqualByComparingTo(PROBE_LARGER_ACCUMULATOR);
        assertThat(acceptedAccount.getAcctCurrBal())
                .isEqualByComparingTo(PROBE_OPENING_BALANCE.add(PROBE_AMOUNT));
    }

    /**
     * Pins one account to a probe state whose two cycle accumulators differ.
     *
     * <p>The expiration date is kept at the delivered seed's own value first, so the expiry test cannot
     * fire and the only reason available to these probes is the over-limit one. That matters: the expiry
     * block is unguarded and would otherwise overwrite the very verdict being measured.
     *
     * @param accountId   the account to pin
     * @param cycleCredit the cycle credit to pin it to
     * @param cycleDebit  the cycle debit to pin it to
     */
    private void pinProbeAccount(final String accountId, final BigDecimal cycleCredit,
            final BigDecimal cycleDebit) {
        final Account account = pinAccountToDeliveredSeed(accountId);
        assertThat(account.getAcctExpirationDate())
                .as("%s: the delivered expiration date must not precede the origination date these"
                        + " probes carry, or the unguarded expiry block would overwrite the verdict"
                        + " being measured", TransactionPostingService.ACCTFILE_DD)
                .isGreaterThanOrEqualTo(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP
                        .substring(0, EXPIRY_COMPARISON_WIDTH));
        account.setAcctCurrBal(PROBE_OPENING_BALANCE);
        account.setAcctCreditLimit(PROBE_CREDIT_LIMIT);
        account.setAcctCurrCycCredit(cycleCredit);
        account.setAcctCurrCycDebit(cycleDebit);
        this.accountRepository.save(account);
    }

    /**
     * A probe record: one identifier, one seeded card, and the amount both probes share.
     *
     * @param  recordId   the sixteen-character identifier
     * @param  cardNumber the seeded card the probe resolves through
     * @return the landing record image
     */
    private static String probeRecord(final String recordId, final String cardNumber) {
        return TestDataFactory.dailyTransaction()
                .id(recordId)
                .cardNumber(cardNumber)
                .amount(PROBE_AMOUNT)
                .image();
    }

    @Test
    @Order(6)
    @DisplayName("the expiry test compares the account's expiration date against the first ten "
            + "characters of the ORIGINATION timestamp as text, and treats equality as acceptable")
    void theExpiryTestIsALexicographicComparisonAgainstTheOriginationTimestamp() throws Exception {
        requireCrossReference(EXPIRED_ARRIVAL_CARD, EXPIRED_ARRIVAL_ACCOUNT);
        final Account account = pinAccountToDeliveredSeed(EXPIRED_ARRIVAL_ACCOUNT);
        final String expirationDate = account.getAcctExpirationDate();
        assertThat(encodedWidth(expirationDate))
                .as("%s: the expiration field is exactly %d characters, and the comparison reads"
                        + " exactly that many characters of the other operand",
                        TransactionPostingService.ACCTFILE_DD, EXPIRY_COMPARISON_WIDTH)
                .isEqualTo(EXPIRY_COMPARISON_WIDTH);

        // TWO PROBES ONE DAY APART, STRADDLING THE ACCOUNT'S OWN EXPIRATION DATE. The comparison passes a
        // record when the expiration date is greater than OR EQUAL TO the origination date, so the record
        // that originates ON the expiration date is acceptable and the one that originates a day later is
        // not. Both operands are zero-padded ten-character text, so character order and calendar order
        // are the same order and no calendar type is constructed on either side.
        final String onBoundary = arrivalRecord(BASIS_ACCEPTED_RECORD_ID, expirationDate);
        final String oneDayLater = arrivalRecord(EXPIRED_ARRIVAL_RECORD_ID,
                LocalDate.parse(expirationDate).plusDays(1L).toString());
        stageInput(List.of(onBoundary, oneDayLater));

        // AND THE SOURCE OPERAND IS THE ORIGINATION TIMESTAMP, NOT THE PROCESSING ONE. The processing
        // field is blank on input, and blanks sort below every date, so a comparison taken against it
        // would accept every record and the second probe below would not be refused at all.
        assertThat(landingField(onBoundary, "DALYTRAN-PROC-TS"))
                .isEqualTo(TestDataFactory.BLANK_PROCESSING_TIMESTAMP);
        assertThat(landingField(oneDayLater, "DALYTRAN-PROC-TS"))
                .isEqualTo(TestDataFactory.BLANK_PROCESSING_TIMESTAMP);
        assertThat(landingField(oneDayLater, "DALYTRAN-ORIG-TS")
                .substring(0, EXPIRY_COMPARISON_WIDTH))
                .as("the refused probe's origination date is lexicographically later than the"
                        + " expiration date, which is the whole of the test the member performs")
                .isGreaterThan(expirationDate);

        final JobExecution execution = launch();
        assertThat(execution.getStatus())
                .as("failure reasons, if any: %s", execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        final List<byte[]> produced = rejectRecordsOf(rejectGeneration(execution));
        assertThat(produced)
                .as("exactly one of the two probes is refused: equality is ACCEPTABLE, so a record"
                        + " originating on the expiration date itself must post")
                .hasSize(1);
        assertThat(produced.get(0))
                .isEqualTo(expectedRejectRecord(oneDayLater,
                        RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION));

        assertThat(this.transactionRepository.findById(BASIS_ACCEPTED_RECORD_ID))
                .as("%s: the record originating on the expiration date posts",
                        TransactionPostingService.TRANFILE_DD)
                .isPresent();
        assertThat(this.transactionRepository.findById(EXPIRED_ARRIVAL_RECORD_ID))
                .as("%s: the record originating a day later does not",
                        TransactionPostingService.TRANFILE_DD)
                .isEmpty();
    }

    /**
     * A record carrying a chosen origination date, at a modest amount no seeded limit refuses.
     *
     * <p>The origination field is twenty-six characters, of which the comparison reads the leading ten,
     * so the remaining sixteen are a fixed time of day and are immaterial to the test.
     *
     * @param  recordId        the sixteen-character identifier
     * @param  originationDate the ten-character origination date
     * @return the landing record image
     */
    private static String arrivalRecord(final String recordId, final String originationDate) {
        return TestDataFactory.dailyTransaction()
                .id(recordId)
                .cardNumber(EXPIRED_ARRIVAL_CARD)
                .amount(MODEST_AMOUNT)
                .originalTimestamp(originationDate + " 12:00:00.000000")
                .image();
    }

    @Test
    @Order(7)
    @DisplayName("the three stores of lines 440 to 442 are three DURABLE units: a refused "
            + "transaction-file store leaves the category balance and the account rewrite that preceded "
            + "it in place, exactly as the abending member left them")
    void aRefusedFinalStoreLeavesTheTwoEarlierStoresInPlace() throws Exception {
        prepareDeliveredSeededState();

        // The same delivered record twice. The first copy posts all three stores. The second reaches the
        // same three, and its LAST one is refused by the store itself - the transaction identifier is the
        // primary key and the first copy already wrote it - so the refusal needs no seam of any kind: no
        // spy, no stub, no injected fault. That is the strongest form this evidence can take, because a
        // seam could be accused of arranging the very independence it then observes.
        final String image = deliveredLandingRecord(POSTING_PURCHASE_ORDINAL);
        stageInput(List.of(image, image));

        final String recordId = landingField(image, "DALYTRAN-ID");
        final BigDecimal balanceBefore = accountNow(PURCHASE_ACCOUNT).getAcctCurrBal();
        final BigDecimal cycleCreditBefore = accountNow(PURCHASE_ACCOUNT).getAcctCurrCycCredit();
        final BigDecimal categoryBalanceBefore =
                this.categoryBalanceRepository.findById(purchaseCategoryBalanceKey())
                        .map(TransactionCategoryBalance::getTranCatBal)
                        .orElseThrow(() -> new IllegalStateException(
                                TransactionPostingService.TCATBALF_DD + ": the reference seed must"
                                        + " carry the category balance both copies update"));

        final JobExecution execution = launch();

        assertThat(execution.getStatus())
                .as("%s: the member's write arm displays its literal and abends, so the run fails -"
                        + " reported failures: %s", TransactionPostingService.TRANFILE_DD,
                        execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.FAILED);

        // ---- THE POSTED ROW EXISTS ONCE. The second store was genuinely refused. ----
        assertThat(this.transactionRepository.findById(recordId))
                .as("%s: the first copy's store stands and the second copy's store was refused, so the"
                        + " key carries exactly one row", TransactionPostingService.TRANFILE_DD)
                .isPresent();

        // ---- AND BOTH EARLIER STORES OF THE REFUSED RECORD SURVIVED IT. ----
        // Twice the amount, not once. Once would mean the second record's category-balance update and
        // account rewrite had been withdrawn when its transaction-file store failed - an all-or-none
        // property the member does not have. Three unrecoverable, unjournalled files and no rollback site
        // anywhere in it: a store that completed stayed completed, whatever the store after it did.
        final BigDecimal twiceTheAmount = PURCHASE_AMOUNT.add(PURCHASE_AMOUNT);
        assertThat(this.categoryBalanceRepository.findById(purchaseCategoryBalanceKey())
                .orElseThrow().getTranCatBal())
                .as("%s: the FIRST store of the refused record is durable on its own and is not undone"
                        + " by the third one failing", TransactionPostingService.TCATBALF_DD)
                .isEqualByComparingTo(categoryBalanceBefore.add(twiceTheAmount));

        final Account after = accountNow(PURCHASE_ACCOUNT);
        assertThat(after.getAcctCurrBal())
                .as("%s: the SECOND store of the refused record is durable on its own as well",
                        TransactionPostingService.ACCTFILE_DD)
                .isEqualByComparingTo(balanceBefore.add(twiceTheAmount));
        assertThat(after.getAcctCurrCycCredit())
                .as("and the cycle credit moved with it, both times")
                .isEqualByComparingTo(cycleCreditBefore.add(twiceTheAmount));
    }

    @Test
    @Order(8)
    @DisplayName("all five reject reasons carry a four-digit code and a description padded to 76, the "
            + "two account-not-found reasons stay distinct, and the six data definitions are realised "
            + "by the named Java collaborators")
    void theTrailerContractAndTheDataDefinitionInventoryHold() {
        // ---- THE WIDTH ARITHMETIC, AGREED BY THREE INDEPENDENT DECLARATIONS. ----
        assertThat(LANDING_RECORD_WIDTH + REASON_CODE_WIDTH + REASON_DESCRIPTION_WIDTH)
                .as("the file description splits the record into a %d-byte source image and an %d-byte"
                        + " trailer, and working storage decomposes the trailer into %d digits and %d"
                        + " characters - so the record is %d bytes and no five-hundred byte layout"
                        + " exists anywhere in the estate to conflict with it",
                        LANDING_RECORD_WIDTH, VALIDATION_TRAILER_WIDTH, REASON_CODE_WIDTH,
                        REASON_DESCRIPTION_WIDTH, REJECT_RECORD_WIDTH)
                .isEqualTo(REJECT_RECORD_WIDTH)
                .isEqualTo(RejectRecordWriter.REJECT_RECORD_LENGTH)
                .isEqualTo(TransactionPostingService.REJECT_RECORD_LENGTH)
                .isEqualTo(TestDataFactory.REJECT_RECORD_WIDTH);
        assertThat(REASON_CODE_WIDTH + REASON_DESCRIPTION_WIDTH)
                .isEqualTo(VALIDATION_TRAILER_WIDTH)
                .isEqualTo(TransactionPostingService.VALIDATION_TRAILER_LENGTH);

        // ---- EVERY REASON'S TRAILER: THE PRODUCTION ASSEMBLER AGAINST THE TRANSCRIPTION. ----
        //
        // This block used to build its expectation from the very enumeration it was checking - it read
        // getReasonCode() and getDescription() and then asserted that the trailer contained them - so a
        // wrong code in the enumeration produced a wrongly-agreeing expectation and a passing test over a
        // file no consumer could read. It now drives the PRODUCTION trailer assembler,
        // RejectRecordWriter.validationTrailer, and compares its bytes against LegacyRejectReasons, a hand
        // transcription of the legacy source that imports nothing from the shipped types.
        assertThat(RejectReason.values())
                .as("the estate sets five reject reasons and no more")
                .hasSize(LegacyRejectReasons.REASONS.size())
                .hasSize(5);
        for (final LegacyRejectReasons.Reason transcribed : LegacyRejectReasons.REASONS) {
            final RejectReason shipped = RejectReason.byReasonCode(transcribed.code())
                    .orElseThrow(() -> new AssertionError("the legacy source sets reason "
                            + transcribed.fourDigitCode() + " at " + transcribed.sourceLocation()
                            + ", but the shipped enumeration recognises no such code"));
            assertThat(shipped.name())
                    .as("%s must be recovered as the reason that arises when %s, because the two"
                            + " account-not-found reasons share one description and are told apart by"
                            + " nothing but these four digits",
                            transcribed.fourDigitCode(), transcribed.role())
                    .isEqualTo(transcribed.shippedConstantName());

            final String trailer = RejectRecordWriter.validationTrailer(shipped);
            final int described = encodedWidth(transcribed.description());
            assertThat(encodedWidth(trailer))
                    .as("the trailer the PRODUCTION assembler emits for %s is exactly %d encoded bytes",
                            transcribed.fourDigitCode(), VALIDATION_TRAILER_WIDTH)
                    .isEqualTo(VALIDATION_TRAILER_WIDTH);
            assertThat(trailer)
                    .as("and it is the eighty characters the legacy source's own values produce:"
                            + " %s zero-filled on the left, then the description the source moves at"
                            + " %s, blank-padded on the right", transcribed.fourDigitCode(),
                            transcribed.sourceLocation())
                    .isEqualTo(transcribed.trailer());
            assertThat(trailer.substring(0, REASON_CODE_WIDTH))
                    .as("the reason field is numeric and fixed width, so the code is zero-filled on the"
                            + " left to four digits")
                    .isEqualTo(transcribed.fourDigitCode());
            assertThat(trailer.substring(REASON_CODE_WIDTH, REASON_CODE_WIDTH + described))
                    .as("the description occupies the front of its field verbatim")
                    .isEqualTo(transcribed.description());
            assertThat(trailer.substring(REASON_CODE_WIDTH + described))
                    .as("and the remaining %d bytes of the seventy-six character field are blanks -"
                            + " never trimmed away, because the field is fixed width",
                            REASON_DESCRIPTION_WIDTH - described)
                    .isEqualTo(" ".repeat(REASON_DESCRIPTION_WIDTH - described))
                    .hasSize(REASON_DESCRIPTION_WIDTH - described);
            assertThat(encodedWidth(RejectRecordWriter.failReasonDescriptionField(shipped)))
                    .as("the production description field is the full seventy-six bytes for %s",
                            transcribed.fourDigitCode())
                    .isEqualTo(REASON_DESCRIPTION_WIDTH);
        }

        // The five description widths, measured against the transcription rather than against the
        // enumeration, and each asserted to be what the production accessor actually returns.
        assertThat(LegacyRejectReasons.REASONS.stream()
                        .map(reason -> encodedWidth(reason.description()))
                        .toList())
                .as("the five description lengths the legacy source moves, in ascending code order")
                .containsExactly(25, 24, 21, 42, 24);
        for (final LegacyRejectReasons.Reason transcribed : LegacyRejectReasons.REASONS) {
            assertThat(RejectReason.byReasonCode(transcribed.code()).orElseThrow().getDescription())
                    .as("reason %s carries the description the legacy source moves at %s, character"
                            + " for character and unpadded", transcribed.fourDigitCode(),
                            transcribed.sourceLocation())
                    .isEqualTo(transcribed.description());
        }

        // ---- THE TWO ACCOUNT-NOT-FOUND REASONS ARE ONE TEXT AND TWO CODES. ----
        final LegacyRejectReasons.Reason transcribedOnRead =
                LegacyRejectReasons.requireByCode(LegacyRejectReasons.ACCOUNT_NOT_FOUND_ON_READ_CODE);
        final LegacyRejectReasons.Reason transcribedOnRewrite =
                LegacyRejectReasons.requireByCode(LegacyRejectReasons.ACCOUNT_NOT_FOUND_ON_REWRITE_CODE);
        assertThat(transcribedOnRewrite.description())
                .as("identical description text, because the member moves the same literal on both"
                        + " arms")
                .isEqualTo(transcribedOnRead.description());
        assertThat(RejectRecordWriter.validationTrailer(
                        RejectReason.byReasonCode(transcribedOnRewrite.code()).orElseThrow()))
                .as("DISTINCT TRAILERS, AND THEY MUST NEVER BE FOLDED TOGETHER: the four-digit code is"
                        + " the only thing that distinguishes a failed read from a failed rewrite, and"
                        + " the two are raised on different operations at different points in the run")
                .isNotEqualTo(RejectRecordWriter.validationTrailer(
                        RejectReason.byReasonCode(transcribedOnRead.code()).orElseThrow()));
        assertThat(fourDigitForm(RejectReason.ACCOUNT_NOT_FOUND_ON_READ)).isEqualTo("0101");
        assertThat(fourDigitForm(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE)).isEqualTo("0109");
        assertThat(RejectReason.byReasonCode(101))
                .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
        assertThat(RejectReason.byReasonCode(109))
                .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);

        // ---- THE SIX DATA DEFINITIONS THE MEMBER DECLARES. ----
        assertThat(MEMBER_DATA_DEFINITIONS)
                .as("the job member declares six data definitions and this run exercised the Java"
                        + " realisation of each: the transaction master and the account master through"
                        + " their repositories, the sequential input through the fixed-width reader the"
                        + " factory owns, the cross-reference and the category balance through their"
                        + " repositories, and the reject destination through its fixed-length writer")
                .containsExactly("TRANFILE", "DALYTRAN", "XREFFILE", "DALYREJS", "ACCTFILE",
                        "TCATBALF")
                .doesNotHaveDuplicates();
        assertThat(RejectRecordWriter.LEGACY_DD_NAME)
                .as("the writer names the destination it replaces")
                .isEqualTo(TransactionPostingService.DALYREJS_DD);
        assertThat(TransactionValidationProcessor.LEGACY_JOB).isEqualTo("POSTTRAN");
        assertThat(TransactionValidationProcessor.LEGACY_STEP)
                .as("the member's single application step")
                .isEqualTo("STEP15");
        assertThat(PostTransactionJobConfig.PROGRAM_NAME).isEqualTo("CBTRN02C");

        // ---- NO PATH IS WRITTEN INTO THE PRODUCTION CONFIGURATION. ----
        assertThat(PostTransactionJobConfig.DEFAULT_DALYTRAN_DATASET)
                .as("the shipped default is the logical NAME the job stream used, and this run resolved"
                        + " a different configured name instead - which is what proves the resource is"
                        + " configuration and not a location chosen in code")
                .isNotEqualTo(DALYTRAN_DATASET_VALUE);
        assertThat(this.config.dalytranInput())
                .as("the sequential input is the configured logical name resolved against the"
                        + " configured directory")
                .isEqualTo(Path.of(STAGING_DIRECTORY_VALUE).resolve(DALYTRAN_DATASET_VALUE));
        assertThat(this.config.rejectGeneration(1L).getFileName())
                .as("and the reject destination is a DISTINCT GENERATION PER EXECUTION under the"
                        + " configured base, in the absolute-generation vocabulary")
                .asString()
                .startsWith(DALYREJS_DATASET_BASE_VALUE + ".");
        assertThat(this.config.rejectGeneration(1L))
                .as("two executions cannot resolve one generation, so neither can overwrite the"
                        + " other's rejects")
                .isNotEqualTo(this.config.rejectGeneration(2L));
        assertThatCode(() -> this.config.rejectGeneration(3L))
                .as("resolving a name has no side effect: asking where a generation would go neither"
                        + " creates nor opens anything, and retention depth belongs to the shared"
                        + " durable store rather than to this specification")
                .doesNotThrowAnyException();
    }

    /**
     * The pinned clock the shared base class owns, handed to the slice through the enclosing class.
     *
     * <p>Reached through a method rather than referenced from the nested configuration directly, so the
     * inherited constant is read in exactly one place and the slice cannot acquire a clock of its own.
     *
     * @return the clock frozen at the fixture's own instant
     */
    static Clock pinnedClock() {
        return FIXED_CLOCK;
    }

    /**
     * The narrowest context the posting job needs: batch orchestration, persistence, the translated
     * program with its own collaborators, and the durable-generation boundary the reject destination
     * crosses.
     *
     * <p>No enabling annotation for batch appears here or anywhere in the module: under this framework
     * generation the batch auto-configuration backs off when that annotation is present, so adding it
     * would switch off the very infrastructure this context depends on.
     *
     * <p>The publication lock is the real one rather than a direct-run stand-in, because this slice has
     * the PostgreSQL server the production lock needs and importing the real one keeps the slice
     * faithful: this job's publication is serialized here exactly as it is in the application.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({PostTransactionJobConfig.class, BatchConfig.class, BatchStagingArea.class,
            StagedGenerationStore.class, AdvisoryGenerationPublicationLock.class,
            FixedWidthFlatFileReaderFactory.class, TransactionPostingService.class,
            PostingStageTransactionBoundary.class, RecordWriter.class, AbendService.class})
    @EnableConfigurationProperties(AwsProperties.class)
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @EntityScan(basePackageClasses = Account.class)
    static class JobContext {

        /**
         * The clock the processing timestamp is regenerated from.
         *
         * <p>Published here because this slice does not import the persistence-auditing configuration
         * that publishes it in the application - and published as the <strong>pinned</strong> clock,
         * not a system one, because the regenerated timestamp is asserted character for character and a
         * system clock would make that assertion a moving target.
         *
         * @return the clock frozen at the fixture's own instant
         */
        @Bean
        Clock pinnedBatchClock() {
            return pinnedClock();
        }

        /**
         * Keeps this PostgreSQL-focused job specification deterministic at the object-store edge.
         *
         * <p>The staging boundary, the durable store and the boundary listener's publication path all
         * remain real; only the external object-store service is replaced. Reporting no existing objects
         * is what a fresh generation group looks like, so retention has nothing to roll off.
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
         * Prevents an unrelated notification endpoint from becoming a prerequisite of a specification
         * about the posting job. Nothing in this slice publishes a notification.
         *
         * @return a notification edge that accepts whatever it is offered
         */
        @Bean
        SnsOperations notifications() {
            return mock(SnsOperations.class);
        }
    }
}
