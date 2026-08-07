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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.domain.Account;
import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.DailyTransactionRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.BatchJobCatalog;
import com.carddemo.service.DailyTransactionReadService;
import com.carddemo.service.DailyTransactionReadService.DailyTransactionReadResult;
import com.carddemo.service.DailyTransactionReadService.DailyTransactionVerification;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import com.carddemo.util.SensitiveFieldCodec;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.LoggerFactory;

/**
 * The orphan's job, run end to end against a real PostgreSQL 16 server with the delivered migrations
 * applied.
 *
 * <h2>Why this specification exists, and why it may not be reduced</h2>
 *
 * <p>The subject is {@link DailyTransactionReadJobConfig}, the translation of the legacy batch member
 * {@code app/cbl/CBTRN01C.cbl} - 491 source lines, 18 procedure-division paragraphs, six named file
 * resources. <strong>That member is the estate's orphan, proven exhaustively rather than inferred:</strong>
 * a repository-wide search for its program name returns exactly one path, its own source member. No job
 * member under {@code app/jcl}, no cataloged procedure under {@code app/proc} and no entry in
 * {@code app/csd/CARDDEMO.CSD} names it anywhere. Nothing ran it on the mainframe, so nothing runs it
 * here: the migrated job is <em>defined but unwired</em>.
 *
 * <p><strong>The consequence is that this file is the sole coverage source for its production tier.</strong>
 * Because the job is absent from every pipeline and no other integration specification launches it, the 18
 * paragraph units and 491 source lines it stands for are covered here or nowhere. There is no fallback, so
 * nothing in this file may be skipped, narrowed or deferred - and nothing in it is conditionally ignored.
 *
 * <h2>The 18 paragraph units, in the order they physically appear in the source</h2>
 *
 * <p>Deliberately <em>not</em> the numeric order of their labels: the mainline and the three processing
 * paragraphs come first, then the six open routines, then the six close routines, then the two diagnostic
 * routines. Preserving that physical order is what lets a reader walk a traceability matrix top to bottom
 * against the member, and {@link TheEighteenParagraphUnitsInPhysicalOrder} asserts the ordering, the group
 * cardinalities and the resource sequence rather than trusting them.
 *
 * <table border="1">
 *   <caption>Paragraph inventory of {@code app/cbl/CBTRN01C.cbl}, procedure division from line 154</caption>
 *   <tr><th>#</th><th>Paragraph</th><th>Line</th><th>Group</th></tr>
 *   <tr><td>1</td><td>{@code MAIN-PARA}</td><td>155</td><td>mainline</td></tr>
 *   <tr><td>2</td><td>{@code 1000-DALYTRAN-GET-NEXT}</td><td>202</td><td>processing</td></tr>
 *   <tr><td>3</td><td>{@code 2000-LOOKUP-XREF}</td><td>227</td><td>processing</td></tr>
 *   <tr><td>4</td><td>{@code 3000-READ-ACCOUNT}</td><td>241</td><td>processing</td></tr>
 *   <tr><td>5</td><td>{@code 0000-DALYTRAN-OPEN}</td><td>252</td><td>open</td></tr>
 *   <tr><td>6</td><td>{@code 0100-CUSTFILE-OPEN}</td><td>271</td><td>open</td></tr>
 *   <tr><td>7</td><td>{@code 0200-XREFFILE-OPEN}</td><td>289</td><td>open</td></tr>
 *   <tr><td>8</td><td>{@code 0300-CARDFILE-OPEN}</td><td>307</td><td>open</td></tr>
 *   <tr><td>9</td><td>{@code 0400-ACCTFILE-OPEN}</td><td>325</td><td>open</td></tr>
 *   <tr><td>10</td><td>{@code 0500-TRANFILE-OPEN}</td><td>343</td><td>open</td></tr>
 *   <tr><td>11</td><td>{@code 9000-DALYTRAN-CLOSE}</td><td>361</td><td>close</td></tr>
 *   <tr><td>12</td><td>{@code 9100-CUSTFILE-CLOSE}</td><td>379</td><td>close</td></tr>
 *   <tr><td>13</td><td>{@code 9200-XREFFILE-CLOSE}</td><td>397</td><td>close</td></tr>
 *   <tr><td>14</td><td>{@code 9300-CARDFILE-CLOSE}</td><td>415</td><td>close</td></tr>
 *   <tr><td>15</td><td>{@code 9400-ACCTFILE-CLOSE}</td><td>433</td><td>close</td></tr>
 *   <tr><td>16</td><td>{@code 9500-TRANFILE-CLOSE}</td><td>451</td><td>close</td></tr>
 *   <tr><td>17</td><td>{@code Z-ABEND-PROGRAM}</td><td>469</td><td>diagnostic</td></tr>
 *   <tr><td>18</td><td>{@code Z-DISPLAY-IO-STATUS}</td><td>476</td><td>diagnostic</td></tr>
 * </table>
 *
 * <p>Each of the 18 corresponds to one method of {@link DailyTransactionReadService}, and that class lays
 * its methods out in exactly the physical order above - the abend routine before the status-display
 * routine, matching source lines 469 and 476, even though the abend is what runs second.
 *
 * <p><strong>A naming-generation divergence worth recording.</strong> The two diagnostic routines carry
 * <em>letter-prefixed</em> labels while the other sixteen carry four-digit numeric prefixes; the equivalent
 * diagnostics in the rest of the batch estate are numerically prefixed. The Java target adopts a
 * <em>single</em> convention and preserves both behaviours rather than reproducing two competing naming
 * styles, and this divergence is a decision-log candidate. A matrix row for either diagnostic must be
 * matched against a letter-prefixed source label, not against a numbered sequence that does not exist.
 *
 * <h2>What is asserted about the absence of wiring</h2>
 *
 * <p>Being unwired is a property that has to be asserted, not assumed. This specification proves that the
 * job is registered and launchable by name; that <strong>no execution of it exists before the first launch
 * here</strong> - which matters doubly for a job the legacy system never ran, so the shared configuration's
 * launch-on-start switch is checked as well as the absence of the framework's start-up runner; that it
 * chains to nothing and carries <em>zero</em> failure-ending flow transitions, because the estate holds no
 * condition-code gate for a program it holds no job step for; that its step name is <em>original to this
 * module</em> and duplicates no other job's step-name constant; that its accepted parameter set is
 * genuinely <em>empty</em>, with no date and no mode parameter invented for it; and that execution is
 * strictly sequential.
 *
 * <p><strong>No output dataset is invented.</strong> The member opens all six resources for input, and
 * there is no {@code WRITE} and no {@code REWRITE} in any of its 491 lines, so the translated program has
 * no write path at all: what the pass produces is the diagnostic stream and the terminating status. This
 * specification therefore asserts that the posted-transaction table is still empty after a complete pass,
 * and asserts nothing about an output it must not have.
 *
 * <h2>The six resources, six widths, and the 350-byte trap</h2>
 *
 * <p>The member names six resources at six widths: the daily-transaction input at 350 bytes, the customer
 * file at 500, the card cross-reference at 50 declared with 36 data bytes, the card file at 150, the
 * account file at 300 and the posted-transaction file at 350.
 *
 * <p><strong>The daily-transaction and posted-transaction layouts are byte-for-byte identical at 350
 * bytes</strong> - the same field sequence under a different field-name prefix - <strong>yet they are
 * distinct entities with distinct lifecycles and distinct tables.</strong> A width check alone cannot tell
 * them apart, so every assertion here names the <em>entity</em> and the <em>table</em> and never merely the
 * width, and neither reader is ever substituted for or merged with the other. The two tables are also
 * distinguishable in the live catalogue: {@code daily_transaction} carries <strong>zero foreign keys,
 * deliberately</strong>, precisely so that a landing row naming a card, account or customer that no seeded
 * row backs can exist and be rejected by validation, whereas {@code transaction} carries a foreign key to
 * the card master. That zero-key property belongs to the landing table and not to the posted one.
 *
 * <p>Every width is asserted in <strong>encoded bytes</strong> and nothing is trimmed. The cross-reference
 * comparison uses the delivered ASCII form's 36 bytes with <strong>no filler at all</strong> and is never
 * padded to 50 - the 14 filler bytes belong to the mainframe-encoded data set, which is a different image
 * of the same three fields. The filler contract is measured and <strong>not uniform</strong>: the
 * daily-transaction, account, card and customer fixtures pad with the space character while the
 * cross-reference fixture has no filler to pad, and the two are not unified for tidiness.
 *
 * <h2>Two measured fixture facts that constrain what may be asserted</h2>
 *
 * <p>All 300 delivered daily-transaction records carry a <em>single identical</em> origination timestamp,
 * and their 26-byte processing-timestamp field is <em>entirely blank</em> on every one of them. No
 * assertion here depends on a populated processing timestamp or on a spread of dates in the seeded
 * fixture; where such a record is needed it is <em>separately constructed</em> through the shared test
 * data factory, and no seed script and no fixture file is mutated.
 *
 * <h2>The two-level file-status model, tested as two levels</h2>
 *
 * <p>The legacy tier never branches on a raw two-character status. It normalises the raw code into a
 * coarse result and branches on <em>that</em> - and the coarse variable is referenced <strong>223 times
 * across the estate</strong>, which is the measure of how load-bearing the distinction is. Level one maps
 * the raw code: success to 0, end of file to 16, anything else to 12. Level two branches on the coarse
 * value: 0 continues, 16 raises the terminating flag and stops cleanly, and 12 logs the error literal,
 * logs the raw two-character status and only then abends. The two levels are asserted <em>separately</em>
 * here and never collapsed, because collapsing them turns the normal completion of every run into an
 * abend.
 *
 * <p>The tri-state the batch tier shares is a nested type inside {@code batch/step/AbstractCobolStep} and
 * is declared {@code protected} in a package this specification is neither in nor a subclass of, so it is
 * unreachable from here by design. The model is therefore asserted through the outcomes the pass reports
 * rather than by naming that type - and no top-level status-normaliser, outcome or timestamp-formatter
 * type is referenced, because no such top-level type exists.
 *
 * <p><strong>Only the status values the source actually compares are exercised.</strong> A status census
 * of the estate finds exactly three compared anywhere: success, end of file and record-not-found. Two
 * further codes appear in earlier specification prose - a duplicate-key value and a file-not-found value -
 * and <em>no source member compares either</em>; no assertion here depends on them, and
 * {@link TheTwoLevelFileStatusModel} records that discrepancy by proving both are declared vocabulary that
 * this program never reports.
 *
 * <h2>Emit, then abend</h2>
 *
 * <p>The abend routine at source lines 469 to 473 displays its announcement, clears a timing field, moves
 * its abend code and only then calls the Language Environment abort routine, and every failing site
 * reaching it has already displayed its own literal and the raw status. <strong>Diagnostic first,
 * exception second</strong> is therefore the contract, and it is asserted as an ordering over captured
 * diagnostics rather than as a set of them. The abend code is taken from {@link AbendException}'s own
 * constant and is never restated as a literal here, and the exception's context is asserted to mirror the
 * legacy four-part structure: a 4-byte code, an 8-byte culprit, a 50-byte reason and a 72-byte message,
 * 134 bytes in total.
 *
 * <p><strong>What the abend path deliberately does <em>not</em> do is close anything.</strong> All six
 * resources are opened by the mainline at source lines 157 to 162 and closed by it at 188 to 193, with the
 * end announcement at 195 following the last close - so on a clean pass the announcement is the only
 * observable evidence that all six closes ran, the twelve open and close routines being silent on their
 * success arm exactly as the source is. The abend routine performs no close of its own, and every failing
 * site jumps to it from inside the routine that reported the failure, so the abort ends the run unit where
 * it stands and lines 188 to 195 are never reached. The faithful assertion is therefore the
 * <em>absence</em> of the closes and of the end announcement once the abend fires, and that is what is
 * asserted here; asserting their presence would credit the program with an orderly shutdown it never had.
 *
 * <p>There is <strong>no skip policy, no reject writer and no fault-tolerant step</strong>. The reject
 * path with its four-digit reason code and its 430-byte record belongs exclusively to the posting job;
 * this program has none, and its not-found arms follow <em>its own</em> measured behaviour - a warning and
 * a skip - rather than a behaviour borrowed from the posting program.
 *
 * <h2>Determinism, and what this specification leaves behind</h2>
 *
 * <p>The shared base class owns the container, the migration and the pinned clock; this class declares no
 * container, no dynamic data-source property and no context-discarding annotation, and reads no clock of
 * its own. Every diagnostic goes through the logging facade, the diagnostic log is deliberately
 * <em>not</em> silenced, and no numeric latency, throughput or memory figure is asserted anywhere - the
 * step timer is read for presence and shape only. The one row this specification writes is removed again
 * whatever the outcome, because the server is shared with every other integration specification in the
 * run.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, legacy member {@code app/cbl/CBTRN01C.cbl}. That
 * stamp is a matrix-header provenance string and is deliberately never asserted per member, because it is
 * not universal across the estate. The legacy tree is cited and never transcribed - no program,
 * job-control, screen-map, copybook or resource-definition statement text appears here - and nothing reads
 * that tree at run time: every fixture is read from this module's own test class path. No user-specified
 * rules govern this file; the project's rules document reports that none were provided, so the work is
 * held to the enterprise standards the specification substitutes for them, and where faithful translation
 * and idiomatic Java diverge, faithful wins.
 */
