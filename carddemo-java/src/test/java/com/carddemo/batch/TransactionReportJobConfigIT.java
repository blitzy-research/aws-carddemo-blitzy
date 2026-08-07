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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.sns.core.SnsOperations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.carddemo.batch.step.AdvisoryGenerationPublicationLock;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.domain.Transaction;
import com.carddemo.exception.AbendException;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.PostingRecordTransactionBoundary;
import com.carddemo.service.TransactionPostingService;
import com.carddemo.service.TransactionReportService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;

/**
 * The transaction report job, run end to end against a real PostgreSQL 16 server carrying the delivered
 * migrations, so that the two contracts this specification owns can be measured on real artifacts rather
 * than described.
 *
 * <p>The subject is the translation of the legacy job stream {@code app/jcl/TRANREPT.jcl}, its cataloged
 * procedure {@code app/proc/TRANREPT.prc} and the report program {@code app/cbl/CBTRN03C.cbl}. Provenance
 * for the traceability matrix header: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is a matrix
 * header string only - it is not carried by every legacy member, so nothing here asserts it against one -
 * and no legacy source line is transcribed anywhere in this file.
 *
 * <h2>The two contracts that exist nowhere else in the module</h2>
 *
 * <ol>
 * <li><strong>The 133-byte report record and its pagination arithmetic</strong>, including a page break
 *     that the legacy program genuinely <em>misses</em>. Reproduced here, not repaired.</li>
 * <li><strong>The inclusive processing-date window</strong>, which is mechanically coupled to the pinned
 *     test clock. Get the clock wrong and every record is filtered out, the report is empty, and every
 *     assertion weaker than a positive detail-row count passes vacuously.</li>
 * </ol>
 *
 * <h2>The pagination arithmetic, derived</h2>
 *
 * <p>The line counter starts at zero and the page bound is twenty records, and the modulo test is applied
 * over <strong>every written record</strong> rather than over detail rows only. Measured per-block
 * increments: the four-record header block advances the counter by <strong>four</strong>, a page-total
 * block by <strong>two</strong>, an account-total block by <strong>two</strong>, one detail record by
 * <strong>one</strong>, and the grand total by <strong>nothing at all</strong>. From those five figures:
 *
 * <ul>
 * <li>zero, then the opening header block, leaves the counter at four; detail records then carry it from
 *     five to twenty, so <strong>page one carries exactly sixteen detail records</strong>;</li>
 * <li>the modulo test fires at twenty, the page-total block carries the counter to twenty-two and a fresh
 *     header block to twenty-six, and detail records then run from twenty-seven to forty, so
 *     <strong>the steady state is exactly fourteen detail records per page</strong>.</li>
 * </ul>
 *
 * <p><strong>The missed page break, preserved as legacy behaviour and a decision-log candidate.</strong>
 * An account-total block advances the counter by two, so it can carry the counter <em>across</em> a
 * multiple of twenty without the modulo test ever observing that multiple. When the counter stands at
 * nineteen at an account boundary, the block leaves it at twenty-one, the modulo test sees one, and the
 * page break never happens: the page runs long. This specification builds a fixture that puts an account
 * boundary at exactly that phase and asserts the break <strong>does not occur</strong>; a second fixture,
 * differing only in carrying one fewer record before the boundary, leaves the counter at eighteen so the
 * block lands exactly on twenty and the break <strong>does</strong> fire. The pair is what makes the
 * omission a measured behaviour rather than an accident of one fixture.
 *
 * <p>The counter and the page bound are declared as packed fields in the legacy program, but they are
 * counters rather than monetary values, so they are plain integers here - never a scaled decimal and
 * never routed through the zoned-decimal codec.
 *
 * <h2>The accumulation chain, and the end-of-file double count</h2>
 *
 * <p>A page-total block writes the page total, <strong>folds it into the grand total</strong>, zeroes the
 * page total and advances the counter twice. An account-total block zeroes <strong>only its own
 * accumulator</strong> and never touches the grand total. Amounts therefore reach the grand total
 * <em>only</em> through page totals, and this specification asserts that chain directly: the grand total
 * equals the sum of the page-total records, and it is not the larger figure an implementation that rolled
 * account totals up as well would have produced.
 *
 * <p><strong>The end-of-file double count, likewise preserved and likewise a decision-log
 * candidate.</strong> The legacy read leaves the record area holding the previous record, so the
 * end-of-file arm re-adds the <em>last</em> record's amount to both the page total and the account total
 * before writing the final page total and the grand total. Two consequences are asserted exactly: the last
 * record's amount is counted <strong>twice</strong> in the grand total, and <strong>no account-total
 * record is written at end of file</strong> - the final card's accumulated subtotal never appears.
 *
 * <h2>The date window, and why a constructed fixture is mandatory</h2>
 *
 * <p>Both bounds are <strong>inclusive</strong>, and the comparison is a pure character comparison rather
 * than a comparison of dates. The hyphenated ten-character form is load bearing: it is exactly the form in
 * which lexicographic order coincides with chronological order, which is how the legacy filtered dates
 * without ever converting one. Nothing here reformats, parses or normalises a bound.
 *
 * <p>The parameter record carries <strong>twenty-one significant bytes</strong> - a ten-character start
 * value, a one-byte separator, a ten-character end value - inside a declared eighty-byte record area, and
 * that width is asserted in <em>encoded bytes</em>. <strong>No date-parameter fixture file is created by
 * this specification</strong>, and its absence is asserted: the job member declares that input as a
 * share-disposition reference to a cataloged dataset rather than as in-stream data, so there is no card
 * image to reproduce. The window arrives as validated job parameters from the shared pinned helper
 * instead.
 *
 * <p><strong>The clock coupling.</strong> The legacy sort symbols bake a start value of {@code 2022-01-01}
 * and an end value of {@code 2022-07-06}, and posting <em>overwrites</em> the processing timestamp with a
 * freshly generated clock value. The pinned instant must therefore fall inside that window or every posted
 * record is filtered out. That relationship is asserted explicitly rather than assumed, and every report
 * assertion is preceded by a non-empty check and an exact detail-row count.
 *
 * <p><strong>The delivered fixture cannot exercise the window at all.</strong> All three hundred records of
 * the delivered daily-transaction fixture carry one single origination timestamp, and their twenty-six byte
 * processing-timestamp field is entirely blank on every one of them - so after posting every resulting
 * record carries the same generated processing timestamp and the fixture spans no date range. The
 * boundary and exclusion cases therefore <strong>require</strong> a separately constructed fixture, built
 * through the shared test data factory's varied processing-timestamp builder. No seed script and no
 * delivered fixture file is edited: they are the byte-accounting authority.
 *
 * <h2>Two source anomalies recorded here</h2>
 *
 * <ol>
 * <li>The job member's first two steps carry the <strong>identical step name</strong>. Two distinct, stable
 *     Spring Batch step names replace it, and this specification asserts both are present and distinct.
 *     For contrast, the cataloged procedure names its three steps distinctly and does not carry the
 *     duplication.</li>
 * <li>The cataloged procedure declares an internal name that <strong>collides with the separate generic
 *     copy procedure</strong>, and its own first step invokes that same name - self-invocation as
 *     literally written, which makes the member effectively dead. It is recorded and deliberately
 *     <strong>not modelled</strong>: this job is not routed through the generic copy procedure's
 *     translation.</li>
 * </ol>
 *
 * <p>The report destination's generation base is declared at a retained depth of five in one member and
 * re-declared at ten in another. <strong>The conflict resolves to ten</strong>, the later and more
 * specific declaration, and both figures are published by the configuration so the conflict stays visible.
 * The destination is a distinct resource per execution, resolved from configuration by logical name; no
 * path is written into the job and no retention is applied by it.
 *
 * <h2>Why the ordering comparator must stay private to the job</h2>
 *
 * <p>Byte position 263 of the record image is typed <strong>zoned decimal</strong> by this job's own sort
 * specification and <strong>character</strong> by the statement job's. The same sixteen bytes, two
 * typings, two jobs - so one shared comparator would silently apply one job's typing to the other job's
 * data, with no symptom a compiler could raise. This specification proves the separation behaviourally:
 * it presents card images whose zoned-decimal order and character order <em>disagree</em> and asserts the
 * job produces the zoned-decimal order. The ordering has exactly one key, card number ascending, because
 * the specification declares no secondary key.
 *
 * <h2>What this specification does not do</h2>
 *
 * <p>It never builds an expected value by delegating to a collaborator of the job under test. Every
 * expected 133-byte record, every amount mask and every signed card image in this file is assembled from
 * plain strings and characters here, so an expectation cannot inherit a defect from the code it is
 * checking. It asserts no throughput, latency or memory figure and no wall-clock bound: the batch step
 * timers are read for presence and shape only. It adds no skip policy and no fault-tolerant step - the
 * report program has no reject path, and the reject record belongs exclusively to the posting job.
 */
@SpringBootTest(classes = TransactionReportJobConfigIT.JobContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false",
                TransactionReportJobConfigIT.REPORT_STAGING_DIRECTORY_PROPERTY + "="
                        + TransactionReportJobConfigIT.STAGING_DIRECTORY_LOCATION,
                PostTransactionJobConfig.STAGING_DIRECTORY_PROPERTY + "="
                        + TransactionReportJobConfigIT.STAGING_DIRECTORY_LOCATION})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("TransactionReportJobConfigIT - three steps, no gate, an inclusive window, and a 133-byte "
        + "record whose page break is genuinely missed")
final class TransactionReportJobConfigIT extends AbstractPostgresIT {

    // -----------------------------------------------------------------------------------------------
    // Measured contracts, taken from the legacy members rather than from the code under test. Each is
    // cross-checked against the configuration's own published constant, so a drift on either side
    // fails rather than cancelling out.
    // -----------------------------------------------------------------------------------------------

    /** Declared record length of the report, fixed-length blocked. */
    private static final int REPORT_RECORD_WIDTH = 133;

    /** Declared record length of both staged transaction generations, fixed-length blocked. */
    private static final int TRANSACTION_RECORD_WIDTH = 350;

    /** The page bound the modulo test breaks a page on. */
    private static final int PAGE_BOUND = 20;

    /** Significant width of the date-parameter group, in encoded bytes. */
    private static final int DATE_PARAMETER_SIGNIFICANT_WIDTH = 21;

    /** Declared width of the record area the date-parameter group is carried inside. */
    private static final int DATE_PARAMETER_RECORD_AREA_WIDTH = 80;

    /** One-based position the sort specification gives the card-number key. */
    private static final int CARD_NUMBER_POSITION = 263;

    /** Declared length of the card-number key. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** One-based position the sort specification gives the processing-date key. */
    private static final int PROCESSING_DATE_POSITION = 305;

    /** Declared length of the processing-date key, and of every bound compared against it. */
    private static final int PROCESSING_DATE_WIDTH = 10;

    /** Records the four-record header block writes, and the counter advance it makes. */
    private static final int HEADER_BLOCK_RECORDS = 4;

    /** Records a page-total block writes - the total and the rule - and the counter advance it makes. */
    private static final int PAGE_TOTAL_BLOCK_RECORDS = 2;

    /** Records an account-total block writes, and the counter advance it makes. */
    private static final int ACCOUNT_TOTAL_BLOCK_RECORDS = 2;

    /** Records one detail line writes, and the counter advance it makes. */
    private static final int DETAIL_RECORD_ADVANCE = 1;

    /** Counter advance the grand total makes: none, because no record's placement can follow it. */
    private static final int GRAND_TOTAL_ADVANCE = 0;

    /** Detail records the first page carries, derived in the class documentation. */
    private static final int FIRST_PAGE_DETAIL_RECORDS = 16;

    /** Detail records every later page carries, derived in the class documentation. */
    private static final int STEADY_STATE_DETAIL_RECORDS = 14;

    /** Inclusive lower bound the legacy sort symbols bake into the member. */
    private static final String LEGACY_WINDOW_START = "2022-01-01";

    /** Inclusive upper bound the legacy sort symbols bake into the member. */
    private static final String LEGACY_WINDOW_END = "2022-07-06";

    /** Legacy step name of the unload lifecycle, as the cataloged form names it. */
    private static final String LEGACY_UNLOAD_STEP = "STEP01R";

    /** Legacy step name of the ordering lifecycle. */
    private static final String LEGACY_SORT_STEP = "STEP05R";

    /** Legacy step name of the report lifecycle. */
    private static final String LEGACY_REPORT_STEP = "STEP10R";

    /** Legacy member name the abend diagnostic names as the culprit. */
    private static final String LEGACY_PROGRAM = "CBTRN03C";

    /**
     * Logical base of the report destination, which is the generation group the two conflicting limit
     * declarations both name. It is a logical name, never a filesystem path.
     */
    private static final String REPORT_LOGICAL_BASE = "AWS.M2.CARDDEMO.TRANREPT";

    /** Suffix a resolved relative generation carries, which is what makes each execution's own visible. */
    private static final String GENERATION_VERSION_SUFFIX = "V00";

    /** Timer the shared batch template records one program lifecycle on. */
    private static final String LIFECYCLE_TIMER = "carddemo.batch.cobol.step";

    /** Tag that timer carries the legacy step name under. */
    private static final String TIMER_STEP_TAG = "step";

    /** Tag that timer carries the lifecycle outcome under. */
    private static final String TIMER_OUTCOME_TAG = "outcome";

    /** Outcome value a lifecycle that ran to its own end records. */
    private static final String TIMER_OUTCOME_COMPLETED = "COMPLETED";

    /** Description that timer carries, which is the shape a dashboard binds to. */
    private static final String LIFECYCLE_TIMER_DESCRIPTION =
            "Elapsed time of one legacy CardDemo batch program lifecycle";

    /** The one raw two-byte status the three reference lookups report. */
    private static final String STATUS_RECORD_NOT_FOUND = "23";

