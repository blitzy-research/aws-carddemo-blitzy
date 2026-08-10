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
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.awspring.cloud.s3.S3Operations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.batch.step.AdvisoryGenerationPublicationLock;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.FileMaintenanceService;
import com.carddemo.service.PostingStageTransactionBoundary;
import com.carddemo.service.TransactionPostingService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.IsolatedStagingRoot;
import com.carddemo.support.TestDataFactory;

/**
 * The category-balance report job, run end to end against a real PostgreSQL 16 server carrying the
 * delivered migrations, with every expectation built here from first principles rather than from the code
 * under test.
 *
 * <p>Provenance: the translated job stream is {@code app/jcl/PRTCATBL.jcl} (66 lines) read at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}. The upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19 is carried only as the provenance string of the
 * traceability-matrix header; the stamp is not universal across the estate, so nothing here asserts it
 * against any member. No job-stream, control-card, copybook or program source line is transcribed: the
 * estate is cited by step name, data-definition name, dataset name, field name, width, offset and count.
 *
 * <h2>This job has no application-program antecedent, and that governs what may be asserted</h2>
 * The planning material describes this job as driven by an application COBOL program. Measured from the
 * member itself, it is not. Its three steps invoke, in order, the no-op allocation utility at line 21
 * (a dataset delete-and-allocate and nothing else), the generic copy-utility wrapper procedure at line 29,
 * and the external sort utility at line 43. There is no application-program step anywhere in the member.
 * Nothing here therefore asserts program-derived business logic, paragraph-level behaviour, a program's
 * abend path, or a two-level file-status normalisation performed by a program: only the three
 * utility-derived steps are asserted. Recorded as a decision-log candidate.
 *
 * <h2>The program the plan named reads the card cross-reference, and both layouts are fifty bytes</h2>
 * The sequential-reader program the plan attributes to this job selects the cross-reference dataset, keys
 * on the cross-reference card number and splits its record as a sixteen-byte key plus a thirty-four-byte
 * remainder; it belongs to the file-probe job. The mis-attribution was plausible because <strong>both
 * record layouts are exactly fifty bytes</strong>, so a width check alone cannot tell them apart. This
 * class therefore asserts the category-balance <em>field decomposition</em> - an eleven-digit account
 * identifier, a two-character type code, a four-digit category code, an eleven-byte signed balance and a
 * twenty-two-byte filler - and separately refutes the cross-reference decomposition. Corroboration worth
 * recording: only the interest program and the posting program reference the category-balance resource at
 * all, and both do so transactionally rather than sequentially.
 *
 * <h2>The comment at line 41 of the member is untrue</h2>
 * It claims a parameter-date filter and a card-number ordering. The control stream performs neither, so
 * this class asserts that the job takes no date parameter, applies no date filter and never orders by card
 * number, rather than assuming that absence. A separate cosmetic oddity: the comment banner at line 26
 * carries a stray backtick. Both are recorded as decision-log candidates and neither is reproduced.
 *
 * <h2>The fourth sort specification, and why its comparator cannot be shared</h2>
 * A verb census across all twenty-eight programs finds zero internal sort and zero merge statements: all
 * ordering in the estate is external, in four distinct specifications, of which this member's is the
 * fourth. Its keys, at one-based offsets, are the account identifier at 1 for 11 as zoned decimal, the
 * type code at 12 for 2 as character, and the category code at 14 for 4 as zoned decimal, all ascending;
 * the balance at 18 for 11 is declared as a field and is <strong>not</strong> a key. <strong>This one
 * specification mixes zoned-decimal and character keys within itself</strong>, which is why the comparator
 * belongs privately to the job configuration and is never shared. The decisive cross-job proof that
 * sharing would be wrong is that byte offset 263 is typed character in the statement job and zoned decimal
 * in the transaction-report job - the same offset, two typings, two jobs.
 *
 * <h2>The fifth contractual output width, its committed golden, and a one-byte conflict</h2>
 * The planning material names four fixed output widths - 80, 100, 133 and 430. This job introduces a
 * <strong>fifth: 40 bytes</strong>. That is a gap in the plan rather than a misreading of the member, and
 * it is recorded here as one. The fifth width now carries a golden of its own,
 * {@value #REPORT_GOLDEN_RESOURCE}, and <strong>that file is this class's verdict oracle</strong>: the
 * job is run once against a state this class fixes completely, and the bytes it wrote to the local
 * dataset are compared with the bytes of the committed file. The reprojection at lines 53 to 56 assembles
 * an eleven-byte account
 * identifier, a blank, a two-byte type code, a blank, a four-byte category code, a blank and a
 * twelve-character edited balance - nine digits, a decimal point and two decimals - which is thirty-two
 * content bytes, and then declares a nine-byte blank run. Thirty-two plus nine is forty-one, against the
 * declared record length of forty at line 61. The specification over-runs its own record by exactly one
 * byte. <strong>Resolved to forty</strong>: thirty-two content bytes followed by exactly eight blanks,
 * never nine, and never a forty-one-byte line. Recorded as a decision-log candidate citing the
 * reprojection at lines 53 to 56 against the record length at line 61.
 *
 * <h2>Why the posting job runs as set-up</h2>
 * Measured across the delivered fifty-record category-balance fixture, every row carries the same balance
 * image and it decodes to zero - exactly one distinct value in fifty rows. A report produced against
 * unmodified seed data would therefore render the same zero on every line, and neither the balance-editing
 * projection nor the zoned-decimal sign handling would be observable at all. The posting job is run first,
 * by name, so that non-zero balances exist before the subject job is launched; it is the only prerequisite
 * this job has. This class is not a second end-to-end test - the chained pipeline run and the shared golden
 * comparisons belong to the end-to-end batch pipeline test, and nothing here asserts against those goldens.
 *
 * <h2>The filler census is measured and is not uniform</h2>
 * The delivered category-balance fixture pads its twenty-two-byte filler with the ASCII digit zero, while
 * the account, card, customer and daily-transaction fixtures pad with the space character and the
 * cross-reference fixture materialises no filler at all. That census is the contract and is asserted here
 * against the fixture. The module's own re-emission writes the same run as spaces, because the copybook
 * declares the filler with no value clause and therefore fixes no byte value; the two are asserted
 * separately for that reason, and neither is quietly unified with the other.
 *
 * <h2>Every expectation is independent of the code under test</h2>
 * No expected image, ordering or mask is produced by delegating to the job configuration, to the shared
 * zoned-decimal codec or to any record mapper. The fifty-byte images, the forty-byte report lines, the
 * overpunched sign byte and the key ordering are all built in this class from plain string arithmetic and
 * a locally declared overpunch table, so an error copied into the production encoder cannot be copied into
 * the expectation that is supposed to catch it.
 *
 * <h2>Which side is the verdict, stated explicitly</h2>
 * Independence of the code under test is necessary and is not sufficient: an expectation assembled in
 * this class from this class's own reading of the layout can still reproduce the very misunderstanding it
 * is meant to catch, because one author wrote both readings. So the <strong>verdict</strong> for the
 * forty-byte contract is the committed golden {@value #REPORT_GOLDEN_RESOURCE} - separately authored
 * bytes, held in the fixture tree beside the other four oracles, never regenerated from a run and never
 * produced by calling the code under test - compared as a byte array against what the job wrote to a real
 * local dataset after reading a real PostgreSQL server. The golden run is driven from a state this class
 * fixes completely beforehand, which is what makes a committed expectation possible at all: the fifty
 * delivered rows plus the four rows this class adds, and nothing else.
 *
 * <p>The builder that assembles a forty-byte line in this class survives that change, and its role is now
 * <strong>diagnostic decomposition only</strong>. It is what says <em>which field</em> of <em>which
 * record</em> differs when the golden comparison fails, and it is what lets the report the job produces
 * over the posted state - a state whose balances are a function of a three-hundred-record posting run
 * rather than of anything committed - still be checked field by field. It is not the verdict for the
 * width, the mask, the separators or the ordering; the committed file is.
 */
@SpringBootTest(classes = CategoryBalanceReportJobConfigIT.JobsUnderTest.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.main.banner-mode=off",
            "spring.jpa.hibernate.ddl-auto=none",
            "management.tracing.enabled=false",
            // The shared server is already at the head of the migration set before this context starts,
            // so a second in-context migration would only repeat work and the mappings are read against
            // a schema the migrations already own. Nothing is generated either way: the setting above is
            // none, so no table, index or constraint here comes from anywhere but a delivered migration.
            "spring.flyway.enabled=false",
            "management.endpoint.health.validate-group-membership=false",
            // ONE key configures both jobs, and it is registered from a property callback rather than
            // written here - see registerIsolatedStagingDirectory. A literal cannot carry the value: the
            // root has to be unique to this process, and a @TestPropertySource entry is a compile-time
            // constant.
            // Both logical names are stated rather than left to their defaults, so that the destinations
            // this class inspects are provably the ones configuration resolved and not paths it invented.
            CategoryBalanceReportJobConfigIT.BACKUP_DATASET_BASE_PROPERTY
                    + "=" + CategoryBalanceReportJobConfigIT.BACKUP_DATASET_BASE,
            CategoryBalanceReportJobConfigIT.REPORT_DATASET_PROPERTY
                    + "=" + CategoryBalanceReportJobConfigIT.REPORT_DATASET})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("category-balance report job against a real server: three ungated steps, a fifty-byte "
        + "unload and a forty-byte report of thirty-two content bytes and eight blanks")
class CategoryBalanceReportJobConfigIT extends AbstractPostgresIT {

    // -----------------------------------------------------------------------------------------------
    // Configuration keys and logical names. Property keys and dataset names are estate identifiers, so
    // they are stated here as literals; no filesystem path is written anywhere in this class.
    // -----------------------------------------------------------------------------------------------

    /** The one staging-directory key both job configurations fall back to. */
    static final String SHARED_STAGING_DIRECTORY_PROPERTY = "carddemo.batch.staging-directory";

    /** Key of the logical name of the generation-group base the unload writes a new generation of. */
    static final String BACKUP_DATASET_BASE_PROPERTY =
            "carddemo.batch.category-balance-report.backup-dataset-base";

    /** Key of the logical name of the report dataset the first step clears and the third step writes. */
    static final String REPORT_DATASET_PROPERTY =
            "carddemo.batch.category-balance-report.report-dataset";

    /**
     * This specification's label within this process's private staging namespace.
     *
     * <p>It was a directory name beneath the platform temporary directory, which made the root shared with
     * every other run and every sibling clone on the host; it is now one segment beneath a namespace unique
     * to this process. See {@link IsolatedStagingRoot} for why that mattered rather than merely being
     * untidy.
     */
    static final String STAGING_LABEL = "category-balance-report-it";

    /** Logical name of the backup generation-group base, as the legacy stream names that resource. */
    static final String BACKUP_DATASET_BASE = "AWS.M2.CARDDEMO.TCATBALF.BKUP";

    /** Logical name of the report dataset, as the legacy stream names that resource. */
    static final String REPORT_DATASET = "AWS.M2.CARDDEMO.TCATBALF.REPT";

    /** Classpath location of the delivered category-balance fixture, discovered on the test classpath. */
    private static final String CATEGORY_BALANCE_FIXTURE = "fixtures/input/tcatbal.txt";

    /** Classpath location of the delivered cross-reference fixture, used only to contrast the layouts. */
    private static final String CROSS_REFERENCE_FIXTURE = "fixtures/input/cardxref.txt";

    /** Classpath location of the delivered daily-transaction fixture the prerequisite run consumes. */
    private static final String DAILY_TRANSACTION_FIXTURE = "fixtures/input/dailytran.txt";