@SpringBootTest(classes = DailyTransactionReadJobConfigIT.JobContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.banner-mode=off", "spring.flyway.enabled=false",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false"})
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@DisplayName("DailyTransactionReadJobConfigIT - the orphan's job: launchable by name, wired into "
        + "nothing, six resources at six widths, two-level status, emit-then-abend")
final class DailyTransactionReadJobConfigIT extends AbstractPostgresIT {

    // =============================================================================================
    // MEASURED CONSTANTS. Every value below was measured from the legacy member, from a delivered
    // fixture or from the migration set, and each is stated once so no two assertions can disagree.
    // =============================================================================================

    /** Source lines of the legacy member, whose coverage exists in this specification and nowhere else. */
    private static final int LEGACY_SOURCE_LINES = 491;

    /** Procedure-division paragraphs of the legacy member, one Java method each. */
    private static final int PARAGRAPH_UNITS = 18;

    /** Estate-wide references to the coarse result the two-level status model branches on. */
    private static final int COARSE_RESULT_REFERENCES = 223;

    /** Class-path location prefix of the delivered sequential fixtures. This module's own resources. */
    private static final String FIXTURES = "classpath:fixtures/input/";

    /** File name of the daily-transaction fixture: 300 records of 350 bytes. */
    private static final String DALYTRAN_FIXTURE_NAME = "dailytran.txt";

    /** File name of the customer fixture: 50 records of 500 bytes. */
    private static final String CUSTDATA_FIXTURE_NAME = "custdata.txt";

    /** File name of the cross-reference fixture: 50 records of 36 bytes, no filler. */
    private static final String CARDXREF_FIXTURE_NAME = "cardxref.txt";

    /** File name of the card fixture: 50 records of 150 bytes. */
    private static final String CARDDATA_FIXTURE_NAME = "carddata.txt";

    /** File name of the account fixture: 50 records of 300 bytes. */
    private static final String ACCTDATA_FIXTURE_NAME = "acctdata.txt";

    /** A staged location naming nothing at all, used to drive the open-failure diagnostic. */
    private static final String ABSENT_FIXTURE = FIXTURES + "no-such-staged-dataset.txt";

    /** The blank value every one of the six locations defaults to: no dataset is staged. */
    private static final String UNSTAGED = "";

    /** Records the delivered daily-transaction fixture carries, and rows the reference seed loads. */
    private static final int DALYTRAN_RECORDS = TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT;

    /** Records each of the account, card, customer and cross-reference fixtures carries. */
    private static final int REFERENCE_RECORDS = TestDataFactory.SEEDED_FIFTY_ROW_COUNT;

    /** Verified width of both 350-byte layouts, in encoded bytes. The trap: one width, two entities. */
    private static final int TRANSACTION_WIDTH = 350;

    /** Verified width of the customer layout, in encoded bytes. */
    private static final int CUSTOMER_WIDTH = 500;

    /** Width of the cross-reference layout as the delivered ASCII fixture carries it: no filler. */
    private static final int CARD_XREF_FIXTURE_WIDTH = 36;

    /** Width of the cross-reference layout as the mainframe sequential data set carries it. */
    private static final int CARD_XREF_DATASET_WIDTH = 50;

    /** Verified width of the card layout, in encoded bytes. */
    private static final int CARD_WIDTH = 150;

    /** Verified width of the account layout, in encoded bytes. */
    private static final int ACCOUNT_WIDTH = 300;

    /** Filler bytes the mainframe cross-reference image carries and the ASCII fixture does not. */
    private static final int CARD_XREF_DATASET_FILLER = CARD_XREF_DATASET_WIDTH
            - CARD_XREF_FIXTURE_WIDTH;

    /** Migrated table the daily-transaction entity maps to; the landing table, with no foreign key. */
    private static final String LANDING_TABLE = "daily_transaction";

    /** Migrated table the posted-transaction entity maps to; a different table with a foreign key. */
    private static final String POSTED_TABLE = "transaction";

    /** The entity property the sequential scan orders by, and the landing record's business key. */
    private static final String KEY_PROPERTY = "dalytranId";

    /** Zero-based offset and width of every field of the 350-byte landing layout, in record order. */
    private static final List<FieldOffset> LANDING_FIELD_OFFSETS = List.of(
            new FieldOffset("DALYTRAN-ID", 0, 16),
            new FieldOffset("DALYTRAN-TYPE-CD", 16, 2),
            new FieldOffset("DALYTRAN-CAT-CD", 18, 4),
            new FieldOffset("DALYTRAN-SOURCE", 22, 10),
            new FieldOffset("DALYTRAN-DESC", 32, 100),
            new FieldOffset("DALYTRAN-AMT", 132, 11),
            new FieldOffset("DALYTRAN-MERCHANT-ID", 143, 9),
            new FieldOffset("DALYTRAN-MERCHANT-NAME", 152, 50),
            new FieldOffset("DALYTRAN-MERCHANT-CITY", 202, 50),
            new FieldOffset("DALYTRAN-MERCHANT-ZIP", 252, 10),
            new FieldOffset("DALYTRAN-CARD-NUM", 262, 16),
            new FieldOffset("DALYTRAN-ORIG-TS", 278, 26),
            new FieldOffset("DALYTRAN-PROC-TS", 304, 26));

    /** Trailing filler of the landing layout: the bytes after the last mapped field. */
    private static final int LANDING_FILLER_WIDTH = 20;

    /** Encoded width of the landing amount field: nine integer digits and two decimal digits. */
    private static final int AMOUNT_FIELD_WIDTH = 11;

    /** Decimal digits every monetary field of the estate carries. */
    private static final int AMOUNT_DECIMAL_DIGITS = 2;

    /**
     * Overpunched final byte for a positive value, indexed by the low-order digit.
     *
     * <p>Declared here rather than borrowed, because an expected record image must be produced without
     * any help from the production encoder it is meant to check.
     */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** Overpunched final byte for a negative value, indexed by the low-order digit. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /** Card-number field of the cross-reference image: 16 characters at offset zero. */
    private static final int XREF_CARD_NUMBER_WIDTH = 16;

    /** Customer-identifier field of the cross-reference image: 9 digits. */
    private static final int XREF_CUSTOMER_ID_WIDTH = 9;

    /** Account-identifier field of the cross-reference image: 11 digits. */
    private static final int XREF_ACCOUNT_ID_WIDTH = 11;

    /**
     * Identifier of the one landing row this specification writes, reserved above every seeded key.
     *
     * <p>Above the highest seeded identifier on purpose: the row is therefore the last the ascending scan
     * delivers, which is what makes the trailing post-end-of-file pass re-verify <em>it</em> and makes
     * that pass observable. It is removed again whatever the outcome.
     */
    private static final String RESERVED_LANDING_ID = "8880000000000001";

    /**
     * Distinguishing text of the mainline's opening announcement, emitted at source line 156.
     *
     * <p>It is the first thing the mainline does, before the first of the six opens, so its presence
     * bounds every other diagnostic below and its absence proves the pass was never entered.
     */
    private static final String START_OF_EXECUTION_MARKER = "START OF EXECUTION";

    /**
     * Distinguishing text of the mainline's closing announcement, emitted at source line 195.
     *
     * <p>It follows the last of the six closes at source line 193, and the twelve open and close routines
     * are silent on their success arm exactly as the source is, so this announcement is the only
     * observable evidence that all six closes ran. Its <em>absence</em> after an abend is equally
     * load-bearing: the abort ends the run unit inside the routine that reported the failure, so the
     * closes and this announcement are never reached.
     */
    private static final String END_OF_EXECUTION_MARKER = "END OF EXECUTION";

    /** Property naming the framework's launch-on-start switch, which every profile disables. */
    private static final String LAUNCH_ON_START_PROPERTY = "spring.batch.job.enabled";

    /** Property naming a job for the framework to launch at start-up, which is never set. */
    private static final String LAUNCH_JOB_NAME_PROPERTY = "spring.batch.job.name";

    // =============================================================================================
    // THE TRACEABILITY CONTRACT. One entry per paragraph, in the order the paragraphs physically
    // appear, so that the ordering, the group cardinalities and the resource sequence can be checked
    // mechanically instead of being asserted in prose.
    // =============================================================================================

    /** The five groups the eighteen paragraphs fall into, in the order the groups appear in the source. */
    private enum ParagraphGroup {

        /** The single driver paragraph. */
        MAINLINE,

        /** The get-next, the cross-reference lookup and the account read. */
        PROCESSING,

        /** One open routine per named resource. */
        OPEN,

        /** One close routine per named resource. */
        CLOSE,

        /** The abend routine and the status-display routine. */
        DIAGNOSTIC
    }

    /**
     * One paragraph of the legacy member, named as the source names it.
     *
     * @param ordinal     position in physical source order, from one
     * @param label       the paragraph label, which is permitted traceability metadata
     * @param sourceLine  the line the paragraph starts on
     * @param group       the group the paragraph belongs to
     * @param resourceName the logical resource the paragraph names, or the empty string for the
     *                     mainline, the get-next and the two diagnostics
     */
    private record ParagraphUnit(int ordinal, String label, int sourceLine, ParagraphGroup group,
            String resourceName) {
    }

    /** One field of a fixed-width record layout, by zero-based offset and encoded width. */
    private record FieldOffset(String cobolName, int offset, int width) {

        /** @return the exclusive end offset of this field */
        int endOffset() {
            return this.offset + this.width;
        }
    }

    /** The eighteen paragraph units, in physical source order. */
    private static final List<ParagraphUnit> PARAGRAPH_INVENTORY = List.of(
            new ParagraphUnit(1, "MAIN-PARA", 155, ParagraphGroup.MAINLINE, ""),
            new ParagraphUnit(2, "1000-DALYTRAN-GET-NEXT", 202, ParagraphGroup.PROCESSING,
                    DailyTransactionReadJobConfig.DD_DALYTRAN),
            new ParagraphUnit(3, "2000-LOOKUP-XREF", 227, ParagraphGroup.PROCESSING,
                    DailyTransactionReadJobConfig.DD_XREFFILE),
            new ParagraphUnit(4, "3000-READ-ACCOUNT", 241, ParagraphGroup.PROCESSING,
                    DailyTransactionReadJobConfig.DD_ACCTFILE),
            new ParagraphUnit(5, "0000-DALYTRAN-OPEN", 252, ParagraphGroup.OPEN,
                    DailyTransactionReadJobConfig.DD_DALYTRAN),
            new ParagraphUnit(6, "0100-CUSTFILE-OPEN", 271, ParagraphGroup.OPEN,
                    DailyTransactionReadJobConfig.DD_CUSTFILE),
            new ParagraphUnit(7, "0200-XREFFILE-OPEN", 289, ParagraphGroup.OPEN,
                    DailyTransactionReadJobConfig.DD_XREFFILE),
            new ParagraphUnit(8, "0300-CARDFILE-OPEN", 307, ParagraphGroup.OPEN,
                    DailyTransactionReadJobConfig.DD_CARDFILE),
            new ParagraphUnit(9, "0400-ACCTFILE-OPEN", 325, ParagraphGroup.OPEN,
                    DailyTransactionReadJobConfig.DD_ACCTFILE),
            new ParagraphUnit(10, "0500-TRANFILE-OPEN", 343, ParagraphGroup.OPEN,
                    DailyTransactionReadJobConfig.DD_TRANFILE),
            new ParagraphUnit(11, "9000-DALYTRAN-CLOSE", 361, ParagraphGroup.CLOSE,
                    DailyTransactionReadJobConfig.DD_DALYTRAN),
            new ParagraphUnit(12, "9100-CUSTFILE-CLOSE", 379, ParagraphGroup.CLOSE,
                    DailyTransactionReadJobConfig.DD_CUSTFILE),
            new ParagraphUnit(13, "9200-XREFFILE-CLOSE", 397, ParagraphGroup.CLOSE,
                    DailyTransactionReadJobConfig.DD_XREFFILE),
            new ParagraphUnit(14, "9300-CARDFILE-CLOSE", 415, ParagraphGroup.CLOSE,
                    DailyTransactionReadJobConfig.DD_CARDFILE),
            new ParagraphUnit(15, "9400-ACCTFILE-CLOSE", 433, ParagraphGroup.CLOSE,
                    DailyTransactionReadJobConfig.DD_ACCTFILE),
            new ParagraphUnit(16, "9500-TRANFILE-CLOSE", 451, ParagraphGroup.CLOSE,
                    DailyTransactionReadJobConfig.DD_TRANFILE),
            new ParagraphUnit(17, "Z-ABEND-PROGRAM", 469, ParagraphGroup.DIAGNOSTIC, ""),
            new ParagraphUnit(18, "Z-DISPLAY-IO-STATUS", 476, ParagraphGroup.DIAGNOSTIC, ""));

    /** The six logical resource names, in the order the six open paragraphs name them. */
    private static final List<String> RESOURCE_SEQUENCE = List.of(
            DailyTransactionReadJobConfig.DD_DALYTRAN,
            DailyTransactionReadJobConfig.DD_CUSTFILE,
            DailyTransactionReadJobConfig.DD_XREFFILE,
            DailyTransactionReadJobConfig.DD_CARDFILE,
            DailyTransactionReadJobConfig.DD_ACCTFILE,
            DailyTransactionReadJobConfig.DD_TRANFILE);