    /** Prefix the shared status diagnostic carries, emitted before any abend. */
    private static final String STATUS_DIAGNOSTIC_PREFIX = "FILE STATUS IS: NNNN";

    /** Prefix the terminal abend diagnostic carries. */
    private static final String ABEND_DIAGNOSTIC_PREFIX = "ABENDING PROGRAM";

    /** The source's own diagnostic for a card the cross-reference cannot resolve. */
    private static final String DIAGNOSTIC_INVALID_CARD = "INVALID CARD NUMBER";

    /** The source's own diagnostic for a transaction type the reference table cannot resolve. */
    private static final String DIAGNOSTIC_INVALID_TYPE = "INVALID TRANSACTION TYPE";

    /** The source's own diagnostic for a transaction category the reference table cannot resolve. */
    private static final String DIAGNOSTIC_INVALID_CATEGORY = "INVALID TRAN CATG KEY";

    // -----------------------------------------------------------------------------------------------
    // The report layout, group by group, as the report copybook declares it. Every figure below is a
    // measured field width or offset and is used only to ASSEMBLE AN EXPECTATION HERE - never read from
    // the formatter the job delegates to, which is precisely what keeps the oracle independent.
    // -----------------------------------------------------------------------------------------------

    /** Width of the short-name field the name header opens with. */
    private static final int NAME_HEADER_SHORT_NAME_WIDTH = 38;

    /** Width of the long-name field that follows it. */
    private static final int NAME_HEADER_LONG_NAME_WIDTH = 41;

    /** Width of the date-range label. */
    private static final int NAME_HEADER_LABEL_WIDTH = 12;

    /** Width of the literal separating the two bounds in the name header. */
    private static final int NAME_HEADER_TO_WIDTH = 4;

    /** Width of the transaction identifier on a detail record. */
    private static final int DETAIL_IDENTIFIER_WIDTH = 16;

    /** Width of the account identifier on a detail record. */
    private static final int DETAIL_ACCOUNT_WIDTH = 11;

    /** Width of the type code on a detail record. */
    private static final int DETAIL_TYPE_CODE_WIDTH = 2;

    /** Width of the type description on a detail record. */
    private static final int DETAIL_TYPE_DESCRIPTION_WIDTH = 15;

    /** Width of the category code on a detail record. */
    private static final int DETAIL_CATEGORY_CODE_WIDTH = 4;

    /** Width of the category description on a detail record. */
    private static final int DETAIL_CATEGORY_DESCRIPTION_WIDTH = 29;

    /** Width of the source on a detail record. */
    private static final int DETAIL_SOURCE_WIDTH = 10;

    /** Width of the blank run between the source and the amount on a detail record. */
    private static final int DETAIL_PRE_AMOUNT_BLANKS = 4;

    /** Width of the blank run after the amount on a detail record. */
    private static final int DETAIL_POST_AMOUNT_BLANKS = 2;

    /** Width of every rendered amount, on a detail record and on all three totals alike. */
    private static final int AMOUNT_FIELD_WIDTH = 15;

    /** Integer digits the amount mask carries. */
    private static final int AMOUNT_INTEGER_DIGITS = 9;

    /** Fraction digits the amount mask carries. */
    private static final int AMOUNT_FRACTION_DIGITS = 2;

    /** Position of the sign in the amount mask. */
    private static final int AMOUNT_SIGN_POSITION = 0;

    /** Position of the first group separator in the amount mask. */
    private static final int AMOUNT_FIRST_SEPARATOR_POSITION = 4;

    /** Position of the second group separator in the amount mask. */
    private static final int AMOUNT_SECOND_SEPARATOR_POSITION = 8;

    /** Position of the decimal point in the amount mask. */
    private static final int AMOUNT_POINT_POSITION = 12;

    /** Highest integer-digit index still followed by the first group separator. */
    private static final int AMOUNT_FIRST_GROUP_LAST_INDEX = 2;

    /** Highest integer-digit index still followed by the second group separator. */
    private static final int AMOUNT_SECOND_GROUP_LAST_INDEX = 5;

    /** Mask positions the nine integer digits occupy, in order. */
    private static final int[] AMOUNT_INTEGER_POSITIONS = {1, 2, 3, 5, 6, 7, 9, 10, 11};

    /** Offset at which every group places its amount field: a detail record and all three totals alike. */
    private static final int AMOUNT_OFFSET = 97;

    /** Zero at the scale every amount in this specification carries. */
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    /** Width of the label a page total opens with. */
    private static final int PAGE_TOTAL_LABEL_WIDTH = 11;

    /** Width of the dot fill a page total carries. */
    private static final int PAGE_TOTAL_DOT_WIDTH = 86;

    /** Width of the label an account total opens with. */
    private static final int ACCOUNT_TOTAL_LABEL_WIDTH = 13;

    /** Width of the dot fill an account total carries. */
    private static final int ACCOUNT_TOTAL_DOT_WIDTH = 84;

    /** Width of the label a grand total opens with. */
    private static final int GRAND_TOTAL_LABEL_WIDTH = 11;

    /** Width of the dot fill a grand total carries. */
    private static final int GRAND_TOTAL_DOT_WIDTH = 86;

    /** Text the name header's short-name field carries. */
    private static final String NAME_HEADER_SHORT_NAME = "DALYREPT";

    /** Text the name header's long-name field carries. */
    private static final String NAME_HEADER_LONG_NAME = "Daily Transaction Report";

    /** Text of the date-range label. */
    private static final String NAME_HEADER_LABEL = "Date Range: ";

    /** Literal separating the two bounds in the name header. */
    private static final String NAME_HEADER_TO = " to ";

    /** Label a page total opens with. */
    private static final String PAGE_TOTAL_LABEL = "Page Total";

    /** Label an account total opens with. */
    private static final String ACCOUNT_TOTAL_LABEL = "Account Total";

    /** Label a grand total opens with. */
    private static final String GRAND_TOTAL_LABEL = "Grand Total";

    /** Column-header texts, in the order the header record places them. */
    private static final List<String> COLUMN_HEADER_TEXTS = List.of("Transaction ID", "Account ID",
            "Transaction Type", "Tran Category", "Tran Source");

    /** Widths of those five column-header fields, in the same order. */
    private static final List<Integer> COLUMN_HEADER_WIDTHS = List.of(17, 12, 19, 35, 14);

    /** The amount column heading, whose eight leading spaces right-align it with the amount field. */
    private static final String COLUMN_HEADER_AMOUNT = "        Amount";

    /** Width of the amount column heading's field. */
    private static final int COLUMN_HEADER_AMOUNT_WIDTH = 16;

    /**
     * Positive overpunch alphabet: the final byte of a zoned field encodes both its low-order digit and
     * a positive sign, from zero through nine.
     */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** Negative overpunch alphabet, on the same terms. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    // -----------------------------------------------------------------------------------------------
    // Fixture material. Every card is one the reference seed already cross-references, because the
    // report step resolves a card to its account through that cross-reference and a card it cannot
    // resolve abends rather than continuing.
    // -----------------------------------------------------------------------------------------------

    /** A seeded card, and the lower of the two ordinary card numbers used here. */
    private static final String FIRST_CARD = "0500024453765740";

    /** The account the reference seed cross-references that card to. */
    private static final String FIRST_ACCOUNT = "00000000050";

    /** The customer the same cross-reference row names. */
    private static final String FIRST_CUSTOMER = "000000050";

    /** A second seeded card, whose number sorts above the first under either typing. */
    private static final String SECOND_CARD = "0683586198171516";

    /**
     * Sixteen digits the two overpunched fixture images are folded from.
     *
     * <p>Its low-order digit is deliberately not nine, so that folding a positive sign into the final byte
     * raises the number's value while raising the field's character value past every digit - which is what
     * makes the two typings disagree.
     */
    private static final String OVERPUNCH_BASE_CARD = "0500024453765741";

    /** A card image whose cross-reference row is deliberately absent, so its lookup abends. */
    private static final String UNREFERENCED_CARD = "0500024453765742";

    /** The account the reference seed cross-references the second card to. */
    private static final String SECOND_ACCOUNT = "00000000027";

    /** A transaction type the reference seed discloses, with the description it carries. */
    private static final String TYPE_CODE = "01";

    /** Description the seeded type carries. */
    private static final String TYPE_DESCRIPTION = "Purchase";

    /** A category the seeded type discloses, with the description it carries. */
    private static final String CATEGORY_CODE = "0001";

    /** Description the seeded category carries. */
    private static final String CATEGORY_DESCRIPTION = "Regular Sales Draft";

    /** The source every fixture record carries, at its declared ten-byte width. */
    private static final String SOURCE = "POS TERM  ";

    /** Prefix of every transaction identifier this specification reserves in the shared master. */
    private static final String IDENTIFIER_PREFIX = "95";

    /** Verification code carried by a fixture card row; not a credential of the migrated system. */
    private static final String FIXTURE_CARD_VERIFICATION_CODE = "000";

    /** Embossed name carried by a fixture card row. */
    private static final String FIXTURE_EMBOSSED_NAME = "Report Fixture";

    /** Expiration date carried by a fixture card row, comfortably after the pinned instant. */
    private static final String FIXTURE_CARD_EXPIRATION = "2023-03-09";

    /** Active-status flag carried by a fixture card row. */
    private static final String FIXTURE_ACTIVE_STATUS = "Y";

    /** A processing date comfortably inside the pinned window, on neither bound. */
    private static final LocalDate INSIDE_WINDOW = LocalDate.parse("2022-06-15");

    /** Amount every record of the plain pagination fixture carries. */
    private static final BigDecimal PLAIN_AMOUNT = new BigDecimal("10.00");

    /** Records the plain pagination fixture presents, spanning one full page and one partial one. */
    private static final int PLAIN_RECORDS = 30;

    /** Records the posting setup stages, which is the whole of the posted master it produces. */
    private static final int POSTED_SETUP_RECORDS = 3;

    /** Amount every record on the first card of the two-card fixtures carries. */
    private static final BigDecimal FIRST_CARD_AMOUNT = new BigDecimal("11.00");

    /** Amount every record on the second card of the two-card fixtures carries. */
    private static final BigDecimal SECOND_CARD_AMOUNT = new BigDecimal("7.00");

    /** Records on the second card of both two-card fixtures. */
    private static final int SECOND_CARD_RECORDS = 3;

    /**
     * Records on the first card that leave the counter at nineteen, so the account-total block's advance
     * of two straddles the page bound and the break is <strong>missed</strong>.
     */
    private static final int RECORDS_BEFORE_STRADDLING_BOUNDARY = 15;

    /**
     * Records on the first card that leave the counter at eighteen, so the same block lands exactly on
     * the page bound and the break <strong>fires</strong>.
     */
    private static final int RECORDS_BEFORE_LANDING_BOUNDARY = 14;

    /**
     * Location of the staging directory this specification owns, held as a compile-time constant so that
     * it can be inlined as configuration on the annotation rather than registered dynamically.
     *
     * <p>It is a child of the module's own build directory, and that is a deliberate isolation choice
     * rather than a convenience. The cleanup below deletes every regular file it finds in this directory,
     * so the directory must belong to this build alone; the platform temporary directory is shared by
     * every process on the host and a fixed child of it would be shared by every build on the host. A
     * build directory is private to its own checkout by construction, which removes the sharing outright
     * instead of trying to time around it. The path is relative, and the build runs each test fork with
     * the module directory as its working directory, so the configuration this class publishes and the
     * path this class inspects resolve to the same place.
     *
     * <p>Package-private rather than private because the annotation that consumes it sits outside this
     * class's body, where a private member of it is not in scope.
     */
    static final String STAGING_DIRECTORY_LOCATION = "target/carddemo-transaction-report-it";

    /**
     * The configuration key the report job resolves its staging directory from. Declared here as a literal
     * because the job carries it inline on its own injection point rather than publishing a constant, and
     * package-private for the same reason as the location above.
     */
    static final String REPORT_STAGING_DIRECTORY_PROPERTY =
            "carddemo.batch.transaction-report.staging-directory";

    /**
     * That location as a path, resolved once so the configuration and the cleanup cannot disagree about
     * which directory is in use.
     */
    private static final Path STAGING_DIRECTORY = Path.of(STAGING_DIRECTORY_LOCATION);

    /**
     * Monotonic run sequence, so each launch in this class is a distinct job instance.
     *
     * <p>The launcher takes the parameters it is given and advances no incrementer, so a second launch
     * carrying an identical identifying set would be refused as an already-completed instance. A counter
     * rather than a random value, because a run identity that varied between runs of the same suite would
     * make the batch metadata this class leaves behind non-reproducible.
     */
    private static final AtomicLong RUN_SEQUENCE = new AtomicLong();

    /**
     * Name of that identifying value, which is the key the shared run incrementer itself contributes, so
     * the metadata this class leaves behind carries the same identity a launch through the control surface
     * would have produced.
     */
    private static final String RUN_IDENTITY_KEY = "run.id";

    /**
     * Name of the identifying value the posting SETUP launch carries, which is deliberately NOT the key
     * above.
     *
     * <p>The report job is this specification's subject and no other specification launches it, so an
     * identity minted for it under the incrementer's own key cannot collide with anything. The posting job
     * is not: it is a shared precondition that several container-backed specifications launch, they all
     * write to the one batch metadata store this tier's server hosts, and the ones that launch it through
     * the control surface take their {@code run.id} from the shared incrementer - whose first value is the
     * same 1 a counter starting from zero produces here. Two launches of one job under one identifying set
     * is an already-completed instance, so a per-class counter under the shared key is an ordering
     * dependency rather than an identity, and the specification that happens to run second is refused.
     *
     * <p>A key no other specification writes removes the collision at its root rather than timing around
     * it, which is the same reason the accrual specification names its own precondition parameter instead
     * of borrowing one. Naming it for this specification's setup step also keeps the metadata legible: an
     * instance carrying this key was staged by the report specification and by nothing else. The posting
     * member declares no parameter validator and reads no parameter, so the key it is launched under is
     * free.
     */
    private static final String POSTING_SETUP_IDENTITY_KEY = "reportPostingSetupRun";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JobRegistry jobRegistry;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job transactionReportJob;

