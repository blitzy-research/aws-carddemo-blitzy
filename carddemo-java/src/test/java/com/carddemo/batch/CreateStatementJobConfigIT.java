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
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.batch.step.StatementProcessor;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.config.JpaAuditConfig;
import com.carddemo.domain.Card;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.InterestCalculationService;
import com.carddemo.service.InterestGroupTransactionBoundary;
import com.carddemo.service.PostingStageTransactionBoundary;
import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.service.StatementDataAccessService;
import com.carddemo.service.StatementGenerationService;
import com.carddemo.service.StatementGenerationService.StatementRun;
import com.carddemo.service.StatementLineSummary;
import com.carddemo.service.StatementOutputSink;
import com.carddemo.service.StatementTransactionSource;
import com.carddemo.service.TransactionPostingService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.IsolatedStagingRoot;
import com.carddemo.support.TestDataFactory;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.sns.core.SnsOperations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
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
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Container-backed verification of {@link CreateStatementJobConfig} against a real PostgreSQL 16
 * server, driven as the last link of the batch pipeline rather than in isolation.
 *
 * <p>Provenance of every legacy figure cited below: checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is a provenance string for the
 * traceability-matrix header only - it is not carried uniformly by the estate's members, so nothing
 * here asserts it against one. No job-stream, program, copybook or resource-definition source line
 * is transcribed anywhere in this file: the estate is cited by step name, data-definition name,
 * program name, width, offset, count, status code and key geometry, plus the handful of contract
 * literals that appear byte for byte in the produced output.
 *
 * <h2>What this class owns, and what it deliberately does not</h2>
 * The sibling unit test already pins the published constants, the reprojection arithmetic, the
 * comparator over a temporary directory, and the transient work resource's own semantics, all in
 * isolation. This class exists for what only a real server and a real job execution can show: that
 * four steps actually execute in order behind three gates, that the gates refuse as well as admit,
 * that the ordering applied to rows read out of the database is the character ordering the member
 * declares, that the reprojection's deliberate defect survives a round trip through the produced
 * artefact, and that the generator's dispatcher visits its phases in the derived execution order.
 *
 * <p>It is <strong>not</strong> a second end-to-end test. The chained run and the byte-array
 * comparison against the shared eighty-byte and one-hundred-byte golden files belong to
 * {@code e2e/BatchPipelineE2ETest}, and nothing here reads those goldens. Every expectation in this
 * class is built from plain string arithmetic written out below.
 *
 * <h2>Why posting, interest and consolidation run first, as setup</h2>
 * This job consumes the consolidated transaction store, and that store does not exist until the
 * three jobs ahead of it have run. The {@code transaction} table is seeded <em>empty</em>, and all
 * fifty seeded category-balance rows carry the same balance image, which decodes to zero - so an
 * interest run reaching an unpopulated store first would produce nothing observable and this job
 * would then order and reproject an empty result. Posting, interest accrual and consolidation are
 * therefore launched by their own name constants, in pipeline order, as setup for the run under
 * test. Their own contracts are asserted by their own tests; here they are only the producers of the
 * store this job reads.
 *
 * <h2>The three gates are the STRICT form, and the sibling backup job's gate is not</h2>
 * Three of the estate's four gated steps live in this one member, at its second, third and fourth
 * steps. All three carry the strict form - the condition-code test bypasses the step whenever the
 * highest return code so far is anything other than zero, so the guarded step executes only when
 * every earlier step returned exactly zero.
 *
 * <p><strong>The backup job's single gate is measurably a different gate and must never be
 * conflated with these three.</strong> Read at {@code app/jcl/TRANBKP.jcl:51}, its condition is the
 * tolerant "four, less-than" form, which bypasses only once the highest code so far exceeds four and
 * therefore <em>tolerates</em> codes up to and including four. The strict form asserted here would
 * refuse a step that the tolerant form admits, so a helper that implements only one of the two forms
 * cannot serve both members faithfully. This test asserts the strict semantics for this member's
 * three gates and asserts nothing about the tolerant one; the divergence, and the fact that the
 * shared decider currently carries a single strict constant, is a decision-log candidate that
 * belongs in {@code docs/decision-log.md} - a file this test neither creates nor edits.
 *
 * <h2>The reprojection defect this test reproduces rather than corrects</h2>
 * The ordering specification is positional, not symbolic: two keys, both character-typed, both
 * ascending, the first sixteen bytes at one-based position 263 and the second sixteen bytes at
 * one-based position 1. The reprojection that follows it assembles three segments - the sixteen-byte
 * card number moved to the front, then the record's leading 262 bytes shifted right behind it, then
 * a fifty-byte slice taken from one-based position 279. Sixteen plus 262 plus fifty is 328 bytes
 * projected out of a 350-byte input, and the result is blank-padded back to 350.
 *
 * <p>Within the transaction layout the origination timestamp occupies one-based bytes 279 to 304 and
 * the processing timestamp occupies 305 to 330. The fifty-byte slice therefore carries the
 * origination timestamp whole but only bytes 305 to 328 of the processing timestamp: <strong>exactly
 * two processing-timestamp bytes are truncated</strong>, and the twenty-byte trailing filler is
 * dropped outright. That is a fidelity defect of the legacy stream. It is <strong>reproduced
 * exactly</strong> here - not corrected, not widened, not restored - and the assertion below proves
 * the two bytes are gone rather than merely that the record is the right width. It is a decision-log
 * candidate for the same reason.
 *
 * <p>The cheapest available proof that the three segments were assembled in the right order is the
 * work resource's own key geometry: the absorbed definition step declares a thirty-two byte key at
 * offset zero, so the projected record's leading thirty-two bytes must be the sixteen-byte card
 * number followed by the sixteen-byte identifier. That is asserted explicitly.
 *
 * <h2>Why the comparator must stay private to the job configuration</h2>
 * One-based position 263 is typed as <em>character</em> data by this member and as <em>zoned
 * decimal</em> by the transaction-report member. One field, one offset, two declared types - which is
 * the definitive reason a comparator may never be shared between the two jobs. A verb census across
 * all twenty-eight programs found zero internal sort and zero merge statements, so every ordering
 * specification in the estate is external and job-local. The assertion below proves the ordering is
 * lexicographic on the raw character image by placing two cards whose lexicographic order is the
 * reverse of their numeric order, so a numeric or decoded comparison fails it.
 *
 * <h2>The dispatcher is a state machine, and its execution order is not its source order</h2>
 * The generator is neither a loop nor a chain of nested calls. A single eight-character control
 * variable selects the next phase, and a dispatcher paragraph re-branches on it through six clauses
 * whose order is the contract; after each phase the control variable is set and control re-enters the
 * dispatcher. The derived execution order is transaction-input open, then the read-all-transactions
 * phase, then cross-reference open, customer open, account open, and finally the mainline - even
 * though the paragraphs are laid out numerically with the read-all phase far below the opens.
 * Execution order and source order genuinely diverge, which is why only an explicit state enum
 * driven by a loop over an ordered switch, re-entered after every transition, is faithful; nested
 * calls cannot reproduce re-entry into a dispatcher after a state change. Four of the six clauses
 * reach their phase through a self-modifying idiom in the original; only the resulting state
 * transition is modelled here, and the idiom itself is neither emulated nor described further.
 *
 * <h2>Widths, and the conflict this member leaves behind</h2>
 * The plain statement record is eighty bytes and the markup statement record is one hundred. The
 * member declares the markup destination twice and disagrees with itself - eighty bytes at one line
 * and one hundred at another. <strong>It is resolved to one hundred</strong>, matching the width the
 * emitting program actually writes, and the conflict is a decision-log candidate. The member also
 * carries a garbled overtyped line whose intent is recovered from the surrounding data definitions;
 * the corruption is reproduced nowhere in this file, in no literal, no comment and no test name.
 *
 * <p>Six byte-identical eighty-character rule lines are emitted per statement, from three declared
 * positions of which two are written twice. They are separate emissions at separate positions and
 * must not be de-duplicated: collapsing them would silently shorten every statement. The count of
 * six is asserted. The markup fixed lines are literal constants emitted in source order and include a
 * malformed tag carrying two adjacent spaces after its element name; it is asserted exactly as it is
 * emitted and is not repaired, because byte equivalence is the contract and well-formed markup is
 * not. No templating engine takes part anywhere in the path.
 *
 * <h2>A structural bound, not a business rule</h2>
 * The generator declares a card table of fifty-one entries, a per-card transaction table of ten, and
 * a per-card counter table of fifty-one - fifty-one cards by ten transactions. That is the shape of
 * the legacy working storage and nothing more. It is recorded here as structural and is deliberately
 * not asserted as a limit on what the migrated job may process.
 *
 * <h2>Harness</h2>
 * The shared base owns the one PostgreSQL 16 server, publishes its address into any context-booting
 * subclass, and pins the clock; this class declares no container, no property source and no profile
 * of its own, and adds only the context annotation. The absorbed definition step leaves no schema
 * artefact behind, which is asserted by reading the live table roster and the applied migration
 * versions back off the server.
 *
 * @see CreateStatementJobConfig
 */
@SpringBootTest(classes = CreateStatementJobConfigIT.JobContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none", "spring.batch.job.enabled=false",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false",
                // One staging root serves all four jobs, so the pipeline chains through the
                // filesystem exactly as it does in the application. All four keys are registered from
                // registerIsolatedStagingDirectory rather than written here: the four @Value bindings are
                // resolved while the context starts, and the root has to carry this process's own
                // identity, which no compile-time constant can.
                "carddemo.batch.combine-transactions.transaction-backup="
                        + CreateStatementJobConfigIT.BACKUP_INPUT_NAME,
                "carddemo.batch.combine-transactions.synthesized-transaction="
                        + CreateStatementJobConfigIT.SYNTHESIZED_INPUT_NAME})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("create-statement job, run against a real server as the pipeline's last link: four "
        + "steps behind three strict gates, a character ordering, and a reproduced 328-of-350 "
        + "reprojection")
final class CreateStatementJobConfigIT extends AbstractPostgresIT {

    /** The four keys the jobs of this slice read their staging root from, in pipeline order. */
    private static final List<String> STAGING_DIRECTORY_PROPERTIES = List.of(
            "carddemo.batch.post-transaction.staging-directory",
            "carddemo.batch.interest-calculation.staging-directory",
            "carddemo.batch.combine-transactions.staging-directory",
            "carddemo.batch.create-statement.staging-directory");

    /**
     * This specification's label within this process's private staging namespace.
     *
     * <p>It was a fixed expression naming a directory directly beneath the platform temporary directory,
     * which every run of this specification and every sibling clone on the host resolved identically. It is
     * now one segment beneath a namespace unique to this process, bound from a property callback. See
     * {@link IsolatedStagingRoot}.
     */
    static final String STAGING_LABEL = "create-statement-it";

    /**
     * The four staging-directory keys the pipeline's jobs read, all bound to the one isolated root.
     *
     * <p>Named here rather than repeated at the callback, so that a job added to this pipeline is visibly
     * either in this list or absent from it.
     */
    private static final List<String> STAGING_DIRECTORY_KEYS = List.of(
            "carddemo.batch.create-statement.staging-directory",
            "carddemo.batch.post-transaction.staging-directory",
            "carddemo.batch.interest-calculation.staging-directory",
            "carddemo.batch.combine-transactions.staging-directory");

    /** Relative name of the first consolidation input, resolved against the staging root. */
    static final String BACKUP_INPUT_NAME = "create-statement-it-transaction-backup.txt";

    /** Relative name of the second consolidation input, resolved against the staging root. */
    static final String SYNTHESIZED_INPUT_NAME = "create-statement-it-synthesized.txt";

    // -----------------------------------------------------------------------------------------------
    // THE RECORD GEOMETRY, RESTATED HERE RATHER THAN IMPORTED.
    //
    // Every figure below is the measured legacy figure, written out as its own literal. None of it is
    // read from the production layout owner, because an expectation that borrows the subject's own
    // arithmetic proves only that the subject agrees with itself. Offsets are zero-based for slicing;
    // the one-based positions the specification uses are named in the field comments.
    // -----------------------------------------------------------------------------------------------

    /** Posted-transaction record width. */
    private static final int RECORD_LENGTH = 350;

    /** Identifier field: one-based position 1, sixteen bytes - the second ordering key. */
    private static final int TRAN_ID_OFFSET = 0;