    /** Every step-name constant the other eight job configurations publish, so originality is checkable. */
    private static final List<String> OTHER_JOBS_STEP_NAMES = List.of(
            BackupTransactionJobConfig.ARCHIVE_STEP_NAME,
            BackupTransactionJobConfig.RESET_STEP_NAME,
            CategoryBalanceReportJobConfig.CLEAR_PRIOR_REPORT_STEP_NAME,
            CategoryBalanceReportJobConfig.UNLOAD_STEP_NAME,
            CategoryBalanceReportJobConfig.SORT_AND_REPROJECT_STEP_NAME,
            CombineTransactionsJobConfig.ORDER_STEP_NAME,
            CombineTransactionsJobConfig.LOAD_STEP_NAME,
            CreateStatementJobConfig.ORDER_AND_REPROJECT_STEP_NAME,
            CreateStatementJobConfig.LOAD_WORK_RESOURCE_STEP_NAME,
            CreateStatementJobConfig.CLEAR_STATEMENT_OUTPUTS_STEP_NAME,
            CreateStatementJobConfig.GENERATE_STATEMENTS_STEP_NAME,
            FileProbeJobConfig.FILE_PROBE_STEP_NAME,
            FileProbeJobConfig.LEGACY_STEP_NAME,
            InterestCalculationJobConfig.STEP_NAME,
            InterestCalculationJobConfig.LEGACY_STEP_NAME,
            PostTransactionJobConfig.STEP_NAME,
            TransactionReportJobConfig.UNLOAD_STEP_NAME,
            TransactionReportJobConfig.FILTER_AND_ORDER_STEP_NAME,
            TransactionReportJobConfig.EMIT_STEP_NAME);

    /** The eight other stable job names, none of which may be this one. */
    private static final List<String> OTHER_JOB_NAMES = List.of(
            BatchJobCatalog.POST_TRANSACTION_JOB,
            BatchJobCatalog.INTEREST_CALCULATION_JOB,
            BatchJobCatalog.COMBINE_TRANSACTIONS_JOB,
            BatchJobCatalog.CREATE_STATEMENT_JOB,
            BatchJobCatalog.TRANSACTION_REPORT_JOB,
            BatchJobCatalog.BACKUP_TRANSACTION_JOB,
            BatchJobCatalog.CATEGORY_BALANCE_REPORT_JOB,
            BatchJobCatalog.FILE_PROBE_JOB);

    /** Every job-parameter name the module declares, none of which this job accepts. */
    private static final List<String> EVERY_DECLARED_PARAMETER_NAME = List.of(
            BatchJobCatalog.INTEREST_PARM_DATE_PARAMETER,
            BatchJobCatalog.REPORT_START_DATE_PARAMETER,
            BatchJobCatalog.REPORT_END_DATE_PARAMETER,
            BatchJobCatalog.FILE_PROBE_MODE_PARAMETER);

    // =============================================================================================
    // THE RUNTIME. Every collaborator is taken from the running context, so nothing about the
    // production wiring is simulated. No container, no data-source property and no context-discarding
    // annotation is declared here: the shared base class owns all three.
    // =============================================================================================

    /** The whole context, used to resolve staged locations exactly as a deployment resolves them. */
    @Autowired
    private ApplicationContext context;

    /** The resolved environment, read to prove the launch-on-start posture rather than assume it. */
    @Autowired
    private Environment environment;

    /** The registry the framework populates, and the surface a caller launches this job through. */
    @Autowired
    private JobRegistry jobRegistry;

    /** Launches a registered job by name, which is the only way this job ever starts. */
    @Autowired
    private JobOperator jobOperator;

    /** Used to observe that no execution of this job existed before this specification launched one. */
    @Autowired
    private JobExplorer jobExplorer;

    /** The job under test, injected by the name its configuration publishes. */
    @Autowired
    private Job dailyTransactionReadJob;

    /** The single step, injected by name so no other step bean can be substituted for it. */
    @Autowired
    private Step dailyTransactionExtractStep;

    /** The configuration under test, as the context wires it: nothing staged, which is the shipped state. */
    @Autowired
    private DailyTransactionReadJobConfig configuration;

    /** The translated program, which owns all eighteen paragraphs. */
    @Autowired
    private DailyTransactionReadService readService;

    /** The factory that owns one reader per fixed-width layout, used unsimulated. */
    @Autowired
    private FixedWidthFlatFileReaderFactory readerFactory;

    /** The registry the step timer records on, read for presence and shape only. */
    @Autowired
    private MeterRegistry meterRegistry;

    /** The framework's job repository, needed to build a second configuration over a staged dataset. */
    @Autowired
    private JobRepository jobRepository;

    /** The transaction manager the step runs its tasklet under. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The shared object-store staging boundary, which holds nothing so a class-path location resolves. */
    @Autowired
    private BatchStagingArea stagingArea;

    /** The landing gateway, used to write and remove the one reserved row and to read back order. */
    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    /** The cross-reference gateway, read to establish the expected account of a seeded card. */
    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /** The account gateway, read to confirm a resolved cross-reference names a row that exists. */
    @Autowired
    private AccountRepository accountRepository;

    /** The posted-transaction gateway, read to prove this program writes nothing at all. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Collects the diagnostics the pass emits, in emission order, so an ordering can be asserted. */
    private ListAppender<ILoggingEvent> recorder;

    /** The loggers the capture is attached to, and the level each carried before it was lowered. */
    private List<CapturedLogger> capturedLoggers = List.of();

    /** One logger under capture, together with the level to restore when the capture is detached. */
    private record CapturedLogger(Logger logger, Level restoreLevel) {
    }

    // =============================================================================================
    // DIAGNOSTIC CAPTURE. The three loggers whose events this specification orders are captured into
    // one list, so the emission order across them is observable. The level is LOWERED rather than
    // raised: the diagnostic stream is what this program produces, and silencing it would remove the
    // only product there is to assert on.
    //
    // The three loggers are siblings - none is an ancestor of another - so no event is recorded twice.
    // =============================================================================================

    @BeforeEach
    void attachDiagnosticCapture() {
        this.recorder = new ListAppender<>();
        this.recorder.start();
        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        final List<CapturedLogger> captured = new ArrayList<>();
        for (final Class<?> emitter : List.of(DailyTransactionReadService.class, AbendService.class,
                DailyTransactionReadJobConfig.class)) {
            final Logger logger = loggerContext.getLogger(emitter);
            captured.add(new CapturedLogger(logger, logger.getLevel()));
            logger.setLevel(Level.DEBUG);
            logger.addAppender(this.recorder);
        }
        this.capturedLoggers = List.copyOf(captured);
    }

    @AfterEach
    void detachDiagnosticCapture() {
        for (final CapturedLogger captured : this.capturedLoggers) {
            captured.logger().detachAppender(this.recorder);
            captured.logger().setLevel(captured.restoreLevel());
        }
        this.capturedLoggers = List.of();
        this.recorder.stop();
        this.recorder.list.clear();
    }

    /**
     * Removes the one reserved landing row, whatever the outcome of the test that may have written it.
     *
     * <p>The server is shared by every integration specification in the run, and a neighbouring
     * specification asserts the measured seed of exactly three hundred landing rows. A row left behind
     * would fail that specification rather than this one, so the reserved identifier is removed
     * unconditionally; removing an absent identifier is a no-operation, so a test that never wrote it
     * cleans up just as well.
     */
    @AfterEach
    void removeTheReservedLandingRow() {
        this.dailyTransactionRepository.deleteById(RESERVED_LANDING_ID);
    }

    // =============================================================================================
    // HELPERS
    // =============================================================================================

    /**
     * Builds a second configuration over the same collaborators the context wired, with the six
     * locations supplied.
     *
     * <p>The context's own bean stages nothing, which is the shipped state and the state the launched
     * job runs in. A staged configuration is built here rather than bound through the environment
     * because both postures must be exercised in one specification, and a property is bound once when
     * the context starts.
     *
     * @param dalytran location of the staged daily-transaction dataset, or blank
     * @param custfile location of the staged customer dataset, or blank
     * @param xreffile location of the staged cross-reference dataset, or blank
     * @param cardfile location of the staged card dataset, or blank
     * @param acctfile location of the staged account dataset, or blank
     * @param tranfile location of the staged posted-transaction dataset, or blank
     * @return a configuration bound to those six locations
     */
    private DailyTransactionReadJobConfig staged(final String dalytran, final String custfile,
            final String xreffile, final String cardfile, final String acctfile,
            final String tranfile) {
        return new DailyTransactionReadJobConfig(this.jobRepository, this.transactionManager,
                this.readerFactory, this.readService, this.meterRegistry, this.context,
                this.stagingArea, dalytran, custfile, xreffile, cardfile, acctfile, tranfile);
    }

    /**
     * Stages all six resources at their delivered fixtures.
     *
     * <p>The daily-transaction fixture serves both 350-byte resources, because the two layouts are
     * byte-for-byte identical and no separate posted-transaction fixture is delivered. That the same
     * bytes parse under both bindings is exactly the trap this specification exists to guard: the two
     * bindings stay distinct by entity and by table, never by width.
     *
     * @return a configuration with every one of the six resources staged
     */
    private DailyTransactionReadJobConfig allSixStaged() {
        return staged(FIXTURES + DALYTRAN_FIXTURE_NAME, FIXTURES + CUSTDATA_FIXTURE_NAME,
                FIXTURES + CARDXREF_FIXTURE_NAME, FIXTURES + CARDDATA_FIXTURE_NAME,
                FIXTURES + ACCTDATA_FIXTURE_NAME, FIXTURES + DALYTRAN_FIXTURE_NAME);
    }

    /**
     * Reads every record a bound reader delivers, in the order the dataset holds them.
     *
     * @param <T> the record type the reader delivers
     * @param bound the binding under test, which must be present
     * @return the records, in dataset order
     * @throws Exception if the reader reports a failure, which fails the test
     */
    private static <T> List<T> readAll(final Optional<FlatFileItemReader<T>> bound) throws Exception {
        assertThat(bound).as("the binding must be present before its records can be read").isPresent();
        final FlatFileItemReader<T> reader = bound.orElseThrow();
        final List<T> records = new ArrayList<>();
        reader.open(new ExecutionContext());
        try {
            T record = reader.read();
            while (record != null) {
                records.add(record);
                record = reader.read();
            }
        } finally {
            reader.close();
        }
        return List.copyOf(records);
    }