    @Autowired
    private Job postTransactionJob;

    @Autowired
    private TransactionReportJobConfig reportConfig;

    @Autowired
    private JobParameterValidators jobParameterValidators;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private BatchStagingArea stagingArea;

    /** The abend tier's own logger, which emits the status diagnostic and then the abend diagnostic. */
    private Logger abendLogger;

    /**
     * The report stage's own logger, which emits the offending key before handing over to the abend
     * tier. Recorded alongside the abend tier's logger because the ordering under assertion spans both:
     * the key comes from here, the status and the abend from there.
     */
    private Logger reportLogger;

    /**
     * One recorder attached to both loggers, so all three diagnostics can be read back in a single list
     * in emission order. A recorder per logger could not establish an ordering across the two.
     */
    private ListAppender<ILoggingEvent> abendRecorder;

    /** The level the abend tier's logger carried before this class raised it, restored afterwards. */
    private Level originalAbendLevel;

    /** The level the report stage's logger carried before this class raised it, restored afterwards. */
    private Level originalReportLevel;

    /** Creates the test class. */
    TransactionReportJobConfigIT() {
        // Intentionally empty: every fixture is installed per test method.
    }

    // -----------------------------------------------------------------------------------------------
    // Environment and lifecycle.
    // -----------------------------------------------------------------------------------------------

    /**
     * Puts the shared server back to exactly the state a fresh migration leaves it in, clears any
     * generation an earlier method staged, and arms the diagnostic recorder.
     *
     * <p>The reset is the base class's own facility and is the one a batch specification wants: this job
     * unloads the <em>whole</em> transaction master, so an exact detail-row count is only meaningful when
     * the master holds this method's fixture and nothing else. A transactional rollback could not serve,
     * because a batch job commits on its own connections.
     *
     * @throws SQLException if the shared server cannot be reset
     * @throws IOException if the staging directory cannot be inspected or cleaned
     */
    @BeforeEach
    void resetTheServerAndArmTheRecorder() throws SQLException, IOException {
        restoreSeededState();
        clearStagedGenerations();

        this.abendLogger = (Logger) LoggerFactory.getLogger(AbendService.class);
        this.reportLogger = (Logger) LoggerFactory.getLogger(TransactionReportService.class);
        this.originalAbendLevel = this.abendLogger.getLevel();
        this.originalReportLevel = this.reportLogger.getLevel();
        this.abendRecorder = new ListAppender<>();
        this.abendRecorder.setContext(this.abendLogger.getLoggerContext());
        this.abendRecorder.start();
        this.abendLogger.addAppender(this.abendRecorder);
        this.reportLogger.addAppender(this.abendRecorder);
        this.abendLogger.setLevel(Level.ERROR);
        this.reportLogger.setLevel(Level.ERROR);
    }

    /**
     * Detaches the recorder and puts the shared server back, whatever this method's outcome.
     *
     * <p>A half-restored master is as disruptive to a neighbouring specification as an unrestored one, and
     * a neighbouring specification asserts that the posted master is empty apart from its own reserved
     * range - so the restore runs on the failure path too.
     *
     * @throws SQLException if the shared server cannot be reset
     * @throws IOException if the staging directory cannot be cleaned
     */
    @AfterEach
    void restoreTheServerAndDetachTheRecorder() throws SQLException, IOException {
        this.reportLogger.detachAppender(this.abendRecorder);
        this.abendLogger.detachAppender(this.abendRecorder);
        this.abendRecorder.stop();
        this.reportLogger.setLevel(this.originalReportLevel);
        this.abendLogger.setLevel(this.originalAbendLevel);

        restoreSeededState();
        clearStagedGenerations();
    }