    /** Width shared by the identifier and the card number. */
    private static final int KEY_FIELD_LENGTH = 16;

    /** Type-code field, immediately behind the identifier in the source record. */
    private static final int TRAN_TYPE_OFFSET = 16;

    /** Type-code width. */
    private static final int TRAN_TYPE_LENGTH = 2;

    /** Category-code field, immediately behind the type code in the source record. */
    private static final int TRAN_CATEGORY_OFFSET = 18;

    /** Category-code width. */
    private static final int TRAN_CATEGORY_LENGTH = 4;

    /** Card-number field: one-based position 263, sixteen bytes - the first ordering key. */
    private static final int CARD_NUMBER_OFFSET = 262;

    /** Origination timestamp: one-based positions 279 through 304. */
    private static final int ORIGINATION_TIMESTAMP_OFFSET = 278;

    /** Width of either timestamp field. */
    private static final int TIMESTAMP_LENGTH = 26;

    /** Processing timestamp: one-based positions 305 through 330. */
    private static final int PROCESSING_TIMESTAMP_OFFSET = 304;

    /** Trailing filler: one-based positions 331 through 350, dropped by the reprojection. */
    private static final int TRAILING_FILLER_LENGTH = 20;

    /** Second reprojection segment: the record's leading 262 bytes, shifted right behind the card. */
    private static final int LEADING_SEGMENT_LENGTH = 262;

    /** Third reprojection segment: a fifty-byte slice taken from one-based position 279. */
    private static final int TIMESTAMP_SLICE_LENGTH = 50;

    /** Sixteen plus 262 plus fifty: the content the reprojection actually carries. */
    private static final int PROJECTED_CONTENT_LENGTH = 328;

    /** 350 less 328: the blanks the reprojection pads back with. */
    private static final int BLANK_PAD_LENGTH = 22;

    /** Key of the absorbed transient definition: thirty-two bytes at offset zero. */
    private static final int WORK_KEY_LENGTH = 32;

    /** Processing-timestamp bytes the fifty-byte slice cannot reach. The defect, in one figure. */
    private static final int TRUNCATED_PROCESSING_TIMESTAMP_BYTES = 2;

    /** Plain statement record width. */
    private static final int STATEMENT_RECORD_WIDTH = 80;

    /** Markup statement record width - the resolution of the member's self-disagreement. */
    private static final int HTML_RECORD_WIDTH = 100;

    /** Five legacy steps less the one absorbed definition step. */
    private static final int MIGRATED_STEP_COUNT = 4;

    /** Gated steps in this member: its second, third and fourth. */
    private static final int GATE_COUNT = 3;

    /**
     * Delivered migrations: three of schema, two of seed, and no sixth for the absorbed cluster.
     *
     * <p>The third schema script is the protected-value invariants, and it takes a <em>dotted</em> version
     * <strong>below</strong> both seed versions - {@code 2 < 2.2 < 3} - for the reasons
     * {@code docs/decision-log.md} DL-343 and DL-349 record. It is counted here because it is delivered;
     * what this constant asserts is that the transient cluster this job's legacy stream defined and
     * destroyed became an in-job result rather than a migration of its own.</p>
     */
    private static final int MIGRATION_VERSION_COUNT = 5;

    /** Rule-line emissions per statement: three declared positions, two of them written twice. */
    private static final int RULE_LINES_PER_STATEMENT = 6;

    /**
     * Declared width of a data-definition name, an eight-byte alphanumeric field.
     *
     * <p>Because the width is fixed, an echoed name is compared whole. Trimming one would hide a
     * padding regression rather than tolerate it.</p>
     */
    private static final int RECORD_TYPE_WIDTH = 8;

    /**
     * The four data-definition names the generator opens, written out here rather than read from the
     * collaborator that echoes them.
     *
     * <p>Data-definition names are contract metadata, so stating them independently costs nothing and
     * buys something real: the round trip below supplies one of these names and asserts the same name
     * comes back, and because the expectation originates here rather than in the collaborator, a
     * collaborator that renamed or reordered its own constants would be caught rather than agreed
     * with. The order is the order the phases open them in.</p>
     */
    private static final List<String> DECLARED_RECORD_TYPES =
            List.of("TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE");

    /** Structural bound of the legacy card table. Recorded, never asserted as a business limit. */
    private static final int STRUCTURAL_CARD_TABLE_ENTRIES = 51;

    /** Structural bound of the legacy per-card transaction table. Recorded, never asserted. */
    private static final int STRUCTURAL_TRANSACTIONS_PER_CARD = 10;

    // -----------------------------------------------------------------------------------------------
    // THE INDEPENDENT ORACLE. Every expectation is assembled here from plain string arithmetic. Not
    // one of the collaborators of this job - the two template holders, the generation service, the
    // assembly stage, the file handler, the decimal codec, or any record mapper - takes any part in
    // producing an expected value, because delegating to the subject would make the comparison
    // circular. The literals that do appear are output contract literals: they reach the produced
    // artefacts byte for byte, so carrying them across is what the parity assertion needs.
    // -----------------------------------------------------------------------------------------------

    /** Asterisks either side of the start banner's eighteen-character text. */
    private static final int START_BANNER_ASTERISKS = 31;

    /** Asterisks either side of the end banner's sixteen-character text. */
    private static final int END_BANNER_ASTERISKS = 32;

    /** The start banner, at the asymmetric 31/18/31 split the source declares. */
    private static final String START_BANNER = "*".repeat(START_BANNER_ASTERISKS)
            + "START OF STATEMENT" + "*".repeat(START_BANNER_ASTERISKS);

    /** The end banner, at the 32/16/32 split - deliberately not the start banner's split. */
    private static final String END_BANNER = "*".repeat(END_BANNER_ASTERISKS)
            + "END OF STATEMENT" + "*".repeat(END_BANNER_ASTERISKS);

    /** The rule line: eighty hyphens and nothing else. */
    private static final String RULE_LINE = "-".repeat(STATEMENT_RECORD_WIDTH);

    /**
     * The malformed table tag, carrying two adjacent spaces after its element name.
     *
     * <p>Written here exactly as the source emits it. It is not repaired, not collapsed and not
     * normalised: byte equivalence is the contract that the produced markup has to satisfy, and
     * well-formed markup is not.
     */
    private static final String MALFORMED_TABLE_TAG = "<table  align=\"center\" frame=\"box\" "
            + "style=\"width:70%; font:12px Segoe UI,sans-serif;\">";

    /** A markup literal from the document preamble, used to prove the preamble reached the output. */
    private static final String HTML_DOCUMENT_TYPE = "<!DOCTYPE html>";

    /** The markup document's closing element, the last record of a complete run. */
    private static final String HTML_DOCUMENT_CLOSE = "</html>";

    /** The closing paragraph tag every composed work line carries after its transferred value. */
    private static final String HTML_PARAGRAPH_CLOSE = "</p>";

    /**
     * The five character references that must never appear in the produced markup.
     *
     * <p>The emitting program encodes nothing, and neither does this translation (DL-209), so any of
     * these appearing in the artefact means a value was rewritten on its way to a fixed-width record.
     */
    private static final List<String> HTML_CHARACTER_REFERENCES =
            List.of("&amp;", "&lt;", "&gt;", "&quot;", "&#39;");

    /**
     * The dispatcher's derived <em>execution</em> order, written out as literals.
     *
     * <p>Not the source's paragraph order: the read-all phase is laid out far below the four opens yet
     * executes second, immediately after the transaction input is opened. The final entry is the
     * catch-all, which terminates the run rather than looping or falling into a phase.
     */
    private static final List<String> DERIVED_DISPATCH_ORDER =
            List.of("TRNXFILE", "READTRNX", "XREFFILE", "CUSTFILE", "ACCTFILE", "TERMINATED");

    // -----------------------------------------------------------------------------------------------
    // THE ORDERING PROBE. Three constructed rows across two cards whose lexicographic order is the
    // reverse of their numeric order, which is what makes the character-versus-decoded distinction
    // provable. The card number is alphanumeric in the layout, and the schema constrains it to sixteen
    // CHARACTERS rather than to sixteen digits, so a short space-padded value is representable in both.
    //
    // Exactly ONE card is added. The generator tabulates into a table of fifty-one card entries and the
    // delivered fixture already covers fifty cards, so one addition sits exactly on the legacy bound
    // and a second would push the run past it - which the legacy program refuses loudly rather than
    // truncating, as the capacity assertion at the end of this class proves in its own right.
    // -----------------------------------------------------------------------------------------------

    /**
     * Sorts <em>last</em> as characters because '9' follows the '0' every delivered card begins with,
     * and sorts <em>first</em> if read as a number, because nine is smaller than any sixteen-digit
     * value. That inversion is the whole point of the probe.
     */
    private static final String LEXICALLY_LAST_CARD = "9" + " ".repeat(KEY_FIELD_LENGTH - 1);

    /** Account the added probe card belongs to; seeded, so the card's own foreign key resolves. */
    private static final String PROBE_ACCOUNT_ID = "00000000001";

    /** On the delivered card, which sorts first as characters and last as a number. */
    private static final String PROBE_ON_DELIVERED_CARD_ID = "9900000000000001";

    /** On the added card, carrying the lower of that card's two identifiers. */
    private static final String PROBE_ON_ADDED_CARD_LOW_ID = "9900000000000002";

    /** On the added card, carrying the higher of that card's two identifiers. */
    private static final String PROBE_ON_ADDED_CARD_HIGH_ID = "9900000000000003";

    /** Probe origination timestamp: twenty-six characters, wholly inside the fifty-byte slice. */
    private static final String PROBE_ORIGINATION_TIMESTAMP = "2022-06-10-19.27.53.000000";

    /**
     * Probe processing timestamp: twenty-six characters whose final two are deliberately distinctive.
     *
     * <p>Those final two are the bytes the fifty-byte slice cannot reach. Choosing non-blank digits
     * for them is what makes the truncation assertion mean something rather than compare blanks with
     * blanks - the seeded fixture cannot serve here, because its processing-timestamp field is blank
     * in every one of its three hundred records.
     */
    private static final String PROBE_PROCESSING_TIMESTAMP = "2022-06-10-19.27.53.123456";

    /** Type code carried by every probe row, asserted inside the shifted leading segment. */
    private static final String PROBE_TYPE_CODE = "07";

    /** Category code carried by every probe row, asserted inside the shifted leading segment. */
    private static final String PROBE_CATEGORY_CODE = "0031";

    /** Amount carried by every probe row. Two decimal places, as the layout declares. */
    private static final BigDecimal PROBE_AMOUNT = new BigDecimal("41.83");

    /** A seeded card that carries a cross-reference row, so a statement is produced against it. */
    private static final String SEEDED_CARD_WITH_CROSS_REFERENCE = "0500024453765740";

    /** Identifiers of the records served to the generator through the constructed source. */
    private static final List<String> GENERATOR_PROBE_IDS =
            List.of("9910000000000001", "9910000000000002");

    /** The consolidation inputs' own identifiers, kept clear of every other probe range. */
    private static final List<String> CONSOLIDATION_IDS =
            List.of("9920000000000001", "9920000000000002");

    /** Ten all-digit characters, the shape the interest job's parameter card carried. */
    private static final String INTEREST_PARM_DATE_SUFFIX = "00";

    /**
     * Separator the repository's own newline-delimited sample data carries between records.
     *
     * <p>Used only for the posting job's <em>landing file</em>, which is the readable rendering of an
     * EBCDIC sequential dataset and is read through the line-oriented reader. No artefact this pipeline
     * <em>produces</em> carries it: every DD in the estate declares a fixed record format, which writes
     * no byte between two records. See {@code docs/decision-log.md} entry DL-213.
     */
    private static final String SAMPLE_DATA_SEPARATOR = "\n";

    /** Name of the observation the framework publishes for a step execution. */
    private static final String STEP_METER_NAME = "spring.batch.step";

    /**
     * The one clean pipeline run, established by {@link #establishTheConsolidatedStore()}.
     *
     * <h4>Why this is an instance field established by a lifecycle callback</h4>
     * It was a <em>static</em> field filled by a guarded {@code @BeforeEach}: the first test to run
     * established the pipeline, a static flag suppressed a second attempt, and twenty-four
     * {@code @Order}-ed tests then read whatever that first test left behind. Two things were wrong with
     * that. The fixture's existence depended on execution order, so a filtered run of a single test read
     * a null field; and the guard hand-rolled a fail-fast that the framework already provides.
     *
     * <p>The class is now {@code PER_CLASS}, so one instance serves every test and a non-static
     * {@code @BeforeAll} can reach the injected collaborators. The pipeline is established there, once,
     * unconditionally, before any test - which is what a class-level fixture is - and if it cannot be
     * established the framework reports that failure against the class and does not run the tests at all,
     * which is exactly the behaviour the guard was imitating.
     */
    private CleanRun cleanRun;

