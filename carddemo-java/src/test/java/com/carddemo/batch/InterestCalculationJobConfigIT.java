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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.batch.step.AdvisoryGenerationPublicationLock;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.InterestCalculationProcessor;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.id.DisclosureGroupId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.InterestCalculationService;
import com.carddemo.service.InterestGroupTransactionBoundary;
import com.carddemo.service.PostingStageTransactionBoundary;
import com.carddemo.service.TransactionPostingService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.sns.core.SnsOperations;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The interest accrual job configuration, driven the way the operational pipeline drives it: the
 * posting job first, then the accrual, against a real PostgreSQL 16 server carrying the real
 * migrations and the real reference seed.
 *
 * <h2>Why the posting job runs first, and why that is not optional</h2>
 *
 * <p>Every one of the fifty seeded transaction-category-balance rows carries the same encoded balance
 * image, and that image decodes to zero - measured across the whole fixture, there is exactly
 * <strong>one</strong> distinct balance value in it. An accrual run over unmodified seed data therefore
 * computes a balance of zero multiplied by a rate and divided by twelve hundred, which is zero for
 * every account. Truncation - the single most consequential arithmetic contract in this migration -
 * would be completely unobservable, and a specification written that way would pass just as happily
 * against a rounding implementation. So this specification launches the <strong>posting</strong> job as
 * a precondition, because posting is what puts non-zero balances into the driving input. The pipeline
 * order the operational convention follows is posting, then accrual, then consolidation, then
 * statements; this file covers the first two links and nothing beyond them.
 *
 * <p>It is deliberately <em>not</em> a second end-to-end specification. The chained four-job run and
 * the byte-equality comparison against the delivered golden files belong to the end-to-end tier, and
 * nothing here reads or asserts against those shared files. Here, posting is setup; every assertion is
 * about the accrual's own behaviour.
 *
 * <h2>Both arms of the rate gate are reached in one run, from seeded data</h2>
 *
 * <p>This chain is measured rather than assumed. The delivered daily-transaction fixture carries three
 * hundred records: two hundred and fifty of transaction type {@code 01} and fifty of type {@code 03},
 * all under category {@code 0001}. Posting <em>creates</em> a category-balance row for a key it does not
 * find, so after posting the driving input holds keys of both type families. The seeded default
 * disclosure group discloses type {@code 01} category {@code 0001} at a non-zero rate and type
 * {@code 03} category {@code 0001} at a rate of zero. The non-zero arm and the zero-rate skip are
 * therefore both exercised by the same run, through the <strong>default</strong> group - not through the
 * all-zero third group - and no synthetic disclosure row is needed for either.
 *
 * <p><strong>That covers the gate and not the resolver, so it is not the whole of the zero-rate
 * branch.</strong> On the fallback path the account's own group identifier resolves nothing: the rate
 * comes from a literal the program supplies after the first probe misses. Reaching a zero rate through a
 * <em>direct</em> hit - the account's own group identifier resolving a row that discloses zero - is a
 * different path through the resolver, and it is unreachable from the delivered seed for the same reason
 * every direct hit is: every seeded account's group identifier is ten spaces. The seed's own migration
 * says so and names what closes it - an account constructed with the padded zero-rate group plus a
 * matching category balance - and that is what
 * {@link #aDirectHitOnTheZeroRateGroupIsSkippedWithoutTouchingTheAccount()} installs. The two
 * specifications are deliberately separate: one proves the gate closes on a fallback rate of zero, the
 * other proves it closes on a directly resolved rate of zero without the fallback being entered at all.
 *
 * <h2>Why the fallback is the only lookup path seeded data can reach</h2>
 *
 * <p>Measured byte by byte: all fifty seeded account rows carry a value that looks like a group
 * identifier in their <em>postcode</em> field, while the account-group-identifier field is exactly ten
 * spaces on every row. The record is its full three hundred bytes with its whole trailing filler
 * present, so the layout is not shifted - this is genuine content. The consequence is that the direct
 * disclosure probe misses on every seeded account and the padded default literal is what resolves every
 * rate. A direct-hit specification is impossible from seeded data alone, which is why one account is
 * <strong>constructed</strong> for it through the shared test data factory. The account's group
 * identifier carries no referential constraint to the disclosure group either: it is a partial,
 * non-unique reference resolved at run time with a fallback, and none is assumed here.
 *
 * <p>The fallback performs one further read and no more. A second miss abends. Nothing here configures
 * or asserts a repeated attempt, a retry policy or a delay between attempts, because the source has
 * none: there is no loop around the second read, no third attempt and no interval to wait.
 *
 * <h2>Divergences recorded rather than reconciled</h2>
 *
 * <p><strong>The end-of-file control break is unreachable, and stays unreachable.</strong> The source
 * expresses it as the {@code ELSE} arm of a test-before loop: the read paragraph raises the end-of-file
 * flag and the loop's own condition then ends the loop before the arm can run. The last account in key
 * order therefore has its synthesized interest transactions written while its current balance still
 * excludes them and both of its cycle accumulators are left as the posting run left them. The delivered
 * translation reproduces that rather than reconciling it, the module's own expected-output fixtures
 * encode the unposted balance, and this specification asserts both halves for the last account in
 * explicit ascending key order. See {@code docs/decision-log.md} entry DL-207.
 *
 * <p><strong>The fee paragraph.</strong> It is empty in the source and genuinely invoked from inside the
 * rate gate. It survives the translation as an invoked method that produces nothing, and this
 * specification asserts exactly that: a group's accrued total equals the sum of its per-row interest and
 * not a penny more, so no fee of any kind exists. No fee logic may be invented for it.
 *
 * <h2>Expected values are computed independently of the code under test</h2>
 *
 * <p>Every expected amount here is derived with explicit decimal arithmetic written out in this file -
 * multiply, then divide, truncating - and every expected fixed-width field is sliced from the artefact
 * with plain character arithmetic. Nothing delegates to the decimal codec, to a record mapper or to any
 * production renderer to build an expectation: were the test to encode an amount with the same codec the
 * job decodes it with, a codec defect would produce a matching input and a matching expectation and the
 * comparison would pass while the system was wrong.
 *
 * <p>Provenance: the interest job member, the interest program, the balance, disclosure, transaction,
 * account and cross-reference record layouts, the delivered sample data and the generation-group
 * definition, at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is a provenance string for the
 * traceability-matrix header only - it is not carried by every legacy member, so nothing here asserts it
 * against one. No legacy source line is transcribed: the estate is cited by step name, definition name,
 * program name, record width, offset, count, status code and contract literal only.
 */
@SpringBootTest(classes = InterestCalculationJobConfigIT.PipelineContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("InterestCalculationJobConfigIT - posting first, then one accrual pass that truncates")
class InterestCalculationJobConfigIT extends AbstractPostgresIT {

    // ===============================================================================================
    // Measured contract values. Every literal below either appears byte for byte in the job's own
    // output or is a definition name, a width, an offset or a count taken from the estate.
    // ===============================================================================================

    /**
     * The ten-character run date the job member supplies to its one application step: eight digits of
     * calendar date followed by two further digits filling the remaining positions of the linkage date
     * field. No separators, and deliberately not a hyphenated ISO date - the value is reused verbatim as
     * the prefix of every synthesized transaction identifier, so reformatting it would silently change
     * all sixteen characters of every identifier the run mints.
     */
    private static final String RUN_DATE = "2022071803";

    /** A second valid run date, so the suffix specification observes two distinct job instances. */
    private static final String SECOND_RUN_DATE = "2022071804";

    /** A third valid run date, reserved for the accrual pass that follows the posting precondition. */
    private static final String ACCRUAL_RUN_DATE = "2022071805";

    /**
     * A fourth valid run date, reserved for the instrumentation pass.
     *
     * <p>Each launch in this file carries its own run date because the date is an identifying parameter:
     * relaunching a completed instance with the same one is refused by the job repository, as resubmitting
     * a completed job member with the same card would have been.
     */
    private static final String INSTRUMENTED_RUN_DATE = "2022071806";

    /** The hyphenated form the parameter contract refuses; ten characters, but not ten digits. */
    private static final String HYPHENATED_RUN_DATE = "2022-07-18";

    /** The transaction type the seeded default group discloses at a non-zero rate. */
    private static final String EARNING_TYPE = "01";

    /** The transaction type the seeded default group discloses at a rate of zero. */
    private static final String ZERO_RATE_TYPE = "03";

    /** The category both of those types are disclosed under. */
    private static final String DISCLOSED_CATEGORY = "0001";

    /** The rate the seeded default group discloses for the earning type and that category. */
    private static final BigDecimal EARNING_RATE = new BigDecimal("15.00");

    /** The rate the seeded default group discloses for the zero-rate type and that category. */
    private static final BigDecimal ZERO_RATE = new BigDecimal("0.00");

    /**
     * Zero at the two decimal places every monetary receiving field of this program carries.
     *
     * <p>Written as a literal rather than derived by rescaling, because the scale of an expected value is
     * part of what is being asserted and a rescaling call in a specification about truncation is exactly
     * the kind of arithmetic that belongs in one place only - the module's decimal codec, which this file
     * deliberately never consults.
     */
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    /** The divisor of the accrual expression, applied after the product and never before it. */
    private static final BigDecimal MONTHLY_DIVISOR = new BigDecimal("1200");

    /** The two decimal places every monetary receiving field of this program carries. */
    private static final int MONETARY_SCALE = 2;

    /**
     * The balance installed on one designated row so the arithmetic assertion is load-bearing.
     *
     * <p>At the seeded earning rate the product is 15010.8000 and the exact quotient is 12.509, whose
     * third decimal is what makes the three possible results visibly different:
     *
     * <ul>
     *   <li>multiplying first and dividing second, then truncating, yields {@code 12.50} - the contract;
     *   <li>truncating with any rounding mode instead would yield {@code 12.51} - one cent more, on
     *       roughly half of all accruals, and invisible to a fixture whose quotient terminates at two
     *       decimals;
     *   <li>dividing the rate by twelve hundred <em>first</em> and only then multiplying moves the
     *       truncation point: the rate becomes {@code 0.01} rather than {@code 0.0125} and the result
     *       collapses to {@code 10.00}.
     * </ul>
     *
     * <p>The naturally posted balances are deterministic but arbitrary, so a pair chosen for this
     * property is installed on one row of one account while every other row of the run is still checked
     * against its own independently computed expectation.
     */
    private static final BigDecimal LOAD_BEARING_BALANCE = new BigDecimal("1000.72");

    /** What multiplying first, dividing second and truncating must yield from that pair. */
    private static final BigDecimal TRUNCATED_INTEREST = new BigDecimal("12.50");

    /** What any rounding mode would have yielded instead, asserted against so the mode cannot drift. */
    private static final BigDecimal ROUNDED_ALTERNATIVE = new BigDecimal("12.51");

    /** What dividing before multiplying would have yielded, asserted against so the order cannot drift. */
    private static final BigDecimal DIVIDE_FIRST_ALTERNATIVE = new BigDecimal("10.00");

    /** The account whose earning row carries the load-bearing pair. */
    private static final String DESIGNATED_ACCOUNT = "00000000001";

    /**
     * The batch timestamp both stamps of every synthesized record must carry, under the pinned clock.
     *
     * <p>Its shape is the batch form and categorically not the online one: four-digit year, hyphen,
     * two-digit month, hyphen, two-digit day, <strong>hyphen</strong> at position eleven, two-digit hour,
     * dot, two-digit minute, dot, two-digit second, dot, two-digit hundredths at positions twenty-one and
     * twenty-two, and the literal {@code 0000} at positions twenty-three to twenty-six. The online form
     * carries a space where this carries its third hyphen, colons instead of the first two dots and a
     * six-digit fraction instead of two digits followed by four zeros.
     *
     * <p>The value is spelled out rather than derived from the production formatter, which is the
     * independent-oracle rule applied to a text field: a formatter defect must not be able to write the
     * expectation it is measured against. It is the pinned instant the shared base fixes its clock at.
     */
    private static final String EXPECTED_BATCH_TIMESTAMP = "2022-06-10-19.27.53.000000";

    /** Encoded width of the batch timestamp, and of both stamps of the record. */
    private static final int BATCH_TIMESTAMP_WIDTH = 26;

    /** Encoded width of one complete record of the output generation. */
    private static final int RECORD_WIDTH = 350;

    // Field offsets and widths of the 350-byte transaction record image, one-based positions expressed
    // as zero-based offsets. Sliced here rather than mapped, so no production mapper participates in
    // building or reading an expectation.
    private static final int TRAN_ID_OFFSET = 0;
    private static final int TRAN_ID_WIDTH = 16;
    private static final int TRAN_TYPE_OFFSET = 16;
    private static final int TRAN_TYPE_WIDTH = 2;
    private static final int TRAN_CATEGORY_OFFSET = 18;
    private static final int TRAN_CATEGORY_WIDTH = 4;
    private static final int TRAN_SOURCE_OFFSET = 22;
    private static final int TRAN_SOURCE_WIDTH = 10;
    private static final int TRAN_DESC_OFFSET = 32;
    private static final int TRAN_DESC_WIDTH = 100;
    private static final int TRAN_AMOUNT_OFFSET = 132;
    private static final int TRAN_AMOUNT_WIDTH = 11;
    private static final int MERCHANT_ID_OFFSET = 143;
    private static final int MERCHANT_ID_WIDTH = 9;
    private static final int MERCHANT_NAME_OFFSET = 152;
    private static final int MERCHANT_NAME_WIDTH = 50;
    private static final int MERCHANT_CITY_OFFSET = 202;
    private static final int MERCHANT_CITY_WIDTH = 50;
    private static final int MERCHANT_ZIP_OFFSET = 252;
    private static final int MERCHANT_ZIP_WIDTH = 10;
    private static final int CARD_NUM_OFFSET = 262;
    private static final int CARD_NUM_WIDTH = 16;
    private static final int ORIG_TS_OFFSET = 278;
    private static final int PROC_TS_OFFSET = 304;

    /** Encoded width of the eleven-digit account identifier the description carries. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Encoded width of the account-group identifier field, whose padding is part of every key. */
    private static final int ACCOUNT_GROUP_ID_WIDTH = 10;

    /** The bare word, without the padding the ten-character key field gives it. */
    private static final String UNPADDED_DEFAULT_GROUP = "DEFAULT";

    /**
     * The bare zero-rate group word, without the padding the ten-character key field gives it.
     *
     * <p>Held separately from the padded form so that the padded key can be asserted <em>against</em> it:
     * a trimmed key resolves nothing, which is exactly how a direct-hit specification could silently
     * degrade into a second copy of the fallback one.
     */
    private static final String UNPADDED_ZERO_RATE_GROUP = "ZEROAPR";

    /** The delivered daily-transaction fixture on the test classpath, the posting job's own input. */
    private static final String DAILY_TRANSACTION_FIXTURE = "dailytran.txt";

    /**
     * Parameter name the posting precondition is launched under.
     *
     * <p>The posting job member passes no parameter string and its configuration therefore attaches no
     * validator, so this name belongs to this specification rather than to the estate - it exists only to
     * give the precondition run an identity of its own, as a distinct card submission would have had. It is
     * deliberately not one of the parameter-contract owner's key constants, because borrowing one would
     * imply the posting job reads it.
     */
    private static final String PRECONDITION_PARAMETER = "preconditionRunDate";

    /** Encoded width of one daily-transaction record in the fixture the posting precondition reads. */
    private static final int DAILY_TRANSACTION_WIDTH = 350;

    /**
     * A scale wide enough to show what a two-decimal store discarded.
     *
     * <p>Six places is comfortably more than the accrual expression can produce from a two-decimal balance
     * and a two-decimal rate, so a quotient carried to it either terminates - in which case truncation and
     * rounding agree and the fixture proves nothing - or it does not, which is the property the designated
     * row was chosen for.
     */
    private static final int REMAINDER_REVEALING_SCALE = 6;

    /** An account identifier outside every seeded range, reserved for the constructed fixtures. */
    private static final String CONSTRUCTED_ACCOUNT = "90000000001";

    /** The card the constructed fixture's cross-reference names. */
    private static final String CONSTRUCTED_CARD_NUMBER = "9000000000000001";

    /** An opening balance for a constructed account, so a control break has something to add to. */
    private static final BigDecimal CONSTRUCTED_OPENING_BALANCE = new BigDecimal("500.00");

    /** A cycle accumulator value a control break must reset to zero. */
    private static final BigDecimal CONSTRUCTED_CYCLE_AMOUNT = new BigDecimal("321.45");

    /** A balance large enough that a leaked computation on a zero-rate row would be obvious. */
    private static final BigDecimal SKIPPED_BALANCE = new BigDecimal("5000.00");

    /** Rows the seeded reference data carries for each of the three disclosure groups. */
    private static final int DISCLOSURE_ROWS_PER_GROUP = 17;

    /** How many disclosure rows the seeded reference data carries in total. */
    private static final int SEEDED_DISCLOSURE_ROWS = 51;

    /** How many accounts, cards, cross-references and category balances the seed carries. */
    private static final int SEEDED_FIFTY = 50;

    /** The first suffix a run mints, which is what a fresh per-execution counter produces. */
    private static final long FIRST_SUFFIX = 1L;

    /**
     * The per-step timer the shared batch step template records every pass on.
     *
     * <p>Named here rather than imported because the template holds it privately, which is correct: a
     * meter name is an operational contract published through the metrics endpoint, not an API a caller
     * links against. Every specification in the module names it the same way.
     */
    private static final String BATCH_STEP_TIMER = "carddemo.batch.cobol.step";

    /** The tag that carries the translated program's name on that timer. */
    private static final String BATCH_STEP_TAG = "step";

    /** The tag that carries the outcome the pass reached on that timer. */
    private static final String BATCH_OUTCOME_TAG = "outcome";

    /** Ascending record-key order over the driving input, since no repository imposes an order. */
    private static final Sort CATEGORY_BALANCE_KEY_ORDER =
            Sort.by(Sort.Direction.ASC, "trancatAcctId", "trancatTypeCd", "trancatCd");

    /** Ascending key order over the account master, for identifying the last group of a pass. */
    private static final Sort ACCOUNT_KEY_ORDER = Sort.by(Sort.Direction.ASC, "acctId");

    // ===============================================================================================
    // Injected collaborators.
    // ===============================================================================================

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private JobRegistry jobRegistry;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobParameterValidators jobParameterValidators;

    @Autowired
    private InterestCalculationJobConfig interestConfig;

    @Autowired
    private PostTransactionJobConfig postingConfig;

    @Autowired
    private InterestCalculationService interestCalculationService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    @Autowired
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * Returns the shared server to the state a fresh migration leaves it in, and stages the posting
     * job's sequential input beside it.
     *
     * <p>The reset is the base class's own opt-in restore rather than a context discard: a batch job
     * commits per closed group on its own connections, so a transactional rollback cannot reach what a
     * run committed, and this specification asserts against the seeded rows wholesale rather than within
     * a reserved key range. Running it before <em>and</em> after each method means neither a neighbour's
     * leftovers nor this file's own are ever read as fixture.
     *
     * @throws Exception if the server cannot be restored or the input cannot be staged
     */
    @BeforeEach
    void restoreSeedAndStageThePostingInput() throws Exception {
        restoreSeededState();
        stageDailyTransactionInput();
    }

    /**
     * Leaves the shared server carrying exactly the delivered seed, whatever this method did to it.
     *
     * @throws Exception if the server cannot be restored
     */
    @AfterEach
    void restoreSeedForTheNextSpecification() throws Exception {
        restoreSeededState();
    }

    // ===============================================================================================
    // The independent oracle. Nothing below consults a production codec, mapper or renderer.
    // ===============================================================================================

    /**
     * The accrual expression, written out here so the expectation cannot inherit a defect from the code
     * it measures: the product of the balance and the rate <strong>first</strong>, that product divided by
     * twelve hundred <strong>second</strong>, and the quotient truncated to the receiving field's two
     * decimals.
     *
     * <p>The truncation is expressed as the division's own scale and rounding, which is what the receiving
     * field does to the intermediate: the estate contains no rounding request of any kind - a census of
     * the keyword across every program and every copybook returned nothing - so the discarded digits are
     * discarded rather than carried into the last cent.
     *
     * @param categoryBalance the row's balance, a nine-integer-digit two-decimal value
     * @param disclosedRate the group's rate, a four-integer-digit two-decimal value
     * @return the monthly interest the receiving field stores
     */
    private static BigDecimal expectedMonthlyInterest(final BigDecimal categoryBalance,
            final BigDecimal disclosedRate) {
        return categoryBalance.multiply(disclosedRate)
                .divide(MONTHLY_DIVISOR, MONETARY_SCALE, RoundingMode.DOWN);
    }

    /**
     * The same expression carried to a wide scale, so a specification can show that digits really were
     * discarded rather than that the quotient happened to terminate.
     *
     * @param categoryBalance the row's balance
     * @param disclosedRate the group's rate
     * @param scale the wide scale to carry the quotient to
     * @return the quotient at that scale
     */
    private static BigDecimal wideQuotient(final BigDecimal categoryBalance,
            final BigDecimal disclosedRate, final int scale) {
        return categoryBalance.multiply(disclosedRate)
                .divide(MONTHLY_DIVISOR, scale, RoundingMode.DOWN);
    }

    /**
     * Slices one field out of a record image as text, measured in encoded bytes and never trimmed.
     *
     * <p>The slice is taken from the byte array rather than from a decoded string, and the width is a byte
     * count rather than a character count: one character outside the seven-bit range satisfies a character
     * count and breaches a width, so a character count is not a width.
     *
     * @param artefact the complete generation
     * @param recordIndex which record of it, counting from zero
     * @param fieldOffset the field's zero-based offset within the record
     * @param fieldWidth the field's width in encoded bytes
     * @return the field exactly as the artefact carries it, padding included
     */
    private static String field(final byte[] artefact, final int recordIndex, final int fieldOffset,
            final int fieldWidth) {
        return new String(artefact, (recordIndex * RECORD_WIDTH) + fieldOffset, fieldWidth,
                StandardCharsets.US_ASCII);
    }

    /**
     * Builds the sixteen-character identifier the run must mint, from the verbatim parameter date and a
     * six-digit suffix, with plain text formatting and no production helper.
     *
     * @param parameterDate the ten-character run date, used exactly as supplied
     * @param suffix the six-digit incrementing suffix, counting from one
     * @return the expected identifier
     */
    private static String expectedTranId(final String parameterDate, final long suffix) {
        return parameterDate + String.format(Locale.ROOT, "%06d", Long.valueOf(suffix));
    }

    /**
     * Builds the hundred-character description the run must write: the contract literal, whose trailing
     * space is part of the text, followed by the eleven-digit account identifier, the remainder blank.
     *
     * @param accountId the eleven-digit account identifier
     * @return the expected description field, at its full width
     */
    private static String expectedDescription(final String accountId) {
        final String text = InterestCalculationProcessor.INTEREST_DESCRIPTION_PREFIX + accountId;
        return text + " ".repeat(TRAN_DESC_WIDTH - text.length());
    }

    // ===============================================================================================
    // Fixture staging and launching.
    // ===============================================================================================

    /**
     * Copies the delivered daily-transaction fixture from the test classpath to wherever the posting job
     * resolves its own sequential input.
     *
     * <p>The destination is read back from the posting configuration rather than written here, so no
     * filesystem path appears in this file and relocating the staging area cannot break this
     * specification. The fixture is read from the classpath and never from the legacy tree.
     *
     * @throws IOException if the fixture cannot be read or the input cannot be staged
     */
    private void stageDailyTransactionInput() throws IOException {
        final byte[] fixture = readFixture(DAILY_TRANSACTION_FIXTURE);
        final Path destination = this.postingConfig.dalytranInput();
        final Path directory = Objects.requireNonNull(destination.getParent(),
                "the posting job's input must resolve inside a directory");
        Files.createDirectories(directory);
        Files.write(destination, fixture);
    }

    /**
     * Reads one delivered fixture off the test classpath, failing with a diagnostic that names what was
     * missing and where it was expected rather than skipping.
     *
     * @param fileName the fixture's file name
     * @return its bytes
     * @throws IOException if the fixture cannot be read
     */
    private static byte[] readFixture(final String fileName) throws IOException {
        final String location = TestDataFactory.FIXTURE_DIRECTORY + fileName;
        try (InputStream contents = InterestCalculationJobConfigIT.class
                .getResourceAsStream(location)) {
            if (contents == null) {
                throw new IllegalStateException("required fixture " + fileName
                        + " is absent from the test classpath at " + location
                        + "; it is the posting job's sequential input and this specification cannot"
                        + " establish its precondition without it. Restore it under"
                        + " src/test/resources" + TestDataFactory.FIXTURE_DIRECTORY);
            }
            return contents.readAllBytes();
        }
    }

    /**
     * Resolves a job through the registry by the name its configuration publishes, so no job name is
     * spelled as a literal anywhere in this file.
     *
     * @param registeredName the configuration's own name constant
     * @return the registered job
     * @throws Exception if no job is registered under that name
     */
    private Job jobNamed(final String registeredName) throws Exception {
        return this.jobRegistry.getJob(registeredName);
    }

    /**
     * Builds the accrual job's one launch parameter under the key its parameter-contract owner declares.
     *
     * @param runDate the ten-character run date
     * @return parameters carrying exactly that one value
     */
    private static JobParameters interestParameters(final String runDate) {
        return new JobParametersBuilder()
                .addString(InterestCalculationJobConfig.PARM_DATE_KEY, runDate)
                .toJobParameters();
    }

    /**
     * Runs the posting job, which is this specification's precondition and never its subject.
     *
     * @return the completed execution
     * @throws Exception if the job cannot be launched
     */
    private JobExecution runPostingPrecondition() throws Exception {
        final JobExecution posting = this.jobLauncher.run(
                jobNamed(PostTransactionJobConfig.JOB_NAME),
                new JobParametersBuilder()
                        .addString(PRECONDITION_PARAMETER, RUN_DATE)
                        .toJobParameters());
        assertThat(posting.getStatus().isUnsuccessful())
                .as("the accrual's precondition is that posting completed; the reasons the posting"
                        + " execution recorded are reported here rather than left in the log: %s",
                        posting.getAllFailureExceptions())
                .isFalse();
        return posting;
    }

    /**
     * Runs the accrual job and reports the execution, failing with the execution's own recorded reasons
     * when it did not complete.
     *
     * @param runDate the ten-character run date to launch with
     * @return the completed execution
     * @throws Exception if the job cannot be launched
     */
    private JobExecution runAccrual(final String runDate) throws Exception {
        final JobExecution accrual = this.jobLauncher.run(
                jobNamed(InterestCalculationJobConfig.JOB_NAME), interestParameters(runDate));
        assertThat(accrual.getStatus().isUnsuccessful())
                .as("a run whose step succeeded can still be failed by its terminal durable-artefact"
                        + " publication, so the reasons the execution recorded are reported here rather"
                        + " than left in the log: %s", accrual.getAllFailureExceptions())
                .isFalse();
        return accrual;
    }

    /**
     * Reads the one step execution of a pass, refusing a shape with any other number of them.
     *
     * @param execution the job execution
     * @return its single step execution
     */
    private static StepExecution theOnlyStep(final JobExecution execution) {
        assertThat(execution.getStepExecutions())
                .as("the job member declares exactly one application step and no condition-code gate,"
                        + " so a pass has one step execution and no second, decider or parallel flow")
                .hasSize(1);
        final StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getStepName()).isEqualTo(InterestCalculationJobConfig.STEP_NAME);
        return step;
    }

    /**
     * Reads the complete output generation one execution owns, resolved by logical name through the
     * configuration rather than by any path written here.
     *
     * @param execution the execution whose generation to read
     * @return the artefact's bytes
     * @throws IOException if the generation cannot be read
     */
    private byte[] generationOf(final JobExecution execution) throws IOException {
        final Path generation =
                this.interestConfig.transactGeneration(execution.getId().longValue());
        assertThat(generation)
                .as("a completing pass leaves the generation its execution owns; it was expected at %s",
                        generation)
                .isRegularFile();
        return Files.readAllBytes(generation);
    }

    /** The three amounts an account carries into and out of a control break. */
    private record AccountAmounts(BigDecimal balance, BigDecimal cycleCredit, BigDecimal cycleDebit) {
    }

    /**
     * Snapshots every account's three mutable amounts, so a control break's effect can be measured
     * against what it started from rather than against a restated constant.
     *
     * @return the amounts by account identifier, in ascending key order
     */
    private Map<String, AccountAmounts> snapshotAccountAmounts() {
        final Map<String, AccountAmounts> amounts = new LinkedHashMap<>();
        for (final Account account : this.accountRepository.findAll(ACCOUNT_KEY_ORDER)) {
            amounts.put(account.getAcctId(), new AccountAmounts(account.getAcctCurrBal(),
                    account.getAcctCurrCycCredit(), account.getAcctCurrCycDebit()));
        }
        return amounts;
    }

    // ===============================================================================================
    // The job's shape, and what nothing does when a context refreshes.
    // ===============================================================================================

    @Test
    @Order(1)
    @DisplayName("the job is one step with no failure-ending transition, its five modelled definitions "
            + "exclude the allocated-but-unreferenced alternate-index path, and nothing launches it when "
            + "the context refreshes")
    void theJobIsOneStepAndNothingLaunchesItAtContextStart() throws Exception {
        assertThat(this.jobRegistry.getJobNames())
                .as("a launcher addresses the job by the name its configuration publishes")
                .contains(InterestCalculationJobConfig.JOB_NAME);
        assertThat(jobNamed(InterestCalculationJobConfig.JOB_NAME).getName())
                .isEqualTo(InterestCalculationJobConfig.JOB_NAME);

        assertThat(this.applicationContext.containsBean(InterestCalculationJobConfig.STEP_NAME))
                .as("the one step is published under its own name so a restart addresses the same step")
                .isTrue();

        assertThat(List.of(InterestCalculationJobConfig.DD_TCATBALF,
                InterestCalculationJobConfig.DD_XREFFILE,
                InterestCalculationJobConfig.DD_ACCTFILE,
                InterestCalculationJobConfig.DD_DISCGRP,
                InterestCalculationJobConfig.DD_TRANSACT))
                .as("the program's own file list is five resources: the driving input, the"
                        + " cross-reference, the account master in and out, the disclosure group and the"
                        + " output generation")
                .doesNotHaveDuplicates()
                .hasSize(5)
                .doesNotContain(InterestCalculationJobConfig.DD_XREFFIL1);
        assertThat(InterestCalculationJobConfig.DD_XREFFIL1)
                .as("the alternate-index path is allocated by the job member and referenced by the"
                        + " program nowhere, so it is named for discoverability and modelled by no reader")
                .isEqualTo(InterestCalculationProcessor.LEGACY_UNREFERENCED_DD);

        assertThat(InterestCalculationJobConfig.TRANSACT_RECORD_LENGTH)
                .as("the output is declared fixed-length unblocked at the transaction record's own width")
                .isEqualTo(RECORD_WIDTH);
        assertThat(InterestCalculationJobConfig.LEGACY_JOB_MEMBER)
                .isEqualTo(InterestCalculationProcessor.LEGACY_JOB);
        assertThat(InterestCalculationJobConfig.LEGACY_STEP_NAME)
                .as("the member declares exactly one application step, and this is its name")
                .isEqualTo(InterestCalculationProcessor.LEGACY_STEP);
        assertThat(InterestCalculationJobConfig.LEGACY_PROGRAM_NAME)
                .isEqualTo(InterestCalculationProcessor.LEGACY_PROGRAM);

        assertThat(this.environment.getProperty("spring.batch.job.enabled"))
                .as("launch on refresh is switched off by the shipped test document")
                .isEqualTo("false");
        assertThat(this.environment.getProperty("spring.batch.job.name"))
                .as("no job is nominated to run at start-up")
                .isNull();
        assertThat(this.applicationContext.getBeansOfType(JobLauncherApplicationRunner.class))
                .as("the only start-up launcher the framework offers is absent from this context")
                .isEmpty();
        assertThat(this.applicationContext.getBeansOfType(CommandLineRunner.class))
                .as("this configuration contributes no runner of its own")
                .isEmpty();
        assertThat(this.applicationContext.getBeansOfType(ApplicationRunner.class)).isEmpty();

        assertThat(this.jobRepository.getLastJobExecution(InterestCalculationJobConfig.JOB_NAME,
                interestParameters(RUN_DATE)))
                .as("no execution of this run date exists until this specification launches one")
                .isNull();
    }

    @Test
    @Order(2)
    @DisplayName("the launch boundary attaches the parameter contract's own validator, which accepts ten "
            + "digits with no separators and refuses a hyphenated date or none at all")
    void theLaunchBoundaryRefusesAnythingButTenDigits() throws Exception {
        final Job accrualJob = jobNamed(InterestCalculationJobConfig.JOB_NAME);

        assertThatThrownBy(() -> this.jobLauncher.run(accrualJob,
                interestParameters(HYPHENATED_RUN_DATE)))
                .as("ten characters is not ten digits; a hyphenated date is refused at the boundary"
                        + " rather than minting sixteen-character identifiers from it")
                .hasMessageContaining(InterestCalculationJobConfig.PARM_DATE_KEY);

        assertThatThrownBy(() -> this.jobLauncher.run(accrualJob, new JobParameters()))
                .as("the parameter is required, and its absence names the key it is required under")
                .hasMessageContaining(InterestCalculationJobConfig.PARM_DATE_KEY);

        assertThat(InterestCalculationJobConfig.PARM_DATE_KEY)
                .as("the key has one owner and this configuration re-exports it rather than respelling it")
                .isEqualTo(JobParameterValidators.INTEREST_PARM_DATE_KEY);

        this.jobParameterValidators.interestParmDateValidator()
                .validate(interestParameters(RUN_DATE));
        assertThatThrownBy(() -> this.jobParameterValidators.interestParmDateValidator()
                .validate(interestParameters(HYPHENATED_RUN_DATE)))
                .hasMessageContaining(InterestCalculationJobConfig.PARM_DATE_KEY);

        assertThat(RUN_DATE.getBytes(StandardCharsets.US_ASCII).length)
                .as("the run date fills the ten positions of the linkage date field exactly")
                .isEqualTo(InterestCalculationProcessor.PARM_DATE_WIDTH);
    }

    // ===============================================================================================
    // The pipeline: posting as precondition, then one accrual pass.
    // ===============================================================================================

    /** One row of the driving input as the accrual pass will read it, with the rate it resolves to. */
    private record DrivingRow(String accountId, String typeCode, String categoryCode,
            BigDecimal balance, BigDecimal disclosedRate) {

        /** @return the interest this row accrues, or zero when the rate gate skips it */
        BigDecimal accrual() {
            return this.disclosedRate.signum() == 0
                    ? ZERO_AMOUNT
                    : expectedMonthlyInterest(this.balance, this.disclosedRate);
        }
    }

    @Test
    @Order(3)
    @DisplayName("posting first makes both rate families reachable, then one accrual pass truncates the "
            + "non-zero rows, skips the zero-rate rows, posts every group including the last, and writes "
            + "an exact multiple of the record width")
    void postingThenOneAccrualPassTruncatesAndSkipsInTheSameRun() throws Exception {
        // THE PRECONDITION, STATED AS AN ASSERTION. Every seeded balance is the same value and that value
        // is zero, so an accrual over unmodified seed data would compute zero for every account and
        // truncation would be invisible. This is why posting runs first.
        final List<TransactionCategoryBalance> seededRows =
                this.categoryBalanceRepository.findAll(CATEGORY_BALANCE_KEY_ORDER);
        assertThat(seededRows).hasSize(SEEDED_FIFTY);
        assertThat(seededRows.stream().map(TransactionCategoryBalance::getTranCatBal)
                .map(TestDataFactory::storedAmount).distinct().toList())
                .as("exactly one distinct balance value across all fifty seeded rows, and it is zero -"
                        + " which is why this specification cannot start from the seed alone")
                .containsExactly(ZERO_AMOUNT);
        assertThat(this.accountRepository.findAll(ACCOUNT_KEY_ORDER))
                .as("every seeded account leaves its group identifier blank, so every rate this run"
                        + " resolves comes through the padded default literal")
                .allSatisfy(account -> assertThat(account.getAcctGroupId())
                        .isEqualTo(TestDataFactory.SEEDED_ACCOUNT_GROUP_ID));

        runPostingPrecondition();

        // WHAT POSTING BOUGHT. It updates the row it finds and CREATES the row it does not, so the driving
        // input now holds keys of both transaction-type families the fixture carries - and the seeded
        // default group discloses one of them at a non-zero rate and the other at zero.
        final List<TransactionCategoryBalance> postedRows =
                this.categoryBalanceRepository.findAll(CATEGORY_BALANCE_KEY_ORDER);
        assertThat(postedRows)
                .as("posting created rows for keys it did not find, so the input grew")
                .hasSizeGreaterThan(SEEDED_FIFTY);
        assertThat(postedRows).extracting(TransactionCategoryBalance::getTrancatTypeCd)
                .as("two hundred and fifty records of one type and fifty of another, so both families"
                        + " are present after posting")
                .contains(EARNING_TYPE, ZERO_RATE_TYPE);
        assertThat(postedRows.stream().map(TransactionCategoryBalance::getTranCatBal)
                .map(TestDataFactory::storedAmount).anyMatch(balance -> balance.signum() != 0))
                .as("posting is what puts a non-zero balance into the driving input")
                .isTrue();

        installTheLoadBearingPair();

        final List<DrivingRow> expectedRows = drivingRowsInKeyOrder();
        final Map<String, AccountAmounts> before = snapshotAccountAmounts();
        final long liveMasterRowsBefore = this.transactionRepository.count();

        final JobExecution accrual = runAccrual(ACCRUAL_RUN_DATE);
        final StepExecution step = theOnlyStep(accrual);

        assertThat(step.getExecutionContext()
                .getInt(InterestCalculationProcessor.CONTEXT_ROWS_READ))
                .as("the read loop delivered every row of the driving input")
                .isEqualTo(expectedRows.size());
        assertThat(step.getExecutionContext()
                .getString(InterestCalculationProcessor.CONTEXT_DEFAULT_GROUP_USED))
                .as("the direct probe misses on every seeded account, so the single default probe is"
                        + " what resolved the rate")
                .isEqualTo(Boolean.TRUE.toString());
        assertThat(step.getExecutionContext()
                .getString(InterestCalculationProcessor.CONTEXT_RATE_GATE_SKIPPED))
                .as("the zero-rate rows the default group discloses skipped the computation and the fee"
                        + " invocation together, in this same run")
                .isEqualTo(Boolean.TRUE.toString());

        final List<DrivingRow> earning = expectedRows.stream()
                .filter(row -> row.disclosedRate().signum() != 0).toList();
        final List<DrivingRow> skipped = expectedRows.stream()
                .filter(row -> row.disclosedRate().signum() == 0).toList();
        assertThat(earning)
                .as("the non-zero arm is reached from seeded reference data, without a synthetic"
                        + " disclosure row")
                .isNotEmpty();
        assertThat(skipped)
                .as("and so is the zero-rate arm, through the DEFAULT group rather than the all-zero"
                        + " third group")
                .isNotEmpty();
        assertThat(step.getExecutionContext()
                .getInt(InterestCalculationProcessor.CONTEXT_TRANSACTIONS))
                .as("a skipped row synthesizes nothing, so the run mints one record per non-zero row")
                .isEqualTo(earning.size());
        assertThat(step.getExecutionContext()
                .getLong(InterestCalculationProcessor.CONTEXT_LAST_TRAN_ID_SUFFIX))
                .as("the six-digit suffix counted one per minted record, from one")
                .isEqualTo(earning.size());

        assertGenerationMatches(accrual, earning, ACCRUAL_RUN_DATE);
        assertEveryGroupWasPosted(expectedRows, before);

        assertThat(this.transactionRepository.count())
                .as("this job writes its generation only; loading the live master is a later link of the"
                        + " pipeline and not this job's business")
                .isEqualTo(liveMasterRowsBefore);
    }

    /**
     * Replaces one designated row's balance with the pair whose two operand orderings visibly differ.
     *
     * <p>The naturally posted balances are deterministic but arbitrary, and an arithmetic assertion made
     * only against them would be incidental rather than load-bearing: a fixture whose exact quotient
     * terminates at two decimals cannot tell truncation from rounding. This row's quotient carries a
     * third decimal, so all three candidate results differ - see the constant's own contract. Every other
     * row of the run is still checked against its own independently computed expectation, so the posted
     * values are not exempted by this substitution.
     */
    private void installTheLoadBearingPair() {
        final TransactionCategoryBalance designated = this.categoryBalanceRepository
                .findAll(CATEGORY_BALANCE_KEY_ORDER).stream()
                .filter(row -> DESIGNATED_ACCOUNT.equals(row.getTrancatAcctId()))
                .filter(row -> EARNING_TYPE.equals(row.getTrancatTypeCd()))
                .filter(row -> DISCLOSED_CATEGORY.equals(row.getTrancatCd()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the reference seed must carry an earning"
                        + " category-balance row for account " + DESIGNATED_ACCOUNT + " under type "
                        + EARNING_TYPE + " and category " + DISCLOSED_CATEGORY
                        + "; it is the row the arithmetic assertion is made on"));
        this.categoryBalanceRepository.saveAndFlush(new TransactionCategoryBalance(
                designated.getTrancatAcctId(), designated.getTrancatTypeCd(),
                designated.getTrancatCd(), LOAD_BEARING_BALANCE));
    }

    /**
     * Reads the driving input as the pass will read it - ascending record-key order - and pairs every row
     * with the rate the seeded default group discloses for its type and category.
     *
     * <p>The order is passed explicitly because no repository imposes one, and the pass's read order is
     * what determines both the sequence of the generation and which group closes last.
     *
     * @return the rows in the order the pass will read them
     */
    private List<DrivingRow> drivingRowsInKeyOrder() {
        final Map<String, BigDecimal> ratesByType = new HashMap<>();
        ratesByType.put(EARNING_TYPE, EARNING_RATE);
        ratesByType.put(ZERO_RATE_TYPE, ZERO_RATE);

        final List<DrivingRow> rows = new ArrayList<>();
        for (final TransactionCategoryBalance row
                : this.categoryBalanceRepository.findAll(CATEGORY_BALANCE_KEY_ORDER)) {
            final BigDecimal rate = ratesByType.get(row.getTrancatTypeCd());
            assertThat(rate)
                    .as("the delivered fixture carries only the two transaction types this"
                            + " specification knows the disclosed rate of, but a row of type %s"
                            + " appeared under category %s", row.getTrancatTypeCd(), row.getTrancatCd())
                    .isNotNull();
            assertThat(row.getTrancatCd())
                    .as("the whole fixture is disclosed under one category")
                    .isEqualTo(DISCLOSED_CATEGORY);
            rows.add(new DrivingRow(row.getTrancatAcctId(), row.getTrancatTypeCd(),
                    row.getTrancatCd(), TestDataFactory.storedAmount(row.getTranCatBal()), rate));
        }
        return List.copyOf(rows);
    }

    /**
     * Checks the output generation field by field against expectations this file computed itself.
     *
     * <p>The artefact is read as bytes and every field is sliced by offset and width in encoded bytes,
     * never trimmed and never decoded through a production mapper. The amount is compared as an image
     * rather than as a number, so the overpunched sign that folds into the field's final byte is part of
     * what is compared.
     *
     * @param execution the completed accrual execution
     * @param earning the rows the rate gate let through, in read order
     * @param runDate the ten-character run date the identifiers must carry verbatim
     * @throws IOException if the generation cannot be read
     */
    private void assertGenerationMatches(final JobExecution execution, final List<DrivingRow> earning,
            final String runDate) throws IOException {

        final byte[] artefact = generationOf(execution);
        assertThat(artefact.length % RECORD_WIDTH)
                .as("the generation is declared fixed-length UNBLOCKED, so it is an exact multiple of"
                        + " the record width with no block padding and no record separator anywhere")
                .isZero();
        assertThat(artefact.length)
                .as("one record per row the rate gate let through, and none for a row it skipped")
                .isEqualTo(earning.size() * RECORD_WIDTH);

        final Map<String, String> cardsByAccount = cardNumbersByAccount();
        boolean loadBearingRowSeen = false;

        for (int index = 0; index < earning.size(); index++) {
            final DrivingRow row = earning.get(index);
            final long suffix = FIRST_SUFFIX + index;

            assertThat(field(artefact, index, TRAN_ID_OFFSET, TRAN_ID_WIDTH))
                    .as("sixteen characters: the ten-character parameter used VERBATIM followed by the"
                            + " six-digit suffix, which increments once per minted record from one")
                    .isEqualTo(expectedTranId(runDate, suffix))
                    .startsWith(runDate);
            assertThat(field(artefact, index, TRAN_TYPE_OFFSET, TRAN_TYPE_WIDTH))
                    .isEqualTo(InterestCalculationProcessor.INTEREST_TRAN_TYPE_CD);
            assertThat(field(artefact, index, TRAN_CATEGORY_OFFSET, TRAN_CATEGORY_WIDTH))
                    .as("a two-character literal moved into a four-digit numeric field de-edits to all"
                            + " four positions")
                    .isEqualTo(InterestCalculationProcessor.INTEREST_TRAN_CAT_CD);
            assertThat(field(artefact, index, TRAN_SOURCE_OFFSET, TRAN_SOURCE_WIDTH))
                    .as("the literal occupies six of the field's ten positions and the remaining four"
                            + " stay blank")
                    .isEqualTo(InterestCalculationProcessor.INTEREST_TRAN_SOURCE);
            assertThat(field(artefact, index, TRAN_DESC_OFFSET, TRAN_DESC_WIDTH))
                    .as("the trailing space inside the literal is part of the contract text, and the"
                            + " eleven-digit account identifier follows it immediately")
                    .isEqualTo(expectedDescription(row.accountId()));
            assertThat(field(artefact, index, MERCHANT_ID_OFFSET, MERCHANT_ID_WIDTH))
                    .as("a synthesized transaction has no merchant, so the identifier is zero-filled")
                    .isEqualTo(InterestCalculationProcessor.INTEREST_MERCHANT_ID);
            assertThat(field(artefact, index, MERCHANT_NAME_OFFSET, MERCHANT_NAME_WIDTH))
                    .isEqualTo(" ".repeat(MERCHANT_NAME_WIDTH));
            assertThat(field(artefact, index, MERCHANT_CITY_OFFSET, MERCHANT_CITY_WIDTH))
                    .isEqualTo(" ".repeat(MERCHANT_CITY_WIDTH));
            assertThat(field(artefact, index, MERCHANT_ZIP_OFFSET, MERCHANT_ZIP_WIDTH))
                    .isEqualTo(" ".repeat(MERCHANT_ZIP_WIDTH));
            assertThat(field(artefact, index, CARD_NUM_OFFSET, CARD_NUM_WIDTH))
                    .as("the card number is the CROSS-REFERENCED card of the group's account and never"
                            + " the account identifier")
                    .isEqualTo(cardsByAccount.get(row.accountId()))
                    .isNotEqualTo(row.accountId());

            final String originationStamp = field(artefact, index, ORIG_TS_OFFSET,
                    BATCH_TIMESTAMP_WIDTH);
            final String processingStamp = field(artefact, index, PROC_TS_OFFSET,
                    BATCH_TIMESTAMP_WIDTH);
            assertThat(originationStamp)
                    .as("one batch timestamp is taken and the same value is moved into both stamps, so"
                            + " the processing stamp is never regenerated")
                    .isEqualTo(processingStamp);
            assertThat(originationStamp)
                    .as("the batch form: a HYPHEN at position eleven where the online form carries a"
                            + " space, dots where it carries colons, and two digits of hundredths"
                            + " followed by four literal zeros where it carries a six-digit fraction")
                    .isEqualTo(EXPECTED_BATCH_TIMESTAMP);
            assertThat(originationStamp.getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(BATCH_TIMESTAMP_WIDTH);

            final String amountImage = field(artefact, index, TRAN_AMOUNT_OFFSET, TRAN_AMOUNT_WIDTH);
            assertThat(amountImage)
                    .as("the amount is the product of the balance and the rate divided by twelve"
                            + " hundred, truncated to the receiving field's two decimals, with the sign"
                            + " overpunched into the field's final byte")
                    .isEqualTo(TestDataFactory.encodeZonedDecimal(row.accrual(), TRAN_AMOUNT_WIDTH));

            if (LOAD_BEARING_BALANCE.compareTo(row.balance()) == 0
                    && EARNING_TYPE.equals(row.typeCode())) {
                assertLoadBearingArithmetic(TestDataFactory.decodeZonedDecimal(amountImage));
                loadBearingRowSeen = true;
            }
        }

        assertThat(loadBearingRowSeen)
                .as("the row whose quotient carries a third decimal must actually have been read: if it"
                        + " was not, the arithmetic assertion never ran and this specification proved"
                        + " nothing whatever about truncation while still reporting a pass")
                .isTrue();
    }

    /**
     * The arithmetic assertion this file exists for: multiply first, divide second, truncate.
     *
     * <p>Three candidate results are named so that none of the three ways of getting it wrong can pass.
     * Truncation is additionally shown to have discarded something, by carrying the same expression to a
     * wide scale and observing that the wide quotient exceeds the stored one - which proves the fixture is
     * capable of telling truncation from rounding rather than merely agreeing with it.
     *
     * @param accrued the amount the run stored for the designated row
     */
    private static void assertLoadBearingArithmetic(final BigDecimal accrued) {
        assertThat(accrued)
                .as("multiplying first, dividing second and discarding the remainder")
                .isEqualByComparingTo(TRUNCATED_INTEREST);
        assertThat(accrued)
                .as("a rounding mode would have carried the discarded third decimal into the last cent"
                        + " and produced %s - one cent more, on roughly half of all accruals",
                        ROUNDED_ALTERNATIVE)
                .isNotEqualByComparingTo(ROUNDED_ALTERNATIVE);
        assertThat(accrued)
                .as("dividing the rate by twelve hundred BEFORE multiplying moves the truncation point"
                        + " and would have produced %s", DIVIDE_FIRST_ALTERNATIVE)
                .isNotEqualByComparingTo(DIVIDE_FIRST_ALTERNATIVE);

        final BigDecimal wide =
                wideQuotient(LOAD_BEARING_BALANCE, EARNING_RATE, REMAINDER_REVEALING_SCALE);
        assertThat(wide)
                .as("the exact quotient carries a non-zero third decimal, which is what makes this"
                        + " fixture able to distinguish truncation from rounding at all")
                .isGreaterThan(TRUNCATED_INTEREST);
        assertThat(expectedMonthlyInterest(LOAD_BEARING_BALANCE, EARNING_RATE))
                .as("the oracle is computed in this file and agrees with the named constant, so neither"
                        + " can drift without the other noticing")
                .isEqualByComparingTo(TRUNCATED_INTEREST);
        assertThat(accrued.scale())
                .as("every monetary field carries the receiving field's own two decimals")
                .isEqualTo(MONETARY_SCALE);
    }

    /**
     * Reads the card each account's cross-reference names, which is what a synthesized record must carry.
     *
     * @return the card number by account identifier
     */
    private Map<String, String> cardNumbersByAccount() {
        final Map<String, String> cards = new HashMap<>();
        for (final CardCrossReference reference : this.cardCrossReferenceRepository.findAll()) {
            cards.put(reference.getXrefAcctId(), reference.getXrefCardNum());
        }
        return cards;
    }

    /**
     * Checks the account control break for every group a KEY CHANGE closed, and checks that the run's
     * FINAL group was not posted at all.
     *
     * <p>Three things are asserted for every key-change group and each is a distinct silent-defect
     * guard: the accrued total reaches the current balance; <strong>both</strong> cycle accumulators are
     * reset, since resetting one and leaving the other is invisible to a balance assertion; and the
     * amounts are read back from the store rather than from the run's own report.
     *
     * <p>The final group is asserted the other way round, and deliberately so.
     * {@code app/cbl/CBACT04C.cbl:L219} to {@code L221} put the second invocation of
     * {@code 1050-UPDATE-ACCOUNT} in the {@code ELSE} arm of an end-of-file test inside a test-before
     * {@code PERFORM UNTIL}, so the loop ends before the arm can run. The last account of a run keeps its
     * balance and keeps both accumulators, while its synthesized records are still written - and the
     * module's own expected-output fixtures encode exactly that. Asserting the posting here instead
     * would assert the divergence rather than the source. See {@code docs/decision-log.md} entry DL-207.
     *
     * @param rows the driving input in read order
     * @param before the account amounts as they stood before the pass
     */
    private void assertEveryGroupWasPosted(final List<DrivingRow> rows,
            final Map<String, AccountAmounts> before) {

        final Map<String, BigDecimal> accruedByAccount = new LinkedHashMap<>();
        for (final DrivingRow row : rows) {
            accruedByAccount.merge(row.accountId(), row.accrual(), BigDecimal::add);
        }
        assertThat(accruedByAccount)
                .as("the pass closed one group per distinct account of the driving input")
                .isNotEmpty();

        final String lastGroup = rows.get(rows.size() - 1).accountId();
        for (final Map.Entry<String, BigDecimal> group : accruedByAccount.entrySet()) {
            if (group.getKey().equals(lastGroup)) {
                continue;
            }
            final AccountAmounts opening = before.get(group.getKey());
            assertThat(opening)
                    .as("account %s was read from the account master before the pass", group.getKey())
                    .isNotNull();
            final Account posted = this.accountRepository.findById(group.getKey())
                    .orElseThrow(() -> new IllegalStateException("account " + group.getKey()
                            + " was accrued against and must still exist in the account master"));

            assertThat(posted.getAcctCurrBal())
                    .as("the control break adds the group's accrued total to the current balance of"
                            + " account %s", group.getKey())
                    .isEqualByComparingTo(opening.balance().add(group.getValue()));
            assertThat(posted.getAcctCurrCycCredit())
                    .as("a control break resets BOTH cycle accumulators; resetting only one is a silent"
                            + " defect a balance assertion cannot see")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(posted.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        assertThat(accruedByAccount).containsKey(lastGroup);
        final AccountAmounts lastOpening = before.get(lastGroup);
        assertThat(lastOpening)
                .as("the last account of the pass was read before it ran")
                .isNotNull();
        assertThat(accruedByAccount.get(lastGroup))
                .as("the final group really did accrue something, so its unposted balance is an"
                        + " observed withholding rather than an accrual of zero")
                .isGreaterThan(BigDecimal.ZERO);
        final Account lastPosted = this.accountRepository.findById(lastGroup)
                .orElseThrow(() -> new IllegalStateException(
                        "the last account of the pass must still exist in the account master"));
        assertThat(lastPosted.getAcctCurrBal())
                .as("the FINAL group's control break is the unreachable end-of-file arm, so account %s"
                        + " keeps the balance it had - see decision-log entry DL-207", lastGroup)
                .isEqualByComparingTo(lastOpening.balance());
        assertThat(lastPosted.getAcctCurrCycCredit())
                .as("and its cycle CREDIT accumulator is left exactly as the posting run left it")
                .isEqualByComparingTo(lastOpening.cycleCredit());
        assertThat(lastPosted.getAcctCurrCycDebit())
                .as("and its cycle DEBIT accumulator is left exactly as the posting run left it")
                .isEqualByComparingTo(lastOpening.cycleDebit());
    }

    // ===============================================================================================
    // The disclosure-group lookup: the fallback key, its padding, and its argument order.
    // ===============================================================================================

    @Test
    @Order(4)
    @DisplayName("seeded data reaches only the fallback lookup, whose key is the literal plus three "
            + "spaces, and whose three key components are positional")
    void theFallbackIsTheOnlyLookupSeededDataReachesAndItsKeyCarriesItsPadding() {
        assertThat(this.accountRepository.findAll(ACCOUNT_KEY_ORDER))
                .hasSize(SEEDED_FIFTY)
                .allSatisfy(account -> {
                    assertThat(account.getAcctGroupId())
                            .as("the group-identifier field is ten spaces on every seeded row - genuine"
                                    + " content, not a shifted layout: the value that LOOKS like a group"
                                    + " identifier sits in the postcode field instead")
                            .isEqualTo(TestDataFactory.SEEDED_ACCOUNT_GROUP_ID)
                            .isBlank();
                    assertThat(account.getAcctGroupId().getBytes(StandardCharsets.US_ASCII).length)
                            .isEqualTo(ACCOUNT_GROUP_ID_WIDTH);
                });

        assertThat(this.disclosureGroupRepository.findById(new DisclosureGroupId(
                TestDataFactory.SEEDED_ACCOUNT_GROUP_ID, EARNING_TYPE, DISCLOSED_CATEGORY)))
                .as("the direct probe MISSES for every seeded account, which is the not-found condition"
                        + " the fallback is entered on - and the only condition it is entered on")
                .isEmpty();

        final DisclosureGroup fallback = this.disclosureGroupRepository.findById(
                new DisclosureGroupId(TestDataFactory.FALLBACK_DISCLOSURE_GROUP_ID, EARNING_TYPE,
                        DISCLOSED_CATEGORY))
                .orElseThrow(() -> new IllegalStateException("the reference seed must carry the padded"
                        + " default disclosure group for type " + EARNING_TYPE + " category "
                        + DISCLOSED_CATEGORY + "; it is the only rate any seeded account can resolve"));
        assertThat(fallback.getDisIntRate())
                .as("the rate the non-zero arm of this run accrues at")
                .isEqualByComparingTo(EARNING_RATE);

        assertThat(TestDataFactory.FALLBACK_DISCLOSURE_GROUP_ID)
                .as("the seven-character literal moved into a ten-character key field leaves THREE"
                        + " trailing spaces, and the padding is part of the key")
                .isEqualTo(UNPADDED_DEFAULT_GROUP + "   ")
                .isEqualTo(InterestCalculationProcessor.DEFAULT_ACCOUNT_GROUP_ID);
        assertThat(InterestCalculationProcessor.DEFAULT_ACCOUNT_GROUP_ID
                .getBytes(StandardCharsets.US_ASCII).length)
                .isEqualTo(ACCOUNT_GROUP_ID_WIDTH);
        assertThat(this.disclosureGroupRepository.findById(new DisclosureGroupId(
                UNPADDED_DEFAULT_GROUP, EARNING_TYPE, DISCLOSED_CATEGORY)))
                .as("the bare word, trimmed of its padding, matches nothing at all")
                .isEmpty();

        // THE ARGUMENT ORDER IS POSITIONAL AND THE TWO SHORT COMPONENTS ARE INTERCHANGEABLE BY MISTAKE.
        // The key type takes its components in the order they occupy the record image - group, then
        // TYPE, then CATEGORY - while the program assigns its lookup fields in the order group, then
        // category, then type. Following the assignment sequence instead would transpose two short
        // character components, resolve the wrong row or none, and still compile.
        assertThat(this.disclosureGroupRepository.findById(new DisclosureGroupId(
                TestDataFactory.FALLBACK_DISCLOSURE_GROUP_ID, DISCLOSED_CATEGORY, EARNING_TYPE)))
                .as("transposing the type and the category resolves nothing, which is what makes"
                        + " following the assignment sequence a silent defect rather than a loud one")
                .isEmpty();

        final List<DisclosureGroup> disclosed = this.disclosureGroupRepository.findAll();
        assertThat(disclosed)
                .as("three groups of seventeen rows each")
                .hasSize(SEEDED_DISCLOSURE_ROWS);
        assertThat(disclosed).filteredOn(row -> TestDataFactory.FALLBACK_DISCLOSURE_GROUP_ID
                        .equals(row.getDisAcctGroupId()))
                .hasSize(DISCLOSURE_ROWS_PER_GROUP);
        assertThat(disclosed.stream().map(DisclosureGroup::getDisAcctGroupId).distinct().toList())
                .containsExactlyInAnyOrderElementsOf(TestDataFactory.SEEDED_DISCLOSURE_GROUP_IDS);

        assertThat(this.disclosureGroupRepository.findById(new DisclosureGroupId(
                TestDataFactory.FALLBACK_DISCLOSURE_GROUP_ID, ZERO_RATE_TYPE, DISCLOSED_CATEGORY))
                .orElseThrow(() -> new IllegalStateException("the reference seed must carry the padded"
                        + " default disclosure group for type " + ZERO_RATE_TYPE + " category "
                        + DISCLOSED_CATEGORY + "; it is what makes the zero-rate arm reachable"))
                .getDisIntRate())
                .as("the zero-rate arm is reached through the DEFAULT group, so no synthetic row and no"
                        + " third group is needed for it")
                .isEqualByComparingTo(ZERO_RATE);
    }

    @Test
    @Order(5)
    @DisplayName("a constructed account is required to reach the direct lookup at all, and it resolves "
            + "without entering the fallback")
    void aConstructedFixtureIsRequiredToReachTheDirectLookup() {
        installConstructedAccount(TestDataFactory.DIRECT_HIT_DISCLOSURE_GROUP_ID);
        final TransactionCategoryBalance row = new TransactionCategoryBalance(CONSTRUCTED_ACCOUNT,
                EARNING_TYPE, DISCLOSED_CATEGORY, LOAD_BEARING_BALANCE);

        final DisclosureGroup direct = this.disclosureGroupRepository.findById(new DisclosureGroupId(
                TestDataFactory.DIRECT_HIT_DISCLOSURE_GROUP_ID, EARNING_TYPE, DISCLOSED_CATEGORY))
                .orElseThrow(() -> new IllegalStateException("the reference seed must carry the first"
                        + " disclosure group, which is the only real group identifier a constructed"
                        + " account can name"));

        final List<Transaction> written = new ArrayList<>();
        final InterestCalculationService.GroupInterestResult result = this.interestCalculationService
                .calculateGroupInterest(RUN_DATE, CONSTRUCTED_ACCOUNT, List.of(row),
                        InterestCalculationProcessor.NO_TRAN_ID_SUFFIX, written::add);

        assertThat(result.defaultGroupUsed())
                .as("the direct probe HIT, so the single default probe was never performed - and this is"
                        + " unreachable from seeded data, which is why the account is constructed")
                .isFalse();
        assertThat(result.rateGateSkipped()).isFalse();
        assertThat(result.categoryInterests()).singleElement().satisfies(detail -> {
            assertThat(detail.disclosedRate()).isEqualByComparingTo(direct.getDisIntRate());
            assertThat(detail.monthlyInterest())
                    .isEqualByComparingTo(expectedMonthlyInterest(LOAD_BEARING_BALANCE,
                            direct.getDisIntRate()));
            assertThat(detail.producedTransaction()).isTrue();
        });
        assertThat(written).hasSize(1);
        assertThat(result.lastTranIdSuffix())
                .as("a group handed a fresh counter mints its first identifier with suffix one")
                .isEqualTo(FIRST_SUFFIX);
        assertThat(written.get(0).getTranId())
                .isEqualTo(expectedTranId(RUN_DATE, FIRST_SUFFIX));
        assertThat(result.updatedAccount().getAcctCurrBal())
                .isEqualByComparingTo(CONSTRUCTED_OPENING_BALANCE.add(result.totalInterest()));
        assertThat(result.updatedAccount().getAcctCurrCycCredit()).isEqualByComparingTo(ZERO_AMOUNT);
        assertThat(result.updatedAccount().getAcctCurrCycDebit()).isEqualByComparingTo(ZERO_AMOUNT);
    }

    /**
     * The zero-rate skip reached through a <strong>direct</strong> group hit, which is a different path
     * through the resolver from the one the fallback reaches.
     *
     * <h2>Why the fallback proof is not this proof</h2>
     *
     * <p>The specification below this one reaches a rate of zero, and it reaches it through the padded
     * default group: the constructed account leaves its group identifier blank exactly as every seeded
     * account does, the direct probe misses, and the fallback resolves the rate. That covers the gate. It
     * does <strong>not</strong> cover the resolver, because on that path the group identifier the account
     * carries is never used to find anything - the rate comes from a literal the program supplies.
     *
     * <p>Reaching a zero rate <em>directly</em> means the account's own group identifier resolves a row,
     * and that row discloses zero. Nothing in the delivered seed can do it: every seeded account's group
     * identifier is ten spaces, so the first probe always misses, and the seed's own migration says so in
     * as many words - covering the skip needs an account constructed with the padded zero-rate group plus a
     * matching category balance. Without this specification the two arms are conflated: a resolver defect
     * that returned the fallback group whenever the direct row disclosed zero - or one that mixed up which
     * group a direct hit came from - would leave every other assertion in this file passing, because every
     * other assertion either takes the fallback or takes a direct hit at a non-zero rate.
     *
     * <h2>The key is padded and the padding is load-bearing</h2>
     *
     * <p>The group identifier is ten characters and the literal is seven, so the seeded key carries exactly
     * three trailing spaces. The account's own field is ten characters wide for the same reason. Trimming
     * either would produce a key that matches nothing, the direct probe would miss, and this specification
     * would silently become a second copy of the fallback one - which is why the padding is asserted here
     * before the lookup is performed.
     */
    @Test
    @Order(6)
    @DisplayName("a constructed account naming the padded zero-rate group resolves it DIRECTLY, and that "
            + "direct hit is skipped by the rate gate with no transaction, no fee and no balance movement")
    void aDirectHitOnTheZeroRateGroupIsSkippedWithoutTouchingTheAccount() {
        assertThat(TestDataFactory.ZERO_RATE_DISCLOSURE_GROUP_ID)
                .as("the seven-character literal occupies a ten-character key field, so the key carries "
                        + "THREE trailing spaces and the padding is part of it")
                .isEqualTo(UNPADDED_ZERO_RATE_GROUP + "   ")
                .hasSize(ACCOUNT_GROUP_ID_WIDTH)
                .isNotEqualTo(TestDataFactory.FALLBACK_DISCLOSURE_GROUP_ID)
                .isNotEqualTo(TestDataFactory.DIRECT_HIT_DISCLOSURE_GROUP_ID);
        assertThat(this.disclosureGroupRepository.findById(new DisclosureGroupId(
                UNPADDED_ZERO_RATE_GROUP, EARNING_TYPE, DISCLOSED_CATEGORY)))
                .as("the bare word, trimmed of its padding, matches nothing at all - which is what would "
                        + "turn this specification back into the fallback one without failing")
                .isEmpty();

        final DisclosureGroup disclosed = this.disclosureGroupRepository.findById(new DisclosureGroupId(
                TestDataFactory.ZERO_RATE_DISCLOSURE_GROUP_ID, EARNING_TYPE, DISCLOSED_CATEGORY))
                .orElseThrow(() -> new IllegalStateException("the reference seed must carry the padded"
                        + " zero-rate disclosure group for type " + EARNING_TYPE + " category "
                        + DISCLOSED_CATEGORY + "; it is the only real group identifier whose direct"
                        + " resolution discloses a rate of zero"));
        assertThat(disclosed.getDisIntRate())
                .as("the row a direct hit resolves discloses zero, and it is the EARNING type - so the "
                        + "zero comes from the group rather than from the type, which is what separates "
                        + "this path from the fallback one")
                .isEqualByComparingTo(ZERO_RATE);

        installConstructedAccount(TestDataFactory.ZERO_RATE_DISCLOSURE_GROUP_ID);
        final TransactionCategoryBalance row = new TransactionCategoryBalance(CONSTRUCTED_ACCOUNT,
                EARNING_TYPE, DISCLOSED_CATEGORY, LOAD_BEARING_BALANCE);

        final List<Transaction> written = new ArrayList<>();
        final InterestCalculationService.GroupInterestResult result = this.interestCalculationService
                .calculateGroupInterest(RUN_DATE, CONSTRUCTED_ACCOUNT, List.of(row),
                        InterestCalculationProcessor.NO_TRAN_ID_SUFFIX, written::add);

        assertThat(result.defaultGroupUsed())
                .as("the direct probe HIT, so the single default probe was never performed. This is the "
                        + "half of the zero-rate branch seeded data cannot reach at all")
                .isFalse();
        assertThat(result.rateGateSkipped())
                .as("and the rate it resolved is zero, so the gate closed")
                .isTrue();
        assertThat(result.categoryInterests()).singleElement().satisfies(detail -> {
            assertThat(detail.defaultGroupUsed())
                    .as("the row itself records that it took no fallback")
                    .isFalse();
            assertThat(detail.disclosedRate())
                    .as("read from the row the direct probe resolved, not from a literal")
                    .isEqualByComparingTo(disclosed.getDisIntRate())
                    .isEqualByComparingTo(ZERO_RATE);
            assertThat(detail.rateGateSkipped()).isTrue();
            assertThat(detail.monthlyInterest())
                    .as("no computation ran, so the interest is zero rather than the truncated product "
                            + "the same balance yields at the earning rate")
                    .isEqualByComparingTo(ZERO_AMOUNT)
                    .isNotEqualByComparingTo(TRUNCATED_INTEREST);
            assertThat(detail.interestTransaction())
                    .as("and no transaction is synthesized, whatever the balance")
                    .isNull();
            assertThat(detail.producedTransaction()).isFalse();
            assertThat(detail.categoryBalance())
                    .as("the skipped row's balance was large enough that a leaked computation would have "
                            + "been obvious - it is the very pair the truncation assertions use")
                    .isEqualByComparingTo(LOAD_BEARING_BALANCE);
        });
        assertThat(written)
                .as("nothing was written to the sequential output either, so the skip is observable "
                        + "outside the result as well as inside it")
                .isEmpty();
        assertThat(result.interestTransactions()).isEmpty();
        assertThat(result.lastTranIdSuffix())
                .as("the identifier counter did not advance, because minting an identifier is part of "
                        + "writing a record and no record was written")
                .isEqualTo(InterestCalculationProcessor.NO_TRAN_ID_SUFFIX);

        assertThat(result.totalInterest())
                .as("the running total is zero: the gate encloses BOTH the computation and the fee "
                        + "invocation, so a zero rate skips the two together and the invoked fee "
                        + "paragraph contributes nothing. Inventing fee logic for it would be feature "
                        + "expansion that changes what an interest run outputs")
                .isEqualByComparingTo(ZERO_AMOUNT);
        assertThat(result.updatedAccount().getAcctCurrBal())
                .as("so the control break posts a movement of zero and the balance is exactly what the "
                        + "constructed account opened with")
                .isEqualByComparingTo(CONSTRUCTED_OPENING_BALANCE);
        assertThat(result.updatedAccount().getAcctCurrCycCredit())
                .as("both cycle accumulators are still reset, because the control break rewrites the "
                        + "account whether or not any row of the group accrued")
                .isEqualByComparingTo(ZERO_AMOUNT);
        assertThat(result.updatedAccount().getAcctCurrCycDebit()).isEqualByComparingTo(ZERO_AMOUNT);
        assertThat(result.accountRewritten())
                .as("and the rewrite did happen, so a zero movement is a posted zero rather than a "
                        + "skipped write")
                .isTrue();
    }

    @Test
    @Order(7)
    @DisplayName("a zero rate reached through the FALLBACK group skips the computation and the fee "
            + "invocation together, and the fee produces no fee of any kind when the rate is non-zero")
    void theZeroRateGateSkipsTheComputationAndTheFeeTogether() {
        installConstructedAccount(TestDataFactory.SEEDED_ACCOUNT_GROUP_ID);

        // Two rows in one group: one the gate lets through and one it skips. Both resolve through the
        // padded default literal, because this constructed account leaves its group identifier blank
        // exactly as every seeded account does.
        final List<TransactionCategoryBalance> group = List.of(
                new TransactionCategoryBalance(CONSTRUCTED_ACCOUNT, EARNING_TYPE, DISCLOSED_CATEGORY,
                        LOAD_BEARING_BALANCE),
                new TransactionCategoryBalance(CONSTRUCTED_ACCOUNT, ZERO_RATE_TYPE, DISCLOSED_CATEGORY,
                        SKIPPED_BALANCE));

        final List<Transaction> written = new ArrayList<>();
        final InterestCalculationService.GroupInterestResult result = this.interestCalculationService
                .calculateGroupInterest(RUN_DATE, CONSTRUCTED_ACCOUNT, group,
                        InterestCalculationProcessor.NO_TRAN_ID_SUFFIX, written::add);

        assertThat(result.defaultGroupUsed())
                .as("a blank group identifier misses the direct probe and resolves through the padded"
                        + " default literal")
                .isTrue();
        assertThat(result.rateGateSkipped()).isTrue();
        assertThat(result.categoryInterests()).hasSize(2);

        final InterestCalculationService.CategoryInterest accrued = result.categoryInterests().get(0);
        final InterestCalculationService.CategoryInterest gated = result.categoryInterests().get(1);

        assertThat(accrued.rateGateSkipped()).isFalse();
        assertThat(accrued.monthlyInterest()).isEqualByComparingTo(TRUNCATED_INTEREST);
        assertThat(accrued.producedTransaction()).isTrue();

        assertThat(gated.rateGateSkipped())
                .as("the gate tests the rate and encloses BOTH the computation and the fee invocation,"
                        + " so a zero rate skips the two together")
                .isTrue();
        assertThat(gated.monthlyInterest()).isEqualByComparingTo(ZERO_AMOUNT);
        assertThat(gated.interestTransaction())
                .as("no transaction is synthesized for a zero-rate row, whatever its balance")
                .isNull();
        assertThat(gated.producedTransaction()).isFalse();
        assertThat(gated.categoryBalance())
                .as("the skipped row's balance was large enough that a leaked computation would have"
                        + " been obvious")
                .isEqualByComparingTo(SKIPPED_BALANCE);

        assertThat(written)
                .as("one record for the row the gate let through and none for the row it skipped")
                .hasSize(1);

        // THE FEE PARAGRAPH IS INVOKED AND PRODUCES NOTHING. It is empty in the source and genuinely
        // invoked from inside the gate, so it survives translation as an invoked method with no body. It
        // is observable exactly here: the group's accrued total is the sum of its per-row interest and
        // not a penny more, and the balance the control break posted moved by that same total. Any
        // invented fee would appear as a discrepancy in one of the two.
        assertThat(result.totalInterest())
                .as("the running total is the sum of the per-row interest alone; the invoked fee"
                        + " paragraph contributes nothing, and inventing fee logic for it would be"
                        + " feature expansion that changes what an interest run outputs")
                .isEqualByComparingTo(accrued.monthlyInterest().add(gated.monthlyInterest()))
                .isEqualByComparingTo(TRUNCATED_INTEREST);
        assertThat(result.updatedAccount().getAcctCurrBal())
                .as("and the posted balance moved by that total and by nothing else")
                .isEqualByComparingTo(CONSTRUCTED_OPENING_BALANCE.add(TRUNCATED_INTEREST));
        assertThat(result.updatedAccount().getAcctCurrCycCredit())
                .as("both cycle accumulators are reset even in a group one of whose rows was skipped")
                .isEqualByComparingTo(ZERO_AMOUNT);
        assertThat(result.updatedAccount().getAcctCurrCycDebit()).isEqualByComparingTo(ZERO_AMOUNT);
    }

    /**
     * Installs one constructed account, the card it owns, the cross-reference that resolves the two, and
     * the opening amounts a control break acts on - all outside every seeded key range.
     *
     * <p>A constructed account is the only way to reach the direct disclosure lookup, and it is also the
     * cleanest way to present a chosen pair of rows to one control break. The cycle accumulators are given
     * non-zero opening values so that a break which resets only one of them cannot pass.
     *
     * <p>The three rows are written in referential order, because the migrated schema declares what the
     * legacy files only implied: the cross-reference references the card and the account, and the card
     * references the account. The customer, however, is <strong>borrowed from the seed</strong> rather than
     * constructed - the cross-reference references one, and a customer row carries a protected identifier
     * that only its own encryption envelope can produce, so minting one here would put key material into a
     * specification about interest arithmetic. Reading the identifier back from a seeded cross-reference
     * keeps this fixture to the three rows it actually needs.
     *
     * @param groupId the ten-character group identifier the account is to name
     */
    private void installConstructedAccount(final String groupId) {
        final String seededCustomerId = this.cardCrossReferenceRepository
                .findAll(Sort.by(Sort.Direction.ASC, "xrefCardNum")).stream()
                .map(CardCrossReference::getXrefCustId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the reference seed must carry"
                        + " cross-reference rows; the constructed fixture borrows a customer identifier"
                        + " from the first of them rather than minting a protected identifier of its own"));

        this.accountRepository.saveAndFlush(TestDataFactory.account()
                .acctId(CONSTRUCTED_ACCOUNT)
                .groupId(groupId)
                .currentBalance(CONSTRUCTED_OPENING_BALANCE)
                .cycleCredit(CONSTRUCTED_CYCLE_AMOUNT)
                .cycleDebit(CONSTRUCTED_CYCLE_AMOUNT)
                .build());
        this.cardRepository.saveAndFlush(TestDataFactory.card()
                .cardNumber(CONSTRUCTED_CARD_NUMBER)
                .accountId(CONSTRUCTED_ACCOUNT)
                .build());
        this.cardCrossReferenceRepository.saveAndFlush(TestDataFactory.cardCrossReference()
                .cardNumber(CONSTRUCTED_CARD_NUMBER)
                .customerId(seededCustomerId)
                .accountId(CONSTRUCTED_ACCOUNT)
                .build());
    }

    // ===============================================================================================
    // Per-execution state, instrumentation, and the roster the schema owns.
    // ===============================================================================================

    @Test
    @Order(8)
    @DisplayName("the six-digit identifier suffix restarts on a second execution rather than continuing "
            + "from the first, and each execution owns its own generation")
    void theSuffixRestartsOnASecondExecution() throws Exception {
        // No posting precondition is needed here and none is taken. The rate gate tests the RATE and not
        // the balance, so the seeded rows - whose balances are all zero - still mint one record each at a
        // non-zero disclosed rate. That is precisely the shape this specification wants: the suffix is
        // observable while the amount is not the subject.
        final JobExecution first = runAccrual(RUN_DATE);
        final int firstMinted = theOnlyStep(first).getExecutionContext()
                .getInt(InterestCalculationProcessor.CONTEXT_TRANSACTIONS);
        assertThat(firstMinted)
                .as("the seeded driving input mints one record per row at the disclosed non-zero rate")
                .isEqualTo(SEEDED_FIFTY);

        final JobExecution second = runAccrual(SECOND_RUN_DATE);
        final StepExecution secondStep = theOnlyStep(second);
        assertThat(secondStep.getExecutionContext()
                .getLong(InterestCalculationProcessor.CONTEXT_LAST_TRAN_ID_SUFFIX))
                .as("the second execution finished on its own count rather than on the sum of both,"
                        + " which is what a per-execution counter does and a mutable singleton field"
                        + " would not")
                .isEqualTo(firstMinted);

        final byte[] firstArtefact = generationOf(first);
        final byte[] secondArtefact = generationOf(second);
        assertThat(this.interestConfig.transactGeneration(first.getId().longValue()))
                .as("each execution resolves its own generation, so neither can overwrite the other's")
                .isNotEqualTo(this.interestConfig.transactGeneration(second.getId().longValue()));

        assertThat(field(firstArtefact, 0, TRAN_ID_OFFSET, TRAN_ID_WIDTH))
                .isEqualTo(expectedTranId(RUN_DATE, FIRST_SUFFIX));
        assertThat(field(secondArtefact, 0, TRAN_ID_OFFSET, TRAN_ID_WIDTH))
                .as("the second execution BEGINS AGAIN at suffix one; continuing from the first would"
                        + " leak sequence numbers across runs")
                .isEqualTo(expectedTranId(SECOND_RUN_DATE, FIRST_SUFFIX));
        assertThat(field(secondArtefact, secondArtefact.length / RECORD_WIDTH - 1, TRAN_ID_OFFSET,
                TRAN_ID_WIDTH))
                .isEqualTo(expectedTranId(SECOND_RUN_DATE, firstMinted));

        for (final byte[] artefact : List.of(firstArtefact, secondArtefact)) {
            assertThat(artefact.length % RECORD_WIDTH)
                    .as("fixed-length unblocked, so an exact multiple of the record width with no block"
                            + " padding")
                    .isZero();
            assertThat(artefact.length).isEqualTo(firstMinted * RECORD_WIDTH);
        }
    }

    @Test
    @Order(9)
    @DisplayName("the step is timed on the registry the metrics endpoint publishes, and no threshold is "
            + "asserted over any figure")
    void theStepIsTimedAndNoThresholdIsAsserted() throws Exception {
        // NOTHING HERE IS A THRESHOLD, and that is deliberate. No latency, throughput, availability or
        // capacity figure appears anywhere in the legacy estate, so there is nothing to compare against
        // and asserting one would invent a service level this migration is expressly forbidden from
        // inventing. What is asserted is that the measurement EXISTS and is shaped correctly.
        final JobExecution execution = runAccrual(INSTRUMENTED_RUN_DATE);
        final StepExecution step = theOnlyStep(execution);

        assertThat(this.meterRegistry.find(BATCH_STEP_TIMER)
                .tag(BATCH_STEP_TAG, InterestCalculationJobConfig.LEGACY_PROGRAM_NAME).timers())
                .as("the registry the step builder was given is the one the metrics endpoint publishes,"
                        + " so the shared batch step template's per-step timer is reachable there,"
                        + " tagged with the program the pass translated")
                .isNotEmpty()
                .allSatisfy(timer -> {
                    assertThat(timer.count())
                            .as("the timer recorded the pass, which is presence and shape and not a"
                                    + " duration compared against a budget")
                            .isPositive();
                    assertThat(timer.getId().getTag(BATCH_OUTCOME_TAG))
                            .as("the pass is tagged with the outcome it reached, so a completing run"
                                    + " and an abending one are distinguishable on the same timer")
                            .isNotBlank();
                });
        assertThat(step.getCommitCount())
                .as("the framework recorded the pass it ran; the value is read for shape and never"
                        + " compared against a target")
                .isNotNegative();
        assertThat(step.getStartTime())
                .as("a step that ran has a start; no elapsed time is compared against a budget")
                .isNotNull();
    }

    @Test
    @Order(10)
    @DisplayName("the eleven application tables are the whole business roster, and the framework's own "
            + "metadata is counted in none of them")
    void theApplicationRosterExcludesFrameworkMetadata() throws Exception {
        // The job repository provisions its own prefixed metadata tables because every shipped profile
        // asks it to, and the migration tool keeps a history table of its own. Both are real and expected
        // and neither belongs to the record-layout inventory, so a count that included them would fail for
        // a reason that has nothing to do with the schema this job reads and writes.
        assertThat(applicationTableNames())
                .containsExactlyInAnyOrderElementsOf(APPLICATION_TABLES)
                .noneSatisfy(name -> assertThat(name.toLowerCase(Locale.ROOT)).startsWith("batch_"))
                .doesNotContain("flyway_schema_history");
        assertThat(APPLICATION_TABLES).hasSize(11);
        assertThat(this.categoryBalanceRepository.count())
                .as("the driving input is one of those eleven and the seed fills it")
                .isEqualTo(SEEDED_FIFTY);
        assertThat(RECORD_WIDTH)
                .as("the width the output generation is declared at, restated from the configuration"
                        + " under test so the two cannot drift")
                .isEqualTo(InterestCalculationJobConfig.TRANSACT_RECORD_LENGTH);
        assertThat(DAILY_TRANSACTION_WIDTH)
                .as("the posting precondition's own input carries the same record width")
                .isEqualTo(RECORD_WIDTH);
    }

    /**
     * The narrowest context this specification needs: batch orchestration and persistence, the
     * launch-boundary parameter contract, the accrual program with its collaborators, and the posting
     * program - because posting is this specification's precondition and must therefore be launchable.
     *
     * <p>No batch-enabling annotation appears here or anywhere in the module: under this framework
     * generation the batch auto-configuration backs off when that annotation is present, so adding it would
     * switch off the very infrastructure this context depends on.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({InterestCalculationJobConfig.class, PostTransactionJobConfig.class, BatchConfig.class,
            JobParameterValidators.class, BatchStagingArea.class, StagedGenerationStore.class,
            AdvisoryGenerationPublicationLock.class, FixedWidthFlatFileReaderFactory.class,
            DateValidationService.class, InterestCalculationService.class,
            InterestGroupTransactionBoundary.class, TransactionPostingService.class,
            PostingStageTransactionBoundary.class, RecordWriter.class, AbendService.class})
    @EnableConfigurationProperties(AwsProperties.class)
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @EntityScan(basePackageClasses = Account.class)
    static class PipelineContext {

        /**
         * The clock every batch timestamp is read from, fixed at the instant the shared base pins.
         *
         * <p>A fixed clock is what makes the twenty-six-character batch timestamp an exact assertable value
         * rather than a shape matched by a pattern, and it is why this specification can state the expected
         * stamp as a literal and compare against it. Nothing in this context reads a system clock.
         *
         * @return the pinned clock
         */
        @Bean
        Clock pinnedClock() {
            return FIXED_CLOCK;
        }

        /**
         * Keeps this PostgreSQL-focused specification deterministic at the object-store edge. The staging
         * boundary, the generation store and the publication lock are all the real ones; only the external
         * object-store service is replaced, and the emulator-backed tier proves those same operations
         * against a running emulator.
         *
         * <p>Reporting that the staging bucket holds nothing is what makes the posting job read its
         * sequential input from the local staging area this specification staged it into.
         *
         * @return an object-store edge that accepts uploads and holds nothing
         */
        @Bean
        S3Operations objectStore() {
            final S3Operations objectStore = mock(S3Operations.class);
            when(objectStore.listObjects(anyString(), anyString())).thenReturn(List.of());
            return objectStore;
        }

        /**
         * Prevents an unrelated notification endpoint from becoming a prerequisite of a specification about
         * interest arithmetic.
         *
         * @return a notification edge that accepts everything
         */
        @Bean
        SnsOperations notifications() {
            return mock(SnsOperations.class);
        }
    }
}