    /**
     * Classpath location of the committed forty-byte golden, which is this class's verdict oracle.
     *
     * <p>The fifth oracle of the expected-output tree, beside the eighty-byte statement, its
     * hundred-byte hypertext counterpart, the hundred-and-thirty-three-byte report line and the
     * four-hundred-and-thirty-byte reject record. It carries fifty-three separator-free forty-byte
     * records: one per delivered category-balance row, plus the three rows this class adds beyond the
     * fifty and one it rewrites in place.
     */
    private static final String REPORT_GOLDEN_RESOURCE = "fixtures/expected/category-balance-report.txt";

    /** Records the committed golden holds, which is the population the golden run reports over. */
    private static final int GOLDEN_RECORD_COUNT = 53;

    // -----------------------------------------------------------------------------------------------
    // The layout, declared independently of the production mapper so that this class is an oracle and
    // not an echo. Every figure below is measured from the copybook and from the member.
    // -----------------------------------------------------------------------------------------------

    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Width of the transaction type code, the second key part and the only character-typed key. */
    private static final int TYPE_CODE_WIDTH = 2;

    private static final int CATEGORY_CODE_WIDTH = 4;

    private static final int COMPOSITE_KEY_WIDTH = ACCOUNT_ID_WIDTH + TYPE_CODE_WIDTH
            + CATEGORY_CODE_WIDTH;

    private static final int BALANCE_WIDTH = 11;

    private static final int BALANCE_SCALE = 2;

    /** Integer digit positions the balance carries, and the index of the mask's decimal point. */
    private static final int BALANCE_INTEGER_DIGITS = BALANCE_WIDTH - BALANCE_SCALE;

    private static final int FILLER_WIDTH = 22;

    private static final int MAPPED_PREFIX_WIDTH = COMPOSITE_KEY_WIDTH + BALANCE_WIDTH;

    private static final int UNLOAD_RECORD_WIDTH = MAPPED_PREFIX_WIDTH + FILLER_WIDTH;

    /** Width of the edited balance the reprojection emits: nine digits, a point and two decimals. */
    private static final int BALANCE_MASK_WIDTH = BALANCE_WIDTH + 1;

    private static final int SEPARATOR_WIDTH = 1;

    private static final int REPORT_CONTENT_WIDTH = ACCOUNT_ID_WIDTH + SEPARATOR_WIDTH + TYPE_CODE_WIDTH
            + SEPARATOR_WIDTH + CATEGORY_CODE_WIDTH + SEPARATOR_WIDTH + BALANCE_MASK_WIDTH;

    /** The declared record length of the report, and the resolution of the one-byte conflict. */
    private static final int REPORT_RECORD_WIDTH = 40;

    /** Trailing blanks a report line carries: eight, never the nine the reprojection declares. */
    private static final int REPORT_TRAILING_BLANKS = REPORT_RECORD_WIDTH - REPORT_CONTENT_WIDTH;

    /** The blank run the reprojection itself declares, which would have produced a forty-first byte. */
    private static final int DECLARED_TRAILING_BLANKS = 9;

    /** The over-long record the declared blank run would have produced, asserted never to be emitted. */
    private static final int OVER_RUN_RECORD_WIDTH = REPORT_CONTENT_WIDTH + DECLARED_TRAILING_BLANKS;

    /** Width of the cross-reference card number, the leading field of the layout this job does NOT read. */
    private static final int CROSS_REFERENCE_KEY_WIDTH = 16;

    /** Width of the cross-reference remainder, which with its key totals the same fifty bytes. */
    private static final int CROSS_REFERENCE_REMAINDER_WIDTH = 34;

    /** Encoded width of one delivered cross-reference fixture record, whose filler is not materialised. */
    private static final int CROSS_REFERENCE_FIXTURE_RECORD_WIDTH = 36;

    /** Key width of the transaction-category record, kept only to keep the two composites distinct. */
    private static final int TRANSACTION_CATEGORY_KEY_WIDTH = 6;

    private static final int SEEDED_ROWS = 50;

    private static final int SEEDED_DISTINCT_BALANCES = 1;

    // -----------------------------------------------------------------------------------------------
    // The overpunched sign convention, declared here rather than imported, because an expectation that
    // borrowed the production table could not detect an error in it.
    // -----------------------------------------------------------------------------------------------

    /** Final-byte encodings of a positive zero through a positive nine. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** Final-byte encodings of a negative zero through a negative nine. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    private static final char ZERO_DIGIT = '0';

    private static final char BLANK = ' ';

    private static final char DECIMAL_POINT = '.';

    /** Filler character the delivered category-balance fixture measurably uses. */
    private static final char FIXTURE_FILLER_CHARACTER = ZERO_DIGIT;

    /** Filler character the module re-emits, the copybook having declared no value for the run. */
    private static final char REEMITTED_FILLER_CHARACTER = BLANK;

    /**
     * The separator a fixed-length staged dataset must NOT carry.
     *
     * <p>Both data-definition statements of the legacy member declare a fixed record format, which takes
     * its record boundary from the declared length and writes no byte between two records. Kept as a
     * named constant because its <em>absence</em> is asserted: this class proves no line feed reaches
     * either artefact. See {@code docs/decision-log.md} entry DL-213.
     */
    private static final String FORBIDDEN_RECORD_SEPARATOR = "\n";

    /** Every byte either overpunch table can place in a final position, for a membership test. */
    private static final String SIGN_BYTES = POSITIVE_OVERPUNCH + NEGATIVE_OVERPUNCH;

    /** Suffix a generation still being written carries, so a sealed one can be told from it. */
    private static final String WORKING_FILE_SUFFIX = ".part";

    /** Upper bound of one instance-metadata query; far above any count this class can produce. */
    private static final int INSTANCE_QUERY_CEILING = 1000;

    // -----------------------------------------------------------------------------------------------
    // Legacy identities the diagnostics and the metric tags carry. Step names are identifiers.
    // -----------------------------------------------------------------------------------------------

    /** Legacy step name the clear-down equivalent reports, and its metric tag value. */
    private static final String LEGACY_CLEAR_STEP = "DELDEF";

    /** Legacy step name the unload equivalent reports, and its metric tag value. */
    private static final String LEGACY_UNLOAD_STEP = "STEP05R";

    /** Legacy step name the order-and-reproject equivalent reports, and its metric tag value. */
    private static final String LEGACY_SORT_STEP = "STEP10R";

    private static final String COBOL_STEP_TIMER = "carddemo.batch.cobol.step";

    private static final String TIMER_STEP_TAG = "step";

    private static final String TIMER_OUTCOME_TAG = "outcome";

    private static final String TIMER_COMPLETED_OUTCOME = "COMPLETED";

    // -----------------------------------------------------------------------------------------------
    // The rows this class adds, on a SEEDED account because the schema constrains the account key.
    // -----------------------------------------------------------------------------------------------

    /**
     * The highest seeded account identifier, used for every row this class adds.
     *
     * <p>A seeded account is not a convenience: the schema carries a foreign key from the category-balance
     * account key to the account master, so an unseeded identifier would be refused. The highest one is
     * chosen so that the rows added here sort to the end of the report and can be asserted as an ordered
     * tail without depending on how the seeded and posted rows interleave ahead of them.
     */
    private static final String DISCRIMINATING_ACCOUNT = "00000000050";

    /** Type code of the three rows that separate a numeric category reading from a character one. */
    private static final String SEEDED_TYPE_CODE = "01";

    /** Type code that sorts after every code the seed and the posting run produce, as a character. */
    private static final String TRAILING_TYPE_CODE = "0Z";

    /** Category code the seed and the posting run use, whose zoned reading is plus one. */
    private static final String SEEDED_CATEGORY_CODE = "0001";

    /** Category code whose overpunched final byte makes its zoned reading minus one. */
    private static final String NEGATIVE_CATEGORY_CODE = "000J";

    /** Category code whose zoned reading is plus two. */
    private static final String HIGHER_CATEGORY_CODE = "0002";

    private static final BigDecimal BALANCE_ON_NEGATIVE_CATEGORY = new BigDecimal("7.00");

    /** Balance of the row whose category reads plus one, and the negative value the mask must render. */
    private static final BigDecimal NEGATIVE_BALANCE = new BigDecimal("-5.00");

    /** Balance of the row whose category reads plus two, filling every declared integer digit. */
    private static final BigDecimal WIDEST_BALANCE = new BigDecimal("123456789.99");

    /** Balance of the trailing row, proving the mask never blanks a zero. */
    private static final BigDecimal ZERO_BALANCE = new BigDecimal("0.00");

    // -----------------------------------------------------------------------------------------------
    // Collaborators, taken from the delivered application context. The database and the report file I/O
    // are real; only the object-store edge is replaced, and it is replaced by a bean rather than by a
    // reset-between-methods override so that a job launched from any method finds it stubbed.
    // -----------------------------------------------------------------------------------------------

    /** The context itself, so that the absence of a start-up launcher can be asserted by bean type. */
    @Autowired
    private ApplicationContext context;

    /** The environment, so that the shipped inertness settings can be read rather than assumed. */
    @Autowired
    private Environment environment;

    /** The framework's registry, which is how the operational surface finds a job by name. */
    @Autowired
    private JobRegistry jobRegistry;

    /** The framework's operator, which advances a job to its next instance by name. */
    @Autowired
    private JobOperator jobOperator;

    /** The framework's explorer, which resolves a launched identifier to its execution. */
    @Autowired
    private JobExplorer jobExplorer;

    /** The repository under the job, used to seed the discriminating rows and to read them back. */
    @Autowired
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    /** Owner of the shared sequential-read and two-level file-status discipline. */
    @Autowired
    private FileMaintenanceService fileMaintenanceService;

    /** The registry the shared batch-program skeleton records its lifecycle timers in. */
    @Autowired
    private MeterRegistry meterRegistry;

    /** Directory every staged dataset of both jobs resolves within, as configuration resolved it. */
    @Value("${" + SHARED_STAGING_DIRECTORY_PROPERTY + "}")
    private String stagingDirectory;

    @Value("${" + REPORT_DATASET_PROPERTY + "}")
    private String reportDatasetName;

    @Value("${" + BACKUP_DATASET_BASE_PROPERTY + "}")
    private String backupDatasetBaseName;

    /** Creates the test class. */
    CategoryBalanceReportJobConfigIT() {
    }

    /**
     * The two jobs and exactly the collaborators they declare, assembled explicitly.
     *
     * <p><strong>Explicitly rather than by scanning the application entry point, and that is a
     * correctness requirement rather than a preference.</strong> A scan rooted at the base package
     * reaches the test tree as well as the production tree, and several test classes here publish nested
     * configurations of their own that declare the same repository beans; a scanned context therefore
     * fails to refresh on a duplicate bean definition that has nothing to do with the job under test.
     * Naming the slice also keeps the reason for every bean visible: a defect elsewhere in the bean graph
     * can neither mask nor manufacture a result here.
     *
     * <p>The database is real and carries the delivered migrations, and the staging store, the generation
     * publication lock, the job-boundary listener, the shared read discipline and both jobs are the
     * production classes. Only the remote object store is stood in for, because reaching one would make an
     * external service a prerequisite of a database-focused test; the same upload operations are proven
     * against the AWS emulator by the object-store integration tier. Neither the database nor the report
     * and unload file I/O is stood in for.
     *
     * <p>The clock is pinned to the shared fixed instant so that anything stamping a timestamp agrees with
     * the seeded fixtures rather than drifting away from them a day at a time.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({CategoryBalanceReportJobConfig.class, PostTransactionJobConfig.class, BatchConfig.class,
            FileMaintenanceService.class, TransactionPostingService.class,
            PostingStageTransactionBoundary.class, AbendService.class, RecordWriter.class,
            FixedWidthFlatFileReaderFactory.class, BatchStagingArea.class, StagedGenerationStore.class,
            AdvisoryGenerationPublicationLock.class})
    @EnableConfigurationProperties(AwsProperties.class)
    @EnableJpaRepositories(basePackageClasses = TransactionCategoryBalanceRepository.class)
    @EntityScan(basePackageClasses = TransactionCategoryBalance.class)
    static class JobsUnderTest {

        JobsUnderTest() {
        }

        /**
         * The one clock of this slice, pinned rather than read from the system.
         *
         * <p>Published here because the slice deliberately does not import the persistence-auditing
         * configuration that publishes a clock in the application, and because a system clock would let an
         * assertion about a derived date mean something different on every run.
         *
         * @return the shared fixed clock
         */
        @Bean
        Clock fixedClock() {
            return FIXED_CLOCK;
        }