    /**
     * The parameters each job was last launched with, so the next launch of it is a fresh instance.
     *
     * <p>The framework refuses a completed instance the same identifying parameters, and each job's own
     * incrementer derives the next value from the previous one - so the previous one has to be kept.
     * This reproduces what the application's launch surface does when it starts the next instance.
     */
    private final Map<String, JobParameters> lastParameters = new HashMap<>();

    @Autowired
    private JobRegistry jobRegistry;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private CreateStatementJobConfig config;

    @Autowired
    private StatementProcessor statementProcessor;

    @Autowired
    private StatementDataAccessService statementDataAccessService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    CreateStatementJobConfigIT() {
        // Intentionally empty: the harness holds no per-instance state beyond its injections.
    }

    /**
     * What one clean run of this job leaves behind, captured so each assertion can read one facet.
     *
     * @param execution        the completed job execution
     * @param stepNames        the names of the steps that actually ran, in execution order
     * @param projectedRecords the ordered, reprojected records the first step wrote
     * @param statementRecords the plain statement records the fourth step wrote
     * @param htmlRecords      the markup statement records the fourth step wrote
     */
    private record CleanRun(JobExecution execution, List<String> stepNames,
            List<String> projectedRecords, List<String> statementRecords,
            List<String> htmlRecords) {
    }

    /**
     * A transaction source serving records this test built, so the generator's own behaviour can be
     * observed over an input the test controls end to end.
     *
     * <p>The interface the generator consumes is positional rather than iterative, which is what lets
     * the legacy read loop re-enter its own read paragraph; serving from an immutable list keeps that
     * faithful without holding a cursor.
     *
     * @param records the projected work records, in key sequence
     */
    private record ConstructedTransactionSource(List<String> records)
            implements StatementTransactionSource {

        @Override
        public Optional<String> readAt(final int position) {
            if (position < 0 || position >= this.records.size()) {
                return Optional.empty();
            }
            return Optional.of(this.records.get(position));
        }
    }

    /**
     * Establishes the one clean pipeline run, once, before the first assertion that reads it.
     *
     * <p>Unconditional, and run exactly once by the framework rather than by whichever test happened to
     * be first. A batch job commits per chunk on its own connections, so a transactional rollback cannot
     * reach it and re-running the whole pipeline for every assertion would both be wasteful and let one
     * assertion observe a store another had already changed. Establishing it here means every assertion
     * reads one outcome, no assertion establishes anything, and none of them can run without it.
     *
     * @throws Exception if the store cannot be prepared or a job in the pipeline cannot be launched
     */
    @BeforeAll
    void establishTheConsolidatedStore() throws Exception {
        restoreSeededState();
        clearStagingRoot();
        stageConsolidationInputs();
        stageDailyTransactionInput();
        installOrderingProbeCards();

        launch(PostTransactionJobConfig.JOB_NAME, new JobParametersBuilder().toJobParameters());
        launch(InterestCalculationJobConfig.JOB_NAME, new JobParametersBuilder()
                .addString(InterestCalculationJobConfig.PARM_DATE_KEY, interestParmDate())
                .toJobParameters());
        launch(CombineTransactionsJobConfig.JOB_NAME, new JobParametersBuilder().toJobParameters());

        installOrderingProbeTransactions();

        final JobExecution execution = launch(CreateStatementJobConfig.JOB_NAME,
                new JobParametersBuilder().toJobParameters());
        final long executionId = requireExecutionId(execution);
        this.cleanRun = new CleanRun(execution, executedStepNames(execution),
                readRecords(this.config.transactionWorkSequentialResource(executionId),
                        RECORD_LENGTH),
                readRecords(this.config.statementOutputGeneration(executionId),
                        STATEMENT_RECORD_WIDTH),
                readRecords(this.config.htmlStatementOutputGeneration(executionId),
                        HTML_RECORD_WIDTH));
    }

    /**
     * Returns the shared server to the state this class borrowed it in.
     *
     * <p>Both halves of this matter, and both are this class's own doing rather than incidental.</p>
     *
     * <p><strong>The data.</strong> Unlike a class that writes a handful of rows and undoes exactly
     * those, this one runs three whole jobs as a fixture: posting writes hundreds of transactions and
     * moves account balances, interest accrual rewrites category balances, consolidation inserts into
     * the master, and one card is added that no seed contains. A batch job commits per chunk on its own
     * connections, so no rollback reaches any of it. Reseeding is the only way back, and leaving the
     * store mutated would break a later class that reads the seeded figures - which is not a defect in
     * that class.</p>
     *
     * <p><strong>The job repository.</strong> The framework's own metadata is shared by the whole
     * integration tier and is not reseeded, by design. Launching the three predecessor jobs leaves
     * instances of jobs this class does not own, and a class that asserts its own job was never
     * triggered at start-up reads those instances and rightly fails. Scoped to exactly the four job
     * names this class launched, and to nothing else, the borrowed metadata is handed back too. The
     * six statements run child-first so no foreign key is violated part-way through.</p>
     *
     * <p>The staging root goes with it. This specification stages a whole pipeline - four jobs, several
     * generations and two synthesized inputs - and its root is private to this process, so nothing left
     * there could be read by a later run in any case. It is still removed rather than left: a root that
     * is emptied between assertions but never at the end leaves one populated tree per process on the
     * host, and "delete only what this execution created" is a statement about what is deleted, not an
     * excuse to delete nothing.</p>
     *
     * @throws SQLException if the store cannot be reseeded or the metadata cannot be released
     */
    @AfterAll
    void returnTheSharedServer() throws Exception {
        try {
            restoreSeededState();
            releaseBorrowedJobMetadata();
            this.cleanRun = null;
            this.lastParameters.clear();
        } finally {
            IsolatedStagingRoot.discard(stagingRoot());
        }
    }

    /**
     * Removes the job-repository rows belonging to the four jobs this class launched.
     *
     * <p>Every job name is bound as a parameter rather than assembled into the statement, and the
     * statements themselves are fixed literals. The order is child-first: the two context tables, the
     * step executions, the execution parameters, the executions, and finally the instances.
     *
     * @throws SQLException if the metadata cannot be released
     */
    private static void releaseBorrowedJobMetadata() throws SQLException {
        final List<String> borrowed = List.of(PostTransactionJobConfig.JOB_NAME,
                InterestCalculationJobConfig.JOB_NAME, CombineTransactionsJobConfig.JOB_NAME,
                CreateStatementJobConfig.JOB_NAME);
        final List<String> statements = List.of("""
                DELETE FROM batch_step_execution_context WHERE step_execution_id IN (
                    SELECT s.step_execution_id FROM batch_step_execution s
                      JOIN batch_job_execution e ON e.job_execution_id = s.job_execution_id
                      JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
                     WHERE i.job_name = ?)
                """, """
                DELETE FROM batch_step_execution WHERE job_execution_id IN (
                    SELECT e.job_execution_id FROM batch_job_execution e
                      JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
                     WHERE i.job_name = ?)
                """, """
                DELETE FROM batch_job_execution_context WHERE job_execution_id IN (
                    SELECT e.job_execution_id FROM batch_job_execution e
                      JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
                     WHERE i.job_name = ?)
                """, """
                DELETE FROM batch_job_execution_params WHERE job_execution_id IN (
                    SELECT e.job_execution_id FROM batch_job_execution e
                      JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
                     WHERE i.job_name = ?)
                """, """
                DELETE FROM batch_job_execution WHERE job_instance_id IN (
                    SELECT i.job_instance_id FROM batch_job_instance i WHERE i.job_name = ?)
                """, """
                DELETE FROM batch_job_instance WHERE job_name = ?
                """);

        try (Connection connection = connect()) {
            for (final String jobName : borrowed) {
                for (final String statement : statements) {
                    try (PreparedStatement delete = connection.prepareStatement(statement)) {
                        delete.setString(1, jobName);
                        delete.executeUpdate();
                    }
                }
            }
        }
    }

    /**
     * Launches one job by the name its own configuration publishes and waits for it to finish.
     *
     * <p>The name is never a literal here: each configuration owns the spelling of its own job, and
     * resolving it through the registry is how the application launches it too.
     *
     * @param jobName    the job's own published name
     * @param parameters the parameters that job's contract declares
     * @return the finished execution
     * @throws Exception if the job is not registered or cannot be launched
     */
    private JobExecution launch(final String jobName, final JobParameters parameters)
            throws Exception {
        final Job job = this.jobRegistry.getJob(jobName);
        final JobParameters base = this.lastParameters.getOrDefault(jobName, parameters);
        final JobParameters effective = job.getJobParametersIncrementer() == null
                ? parameters
                : job.getJobParametersIncrementer().getNext(base);
        this.lastParameters.put(jobName, effective);
        return this.jobLauncher.run(job, effective);
    }

    /**
     * Renders the interest job's parameter value: ten all-digit characters, as its card carried.
     *
     * <p>Derived from the shared pinned business date rather than from a clock, so the value is the
     * same on every run and on every host. The eight date digits carry a two-digit tail, which is the
     * shape the ten-character card had.
     *
     * @return the ten-character all-digit parameter value
     */
    private static String interestParmDate() {
        return String.format(Locale.ROOT, "%04d%02d%02d%s", PINNED_BUSINESS_DATE.getYear(),
                PINNED_BUSINESS_DATE.getMonthValue(), PINNED_BUSINESS_DATE.getDayOfMonth(),
                INTEREST_PARM_DATE_SUFFIX);
    }

    /**
     * Returns the staging root the four jobs share, resolved the way the context resolved it.
     *
     * @return the staging root, private to this run
     */
    private static Path stagingRoot() {
        return IsolatedStagingRoot.forSpecification(STAGING_LABEL);
    }

    /**
     * Binds all four staging-directory keys to one root private to this process, before the context is
     * created.
     *
     * <p>A property callback rather than four annotation entries, because the value cannot be a
     * compile-time constant: it carries the process identifier so that no other run of this specification,
     * and no sibling clone sharing this host, resolves the same absolute path. The callback runs before the
     * context starts, which is what four {@code @Value} bindings resolved during start-up require.
     *
     * @param registry the registry the framework supplies
     */
    @DynamicPropertySource
    static void registerIsolatedStagingDirectory(final DynamicPropertyRegistry registry) {
        for (final String key : STAGING_DIRECTORY_KEYS) {
            registry.add(key, () -> IsolatedStagingRoot.pathFor(STAGING_LABEL));
        }
    }

    /**
     * Removes this specification's own staging root, so nothing a previous run left can be mistaken for
     * this one's, and prepares an empty one.
     *
     * <p>What is removed is a tree this process owns. The sweep that stood here removed every regular file
     * beneath a directory every run and every sibling clone shared, which could reach a file a
     * concurrently running sibling was still composing.
     */
    private static void clearStagingRoot() {
        IsolatedStagingRoot.discard(stagingRoot());
        IsolatedStagingRoot.forSpecification(STAGING_LABEL);
    }

    /**
     * Writes the daily-transaction input the posting job reads, under the name that job resolves.
     *
     * <p>The records come from the delivered fixture on the test class path. Nothing here reads the
     * legacy tree at run time: the fixture is a class-path resource of this module.
     *
     * @throws IOException if the input cannot be written
     */
    private static void stageDailyTransactionInput() throws IOException {
        final List<String> records =
                TestDataFactory.fixedWidthRecords("dailytran.txt", RECORD_LENGTH);
        // Line-oriented, because the posting job's reader takes its record boundary from a line
        // terminator. The consolidation job's reader does not - see the unblocked writer below.
        writeArtefact(stagingRoot().resolve(PostTransactionJobConfig.DEFAULT_DALYTRAN_DATASET),
                records, SAMPLE_DATA_SEPARATOR);
    }