    /**
     * Measures a record image in <strong>encoded bytes</strong>, never in characters and never trimmed.
     *
     * @param image the record image exactly as the fixture carries it
     * @return the encoded width
     */
    private static int encodedWidth(final String image) {
        return image.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Slices one field out of a landing record image by the reference offsets declared in this file.
     *
     * @param image the 350-byte landing image
     * @param cobolName the field name, as the layout names it
     * @return the field's bytes exactly as the image carries them, untrimmed
     */
    private static String landingField(final String image, final String cobolName) {
        for (final FieldOffset field : LANDING_FIELD_OFFSETS) {
            if (field.cobolName().equals(cobolName)) {
                return image.substring(field.offset(), field.endOffset());
            }
        }
        throw new IllegalArgumentException("the landing layout declares no field named " + cobolName
                + "; it declares " + LANDING_FIELD_OFFSETS.size() + " fields");
    }

    /**
     * Renders one signed amount as its zoned-decimal image, independently of the production encoder.
     *
     * <p>Plain string arithmetic and a hand-declared overpunch table: the sign is folded into the final
     * byte, and nothing here calls the codec whose output it is used to check. No scaling operation is
     * performed at all - the value is shifted to whole units and taken exactly - so the truncating
     * policy the estate carries cannot be contradicted by a rounding choice made here.
     *
     * @param amount the amount to render
     * @param width the encoded field width
     * @return the zoned-decimal image, exactly {@code width} bytes
     */
    private static String zonedImage(final BigDecimal amount, final int width) {
        final BigInteger units = amount.movePointRight(AMOUNT_DECIMAL_DIGITS).toBigIntegerExact();
        final String digits = units.abs().toString();
        final String padded = "0".repeat(width - digits.length()) + digits;
        final int lowOrderDigit = padded.charAt(width - 1) - '0';
        final String overpunch = units.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        return padded.substring(0, width - 1) + overpunch.charAt(lowOrderDigit);
    }

    /**
     * Decodes one zoned-decimal image, independently of the production decoder.
     *
     * @param field the field's bytes exactly as the image carries them
     * @return the value the field encodes, at the estate's two-decimal scale
     */
    private static BigDecimal decodedAmount(final String field) {
        final char finalByte = field.charAt(field.length() - 1);
        int lowOrderDigit = POSITIVE_OVERPUNCH.indexOf(finalByte);
        final boolean negative = lowOrderDigit < 0;
        if (negative) {
            lowOrderDigit = NEGATIVE_OVERPUNCH.indexOf(finalByte);
        }
        assertThat(lowOrderDigit)
                .as("the final byte of a zoned field carries both a digit and a sign")
                .isNotNegative();
        final BigInteger units = new BigInteger(String.format(Locale.ROOT, "%s%d",
                field.substring(0, field.length() - 1), lowOrderDigit));
        return new BigDecimal(negative ? units.negate() : units, AMOUNT_DECIMAL_DIGITS);
    }

    /**
     * The sealing function this specification hands to the customer binding.
     *
     * <p>No cryptography and no key: the body is the value's bytes zero-extended to the envelope's
     * minimum length and encoded behind the module's own marker, which is the shape the entity checks.
     * A real sealing operation belongs to the layer that holds a key, and this specification holds
     * none - which is why the function is supplied rather than defaulted.
     *
     * @return an envelope-shaped sealing function
     */
    private static UnaryOperator<String> sealer() {
        return DailyTransactionReadJobConfigIT::seal;
    }

    /**
     * Produces one envelope-shaped stand-in for a regulated identifier.
     *
     * @param cleartext the value exactly as the record image carries it
     * @return an envelope-shaped value
     */
    private static String seal(final String cleartext) {
        final byte[] raw = cleartext.getBytes(StandardCharsets.US_ASCII);
        final byte[] body =
                new byte[Math.max(SensitiveFieldCodec.MINIMUM_ENVELOPE_BODY_BYTES, raw.length)];
        System.arraycopy(raw, 0, body, 0, raw.length);
        return SensitiveFieldCodec.ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(body);
    }

    /**
     * The card number the first record of the delivered daily-transaction fixture carries.
     *
     * <p>Sliced out of the fixture image by the reference offsets declared in this file, so the value
     * comes from the delivered bytes rather than from a restated literal, and the reference seed is
     * known to hold a cross-reference for it.
     *
     * @return a card number the cross-reference resolves
     */
    private static String firstSeededCardNumber() {
        return landingField(
                TestDataFactory.fixedWidthRecords(DALYTRAN_FIXTURE_NAME, TRANSACTION_WIDTH).get(0),
                "DALYTRAN-CARD-NUM");
    }

    /**
     * The account identifier the delivered cross-reference fixture maps one card number to.
     *
     * <p>Read out of the delivered image by plain offset arithmetic, so the expectation comes from the
     * fixture's own bytes rather than from the repository the code under test reads through.
     *
     * @param cardNumber the card number to resolve
     * @return the eleven-digit account identifier the fixture pairs with that card
     */
    private static String expectedAccountOf(final String cardNumber) {
        for (final String image : TestDataFactory.fixedWidthRecords(CARDXREF_FIXTURE_NAME,
                CARD_XREF_FIXTURE_WIDTH)) {
            if (image.startsWith(cardNumber)) {
                return image.substring(XREF_CARD_NUMBER_WIDTH + XREF_CUSTOMER_ID_WIDTH);
            }
        }
        throw new AssertionError("the delivered cross-reference fixture "
                + TestDataFactory.FIXTURE_DIRECTORY + CARDXREF_FIXTURE_NAME
                + " holds no record for the supplied card number, so the expected account identifier"
                + " cannot be established; it carries " + REFERENCE_RECORDS + " record(s) of "
                + CARD_XREF_FIXTURE_WIDTH + " encoded bytes");
    }

    /**
     * Locates the first captured diagnostic carrying a fragment, failing with an actionable message
     * naming the fragment and the stream when there is none.
     *
     * @param emitted the captured diagnostics, in emission order
     * @param fragment the text to locate
     * @return the position of the first diagnostic carrying the fragment
     */
    private static int indexOfDiagnosticContaining(final List<String> emitted, final String fragment) {
        for (int index = 0; index < emitted.size(); index++) {
            if (emitted.get(index).contains(fragment)) {
                return index;
            }
        }
        throw new AssertionError("no captured diagnostic carries \"" + fragment + "\"; the pass emitted "
                + emitted.size() + " diagnostic(s) on the loggers of "
                + DailyTransactionReadService.class.getName() + ", "
                + AbendService.class.getName() + " and "
                + DailyTransactionReadJobConfig.class.getName() + ": " + emitted);
    }

    /**
     * Renders every captured diagnostic, in emission order.
     *
     * @return the formatted texts
     */
    private List<String> diagnostics() {
        return this.recorder.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Renders the captured error-level diagnostics, in emission order.
     *
     * @return the formatted error texts
     */
    private List<String> errorDiagnostics() {
        return this.recorder.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * Launches the job by name and advances it to its next instance, which is how the operational
     * surface starts one.
     *
     * @return the execution that ran
     * @throws Exception if the launch is refused, which fails the test
     */
    private JobExecution launchByName() throws Exception {
        return this.jobExplorer.getJobExecution(
                this.jobOperator.startNextInstance(DailyTransactionReadJobConfig.JOB_NAME));
    }

    /**
     * Counts the passes recorded on the batch tier's shared program-lifecycle timer for one outcome.
     *
     * <p>The count alone, never a duration: no numeric latency, throughput or memory figure is asserted
     * anywhere in this specification, because no legacy baseline exists to compare one against.
     *
     * @param outcome the outcome tag value
     * @return how many passes have been recorded under that outcome
     */
    private long timedPasses(final String outcome) {
        final Timer timer = this.meterRegistry.find(DailyTransactionReadJobConfig.STEP_TIMER_NAME)
                .tag(DailyTransactionReadJobConfig.TAG_STEP,
                        DailyTransactionReadJobConfig.PROGRAM_NAME)
                .tag(DailyTransactionReadJobConfig.TAG_OUTCOME, outcome)
                .timer();
        return timer == null ? 0L : timer.count();
    }

    /**
     * Counts one integer projection from a fixed catalogue query with one bound parameter.
     *
     * <p>Every statement is a complete literal and the one variable is bound, so no value is ever
     * concatenated into SQL.
     *
     * @param sql the literal query
     * @param parameter the single bound value
     * @return the projected count
     * @throws SQLException if the catalogue cannot be read
     */
    private static int catalogueCount(final String sql, final String parameter) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, parameter);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).as("a counting query always projects one row").isTrue();
                return rows.getInt(1);
            }
        }
    }

    /** Counts the foreign keys declared on one migrated table. */
    private static final String FOREIGN_KEY_COUNT_SQL = """
            SELECT count(*) FROM information_schema.table_constraints
             WHERE table_schema = 'public'
               AND constraint_type = 'FOREIGN KEY'
               AND table_name = ?
            """;

    /** Counts the columns of one migrated table that carry a generated or defaulted value. */
    private static final String GENERATED_COLUMN_COUNT_SQL = """
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema = 'public'
               AND table_name = ?
               AND (is_identity = 'YES' OR column_default IS NOT NULL)
            """;

    /**
     * Counts the sequences the migrations created, excluding the framework's own metadata sequences.
     *
     * <p>The exclusion is the point: the job-repository sequences are provisioned by the framework from
     * its bundled script under a reserved prefix, they are real and expected, and counting them would
     * fail an assertion about the record schema for a reason that has nothing to do with it.
     */
    private static final String APPLICATION_SEQUENCE_COUNT_SQL = """
            SELECT count(*) FROM information_schema.sequences
             WHERE sequence_schema = 'public'
               AND sequence_name NOT LIKE 'batch\\_%'
            """;

    /**
     * Counts one integer projection from a fixed catalogue query that binds nothing.
     *
     * @param sql the literal query
     * @return the projected count
     * @throws SQLException if the catalogue cannot be read
     */
    private static int catalogueCount(final String sql) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement query = connection.prepareStatement(sql);
                ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).as("a counting query always projects one row").isTrue();
            return rows.getInt(1);
        }
    }

    // =============================================================================================
    // 1 - THE ORPHAN'S JOB IS DEFINED, LAUNCHABLE BY NAME, AND WIRED INTO NOTHING
    // =============================================================================================

    /** Creates the specification. */
    DailyTransactionReadJobConfigIT() {
    }

    @Nested
    @Order(1)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @DisplayName("1 - defined and deliberately unwired: launchable by name, reached by no pipeline")
    final class TheOrphanJobIsDefinedAndDeliberatelyUnwired {

        /** Creates the nest. */
        TheOrphanJobIsDefinedAndDeliberatelyUnwired() {
        }

        /**
         * The one test that may assert the absence of a prior execution, so it also performs the first
         * launch.
         *
         * <p>Both halves belong together. The emptiness of this job's execution history is only true
         * until something launches it, so asserting it in one test and launching in another would make
         * the pair order-dependent on nothing more than a method name. Asserting it immediately before
         * the launch it precedes removes that dependence entirely.
         *
         * @throws Exception if the launch is refused, which fails the test
         */
        @Test
        @Order(1)
        @DisplayName("nothing had ever run it, nothing auto-launches it, and it runs to completion when "
                + "this specification launches it by name")
        void nothingHadRunItAndItCompletesWhenLaunchedByName() throws Exception {
            assertThat(jobRegistry.getJobNames())
                    .as("the job is launched by name, so the registry must hold it under that name")
                    .contains(DailyTransactionReadJobConfig.JOB_NAME);

            assertThat(context.getBeansOfType(JobLauncherApplicationRunner.class))
                    .as("the framework's start-up runner is what would launch a job merely because a "
                            + "context refreshed, and for a job the legacy system never ran that would "
                            + "run a program nothing ever ran")
                    .isEmpty();
            assertThat(environment.getProperty(LAUNCH_ON_START_PROPERTY, Boolean.class, Boolean.TRUE))
                    .as("%s is disabled by the shared configuration document", LAUNCH_ON_START_PROPERTY)
                    .isFalse();
            assertThat(environment.getProperty(LAUNCH_JOB_NAME_PROPERTY))
                    .as("%s names a job for the framework to start and is never set",
                            LAUNCH_JOB_NAME_PROPERTY)
                    .isNull();

            assertThat(jobExplorer.getJobInstances(DailyTransactionReadJobConfig.JOB_NAME, 0,
                    DALYTRAN_RECORDS))
                    .as("no execution of the orphan may exist before this specification launches one")
                    .isEmpty();

            final JobExecution execution = launchByName();

            assertThat(execution.getStatus())
                    .as("defined but unwired means unwired, not unreachable")
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName)
                    .as("the single step ran, and no other step is part of this job")
                    .containsExactly(DailyTransactionReadJobConfig.STEP_NAME);
            assertThat(jobExplorer.findRunningJobExecutions(DailyTransactionReadJobConfig.JOB_NAME))
                    .as("nothing may be left running")
                    .isEmpty();
        }

        @Test
        @Order(2)
        @DisplayName("the job is a plain sequence holding exactly one step, so it carries zero "
                + "failure-ending transitions - the estate holds no gate for a program it holds no step for")
        void theJobCarriesZeroFailureEndingTransitions() {
            assertThat(dailyTransactionReadJob)
                    .as("a failure-ending transition would have produced a flow job; the member has no "
                            + "job stream at all and therefore no condition-code gate to reproduce")
                    .isExactlyInstanceOf(SimpleJob.class);
            assertThat(((SimpleJob) dailyTransactionReadJob).getStepNames())
                    .containsExactly(DailyTransactionReadJobConfig.STEP_NAME)
                    .doesNotHaveDuplicates();
            assertThat(dailyTransactionReadJob.getName())
                    .isEqualTo(DailyTransactionReadJobConfig.JOB_NAME);
        }

        @Test
        @Order(3)
        @DisplayName("the step name is original to this module and duplicates no other job's step-name "
                + "constant, because there is no legacy job member to inherit one from")
        void theStepNameIsOriginalToThisModule() {
            assertThat(DailyTransactionReadJobConfig.STEP_NAME)
                    .as("a step name has to be non-blank for a launch to address the step")
                    .isNotBlank();
            assertThat(OTHER_JOBS_STEP_NAMES)
                    .as("no other job configuration in the module publishes this step name")
                    .doesNotContain(DailyTransactionReadJobConfig.STEP_NAME);
            assertThat(dailyTransactionExtractStep.getName())
                    .isEqualTo(DailyTransactionReadJobConfig.STEP_NAME);
        }

        @Test
        @Order(4)
        @DisplayName("the job is absent from every other job in the module: no other stable job name is "
                + "this one, and no other job's step sequence reaches this step")
        void theJobIsAbsentFromTheDefaultPipeline() {
            assertThat(OTHER_JOB_NAMES)
                    .as("the eight wired jobs are distinct from the orphan, so none of them is it")
                    .doesNotContain(DailyTransactionReadJobConfig.JOB_NAME)
                    .hasSize(BatchJobCatalog.launchableJobNames().size() - 1);
            assertThat(BatchJobCatalog.launchableJobNames())
                    .as("the orphan is launchable on demand and is one of the module's declared names")
                    .contains(DailyTransactionReadJobConfig.JOB_NAME)
                    .containsAll(OTHER_JOB_NAMES);
            assertThat(context.getBeanNamesForType(Job.class))
                    .as("this slice publishes the orphan's job and no other, so no composite flow, no "
                            + "chained job and no split can reach it here")
                    .containsExactly(DailyTransactionReadJobConfig.JOB_NAME);
            assertThat(context.getBeanNamesForType(Step.class))
                    .as("one step bean, so nothing else can be sequenced into the job")
                    .containsExactly(DailyTransactionReadJobConfig.STEP_NAME);
        }

        @Test
        @Order(5)
        @DisplayName("the accepted parameter set is empty: no date parameter and no mode parameter is "
                + "invented for a program whose translation asks for neither")
        void theParameterContractIsEmpty() {
            final Optional<Set<String>> accepted =
                    BatchJobCatalog.parameterNamesFor(DailyTransactionReadJobConfig.JOB_NAME);
            assertThat(accepted)
                    .as("an unlaunchable name yields no result at all, so a present-but-empty result is "
                            + "the statement that this job accepts nothing")
                    .isPresent();
            assertThat(accepted.orElseThrow())
                    .as("no parameter string exists to reproduce, so none is accepted")
                    .isEmpty();
            for (final String declared : EVERY_DECLARED_PARAMETER_NAME) {
                assertThat(accepted.orElseThrow())
                        .as("%s belongs to a job that has a job member; this one has none", declared)
                        .doesNotContain(declared);
            }
        }

        @Test
        @Order(6)
        @DisplayName("execution is strictly sequential: one tasklet step, one chunk transaction, and no "
                + "partition step, flow step or split anywhere in the job")
        void executionIsStrictlySequential() throws Exception {
            assertThat(dailyTransactionExtractStep)
                    .as("a tasklet step exactly: a partition step, a flow step or a job step would each "
                            + "be a different type, and each would introduce a boundary the member has "
                            + "no restart point for")
                    .isExactlyInstanceOf(TaskletStep.class);

            final JobExecution execution = launchByName();

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getStepExecutions()).hasSize(1);
            final StepExecution step = execution.getStepExecutions().iterator().next();
            assertThat(step.getCommitCount())
                    .as("one indivisible pass commits once; a chunk boundary the member cannot restart "
                            + "from would commit more than once")
                    .isEqualTo(1L);
            assertThat(step.getReadCount())
                    .as("a tasklet step reports no item counts, because the read loop belongs to the "
                            + "translated program and not to the framework")
                    .isZero();
            assertThat(step.getWriteCount())
                    .as("nothing is written, because the member has no write statement at all")
                    .isZero();
        }

        @Test
        @Order(7)
        @DisplayName("the tasklet is reachable on its own and reports that it is finished, so the pass "
                + "can be driven without a launcher")
        void theTaskletIsReachableOnItsOwn() throws Exception {
            assertThat(configuration.dailyTransactionExtractTasklet().execute(null, null))
                    .as("one invocation runs the whole program and reports that there is no more to do")
                    .isEqualTo(RepeatStatus.FINISHED);
        }
    }

    // =============================================================================================
    // 2 - THE EIGHTEEN PARAGRAPH UNITS, IN PHYSICAL SOURCE ORDER
    // =============================================================================================

    @Nested
    @Order(2)
    @DisplayName("2 - the eighteen paragraph units, in physical source order, are the traceability "
            + "contract this specification is the only cover for")
    final class TheEighteenParagraphUnitsInPhysicalOrder {

        /** Creates the nest. */
        TheEighteenParagraphUnitsInPhysicalOrder() {
        }

        @Test
        @DisplayName("there are exactly eighteen units, numbered contiguously, and their source lines "
                + "ascend strictly - which is what makes a matrix walkable against the member")
        void thereAreEighteenUnitsInStrictlyAscendingSourceOrder() {
            assertThat(PARAGRAPH_INVENTORY)
                    .as("eighteen paragraphs across %d source lines, covered here and nowhere else",
                            LEGACY_SOURCE_LINES)
                    .hasSize(PARAGRAPH_UNITS);
            assertThat(PARAGRAPH_INVENTORY).extracting(ParagraphUnit::label)
                    .as("no paragraph label is recorded twice")
                    .doesNotHaveDuplicates();

            for (int index = 0; index < PARAGRAPH_INVENTORY.size(); index++) {
                final ParagraphUnit unit = PARAGRAPH_INVENTORY.get(index);
                assertThat(unit.ordinal())
                        .as("unit %s is the %dth in physical order", unit.label(), index + 1)
                        .isEqualTo(index + 1);
                assertThat(unit.label()).as("every unit carries its source label").isNotBlank();
                if (index > 0) {
                    assertThat(unit.sourceLine())
                            .as("%s at line %d must follow %s in the source, because the physical order "
                                    + "is not the numeric order of the labels", unit.label(),
                                    unit.sourceLine(), PARAGRAPH_INVENTORY.get(index - 1).label())
                            .isGreaterThan(PARAGRAPH_INVENTORY.get(index - 1).sourceLine());
                }
                assertThat(unit.sourceLine())
                        .as("%s lies inside the member", unit.label())
                        .isBetween(1, LEGACY_SOURCE_LINES);
            }
        }

        @Test
        @DisplayName("the groups appear as one mainline, three processing units, six opens, six closes "
                + "and two diagnostics, in that order and with no interleaving")
        void theGroupsAppearInTheirMeasuredOrderAndCardinality() {
            final List<ParagraphGroup> groups =
                    PARAGRAPH_INVENTORY.stream().map(ParagraphUnit::group).toList();

            assertThat(groups).containsExactly(
                    ParagraphGroup.MAINLINE,
                    ParagraphGroup.PROCESSING, ParagraphGroup.PROCESSING, ParagraphGroup.PROCESSING,
                    ParagraphGroup.OPEN, ParagraphGroup.OPEN, ParagraphGroup.OPEN,
                    ParagraphGroup.OPEN, ParagraphGroup.OPEN, ParagraphGroup.OPEN,
                    ParagraphGroup.CLOSE, ParagraphGroup.CLOSE, ParagraphGroup.CLOSE,
                    ParagraphGroup.CLOSE, ParagraphGroup.CLOSE, ParagraphGroup.CLOSE,
                    ParagraphGroup.DIAGNOSTIC, ParagraphGroup.DIAGNOSTIC);
            assertThat(groups).as("every declared group is represented, so none was dropped")
                    .containsAll(List.of(ParagraphGroup.values()));
        }

        @Test
        @DisplayName("the six opens and the six closes name the six resources in the same order, which "
                + "is the order the mainline performs them in")
        void theOpensAndClosesNameTheSixResourcesInOneOrder() {
            assertThat(PARAGRAPH_INVENTORY.stream()
                    .filter(unit -> unit.group() == ParagraphGroup.OPEN)
                    .map(ParagraphUnit::resourceName)
                    .toList())
                    .as("six opens, one per resource, in declaration order")
                    .containsExactlyElementsOf(RESOURCE_SEQUENCE);
            assertThat(PARAGRAPH_INVENTORY.stream()
                    .filter(unit -> unit.group() == ParagraphGroup.CLOSE)
                    .map(ParagraphUnit::resourceName)
                    .toList())
                    .as("six closes, in the same order as the opens rather than in reverse")
                    .containsExactlyElementsOf(RESOURCE_SEQUENCE);
            assertThat(RESOURCE_SEQUENCE)
                    .as("six distinct logical names, and the two 350-byte resources are two of them")
                    .doesNotHaveDuplicates()
                    .hasSize(RESOURCE_SEQUENCE.size());
        }

        @Test
        @DisplayName("the two diagnostic units are letter-prefixed while the other sixteen are "
                + "four-digit prefixed, a naming-generation divergence the Java target does not reproduce")
        void theTwoDiagnosticUnitsAreLetterPrefixed() {
            for (final ParagraphUnit unit : PARAGRAPH_INVENTORY) {
                final boolean numericPrefix = unit.label().length() > 4
                        && unit.label().substring(0, 4).chars().allMatch(Character::isDigit);
                if (unit.group() == ParagraphGroup.DIAGNOSTIC) {
                    assertThat(numericPrefix)
                            .as("%s is letter-prefixed, so a matrix row for it must be matched against "
                                    + "a letter-prefixed label and not against a numbered sequence that "
                                    + "does not exist", unit.label())
                            .isFalse();
                } else if (unit.group() != ParagraphGroup.MAINLINE) {
                    assertThat(numericPrefix)
                            .as("%s carries the four-digit prefix the rest of the estate uses",
                                    unit.label())
                            .isTrue();
                }
            }
            assertThat(PARAGRAPH_INVENTORY.stream()
                    .filter(unit -> unit.group() == ParagraphGroup.DIAGNOSTIC)
                    .map(ParagraphUnit::label)
                    .toList())
                    .as("the abend routine precedes the status-display routine in the source, and the "
                            + "translated layout keeps that order even though the abend runs second")
                    .containsExactly("Z-ABEND-PROGRAM", "Z-DISPLAY-IO-STATUS");
        }

        /**
         * Proves the mainline's runtime order from the diagnostic stream: the start announcement, then
         * the three processing units per record in source order, then the end announcement.
         *
         * <p>The twelve open and close units emit nothing on their success arm, which is faithful - the
         * legacy paragraphs display only on their error arm - so their execution is proven by the
         * brackets straddling the loop rather than by a diagnostic of their own. An open that had failed
         * would have abended inside the mainline and no end announcement would exist.
         *
         * @throws Exception if the pass fails, which fails the test
         */
        @Test
        @DisplayName("one pass over a single-record source emits the mainline's brackets around the three "
                + "processing units in source order: get-next, cross-reference lookup, account read")
        void onePassEmitsTheProcessingUnitsInSourceOrderInsideTheMainlineBrackets() throws Exception {
            final DailyTransaction record = TestDataFactory.dailyTransaction()
                    .id(RESERVED_LANDING_ID)
                    .cardNumber(firstSeededCardNumber())
                    .build();

            final DailyTransactionReadResult result =
                    readService.execute(List.of(record), verifications -> { });

            assertThat(result.recordsRead()).isEqualTo(1);
            final List<String> emitted = diagnostics();
            final int startIndex = indexOfDiagnosticContaining(emitted, START_OF_EXECUTION_MARKER);
            final int readIndex = indexOfDiagnosticContaining(emitted, "DALYTRAN-RECORD read");
            final int xrefIndex = indexOfDiagnosticContaining(emitted, "SUCCESSFUL READ OF XREF");
            final int accountIndex =
                    indexOfDiagnosticContaining(emitted, "SUCCESSFUL READ OF ACCOUNT FILE");
            final int endIndex = indexOfDiagnosticContaining(emitted, END_OF_EXECUTION_MARKER);

            assertThat(startIndex)
                    .as("the mainline announces itself before it opens anything")
                    .isLessThan(readIndex);
            assertThat(readIndex)
                    .as("the get-next at source line 202 runs before the cross-reference lookup at 227")
                    .isLessThan(xrefIndex);
            assertThat(xrefIndex)
                    .as("the account read at source line 241 is never reached before the cross-reference "
                            + "lookup that gates it")
                    .isLessThan(accountIndex);
            assertThat(accountIndex)
                    .as("the six closes and the end announcement follow the whole loop")
                    .isLessThan(endIndex);
        }
    }

    // =============================================================================================
    // 3 - SIX RESOURCES, SIX WIDTHS, AND THE 350-BYTE TRAP
    // =============================================================================================

    @Nested
    @Order(3)
    @DisplayName("3 - six resources at six widths, with the two 350-byte layouts kept distinct by "
            + "entity and by table rather than by width")
    final class TheSixResourcesAtSixWidths {

        /** Creates the nest. */
        TheSixResourcesAtSixWidths() {
        }

        @Test
        @DisplayName("the shipped state stages nothing at all, which is a normal state: five of the six "
                + "resources are never read and the sixth is resolved relationally")
        void theShippedStateStagesNothing() {
            assertThat(configuration.dalytranReader()).isEmpty();
            assertThat(configuration.custfileReader(sealer())).isEmpty();
            assertThat(configuration.xreffileReader()).isEmpty();
            assertThat(configuration.cardfileReader()).isEmpty();
            assertThat(configuration.acctfileReader()).isEmpty();
            assertThat(configuration.tranfileReader()).isEmpty();
        }

        @Test
        @DisplayName("each of the six bindings delivers its own entity type, so the compiler is what "
                + "refuses to substitute one resource's reader for another's")
        void eachBindingDeliversItsOwnEntityType() throws Exception {
            final DailyTransactionReadJobConfig staged = allSixStaged();

            final List<DailyTransaction> landing = readAll(staged.dalytranReader());
            final List<Customer> customers = readAll(staged.custfileReader(sealer()));
            final List<CardCrossReference> crossReferences = readAll(staged.xreffileReader());
            final List<Card> cards = readAll(staged.cardfileReader());
            final List<Account> accounts = readAll(staged.acctfileReader());
            final List<Transaction> posted = readAll(staged.tranfileReader());

            assertThat(landing).hasSize(DALYTRAN_RECORDS).hasOnlyElementsOfType(DailyTransaction.class);
            assertThat(customers).hasSize(REFERENCE_RECORDS).hasOnlyElementsOfType(Customer.class);
            assertThat(crossReferences).hasSize(REFERENCE_RECORDS)
                    .hasOnlyElementsOfType(CardCrossReference.class);
            assertThat(cards).hasSize(REFERENCE_RECORDS).hasOnlyElementsOfType(Card.class);
            assertThat(accounts).hasSize(REFERENCE_RECORDS).hasOnlyElementsOfType(Account.class);
            assertThat(posted).hasSize(DALYTRAN_RECORDS).hasOnlyElementsOfType(Transaction.class);

            assertThat(landing.get(0)).isNotInstanceOf(Transaction.class);
            assertThat(posted.get(0)).isNotInstanceOf(DailyTransaction.class);
        }

        @Test
        @DisplayName("every one of the six fixtures measures its verified width in encoded bytes, "
                + "untrimmed - 350, 500, 36, 150, 300 and 350")
        void everyFixtureMeasuresItsVerifiedWidthInEncodedBytes() {
            assertFixtureWidth(DALYTRAN_FIXTURE_NAME, TRANSACTION_WIDTH, DALYTRAN_RECORDS);
            assertFixtureWidth(CUSTDATA_FIXTURE_NAME, CUSTOMER_WIDTH, REFERENCE_RECORDS);
            assertFixtureWidth(CARDXREF_FIXTURE_NAME, CARD_XREF_FIXTURE_WIDTH, REFERENCE_RECORDS);
            assertFixtureWidth(CARDDATA_FIXTURE_NAME, CARD_WIDTH, REFERENCE_RECORDS);
            assertFixtureWidth(ACCTDATA_FIXTURE_NAME, ACCOUNT_WIDTH, REFERENCE_RECORDS);
        }

        @Test
        @DisplayName("the landing table and the posted table are different tables, and only the landing "
                + "table carries zero foreign keys - deliberately, so an unbacked row can exist")
        void theTwoIdenticallyWidthedResourcesMapToDifferentTables() throws SQLException {
            assertThat(LANDING_TABLE).isNotEqualTo(POSTED_TABLE);
            assertThat(APPLICATION_TABLES)
                    .as("both tables belong to the eleven-table application inventory")
                    .contains(LANDING_TABLE, POSTED_TABLE);
            assertThat(applicationTableNames())
                    .as("the migrated schema holds exactly the eleven application tables; the framework's "
                            + "metadata tables and the migration history are excluded by the reader")
                    .containsExactlyInAnyOrderElementsOf(APPLICATION_TABLES);

            assertThat(catalogueCount(FOREIGN_KEY_COUNT_SQL, LANDING_TABLE))
                    .as("the landing table carries no referential constraint at all, which is what lets "
                            + "a row naming a card no seeded row backs be written and then rejected")
                    .isZero();
            assertThat(catalogueCount(FOREIGN_KEY_COUNT_SQL, POSTED_TABLE))
                    .as("the posted table constrains every row to name an existing card, so the "
                            + "zero-key property is the landing table's and not this one's")
                    .isPositive();
        }

        @Test
        @DisplayName("the cross-reference comparison uses the delivered 36-byte form with no filler and "
                + "is never padded to the 50-byte data-set form, whose 14 filler bytes are the encoding's")
        void theCrossReferenceFixtureCarriesThirtySixBytesWithNoFiller() {
            final List<String> records =
                    TestDataFactory.fixedWidthRecords(CARDXREF_FIXTURE_NAME, CARD_XREF_FIXTURE_WIDTH);

            assertThat(records).hasSize(REFERENCE_RECORDS);
            assertThat(CARD_XREF_FIXTURE_WIDTH)
                    .as("the delivered form is 36 bytes and the data-set form is 50; padding the former "
                            + "to the latter produces an image that matches neither fixture")
                    .isNotEqualTo(CARD_XREF_DATASET_WIDTH)
                    .isEqualTo(XREF_CARD_NUMBER_WIDTH + XREF_CUSTOMER_ID_WIDTH + XREF_ACCOUNT_ID_WIDTH);
            assertThat(CARD_XREF_DATASET_FILLER)
                    .as("the 14 filler bytes belong to the mainframe-encoded data set alone")
                    .isEqualTo(14);

            for (final String image : records) {
                assertThat(encodedWidth(image))
                        .as("every delivered cross-reference record is 36 encoded bytes, untrimmed")
                        .isEqualTo(CARD_XREF_FIXTURE_WIDTH);
                final String cardNumber = image.substring(0, XREF_CARD_NUMBER_WIDTH);
                final String customerId = image.substring(XREF_CARD_NUMBER_WIDTH,
                        XREF_CARD_NUMBER_WIDTH + XREF_CUSTOMER_ID_WIDTH);
                final String accountId =
                        image.substring(XREF_CARD_NUMBER_WIDTH + XREF_CUSTOMER_ID_WIDTH);
                assertThat(encodedWidth(cardNumber)).isEqualTo(XREF_CARD_NUMBER_WIDTH);
                assertThat(customerId).hasSize(XREF_CUSTOMER_ID_WIDTH).containsOnlyDigits();
                assertThat(accountId).hasSize(XREF_ACCOUNT_ID_WIDTH).containsOnlyDigits();
                assertThat(image)
                        .as("a 36-byte record ends on a data byte, so there is nothing to trim and no "
                                + "filler to unify with the other four fixtures")
                        .doesNotEndWith(" ");
            }
        }

        @Test
        @DisplayName("the landing layout's fourteen fields are contiguous from offset zero and its "
                + "twenty trailing filler bytes bring the record to exactly 350")
        void theLandingLayoutOffsetsAreContiguousAndSumToThreeHundredAndFifty() {
            int expectedOffset = 0;
            for (final FieldOffset field : LANDING_FIELD_OFFSETS) {
                assertThat(field.offset())
                        .as("%s begins where the previous field ends; a gap or an overlap moves every "
                                + "later offset", field.cobolName())
                        .isEqualTo(expectedOffset);
                assertThat(field.width())
                        .as("%s carries at least one byte", field.cobolName())
                        .isPositive();
                expectedOffset = field.endOffset();
            }
            assertThat(expectedOffset + LANDING_FILLER_WIDTH)
                    .as("thirteen mapped fields plus twenty trailing filler bytes is exactly the record")
                    .isEqualTo(TRANSACTION_WIDTH);
            assertThat(LANDING_FIELD_OFFSETS)
                    .as("the amount field sits at offset 132 for eleven bytes, and every later offset "
                            + "depends on that")
                    .contains(new FieldOffset("DALYTRAN-AMT", 132, AMOUNT_FIELD_WIDTH))
                    .contains(new FieldOffset("DALYTRAN-CARD-NUM", 262, XREF_CARD_NUMBER_WIDTH))
                    .contains(new FieldOffset("DALYTRAN-PROC-TS", 304,
                            TestDataFactory.TIMESTAMP_TEXT_WIDTH));
        }

        @Test
        @DisplayName("the filler contract is measured and not uniform: four fixtures pad with the space "
                + "character and the cross-reference fixture has no filler to pad")
        void theFillerContractIsMeasuredAndNotUniform() {
            for (final String image : TestDataFactory.fixedWidthRecords(DALYTRAN_FIXTURE_NAME,
                    TRANSACTION_WIDTH)) {
                assertThat(image.substring(TRANSACTION_WIDTH - LANDING_FILLER_WIDTH))
                        .as("the landing layout's trailing filler is the space character")
                        .isEqualTo(String.valueOf(TestDataFactory.SPACE_FILLER)
                                .repeat(LANDING_FILLER_WIDTH));
            }
            assertThat(TestDataFactory.ACCOUNT.fillerCharacter())
                    .isEqualTo(TestDataFactory.SPACE_FILLER);
            assertThat(TestDataFactory.CARD.fillerCharacter()).isEqualTo(TestDataFactory.SPACE_FILLER);
            assertThat(TestDataFactory.CUSTOMER.fillerCharacter())
                    .isEqualTo(TestDataFactory.SPACE_FILLER);
            assertThat(TestDataFactory.CARD_CROSS_REFERENCE_FIXTURE.hasFiller())
                    .as("the delivered cross-reference form has no filler at all, and unifying it with "
                            + "the other four for tidiness would break its own comparison")
                    .isFalse();
            assertThat(TestDataFactory.CARD_CROSS_REFERENCE_DATASET.fillerLength())
                    .as("only the mainframe-encoded form carries filler")
                    .isEqualTo(CARD_XREF_DATASET_FILLER);
        }

        /**
         * Builds the amount image of the first delivered record by hand and compares it byte for byte
         * with the fixture's own bytes, then decodes it and compares the value with the one the reader
         * bound.
         *
         * <p>Nothing in either direction goes through the production encoder or any record mapper: the
         * overpunch tables are declared in this file, the digits are assembled with plain string
         * operations, and no scaling operation is performed anywhere - which is what makes this an
         * independent check rather than a restatement of the code under test.
         *
         * @throws Exception if the staged reader fails, which fails the test
         */
        @Test
        @DisplayName("the eleven-byte amount image is reproduced independently, sign overpunched into its "
                + "final byte, and matches both the fixture bytes and the bound entity")
        void theAmountImageIsReproducedIndependently() throws Exception {
            final String firstImage =
                    TestDataFactory.fixedWidthRecords(DALYTRAN_FIXTURE_NAME, TRANSACTION_WIDTH).get(0);
            final String amountField = landingField(firstImage, "DALYTRAN-AMT");
            assertThat(encodedWidth(amountField)).isEqualTo(AMOUNT_FIELD_WIDTH);

            final BigDecimal decoded = decodedAmount(amountField);
            assertThat(decoded.scale())
                    .as("every monetary field of the estate carries two decimal digits")
                    .isEqualTo(AMOUNT_DECIMAL_DIGITS);
            assertThat(zonedImage(decoded, AMOUNT_FIELD_WIDTH))
                    .as("the hand-built image must equal the delivered bytes exactly, including the "
                            + "overpunched final byte")
                    .isEqualTo(amountField);
            assertThat(POSITIVE_OVERPUNCH).hasSize(10).doesNotContain(NEGATIVE_OVERPUNCH.substring(0, 1));
            assertThat(NEGATIVE_OVERPUNCH).hasSize(10);
            assertThat(zonedImage(decoded.negate(), AMOUNT_FIELD_WIDTH))
                    .as("negating the value changes only the overpunched final byte")
                    .hasSize(AMOUNT_FIELD_WIDTH)
                    .startsWith(amountField.substring(0, AMOUNT_FIELD_WIDTH - 1))
                    .isNotEqualTo(amountField);

            assertThat(TestDataFactory.MONETARY_ROUNDING)
                    .as("no arithmetic statement in the estate specifies rounding, so a store into a "
                            + "two-decimal field truncates towards zero")
                    .isEqualTo(RoundingMode.DOWN);

            final DailyTransaction bound = readAll(allSixStaged().dalytranReader()).get(0);
            assertThat(bound.getDalytranAmt())
                    .as("the value the binding produced is the value the image encodes")
                    .isEqualByComparingTo(decoded);
            assertThat(bound.getDalytranId())
                    .isEqualTo(landingField(firstImage, "DALYTRAN-ID"));
        }

        @Test
        @DisplayName("all three hundred delivered records share one origination timestamp and carry an "
                + "entirely blank processing timestamp, so no assertion here may depend on either")
        void theSeededProcessingTimestampIsBlankOnEveryRecord() {
            final List<String> images =
                    TestDataFactory.fixedWidthRecords(DALYTRAN_FIXTURE_NAME, TRANSACTION_WIDTH);
            assertThat(images).hasSize(DALYTRAN_RECORDS);

            for (final String image : images) {
                assertThat(encodedWidth(image)).isEqualTo(TRANSACTION_WIDTH);
                assertThat(landingField(image, "DALYTRAN-ORIG-TS"))
                        .as("one origination value across the whole fixture, so it cannot separate "
                                + "records by date")
                        .isEqualTo(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP);
                assertThat(landingField(image, "DALYTRAN-PROC-TS"))
                        .as("the processing timestamp is written by the posting job, not by the fixture, "
                                + "so it is blank here on every record")
                        .isEqualTo(TestDataFactory.BLANK_PROCESSING_TIMESTAMP)
                        .isBlank()
                        .hasSize(TestDataFactory.TIMESTAMP_TEXT_WIDTH);
            }

            final DailyTransaction constructed = TestDataFactory
                    .dailyTransactionWithProcessingDate(PINNED_BUSINESS_DATE)
                    .id(RESERVED_LANDING_ID)
                    .build();
            assertThat(constructed.getDalytranProcTs())
                    .as("a record carrying a processing date is separately CONSTRUCTED; neither the seed "
                            + "script nor any fixture file is edited to produce one")
                    .isNotBlank()
                    .startsWith(PINNED_BUSINESS_DATE.toString());
        }

        @Test
        @DisplayName("each call builds a new reader, and the customer binding refuses a null sealer "
                + "rather than defaulting one that would leave two regulated fields unprotected")
        void eachCallBuildsANewReaderAndTheSealerIsNeverDefaulted() {
            final DailyTransactionReadJobConfig staged = allSixStaged();

            assertThat(staged.dalytranReader().orElseThrow())
                    .as("a reader holds position, so two launches must not observe one another")
                    .isNotSameAs(staged.dalytranReader().orElseThrow());
            assertThat(staged.dalytranReader().orElseThrow().getName())
                    .isEqualTo(FixedWidthFlatFileReaderFactory.DAILY_TRANSACTION_READER_NAME)
                    .isNotEqualTo(FixedWidthFlatFileReaderFactory.TRANSACTION_READER_NAME);
            assertThat(staged.tranfileReader().orElseThrow().getName())
                    .isEqualTo(FixedWidthFlatFileReaderFactory.TRANSACTION_READER_NAME);
            assertThatNullPointerException()
                    .isThrownBy(() -> staged.custfileReader(null))
                    .withMessageContaining("regulatedFieldSealer");
        }

        /**
         * Asserts one fixture's record count and every record's encoded width.
         *
         * @param fileName the fixture on this module's own test class path
         * @param width the verified encoded width of one record
         * @param records the number of records the fixture carries
         */
        private void assertFixtureWidth(final String fileName, final int width, final int records) {
            final List<String> images = TestDataFactory.fixedWidthRecords(fileName, width);
            assertThat(images)
                    .as("%s carries %d record(s) of %d encoded bytes", fileName, records, width)
                    .hasSize(records);
            for (final String image : images) {
                assertThat(encodedWidth(image))
                        .as("a record of %s is measured in encoded bytes and is never trimmed", fileName)
                        .isEqualTo(width);
            }
        }
    }

    // =============================================================================================
    // 4 - THE TWO-LEVEL FILE-STATUS MODEL, TESTED AS TWO LEVELS
    // =============================================================================================

    @Nested
    @Order(4)
    @DisplayName("4 - the two-level status model, asserted as two levels and never collapsed, with the "
            + "diagnostic emitted before the abend")
    final class TheTwoLevelFileStatusModel {

        /** Creates the nest. */
        TheTwoLevelFileStatusModel() {
        }

        /**
         * Level one, the success arm: a raw success code normalises to the coarse continue value, and the
         * only way that is observable is that the record was counted and the pass carried on.
         */
        @Test
        @DisplayName("level one, success: a raw success status normalises to the coarse continue value, "
                + "so the record is counted and the loop carries on")
        void levelOneMapsSuccessToTheCoarseContinueValue() {
            final List<DailyTransactionVerification> observed = new ArrayList<>();
            final DailyTransaction record = TestDataFactory.dailyTransaction()
                    .id(RESERVED_LANDING_ID)
                    .cardNumber(firstSeededCardNumber())
                    .build();

            final DailyTransactionReadResult result =
                    readService.execute(List.of(record), observed::add);

            assertThat(FileStatus.SUCCESS.getCode())
                    .as("the raw code the read reports on its success arm")
                    .isEqualTo("00");
            assertThat(FileStatus.SUCCESS.isSuccess()).isTrue();
            assertThat(result.recordsRead())
                    .as("the coarse continue value is what lets the record be counted at all")
                    .isEqualTo(1);
            assertThat(result.returnCode())
                    .as("a pass that only ever saw success and end of file terminates on zero")
                    .isZero();
            assertThat(observed).hasSize(2);
            assertThat(errorDiagnostics())
                    .as("nothing on the success arm is an error, so no error diagnostic is emitted")
                    .isEmpty();
        }

        @Test
        @DisplayName("level one, end of file: a raw end-of-file status normalises to the coarse "
                + "end-of-file value, which is never an error - collapsing the two would abend every run")
        void levelOneMapsEndOfFileToItsOwnCoarseValue() {
            final List<DailyTransactionVerification> observed = new ArrayList<>();

            final DailyTransactionReadResult result =
                    readService.execute(List.<DailyTransaction>of(), observed::add);

            assertThat(FileStatus.END_OF_FILE.getCode()).isEqualTo("10");
            assertThat(FileStatus.END_OF_FILE.isEndOfFile()).isTrue();
            assertThat(FileStatus.END_OF_FILE.isSuccess())
                    .as("end of file is neither success nor error; it is its own coarse value")
                    .isFalse();
            assertThat(result.recordsRead()).isZero();
            assertThat(result.recordsVerified()).isZero();
            assertThat(result.verificationPasses())
                    .as("the verification block sits outside the end-of-file guard, so it runs once even "
                            + "over an empty input")
                    .isEqualTo(1);
            assertThat(result.returnCode())
                    .as("an empty input completes normally rather than abending")
                    .isZero();
            assertThat(observed).hasSize(1);
            assertThat(observed.get(0).afterEndOfFile()).isTrue();
        }

        /**
         * Level one, the error arm, and level two's abend branch, in one observation.
         *
         * <p>A source element carrying no record image is neither a record nor end of file, so the read
         * paragraph has only its error arm left for it. That is the one route into the error arm that
         * needs neither a mocked gateway nor a broken server, and it is the route the translated read
         * itself defines.
         */
        @Test
        @DisplayName("level one, error, and level two's abend branch: anything that is neither success "
                + "nor end of file logs the error literal, then the raw status, then abends - in that order")
        void levelOneMapsEverythingElseToErrorAndLevelTwoAbendsAfterEmitting() {
            final List<DailyTransaction> sourceWithNoRecordImage = new ArrayList<>();
            sourceWithNoRecordImage.add(null);

            final AbendException thrown = catchThrowableOfType(AbendException.class,
                    () -> readService.execute(sourceWithNoRecordImage, verification -> { }));

            assertThat(thrown).as("the error arm abends; it does not skip and it does not reject")
                    .isNotNull();

            final List<String> errors = errorDiagnostics();
            final int literalIndex = indexOfDiagnosticContaining(errors,
                    "ERROR READING DAILY TRANSACTION FILE");
            final int statusIndex =
                    indexOfDiagnosticContaining(errors, FileStatusException.DISPLAY_PREFIX);
            final int abendIndex = indexOfDiagnosticContaining(errors, "ABENDING PROGRAM");

            assertThat(literalIndex)
                    .as("the failing site displays its own literal first")
                    .isLessThan(statusIndex);
            assertThat(statusIndex)
                    .as("the raw two-character status is displayed before the abend, never after: on a "
                            + "mainframe the diagnostic reached the operator whether or not anything "
                            + "survived the abend")
                    .isLessThan(abendIndex);
            assertThat(errors.get(statusIndex))
                    .as("the status reported is the raw two-character code, not a rendered value")
                    .contains(FileStatus.PERMANENT_ERROR.getCode())
                    .contains(DailyTransactionReadJobConfig.DD_DALYTRAN);
            assertThat(errors.get(abendIndex))
                    .contains(AbendException.BATCH_ABEND_CODE)
                    .contains(DailyTransactionReadJobConfig.PROGRAM_NAME);
            assertThat(diagnostics())
                    .as("the abend routine closes nothing: the six closes at source lines 188 to 193 and "
                            + "the end announcement at 195 sit in the mainline after the read loop, and the "
                            + "abort ends the run unit inside the routine that reported the failure, so "
                            + "none of them is reached - the faithful assertion is their absence")
                    .noneMatch(line -> line.contains(END_OF_EXECUTION_MARKER));
        }

        @Test
        @DisplayName("the abend carries the code the exception itself owns and a context mirroring the "
                + "legacy four-part structure: four, eight, fifty and seventy-two bytes")
        void theAbendCarriesTheExceptionsOwnCodeAndTheLegacyContextWidths() {
            final List<DailyTransaction> sourceWithNoRecordImage = new ArrayList<>();
            sourceWithNoRecordImage.add(null);

            final AbendException thrown = catchThrowableOfType(AbendException.class,
                    () -> readService.execute(sourceWithNoRecordImage, verification -> { }));

            assertThat(thrown).isNotNull();
            assertThat(thrown.code())
                    .as("the abend code is taken from the exception's own constant and is restated "
                            + "nowhere, here or in the translated program")
                    .isEqualTo(AbendException.BATCH_ABEND_CODE);
            assertThat(thrown.code())
                    .as("the batch code and the online code are different values and must not be "
                            + "interchanged")
                    .isNotEqualTo(AbendException.ONLINE_ABEND_CODE);
            assertThat(thrown.culprit())
                    .as("the culprit is the eight-character legacy member name")
                    .isEqualTo(DailyTransactionReadJobConfig.PROGRAM_NAME)
                    .hasSize(AbendException.CULPRIT_LENGTH);
            assertThat(thrown.reason()).isNotBlank();

            assertThat(AbendException.CODE_LENGTH + AbendException.CULPRIT_LENGTH
                    + AbendException.REASON_LENGTH + AbendException.MESSAGE_LENGTH)
                    .as("four plus eight plus fifty plus seventy-two is the legacy abend work area")
                    .isEqualTo(AbendException.CONTEXT_LENGTH)
                    .isEqualTo(134);
            assertThat(encodedWidth(thrown.toFixedWidthContext()))
                    .as("the rendered context measures the legacy width in encoded bytes")
                    .isEqualTo(AbendException.CONTEXT_LENGTH);
        }

        @Test
        @DisplayName("the twelve open and close paragraphs have no end-of-file arm at all, which is why "
                + "the read's three-way normalisation is kept separate from their two-way one")
        void theOpenAndCloseParagraphsHaveNoEndOfFileArm() {
            assertThat(PARAGRAPH_INVENTORY.stream()
                    .filter(unit -> unit.group() == ParagraphGroup.OPEN
                            || unit.group() == ParagraphGroup.CLOSE)
                    .count())
                    .as("twelve paragraphs share one two-way normalisation, and the single read "
                            + "paragraph owns the only three-way one")
                    .isEqualTo(12L);
            assertThat(PARAGRAPH_INVENTORY.stream()
                    .filter(unit -> unit.group() == ParagraphGroup.PROCESSING)
                    .count())
                    .isEqualTo(3L);
            assertThat(COARSE_RESULT_REFERENCES)
                    .as("the coarse result is referenced this many times across the estate, which is the "
                            + "measure of how load-bearing the end-of-file-versus-error distinction is")
                    .isEqualTo(223);
        }

        @Test
        @DisplayName("only the status values the source actually compares are exercised: the two codes "
                + "earlier specification prose cites are declared vocabulary this program never reports")
        void theTwoCodesEarlierProseCitesAreNeverReported() {
            final List<DailyTransactionVerification> observed = new ArrayList<>();

            readService.execute(List.<DailyTransaction>of(), observed::add);

            assertThat(FileStatus.fromCode(FileStatus.DUPLICATE_KEY.getCode()))
                    .as("a duplicate-key value exists in the declared vocabulary")
                    .contains(FileStatus.DUPLICATE_KEY);
            assertThat(FileStatus.fromCode(FileStatus.FILE_NOT_FOUND.getCode()))
                    .as("a file-not-found value exists in the declared vocabulary")
                    .contains(FileStatus.FILE_NOT_FOUND);
            assertThat(FileStatus.DUPLICATE_KEY.isSuccess()
                    || FileStatus.DUPLICATE_KEY.isEndOfFile()
                    || FileStatus.FILE_NOT_FOUND.isSuccess()
                    || FileStatus.FILE_NOT_FOUND.isEndOfFile())
                    .as("both are declared but unexercised values: were either ever reported it would "
                            + "reach the error arm like any other unrecognised status, and no code path "
                            + "of this program branches on either")
                    .isFalse();
            assertThat(diagnostics().stream()
                    .filter(line -> line.contains(FileStatusException.DISPLAY_PREFIX))
                    .toList())
                    .as("a pass that saw only success and end of file reports no status line at all, so "
                            + "no assertion here can depend on a status the source never compares; the "
                            + "discrepancy against the earlier specification prose is recorded rather "
                            + "than acted on")
                    .isEmpty();
            assertThat(List.of(FileStatus.SUCCESS.getCode(), FileStatus.END_OF_FILE.getCode(),
                            FileStatus.RECORD_NOT_FOUND.getCode()))
                    .as("success, end of file and record-not-found are the three the estate compares")
                    .containsExactly("00", "10", "23");
            assertThat(observed).hasSize(1);
        }

        /**
         * A staged dataset that names nothing is the other route to an abend, and it emits in the same
         * order.
         *
         * <p>The location is deliberately unresolvable so that the failure is the file's and not the
         * server's, and the diagnostic it produces names the operation, the resource and a raw status
         * drawn from the estate's own vocabulary rather than an invented one.
         */
        @Test
        @DisplayName("a staged dataset that cannot be opened is reported with its raw status and only "
                + "then abended, and the pass is never entered")
        void aStagedDatasetThatCannotBeOpenedIsReportedThenAbended() {
            final DailyTransactionReadJobConfig unresolvable =
                    staged(ABSENT_FIXTURE, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED);

            final AbendException thrown =
                    catchThrowableOfType(AbendException.class, unresolvable::runExtractPass);

            assertThat(thrown).isNotNull();
            assertThat(thrown.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
            assertThat(thrown.reason())
                    .contains(FileStatus.PERMANENT_ERROR.getCode())
                    .contains(DailyTransactionReadJobConfig.DD_DALYTRAN);

            final List<String> errors = errorDiagnostics();
            assertThat(errors).hasSize(2);
            assertThat(errors.get(0))
                    .as("the raw status comes first")
                    .contains(FileStatus.PERMANENT_ERROR.getCode())
                    .contains(DailyTransactionReadJobConfig.DD_DALYTRAN);
            assertThat(errors.get(1))
                    .as("the abend announcement comes second, carrying the exception's own code")
                    .contains(AbendException.BATCH_ABEND_CODE)
                    .contains(DailyTransactionReadJobConfig.PROGRAM_NAME);
            assertThat(diagnostics())
                    .as("the pass was never entered, so the mainline never announced itself")
                    .noneMatch(line -> line.contains(START_OF_EXECUTION_MARKER));
        }
    }

    // =============================================================================================
    // 5 - THE THREE PROCESSING PARAGRAPHS, OVER THE REAL MIGRATED AND SEEDED SERVER
    // =============================================================================================

    @Nested
    @Order(5)
    @DisplayName("5 - the three processing paragraphs over the real seed: three hundred records read to "
            + "end of file in ascending key order, both keyed reads, and nothing written")
    final class TheThreeProcessingParagraphs {

        /** Creates the nest. */
        TheThreeProcessingParagraphs() {
        }

        @Test
        @DisplayName("the get-next reads the whole seeded input to end of file, in ascending order of the "
                + "driving stream's key, and every record resolves both of its keyed reads")
        void theGetNextReadsTheWholeSeededInputInAscendingKeyOrder() {
            final List<DailyTransactionVerification> observed = new ArrayList<>();

            final DailyTransactionReadResult result = readService.execute(observed::add);

            assertThat(result.recordsRead())
                    .as("every seeded landing row is read, and end of file ends the loop cleanly")
                    .isEqualTo(DALYTRAN_RECORDS);
            assertThat(result.recordsVerified()).isEqualTo(DALYTRAN_RECORDS);
            assertThat(result.verificationPasses())
                    .as("one pass per record plus the one the loop makes after end of file, over the "
                            + "record area the previous read left in place")
                    .isEqualTo(DALYTRAN_RECORDS + 1);
            assertThat(result.cardsNotVerified())
                    .as("every seeded card number resolves through the cross-reference")
                    .isZero();
            assertThat(result.accountsNotFound())
                    .as("every resolved cross-reference names an account the master holds")
                    .isZero();
            assertThat(result.returnCode()).isZero();
            assertThat(result.allRecordsVerified()).isTrue();

            final List<String> expectedAscending = dailyTransactionRepository
                    .findAll(Sort.by(Sort.Direction.ASC, KEY_PROPERTY)).stream()
                    .map(DailyTransaction::getDalytranId)
                    .toList();
            assertThat(expectedAscending).hasSize(DALYTRAN_RECORDS).isSorted();
            assertThat(observed.stream()
                    .filter(verification -> !verification.afterEndOfFile())
                    .map(DailyTransactionVerification::dalytranId)
                    .toList())
                    .as("the relational scan imposes the ascending order the legacy physical sequential "
                            + "read had, so an explicit ascending sort is what the expectation is built "
                            + "from rather than whatever order the server happened to return")
                    .containsExactlyElementsOf(expectedAscending);
            assertThat(observed.get(observed.size() - 1).afterEndOfFile())
                    .as("the trailing pass is the last one offered and is marked as such")
                    .isTrue();
            assertThat(observed.get(observed.size() - 1).dalytranId())
                    .as("the trailing pass re-verifies the last record read, because a read at end of "
                            + "file leaves its receiving item unchanged")
                    .isEqualTo(expectedAscending.get(expectedAscending.size() - 1));
        }

        @Test
        @DisplayName("the cross-reference lookup resolves a seeded card and names the account the "
                + "cross-reference holds, and the account read then finds that account")
        void bothKeyedReadsResolveForASeededCard() {
            final String seededCard = firstSeededCardNumber();
            final DailyTransaction record = TestDataFactory.dailyTransaction()
                    .id(RESERVED_LANDING_ID)
                    .cardNumber(seededCard)
                    .build();
            final String expectedAccount = expectedAccountOf(seededCard);

            final DailyTransactionVerification verification = readService.verify(record);

            assertThat(verification.cardVerified())
                    .as("the cross-reference holds this card, so the lookup takes its success arm")
                    .isTrue();
            assertThat(verification.xrefAcctId())
                    .as("the account identifier is the one the delivered cross-reference image carries, "
                            + "sliced out of the fixture rather than read back from the class under test")
                    .isEqualTo(expectedAccount);
            assertThat(verification.accountLookupAttempted())
                    .as("the account read is reached exactly when the card resolved")
                    .isTrue();
            assertThat(verification.accountFound()).isTrue();
            assertThat(verification.afterEndOfFile()).isFalse();
            assertThat(accountRepository.findById(expectedAccount))
                    .as("the account the cross-reference named exists, which is what the read found")
                    .isPresent();
        }

        @Test
        @DisplayName("a card no cross-reference holds takes the invalid-key arm: a warning and a skip, "
                + "with no reject code and no reject record, because this program has neither")
        void aCardNoCrossReferenceHoldsTakesTheInvalidKeyArm() {
            final DailyTransaction orphan = TestDataFactory.orphanDailyTransaction()
                    .id(RESERVED_LANDING_ID)
                    .build();

            final DailyTransactionVerification verification = readService.verify(orphan);

            assertThat(cardCrossReferenceRepository.findById(TestDataFactory.UNKNOWN_CARD_NUMBER))
                    .as("no seeded cross-reference holds this card, which is what makes the arm reachable")
                    .isEmpty();
            assertThat(verification.cardVerified()).isFalse();
            assertThat(verification.accountLookupAttempted())
                    .as("the account read is never reached for a card the cross-reference could not "
                            + "resolve, which is the source's own ordering of the two tests")
                    .isFalse();
            assertThat(verification.accountFound())
                    .as("a read that never happened found nothing, and that is different from a read "
                            + "that missed")
                    .isFalse();
            assertThat(verification.xrefAcctId()).isNull();
            assertThat(diagnostics())
                    .as("the arm warns and skips; it raises nothing")
                    .anyMatch(line -> line.contains("INVALID CARD NUMBER FOR XREF"));
            assertThat(errorDiagnostics())
                    .as("an unresolvable card is an ordinary outcome of this program, not an error")
                    .isEmpty();
            assertThat(transactionRepository.count())
                    .as("no reject record, no posted row - this program writes nothing at all")
                    .isEqualTo(TestDataFactory.SEEDED_TRANSACTION_COUNT);
        }

        /**
         * Writes one unbacked landing row, which the landing table's deliberate absence of referential
         * constraints is what permits, and drives the whole pass over it.
         *
         * <p>The reserved identifier sits above every seeded key, so the row is the last the ascending
         * scan delivers - which makes it the record the trailing post-end-of-file pass re-verifies, and
         * that is why two passes report an unresolved card for one unresolvable row. The row is removed
         * again whatever the outcome.
         */
        @Test
        @DisplayName("the landing table accepts a row no seeded card backs - it carries zero foreign keys "
                + "deliberately - and the whole pass reports it without writing anything")
        void theLandingTableAcceptsARowNoSeededCardBacks() throws SQLException {
            final DailyTransaction orphan = TestDataFactory.orphanDailyTransaction()
                    .id(RESERVED_LANDING_ID)
                    .build();
            dailyTransactionRepository.save(orphan);

            assertThat(catalogueCount(FOREIGN_KEY_COUNT_SQL, LANDING_TABLE))
                    .as("a row naming a card, an account and a customer that no seeded row backs is "
                            + "writable only because the landing table constrains none of the three")
                    .isZero();
            assertThat(dailyTransactionRepository.findById(RESERVED_LANDING_ID)).isPresent();

            final List<DailyTransactionVerification> observed = new ArrayList<>();
            final DailyTransactionReadResult result = readService.execute(observed::add);

            assertThat(result.recordsRead()).isEqualTo(DALYTRAN_RECORDS + 1);
            assertThat(result.verificationPasses()).isEqualTo(DALYTRAN_RECORDS + 2);
            assertThat(result.cardsNotVerified())
                    .as("the unbacked row is the highest key, so it is also the record the trailing "
                            + "post-end-of-file pass re-verifies: one row, two reported passes")
                    .isEqualTo(2);
            assertThat(result.accountsNotFound())
                    .as("the account read is never reached for it, so nothing is counted as an account "
                            + "miss - a read that never happened is not a read that failed")
                    .isZero();
            assertThat(result.returnCode())
                    .as("an unresolvable card does not abend and does not change the terminal status")
                    .isZero();
            assertThat(observed)
                    .filteredOn(verification -> RESERVED_LANDING_ID.equals(verification.dalytranId()))
                    .as("both passes over the unbacked row report it unverified")
                    .hasSize(2)
                    .allMatch(verification -> !verification.cardVerified())
                    .allMatch(verification -> !verification.accountLookupAttempted());
            assertThat(transactionRepository.count())
                    .as("the member has no write statement, so the posted table is untouched")
                    .isEqualTo(TestDataFactory.SEEDED_TRANSACTION_COUNT);
        }

        @Test
        @DisplayName("a resolved cross-reference cannot name an absent account while the schema's "
                + "referential constraints stand, so the account invalid-key arm has no persisted route")
        void aResolvedCrossReferenceCannotNameAnAbsentAccount() throws SQLException {
            assertThat(catalogueCount(FOREIGN_KEY_COUNT_SQL, "card_cross_reference"))
                    .as("the cross-reference names an existing card, an existing account and an existing "
                            + "customer, which is what makes a dangling account identifier impossible")
                    .isEqualTo(3);
            assertThat(accountRepository.findById(TestDataFactory.UNKNOWN_ACCOUNT_ID))
                    .as("the identifier reserved for an absent account is genuinely absent")
                    .isEmpty();
            for (final CardCrossReference crossReference : cardCrossReferenceRepository.findAll()) {
                assertThat(accountRepository.findById(crossReference.getXrefAcctId()))
                        .as("every seeded cross-reference names an account the master holds, so the "
                                + "account read's invalid-key arm is unreachable from persisted state "
                                + "and is covered by the translated program's own specification")
                        .isPresent();
            }
        }

        @Test
        @DisplayName("the schema creates no surrogate key: every identifier is the business key taken "
                + "from the record image, and no sequence outside the framework's own exists")
        void theSchemaCreatesNoSurrogateKey() throws SQLException {
            assertThat(catalogueCount(GENERATED_COLUMN_COUNT_SQL, LANDING_TABLE))
                    .as("no identity column and no defaulted column on the landing table, so the "
                            + "sixteen-character record key is the primary key")
                    .isZero();
            assertThat(catalogueCount(GENERATED_COLUMN_COUNT_SQL, POSTED_TABLE))
                    .as("the posted table derives a new identifier from the highest existing one, never "
                            + "from a counter that diverges after the first rolled-back attempt")
                    .isZero();
            assertThat(catalogueCount(APPLICATION_SEQUENCE_COUNT_SQL))
                    .as("the migrations create no sequence at all; the framework's own metadata "
                            + "sequences are excluded, because counting them would fail this for a "
                            + "reason that has nothing to do with the record schema")
                    .isZero();
        }
    }

    // =============================================================================================
    // 6 - THE STEP TIMER, READ FOR PRESENCE AND SHAPE ONLY
    // =============================================================================================

    @Nested
    @Order(6)
    @DisplayName("6 - the step timer joins the batch tier's one metric family, read for presence and "
            + "shape and never for a figure")
    final class TheStepTimerShape {

        /** Creates the nest. */
        TheStepTimerShape() {
        }

        @Test
        @DisplayName("a completed pass and an abended pass are both recorded under the shared timer name "
                + "with the shared tag keys, so this program appears beside the nine wired ones")
        void bothOutcomesAreRecordedUnderTheSharedTimerName() {
            final long completedBefore = timedPasses(DailyTransactionReadJobConfig.OUTCOME_COMPLETED);
            final long abendedBefore = timedPasses(DailyTransactionReadJobConfig.OUTCOME_ABENDED);

            assertThatCode(configuration::runExtractPass)
                    .as("the relational pass over the seeded input completes")
                    .doesNotThrowAnyException();
            assertThat(timedPasses(DailyTransactionReadJobConfig.OUTCOME_COMPLETED))
                    .as("one more completed pass, counted and never timed against a target")
                    .isEqualTo(completedBefore + 1);

            final DailyTransactionReadJobConfig unresolvable =
                    staged(ABSENT_FIXTURE, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED);
            assertThat(catchThrowableOfType(AbendException.class, unresolvable::runExtractPass))
                    .isNotNull();
            assertThat(timedPasses(DailyTransactionReadJobConfig.OUTCOME_ABENDED))
                    .as("an abended pass is recorded under its own outcome rather than being lost")
                    .isEqualTo(abendedBefore + 1);

            assertThat(meterRegistry.find(DailyTransactionReadJobConfig.STEP_TIMER_NAME).timers())
                    .as("the timer name is the batch tier's shared program-lifecycle name, so this "
                            + "program joins one metric family rather than starting a second")
                    .isNotEmpty()
                    .allSatisfy(timer -> assertThat(
                            timer.getId().getTag(DailyTransactionReadJobConfig.TAG_STEP))
                            .isEqualTo(DailyTransactionReadJobConfig.PROGRAM_NAME));
            assertThat(DailyTransactionReadJobConfig.OUTCOME_COMPLETED)
                    .isNotEqualTo(DailyTransactionReadJobConfig.OUTCOME_ABENDED);
        }
    }

    // =============================================================================================
    // THE RUNTIME CONTEXT
    // =============================================================================================

    /**
     * The narrowest context this job needs: batch orchestration, persistence, metrics, and exactly the
     * collaborators the configuration and the translated program are built against.
     *
     * <p>The migrated schema and the container address arrive from the shared base class, so nothing
     * about the data source is declared here. Schema validation is inherited from the shared test
     * profile rather than restated, which is what makes a mapping that has drifted from the migrated
     * table a refusal to start rather than a silent divergence. The migration itself is switched off in
     * this context because the base class has already brought the server to the head of the delivered
     * set, and running it a second time would be redundant work rather than a second owner.
     *
     * <p>No enabling annotation for batch appears here or anywhere in the module: under this framework
     * generation the batch auto-configuration backs off when that annotation is present, so adding it
     * would switch off the very infrastructure this context depends on.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({DailyTransactionReadJobConfig.class, BatchConfig.class,
            FixedWidthFlatFileReaderFactory.class, DailyTransactionReadService.class,
            AbendService.class})
    @EnableConfigurationProperties(AwsProperties.class)
    @EnableJpaRepositories(basePackageClasses = DailyTransactionRepository.class)
    @EntityScan(basePackageClasses = DailyTransaction.class)
    static class JobContext {

        /** Creates the configuration. */
        JobContext() {
        }

        /**
         * Publishes the shared staging boundary without adding an object-store dependency to this
         * PostgreSQL-focused slice.
         *
         * <p>It holds nothing, which is what makes a class-path location resolve through the resource
         * loader exactly as an unstaged deployment resolves one. The object-store edge itself is
         * exercised against the emulator by the specifications that own it.
         *
         * @return a staging boundary that holds no object
         */
        @Bean
        BatchStagingArea batchStagingArea() {
            return mock(BatchStagingArea.class);
        }
    }
}