    /**
     * Removes every generation left in the staging directory, and creates the directory when absent.
     *
     * <p>The deletion is unconditional over everything it finds, which is safe only because the directory
     * belongs to this build alone - it is a child of this checkout's own build directory. Pointing it at a
     * shared location another build could also write into would make this helper delete that build's
     * artifacts.
     *
     * @throws IOException if the directory cannot be created, listed or cleaned
     */
    private static void clearStagedGenerations() throws IOException {
        Files.createDirectories(STAGING_DIRECTORY);
        try (Stream<Path> contents = Files.list(STAGING_DIRECTORY)) {
            for (final Path artifact : contents.toList()) {
                if (Files.isRegularFile(artifact)) {
                    Files.deleteIfExists(artifact);
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------------
    // THE INDEPENDENT ORACLE. Every expected report record is assembled here, from plain strings and a
    // character array, and never by delegating to the formatter, the report stage, the report service,
    // the zoned-decimal codec or any record mapper. Four of those are collaborators of the job under
    // test, so an expectation built from one of them would inherit the very defect it is meant to catch.
    // -----------------------------------------------------------------------------------------------

    /**
     * Performs a COBOL alphanumeric move into a fixed field: left justified, space padded on the right,
     * truncated on the right when the value is longer than the field.
     *
     * @param value the sending value; must not be {@code null}
     * @param width the receiving field's declared width
     * @return the field image, exactly {@code width} characters
     */
    private static String field(final String value, final int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Pads an assembled group out to the declared report record width, as a move into the record area
     * does.
     *
     * @param group the assembled group, no wider than the record
     * @return the record image, exactly {@link #REPORT_RECORD_WIDTH} characters
     */
    private static String toRecordWidth(final String group) {
        return field(group, REPORT_RECORD_WIDTH);
    }

    /**
     * Renders an amount through the mask a total record carries: always signed, so a non-negative value
     * shows a plus where a detail record shows a space.
     *
     * @param amount the amount, at scale two with at most nine integer digits
     * @return the fifteen-character rendering
     */
    private static String totalAmount(final BigDecimal amount) {
        return amountMask(amount, true);
    }

    /**
     * Renders an amount through the mask a detail record carries: a minus for a negative value and a
     * space otherwise, never a plus.
     *
     * @param amount the amount, at scale two with at most nine integer digits
     * @return the fifteen-character rendering
     */
    private static String detailAmount(final BigDecimal amount) {
        return amountMask(amount, false);
    }

    /**
     * Assembles one amount mask: a fixed sign position, two zero-suppressed group separators, a fixed
     * decimal point and two fraction digits, with the whole field blank for a value of exactly zero.
     *
     * <p>No scaling happens here. The amounts this class presents are written as scale-two literals, so
     * the digits are read out of the value as it stands; a rounding mode never enters the picture.
     *
     * @param amount the amount to render
     * @param alwaysSigned whether a non-negative value carries a plus
     * @return the fifteen-character rendering
     */
    private static String amountMask(final BigDecimal amount, final boolean alwaysSigned) {
        final char[] mask = new char[AMOUNT_FIELD_WIDTH];
        Arrays.fill(mask, ' ');
        if (amount.signum() == 0) {
            return new String(mask);
        }

        final String digits = String.format(Locale.ROOT,
                "%0" + (AMOUNT_INTEGER_DIGITS + AMOUNT_FRACTION_DIGITS) + "d",
                amount.abs().movePointRight(AMOUNT_FRACTION_DIGITS).toBigIntegerExact());

        mask[AMOUNT_SIGN_POSITION] = amount.signum() < 0 ? '-' : signWhenNotNegative(alwaysSigned);
        mask[AMOUNT_POINT_POSITION] = '.';
        mask[AMOUNT_POINT_POSITION + 1] = digits.charAt(AMOUNT_INTEGER_DIGITS);
        mask[AMOUNT_POINT_POSITION + 2] = digits.charAt(AMOUNT_INTEGER_DIGITS + 1);

        final int firstSignificant = firstSignificantDigit(digits);
        if (firstSignificant >= 0) {
            for (int index = firstSignificant; index < AMOUNT_INTEGER_DIGITS; index++) {
                mask[AMOUNT_INTEGER_POSITIONS[index]] = digits.charAt(index);
            }
            if (firstSignificant <= AMOUNT_FIRST_GROUP_LAST_INDEX) {
                mask[AMOUNT_FIRST_SEPARATOR_POSITION] = ',';
            }
            if (firstSignificant <= AMOUNT_SECOND_GROUP_LAST_INDEX) {
                mask[AMOUNT_SECOND_SEPARATOR_POSITION] = ',';
            }
        }
        return new String(mask);
    }

    /**
     * The sign character a non-negative amount carries under each of the two masks.
     *
     * @param alwaysSigned whether the mask is the always-signed one
     * @return a plus for the total mask, a space for the detail mask
     */
    private static char signWhenNotNegative(final boolean alwaysSigned) {
        return alwaysSigned ? '+' : ' ';
    }

    /**
     * Index of the first non-zero integer digit, which is where zero suppression stops.
     *
     * @param digits the eleven magnitude digits
     * @return the index, or {@code -1} when every integer digit is zero
     */
    private static int firstSignificantDigit(final String digits) {
        for (int index = 0; index < AMOUNT_INTEGER_DIGITS; index++) {
            if (digits.charAt(index) != '0') {
                return index;
            }
        }
        return -1;
    }

    /**
     * The report name header, carrying both bounds of the window exactly as supplied.
     *
     * @param startDate the inclusive lower bound, ten characters
     * @param endDate the inclusive upper bound, ten characters
     * @return the expected 133-character record
     */
    private static String expectedNameHeader(final String startDate, final String endDate) {
        return toRecordWidth(field(NAME_HEADER_SHORT_NAME, NAME_HEADER_SHORT_NAME_WIDTH)
                + field(NAME_HEADER_LONG_NAME, NAME_HEADER_LONG_NAME_WIDTH)
                + field(NAME_HEADER_LABEL, NAME_HEADER_LABEL_WIDTH)
                + field(startDate, PROCESSING_DATE_WIDTH)
                + field(NAME_HEADER_TO, NAME_HEADER_TO_WIDTH)
                + field(endDate, PROCESSING_DATE_WIDTH));
    }

    /**
     * The blank record of the header block, which is a real emitted record of spaces and not padding.
     *
     * @return the expected 133-character record
     */
    private static String expectedBlankRecord() {
        return " ".repeat(REPORT_RECORD_WIDTH);
    }

    /**
     * The column header record.
     *
     * @return the expected 133-character record
     */
    private static String expectedColumnHeader() {
        final StringBuilder group = new StringBuilder();
        for (int index = 0; index < COLUMN_HEADER_TEXTS.size(); index++) {
            group.append(field(COLUMN_HEADER_TEXTS.get(index),
                    COLUMN_HEADER_WIDTHS.get(index).intValue()));
        }
        group.append(' ').append(field(COLUMN_HEADER_AMOUNT, COLUMN_HEADER_AMOUNT_WIDTH));
        return toRecordWidth(group.toString());
    }

    /**
     * The rule record, which is the only group already the full record width and so is never padded.
     *
     * @return the expected 133-character record
     */
    private static String expectedRuleRecord() {
        return "-".repeat(REPORT_RECORD_WIDTH);
    }

    /**
     * The four records of one header block, in the one legal order.
     *
     * @param startDate the inclusive lower bound
     * @param endDate the inclusive upper bound
     * @return the four expected records
     */
    private static List<String> expectedHeaderBlock(final String startDate, final String endDate) {
        return List.of(expectedNameHeader(startDate, endDate), expectedBlankRecord(),
                expectedColumnHeader(), expectedRuleRecord());
    }

    /**
     * One detail record, assembled field by field with both hyphens placed unconditionally.
     *
     * @param identifier the transaction identifier
     * @param accountId the account the cross-reference resolved
     * @param amount the transaction amount at scale two
     * @return the expected 133-character record
     */
    private static String expectedDetailRecord(final String identifier, final String accountId,
            final BigDecimal amount) {

        return toRecordWidth(field(identifier, DETAIL_IDENTIFIER_WIDTH) + " "
                + field(accountId, DETAIL_ACCOUNT_WIDTH) + " "
                + field(TYPE_CODE, DETAIL_TYPE_CODE_WIDTH) + "-"
                + field(TYPE_DESCRIPTION, DETAIL_TYPE_DESCRIPTION_WIDTH) + " "
                + field(CATEGORY_CODE, DETAIL_CATEGORY_CODE_WIDTH) + "-"
                + field(CATEGORY_DESCRIPTION, DETAIL_CATEGORY_DESCRIPTION_WIDTH) + " "
                + field(SOURCE, DETAIL_SOURCE_WIDTH) + " ".repeat(DETAIL_PRE_AMOUNT_BLANKS)
                + detailAmount(amount) + " ".repeat(DETAIL_POST_AMOUNT_BLANKS));
    }

    /**
     * One page-total record.
     *
     * @param amount the page total at scale two
     * @return the expected 133-character record
     */
    private static String expectedPageTotalRecord(final BigDecimal amount) {
        return toRecordWidth(field(PAGE_TOTAL_LABEL, PAGE_TOTAL_LABEL_WIDTH)
                + ".".repeat(PAGE_TOTAL_DOT_WIDTH) + totalAmount(amount));
    }

    /**
     * One account-total record.
     *
     * @param amount the account total at scale two
     * @return the expected 133-character record
     */
    private static String expectedAccountTotalRecord(final BigDecimal amount) {
        return toRecordWidth(field(ACCOUNT_TOTAL_LABEL, ACCOUNT_TOTAL_LABEL_WIDTH)
                + ".".repeat(ACCOUNT_TOTAL_DOT_WIDTH) + totalAmount(amount));
    }

    /**
     * The single grand-total record, which is the last record of a report.
     *
     * @param amount the grand total at scale two
     * @return the expected 133-character record
     */
    private static String expectedGrandTotalRecord(final BigDecimal amount) {
        return toRecordWidth(field(GRAND_TOTAL_LABEL, GRAND_TOTAL_LABEL_WIDTH)
                + ".".repeat(GRAND_TOTAL_DOT_WIDTH) + totalAmount(amount));
    }

    /**
     * Folds a sixteen-digit card number's final byte into an overpunched zoned-decimal image, so that the
     * field's character order and its zoned-decimal order can be made to disagree.
     *
     * <p>Encoded here from the convention itself rather than through the codec the job uses, for the same
     * reason every other expectation is: an oracle that shared the subject's implementation would agree
     * with it whatever either of them did.
     *
     * @param sixteenDigits the card number, exactly sixteen digits
     * @param negative whether the folded sign is negative
     * @return the sixteen-byte field image with an overpunched final byte
     */
    private static String signedCardImage(final String sixteenDigits, final boolean negative) {
        final int lowOrderDigit = sixteenDigits.charAt(CARD_NUMBER_WIDTH - 1) - '0';
        final String alphabet = negative ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        return sixteenDigits.substring(0, CARD_NUMBER_WIDTH - 1) + alphabet.charAt(lowOrderDigit);
    }

    // -----------------------------------------------------------------------------------------------
    // Fixtures. Built through the shared test data factory's varied processing-timestamp builder, which
    // is the only route to a date range at all: the delivered fixture leaves the processing-timestamp
    // field blank on every one of its three hundred records. No delivered file is edited.
    // -----------------------------------------------------------------------------------------------

    /**
     * The sixteen-digit identifier of one fixture record, drawn from a range this class reserves.
     *
     * @param ordinal the record's position, from one
     * @return the identifier, exactly sixteen digits
     */
    private static String identifier(final int ordinal) {
        return IDENTIFIER_PREFIX
                + String.format(Locale.ROOT, "%014d", Integer.valueOf(ordinal));
    }

    /**
     * One posted transaction, carrying a processing date the window can select or reject.
     *
     * @param ordinal the record's position, from one, which supplies its identifier
     * @param cardNumber the card-number field image
     * @param processingDate the processing date the timestamp field opens with
     * @param amount the amount at scale two
     * @return the entity
     */
    private static Transaction fixtureRecord(final int ordinal, final String cardNumber,
            final LocalDate processingDate, final BigDecimal amount) {

        return TestDataFactory.transaction()
                .id(identifier(ordinal))
                .typeCode(TYPE_CODE)
                .categoryCode(CATEGORY_CODE)
                .source(SOURCE)
                .cardNumber(cardNumber)
                .amount(amount)
                .processingDate(processingDate)
                .build();
    }

    /**
     * The plain pagination fixture: one card, every record inside the window, so the only breaks are page
     * breaks.
     *
     * @return the records, in identifier order
     */
    private static List<Transaction> plainPaginationFixture() {
        final List<Transaction> records = new ArrayList<>();
        for (int ordinal = 1; ordinal <= PLAIN_RECORDS; ordinal++) {
            records.add(fixtureRecord(ordinal, FIRST_CARD, INSIDE_WINDOW, PLAIN_AMOUNT));
        }
        return List.copyOf(records);
    }

    /**
     * A two-card fixture whose account boundary sits at a chosen phase of the page arithmetic.
     *
     * @param recordsOnFirstCard how many records precede the boundary, which decides the phase
     * @return the records, in identifier order
     */
    private static List<Transaction> twoCardFixture(final int recordsOnFirstCard) {
        final List<Transaction> records = new ArrayList<>();
        for (int ordinal = 1; ordinal <= recordsOnFirstCard; ordinal++) {
            records.add(fixtureRecord(ordinal, FIRST_CARD, INSIDE_WINDOW, FIRST_CARD_AMOUNT));
        }
        for (int offset = 1; offset <= SECOND_CARD_RECORDS; offset++) {
            records.add(fixtureRecord(recordsOnFirstCard + offset, SECOND_CARD, INSIDE_WINDOW,
                    SECOND_CARD_AMOUNT));
        }
        return List.copyOf(records);
    }

    /**
     * Writes the supplied records into the shared master and makes them visible to the job's own
     * connection.
     *
     * @param records the fixture to install
     */
    private void install(final List<Transaction> records) {
        this.transactionRepository.saveAll(records);
    }

    /**
     * Inserts one card and its cross-reference row for a card-number image the reference seed does not
     * carry, so that a record naming it resolves rather than abending.
     *
     * <p>Written as a parameterised statement: the card image is a value, never assembled into the
     * statement text. The rows are removed with every other application row by the reset this class runs
     * after each method.
     *
     * @param cardImage the sixteen-byte card-number field image
     * @throws SQLException if the rows cannot be inserted
     */
    private static void addResolvableCard(final String cardImage) throws SQLException {
        try (Connection connection = connect()) {
            insertCard(connection, cardImage);
            insertCrossReference(connection, cardImage);
        }
    }

    /**
     * Inserts one card row for a card-number image, without a cross-reference row.
     *
     * <p>The posted master constrains every transaction to name an existing card, so a record whose
     * cross-reference is deliberately absent still needs this row - which is exactly how the invalid-key
     * condition of the cross-reference read is reached.
     *
     * @param cardImage the sixteen-byte card-number field image
     * @throws SQLException if the row cannot be inserted
     */
    private static void addUnreferencedCard(final String cardImage) throws SQLException {
        try (Connection connection = connect()) {
            insertCard(connection, cardImage);
        }
    }

    /**
     * Inserts one card row naming the first seeded account.
     *
     * <p>Every statement in this class is a complete literal with its values bound as parameters, so no
     * value and no identifier is ever assembled into statement text.
     *
     * @param connection an open connection
     * @param cardImage the sixteen-byte card-number field image
     * @throws SQLException if the row cannot be inserted
     */
    private static void insertCard(final Connection connection, final String cardImage)
            throws SQLException {

        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO card (card_num, card_acct_id, card_cvv_cd, card_embossed_name,
                                  card_expiration_date, card_active_status)
                     VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, cardImage);
            insert.setString(2, FIRST_ACCOUNT);
            insert.setString(3, FIXTURE_CARD_VERIFICATION_CODE);
            insert.setString(4, FIXTURE_EMBOSSED_NAME);
            insert.setString(5, FIXTURE_CARD_EXPIRATION);
            insert.setString(6, FIXTURE_ACTIVE_STATUS);
            insert.executeUpdate();
        }
    }

    /**
     * Inserts one cross-reference row resolving a card image to the first seeded account.
     *
     * @param connection an open connection
     * @param cardImage the sixteen-byte card-number field image
     * @throws SQLException if the row cannot be inserted
     */
    private static void insertCrossReference(final Connection connection, final String cardImage)
            throws SQLException {

        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO card_cross_reference (xref_card_num, xref_cust_id, xref_acct_id)
                     VALUES (?, ?, ?)
                """)) {
            insert.setString(1, cardImage);
            insert.setString(2, FIRST_CUSTOMER);
            insert.setString(3, FIRST_ACCOUNT);
            insert.executeUpdate();
        }
    }

    /**
     * Removes one transaction-type row, so that the type lookup reaches its invalid-key condition.
     *
     * @param typeCode the two-character type code to remove
     * @throws SQLException if the row cannot be removed
     */
    private static void removeTransactionType(final String typeCode) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM transaction_type WHERE tran_type = ?")) {
            delete.setString(1, typeCode);
            delete.executeUpdate();
        }
    }

    /**
     * Removes one transaction-category row, so that the category lookup reaches its invalid-key
     * condition while the type lookup that precedes it still succeeds.
     *
     * @param typeCode the two-character type code half of the composite key
     * @param categoryCode the four-character category code half
     * @throws SQLException if the row cannot be removed
     */
    private static void removeTransactionCategory(final String typeCode, final String categoryCode)
            throws SQLException {

        try (Connection connection = connect();
                PreparedStatement delete = connection.prepareStatement("""
                        DELETE FROM transaction_category
                              WHERE tran_type_cd = ? AND tran_cat_cd = ?
                        """)) {
            delete.setString(1, typeCode);
            delete.setString(2, categoryCode);
            delete.executeUpdate();
        }
    }

    /**
     * Counts the cross-reference rows on the shared server, so a fixture that removed one can prove it.
     *
     * @return the row count
     * @throws SQLException if the table cannot be read
     */
    private static long crossReferenceRowCount() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM card_cross_reference")) {
            return rows.next() ? rows.getLong(1) : -1L;
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Launching, and reading the artifacts back.
    // -----------------------------------------------------------------------------------------------

    /**
     * The pinned inclusive window, plus one identifying value so each launch is a distinct instance.
     *
     * <p>The two bounds come from the shared pinned helper and their names from the parameter owner's own
     * constants, so no key literal is retyped here and the window cannot drift away from the one the rest
     * of the container-backed tier uses.
     *
     * @return the launch parameters
     */
    private static JobParameters pinnedWindowParameters() {
        return new JobParametersBuilder(pinnedDateWindowParameters(
                JobParameterValidators.REPORT_START_DATE_KEY,
                JobParameterValidators.REPORT_END_DATE_KEY))
                .addLong(RUN_IDENTITY_KEY, Long.valueOf(RUN_SEQUENCE.incrementAndGet()))
                .toJobParameters();
    }

    /**
     * Launches the report job by resolving it from the registry under its own published name, which is
     * how the operational control surface reaches it.
     *
     * @return the completed or failed execution
     * @throws Exception if the job cannot be resolved or launched
     */
    private JobExecution launchReportJob() throws Exception {
        return this.jobLauncher.run(this.jobRegistry.getJob(TransactionReportJobConfig.JOB_NAME),
                pinnedWindowParameters());
    }

    /**
     * Launches the posting job the same way. Posting is this specification's setup step, not its subject:
     * the posted master is seeded empty, and posting is what regenerates the processing timestamps the
     * report job filters on.
     *
     * <p>The run identity is minted under this specification's own key, for the reason recorded on
     * {@link #POSTING_SETUP_IDENTITY_KEY}: the posting job is a precondition several container-backed
     * specifications share on one metadata store, so the identity that keeps each of this class's launches
     * distinct must also keep them distinct from every other specification's.
     *
     * @return the completed execution
     * @throws Exception if the job cannot be resolved or launched
     */
    private JobExecution launchPostingJob() throws Exception {
        return this.jobLauncher.run(this.jobRegistry.getJob(PostTransactionJobConfig.JOB_NAME),
                new JobParametersBuilder()
                        .addLong(POSTING_SETUP_IDENTITY_KEY,
                                Long.valueOf(RUN_SEQUENCE.incrementAndGet()))
                        .toJobParameters());
    }

    /**
     * Writes a sequential daily-transaction dataset for the posting job to read, one record image per
     * line, under the logical name the job resolves by default.
     *
     * @param recordImages the record images to stage
     * @throws IOException if the dataset cannot be written
     */
    private static void stageDailyTransactionInput(final List<String> recordImages)
            throws IOException {

        final StringBuilder content = new StringBuilder();
        for (final String image : recordImages) {
            content.append(image).append('\n');
        }
        Files.writeString(
                STAGING_DIRECTORY.resolve(PostTransactionJobConfig.DEFAULT_DALYTRAN_DATASET),
                content.toString(), StandardCharsets.US_ASCII);
    }

    /**
     * The job execution identifier the framework assigned, which is how all three steps agree on one set
     * of generations.
     *
     * @param execution the execution to read
     * @return the identifier
     */
    private static long executionIdOf(final JobExecution execution) {
        return Objects.requireNonNull(execution.getId(),
                "the framework must have assigned a job execution identifier").longValue();
    }

    /**
     * Reads one staged generation back as its record images, in emission order.
     *
     * @param generation the generation to read
     * @return the record images
     * @throws IOException if the generation cannot be read
     */
    private static List<String> recordsOf(final Path generation) throws IOException {
        return Files.readAllLines(generation, StandardCharsets.US_ASCII);
    }

    /**
     * Reads the report one execution produced.
     *
     * @param execution the execution whose report to read
     * @return the report records, in emission order
     * @throws IOException if the generation cannot be read
     */
    private List<String> reportOf(final JobExecution execution) throws IOException {
        return recordsOf(this.reportConfig.reportGeneration(executionIdOf(execution)));
    }

    /**
     * Reads the filtered, ordered generation one execution produced.
     *
     * @param execution the execution whose filtered generation to read
     * @return the record images, in emission order
     * @throws IOException if the generation cannot be read
     */
    private List<String> filteredOf(final JobExecution execution) throws IOException {
        return recordsOf(this.reportConfig.filteredGeneration(executionIdOf(execution)));
    }

    /**
     * Reads the card-number field out of a staged record image, at the position the sort specification
     * declares for it.
     *
     * @param recordImage the whole record image
     * @return the sixteen-byte field, untrimmed
     */
    private static String cardNumberOf(final String recordImage) {
        final int offset = CARD_NUMBER_POSITION - 1;
        return recordImage.substring(offset, offset + CARD_NUMBER_WIDTH);
    }

    /**
     * Reads the processing-date field out of a staged record image, at the position the sort specification
     * declares for it.
     *
     * @param recordImage the whole record image
     * @return the ten-byte field, untrimmed
     */
    private static String processingDateOf(final String recordImage) {
        final int offset = PROCESSING_DATE_POSITION - 1;
        return recordImage.substring(offset, offset + PROCESSING_DATE_WIDTH);
    }

    /**
     * Reads the transaction identifier out of a staged record image, which the layout places first.
     *
     * @param recordImage the whole record image
     * @return the sixteen-byte field, untrimmed
     */
    private static String identifierOf(final String recordImage) {
        return recordImage.substring(0, DETAIL_IDENTIFIER_WIDTH);
    }

    /**
     * Reads the amount out of a report record's amount field and back into a decimal.
     *
     * <p>Parsed here rather than through anything the job uses, and parsed rather than compared as text,
     * so that a total can be summed and cross-checked. A wholly blank field is the mask's rendering of
     * exactly zero.
     *
     * @param reportRecord one 133-character report record
     * @return the amount the record carries, at scale two
     */
    private static BigDecimal reportedAmount(final String reportRecord) {
        final String mask = reportRecord.substring(AMOUNT_OFFSET, AMOUNT_OFFSET + AMOUNT_FIELD_WIDTH);
        final String magnitude = mask.replace(",", "").replace(" ", "");
        return magnitude.isEmpty() ? ZERO_AMOUNT : new BigDecimal(magnitude);
    }

    /**
     * Whether a report record is one of this specification's detail records, which is decidable because
     * every identifier it stages is drawn from a reserved range.
     *
     * @param reportRecord one report record
     * @return whether it is a detail record
     */
    private static boolean isDetailRecord(final String reportRecord) {
        return reportRecord.startsWith(IDENTIFIER_PREFIX);
    }

    /**
     * Whether a report record opens a header block.
     *
     * @param reportRecord one report record
     * @return whether it is the report name header
     */
    private static boolean isNameHeader(final String reportRecord) {
        return reportRecord.startsWith(NAME_HEADER_SHORT_NAME);
    }

    /**
     * Every report record satisfying a label prefix, in emission order.
     *
     * @param report the report records
     * @param label the label the record opens with
     * @return the matching records
     */
    private static List<String> recordsLabelled(final List<String> report, final String label) {
        final List<String> matching = new ArrayList<>();
        for (final String record : report) {
            if (record.startsWith(label)) {
                matching.add(record);
            }
        }
        return List.copyOf(matching);
    }

    /**
     * Every detail record of a report, in emission order.
     *
     * @param report the report records
     * @return the detail records
     */
    private static List<String> detailRecords(final List<String> report) {
        final List<String> details = new ArrayList<>();
        for (final String record : report) {
            if (isDetailRecord(record)) {
                details.add(record);
            }
        }
        return List.copyOf(details);
    }

    /**
     * Sums the amounts a set of report records carries.
     *
     * @param records the records to sum
     * @return the total, at scale two
     */
    private static BigDecimal sumOf(final List<String> records) {
        BigDecimal total = ZERO_AMOUNT;
        for (final String record : records) {
            total = total.add(reportedAmount(record));
        }
        return total;
    }

    /**
     * Detail records carried between one header block and the next, which is what a page is.
     *
     * @param report the report records
     * @return the per-page detail counts, in page order
     */
    private static List<Integer> detailRecordsPerPage(final List<String> report) {
        final List<Integer> perPage = new ArrayList<>();
        int running = 0;
        boolean open = false;
        for (final String record : report) {
            if (isNameHeader(record)) {
                if (open) {
                    perPage.add(Integer.valueOf(running));
                }
                running = 0;
                open = true;
            } else if (isDetailRecord(record)) {
                running++;
            }
        }
        if (open) {
            perPage.add(Integer.valueOf(running));
        }
        return List.copyOf(perPage);
    }

    /**
     * Builds a chunk context carrying the supplied launch parameters, so the parameter-record members can
     * be driven without a launcher.
     *
     * @param parameters the launch parameters
     * @return the chunk context
     */
    private static ChunkContext chunkContextFor(final JobParameters parameters) {
        return new ChunkContext(new StepContext(new StepExecution(
                TransactionReportJobConfig.EMIT_STEP_NAME,
                new JobExecution(Long.valueOf(1L), parameters))));
    }

    /**
     * The grand total the plain pagination fixture must report.
     *
     * <p>The sum of every amount the fixture carries <strong>plus the last one again</strong>: the
     * end-of-file arm re-adds the stale record's amount before the final page total is written, and the
     * grand total is fed from page totals, so the duplicate reaches it.
     *
     * @return the expected grand total, at scale two
     */
    private static BigDecimal expectedPlainGrandTotal() {
        return PLAIN_AMOUNT.multiply(new BigDecimal(PLAIN_RECORDS + 1));
    }

    /**
     * Asserts that a header block sits at one position of a report, byte for byte.
     *
     * @param report the report records
     * @param index the index the block is expected to open at
     */
    private static void assertHeaderBlockAt(final List<String> report, final int index) {
        final List<String> expected = expectedHeaderBlock(PINNED_WINDOW_START_DATE.toString(),
                PINNED_WINDOW_END_DATE.toString());
        for (int offset = 0; offset < expected.size(); offset++) {
            assertThat(report.get(index + offset).getBytes(StandardCharsets.US_ASCII))
                    .as("header block record %d, at report index %d", Integer.valueOf(offset),
                            Integer.valueOf(index + offset))
                    .isEqualTo(expected.get(offset).getBytes(StandardCharsets.US_ASCII));
        }
    }

    /**
     * Asserts that every record of an artifact measures the declared width in encoded bytes, and that the
     * record images together account for an exact multiple of it.
     *
     * <p>Measured on the encoded array rather than on a character count, because the record length is a
     * byte contract, and never on a trimmed value: a trimmed comparison would accept a record that no
     * longer fits its declared length.
     *
     * <p>The staged datasets this job writes carry one line terminator after each fixed-width record - a
     * deliberate choice of the module's own staging mechanics, so that a generation is byte-identical on
     * every host and readable by the shared fixed-width reader. The terminators are therefore excluded
     * from the record-image accounting and the record images alone are measured.
     *
     * @param records the record images
     * @param declaredWidth the declared record length
     */
    private static void assertEveryRecordMeasures(final List<String> records,
            final int declaredWidth) {

        long encodedTotal = 0L;
        for (int index = 0; index < records.size(); index++) {
            final String record = records.get(index);
            final byte[] encoded = record.getBytes(StandardCharsets.US_ASCII);
            assertThat(encoded.length)
                    .as("record %d must measure exactly %d encoded bytes, never a trimmed value",
                            Integer.valueOf(index), Integer.valueOf(declaredWidth))
                    .isEqualTo(declaredWidth);
            encodedTotal += encoded.length;
        }
        assertThat(encodedTotal % declaredWidth)
                .as("the record images of a fixed-length dataset must account for an exact multiple "
                        + "of %d bytes", Integer.valueOf(declaredWidth))
                .isZero();
        assertThat(encodedTotal)
                .isEqualTo((long) records.size() * declaredWidth);
    }

    // -----------------------------------------------------------------------------------------------
    // The measured shape of the job, and the inertness of the context.
    // -----------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("three steps under two distinct names, not one failure-ending transition, and nothing "
            + "launched merely because the context started")
    void theJobShapeIsTheMeasuredOne() throws SQLException {
        assertThat(this.jobRegistry.getJobNames())
                .as("the control surface launches by name, so both jobs must be registered under one")
                .contains(TransactionReportJobConfig.JOB_NAME, PostTransactionJobConfig.JOB_NAME);
        assertThat(this.transactionReportJob.getName())
                .isEqualTo(TransactionReportJobConfig.JOB_NAME);
        assertThat(this.postTransactionJob.getName())
                .isEqualTo(PostTransactionJobConfig.JOB_NAME);

        assertThat(this.transactionReportJob)
                .as("a flow job is the shape a failure-ending transition produces, and the member "
                        + "carries no condition-code dependency on any step")
                .isExactlyInstanceOf(SimpleJob.class);
        assertThat(((SimpleJob) this.transactionReportJob).getStepNames())
                .as("the two record-inclusion conditions the member declares are sort predicates over "
                        + "records, not gates over steps, so they add no transition")
                .containsExactly(TransactionReportJobConfig.UNLOAD_STEP_NAME,
                        TransactionReportJobConfig.FILTER_AND_ORDER_STEP_NAME,
                        TransactionReportJobConfig.EMIT_STEP_NAME)
                .doesNotHaveDuplicates()
                .hasSize(TransactionReportJobConfig.STEP_COUNT);
        assertThat(TransactionReportJobConfig.STEP_COUNT).isEqualTo(3);
        assertThat(TransactionReportJobConfig.CONDITION_CODE_GATE_COUNT).isZero();

        assertThat(TransactionReportJobConfig.UNLOAD_STEP_NAME)
                .as("the member gives its first two steps the IDENTICAL name, so the translation has to "
                        + "supply two distinct stable names of its own")
                .isNotEqualTo(TransactionReportJobConfig.FILTER_AND_ORDER_STEP_NAME);

        assertThat(this.applicationContext.getBeansOfType(ApplicationRunner.class))
                .as("no runner may fire a job when the context comes up")
                .isEmpty();
        assertThat(this.applicationContext.getBeansOfType(CommandLineRunner.class)).isEmpty();
        assertThat(this.applicationContext.getEnvironment()
                .getProperty("spring.batch.job.enabled"))
                .isEqualTo("false");
        assertThat(this.applicationContext.getEnvironment().getProperty("spring.batch.job.name"))
                .as("a named job would be started by the framework's own runner")
                .isNull();
        assertThat(this.jobExplorer
                .findRunningJobExecutions(TransactionReportJobConfig.JOB_NAME))
                .isEmpty();
        assertThat(this.jobExplorer
                .findRunningJobExecutions(PostTransactionJobConfig.JOB_NAME))
                .isEmpty();

        assertThat(applicationTableNames())
                .as("the eleven application tables the schema migration creates - the framework's own "
                        + "metadata tables and the migration history are excluded, because neither "
                        + "belongs to the record schema")
                .containsExactlyInAnyOrderElementsOf(APPLICATION_TABLES);
    }

    @Test
    @Order(2)
    @DisplayName("every width, position and retained depth the configuration publishes is the measured "
            + "one, and the retention conflict resolves to the later declaration")
    void theMeasuredContractsAgreeWithTheSource() {
        assertThat(TransactionReportJobConfig.REPORT_RECORD_LENGTH).isEqualTo(REPORT_RECORD_WIDTH);
        assertThat(TransactionReportJobConfig.UNLOAD_RECORD_LENGTH)
                .isEqualTo(TRANSACTION_RECORD_WIDTH);
        assertThat(TransactionReportJobConfig.PAGE_SIZE)
                .as("the page bound is a formatting contract and a plain integer - a counter, never a "
                        + "monetary value, so never a scaled decimal and never routed through the codec")
                .isEqualTo(PAGE_BOUND);

        assertThat(TransactionReportJobConfig.CARD_NUMBER_SORT_POSITION)
                .isEqualTo(CARD_NUMBER_POSITION);
        assertThat(TransactionReportJobConfig.CARD_NUMBER_SORT_LENGTH).isEqualTo(CARD_NUMBER_WIDTH);
        assertThat(TransactionReportJobConfig.PROCESSING_DATE_SORT_POSITION)
                .isEqualTo(PROCESSING_DATE_POSITION);
        assertThat(TransactionReportJobConfig.PROCESSING_DATE_SORT_LENGTH)
                .isEqualTo(PROCESSING_DATE_WIDTH);

        assertThat(TransactionReportJobConfig.REPORT_GENERATION_LIMIT)
                .as("declared at one depth in one member and re-declared at another in a second; the "
                        + "later and more specific declaration governs")
                .isEqualTo(10);
        assertThat(TransactionReportJobConfig.SUPERSEDED_REPORT_GENERATION_LIMIT)
                .as("published so the conflict stays visible rather than being silently resolved")
                .isEqualTo(5)
                .isNotEqualTo(TransactionReportJobConfig.REPORT_GENERATION_LIMIT);
        assertThat(TransactionReportJobConfig.TRANSACTION_BACKUP_GENERATION_LIMIT).isEqualTo(5);
        assertThat(TransactionReportJobConfig.FILTERED_TRANSACTION_GENERATION_LIMIT).isEqualTo(5);
    }

    // -----------------------------------------------------------------------------------------------
    // The clock coupling, reached through the posting setup that regenerates the processing timestamp.
    // -----------------------------------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("posting runs as setup, regenerates the processing timestamp from the pinned clock, and "
            + "that date falls inside the window the member bakes in - so the report is not empty")
    void postingSetupLandsInsideTheWindowAndTheReportIsNotEmpty() throws Exception {
        assertThat(PINNED_BUSINESS_DATE.toString())
                .as("THE COUPLING. Posting overwrites the processing timestamp with a clock value, and "
                        + "the member's own bounds are %s through %s. A pinned instant outside them "
                        + "would filter every posted record out and leave an empty report that a weak "
                        + "assertion would accept.", LEGACY_WINDOW_START, LEGACY_WINDOW_END)
                .isGreaterThanOrEqualTo(LEGACY_WINDOW_START)
                .isLessThanOrEqualTo(LEGACY_WINDOW_END);
        assertThat(PINNED_WINDOW_START_DATE.toString())
                .isGreaterThanOrEqualTo(LEGACY_WINDOW_START);
        assertThat(PINNED_WINDOW_END_DATE.toString()).isLessThanOrEqualTo(LEGACY_WINDOW_END);
        assertThat(PINNED_BUSINESS_DATE)
                .isAfterOrEqualTo(PINNED_WINDOW_START_DATE)
                .isBeforeOrEqualTo(PINNED_WINDOW_END_DATE);

        assertThat(this.transactionRepository.count())
                .as("the posted master is seeded empty, which is why posting is the setup step")
                .isZero();

        final List<String> landingRecords = new ArrayList<>();
        for (int ordinal = 1; ordinal <= POSTED_SETUP_RECORDS; ordinal++) {
            landingRecords.add(TestDataFactory.dailyTransaction()
                    .id(identifier(ordinal))
                    .typeCode(TYPE_CODE)
                    .categoryCode(CATEGORY_CODE)
                    .source(SOURCE)
                    .cardNumber(FIRST_CARD)
                    .amount(PLAIN_AMOUNT)
                    .image());
        }
        stageDailyTransactionInput(landingRecords);

        final long descendantsBeforePosting = ProcessHandle.current().descendants().count();
        final JobExecution posting = launchPostingJob();
        assertThat(posting.getStatus())
                .as("the posting setup must complete; the reasons it recorded are %s",
                        posting.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(posting.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactly(PostTransactionJobConfig.STEP_NAME);

        final List<Transaction> posted = this.transactionRepository.findAll();
        assertThat(posted).hasSize(POSTED_SETUP_RECORDS);
        for (final Transaction record : posted) {
            assertThat(record.getTranProcTs().substring(0, PROCESSING_DATE_WIDTH))
                    .as("posting regenerates the processing timestamp from the injected clock, so its "
                            + "leading date is the pinned business date and nothing else")
                    .isEqualTo(PINNED_BUSINESS_DATE.toString());
            assertThat(record.getTranOrigTs())
                    .as("the origination timestamp records when the transaction happened and is copied "
                            + "verbatim, never regenerated")
                    .isEqualTo(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP);
        }

        final JobExecution report = launchReportJob();
        assertThat(report.getStatus())
                .as("the report run must complete; the reasons it recorded are %s",
                        report.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(report.getStepExecutions()).extracting(StepExecution::getStepName)
                .as("three steps ran, in the member's order, and nothing was skipped over")
                .containsExactly(TransactionReportJobConfig.UNLOAD_STEP_NAME,
                        TransactionReportJobConfig.FILTER_AND_ORDER_STEP_NAME,
                        TransactionReportJobConfig.EMIT_STEP_NAME);

        assertThat(ProcessHandle.current().descendants().count())
                .as("the unload step stands in for a cataloged wrapper around a copy utility, and it "
                        + "replaces that utility rather than invoking it: no process is spawned")
                .isEqualTo(descendantsBeforePosting);

        final List<String> unloaded = recordsOf(
                this.reportConfig.backupGeneration(executionIdOf(report)));
        assertThat(unloaded).hasSize(POSTED_SETUP_RECORDS);
        assertEveryRecordMeasures(unloaded, TRANSACTION_RECORD_WIDTH);

        final List<String> reportRecords = reportOf(report);
        assertThat(reportRecords)
                .as("NON-EMPTY FIRST. Every assertion about content below would pass vacuously against "
                        + "an empty report, which is precisely what a mis-pinned clock produces")
                .isNotEmpty();
        assertThat(detailRecords(reportRecords))
                .as("one detail record per posted transaction, counted exactly before anything about "
                        + "their content is asserted")
                .hasSize(POSTED_SETUP_RECORDS);
        assertEveryRecordMeasures(reportRecords, REPORT_RECORD_WIDTH);
    }

    // -----------------------------------------------------------------------------------------------
    // The date window: twenty-one significant bytes, both bounds inclusive, compared as characters.
    // -----------------------------------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("the date-parameter record carries twenty-one significant encoded bytes inside an "
            + "eighty-byte record area, and no fixture file for it exists")
    void theDateParameterRecordIsTwentyOneSignificantEncodedBytes() {
        final JobParameters parameters = pinnedWindowParameters();
        final String startDate = parameters
                .getString(JobParameterValidators.REPORT_START_DATE_KEY);
        final String endDate = parameters.getString(JobParameterValidators.REPORT_END_DATE_KEY);

        assertThat(startDate.getBytes(StandardCharsets.US_ASCII).length)
                .isEqualTo(PROCESSING_DATE_WIDTH);
        assertThat(endDate.getBytes(StandardCharsets.US_ASCII).length)
                .isEqualTo(PROCESSING_DATE_WIDTH);

        final ChunkContext chunkContext = chunkContextFor(parameters);
        final String card = this.reportConfig.dateParameterCard(chunkContext);

        assertThat(card)
                .as("assembled here from the two bounds and one separator, never from the formatter the "
                        + "job delegates to")
                .isEqualTo(startDate + " " + endDate);
        assertThat(card.getBytes(StandardCharsets.US_ASCII).length)
                .as("measured in ENCODED BYTES, because the layout reserves bytes - never in characters")
                .isEqualTo(DATE_PARAMETER_SIGNIFICANT_WIDTH);
        assertThat(DATE_PARAMETER_SIGNIFICANT_WIDTH)
                .as("the significant group is carried inside the declared record area rather than being "
                        + "the whole of it")
                .isLessThan(DATE_PARAMETER_RECORD_AREA_WIDTH);

        final JobParameterValidators.ReportDateWindow window =
                this.jobParameterValidators.parseDateParmRecord(card);
        assertThat(window.startDate()).isEqualTo(startDate);
        assertThat(window.endDate()).isEqualTo(endDate);
        assertThat(this.reportConfig.reportDateWindow(chunkContext)).isEqualTo(window);

        final String recordArea = card
                + " ".repeat(DATE_PARAMETER_RECORD_AREA_WIDTH - DATE_PARAMETER_SIGNIFICANT_WIDTH);
        assertThat(recordArea.getBytes(StandardCharsets.US_ASCII).length)
                .isEqualTo(DATE_PARAMETER_RECORD_AREA_WIDTH);
        assertThat(this.jobParameterValidators.parseDateParmRecord(recordArea))
                .as("the legacy read moves an eighty-byte record area into a twenty-one-byte group, "
                        + "keeping the leading bytes and discarding the remainder without diagnostic")
                .isEqualTo(window);

        assertThat(getClass().getResource("/fixtures/input/dateparm.txt"))
                .as("NO DATE-PARAMETER FIXTURE FILE. The member declares that input as a "
                        + "share-disposition reference to a cataloged dataset, not as in-stream data, so "
                        + "there is no card image to reproduce and the window arrives as parameters")
                .isNull();
    }

    @Test
    @Order(5)
    @DisplayName("both bounds are inclusive, a record one day outside either bound is excluded, and the "
            + "comparison is lexicographic over the hyphenated character image")
    void bothBoundsAreInclusiveAndTheComparisonIsLexicographic() throws Exception {
        final LocalDate beforeWindow = PINNED_WINDOW_START_DATE.minusDays(1L);
        final LocalDate afterWindow = PINNED_WINDOW_END_DATE.plusDays(1L);

        install(List.of(
                fixtureRecord(1, FIRST_CARD, beforeWindow, PLAIN_AMOUNT),
                fixtureRecord(2, FIRST_CARD, PINNED_WINDOW_START_DATE, PLAIN_AMOUNT),
                fixtureRecord(3, FIRST_CARD, INSIDE_WINDOW, PLAIN_AMOUNT),
                fixtureRecord(4, FIRST_CARD, PINNED_WINDOW_END_DATE, PLAIN_AMOUNT),
                fixtureRecord(5, FIRST_CARD, afterWindow, PLAIN_AMOUNT)));

        final JobExecution execution = launchReportJob();
        assertThat(execution.getStatus())
                .as("the reasons this run recorded are %s", execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        final List<String> filtered = filteredOf(execution);
        assertThat(filtered)
                .as("the filtered generation must not be empty, or the inclusion assertions below "
                        + "would hold over nothing")
                .isNotEmpty();
        assertEveryRecordMeasures(filtered, TRANSACTION_RECORD_WIDTH);

        assertThat(filtered).extracting(TransactionReportJobConfigIT::processingDateOf)
                .as("BOTH BOUNDS ARE INCLUSIVE: the record ON the lower bound and the record ON the "
                        + "upper bound both survive, and the two records one day outside either bound "
                        + "do not")
                .containsExactlyInAnyOrder(PINNED_WINDOW_START_DATE.toString(),
                        INSIDE_WINDOW.toString(), PINNED_WINDOW_END_DATE.toString())
                .doesNotContain(beforeWindow.toString(), afterWindow.toString());

        assertThat(filtered).extracting(TransactionReportJobConfigIT::identifierOf)
                .containsExactlyInAnyOrder(identifier(2), identifier(3), identifier(4));

        final String lowerBound = PINNED_WINDOW_START_DATE.toString();
        final String upperBound = PINNED_WINDOW_END_DATE.toString();
        assertThat(lowerBound.compareTo(upperBound))
                .as("THE HYPHENATED FORM IS LOAD BEARING: it is exactly the form in which "
                        + "lexicographic order coincides with chronological order, which is how the "
                        + "legacy filtered dates without ever converting one")
                .isNegative();
        assertThat(beforeWindow.toString().compareTo(lowerBound)).isNegative();
        assertThat(afterWindow.toString().compareTo(upperBound)).isPositive();
        assertThat(lowerBound).matches("\\d{4}-\\d{2}-\\d{2}");

        final List<String> report = reportOf(execution);
        assertThat(detailRecords(report))
                .as("THE SORT-STAGE FILTER AND THE REPORT-STAGE FILTER ANSWER TO ONE WINDOW: the report "
                        + "carries exactly one detail record per surviving record of the filtered "
                        + "generation, so the two cannot have diverged")
                .hasSize(filtered.size());
    }

    // -----------------------------------------------------------------------------------------------
    // The 133-byte record, and the pagination arithmetic derived from the measured increments.
    // -----------------------------------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("every report record is exactly 133 encoded bytes and equals, byte for byte, a record "
            + "assembled independently of the code that produced it")
    void everyReportRecordIsExactlyTheDeclaredWidth() throws Exception {
        install(plainPaginationFixture());

        final JobExecution execution = launchReportJob();
        assertThat(execution.getStatus())
                .as("the reasons this run recorded are %s", execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        final List<String> report = reportOf(execution);
        assertThat(report).isNotEmpty();
        assertThat(detailRecords(report)).hasSize(PLAIN_RECORDS);
        assertEveryRecordMeasures(report, REPORT_RECORD_WIDTH);

        final String startDate = PINNED_WINDOW_START_DATE.toString();
        final String endDate = PINNED_WINDOW_END_DATE.toString();
        final List<String> expectedBlock = expectedHeaderBlock(startDate, endDate);
        for (int index = 0; index < expectedBlock.size(); index++) {
            assertThat(report.get(index).getBytes(StandardCharsets.US_ASCII))
                    .as("header block record %d, compared as a byte array and never trimmed",
                            Integer.valueOf(index))
                    .isEqualTo(expectedBlock.get(index).getBytes(StandardCharsets.US_ASCII));
        }
        assertThat(report.get(HEADER_BLOCK_RECORDS).getBytes(StandardCharsets.US_ASCII))
                .as("the first detail record, assembled field by field here")
                .isEqualTo(expectedDetailRecord(identifier(1), FIRST_ACCOUNT, PLAIN_AMOUNT)
                        .getBytes(StandardCharsets.US_ASCII));

        final BigDecimal firstPageTotal =
                PLAIN_AMOUNT.multiply(new BigDecimal(FIRST_PAGE_DETAIL_RECORDS));
        assertThat(recordsLabelled(report, PAGE_TOTAL_LABEL).get(0)
                .getBytes(StandardCharsets.US_ASCII))
                .as("the first page total, rendered through the always-signed total mask this class "
                        + "assembles rather than borrows")
                .isEqualTo(expectedPageTotalRecord(firstPageTotal)
                        .getBytes(StandardCharsets.US_ASCII));

        assertThat(report.get(report.size() - 1).getBytes(StandardCharsets.US_ASCII))
                .as("the grand total is the last record of the report")
                .isEqualTo(expectedGrandTotalRecord(expectedPlainGrandTotal())
                        .getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    @Order(7)
    @DisplayName("the first page carries sixteen detail records and every later page fourteen, which is "
            + "what the measured per-block increments give")
    void paginationCarriesSixteenThenFourteenDetailRecords() throws Exception {
        install(plainPaginationFixture());

        final JobExecution execution = launchReportJob();
        assertThat(execution.getStatus())
                .as("the reasons this run recorded are %s", execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        final List<String> report = reportOf(execution);
        assertThat(report).isNotEmpty();
        assertThat(detailRecords(report)).hasSize(PLAIN_RECORDS);

        assertThat(detailRecordsPerPage(report))
                .as("zero, then a header block of %d, leaves the counter at %d; detail records then "
                        + "carry it to the page bound of %d, which is %d of them. The bound then fires, "
                        + "a page-total block adds two and a header block four, so the next page starts "
                        + "at twenty-six and runs to forty - %d detail records",
                        Integer.valueOf(HEADER_BLOCK_RECORDS), Integer.valueOf(HEADER_BLOCK_RECORDS),
                        Integer.valueOf(PAGE_BOUND), Integer.valueOf(FIRST_PAGE_DETAIL_RECORDS),
                        Integer.valueOf(STEADY_STATE_DETAIL_RECORDS))
                .containsExactly(Integer.valueOf(FIRST_PAGE_DETAIL_RECORDS),
                        Integer.valueOf(STEADY_STATE_DETAIL_RECORDS));

        assertThat(recordsLabelled(report, NAME_HEADER_SHORT_NAME))
                .as("one header block per page")
                .hasSize(2);
        assertThat(recordsLabelled(report, PAGE_TOTAL_LABEL))
                .as("one page-total block per page: the bound fires once inside the run and once more "
                        + "on the end-of-file path")
                .hasSize(2);
        assertThat(recordsLabelled(report, ACCOUNT_TOTAL_LABEL))
                .as("one card throughout, so the card-number break never fires")
                .isEmpty();
        assertThat(recordsLabelled(report, GRAND_TOTAL_LABEL)).hasSize(1);

        assertThat(report)
                .as("every written record is accounted for: %d header-block records, %d detail records, "
                        + "%d page-total blocks of two records each and one grand total",
                        Integer.valueOf(HEADER_BLOCK_RECORDS), Integer.valueOf(PLAIN_RECORDS),
                        Integer.valueOf(2))
                .hasSize(2 * HEADER_BLOCK_RECORDS + PLAIN_RECORDS + 2 * PAGE_TOTAL_BLOCK_RECORDS + 1);

        assertHeaderBlockAt(report, 0);
        final int pageBreakIndex = HEADER_BLOCK_RECORDS + FIRST_PAGE_DETAIL_RECORDS;
        assertThat(report.get(pageBreakIndex)).startsWith(PAGE_TOTAL_LABEL);
        assertThat(report.get(pageBreakIndex + 1))
                .as("a page-total block writes its total and then the rule record: two records, two "
                        + "counter advances")
                .isEqualTo(expectedRuleRecord());
        assertHeaderBlockAt(report, pageBreakIndex + PAGE_TOTAL_BLOCK_RECORDS);
    }

    @Test
    @Order(8)
    @DisplayName("the page break is genuinely MISSED when an account-total block straddles the page "
            + "bound, and it still fires when the same block lands exactly on it")
    void theMissedPageBreakIsReproducedAndThePhaseSensitivityIsProved() throws Exception {
        install(twoCardFixture(RECORDS_BEFORE_STRADDLING_BOUNDARY));

        final JobExecution straddling = launchReportJob();
        assertThat(straddling.getStatus())
                .as("the reasons this run recorded are %s", straddling.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        final List<String> report = reportOf(straddling);
        assertThat(report).isNotEmpty();
        final int totalRecords = RECORDS_BEFORE_STRADDLING_BOUNDARY + SECOND_CARD_RECORDS;
        assertThat(detailRecords(report)).hasSize(totalRecords);
        assertEveryRecordMeasures(report, REPORT_RECORD_WIDTH);

        assertThat(recordsLabelled(report, NAME_HEADER_SHORT_NAME))
                .as("THE MISSED PAGE BREAK, PRESERVED. The counter stood at %d when the account "
                        + "boundary arrived; the account-total block advanced it by %d, to %d, so the "
                        + "modulo test never observed the bound of %d and no second page was opened. "
                        + "One header block for a report that ran well past one page.",
                        Integer.valueOf(HEADER_BLOCK_RECORDS + RECORDS_BEFORE_STRADDLING_BOUNDARY),
                        Integer.valueOf(ACCOUNT_TOTAL_BLOCK_RECORDS),
                        Integer.valueOf(HEADER_BLOCK_RECORDS + RECORDS_BEFORE_STRADDLING_BOUNDARY
                                + ACCOUNT_TOTAL_BLOCK_RECORDS),
                        Integer.valueOf(PAGE_BOUND))
                .hasSize(1);

        final int accountBreakIndex = HEADER_BLOCK_RECORDS + RECORDS_BEFORE_STRADDLING_BOUNDARY;
        assertThat(report.get(accountBreakIndex)).startsWith(ACCOUNT_TOTAL_LABEL);
        assertThat(report.get(accountBreakIndex + 1))
                .as("an account-total block writes its total and then the rule record: two records, "
                        + "two counter advances - and that pair is what straddles the bound")
                .isEqualTo(expectedRuleRecord());
        assertThat(report.get(accountBreakIndex + ACCOUNT_TOTAL_BLOCK_RECORDS))
                .as("the record immediately after the account-total block is a DETAIL record, not a "
                        + "fresh header - which is the missed break made visible")
                .startsWith(IDENTIFIER_PREFIX);
        assertThat(detailRecordsPerPage(report))
                .as("one page, running long by exactly the records the missed break should have moved")
                .containsExactly(Integer.valueOf(totalRecords));

        // The contrasting phase needs a master holding only its own fixture, so the shared server is put
        // back to the state the reset leaves it in before the second fixture is installed.
        restoreSeededState();
        clearStagedGenerations();
        install(twoCardFixture(RECORDS_BEFORE_LANDING_BOUNDARY));

        final JobExecution landing = launchReportJob();
        assertThat(landing.getStatus())
                .as("the reasons this run recorded are %s", landing.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        final List<String> secondReport = reportOf(landing);
        assertThat(detailRecords(secondReport))
                .hasSize(RECORDS_BEFORE_LANDING_BOUNDARY + SECOND_CARD_RECORDS);
        assertThat(recordsLabelled(secondReport, NAME_HEADER_SHORT_NAME))
                .as("THE CONTRAST. One record fewer before the boundary leaves the counter at %d, so "
                        + "the same account-total block lands exactly ON the bound of %d, the modulo "
                        + "test observes it, and a second page IS opened. The pair is what makes the "
                        + "omission above a measured behaviour rather than an accident of one fixture.",
                        Integer.valueOf(HEADER_BLOCK_RECORDS + RECORDS_BEFORE_LANDING_BOUNDARY),
                        Integer.valueOf(PAGE_BOUND))
                .hasSize(2);

        final int landingBreakIndex = HEADER_BLOCK_RECORDS + RECORDS_BEFORE_LANDING_BOUNDARY;
        assertThat(secondReport.get(landingBreakIndex)).startsWith(ACCOUNT_TOTAL_LABEL);
        assertThat(secondReport.get(landingBreakIndex + ACCOUNT_TOTAL_BLOCK_RECORDS))
                .as("the page-total block the fired break writes sits immediately after the "
                        + "account-total block")
                .startsWith(PAGE_TOTAL_LABEL);
        assertHeaderBlockAt(secondReport,
                landingBreakIndex + ACCOUNT_TOTAL_BLOCK_RECORDS + PAGE_TOTAL_BLOCK_RECORDS);
    }

    @Test
    @Order(9)
    @DisplayName("the grand total is fed ONLY by page totals, the last amount is counted twice, and no "
            + "account-total record is written at end of file")
    void theAccumulationChainAndTheEndOfFileDoubleCount() throws Exception {
        install(twoCardFixture(RECORDS_BEFORE_STRADDLING_BOUNDARY));

        final JobExecution execution = launchReportJob();
        assertThat(execution.getStatus())
                .as("the reasons this run recorded are %s", execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        final List<String> report = reportOf(execution);
        assertThat(report).isNotEmpty();
        final List<String> details = detailRecords(report);
        assertThat(details)
                .hasSize(RECORDS_BEFORE_STRADDLING_BOUNDARY + SECOND_CARD_RECORDS);

        final List<String> pageTotals = recordsLabelled(report, PAGE_TOTAL_LABEL);
        final List<String> accountTotals = recordsLabelled(report, ACCOUNT_TOTAL_LABEL);
        final List<String> grandTotals = recordsLabelled(report, GRAND_TOTAL_LABEL);
        assertThat(grandTotals).hasSize(1);

        final BigDecimal grandTotal = reportedAmount(grandTotals.get(0));
        assertThat(grandTotal)
                .as("AMOUNTS REACH THE GRAND TOTAL ONLY VIA PAGE TOTALS. A page-total block folds its "
                        + "total into the grand total; an account-total block zeroes only its own "
                        + "accumulator and never touches it.")
                .isEqualByComparingTo(sumOf(pageTotals));
        assertThat(grandTotal)
                .as("an implementation that rolled account totals up as well would have reported %s, "
                        + "which is plausible, wrong, and invisible to a weaker assertion",
                        sumOf(pageTotals).add(sumOf(accountTotals)))
                .isNotEqualByComparingTo(sumOf(pageTotals).add(sumOf(accountTotals)));

        final BigDecimal detailSum = sumOf(details);
        final BigDecimal lastDetailAmount = reportedAmount(details.get(details.size() - 1));
        assertThat(grandTotal.subtract(detailSum))
                .as("THE END-OF-FILE DOUBLE COUNT, PRESERVED. The read leaves the record area holding "
                        + "the previous record, so the end-of-file arm re-adds the LAST amount to the "
                        + "page and account totals before the final page total is written. The grand "
                        + "total therefore exceeds the sum of the detail records by exactly that one "
                        + "amount.")
                .isEqualByComparingTo(lastDetailAmount);
        assertThat(grandTotal).isEqualByComparingTo(detailSum.add(lastDetailAmount));

        final BigDecimal firstCardSubtotal = FIRST_CARD_AMOUNT
                .multiply(new BigDecimal(RECORDS_BEFORE_STRADDLING_BOUNDARY));
        assertThat(accountTotals)
                .as("NO ACCOUNT-TOTAL RECORD IS WRITTEN AT END OF FILE: the only one that appears is "
                        + "the one the card-number break wrote inside the run")
                .hasSize(1);
        assertThat(reportedAmount(accountTotals.get(0)))
                .isEqualByComparingTo(firstCardSubtotal);
        assertThat(accountTotals.get(0).getBytes(StandardCharsets.US_ASCII))
                .isEqualTo(expectedAccountTotalRecord(firstCardSubtotal)
                        .getBytes(StandardCharsets.US_ASCII));

        final BigDecimal finalCardSubtotal = SECOND_CARD_AMOUNT
                .multiply(new BigDecimal(SECOND_CARD_RECORDS + 1));
        assertThat(reportedAmount(accountTotals.get(0)))
                .as("the last card's accumulated subtotal of %s never reaches the report, because the "
                        + "end-of-file arm writes the page total and the grand total and no account "
                        + "total at all", finalCardSubtotal)
                .isNotEqualByComparingTo(finalCardSubtotal);
    }

    // -----------------------------------------------------------------------------------------------
    // The ordering: one key, card number ascending, typed zoned decimal - and job-local by necessity.
    // -----------------------------------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("the ordering is card number ascending under the ZONED-DECIMAL typing this job declares, "
            + "which is not the character typing the statement job declares for the very same bytes")
    void theOrderingAppliesThisJobsOwnZonedDecimalTyping() throws Exception {
        final String positivelyOverpunched = signedCardImage(OVERPUNCH_BASE_CARD, false);
        final String negativelyOverpunched = signedCardImage(OVERPUNCH_BASE_CARD, true);
        addResolvableCard(positivelyOverpunched);
        addResolvableCard(negativelyOverpunched);

        install(List.of(
                fixtureRecord(1, FIRST_CARD, INSIDE_WINDOW, PLAIN_AMOUNT),
                fixtureRecord(2, positivelyOverpunched, INSIDE_WINDOW, PLAIN_AMOUNT),
                fixtureRecord(3, negativelyOverpunched, INSIDE_WINDOW, PLAIN_AMOUNT)));

        final JobExecution execution = launchReportJob();
        assertThat(execution.getStatus())
                .as("the reasons this run recorded are %s", execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        final List<String> filtered = filteredOf(execution);
        assertThat(filtered).hasSize(3);
        final List<String> orderedCardNumbers = new ArrayList<>();
        for (final String image : filtered) {
            orderedCardNumbers.add(cardNumberOf(image));
        }

        assertThat(orderedCardNumbers)
                .as("the final byte of a zoned field carries both a digit and a sign, so the negatively "
                        + "overpunched image is the smallest of the three as a NUMBER while being the "
                        + "largest of the three as CHARACTERS")
                .containsExactly(negativelyOverpunched, FIRST_CARD, positivelyOverpunched);

        final List<String> characterOrder = new ArrayList<>(orderedCardNumbers);
        characterOrder.sort(null);
        assertThat(orderedCardNumbers)
                .as("THE COMPARATOR MUST STAY PRIVATE TO THIS JOB. Byte position %d is typed zoned "
                        + "decimal by this job's specification and CHARACTER by the statement job's, so "
                        + "one shared comparator would apply one job's typing to the other job's data. "
                        + "Were the two orders the same the assertion above would be vacuous and a "
                        + "shared comparator would pass unnoticed.", Integer.valueOf(CARD_NUMBER_POSITION))
                .isNotEqualTo(characterOrder);

        final List<String> unloadOrder = new ArrayList<>();
        for (final Transaction record
                : this.transactionRepository.findAll(Sort.by(Sort.Direction.ASC, "tranId"))) {
            unloadOrder.add(record.getTranCardNum());
        }
        assertThat(unloadOrder)
                .as("the master is unloaded in ascending cluster-key sequence, and the ordering step "
                        + "then imposes its own key - so the two sequences must differ here, or the "
                        + "ordering would be unobservable")
                .isNotEqualTo(orderedCardNumbers);

        assertThat(detailRecords(reportOf(execution)))
                .as("every one of the three cards resolved through the cross-reference, so no record "
                        + "was lost and none abended")
                .hasSize(3);
    }

    @Test
    @Order(11)
    @DisplayName("records sharing a card number keep the sequence the unload delivered them in, because "
            + "the specification declares no secondary key")
    void tiesKeepTheUnloadSequenceBecauseThereIsNoSecondaryKey() throws Exception {
        install(List.of(
                fixtureRecord(1, FIRST_CARD, PINNED_WINDOW_END_DATE, PLAIN_AMOUNT),
                fixtureRecord(2, FIRST_CARD, INSIDE_WINDOW, PLAIN_AMOUNT),
                fixtureRecord(3, FIRST_CARD, PINNED_WINDOW_START_DATE, PLAIN_AMOUNT)));

        final JobExecution execution = launchReportJob();
        assertThat(execution.getStatus())
                .as("the reasons this run recorded are %s", execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        final List<String> filtered = filteredOf(execution);
        assertThat(filtered).hasSize(3);
        assertThat(filtered).extracting(TransactionReportJobConfigIT::identifierOf)
                .as("the three processing dates descend, so a secondary key on the processing date - "
                        + "which the specification does NOT declare - would have reversed this sequence")
                .containsExactly(identifier(1), identifier(2), identifier(3));
        assertThat(filtered).extracting(TransactionReportJobConfigIT::processingDateOf)
                .containsExactly(PINNED_WINDOW_END_DATE.toString(), INSIDE_WINDOW.toString(),
                        PINNED_WINDOW_START_DATE.toString());
    }

    // -----------------------------------------------------------------------------------------------
    // The three reference lookups: every one abends, and none of them skips.
    // -----------------------------------------------------------------------------------------------

    @Test
    @Order(12)
    @DisplayName("a card the cross-reference cannot resolve abends the step, with the raw two-byte status "
            + "logged before the failure propagates")
    void anUnresolvableCardAbends() throws Exception {
        final long crossReferencesBefore = crossReferenceRowCount();
        addUnreferencedCard(UNREFERENCED_CARD);
        assertThat(crossReferenceRowCount())
                .as("the card exists so the master's own constraint is satisfied; only its "
                        + "cross-reference row is absent, which is how the invalid-key condition of the "
                        + "cross-reference read is reached")
                .isEqualTo(crossReferencesBefore);

        install(List.of(fixtureRecord(1, UNREFERENCED_CARD, INSIDE_WINDOW, PLAIN_AMOUNT)));

        assertLookupAbend(DIAGNOSTIC_INVALID_CARD);
    }

    @Test
    @Order(13)
    @DisplayName("a transaction type the reference table cannot resolve abends the step on the same terms")
    void anUnresolvableTransactionTypeAbends() throws Exception {
        install(List.of(fixtureRecord(1, FIRST_CARD, INSIDE_WINDOW, PLAIN_AMOUNT)));
        removeTransactionType(TYPE_CODE);

        assertLookupAbend(DIAGNOSTIC_INVALID_TYPE);
    }

    @Test
    @Order(14)
    @DisplayName("a transaction category the reference table cannot resolve abends the step on the same "
            + "terms, after the type lookup that precedes it has succeeded")
    void anUnresolvableTransactionCategoryAbends() throws Exception {
        install(List.of(fixtureRecord(1, FIRST_CARD, INSIDE_WINDOW, PLAIN_AMOUNT)));
        removeTransactionCategory(TYPE_CODE, CATEGORY_CODE);

        assertLookupAbend(DIAGNOSTIC_INVALID_CATEGORY);
    }

    /**
     * Runs the report job over a fixture whose reference row is missing and asserts the whole of the
     * legacy failure contract: the step terminates, the exception names the program and the diagnostic, the
     * raw two-byte status reaches the log <strong>before</strong> the abend, and nothing was skipped.
     *
     * <p>There is no reject path and no skip-and-continue anywhere in the report program - the reject
     * record belongs exclusively to the posting job - so a completed run here would be a defect however
     * plausible its output looked.
     *
     * @param diagnostic the source's own diagnostic for the failing lookup
     * @throws Exception if the job cannot be resolved or launched
     */
    private void assertLookupAbend(final String diagnostic) throws Exception {
        final JobExecution execution = launchReportJob();

        assertThat(execution.getStatus())
                .as("all three reference lookups abend on an invalid key, so the step terminates rather "
                        + "than rejecting the record and carrying on")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions())
                .filteredOn(step -> TransactionReportJobConfig.EMIT_STEP_NAME
                        .equals(step.getStepName()))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.getStatus()).isEqualTo(BatchStatus.FAILED);
                    assertThat(step.getSkipCount())
                            .as("NO SKIP POLICY AND NO FAULT-TOLERANT STEP: a skipped record would be a "
                                    + "record the legacy report never omitted")
                            .isZero();
                    assertThat(step.getFilterCount()).isZero();
                });

        assertThat(abendException(execution))
                .as("the failure the run recorded must be the abend itself, not a wrapper that lost it")
                .isNotNull()
                .satisfies(abend -> {
                    assertThat(abend.culprit()).isEqualTo(LEGACY_PROGRAM);
                    assertThat(abend.reason()).isEqualTo(diagnostic);
                    assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                });

        final List<String> diagnostics = recordedDiagnostics();
        final int keyIndex = indexOfDiagnostic(diagnostics, diagnostic);
        final int statusIndex = indexOfDiagnostic(diagnostics, STATUS_DIAGNOSTIC_PREFIX);
        final int abendIndex = indexOfDiagnostic(diagnostics, ABEND_DIAGNOSTIC_PREFIX);
        assertThat(keyIndex)
                .as("DIAGNOSTIC FIRST, and the offending key opens it. The recorded diagnostics were %s",
                        diagnostics)
                .isNotNegative()
                .isLessThan(statusIndex);
        assertThat(statusIndex)
                .as("DIAGNOSTIC FIRST: the offending key and then the raw two-byte status, and only then "
                        + "the abend. The recorded diagnostics were %s", diagnostics)
                .isNotNegative()
                .isLessThan(abendIndex);
        assertThat(diagnostics.get(statusIndex))
                .as("the raw status the source moves into its status field at all three lookup sites")
                .contains(STATUS_RECORD_NOT_FOUND);
        assertThat(diagnostics)
                .as("the only statuses compared anywhere in the estate are success, end of file and "
                        + "record-not-found, so no diagnostic here may name any other")
                .noneMatch(line -> line.contains("fileStatus=22"))
                .noneMatch(line -> line.contains("fileStatus=35"));
    }

    /**
     * The abend the execution recorded, found by walking the cause chain of every failure it carries.
     *
     * @param execution the failed execution
     * @return the abend, or {@code null} when the execution recorded none
     */
    private static AbendException abendException(final JobExecution execution) {
        for (final Throwable failure : execution.getAllFailureExceptions()) {
            for (Throwable candidate = failure; candidate != null;
                    candidate = candidate.getCause()) {
                if (candidate instanceof AbendException abend) {
                    return abend;
                }
                if (candidate.getCause() == candidate) {
                    break;
                }
            }
        }
        return null;
    }

    /**
     * The diagnostics the recorder captured, as formatted messages in emission order.
     *
     * @return the captured messages
     */
    private List<String> recordedDiagnostics() {
        final List<String> messages = new ArrayList<>();
        for (final ILoggingEvent event : this.abendRecorder.list) {
            messages.add(event.getFormattedMessage());
        }
        return List.copyOf(messages);
    }

    /**
     * Index of the first captured diagnostic opening with a prefix.
     *
     * @param diagnostics the captured messages
     * @param prefix the prefix to find
     * @return the index, or {@code -1} when no diagnostic carries it
     */
    private static int indexOfDiagnostic(final List<String> diagnostics, final String prefix) {
        for (int index = 0; index < diagnostics.size(); index++) {
            if (diagnostics.get(index).startsWith(prefix)) {
                return index;
            }
        }
        return -1;
    }

    // -----------------------------------------------------------------------------------------------
    // The output destination, and the lifecycle instrumentation.
    // -----------------------------------------------------------------------------------------------

    @Test
    @Order(15)
    @DisplayName("the report destination is resolved from configuration by logical name, is a distinct "
            + "generation per execution, and this job prunes nothing")
    void theDestinationIsAConfiguredLogicalNameAndADistinctGenerationPerExecution() throws Exception {
        install(List.of(fixtureRecord(1, FIRST_CARD, INSIDE_WINDOW, PLAIN_AMOUNT)));

        final JobExecution first = launchReportJob();
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        final Path firstGeneration = this.reportConfig.reportGeneration(executionIdOf(first));

        final JobExecution second = launchReportJob();
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        final Path secondGeneration = this.reportConfig.reportGeneration(executionIdOf(second));

        assertThat(firstGeneration.getParent())
                .as("NO HARDCODED PATH: the destination sits under the directory this specification "
                        + "published as configuration, so the job resolved it rather than carrying one")
                .isEqualTo(STAGING_DIRECTORY);
        assertThat(secondGeneration.getParent()).isEqualTo(STAGING_DIRECTORY);

        assertThat(firstGeneration.getFileName())
                .as("resolved BY LOGICAL NAME: the generation is named for the logical base the member "
                        + "declares as its report destination, and carries the generation-version suffix "
                        + "a resolved relative generation carries")
                .asString()
                .startsWith(REPORT_LOGICAL_BASE)
                .endsWith(GENERATION_VERSION_SUFFIX);
        assertThat(secondGeneration.getFileName())
                .asString()
                .startsWith(REPORT_LOGICAL_BASE)
                .endsWith(GENERATION_VERSION_SUFFIX);

        assertThat(secondGeneration)
                .as("A DISTINCT GENERATION PER EXECUTION, which is what the relative generation the "
                        + "member names resolves to once the group is no longer a mainframe catalog entry")
                .isNotEqualTo(firstGeneration);

        verify(this.stagingArea).publish(firstGeneration);
        verify(this.stagingArea).publish(secondGeneration);

        assertThat(Files.exists(firstGeneration))
                .as("NO RETENTION LOGIC HERE: the later execution must not remove the earlier "
                        + "generation. The two conflicting declarations of the group's limit - five in one "
                        + "member and ten in another - are resolved to ten as a documented configuration "
                        + "figure, never as pruning this job performs")
                .isTrue();
        assertThat(Files.exists(secondGeneration)).isTrue();
    }

    @Test
    @Order(16)
    @DisplayName("the three program lifecycles are instrumented, one timer per legacy step name, carrying "
            + "the shared lifecycle description")
    void everyProgramLifecycleIsInstrumented() throws Exception {
        install(List.of(fixtureRecord(1, FIRST_CARD, INSIDE_WINDOW, PLAIN_AMOUNT)));

        assertThat(launchReportJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        for (final String legacyStepName
                : List.of(LEGACY_UNLOAD_STEP, LEGACY_SORT_STEP, LEGACY_REPORT_STEP)) {
            final Timer timer = this.meterRegistry.find(LIFECYCLE_TIMER)
                    .tag(TIMER_STEP_TAG, legacyStepName)
                    .tag(TIMER_OUTCOME_TAG, TIMER_OUTCOME_COMPLETED)
                    .timer();
            assertThat(timer)
                    .as("a completed lifecycle of legacy step %s must be recorded, because the throughput "
                            + "and memory figures the migration is asked to establish can only come from "
                            + "instrumentation that exists", legacyStepName)
                    .isNotNull();
            assertThat(timer.count())
                    .as("presence, not a performance figure: at least one lifecycle of %s was recorded",
                            legacyStepName)
                    .isPositive();
            assertThat(timer.getId().getDescription())
                    .as("shape, not a threshold: the description a dashboard binds to")
                    .isEqualTo(LIFECYCLE_TIMER_DESCRIPTION);
        }

        final List<String> instrumentedSteps = new ArrayList<>();
        for (final Timer timer : this.meterRegistry.find(LIFECYCLE_TIMER).timers()) {
            final String legacyStepName = timer.getId().getTag(TIMER_STEP_TAG);
            if (!instrumentedSteps.contains(legacyStepName)) {
                instrumentedSteps.add(legacyStepName);
            }
        }
        assertThat(instrumentedSteps)
                .as("exactly three program lifecycles, under the three names the cataloged form gives "
                        + "them - which is also the only place the duplicated job-member step name is "
                        + "disambiguated")
                .containsExactlyInAnyOrder(LEGACY_UNLOAD_STEP, LEGACY_SORT_STEP, LEGACY_REPORT_STEP);
    }

    // -----------------------------------------------------------------------------------------------
    // The context under test.
    // -----------------------------------------------------------------------------------------------

    /**
     * The slice this specification runs the job in: the job configuration itself, the posting job that
     * populates the master it reads, the shared batch infrastructure, the report stage and every
     * collaborator each of those requires - and nothing else.
     *
     * <p>The generation store and its advisory publication lock are the production ones rather than
     * direct-run stand-ins, because this slice has the real PostgreSQL server the lock needs and a
     * stand-in would leave the serialisation of publication unproven. Only the two edges that would
     * reach a remote service are replaced: the object store and the notification topic. Those same
     * operations are proved against LocalStack elsewhere in the module, so nothing is left unexercised.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({TransactionReportJobConfig.class, PostTransactionJobConfig.class, BatchConfig.class,
            JobParameterValidators.class, DateValidationService.class, TransactionReportService.class,
            TransactionPostingService.class, PostingRecordTransactionBoundary.class,
            RecordWriter.class, AbendService.class, FixedWidthFlatFileReaderFactory.class,
            StagedGenerationStore.class, AdvisoryGenerationPublicationLock.class})
    @EnableConfigurationProperties(AwsProperties.class)
    @EnableJpaRepositories(basePackageClasses = TransactionRepository.class)
    @EntityScan(basePackageClasses = Transaction.class)
    static class JobContext {

        /** Creates the configuration. */
        JobContext() {
            // Intentionally empty: every bean this slice adds is declared below.
        }

        /**
         * The clock every batch timestamp is read from, pinned rather than live.
         *
         * <p>This is the single most consequential bean in the slice. The posting job overwrites each
         * record's processing timestamp with a freshly generated value read from here, and the report
         * filters on the first ten characters of that value against a window whose bounds the legacy sort
         * symbols bake in. A live clock would put every posted record outside that window, the report
         * would come out empty, and every assertion weaker than a positive record count would pass on an
         * empty artifact. Pinning the clock is what makes the window observable at all.
         *
         * @return the clock frozen at the instant the shared base class pins
         */
        @Bean
        Clock fixedClock() {
            return FIXED_CLOCK;
        }

        /**
         * The object-store staging boundary, stood in for so that publication can be observed, and made to
         * refuse a generation that is not on disk.
         *
         * <p>Refusing an absent file is the faithful behaviour rather than a convenience: a step that
         * published a path before completing the working file would be publishing something no reader
         * could read, and a permissive stand-in would let that defect through silently.
         *
         * @return a staging boundary that accepts a completed generation and refuses an absent one
         */
        @Bean
        BatchStagingArea stagingArea() {
            final BatchStagingArea stagingArea = mock(BatchStagingArea.class);
            doAnswer(invocation -> {
                final Path published = invocation.getArgument(0);
                if (!Files.exists(published)) {
                    throw new UncheckedIOException(
                            "staged batch file could not be read for publication: " + published,
                            new NoSuchFileException(published.toString()));
                }
                return null;
            }).when(stagingArea).publish(any(Path.class));
            return stagingArea;
        }

        /**
         * Keeps this PostgreSQL-focused slice deterministic at the object-store edge. The generation store
         * itself and the job-boundary listener's publication path stay real; only the remote service is
         * replaced, and it reports no older retained generations so retention counting has a defined base.
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
         * Prevents an unrelated notification endpoint from becoming a prerequisite of a job specification.
         *
         * @return a notification edge that accepts every publication
         */
        @Bean
        SnsOperations notifications() {
            return mock(SnsOperations.class);
        }
    }
}