    /**
     * Writes the two inputs the consolidation job concatenates and orders.
     *
     * <p>Both carry identifiers well clear of every other probe range and both name a seeded card, so
     * the posted-transaction foreign key resolves when the job loads them into the master.
     *
     * @throws IOException if either input cannot be written
     */
    private static void stageConsolidationInputs() throws IOException {
        // Unblocked, with NO separator between records: the consolidation job reads its two inputs on a
        // fixed 350-byte stride rather than on a line terminator, so a separator would leave a partial
        // record at the end of the stride and the reader would refuse the resource outright.
        writeArtefact(stagingRoot().resolve(BACKUP_INPUT_NAME),
                List.of(consolidationRecord(CONSOLIDATION_IDS.get(0))), "");
        writeArtefact(stagingRoot().resolve(SYNTHESIZED_INPUT_NAME),
                List.of(consolidationRecord(CONSOLIDATION_IDS.get(1))), "");
    }

    /**
     * Builds one 350-byte source record for the consolidation inputs.
     *
     * @param identifier the sixteen-digit identifier the record carries
     * @return the record image
     */
    private static String consolidationRecord(final String identifier) {
        return TestDataFactory.transaction()
                .id(identifier)
                .cardNumber(SEEDED_CARD_WITH_CROSS_REFERENCE)
                .amount(PROBE_AMOUNT)
                .originalTimestamp(PROBE_ORIGINATION_TIMESTAMP)
                .processingTimestamp(PROBE_PROCESSING_TIMESTAMP)
                .image();
    }

    /**
     * Installs the two cards the ordering probe needs, each sixteen characters wide.
     *
     * <p>The schema constrains a card number to sixteen characters rather than to sixteen digits,
     * which is what the alphanumeric layout allows and what makes a short space-padded value legal.
     * Both cards belong to a seeded account so the card's own foreign key resolves.
     */
    private void installOrderingProbeCards() {
        if (this.cardRepository.existsById(LEXICALLY_LAST_CARD)) {
            return;
        }
        final Card card = TestDataFactory.card()
                .cardNumber(LEXICALLY_LAST_CARD)
                .accountId(PROBE_ACCOUNT_ID)
                .build();
        this.cardRepository.saveAndFlush(card);
    }

    /**
     * Installs the three ordering probes into the consolidated store, after consolidation has run.
     *
     * <p>They are written here rather than through one of the setup jobs on purpose. Two of the three
     * carry the added card, whose lexicographic order relative to every delivered card is the reverse
     * of its numeric order, and those two carry different identifiers so the second ordering key is
     * exercised as well. The third carries a delivered card, which is the other side of the
     * comparison. No delivered fixture holds such a value, and none is edited to make it do so.
     */
    private void installOrderingProbeTransactions() {
        this.transactionRepository.insertAndFlush(
                probe(PROBE_ON_DELIVERED_CARD_ID, SEEDED_CARD_WITH_CROSS_REFERENCE));
        this.transactionRepository.insertAndFlush(
                probe(PROBE_ON_ADDED_CARD_LOW_ID, LEXICALLY_LAST_CARD));
        this.transactionRepository.insertAndFlush(
                probe(PROBE_ON_ADDED_CARD_HIGH_ID, LEXICALLY_LAST_CARD));
    }

    /**
     * Builds one probe entity carrying the values every reprojection assertion is written against.
     *
     * @param identifier the sixteen-digit identifier
     * @param cardNumber the sixteen-character card number
     * @return the entity, not yet written
     */
    private static Transaction probe(final String identifier, final String cardNumber) {
        return TestDataFactory.transaction()
                .id(identifier)
                .typeCode(PROBE_TYPE_CODE)
                .categoryCode(PROBE_CATEGORY_CODE)
                .cardNumber(cardNumber)
                .amount(PROBE_AMOUNT)
                .originalTimestamp(PROBE_ORIGINATION_TIMESTAMP)
                .processingTimestamp(PROBE_PROCESSING_TIMESTAMP)
                .build();
    }

    // -----------------------------------------------------------------------------------------------
    // Shared reading, writing and slicing. Every width check counts ENCODED bytes and nothing is
    // trimmed anywhere: a fixed-width comparison that trims stops being a fixed-width comparison.
    // -----------------------------------------------------------------------------------------------

    /**
     * Writes fixed-width records to an artefact under the record boundary its reader expects.
     *
     * <p>The boundary is an argument because the two setup jobs disagree about it, and the
     * disagreement is real rather than incidental: the posting job's reader is line-oriented while the
     * consolidation job's reader advances on a fixed 350-byte stride. Writing either input in the
     * other's shape makes its reader refuse the resource.
     *
     * @param target    the artefact to write
     * @param records   the records, each already at its declared width
     * @param separator the record boundary - a line terminator, or empty for an unblocked artefact
     * @throws IOException if the artefact cannot be written
     */
    private static void writeArtefact(final Path target, final List<String> records,
            final String separator) throws IOException {
        final StringBuilder image = new StringBuilder(records.size() * RECORD_LENGTH);
        for (final String record : records) {
            image.append(record).append(separator);
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, image.toString(), StandardCharsets.US_ASCII);
    }

    /**
     * Reads an artefact back and proves, while doing so, that it holds whole records of one width.
     *
     * <p>A missing artefact fails here with a diagnostic naming what was expected and where, rather
     * than surfacing later as an empty list that an assertion would read as "nothing was produced".
     *
     * @param artefact      the artefact the job wrote
     * @param declaredWidth the width the artefact's records are declared at
     * @return the records, in the order the artefact holds them
     * @throws IOException if the artefact cannot be read
     */
    private static List<String> readRecords(final Path artefact, final int declaredWidth)
            throws IOException {
        assertThat(Files.exists(artefact))
                .as("the run was expected to leave an artefact of %d-byte records at %s, and nothing"
                        + " is there; the step that writes it either did not run or wrote elsewhere",
                        declaredWidth, artefact)
                .isTrue();
        final byte[] image = Files.readAllBytes(artefact);
        assertThat(image.length % declaredWidth)
                .as("%s holds %d byte(s), which is not a whole number of %d-byte records; every artefact"
                        + " this pipeline produces is fixed-length with nothing between two records, so a"
                        + " remainder means either a partial record or a stray separator - and either"
                        + " would leave every downstream reader of that artefact reading the wrong"
                        + " field (DL-213)", artefact, Integer.valueOf(image.length),
                        Integer.valueOf(declaredWidth))
                .isZero();
        assertThat(new String(image, StandardCharsets.US_ASCII))
                .as("%s must carry no record separator at all", artefact)
                .doesNotContain(SAMPLE_DATA_SEPARATOR);
        final List<String> records = new ArrayList<>(image.length / declaredWidth);
        for (int offset = 0; offset < image.length; offset += declaredWidth) {
            records.add(new String(image, offset, declaredWidth, StandardCharsets.US_ASCII));
        }
        return records;
    }

    /**
     * Names the steps that actually ran, in the order the framework ran them.
     *
     * <p>A step a gate refused is simply absent, which is what makes this list the observation a gate
     * assertion needs: it distinguishes "ran and failed" from "never ran".
     *
     * @param execution the finished execution
     * @return the executed step names in execution order
     */
    private static List<String> executedStepNames(final JobExecution execution) {
        return execution.getStepExecutions().stream()
                .sorted(Comparator.comparing(StepExecution::getId))
                .map(StepExecution::getStepName)
                .toList();
    }

    /**
     * Returns the identifier the framework assigned, refusing to guess if it assigned none.
     *
     * @param execution the execution
     * @return the identifier
     */
    private static long requireExecutionId(final JobExecution execution) {
        final Long identifier = execution.getId();
        assertThat(identifier)
                .as("the framework must assign an execution identifier before this job resolves the"
                        + " per-execution artefacts its steps hand to one another")
                .isNotNull();
        return identifier.longValue();
    }

    /**
     * Reads one field out of a record image by zero-based offset and width, without trimming.
     *
     * @param image  the record image
     * @param offset the zero-based offset
     * @param length the field width
     * @return the field's raw characters
     */
    private static String field(final String image, final int offset, final int length) {
        return image.substring(offset, offset + length);
    }

    /**
     * Assembles the thirty-two byte key the absorbed definition step declares at offset zero.
     *
     * <p>Built here from the two values the probe carries, so the assertion that the projected
     * record's leading thirty-two bytes equal it borrows nothing from the subject.
     *
     * @param cardNumber the sixteen-character card number
     * @param identifier the sixteen-character identifier
     * @return the thirty-two byte key
     */
    private static String workKey(final String cardNumber, final String identifier) {
        return cardNumber + identifier;
    }

    /**
     * Finds the projected record carrying one identifier, failing with a diagnostic if it is absent.
     *
     * @param records    the projected records
     * @param identifier the sixteen-character identifier, as it sits behind the card number
     * @return the record
     */
    private static String projectedRecordFor(final List<String> records, final String identifier) {
        final Optional<String> found = records.stream()
                .filter(record -> field(record, KEY_FIELD_LENGTH, KEY_FIELD_LENGTH)
                        .equals(identifier))
                .findFirst();
        assertThat(found)
                .as("the ordered artefact was expected to carry the probe identifier %s at offset %d"
                        + " for %d byte(s); it carries %d record(s) and none of them holds it,"
                        + " so either the probe never reached the store or the identifier is not"
                        + " where the reprojection places it", identifier, KEY_FIELD_LENGTH,
                        KEY_FIELD_LENGTH, records.size())
                .isPresent();
        return found.orElseThrow();
    }

    /**
     * Returns the position of the record carrying one identifier, for a relative-order assertion.
     *
     * @param records    the projected records
     * @param identifier the sixteen-character identifier
     * @return the zero-based position
     */
    private static int positionOf(final List<String> records, final String identifier) {
        return records.indexOf(projectedRecordFor(records, identifier));
    }

    /**
     * Counts how many of a run's records equal one expected line exactly.
     *
     * @param records  the produced records
     * @param expected the line, already at its declared width
     * @return the number of exact matches
     */
    private static long occurrencesOf(final List<String> records, final String expected) {
        return records.stream().filter(expected::equals).count();
    }

    /**
     * Pads a literal on the right with blanks to a declared record width.
     *
     * <p>The produced records are fixed-width, so a contract literal only matches once it is padded
     * the same way. Padding here, in the test, keeps the expectation independent of the padding the
     * subject applies.
     *
     * @param literal the contract literal
     * @param width   the declared record width
     * @return the literal at that width
     */
    private static String atWidth(final String literal, final int width) {
        return literal + " ".repeat(width - literal.length());
    }

    /**
     * The reprojection, restated as this test's own arithmetic.
     *
     * <p>Three segments and a pad, written out from the measured offsets above: the sixteen-byte card
     * number taken from one-based position 263, the record's leading 262 bytes behind it, a fifty-byte
     * slice taken from one-based position 279, and blanks back to 350. Nothing here calls the subject,
     * which is the whole point - it is the oracle the subject is compared against, and it is also what
     * builds the work records served to the generator below.
     *
     * @param sourceImage the 350-byte source record image
     * @return the 350-byte projected image
     */
    private static String projectIndependently(final String sourceImage) {
        return field(sourceImage, CARD_NUMBER_OFFSET, KEY_FIELD_LENGTH)
                + field(sourceImage, TRAN_ID_OFFSET, LEADING_SEGMENT_LENGTH)
                + field(sourceImage, ORIGINATION_TIMESTAMP_OFFSET, TIMESTAMP_SLICE_LENGTH)
                + " ".repeat(BLANK_PAD_LENGTH);
    }

    /**
     * Runs the generator over work records this test built, and returns what it produced.
     *
     * <p>The served records are complete projected images assembled by this class's own reprojection,
     * in key sequence, against a card that carries a cross-reference row - so the generator walks its
     * phases, matches the card and writes a whole statement, all over an input the test controls.
     *
     * <p>The generator writes each record to a sink as it composes it, so the two streams are collected
     * here rather than read off the result, and the result carries counts and the dispatch trace.
     *
     * @param cardNumber the card the served records belong to
     * @return the generator's own result together with the two streams its sinks received
     */
    private ObservedRun generateOver(final String cardNumber) {
        final List<String> served = new ArrayList<>();
        for (final String identifier : GENERATOR_PROBE_IDS) {
            served.add(projectIndependently(TestDataFactory.transaction()
                    .id(identifier)
                    .typeCode(PROBE_TYPE_CODE)
                    .categoryCode(PROBE_CATEGORY_CODE)
                    .cardNumber(cardNumber)
                    .amount(PROBE_AMOUNT)
                    .originalTimestamp(PROBE_ORIGINATION_TIMESTAMP)
                    .processingTimestamp(PROBE_PROCESSING_TIMESTAMP)
                    .image()));
        }
        final CollectingSink sink = new CollectingSink();
        final StatementRun tallies = this.statementProcessor.process(
                new StatementProcessor.StatementRunRequest(
                        new ConstructedTransactionSource(List.copyOf(served)), sink));
        return new ObservedRun(List.copyOf(sink.statementRecords), List.copyOf(sink.htmlRecords),
                List.copyOf(sink.transactionSummaries), List.copyOf(sink.dispatchedPhases), tallies);
    }