        /**
         * The object-store edge: it accepts an upload, reports that nothing is already staged so a local
         * input is read, and reports no retained older generation so retention has nothing to remove.
         *
         * @return the stubbed object-store edge
         */
        @Bean
        S3Operations objectStore() {
            final S3Operations objectStore = mock(S3Operations.class);
            when(objectStore.objectExists(anyString(), anyString())).thenReturn(false);
            when(objectStore.listObjects(anyString(), anyString())).thenReturn(List.of());
            return objectStore;
        }
    }

    /**
     * Creates the staging directory this class owns, before any job resolves a dataset within it.
     *
     * <p>Only the directory is prepared. Nothing is deleted here, because the first assertion of the
     * clear-down contract is that the report output does <em>not</em> exist before the first run, and a
     * blanket clean would make that assertion true by the test's own hand rather than by the job's.
     *
     * @throws IOException if the directory cannot be created
     */
    @BeforeEach
    void prepareStagingDirectory() {
        IsolatedStagingRoot.forSpecification(STAGING_LABEL);
    }

    /**
     * Binds the staging directory to a root private to this process, before the context is created.
     *
     * <p>A property callback rather than a {@code @TestPropertySource} entry, because the value cannot be a
     * compile-time constant: the root carries the process identifier so that no other run of this
     * specification, and no sibling clone sharing this host, resolves the same absolute path. The callback
     * runs before the context starts, which is what a staging directory being bound during start-up
     * requires.
     *
     * @param registry the registry the framework supplies
     */
    @DynamicPropertySource
    static void registerIsolatedStagingDirectory(final DynamicPropertyRegistry registry) {
        registry.add(SHARED_STAGING_DIRECTORY_PROPERTY,
                () -> IsolatedStagingRoot.pathFor(STAGING_LABEL));
    }

    /**
     * Returns the shared server to the state a fresh migration leaves it in, and removes every dataset
     * this class staged.
     *
     * <p>Both halves are obligations rather than tidiness. The prerequisite posting run commits per record
     * on its own connections, so no rollback can reach it, and the server is shared by every integration
     * class in the run - leaving posted transactions and moved balances behind would break classes that
     * assert against the delivered seed for a reason unrelated to what they test. The staged datasets are
     * removed because a staged generation is named from a batch execution identifier and those restart in a
     * fresh metadata schema, so a generation left behind would reappear as though it belonged to a later
     * execution. What is removed is this specification's own root and nothing else - the sweep of every
     * regular file beneath a shared directory that stood here could reach a file a concurrently running
     * sibling was still composing.
     *
     * @throws Exception if the server cannot be restored or the staged datasets cannot be removed
     */
    @AfterAll
    void restoreSharedState() throws Exception {
        restoreSeededState();
        IsolatedStagingRoot.discard(stagingRoot());
    }

    // -----------------------------------------------------------------------------------------------
    // The contracts.
    // -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("registered under its own name with exactly three steps and no condition-code gate, and "
            + "nothing whatsoever ran when the context came up")
    void theJobIsRegisteredWithThreeUngatedStepsAndNothingRanAtStartUp() throws Exception {
        assertThat(this.jobRegistry.getJobNames())
                .as("the operational surface launches by name, so the job has to be registered under one")
                .contains(CategoryBalanceReportJobConfig.JOB_NAME, PostTransactionJobConfig.JOB_NAME);

        final Job job = this.jobRegistry.getJob(CategoryBalanceReportJobConfig.JOB_NAME);
        assertThat(job)
                .as("a flow job is the shape a condition-code gate, a split or a parallel flow produces. "
                        + "Step-level condition codes exist on exactly four steps in the whole estate - "
                        + "three in the statement job and one in the backup job - and none of them is in "
                        + "this member, so a simple sequential job is the only faithful shape")
                .isExactlyInstanceOf(SimpleJob.class);
        assertThat(((SimpleJob) job).getStepNames())
                .as("the three steps the member declares, in the order it declares them: the clear-down "
                        + "standing in for the no-op allocation utility, the unload standing in for the "
                        + "copy-utility wrapper, and the order-and-reproject standing in for the external "
                        + "sort. No application-program step exists in the member to add a fourth")
                .containsExactly(
                        CategoryBalanceReportJobConfig.CLEAR_PRIOR_REPORT_STEP_NAME,
                        CategoryBalanceReportJobConfig.UNLOAD_STEP_NAME,
                        CategoryBalanceReportJobConfig.SORT_AND_REPROJECT_STEP_NAME)
                .doesNotHaveDuplicates()
                .hasSize(CategoryBalanceReportJobConfig.STEP_COUNT);

        assertThat(this.environment.getProperty("spring.batch.job.enabled"))
                .as("the shipped profile disables start-up launching; this reads the setting rather than "
                        + "restating it, so a profile that lost it fails here")
                .isEqualTo("false");
        assertThat(this.environment.getProperty("spring.batch.job.name"))
                .as("naming a job would launch it at start-up")
                .isNull();
        assertThat(this.context.getBeansOfType(JobLauncherApplicationRunner.class))
                .as("the framework's own start-up launcher must not be published")
                .isEmpty();
        assertThat(this.context.getBeansOfType(CommandLineRunner.class))
                .as("no start-up runner of any kind may exist to fire a job as a side effect of a refresh")
                .isEmpty();
        assertThat(this.context.getBeansOfType(ApplicationRunner.class))
                .as("no start-up runner of any kind may exist to fire a job as a side effect of a refresh")
                .isEmpty();
        assertThat(this.jobExplorer.findRunningJobExecutions(CategoryBalanceReportJobConfig.JOB_NAME))
                .as("nothing may be running before this class launches anything")
                .isEmpty();
    }

    // -----------------------------------------------------------------------------------------------
    // WHY THE TWO RUNS AND THEIR SIX CONSUMERS ARE ONE FACTORY RATHER THAN EIGHT ORDERED TESTS
    //
    // The subject job reports category balances, and every one of the fifty delivered rows carries the
    // same zero balance - so a report produced against the seed alone renders the same zero on every
    // line and neither the edit mask nor the sign handling is observable. The posting run has to move
    // those balances first, and the subject job then has to run twice for the clear-down contract to be
    // observable at all. That prerequisite chain is one indivisible act.
    //
    // Six further assertions read what those runs produced. Expressed as @Order-ed methods over
    // PER_CLASS instance fields, each was untrue in isolation: run alone - which -Dit.test=Class#method
    // does - it found an empty capture and failed for a reason unrelated to its subject, and the class's
    // correctness rested on MethodOrderer rather than on anything a reader could see.
    //
    // A factory drives the chain once and hands each consumer the capture as a VALUE. Every consumer is
    // still separately named and separately reported, none can execute without a capture to read, and
    // there is no order left to get wrong.
    // -----------------------------------------------------------------------------------------------

    @TestFactory
    @DisplayName("the posting run supplies the non-zero balances the delivered seed cannot, the subject "
            + "job then runs twice over with an idempotent clear-down, and every contract of what those "
            + "runs produced holds")
    Stream<DynamicTest> theTwoRunsAndEveryContractOfWhatTheyProduced() throws Exception {
        final CapturedRuns runs = driveTheTwoRuns();
        return Stream.of(
                dynamicTest(
                        "GATE 1 VERDICT - the report the job wrote over a completely fixed state is "
                                + "byte-identical to the committed forty-byte golden, which is a "
                                + "separately authored file rather than anything this class built",
                        () -> theGoldenRunIsByteIdenticalToTheCommittedOracle(runs)),
                dynamicTest(
                        "the unload is the fifty-byte CATEGORY-BALANCE layout - eleven-digit "
                                + "account, two-character type, four-digit category, eleven-byte "
                                + "signed balance, twenty-two-byte filler - and provably not the "
                                + "equally fifty-byte cross-reference layout",
                        () -> theUnloadCarriesTheCategoryBalanceDecomposition(runs)),
                dynamicTest(
                        "every report line is exactly forty encoded bytes - thirty-two content "
                                + "bytes and EXACTLY EIGHT blanks, never the forty-one the "
                                + "reprojection's own nine-byte run would give",
                        () -> theReportResolvesTheOneByteConflictToForty(runs)),
                dynamicTest(
                        "ordered by account, then type, then category, all ascending, with the two "
                                + "zoned keys read as signed numbers and the character key "
                                + "lexicographically - and the balance is no key",
                        () -> theOrderingIsAccountThenTypeThenCategoryAscending(runs)),
                dynamicTest(
                        "the delivered fixture carries this record's own measured census: fifty "
                                + "fifty-byte records, a seventeen-byte composite key and a "
                                + "twenty-two-byte ASCII-zero filler",
                        this::theDeliveredFixtureCarriesTheMeasuredCensusForThisRecord),
                dynamicTest(
                        "the unload's sequential pass is the shared discipline's GENERIC "
                                + "category-balance entry point, read once, in composite key "
                                + "order, and ending at end of file",
                        this::theUnloadUsesTheGenericCategoryBalanceEntryPoint),
                dynamicTest(
                        "one lifecycle timer per legacy step, tagged by step name and completed "
                                + "outcome - presence and shape only, because the estate documents "
                                + "no performance figure",
                        this::theProgramLifecycleTimersArePresentAndTagged));
    }

    /**
     * Drives the golden run, the prerequisite posting run and the two subject runs, once, and captures
     * what they made.
     *
     * <p>Every assertion here is a fact about the driving itself and has to be made while it happens: the
     * seeded census has to be measured before the posting run moves it, and the clear-down contract needs
     * the report absent before the first launch and present before the second. What the runs produced is
     * returned as a value rather than stored on the instance, so no later assertion can find it by
     * accident.
     *
     * <h2>Why the golden run comes first, and why the added rows move with it</h2>
     *
     * <p>A committed expectation can only be compared against a state that is fixed in advance, and the
     * posting run's output is not: its balances are a function of three hundred input records passing
     * through the accept-or-reject cascade. So the forty-byte verdict is taken <strong>before</strong>
     * posting, over a state this method fixes completely - the fifty delivered rows plus the four rows
     * this class adds - and the committed golden holds exactly the fifty-three lines that state projects
     * to. Adding those four rows before the golden run rather than after it is what puts a non-zero
     * magnitude, a negative balance and a nine-digit balance inside the committed expectation instead of
     * leaving all three to an expectation this class assembles.
     *
     * <p>The four rows are then re-applied after the posting run. Posting owns one of their keys - the
     * seeded type and category on the discriminating account - and would otherwise leave it carrying a
     * posted balance rather than the negative value the later assertions name. Re-applying is a write of
     * the same four composite keys with the same four values, so it restores exactly the state those
     * assertions were written against and changes nothing else.
     *
     * @return the captured artefacts of the golden run and the two subject runs
     * @throws Exception if a launch is refused, or a staged artefact cannot be read
     */
    private CapturedRuns driveTheTwoRuns() throws Exception {
        // The one server is shared by every integration class in the run, and a batch job commits per
        // record on its own connections so no rollback can reach it. Starting from the state a fresh
        // migration leaves - which is the opt-in reset the shared base offers rather than a context
        // discard - is what makes the seeded census below a measurement instead of a hope.
        restoreSeededState();

        assertThat(distinctBalances())
                .as("measured over the whole delivered fixture: fifty rows, one distinct balance, and it "
                        + "decodes to zero. A report produced against this state alone would render the "
                        + "same zero on every line, so neither the edit mask nor the sign handling would "
                        + "be observable - which is why the four discriminating rows are added before the "
                        + "golden run and why the posting job runs before the two subject runs")
                .hasSize(SEEDED_DISTINCT_BALANCES)
                .containsExactly(ZERO_BALANCE);
        assertThat(this.categoryBalanceRepository.count()).isEqualTo(SEEDED_ROWS);

        // THE GOLDEN RUN. Fixed state, one launch, and the verdict is the committed file.
        addDiscriminatingRows();
        final long rowsUnderTheGolden = this.categoryBalanceRepository.count();
        assertThat(rowsUnderTheGolden)
                .as("the state the committed golden was authored against: the fifty delivered rows, one "
                        + "of which the added rows rewrite in place, plus three new ones")
                .isEqualTo(GOLDEN_RECORD_COUNT);
        removeStaleArtefactsOfThisJob();
        final JobExecution goldenRun = launch(CategoryBalanceReportJobConfig.JOB_NAME);
        assertCompletedWithThreeSteps(goldenRun);
        assertNoDateOrCardParameter(goldenRun);
        final byte[] goldenRunReport = Files.readAllBytes(reportDataset());

        stageDailyTransactionInput();
        final long rowsBeforePosting = this.categoryBalanceRepository.count();
        final JobExecution posting = launch(PostTransactionJobConfig.JOB_NAME);
        assertThat(posting.getStatus())
                .as("the prerequisite run has to complete, or the balances the subject job reports are "
                        + "still the seeded zeros. Its input is the delivered three-hundred-record "
                        + "daily-transaction fixture, staged under the logical name its own configuration "
                        + "resolves")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(distinctBalances())
                .as("the posting run moved balances, so the edit mask and the sign handling are now "
                        + "observable in the report the subject job produces")
                .hasSizeGreaterThan(SEEDED_DISTINCT_BALANCES);
        assertThat(this.categoryBalanceRepository.count())
                .as("the posting arm creates a category-balance row for a key it does not find, so the "
                        + "population grows past the %d row(s) the golden run reported over",
                        rowsBeforePosting)
                .isGreaterThan(rowsBeforePosting);

        // Re-applied, not added again: the same four composite keys carrying the same four values, which
        // puts back the one key the posting run owns and leaves the other three exactly as they were.
        addDiscriminatingRows();

        removeStaleArtefactsOfThisJob();
        final int instancesBefore = reportJobInstanceCount();

        assertThat(reportDataset())
                .as("the first run has to clear an output that is not there. The legacy data definition "
                        + "allocates the dataset when it is absent and deletes it at normal end, so the "
                        + "step cannot fail on a first run and neither may its equivalent")
                .doesNotExist();
        final JobExecution first = launch(CategoryBalanceReportJobConfig.JOB_NAME);
        assertCompletedWithThreeSteps(first);
        assertNoDateOrCardParameter(first);

        assertThat(reportDataset())
                .as("the second run has to clear an output that IS there, which is the other half of the "
                        + "idempotence the disposition promises")
                .exists();
        final JobExecution second = launch(CategoryBalanceReportJobConfig.JOB_NAME);
        assertCompletedWithThreeSteps(second);
        assertNoDateOrCardParameter(second);
        assertThat(second.getId())
                .as("each launch is its own instance, which is what advancing a job by name produces")
                .isNotEqualTo(first.getId());

        assertThat(reportJobInstanceCount())
                .as("exactly the two runs this method launched, and not one more: nothing else started a "
                        + "run of this job while the context was alive")
                .isEqualTo(instancesBefore + 2);

        final List<Path> generations = backupGenerations();
        assertThat(generations)
                .as("a new generation per execution, which is what the relative generation the member "
                        + "names resolves to. Retention belongs to the generation store and its limit of "
                        + "five, not to this job")
                .hasSize(2);
        final List<String> firstGeneration =
                fixedWidthRecordsOf(generations.get(0), UNLOAD_RECORD_WIDTH);
        assertThat(fixedWidthRecordsOf(generations.get(1), UNLOAD_RECORD_WIDTH))
                .as("nothing changed between the runs, so the two unloads are byte-identical; a "
                        + "difference here would mean the unload was not a function of the cluster alone")
                .isEqualTo(firstGeneration);

        final long unloadBytes = Files.size(generations.get(0));
        final long reportBytes = Files.size(reportDataset());
        final List<String> capturedReport =
                fixedWidthRecordsOf(reportDataset(), REPORT_RECORD_WIDTH);

        assertThat(firstGeneration)
                .as("the unloaded generation of the first run was captured; an empty capture would mean"
                        + " the run that produces it did not complete")
                .isNotEmpty();
        assertThat(capturedReport)
                .as("the reprojection emits one line per unloaded record")
                .isNotEmpty()
                .hasSameSizeAs(firstGeneration);

        return new CapturedRuns(goldenRunReport, firstGeneration, capturedReport, unloadBytes,
                reportBytes);
    }

    /**
     * What the golden run and the two subject runs produced, as one immutable value.
     *
     * <p>This is what replaced four mutable instance fields shared between eight ordered tests. It is
     * handed to each consumer as a parameter, so the dependency is in the signature and an assertion
     * cannot be written that silently requires another test to have run first.
     *
     * @param goldenRunReport     the exact bytes the golden run wrote to the local report dataset, over a
     *                            state fixed before the run, which the committed oracle is compared with
     * @param unloadedRecords     the fifty-byte unload records of the first run, in written order
     * @param reportLines         the forty-byte report lines of the second run, in written order
     * @param unloadArtefactBytes encoded size of the unloaded artefact
     * @param reportArtefactBytes encoded size of the report artefact
     */
    private record CapturedRuns(byte[] goldenRunReport, List<String> unloadedRecords,
            List<String> reportLines, long unloadArtefactBytes, long reportArtefactBytes) {

        /** Copies the array and both lists defensively, so no consumer can alter what another reads. */
        CapturedRuns {
            goldenRunReport = goldenRunReport.clone();
            unloadedRecords = List.copyOf(unloadedRecords);
            reportLines = List.copyOf(reportLines);
        }

        /**
         * The golden run's report bytes, copied again so the caller cannot reach the held array.
         *
         * @return the exact bytes the golden run wrote
         */
        @Override
        public byte[] goldenRunReport() {
            return this.goldenRunReport.clone();
        }
    }

    /**
     * THE GATE 1 VERDICT for the fifth contractual width: the report the job wrote is byte-identical to
     * the committed forty-byte oracle.
     *
     * <p>Both sides are named explicitly, because which side is which is the whole point of this
     * assertion. The <strong>expected</strong> side is {@value #REPORT_GOLDEN_RESOURCE}, a file authored
     * separately from this class and from the job, committed to the fixture tree, never regenerated from a
     * run and never produced by calling the code under test. The <strong>actual</strong> side is the exact
     * bytes read back from the local dataset the job wrote, after the job read a real PostgreSQL server
     * through the delivered repository - no recording double, no in-memory substitute, and no
     * re-derivation of the bytes by this class.
     *
     * <p>The comparison is on raw byte arrays and nothing is trimmed, decoded, normalised or split first,
     * so a difference of one trailing blank, one suppressed leading zero, one transposed record or one
     * stray separator fails here. The three measurements that follow it are decomposition of the same
     * fact, kept because they name <em>what</em> differs when the arrays do: the record count, the stride
     * and the absence of a separator.
     *
     * @param runs the captured artefacts of the runs, handed in rather than found, so this assertion
     *             cannot run without them
     * @throws IOException if the committed oracle cannot be read
     */
    private void theGoldenRunIsByteIdenticalToTheCommittedOracle(final CapturedRuns runs)
            throws IOException {

        final byte[] expected = committedGoldenBytes();
        final byte[] actual = runs.goldenRunReport();

        assertThat(expected.length)
                .as("the committed oracle holds %d separator-free records of %d bytes",
                        GOLDEN_RECORD_COUNT, REPORT_RECORD_WIDTH)
                .isEqualTo(GOLDEN_RECORD_COUNT * REPORT_RECORD_WIDTH);
        assertThat(new String(expected, StandardCharsets.US_ASCII))
                .as("and it carries no separator byte of any kind, because the record length is the whole "
                        + "stride of a fixed-length dataset (DL-213)")
                .doesNotContain(FORBIDDEN_RECORD_SEPARATOR)
                .doesNotContain("\r");

        assertThat(actual)
                .as("the report the job wrote over the fixed state must equal the committed oracle byte "
                        + "for byte. Expected is %s; actual is the local dataset the job wrote after "
                        + "reading the real server", REPORT_GOLDEN_RESOURCE)
                .isEqualTo(expected);

        assertThat(actual.length % REPORT_RECORD_WIDTH)
                .as("decomposition of the same fact: the produced artefact divides exactly by the "
                        + "declared record length")
                .isZero();
        assertThat(actual.length / REPORT_RECORD_WIDTH)
                .as("and holds one line per row the golden run reported over")
                .isEqualTo(GOLDEN_RECORD_COUNT);
    }

    /**
     * Reads the committed forty-byte oracle whole, from the class path and from nowhere else.
     *
     * @return the oracle's exact bytes
     * @throws IOException if the resource is absent or cannot be read
     */
    private static byte[] committedGoldenBytes() throws IOException {
        final ClassPathResource oracle = new ClassPathResource(REPORT_GOLDEN_RESOURCE);
        assertThat(oracle.exists())
                .as("%s must be on the test class path: it is the verdict oracle for the estate's fifth "
                        + "fixed output width, and without it the width has no committed expectation at "
                        + "all", REPORT_GOLDEN_RESOURCE)
                .isTrue();
        try (InputStream bytes = oracle.getInputStream()) {
            return bytes.readAllBytes();
        }
    }

    /**
     * Asserts one contract of the two runs, as one dynamic test of
     * {@link #theTwoRunsAndEveryContractOfWhatTheyProduced()}.
     *
     * @param runs the captured artefacts of the two runs, handed in rather than
     *             found, so this assertion cannot run without them
     */
    private void theUnloadCarriesTheCategoryBalanceDecomposition(final CapturedRuns runs) {

        final long content = encodedLengthOf(runs.unloadedRecords());
        assertThat(content % UNLOAD_RECORD_WIDTH)
                .as("the unloaded artefact is a whole number of fifty-byte records; %d encoded byte(s) "
                        + "over %d record(s) leaves a remainder", content, runs.unloadedRecords().size())
                .isZero();
        assertThat(content).isEqualTo((long) runs.unloadedRecords().size() * UNLOAD_RECORD_WIDTH);
        assertThat(runs.unloadArtefactBytes())
                .as("the staged dataset carries NOTHING between two records, so the file measures exactly"
                        + " what its records measure - the declared fixed record format has no separator"
                        + " (DL-213)")
                .isEqualTo((long) runs.unloadedRecords().size() * UNLOAD_RECORD_WIDTH);

        for (final String record : runs.unloadedRecords()) {
            assertThat(record.getBytes(StandardCharsets.US_ASCII).length)
                    .as("every unloaded record is the declared fifty encoded bytes, never trimmed: <%s>",
                            record)
                    .isEqualTo(UNLOAD_RECORD_WIDTH);

            assertThat(record.substring(0, ACCOUNT_ID_WIDTH))
                    .as("an eleven-digit account identifier opens the record")
                    .hasSize(ACCOUNT_ID_WIDTH)
                    .containsOnlyDigits();
            assertThat(record.substring(ACCOUNT_ID_WIDTH, COMPOSITE_KEY_WIDTH - CATEGORY_CODE_WIDTH))
                    .as("a two-character type code follows it, and it is the one character-typed key of "
                            + "this specification, so it is not asserted to be numeric")
                    .hasSize(TYPE_CODE_WIDTH)
                    .isNotBlank();
            assertZonedField(
                    record.substring(COMPOSITE_KEY_WIDTH - CATEGORY_CODE_WIDTH, COMPOSITE_KEY_WIDTH),
                    CATEGORY_CODE_WIDTH, "the four-byte category code");
            assertZonedField(record.substring(COMPOSITE_KEY_WIDTH, MAPPED_PREFIX_WIDTH), BALANCE_WIDTH,
                    "the eleven-byte signed balance");
            assertThat(record.substring(MAPPED_PREFIX_WIDTH))
                    .as("a twenty-two-byte filler run closes the record. The module re-emits it as the "
                            + "space character because the copybook declares the run with no value clause "
                            + "and therefore fixes no byte value; the delivered fixture's own measured "
                            + "census, which is the ASCII digit zero, is asserted against the fixture "
                            + "itself rather than against this re-emission")
                    .hasSize(FILLER_WIDTH)
                    .isEqualTo(String.valueOf(REEMITTED_FILLER_CHARACTER).repeat(FILLER_WIDTH));

            // THE DECISIVE REFUTATION OF THE OTHER FIFTY-BYTE LAYOUT. In the cross-reference record the
            // byte at this index falls inside an eleven-digit account identifier and is therefore a
            // digit; here it is the overpunched final byte of the balance and is never one. A width
            // check cannot tell the two layouts apart, because both are fifty bytes - this can.
            final char signByte = record.charAt(MAPPED_PREFIX_WIDTH - 1);
            assertThat(SIGN_BYTES.indexOf(signByte))
                    .as("the byte at zero-based index %d is the overpunched sign of the balance, one of "
                            + "%s. In the cross-reference layout - a sixteen-character card number and a "
                            + "thirty-four-byte remainder, also totalling fifty bytes - the same index "
                            + "sits inside an eleven-digit account identifier, so it would be a digit "
                            + "there. It is '%s' here", MAPPED_PREFIX_WIDTH - 1, SIGN_BYTES, signByte)
                    .isNotNegative();
            assertThat(Character.isDigit(signByte))
                    .as("and it is provably not a digit, which is what the other fifty-byte layout would "
                            + "have put at that index")
                    .isFalse();
        }

        assertThat(CROSS_REFERENCE_KEY_WIDTH + CROSS_REFERENCE_REMAINDER_WIDTH)
                .as("the arithmetic that made the mis-attribution plausible: the cross-reference layout "
                        + "is also exactly fifty bytes")
                .isEqualTo(UNLOAD_RECORD_WIDTH);
        assertThat(COMPOSITE_KEY_WIDTH + BALANCE_WIDTH + FILLER_WIDTH)
                .as("and so is this one, from a completely different decomposition: seventeen plus eleven "
                        + "plus twenty-two")
                .isEqualTo(UNLOAD_RECORD_WIDTH);

        final List<TransactionCategoryBalance> rows = rowsInClusterKeyOrder();
        final List<String> expected = new ArrayList<>();
        rows.forEach(row -> expected.add(expectedUnloadRecord(row)));
        assertThat(runs.unloadedRecords())
                .as("byte for byte against an image this class builds from the layout alone, in the "
                        + "cluster's own composite-key order, which is the order a sequential read of an "
                        + "indexed cluster returns")
                .isEqualTo(expected);
    }

    /**
     * Asserts one contract of the two runs, as one dynamic test of
     * {@link #theTwoRunsAndEveryContractOfWhatTheyProduced()}.
     *
     * @param runs the captured artefacts of the two runs, handed in rather than
     *             found, so this assertion cannot run without them
     */
    private void theReportResolvesTheOneByteConflictToForty(final CapturedRuns runs) {

        assertThat(OVER_RUN_RECORD_WIDTH)
                .as("the conflict, stated as arithmetic: thirty-two content bytes plus the nine-byte "
                        + "blank run the reprojection at lines 53 to 56 declares is forty-one, against "
                        + "the record length of forty declared at line 61")
                .isEqualTo(REPORT_RECORD_WIDTH + 1);
        assertThat(REPORT_TRAILING_BLANKS)
                .as("resolved in favour of the declared record length, so the run is eight and not nine")
                .isEqualTo(DECLARED_TRAILING_BLANKS - 1)
                .isEqualTo(8);

        final long content = encodedLengthOf(runs.reportLines());
        assertThat(content % REPORT_RECORD_WIDTH)
                .as("the reprojected artefact is a whole number of forty-byte records; %d encoded byte(s) "
                        + "over %d line(s) leaves a remainder", content, runs.reportLines().size())
                .isZero();
        assertThat(content).isEqualTo((long) runs.reportLines().size() * REPORT_RECORD_WIDTH);
        assertThat(runs.reportArtefactBytes())
                .as("no separator byte on the staged dataset either, as with the unload")
                .isEqualTo((long) runs.reportLines().size() * REPORT_RECORD_WIDTH);

        for (final String line : runs.reportLines()) {
            final int encoded = line.getBytes(StandardCharsets.US_ASCII).length;
            assertThat(encoded)
                    .as("the declared record length is the dataset contract, measured in encoded bytes "
                            + "and never trimmed: <%s>", line)
                    .isEqualTo(REPORT_RECORD_WIDTH);
            assertThat(encoded)
                    .as("and never the forty-first byte the declared blank run would have produced")
                    .isNotEqualTo(OVER_RUN_RECORD_WIDTH);

            final String trailing = line.substring(REPORT_CONTENT_WIDTH);
            assertThat(trailing)
                    .as("exactly eight trailing blanks. Eight against nine is precisely the defect, so "
                            + "the count is asserted rather than the emptiness of a trimmed tail")
                    .hasSize(REPORT_TRAILING_BLANKS)
                    .isEqualTo(String.valueOf(BLANK).repeat(REPORT_TRAILING_BLANKS));
            assertThat(trailing.chars().filter(character -> character == BLANK).count())
                    .as("counted, so a nine-blank run could not satisfy this by being mostly blank")
                    .isEqualTo(REPORT_TRAILING_BLANKS);

            assertThat(line.charAt(ACCOUNT_ID_WIDTH))
                    .as("a single blank separates the account identifier from the type code")
                    .isEqualTo(BLANK);
            assertThat(line.charAt(typeCodeOffset() + TYPE_CODE_WIDTH))
                    .as("a single blank separates the type code from the category code")
                    .isEqualTo(BLANK);
            assertThat(line.charAt(categoryCodeOffset() + CATEGORY_CODE_WIDTH))
                    .as("a single blank separates the category code from the edited balance")
                    .isEqualTo(BLANK);

            final String mask = editedBalanceOf(line);
            assertThat(mask)
                    .as("the edited balance occupies twelve characters: nine integer digits, the point "
                            + "and two decimals")
                    .hasSize(BALANCE_MASK_WIDTH)
                    .doesNotContain(String.valueOf(BLANK));
            assertThat(mask.charAt(BALANCE_INTEGER_DIGITS))
                    .as("the decimal point sits at a FIXED position, the tenth character of the mask; a "
                            + "floating point would move with the magnitude and every downstream reader "
                            + "of a fixed-width dataset would read the wrong field: <%s>", mask)
                    .isEqualTo(DECIMAL_POINT);
            assertThat(mask.substring(0, BALANCE_INTEGER_DIGITS))
                    .as("every declared integer position carries a digit, leading zeros included, because "
                            + "the mask is written entirely from the always-print selector and never from "
                            + "the zero-suppressing one")
                    .containsOnlyDigits();
            assertThat(mask.substring(BALANCE_INTEGER_DIGITS + 1))
                    .as("and both fractional positions carry a digit")
                    .hasSize(BALANCE_SCALE)
                    .containsOnlyDigits();
        }

        assertThat(reportLineFor(runs, SEEDED_TYPE_CODE, NEGATIVE_CATEGORY_CODE))
                .as("a positive balance renders its digits with the point fixed")
                .isEqualTo(expectedReportLine(DISCRIMINATING_ACCOUNT, SEEDED_TYPE_CODE,
                        NEGATIVE_CATEGORY_CODE, BALANCE_ON_NEGATIVE_CATEGORY));
        assertThat(reportLineFor(runs, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE))
                .as("a NEGATIVE balance renders the same twelve-character shape at the same fixed point. "
                        + "The specification requests no sign character, so the magnitude is what appears "
                        + "and a negative balance is indistinguishable from its positive counterpart in "
                        + "this report - a property of the specification, not of the translation")
                .isEqualTo(expectedReportLine(DISCRIMINATING_ACCOUNT, SEEDED_TYPE_CODE,
                        SEEDED_CATEGORY_CODE, NEGATIVE_BALANCE));
        assertThat(reportLineFor(runs, SEEDED_TYPE_CODE, HIGHER_CATEGORY_CODE))
                .as("a balance filling every one of the nine declared integer digits still fits the mask")
                .isEqualTo(expectedReportLine(DISCRIMINATING_ACCOUNT, SEEDED_TYPE_CODE,
                        HIGHER_CATEGORY_CODE, WIDEST_BALANCE));
        assertThat(reportLineFor(runs, TRAILING_TYPE_CODE, SEEDED_CATEGORY_CODE))
                .as("and a balance of exactly zero renders nine zeros, the point and two more zeros "
                        + "rather than blanking the field")
                .isEqualTo(expectedReportLine(DISCRIMINATING_ACCOUNT, TRAILING_TYPE_CODE,
                        SEEDED_CATEGORY_CODE, ZERO_BALANCE));
    }

    /**
     * Asserts one contract of the two runs, as one dynamic test of
     * {@link #theTwoRunsAndEveryContractOfWhatTheyProduced()}.
     *
     * @param runs the captured artefacts of the two runs, handed in rather than
     *             found, so this assertion cannot run without them
     */
    private void theOrderingIsAccountThenTypeThenCategoryAscending(final CapturedRuns runs) {

        final List<TransactionCategoryBalance> rows = rowsInClusterKeyOrder();
        final List<TransactionCategoryBalance> ordered = new ArrayList<>(rows);
        ordered.sort(expectedSortSpecification());
        final List<String> expected = new ArrayList<>();
        ordered.forEach(row -> expected.add(expectedReportLine(row)));
        assertThat(runs.reportLines())
                .as("byte for byte against a projection this class orders itself, by decoding the two "
                        + "zoned keys and comparing the character key as characters. No level of the "
                        + "ordering is reordered and none is dropped")
                .isEqualTo(expected);

        final List<String> keys = new ArrayList<>();
        runs.reportLines().forEach(line -> keys.add(keyPrefixOf(line)));

        final List<Long> accounts = new ArrayList<>();
        runs.reportLines().forEach(line ->
                accounts.add(decodeZonedKey(accountIdentifierOf(line), ACCOUNT_ID_WIDTH)));
        assertThat(accounts)
                .as("first level: the account identifier ascending across the whole report")
                .isSorted();

        assertThat(keys)
                .as("second and third levels, on one account so the first level is a tie throughout: the "
                        + "character type code orders 01 before 0Z, and within one type code the "
                        + "zoned-decimal category code orders minus one before plus one before plus two - "
                        + "which a character comparison would reverse, putting 000J last")
                .containsSubsequence(
                        keyPrefix(DISCRIMINATING_ACCOUNT, SEEDED_TYPE_CODE, NEGATIVE_CATEGORY_CODE),
                        keyPrefix(DISCRIMINATING_ACCOUNT, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE),
                        keyPrefix(DISCRIMINATING_ACCOUNT, SEEDED_TYPE_CODE, HIGHER_CATEGORY_CODE),
                        keyPrefix(DISCRIMINATING_ACCOUNT, TRAILING_TYPE_CODE, SEEDED_CATEGORY_CODE));

        assertThat(keys)
                .as("the report as a whole is NOT in character order, and that is what proves the two "
                        + "zoned keys were compared as numbers rather than as text")
                .isNotEqualTo(keys.stream().sorted().toList());

        final List<TransactionCategoryBalance> byBalance = new ArrayList<>(ordered);
        byBalance.sort(Comparator.comparing(TransactionCategoryBalance::getTranCatBal));
        final List<String> keysByBalance = new ArrayList<>();
        byBalance.forEach(row -> keysByBalance.add(
                keyPrefix(row.getTrancatAcctId(), row.getTrancatTypeCd(), row.getTrancatCd())));
        assertThat(keys)
                .as("the balance is declared as a field by the specification and is NOT one of its three "
                        + "keys. The rows added here carry balances whose own order contradicts the key "
                        + "order, so an ordering that consulted the balance would differ from this one")
                .isNotEqualTo(keysByBalance);
    }

    /**
     * Asserts one contract of the two runs, as one dynamic test of
     * {@link #theTwoRunsAndEveryContractOfWhatTheyProduced()}.
     */
    private void theDeliveredFixtureCarriesTheMeasuredCensusForThisRecord() {
        final List<String> fixture = readFixtureRecords(CATEGORY_BALANCE_FIXTURE);

        assertThat(fixture)
                .as("the delivered category-balance fixture, discovered on the test classpath rather than "
                        + "read from the legacy tree")
                .hasSize(SEEDED_ROWS);

        final String positiveZeroBalance = leftPadZeros("", BALANCE_WIDTH - 1)
                + POSITIVE_OVERPUNCH.charAt(0);
        for (final String record : fixture) {
            assertThat(record.getBytes(StandardCharsets.US_ASCII).length)
                    .as("fifty encoded bytes per record: <%s>", record)
                    .isEqualTo(UNLOAD_RECORD_WIDTH);
            assertThat(record.substring(0, ACCOUNT_ID_WIDTH)).containsOnlyDigits();
            assertThat(record.substring(ACCOUNT_ID_WIDTH, COMPOSITE_KEY_WIDTH - CATEGORY_CODE_WIDTH))
                    .hasSize(TYPE_CODE_WIDTH);
            assertThat(record.substring(COMPOSITE_KEY_WIDTH - CATEGORY_CODE_WIDTH, COMPOSITE_KEY_WIDTH))
                    .containsOnlyDigits();
            assertThat(record.substring(COMPOSITE_KEY_WIDTH, MAPPED_PREFIX_WIDTH))
                    .as("one distinct balance image across all fifty rows, and it is the positive-zero "
                            + "overpunch this class builds independently from the sign table")
                    .isEqualTo(positiveZeroBalance);
            assertThat(record.substring(MAPPED_PREFIX_WIDTH))
                    .as("THIS record's filler is the ASCII digit zero. The census is measured and is not "
                            + "uniform: the account, card, customer and daily-transaction fixtures pad "
                            + "with the space character and the cross-reference fixture materialises no "
                            + "filler at all, so the fillers are deliberately not unified")
                    .hasSize(FILLER_WIDTH)
                    .isEqualTo(String.valueOf(FIXTURE_FILLER_CHARACTER).repeat(FILLER_WIDTH));
        }

        final String firstRecord = fixture.get(0);
        final TransactionCategoryBalanceId identifier = new TransactionCategoryBalanceId(
                firstRecord.substring(0, ACCOUNT_ID_WIDTH),
                firstRecord.substring(ACCOUNT_ID_WIDTH, COMPOSITE_KEY_WIDTH - CATEGORY_CODE_WIDTH),
                firstRecord.substring(COMPOSITE_KEY_WIDTH - CATEGORY_CODE_WIDTH, COMPOSITE_KEY_WIDTH));
        assertThat(identifier.getTrancatAcctId() + identifier.getTrancatTypeCd()
                + identifier.getTrancatCd())
                .as("the composite identifier is constructed account, then type, then category, and its "
                        + "three parts reassemble the record's own leading seventeen bytes in that order")
                .isEqualTo(firstRecord.substring(0, COMPOSITE_KEY_WIDTH))
                .hasSize(COMPOSITE_KEY_WIDTH);
        assertThat(COMPOSITE_KEY_WIDTH)
                .as("seventeen bytes, which is also the key length the cluster definition declares. It is "
                        + "not the six-byte transaction-category composite - the two are similarly named "
                        + "in the legacy and conflating them would key this job on the wrong record")
                .isEqualTo(17)
                .isNotEqualTo(TRANSACTION_CATEGORY_KEY_WIDTH);
        assertThat(this.categoryBalanceRepository.findById(identifier))
                .as("and that ordering resolves a real row, so the argument order is the schema's own")
                .isPresent();

        assertThat(readFixtureRecords(CROSS_REFERENCE_FIXTURE))
                .as("the layout the planning material confused this one with. Its copybook record is also "
                        + "fifty bytes - a sixteen-character card number and a thirty-four-byte remainder "
                        + "- but its delivered fixture materialises thirty-six bytes and no filler, which "
                        + "is a second way the two are told apart once a width check has failed to")
                .isNotEmpty()
                .allSatisfy(record -> assertThat(record.getBytes(StandardCharsets.US_ASCII).length)
                        .isEqualTo(CROSS_REFERENCE_FIXTURE_RECORD_WIDTH));
    }

    /**
     * Asserts one contract of the two runs, as one dynamic test of
     * {@link #theTwoRunsAndEveryContractOfWhatTheyProduced()}.
     */
    private void theUnloadUsesTheGenericCategoryBalanceEntryPoint() {
        final List<String> observed = new ArrayList<>();
        final FileMaintenanceService.FileReadSummary summary =
                this.fileMaintenanceService.readTransactionCategoryBalanceFile(record ->
                        observed.add(keyPrefix(record.getTrancatAcctId(), record.getTrancatTypeCd(),
                                record.getTrancatCd())));

        assertThat(summary.endedAtEndOfFile())
                .as("end of file is the only route by which a sequential reader returns rather than "
                        + "abending; the terminal status was <%s>", summary.terminalFileStatus())
                .isTrue();
        assertThat(summary.recordsRead())
                .as("every row of the cluster, counted by the pass itself")
                .isEqualTo(this.categoryBalanceRepository.count());
        assertThat(observed)
                .as("the pass delivers each record as it reads it, so the sink sees exactly the rows the "
                        + "count reports")
                .hasSize((int) summary.recordsRead());

        final List<String> expected = new ArrayList<>();
        rowsInClusterKeyOrder().forEach(row -> expected.add(
                keyPrefix(row.getTrancatAcctId(), row.getTrancatTypeCd(), row.getTrancatCd())));
        assertThat(observed)
                .as("in the cluster's own composite key order, which is the order an explicitly ascending "
                        + "repository read returns. No cross-reference-oriented entry point of the shared "
                        + "discipline is used here: that one belongs to the file-probe job")
                .isEqualTo(expected);
    }

    /**
     * Asserts one contract of the two runs, as one dynamic test of
     * {@link #theTwoRunsAndEveryContractOfWhatTheyProduced()}.
     */
    private void theProgramLifecycleTimersArePresentAndTagged() {
        for (final String legacyStep : List.of(LEGACY_CLEAR_STEP, LEGACY_UNLOAD_STEP, LEGACY_SORT_STEP)) {
            final Timer timer = this.meterRegistry.find(COBOL_STEP_TIMER)
                    .tag(TIMER_STEP_TAG, legacyStep)
                    .tag(TIMER_OUTCOME_TAG, TIMER_COMPLETED_OUTCOME)
                    .timer();
            assertThat(timer)
                    .as("the shared batch-program skeleton records one timer per lifecycle under <%s>, "
                            + "tagged with the legacy step <%s>; its absence means the step ran outside "
                            + "the instrumented skeleton", COBOL_STEP_TIMER, legacyStep)
                    .isNotNull();
            assertThat(timer.count())
                    .as("both runs of the job recorded a completed lifecycle for <%s>. Only the presence "
                            + "and the shape of the instrument are asserted; no latency, throughput or "
                            + "memory figure is, because the legacy estate documents none to compare "
                            + "against", legacyStep)
                    .isGreaterThanOrEqualTo(2L);
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Launching, and the assertions shared by both runs.
    // -----------------------------------------------------------------------------------------------

    /**
     * Advances one job to its next instance by name and returns the execution that ran.
     *
     * <p>By name and through the framework's own operator, because that is how the module's operational
     * surface starts a job; no job name is written into this class as a literal.
     *
     * @param jobName the registered job name
     * @return the execution the launch produced
     * @throws Exception if the framework refuses the launch
     */
    private JobExecution launch(final String jobName) throws Exception {
        return this.jobExplorer.getJobExecution(this.jobOperator.startNextInstance(jobName));
    }

    /**
     * Asserts one execution completed with all three of its steps completed.
     *
     * @param execution the execution to inspect
     */
    private static void assertCompletedWithThreeSteps(final JobExecution execution) {
        assertThat(execution.getStatus())
                .as("the whole job completes; the member gates nothing, so no step may be skipped")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions())
                .as("all three steps ran and all three completed, which is what having no condition-code "
                        + "dependency means")
                .hasSize(CategoryBalanceReportJobConfig.STEP_COUNT)
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED));
        assertThat(execution.getStepExecutions())
                .extracting(StepExecution::getStepName)
                .doesNotHaveDuplicates();
        assertThat(execution.getFailureExceptions())
                .as("a failure-ending transition would leave a recorded failure behind")
                .isEmpty();
    }

    /**
     * Asserts the launch surface carries neither a date parameter nor a card-number parameter.
     *
     * <p>This guards the comment at line 41 of the member, which claims a parameter-date filter and a
     * card-number ordering that its control stream does not contain. The only parameter a launch may carry
     * is the run identity the shared incrementer mints, which is what lets the job be submitted again.
     *
     * @param execution the execution to inspect
     */
    private static void assertNoDateOrCardParameter(final JobExecution execution) {
        final List<String> keys =
                new ArrayList<>(execution.getJobParameters().getParameters().keySet());
        assertThat(keys)
                .as("no date parameter: the member passes no parameter string, so a date key here would "
                        + "be the filter the line-41 comment describes and the control stream refuses")
                .allSatisfy(key -> assertThat(key.toLowerCase(Locale.ROOT)).doesNotContain("date"))
                .as("and no card-number key either, for the same reason")
                .allSatisfy(key -> assertThat(key.toLowerCase(Locale.ROOT)).doesNotContain("card"))
                .as("nothing beyond the run identity the incrementer mints")
                .hasSizeLessThanOrEqualTo(1);
    }

    /**
     * Counts the instances of the subject job the framework has recorded.
     *
     * @return the instance count
     */
    private int reportJobInstanceCount() {
        return this.jobExplorer
                .getJobInstances(CategoryBalanceReportJobConfig.JOB_NAME, 0, INSTANCE_QUERY_CEILING)
                .size();
    }

    // -----------------------------------------------------------------------------------------------
    // Staged datasets, all resolved from configuration by logical name.
    // -----------------------------------------------------------------------------------------------

    /**
     * The staging root the context resolved for this run, as a path.
     *
     * <p>Read from the injected configuration value rather than recomputed, so this is by construction the
     * very directory both job configurations were given.
     *
     * @return this run's own staging root
     */
    private Path stagingRoot() {
        return Path.of(this.stagingDirectory);
    }


    /**
     * The report dataset, resolved by its configured logical name within the configured directory.
     *
     * @return the report dataset
     */
    private Path reportDataset() {
        return stagingRoot().resolve(this.reportDatasetName);
    }

    /**
     * Every completed backup generation present in the staging directory, in name order.
     *
     * <p>A working file still being written carries a distinct suffix and is excluded, so a generation is
     * counted only once it has been sealed.
     *
     * @return the completed generations
     * @throws IOException if the staging directory cannot be listed
     */
    private List<Path> backupGenerations() throws IOException {
        try (Stream<Path> entries = Files.list(stagingRoot())) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> generationName(path).startsWith(this.backupDatasetBaseName + '.'))
                    .filter(path -> !generationName(path).endsWith(WORKING_FILE_SUFFIX))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Removes datasets of this job's own two logical names left by an <em>earlier assertion of this
     * class</em>.
     *
     * <p>The assertions of this class are ordered and share one staging root, and an earlier one produces a
     * report. This removes it so the next can assert that the job clears down an output that is not there.
     * Only the two logical names this job owns are touched, so the prerequisite run's own staged input and
     * reject generations are left alone. This establishes the precondition the clear-down contract is then
     * asserted against rather than standing in for it: the job, not this method, is what has to survive an
     * absent output.
     *
     * <p><strong>It is not protection against an earlier build.</strong> It used to be described that way,
     * and while the root was a fixed directory beneath the platform temporary directory that description
     * was accurate: the directory outlived the build while the framework's execution identifiers restarted
     * against a fresh database, so a generation from an earlier build could appear to belong to an
     * execution of this one. The root is now private to this process and discarded when the class finishes
     * (DL-273), so no artefact of another build or another clone can be present to remove, and a reader
     * should not infer from this method that one could be.
     *
     * @throws IOException if the staging directory cannot be listed or an entry cannot be removed
     */
    private void removeStaleArtefactsOfThisJob() throws IOException {
        try (Stream<Path> entries = Files.list(stagingRoot())) {
            for (final Path entry : entries.toList()) {
                final String name = generationName(entry);
                if (Files.isRegularFile(entry)
                        && (name.startsWith(this.reportDatasetName)
                                || name.startsWith(this.backupDatasetBaseName))) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    /**
     * Stages the delivered daily-transaction fixture under the logical name the prerequisite job resolves.
     *
     * @throws IOException if the fixture cannot be read or the staged copy cannot be written
     */
    private void stageDailyTransactionInput() throws IOException {
        final Path staged = stagingRoot().resolve(PostTransactionJobConfig.DEFAULT_DALYTRAN_DATASET);
        try (InputStream fixture = new ClassPathResource(DAILY_TRANSACTION_FIXTURE).getInputStream()) {
            Files.copy(fixture, staged, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The file name of one staged entry, named rather than assumed so a rootless path cannot slip through.
     *
     * @param staged the staged entry
     * @return its file name
     */
    private static String generationName(final Path staged) {
        final Path fileName = staged.getFileName();
        assertThat(fileName)
                .as("a staged dataset always names a file; <%s> names none", staged)
                .isNotNull();
        return fileName.toString();
    }

    // -----------------------------------------------------------------------------------------------
    // The rows this class adds, written through the real repository rather than through a statement.
    // -----------------------------------------------------------------------------------------------

    /**
     * Adds the rows that separate a numeric key reading from a character one, and whose balances are
     * deliberately ordered against their keys.
     *
     * <p>Written through the repository and the entity, so no statement text is assembled anywhere in this
     * class and the values pass through exactly the mapping the job reads them back through. All four rows
     * sit on one seeded account, so the first ordering level is a tie across them and the second and third
     * levels are what separates them. No two of them decode to the same key triple, so the ordering is a
     * strict total order and nothing depends on how equal keys happen to be arranged.
     */
    private void addDiscriminatingRows() {
        final List<TransactionCategoryBalance> rows = List.of(
                categoryBalance(SEEDED_TYPE_CODE, NEGATIVE_CATEGORY_CODE, BALANCE_ON_NEGATIVE_CATEGORY),
                categoryBalance(SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE, NEGATIVE_BALANCE),
                categoryBalance(SEEDED_TYPE_CODE, HIGHER_CATEGORY_CODE, WIDEST_BALANCE),
                categoryBalance(TRAILING_TYPE_CODE, SEEDED_CATEGORY_CODE, ZERO_BALANCE));
        this.categoryBalanceRepository.saveAll(rows);
        this.categoryBalanceRepository.flush();

        for (final TransactionCategoryBalance row : rows) {
            assertThat(this.categoryBalanceRepository.findById(row.toId()))
                    .as("each added row is readable back by its own composite identifier <%s>", row.toId())
                    .isPresent();
        }
    }

    /**
     * Builds one category-balance row on the discriminating account.
     *
     * @param typeCode the two-character type code
     * @param categoryCode the four-byte category code
     * @param balance the balance to store
     * @return the row
     */
    private static TransactionCategoryBalance categoryBalance(final String typeCode,
            final String categoryCode, final BigDecimal balance) {

        return TestDataFactory.transactionCategoryBalance()
                .accountId(DISCRIMINATING_ACCOUNT)
                .typeCode(typeCode)
                .categoryCode(categoryCode)
                .balance(balance)
                .build();
    }

    /**
     * The distinct balance values the table currently holds.
     *
     * @return the distinct balances, each at the column's own scale
     */
    private List<BigDecimal> distinctBalances() {
        return this.categoryBalanceRepository.findAll().stream()
                .map(TransactionCategoryBalance::getTranCatBal)
                .distinct()
                .toList();
    }

    /**
     * Every row in the cluster's own composite key order.
     *
     * <p>The ordering is stated explicitly because no repository method imposes one, and a read whose order
     * was left to the server could agree with an expectation by accident.
     *
     * @return the rows, ascending by account, then type, then category
     */
    private List<TransactionCategoryBalance> rowsInClusterKeyOrder() {
        return this.categoryBalanceRepository.findAll(
                Sort.by(Sort.Direction.ASC, "trancatAcctId", "trancatTypeCd", "trancatCd"));
    }

    // -----------------------------------------------------------------------------------------------
    // THE DECOMPOSITION. Nothing below delegates to the job configuration, to the shared zoned-decimal
    // codec or to any record mapper: every image, mask and ordering is built here from the layout and
    // from a locally declared sign table, so an error copied into the production encoder cannot be
    // copied into an expectation assembled here.
    //
    // WHAT THIS IS NOT. It is not the verdict for the forty-byte contract. Independence from the code
    // under test is necessary and insufficient: a reading of the layout written here by the same author
    // who read it for the production side can reproduce the same misunderstanding, and then both sides
    // agree and nothing catches it. The verdict is the committed golden - see
    // theGoldenRunIsByteIdenticalToTheCommittedOracle - and the builders below serve two other purposes:
    // they name WHICH field of WHICH record differs when that byte comparison fails, and they let the
    // report produced over the POSTED state be checked field by field, which no committed file can do
    // because those balances are a function of a three-hundred-record posting run.
    // -----------------------------------------------------------------------------------------------

    /**
     * Reads one staged artefact back as its record images, framing it the way its DD declares.
     *
     * <p>Framed by width and never by line, and the absence of
     * {@value #FORBIDDEN_RECORD_SEPARATOR} is proved before any record is handed back - so a
     * separator regression fails here instead of shifting every later assertion by one ordinal.
     * See {@code docs/decision-log.md} entry DL-213.
     *
     * @param  artefact     the artefact to read
     * @param  recordLength the declared record length
     * @return the record images in emission order
     */
    private static List<String> fixedWidthRecordsOf(final Path artefact, final int recordLength) {
        final byte[] image;
        try {
            image = Files.readAllBytes(artefact);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("the staged artefact " + artefact
                    + " could not be read", unreadable);
        }
        assertThat(image.length % recordLength)
                .as("a fixed-length artefact carries no separator, so its length is a whole number of"
                        + " %d-byte records; it measured %d bytes",
                        Integer.valueOf(recordLength), Integer.valueOf(image.length))
                .isZero();
        assertThat(new String(image, StandardCharsets.US_ASCII))
                .as("no line feed may appear anywhere in a fixed-length artefact")
                .doesNotContain(FORBIDDEN_RECORD_SEPARATOR);
        final List<String> records = new ArrayList<>(image.length / recordLength);
        for (int offset = 0; offset < image.length; offset += recordLength) {
            records.add(new String(image, offset, recordLength, StandardCharsets.US_ASCII));
        }
        return records;
    }

    /**
     * The total encoded length of a list of record images, excluding any separator between them.
     *
     * @param images the record images
     * @return the summed encoded byte count
     */
    private static long encodedLengthOf(final List<String> images) {
        long total = 0L;
        for (final String image : images) {
            total += image.getBytes(StandardCharsets.US_ASCII).length;
        }
        return total;
    }

    /**
     * Builds the fifty-byte record image one row unloads as.
     *
     * <p>The numeric key parts are right justified and zero filled, which preserves their significant
     * leading zeros; the type code is left justified because it is character data; the balance carries its
     * sign in its final byte; and the trailing run is the filler the module re-emits.
     *
     * @param row the row to encode
     * @return the fifty-character record image
     */
    private static String expectedUnloadRecord(final TransactionCategoryBalance row) {
        final String image = leftPadZeros(row.getTrancatAcctId(), ACCOUNT_ID_WIDTH)
                + rightPadBlanks(row.getTrancatTypeCd(), TYPE_CODE_WIDTH)
                + leftPadZeros(row.getTrancatCd(), CATEGORY_CODE_WIDTH)
                + zonedBalanceImage(row.getTranCatBal())
                + String.valueOf(REEMITTED_FILLER_CHARACTER).repeat(FILLER_WIDTH);
        assertThat(image.getBytes(StandardCharsets.US_ASCII).length)
                .as("the oracle's own image must be the declared width before it may be compared: <%s>",
                        image)
                .isEqualTo(UNLOAD_RECORD_WIDTH);
        return image;
    }

    /**
     * Builds the forty-byte report line one row projects to.
     *
     * @param row the row to project
     * @return the forty-character report line
     */
    private static String expectedReportLine(final TransactionCategoryBalance row) {
        return expectedReportLine(row.getTrancatAcctId(), row.getTrancatTypeCd(), row.getTrancatCd(),
                row.getTranCatBal());
    }

    /**
     * Builds the forty-byte report line one key and balance project to.
     *
     * <p>Thirty-two content bytes - the three key parts with a single blank between each pair, then the
     * twelve-character edited balance - followed by exactly eight blanks. Never nine, and therefore never
     * forty-one bytes.
     *
     * @param accountId the account identifier
     * @param typeCode the type code
     * @param categoryCode the category code
     * @param balance the balance
     * @return the forty-character report line
     */
    private static String expectedReportLine(final String accountId, final String typeCode,
            final String categoryCode, final BigDecimal balance) {

        final StringBuilder line = new StringBuilder(REPORT_RECORD_WIDTH);
        line.append(leftPadZeros(accountId, ACCOUNT_ID_WIDTH))
                .append(BLANK)
                .append(rightPadBlanks(typeCode, TYPE_CODE_WIDTH))
                .append(BLANK)
                .append(leftPadZeros(categoryCode, CATEGORY_CODE_WIDTH))
                .append(BLANK)
                .append(editedBalanceMask(balance));
        assertThat(line.length())
                .as("the oracle's own content run must be thirty-two bytes before the blanks are added")
                .isEqualTo(REPORT_CONTENT_WIDTH);
        line.append(String.valueOf(BLANK).repeat(REPORT_TRAILING_BLANKS));

        final String image = line.toString();
        assertThat(image.getBytes(StandardCharsets.US_ASCII).length)
                .as("and the completed line must be forty encoded bytes: <%s>", image)
                .isEqualTo(REPORT_RECORD_WIDTH);
        return image;
    }

    /**
     * Renders the twelve-character edit mask: nine integer digits, the point and two decimals.
     *
     * <p>Every declared digit position carries a digit, leading zeros included, and no position is ever
     * blanked. The specification requests no sign character, so the magnitude is rendered and a negative
     * balance is indistinguishable from its positive counterpart.
     *
     * @param balance the balance to render
     * @return the twelve-character mask
     */
    private static String editedBalanceMask(final BigDecimal balance) {
        final String digits = magnitudeDigits(balance);
        return digits.substring(0, BALANCE_INTEGER_DIGITS) + DECIMAL_POINT
                + digits.substring(BALANCE_INTEGER_DIGITS);
    }

    /**
     * Renders the eleven-byte zoned-decimal balance image, folding the sign into the final byte.
     *
     * @param balance the balance to encode
     * @return the eleven-character field image
     */
    private static String zonedBalanceImage(final BigDecimal balance) {
        final String digits = magnitudeDigits(balance);
        final int finalDigit = digits.charAt(BALANCE_WIDTH - 1) - ZERO_DIGIT;
        final String overpunch = balance.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        return digits.substring(0, BALANCE_WIDTH - 1) + overpunch.charAt(finalDigit);
    }

    /**
     * The magnitude of one balance as exactly eleven digits, left padded.
     *
     * <p>The stored scale is asserted rather than imposed. The column carries two implied decimal
     * positions, so every value read back from it already has them; a value at any other scale did not come
     * from the report's own source and rescaling it here would hide that rather than reveal it.
     *
     * @param balance the balance whose magnitude is wanted
     * @return the eleven magnitude digits
     */
    private static String magnitudeDigits(final BigDecimal balance) {
        assertThat(balance.scale())
                .as("the balance column carries two implied decimal positions, so <%s> should already be "
                        + "at that scale; the oracle rescales nothing, because the estate declares no "
                        + "rounding anywhere and truncation is the module-wide rule", balance)
                .isEqualTo(BALANCE_SCALE);
        final String digits = balance.unscaledValue().abs().toString();
        assertThat(digits.length())
                .as("<%s> needs more than the eleven digit positions the field declares", balance)
                .isLessThanOrEqualTo(BALANCE_WIDTH);
        return leftPadZeros(digits, BALANCE_WIDTH);
    }

    /**
     * Reads one zoned-decimal field as a signed number, honouring the overpunched final byte.
     *
     * @param image the field image, which may be narrower than the declared field
     * @param width the declared field width
     * @return the signed value the field carries
     */
    private static long decodeZonedKey(final String image, final int width) {
        final String padded = leftPadZeros(image, width);
        final String leading = padded.substring(0, width - 1);
        final char last = padded.charAt(width - 1);
        if (Character.isDigit(last)) {
            return Long.parseLong(leading + last);
        }
        final int positive = POSITIVE_OVERPUNCH.indexOf(last);
        if (positive >= 0) {
            return Long.parseLong(leading + (char) (ZERO_DIGIT + positive));
        }
        final int negative = NEGATIVE_OVERPUNCH.indexOf(last);
        if (negative >= 0) {
            return -Long.parseLong(leading + (char) (ZERO_DIGIT + negative));
        }
        throw new AssertionError("the final byte of the zoned-decimal field <" + image + "> is '" + last
                + "', which is neither a digit nor one of the overpunched signs "
                + POSITIVE_OVERPUNCH + NEGATIVE_OVERPUNCH + "; it cannot be read as a signed zoned"
                + " decimal at width " + width);
    }

    /**
     * The ordering this member's own sort specification declares, built here rather than borrowed.
     *
     * <p>Account identifier ascending as a signed zoned-decimal value, then type code ascending as
     * character data, then category code ascending as a signed zoned-decimal value. This one specification
     * therefore mixes the two typings within itself, which is why the production comparator that
     * corresponds to it is private to its own job and is never shared with another. Each stage is a fully
     * witnessed comparator so that the character key cannot be resolved against a numeric overload by
     * inference.
     *
     * @return the expected ordering
     */
    private static Comparator<TransactionCategoryBalance> expectedSortSpecification() {
        return Comparator.<TransactionCategoryBalance, Long>comparing(
                        row -> decodeZonedKey(row.getTrancatAcctId(), ACCOUNT_ID_WIDTH))
                .thenComparing(Comparator.<TransactionCategoryBalance, String>comparing(
                        TransactionCategoryBalance::getTrancatTypeCd))
                .thenComparing(Comparator.<TransactionCategoryBalance, Long>comparing(
                        row -> decodeZonedKey(row.getTrancatCd(), CATEGORY_CODE_WIDTH)));
    }

    /**
     * Right justifies a value in a numeric field by padding it with the zero digit.
     *
     * @param value the value to place
     * @param width the field width
     * @return the placed value
     */
    private static String leftPadZeros(final String value, final int width) {
        assertThat(value.length())
                .as("<%s> is wider than the %d-byte field it is placed in", value, width)
                .isLessThanOrEqualTo(width);
        return String.valueOf(ZERO_DIGIT).repeat(width - value.length()) + value;
    }

    /**
     * Left justifies a value in a character field by padding it with blanks.
     *
     * @param value the value to place
     * @param width the field width
     * @return the placed value
     */
    private static String rightPadBlanks(final String value, final int width) {
        assertThat(value.length())
                .as("<%s> is wider than the %d-byte field it is placed in", value, width)
                .isLessThanOrEqualTo(width);
        return value + String.valueOf(BLANK).repeat(width - value.length());
    }

    // -----------------------------------------------------------------------------------------------
    // Field placements within one record, and the slices this class reads back.
    // -----------------------------------------------------------------------------------------------

    /**
     * Asserts one field is a well formed zoned-decimal image: digits, then a digit or an overpunched sign.
     *
     * @param field the field image
     * @param width the declared field width
     * @param description what the field is, for the diagnostic
     */
    private static void assertZonedField(final String field, final int width, final String description) {
        assertThat(field)
                .as("%s is %d bytes wide", description, width)
                .hasSize(width);
        assertThat(field.substring(0, width - 1))
                .as("%s carries digits ahead of its final byte: <%s>", description, field)
                .containsOnlyDigits();
        final char last = field.charAt(width - 1);
        assertThat(Character.isDigit(last) || SIGN_BYTES.indexOf(last) >= 0)
                .as("%s ends in a digit or one of the overpunched signs %s, but ends in '%s': <%s>",
                        description, SIGN_BYTES, last, field)
                .isTrue();
    }

    /**
     * @return the offset of the type code within a report line
     */
    private static int typeCodeOffset() {
        return ACCOUNT_ID_WIDTH + SEPARATOR_WIDTH;
    }

    /**
     * @return the offset of the category code within a report line
     */
    private static int categoryCodeOffset() {
        return typeCodeOffset() + TYPE_CODE_WIDTH + SEPARATOR_WIDTH;
    }

    /**
     * @return the offset of the edited balance within a report line
     */
    private static int balanceMaskOffset() {
        return categoryCodeOffset() + CATEGORY_CODE_WIDTH + SEPARATOR_WIDTH;
    }

    /**
     * @return the width of the key run a report line opens with: the three parts and their two separators
     */
    private static int keyPrefixWidth() {
        return categoryCodeOffset() + CATEGORY_CODE_WIDTH;
    }

    /**
     * The account identifier one report line carries.
     *
     * @param line the report line
     * @return the eleven-byte account field
     */
    private static String accountIdentifierOf(final String line) {
        return line.substring(0, ACCOUNT_ID_WIDTH);
    }

    /**
     * The edited balance one report line carries.
     *
     * @param line the report line
     * @return the twelve-character mask
     */
    private static String editedBalanceOf(final String line) {
        return line.substring(balanceMaskOffset(), balanceMaskOffset() + BALANCE_MASK_WIDTH);
    }

    /**
     * The key run one report line opens with.
     *
     * @param line the report line
     * @return the nineteen-character key run
     */
    private static String keyPrefixOf(final String line) {
        return line.substring(0, keyPrefixWidth());
    }

    /**
     * The key run one composite key projects to, built independently of any report line.
     *
     * @param accountId the account identifier
     * @param typeCode the type code
     * @param categoryCode the category code
     * @return the nineteen-character key run
     */
    private static String keyPrefix(final String accountId, final String typeCode,
            final String categoryCode) {

        return leftPadZeros(accountId, ACCOUNT_ID_WIDTH) + BLANK
                + rightPadBlanks(typeCode, TYPE_CODE_WIDTH) + BLANK
                + leftPadZeros(categoryCode, CATEGORY_CODE_WIDTH);
    }

    /**
     * The one report line belonging to one key on the discriminating account.
     *
     * @param runs the captured artefacts of the two runs, so the lines searched are provably those runs'
     * @param typeCode the type code
     * @param categoryCode the category code
     * @return the forty-character report line
     */
    private static String reportLineFor(final CapturedRuns runs, final String typeCode,
            final String categoryCode) {
        final String wanted = keyPrefix(DISCRIMINATING_ACCOUNT, typeCode, categoryCode);
        final List<String> matches = runs.reportLines().stream()
                .filter(line -> keyPrefixOf(line).equals(wanted))
                .toList();
        assertThat(matches)
                .as("exactly one report line carries the key run <%s>; the key is a primary key, so a "
                        + "second line under it would mean the reprojection emitted a row twice", wanted)
                .hasSize(1);
        return matches.get(0);
    }

    /**
     * Reads one delivered fixture from the test classpath as its record images.
     *
     * @param classpathLocation the fixture's classpath location
     * @return the record images, in file order
     */
    private static List<String> readFixtureRecords(final String classpathLocation) {
        final ClassPathResource fixture = new ClassPathResource(classpathLocation);
        try (InputStream content = fixture.getInputStream()) {
            return new String(content.readAllBytes(), StandardCharsets.US_ASCII)
                    .lines()
                    .toList();
        } catch (IOException unreadable) {
            throw new AssertionError("the delivered fixture <" + classpathLocation + "> could not be read"
                    + " from the test classpath, where it is expected beneath"
                    + " src/test/resources; the legacy tree is never read at run time, so a missing"
                    + " fixture cannot be substituted for from it", unreadable);
        }
    }
}