    /**
     * Collects everything one generation forwards, so that content and order can be asserted.
     *
     * <p>The service and the stage retain nothing: each record leaves through a
     * {@link StatementOutputSink} as it is produced. A test controls its own volume, so collecting the
     * emission here is a bounded observation rather than the accumulation the production path no longer
     * performs.
     */
    private static final class CollectingSink implements StatementOutputSink {

        /** Plain records forwarded, in order. */
        private final List<String> statementRecords = new ArrayList<>();

        /** Markup records forwarded, in order. */
        private final List<String> htmlRecords = new ArrayList<>();

        /** Per-line summaries forwarded, in order. */
        private final List<StatementLineSummary> transactionSummaries = new ArrayList<>();

        /** Dispatcher entries forwarded, in order. */
        private final List<String> dispatchedPhases = new ArrayList<>();

        @Override
        public void statementRecord(final String record) {
            this.statementRecords.add(record);
        }

        @Override
        public void htmlRecord(final String record) {
            this.htmlRecords.add(record);
        }

        @Override
        public void transactionSummary(final StatementLineSummary summary) {
            this.transactionSummaries.add(summary);
        }

        @Override
        public void dispatchedPhase(final String phase) {
            this.dispatchedPhases.add(phase);
        }
    }

    /**
     * One generation's emitted content together with the tallies the stage returned.
     *
     * @param statementRecords     the plain records emitted, in order
     * @param htmlRecords          the markup records emitted, in order
     * @param transactionSummaries the per-line summaries emitted, in order
     * @param dispatchedPhases     the dispatcher entries reported, in order
     * @param tallies              the seven counts the stage returned
     */
    private record ObservedRun(List<String> statementRecords, List<String> htmlRecords,
                               List<StatementLineSummary> transactionSummaries,
                               List<String> dispatchedPhases, StatementRun tallies) {

        /** @return how many statements the mainline produced */
        int statementsWritten() {
            return this.tallies.statementsWritten();
        }

        /** @return the total of the per-card counters */
        int transactionsTabulated() {
            return this.tallies.transactionsTabulated();
        }

        /** @return {@code CR-CNT} as the read phase left it */
        int cardsTabulated() {
            return this.tallies.cardsTabulated();
        }
    }

    // ===============================================================================================
    // THE JOB'S SHAPE
    // ===============================================================================================

    @Test
    @DisplayName("the four jobs of the pipeline are registered by name and none of them ran merely "
            + "because a context was refreshed")
    void thePipelineIsRegisteredByNameAndInertAtStartUp() throws Exception {
        assertThat(this.jobRegistry.getJobNames())
                .as("each job is launched by the name its own configuration publishes, so all four"
                        + " links of the pipeline have to be resolvable through the registry")
                .contains(PostTransactionJobConfig.JOB_NAME, InterestCalculationJobConfig.JOB_NAME,
                        CombineTransactionsJobConfig.JOB_NAME, CreateStatementJobConfig.JOB_NAME);

        assertThat(this.jobExplorer.getJobInstanceCount(CreateStatementJobConfig.JOB_NAME))
                .as("nothing runs merely because a context was refreshed: this class has launched"
                        + " this job exactly once so far, so exactly one instance exists. A"
                        + " start-up launcher of any kind - a command-line runner, an application"
                        + " runner, a post-construct hook, a lifecycle bean or an event listener -"
                        + " would show up here as a second instance")
                .isEqualTo(1);

        assertThat(this.cleanRun.execution().getStatus())
                .as("and the one instance that does exist is the one this class launched explicitly")
                .isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("one legacy step is absorbed, so four steps run in declaration order and the run "
            + "completes")
    void fourStepsRunInDeclarationOrder() {
        assertThat(this.cleanRun.stepNames())
                .as("five legacy steps less the absorbed definition step leaves %d, and they run"
                        + " strictly in sequence - no parallel flow, no partitioning and no task"
                        + " executor takes part", MIGRATED_STEP_COUNT)
                .containsExactly(CreateStatementJobConfig.ORDER_AND_REPROJECT_STEP_NAME,
                        CreateStatementJobConfig.LOAD_WORK_RESOURCE_STEP_NAME,
                        CreateStatementJobConfig.CLEAR_STATEMENT_OUTPUTS_STEP_NAME,
                        CreateStatementJobConfig.GENERATE_STATEMENTS_STEP_NAME)
                .hasSize(MIGRATED_STEP_COUNT);

        assertThat(this.cleanRun.execution().getStatus())
                .as("a clean pass through all four steps completes the run")
                .isEqualTo(BatchStatus.COMPLETED);

        for (final StepExecution step : this.cleanRun.execution().getStepExecutions()) {
            assertThat(step.getStatus())
                    .as("step %s ended %s; on a clean pass every step has to complete, because a"
                            + " strict gate refuses the next step on anything else",
                            step.getStepName(), step.getStatus())
                    .isEqualTo(BatchStatus.COMPLETED);
        }
    }

    @Test
    @DisplayName("this job carries no date parameter, because its member declares no parameter "
            + "string on any of its five steps")
    void theJobCarriesNoDateParameter() {
        final JobParameters parameters = this.cleanRun.execution().getJobParameters();

        assertThat(parameters.getParameters().keySet())
                .as("the member passes no program parameter to any step, so the only parameter this"
                        + " job may carry is the framework's own run identifier; a date window here"
                        + " would be an invented contract, and the interest and report jobs are the"
                        + " ones that genuinely carry date parameters")
                .doesNotContain(InterestCalculationJobConfig.PARM_DATE_KEY);

        assertThat(parameters.getString(InterestCalculationJobConfig.PARM_DATE_KEY))
                .as("no date value reaches this job under any name")
                .isNull();
    }

    @Test
    @DisplayName("the absorbed transient definition leaves no schema artefact: eleven application "
            + "tables and five migrations, unchanged")
    void theAbsorbedTransientDefinitionLeavesNoSchemaArtefact() throws Exception {
        assertThat(applicationTableNames())
                .as("the transient cluster is created and destroyed inside this one job stream, so it"
                        + " becomes an in-job ordered result and not a table: no twelfth application"
                        + " table appears. The framework's own metadata tables and the migration"
                        + " history are excluded by the roster helper, because neither belongs to the"
                        + " record schema")
                .containsExactlyInAnyOrderElementsOf(APPLICATION_TABLES)
                .hasSize(APPLICATION_TABLES.size());

        assertThat(appliedMigrationVersions())
                .as("nor does it become a migration: the delivered set stays at exactly %d versions",
                        MIGRATION_VERSION_COUNT)
                .hasSize(MIGRATION_VERSION_COUNT);

        assertThat(this.cleanRun.projectedRecords())
                .as("what the absorbed definition becomes is this artefact - an ordered, keyed,"
                        + " 350-byte result the job writes and reads within one execution")
                .isNotEmpty();
    }

    // ===============================================================================================
    // THE THREE STRICT GATES, IN BOTH DIRECTIONS
    // ===============================================================================================

    @Test
    @DisplayName("each of the three gates admits its guarded step when every earlier step ended "
            + "clean")
    void everyGateAdmitsItsGuardedStepAfterACleanPredecessor() {
        assertThat(GATE_COUNT)
                .as("three of the estate's four gated steps live in this one member")
                .isEqualTo(MIGRATED_STEP_COUNT - 1);

        assertThat(this.cleanRun.stepNames())
                .as("the first gate guards the load step: it ran, so the gate admitted it after the"
                        + " ordering step ended clean")
                .contains(CreateStatementJobConfig.LOAD_WORK_RESOURCE_STEP_NAME);
        assertThat(this.cleanRun.stepNames())
                .as("the second gate guards the scratch step: it ran, so the gate admitted it after"
                        + " the load step ended clean")
                .contains(CreateStatementJobConfig.CLEAR_STATEMENT_OUTPUTS_STEP_NAME);
        assertThat(this.cleanRun.stepNames())
                .as("the third gate guards the generation step: it ran, so the gate admitted it"
                        + " after the scratch step ended clean")
                .contains(CreateStatementJobConfig.GENERATE_STATEMENTS_STEP_NAME);
    }

    @Test
    @DisplayName("when the ordering step fails, all three gates refuse: the load, scratch and "
            + "generation steps never run")
    void aFailedOrderingStepIsRefusedByEveryGate() throws Exception {
        final Path root = stagingRoot();
        final Path displaced = root.resolveSibling(root.getFileName() + ".displaced");
        Files.move(root, displaced);
        try {
            // A regular file where the staging root belongs. The ordering step cannot bring its own
            // container into existence over a non-directory, so it fails on the way in. Ownership and
            // permission bits are deliberately not used to force this: the suite may run as a user
            // that bypasses them, and a lever that silently stops working would leave this assertion
            // passing for the wrong reason.
            Files.writeString(root, "", StandardCharsets.US_ASCII);

            final JobExecution halted = launch(CreateStatementJobConfig.JOB_NAME,
                    new JobParametersBuilder().toJobParameters());
            final List<String> ran = executedStepNames(halted);

            assertThat(ran)
                    .as("the ordering step is the only step that gets to run")
                    .containsExactly(CreateStatementJobConfig.ORDER_AND_REPROJECT_STEP_NAME);
            assertThat(halted.getStepExecutions().iterator().next().getStatus())
                    .as("and it genuinely failed rather than completing with a remark")
                    .isEqualTo(BatchStatus.FAILED);

            assertThat(ran)
                    .as("FIRST GATE, halt direction: its guarded load step did not run, because the"
                            + " step ahead of it did not end clean. The strict form bypasses on"
                            + " anything other than zero - it does not tolerate a code up to four the"
                            + " way the sibling backup job's single gate does")
                    .doesNotContain(CreateStatementJobConfig.LOAD_WORK_RESOURCE_STEP_NAME);
            assertThat(ran)
                    .as("SECOND GATE, halt direction: its guarded scratch step did not run either,"
                            + " because the load step ahead of it did not end clean - it never ended"
                            + " at all, which the strict rule treats as a refusal rather than as"
                            + " nothing to report")
                    .doesNotContain(CreateStatementJobConfig.CLEAR_STATEMENT_OUTPUTS_STEP_NAME);
            assertThat(ran)
                    .as("THIRD GATE, halt direction: nor did the generation step, so no statement was"
                            + " written on a run whose first step failed")
                    .doesNotContain(CreateStatementJobConfig.GENERATE_STATEMENTS_STEP_NAME);

            assertThat(halted.getStatus())
                    .as("DL-208: the gates bypass the downstream steps AND propagate the abend. A"
                            + " z/OS job's completion code is the highest code any of its steps"
                            + " returned, so a run whose first step abended cannot end clean")
                    .isEqualTo(BatchStatus.FAILED);
            assertThat(halted.getExitStatus().getExitCode())
                    .as("and the code the submitter reads says so too, rather than saying zero")
                    .isEqualTo(ExitStatus.FAILED.getExitCode());
        } finally {
            Files.deleteIfExists(root);
            Files.move(displaced, root);
        }
    }

    @Test
    @DisplayName("when the scratch step fails, the third gate refuses the generation step while the "
            + "two steps ahead of it stay clean")
    void aFailedScratchStepIsRefusedByTheThirdGate() throws Exception {
        // The scratch step deletes each allocated output. A non-empty directory standing where the
        // plain statement output belongs cannot be deleted, so that step - and only that step - fails.
        final Path obstruction = stagingRoot().resolve("AWS.M2.CARDDEMO.STATEMNT.PS");
        Files.deleteIfExists(obstruction);
        Files.createDirectories(obstruction.resolve("occupied"));
        try {
            final JobExecution halted = launch(CreateStatementJobConfig.JOB_NAME,
                    new JobParametersBuilder().toJobParameters());
            final List<String> ran = executedStepNames(halted);

            assertThat(ran)
                    .as("the ordering and load steps ran and the scratch step ran and failed, so the"
                            + " run reached the third gate with a genuinely failed predecessor")
                    .containsExactly(CreateStatementJobConfig.ORDER_AND_REPROJECT_STEP_NAME,
                            CreateStatementJobConfig.LOAD_WORK_RESOURCE_STEP_NAME,
                            CreateStatementJobConfig.CLEAR_STATEMENT_OUTPUTS_STEP_NAME);

            for (final StepExecution step : halted.getStepExecutions()) {
                final BatchStatus expected = CreateStatementJobConfig
                        .CLEAR_STATEMENT_OUTPUTS_STEP_NAME.equals(step.getStepName())
                        ? BatchStatus.FAILED
                        : BatchStatus.COMPLETED;
                assertThat(step.getStatus())
                        .as("step %s was expected to end %s on this run", step.getStepName(),
                                expected)
                        .isEqualTo(expected);
            }

            assertThat(ran)
                    .as("THIRD GATE, halt direction, driven by a real failure of its own immediate"
                            + " predecessor: the generation step did not run")
                    .doesNotContain(CreateStatementJobConfig.GENERATE_STATEMENTS_STEP_NAME);

            assertThat(halted.getStatus())
                    .as("DL-208 again, this time with two clean steps ahead of the failed one: the"
                            + " highest step code still decides, so the run is FAILED and not"
                            + " COMPLETED-with-a-bypass")
                    .isEqualTo(BatchStatus.FAILED);
            assertThat(halted.getExitStatus().getExitCode())
                    .as("and the submitter reads FAILED rather than a zero it would act on")
                    .isEqualTo(ExitStatus.FAILED.getExitCode());
        } finally {
            Files.deleteIfExists(obstruction.resolve("occupied"));
            Files.deleteIfExists(obstruction);
        }
    }

    // ===============================================================================================
    // THE REPROJECTION: 328 OF 350 BYTES, AND THE TWO BYTES THIS TEST REPRODUCES THE LOSS OF
    // ===============================================================================================

    @Test
    @DisplayName("every projected record is exactly 350 encoded bytes and the artefact is an exact "
            + "multiple of that width")
    void everyProjectedRecordIsExactlyThreeHundredAndFiftyEncodedBytes() {
        assertThat(this.cleanRun.projectedRecords()).isNotEmpty();
        for (final String record : this.cleanRun.projectedRecords()) {
            assertThat(record.getBytes(StandardCharsets.US_ASCII).length)
                    .as("a projected record is 328 bytes of content padded back to %d; nothing is"
                            + " trimmed and the width is counted in encoded bytes, not characters",
                            RECORD_LENGTH)
                    .isEqualTo(RECORD_LENGTH);
        }
        assertThat(PROJECTED_CONTENT_LENGTH + BLANK_PAD_LENGTH)
                .as("sixteen plus %d plus %d is %d content bytes, and %d blanks bring it back to %d",
                        LEADING_SEGMENT_LENGTH, TIMESTAMP_SLICE_LENGTH, PROJECTED_CONTENT_LENGTH,
                        BLANK_PAD_LENGTH, RECORD_LENGTH)
                .isEqualTo(RECORD_LENGTH);
        assertThat(KEY_FIELD_LENGTH + LEADING_SEGMENT_LENGTH + TIMESTAMP_SLICE_LENGTH)
                .isEqualTo(PROJECTED_CONTENT_LENGTH);
    }

    @Test
    @DisplayName("the three segments land where the specification places them: card number to the "
            + "front, leading 262 bytes behind it, fifty-byte slice last")
    void theThreeSegmentsLandWhereTheSpecificationPlacesThem() {
        final String projected =
                projectedRecordFor(this.cleanRun.projectedRecords(), PROBE_ON_ADDED_CARD_LOW_ID);

        assertThat(field(projected, 0, KEY_FIELD_LENGTH))
                .as("FIRST SEGMENT: the sixteen bytes from one-based position 263 move to the front")
                .isEqualTo(LEXICALLY_LAST_CARD);

        assertThat(field(projected, KEY_FIELD_LENGTH, KEY_FIELD_LENGTH))
                .as("SECOND SEGMENT, at its head: the source record's own leading field, shifted"
                        + " right by sixteen bytes")
                .isEqualTo(PROBE_ON_ADDED_CARD_LOW_ID);
        assertThat(field(projected, KEY_FIELD_LENGTH + TRAN_TYPE_OFFSET, TRAN_TYPE_LENGTH))
                .as("SECOND SEGMENT, further in: the field that sat at source offset %d is now at"
                        + " offset %d, shifted right by the sixteen bytes ahead of it",
                        TRAN_TYPE_OFFSET, KEY_FIELD_LENGTH + TRAN_TYPE_OFFSET)
                .isEqualTo(PROBE_TYPE_CODE);
        assertThat(field(projected, KEY_FIELD_LENGTH + TRAN_CATEGORY_OFFSET, TRAN_CATEGORY_LENGTH))
                .as("SECOND SEGMENT, further in again: the same uniform shift applies to every field"
                        + " of the leading 262 bytes")
                .isEqualTo(PROBE_CATEGORY_CODE);

        assertThat(field(projected, ORIGINATION_TIMESTAMP_OFFSET, TIMESTAMP_LENGTH))
                .as("THIRD SEGMENT: the fifty-byte slice starts at one-based position 279 and lands"
                        + " at the same position, so the origination timestamp survives whole")
                .isEqualTo(PROBE_ORIGINATION_TIMESTAMP);

        // The round trip. The source record is rebuilt out of the projected one by undoing the three
        // segments - leading 262 bytes back to the front, card number back to one-based 263, slice back
        // to one-based 279 - and restoring the two truncated bytes and the dropped filler from the
        // values the probe was built with. Reprojecting that reconstruction has to give the record back.
        final String reconstructedSource = field(projected, KEY_FIELD_LENGTH, LEADING_SEGMENT_LENGTH)
                + field(projected, 0, KEY_FIELD_LENGTH)
                + field(projected, ORIGINATION_TIMESTAMP_OFFSET, TIMESTAMP_SLICE_LENGTH)
                + PROBE_PROCESSING_TIMESTAMP.substring(
                        TIMESTAMP_LENGTH - TRUNCATED_PROCESSING_TIMESTAMP_BYTES)
                + " ".repeat(TRAILING_FILLER_LENGTH);

        assertThat(reconstructedSource.getBytes(StandardCharsets.US_ASCII).length)
                .as("the reconstruction is a whole source record: %d leading bytes, the sixteen-byte"
                        + " card number, the fifty-byte slice, the two truncated bytes restored and"
                        + " the %d-byte filler restored", LEADING_SEGMENT_LENGTH,
                        TRAILING_FILLER_LENGTH)
                .isEqualTo(RECORD_LENGTH);
        assertThat(field(reconstructedSource, CARD_NUMBER_OFFSET, KEY_FIELD_LENGTH))
                .as("and in it the card number is back at one-based position 263, where the source"
                        + " layout puts it")
                .isEqualTo(LEXICALLY_LAST_CARD);
        assertThat(field(reconstructedSource, PROCESSING_TIMESTAMP_OFFSET, TIMESTAMP_LENGTH))
                .as("with the whole twenty-six byte processing timestamp present again")
                .isEqualTo(PROBE_PROCESSING_TIMESTAMP);

        assertThat(projectIndependently(reconstructedSource))
                .as("and reprojecting that reconstruction reproduces the record the job wrote, byte"
                        + " for byte - which closes the loop using only this test's own arithmetic"
                        + " and the values the probe was built with, never the subject's")
                .isEqualTo(projected);
    }

    @Test
    @DisplayName("exactly two processing-timestamp bytes are truncated and the twenty-byte filler is "
            + "dropped - the legacy defect, reproduced and not corrected")
    void exactlyTwoProcessingTimestampBytesAreTruncated() {
        final String projected =
                projectedRecordFor(this.cleanRun.projectedRecords(), PROBE_ON_ADDED_CARD_LOW_ID);
        final int survivingBytes = TIMESTAMP_LENGTH - TRUNCATED_PROCESSING_TIMESTAMP_BYTES;
        final String surviving = PROBE_PROCESSING_TIMESTAMP.substring(0, survivingBytes);
        final String lost = PROBE_PROCESSING_TIMESTAMP.substring(survivingBytes);

        assertThat(lost)
                .as("the probe has to carry non-blank bytes in the two positions the slice cannot"
                        + " reach, or this assertion would compare blanks with blanks and prove"
                        + " nothing; the delivered fixture cannot serve here because its"
                        + " processing-timestamp field is blank in every record")
                .isNotBlank()
                .hasSize(TRUNCATED_PROCESSING_TIMESTAMP_BYTES);

        assertThat(field(projected, PROCESSING_TIMESTAMP_OFFSET, survivingBytes))
                .as("the fifty-byte slice reaches one-based position 328, so bytes 305 through 328 of"
                        + " the processing timestamp survive - twenty-four of its twenty-six")
                .isEqualTo(surviving);

        assertThat(field(projected, PROCESSING_TIMESTAMP_OFFSET + survivingBytes, BLANK_PAD_LENGTH))
                .as("and everything past position 328 is blank pad. The two truncated bytes are NOT"
                        + " restored, the slice is NOT widened and the loss is NOT corrected: this is"
                        + " the legacy stream's own fidelity defect, preserved deliberately")
                .isEqualTo(" ".repeat(BLANK_PAD_LENGTH));

        assertThat(field(projected, PROCESSING_TIMESTAMP_OFFSET, TIMESTAMP_LENGTH))
                .as("read across the whole twenty-six byte field, the truncated tail is simply gone")
                .doesNotEndWith(lost)
                .isEqualTo(surviving + " ".repeat(TRUNCATED_PROCESSING_TIMESTAMP_BYTES));

        assertThat(BLANK_PAD_LENGTH)
                .as("the pad is the two truncated timestamp bytes plus the dropped %d-byte filler",
                        TRAILING_FILLER_LENGTH)
                .isEqualTo(TRUNCATED_PROCESSING_TIMESTAMP_BYTES + TRAILING_FILLER_LENGTH);
    }

    @Test
    @DisplayName("the projected record's leading thirty-two bytes are the key the absorbed "
            + "definition declares at offset zero")
    void theLeadingThirtyTwoBytesAreTheTransientDefinitionKey() {
        assertThat(WORK_KEY_LENGTH)
                .as("a thirty-two byte key is the sixteen-byte card number followed by the"
                        + " sixteen-byte identifier, and nothing else fits")
                .isEqualTo(KEY_FIELD_LENGTH * 2);

        for (final String identifier : List.of(PROBE_ON_ADDED_CARD_LOW_ID,
                PROBE_ON_ADDED_CARD_HIGH_ID)) {
            assertThat(field(projectedRecordFor(this.cleanRun.projectedRecords(), identifier), 0,
                    WORK_KEY_LENGTH))
                    .as("the cheapest available proof that the three segments were assembled in the"
                            + " right order: bytes one through thirty-two are exactly the key the"
                            + " definition step declares, at length %d and offset zero",
                            WORK_KEY_LENGTH)
                    .isEqualTo(workKey(LEXICALLY_LAST_CARD, identifier));
        }

        assertThat(field(projectedRecordFor(this.cleanRun.projectedRecords(),
                PROBE_ON_DELIVERED_CARD_ID), 0, WORK_KEY_LENGTH))
                .as("and the same holds for a record on a delivered card, so the assembly is not a"
                        + " property of the constructed one")
                .isEqualTo(workKey(SEEDED_CARD_WITH_CROSS_REFERENCE, PROBE_ON_DELIVERED_CARD_ID));
    }

    // ===============================================================================================
    // THE ORDERING: TWO CHARACTER KEYS, BOTH ASCENDING, NEITHER DECODED
    // ===============================================================================================

    @Test
    @DisplayName("the ordering is lexicographic on the raw character image, never numeric and never "
            + "zoned-decimal decoded")
    void theOrderingIsLexicographicOnTheCharacterImage() {
        final List<String> records = this.cleanRun.projectedRecords();
        final int onDeliveredCard = positionOf(records, PROBE_ON_DELIVERED_CARD_ID);
        final int onAddedCardLow = positionOf(records, PROBE_ON_ADDED_CARD_LOW_ID);
        final int onAddedCardHigh = positionOf(records, PROBE_ON_ADDED_CARD_HIGH_ID);

        assertThat(SEEDED_CARD_WITH_CROSS_REFERENCE.compareTo(LEXICALLY_LAST_CARD))
                .as("the probe is only meaningful while the two card images order one way as"
                        + " characters and the other way as numbers: '%s' precedes '%s' as"
                        + " characters, because '0' precedes '9', while as numbers nine is the"
                        + " smaller of the two by fifteen orders of magnitude",
                        SEEDED_CARD_WITH_CROSS_REFERENCE, LEXICALLY_LAST_CARD.strip())
                .isNegative();
        assertThat(new BigDecimal(LEXICALLY_LAST_CARD.strip())
                .compareTo(new BigDecimal(SEEDED_CARD_WITH_CROSS_REFERENCE)))
                .as("stated the other way round to make the inversion explicit rather than implied:"
                        + " read as numbers the order reverses")
                .isNegative();

        assertThat(onDeliveredCard)
                .as("FIRST KEY, ascending on the character image: the record on card '%s' precedes"
                        + " the record on card '%s'. A numeric reading, or a zoned-decimal decode of"
                        + " the sixteen bytes at one-based position 263, would order these the other"
                        + " way round - which is exactly why the ordering may not be shared with the"
                        + " transaction-report member, where that same offset is typed as zoned"
                        + " decimal rather than as character data",
                        SEEDED_CARD_WITH_CROSS_REFERENCE, LEXICALLY_LAST_CARD.strip())
                .isLessThan(onAddedCardLow);

        assertThat(onAddedCardLow)
                .as("SECOND KEY, ascending: within one card the lower identifier comes first, which"
                        + " is the sixteen bytes at one-based position 1 doing their work")
                .isLessThan(onAddedCardHigh);

        final List<String> keys = new ArrayList<>();
        for (final String record : records) {
            keys.add(field(record, 0, WORK_KEY_LENGTH));
        }
        assertThat(keys)
                .as("and the artefact as a whole is in strictly ascending key sequence, compared as"
                        + " characters - which is also what lets the work resource admit it")
                .isSorted()
                .doesNotHaveDuplicates();
    }

    // ===============================================================================================
    // THE DISPATCHER
    // ===============================================================================================

    @Test
    @DisplayName("the dispatcher is entered six times, in the derived execution order, and its "
            + "catch-all terminates the run")
    void theDispatcherVisitsItsSixClausesInTheDerivedExecutionOrder() {
        final ObservedRun run = generateOver(SEEDED_CARD_WITH_CROSS_REFERENCE);

        assertThat(run.dispatchedPhases())
                .as("six clauses, whose order is the contract, observed at each dispatcher entry."
                        + " The read-all phase is second even though its paragraphs sit far below the"
                        + " four opens in the source: execution order and source order genuinely"
                        + " diverge here, and only a state variable re-entering an ordered dispatcher"
                        + " reproduces that. Nested calls could not, because they cannot re-enter a"
                        + " dispatcher after a state change")
                .containsExactlyElementsOf(DERIVED_DISPATCH_ORDER)
                .hasSize(DERIVED_DISPATCH_ORDER.size());

        assertThat(run.dispatchedPhases().getLast())
                .as("the catch-all clause is reached last and terminates the run rather than looping"
                        + " or falling into a phase")
                .isEqualTo(DERIVED_DISPATCH_ORDER.getLast());

        assertThat(run.dispatchedPhases().subList(0, DERIVED_DISPATCH_ORDER.size() - 1))
                .as("and no clause is visited twice, so the loop terminates through the catch-all"
                        + " rather than by exhausting a bound")
                .doesNotHaveDuplicates();

        assertThat(run.statementsWritten())
                .as("the run reached its mainline and produced a statement, which is what makes the"
                        + " observed phase sequence a record of real work rather than of an empty pass")
                .isPositive();
    }

    @Test
    @DisplayName("the read loop keeps three outcomes distinct: success re-enters the read, "
            + "end-of-file leaves forward, anything else is an error")
    void theReadLoopKeepsThreeOutcomesDistinct() {
        assertThat(FileStatus.fromCode("00"))
                .as("SUCCESS is the outcome that re-enters the read paragraph, and it is one of the"
                        + " only three status codes compared anywhere in the estate")
                .contains(FileStatus.SUCCESS);
        assertThat(FileStatus.fromCode("10"))
                .as("END-OF-FILE is a separate outcome that leaves forward to the phase exit. It is"
                        + " never folded into the error branch: every batch read loop in the estate"
                        + " depends on telling the two apart, and a two-way model would erase that")
                .contains(FileStatus.END_OF_FILE);
        assertThat(FileStatus.END_OF_FILE)
                .as("and the two are genuinely different values, not two spellings of one")
                .isNotEqualTo(FileStatus.SUCCESS);
        assertThat(FileStatus.fromCode("23"))
                .as("the third and last code compared anywhere in the estate; it falls to the error"
                        + " branch here, which logs and abends rather than leaving quietly")
                .contains(FileStatus.RECORD_NOT_FOUND);
        assertThat(FileStatus.RECORD_NOT_FOUND)
                .as("and it is neither of the two outcomes that leave the loop without an error, so"
                        + " the third branch really is a third branch")
                .isNotEqualTo(FileStatus.SUCCESS)
                .isNotEqualTo(FileStatus.END_OF_FILE);

        final ObservedRun run = generateOver(SEEDED_CARD_WITH_CROSS_REFERENCE);
        assertThat(run.transactionsTabulated())
                .as("the success branch was taken for each served record before end-of-file ended the"
                        + " loop, so both of the two non-error outcomes were exercised on one pass:"
                        + " %d record(s) were tabulated and then the read reported end of file",
                        run.transactionsTabulated())
                .isEqualTo(GENERATOR_PROBE_IDS.size());
        assertThat(run.cardsTabulated())
                .as("and they were tabulated against one card, so the loop re-entered its own read"
                        + " rather than restarting a phase")
                .isEqualTo(1);
    }

    // ===============================================================================================
    // THE TWO OUTPUTS: EIGHTY BYTES AND ONE HUNDRED
    // ===============================================================================================

    @Test
    @DisplayName("every plain statement record is exactly eighty encoded bytes and the artefact is "
            + "an exact multiple of eighty")
    void everyStatementRecordIsExactlyEightyEncodedBytes() {
        assertThat(this.cleanRun.statementRecords())
                .as("the run has to have written statements for the assertion to mean anything")
                .isNotEmpty();
        for (final String record : this.cleanRun.statementRecords()) {
            assertThat(record.getBytes(StandardCharsets.US_ASCII).length)
                    .as("counted in encoded bytes and never trimmed: [%s]", record)
                    .isEqualTo(STATEMENT_RECORD_WIDTH);
        }
    }

    @Test
    @DisplayName("every markup statement record is exactly one hundred encoded bytes, resolving the "
            + "member's own eighty-versus-one-hundred disagreement to one hundred")
    void everyHtmlRecordIsExactlyOneHundredEncodedBytes() {
        assertThat(this.cleanRun.htmlRecords()).isNotEmpty();
        for (final String record : this.cleanRun.htmlRecords()) {
            assertThat(record.getBytes(StandardCharsets.US_ASCII).length)
                    .as("the member declares this destination twice and disagrees with itself -"
                            + " eighty bytes at one line and one hundred at another. It is resolved"
                            + " to one hundred, matching the width the emitting program writes;"
                            + " eighty would truncate every markup record. Recorded as a"
                            + " decision-log candidate: [%s]", record)
                    .isEqualTo(HTML_RECORD_WIDTH)
                    .isNotEqualTo(STATEMENT_RECORD_WIDTH);
        }
    }

    @Test
    @DisplayName("both banner literals reach the output byte for byte, at their two different and "
            + "deliberately unequal splits")
    void bothBannerLiteralsReachTheOutputByteForByte() {
        assertThat(START_BANNER.getBytes(StandardCharsets.US_ASCII).length)
                .isEqualTo(STATEMENT_RECORD_WIDTH);
        assertThat(END_BANNER.getBytes(StandardCharsets.US_ASCII).length)
                .isEqualTo(STATEMENT_RECORD_WIDTH);
        assertThat(START_BANNER_ASTERISKS)
                .as("the two splits are 31/18/31 and 32/16/32; neither is corrected toward the other,"
                        + " and a shared centring helper would quietly make them agree")
                .isNotEqualTo(END_BANNER_ASTERISKS);

        assertThat(this.cleanRun.statementRecords())
                .as("the start banner, assembled here from asterisk counts and its own text rather"
                        + " than read from the template holder")
                .contains(START_BANNER);
        assertThat(this.cleanRun.statementRecords())
                .as("and the end banner, on its own split")
                .contains(END_BANNER);
    }

    @Test
    @DisplayName("all six rule lines are emitted per statement and none of them is de-duplicated "
            + "away")
    void allSixRuleLinesAreEmittedPerStatement() {
        final ObservedRun run = generateOver(SEEDED_CARD_WITH_CROSS_REFERENCE);

        assertThat(RULE_LINE.getBytes(StandardCharsets.US_ASCII).length)
                .as("a rule line is eighty hyphens and nothing else")
                .isEqualTo(STATEMENT_RECORD_WIDTH);

        assertThat(occurrencesOf(run.statementRecords(), RULE_LINE))
                .as("three declared positions, two of them written a second time, is %d emissions per"
                        + " statement. They are separate emissions at separate positions: collapsing"
                        + " them to one constant emitted once would silently shorten every statement,"
                        + " so the count is asserted rather than the mere presence",
                        RULE_LINES_PER_STATEMENT)
                .isEqualTo((long) RULE_LINES_PER_STATEMENT * run.statementsWritten());

        assertThat(run.statementRecords())
                .as("and they sit between the two banners rather than at the edges of the statement")
                .startsWith(START_BANNER)
                .endsWith(END_BANNER);
    }

    @Test
    @DisplayName("the malformed table tag is emitted unrepaired, and the markup path uses literal "
            + "constants with no templating engine")
    void theMalformedTableTagIsEmittedUnrepaired() {
        assertThat(MALFORMED_TABLE_TAG)
                .as("two adjacent spaces after the element name, which is the malformation")
                .contains("<table  ");

        assertThat(this.cleanRun.htmlRecords())
                .as("emitted exactly as the source emits it: not collapsed, not normalised, not"
                        + " repaired. Byte equivalence is the contract that this output has to"
                        + " satisfy; well-formed markup is not, and repairing the tag would fail a"
                        + " byte comparison while looking like an improvement")
                .contains(atWidth(MALFORMED_TABLE_TAG, HTML_RECORD_WIDTH));

        assertThat(this.cleanRun.htmlRecords())
                .as("the document preamble and its close are likewise invariant literals emitted in"
                        + " source order - there is no template to interpolate, no whitespace"
                        + " normalisation and no ordering variability for a comparison to trip over")
                .contains(atWidth(HTML_DOCUMENT_TYPE, HTML_RECORD_WIDTH))
                .contains(atWidth(HTML_DOCUMENT_CLOSE, HTML_RECORD_WIDTH));

        assertThat(this.cleanRun.htmlRecords().getFirst())
                .as("and the preamble is genuinely first, which a templating engine reordering its"
                        + " fragments would not guarantee")
                .isEqualTo(atWidth(HTML_DOCUMENT_TYPE, HTML_RECORD_WIDTH));
    }

    // ===============================================================================================
    // THE DATA-ACCESS COLLABORATOR, AND THE ONE CUSTOMER ENTITY
    // ===============================================================================================

    @Test
    @DisplayName("a markup-significant byte in seeded data reaches the produced markup artefact "
            + "verbatim, and no character reference appears anywhere in it (DL-209)")
    void seededMarkupSignificantBytesReachTheArtefactVerbatim() {
        // The seeded estate carries apostrophes in customer surnames and transaction descriptions, so
        // this is not a synthetic probe: it is what the pipeline actually produces from the delivered
        // reference data. An earlier delivery escaped these positions, which kept the record a hundred
        // bytes wide while displacing every byte after the substitution and pushing the record tail off
        // the end - a byte-parity defect against this module's own hundred-byte oracle. Asserted here,
        // against the artefact a real run wrote through a real server, rather than only at the template
        // class, because that is where the defect was observable.
        final List<String> markupBearing = this.cleanRun.htmlRecords().stream()
                .filter(record -> record.indexOf('\'') >= 0)
                .toList();

        assertThat(markupBearing)
                .as("the seeded reference data carries apostrophes, so a run that produced none would"
                        + " mean this assertion is matching nothing at all")
                .isNotEmpty();

        for (final String record : markupBearing) {
            assertThat(record.getBytes(StandardCharsets.US_ASCII).length)
                    .as("a record carrying an apostrophe is the same hundred bytes as every other"
                            + " record - escaping it would have widened the value and cut the tail:"
                            + " [%s]", record)
                    .isEqualTo(HTML_RECORD_WIDTH);
            assertThat(record)
                    .as("and it still closes with the tag the composition puts there, which a"
                            + " displaced tail would have lost: [%s]", record)
                    .contains(HTML_PARAGRAPH_CLOSE);
        }

        final String whole = String.join("", this.cleanRun.htmlRecords());
        for (final String reference : HTML_CHARACTER_REFERENCES) {
            assertThat(whole)
                    .as("%s must not appear anywhere in the artefact: the emitting program encodes"
                            + " nothing and neither does this translation", reference)
                    .doesNotContain(reference);
        }
    }

    @Test
    @DisplayName("the file handler is an injected collaborator and its typed request round-trips all "
            + "four record types with their own status fields")
    void theFileHandlerRoundTripsAllFourRecordTypes() {
        assertThat(this.statementDataAccessService)
                .as("the legacy generator reached its file handler through thirteen static call"
                        + " sites passing one shared linkage area; here it is one injected"
                        + " collaborator taking a typed request and returning a typed response")
                .isNotNull();

        final StatementTransactionSource source = new ConstructedTransactionSource(List.of(
                projectIndependently(TestDataFactory.transaction()
                        .id(GENERATOR_PROBE_IDS.get(0))
                        .cardNumber(SEEDED_CARD_WITH_CROSS_REFERENCE)
                        .amount(PROBE_AMOUNT)
                        .originalTimestamp(PROBE_ORIGINATION_TIMESTAMP)
                        .processingTimestamp(PROBE_PROCESSING_TIMESTAMP)
                        .image())));

        final List<String> recordTypes = List.of(StatementDataAccessService.DD_TRNXFILE,
                StatementDataAccessService.DD_XREFFILE, StatementDataAccessService.DD_CUSTFILE,
                StatementDataAccessService.DD_ACCTFILE);
        assertThat(recordTypes)
                .as("four record types, one per data definition the generator opens, each matching a"
                        + " name written out in this test rather than taken on trust from the"
                        + " collaborator that echoes it")
                .containsExactlyElementsOf(DECLARED_RECORD_TYPES)
                .doesNotHaveDuplicates();

        for (final String recordType : recordTypes) {
            assertThat(recordType.getBytes(StandardCharsets.US_ASCII).length)
                    .as("each record type names an eight-byte data definition, which is why the"
                            + " echoed name below is compared whole rather than trimmed")
                    .isEqualTo(RECORD_TYPE_WIDTH);
        }

        for (final String recordType : recordTypes) {
            final StatementDataAccessService.StatementFileResponse response =
                    this.statementDataAccessService.execute(
                            new StatementDataAccessService.StatementFileRequest(recordType,
                                    StatementDataAccessService.OPERATION_OPEN, "  ", "", 0, "", 0,
                                    UnaryOperator.identity()),
                            source, this.statementDataAccessService.openCrossReferenceSource());

            assertThat(response.ddName())
                    .as("the response names the record type it answers for, so four separate status"
                            + " fields cannot be confused with one another. The name is an"
                            + " eight-byte field, so it is compared whole: trimming here would hide"
                            + " a padding regression rather than tolerate one")
                    .isEqualTo(recordType);
            assertThat(response.ddName().getBytes(StandardCharsets.US_ASCII).length)
                    .as("and the echoed name keeps that eight-byte width on the way back")
                    .isEqualTo(RECORD_TYPE_WIDTH);
            assertThat(response.status())
                    .as("and each record type carries its own status field, which round-trips as a"
                            + " recognised code rather than as an opaque string")
                    .isPresent();
            assertThat(response.returnCode().getBytes(StandardCharsets.US_ASCII).length)
                    .as("the status field keeps the two-byte width the linkage area declared")
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("the five-hundred-byte customer layout the generator consumes is an alternate view "
            + "of the one customer entity, not a second entity")
    void theFiveHundredByteCustomerLayoutIsOneEntity() {
        assertThat(APPLICATION_TABLES)
                .as("the generator includes a five-hundred-byte customer layout of its own, which is"
                        + " an alternate view of the same record rather than a second record type:"
                        + " one customer table, and no second one beside it")
                .containsOnlyOnce("customer");

        assertThat(APPLICATION_TABLES.stream().filter(name -> name.contains("customer")).toList())
                .as("nothing named for the variant layout appears in the schema")
                .containsExactly("customer");

        assertThat(generateOver(SEEDED_CARD_WITH_CROSS_REFERENCE).statementsWritten())
                .as("and a statement is produced from it, so the one entity really is what the"
                        + " variant layout is read through")
                .isPositive();
    }

    // ===============================================================================================
    // OBSERVABILITY, AND THE STRUCTURAL BOUND THAT IS NOT A BUSINESS RULE
    // ===============================================================================================

    @Test
    @DisplayName("each of the four steps publishes its own timer, read for presence and shape only")
    void eachStepPublishesItsOwnTimer() {
        final List<String> stepNames = List.of(
                CreateStatementJobConfig.ORDER_AND_REPROJECT_STEP_NAME,
                CreateStatementJobConfig.LOAD_WORK_RESOURCE_STEP_NAME,
                CreateStatementJobConfig.CLEAR_STATEMENT_OUTPUTS_STEP_NAME,
                CreateStatementJobConfig.GENERATE_STATEMENTS_STEP_NAME);

        // Collected by meter name and then by tag VALUE rather than by a guessed tag key, so the
        // assertion is about the observation being published and carrying the step's identity - which
        // is the shape that matters - rather than about the framework's current choice of key spelling.
        final List<String> observedNames = this.meterRegistry.find(STEP_METER_NAME).meters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getValue)
                .distinct()
                .toList();

        assertThat(observedNames)
                .as("the meter %s has to exist at all; the meters this run did publish are %s",
                        STEP_METER_NAME,
                        this.meterRegistry.getMeters().stream()
                                .map(meter -> meter.getId().getName())
                                .filter(name -> name.startsWith("spring.batch"))
                                .distinct()
                                .toList())
                .isNotEmpty();

        for (final String stepName : stepNames) {
            assertThat(observedNames)
                    .as("step %s has to be observable, because the display statements the estate used"
                            + " as its only diagnostic channel become structured logging and meters."
                            + " Presence and shape only: no latency, throughput or capacity figure is"
                            + " asserted anywhere, because the estate documents no baseline to assert"
                            + " one against", stepName)
                    .contains(stepName);
        }
    }

    @Test
    @DisplayName("the fifty-one by ten card table is the shape of the legacy working storage, and the "
            + "migrated job refuses to exceed it loudly rather than truncating silently")
    void theCardTableBoundIsStructuralAndIsEnforcedRatherThanTruncated() {
        assertThat(STRUCTURAL_CARD_TABLE_ENTRIES)
                .as("the generator declares a card table of fifty-one entries and a per-card counter"
                        + " table of the same size. This is the shape of the legacy tabulation area,"
                        + " not a rule about how much business data may exist")
                .isEqualTo(StatementGenerationService.MAX_CARD_ENTRIES);
        assertThat(STRUCTURAL_TRANSACTIONS_PER_CARD)
                .as("and a per-card transaction table of ten")
                .isEqualTo(StatementGenerationService.MAX_TRANSACTIONS_PER_CARD);

        assertThat(this.cleanRun.projectedRecords().size())
                .as("nothing truncates on the way in: the ordering step carries every row of the"
                        + " consolidated store into the work resource, far more rows than the card"
                        + " table has entries, because the bound applies to the tabulation and not to"
                        + " the ordered result")
                .isGreaterThan(STRUCTURAL_CARD_TABLE_ENTRIES);

        // One card past the bound. The legacy program does not quietly drop the fifty-second card: it
        // reports the full table and abends, and reproducing that refusal is what faithfulness means
        // here. A translation that silently ignored the extra card would produce a statement run that
        // looked successful while omitting a cardholder.
        final List<String> beyondTheBound = new ArrayList<>();
        for (int card = 0; card <= STRUCTURAL_CARD_TABLE_ENTRIES; card++) {
            beyondTheBound.add(projectIndependently(TestDataFactory.transaction()
                    .id(String.format(Locale.ROOT, "99300000000000%02d", card))
                    .cardNumber(String.format(Locale.ROOT, "%016d", card))
                    .amount(PROBE_AMOUNT)
                    .originalTimestamp(PROBE_ORIGINATION_TIMESTAMP)
                    .processingTimestamp(PROBE_PROCESSING_TIMESTAMP)
                    .image()));
        }
        final StatementTransactionSource pastCapacity =
                new ConstructedTransactionSource(List.copyOf(beyondTheBound));

        assertThat(beyondTheBound)
                .as("the constructed source carries one card more than the table has entries")
                .hasSize(STRUCTURAL_CARD_TABLE_ENTRIES + 1);
        assertThatThrownBy(() -> this.statementProcessor.process(
                new StatementProcessor.StatementRunRequest(pastCapacity, new CollectingSink())))
                .as("the fifty-second card is refused loudly - the run abends, exactly as the legacy"
                        + " program does when its tabulation area is full. This is asserted as a"
                        + " reproduction of legacy behaviour, not as a business limit on the migrated"
                        + " service")
                .isInstanceOf(AbendException.class);
    }

    /**
     * The context slice: the four links of the pipeline and the collaborators they inject.
     *
     * <p>Declared explicitly rather than scanned, so that a component scan cannot sweep the test tree
     * into the context and so that the graph this test exercises is visible in one place. The real
     * publication lock is imported rather than a stand-in because this slice has the PostgreSQL server
     * the production lock needs, which keeps the staging behaviour faithful.
     *
     * <p>Three beans are supplied here. The clock is the shared pinned one, so nothing in the graph
     * reads a system clock and every derived date means the same thing on every run. The object store
     * and the notification publisher are stand-ins: neither the storage nor the messaging contract is
     * this test's subject, both have their own container-backed suites, and reaching a real endpoint
     * from here would make a parity assertion depend on an unrelated service being up.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({CreateStatementJobConfig.class, PostTransactionJobConfig.class,
            InterestCalculationJobConfig.class, CombineTransactionsJobConfig.class,
            BatchConfig.class, JpaAuditConfig.class, JobParameterValidators.class,
            BatchStagingArea.class, StagedGenerationStore.class,
            AdvisoryGenerationPublicationLock.class, FixedWidthFlatFileReaderFactory.class,
            StatementGenerationService.class, StatementDataAccessService.class,
            SensitiveFieldEncryptionService.class, AbendService.class, DateValidationService.class,
            TransactionPostingService.class, PostingStageTransactionBoundary.class,
            InterestCalculationService.class, InterestGroupTransactionBoundary.class,
            RecordWriter.class})
    @EnableConfigurationProperties(AwsProperties.class)
    @EnableJpaRepositories(basePackageClasses = TransactionRepository.class)
    @EntityScan(basePackageClasses = Transaction.class)
    static class JobContext {

        JobContext() {
            // Intentionally empty: this slice contributes beans, not state.
        }

        /**
         * The pinned clock, so no collaborator in the graph reads a system clock.
         *
         * <p>Marked as the preferred candidate rather than replacing the auditing configuration's own
         * clock, because that configuration belongs to the production graph and is imported whole. A
         * system clock reaching a batch step would make a derived date drift away from the seeded
         * data one day at a time, which is exactly the non-determinism the shared pinned instant
         * exists to remove.
         *
         * @return a clock frozen at the shared pinned instant
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return FIXED_CLOCK;
        }

        /**
         * A stand-in for the object store the staging area publishes through.
         *
         * @return an object store that reports no existing generations
         */
        @Bean
        S3Operations objectStore() {
            final S3Operations objectStore = mock(S3Operations.class);
            when(objectStore.listObjects(anyString(), anyString())).thenReturn(List.of());
            return objectStore;
        }

        /**
         * A stand-in for the notification publisher a completed job would fan out through.
         *
         * @return a notification publisher that records nothing
         */
        @Bean
        SnsOperations notifications() {
            return mock(SnsOperations.class);
        }
    }
}
