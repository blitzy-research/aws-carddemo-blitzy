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

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.PathResource;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.batch.step.AbstractCobolStep;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.service.FileMaintenanceService;
import com.carddemo.util.FixedWidthFieldReader;
import com.carddemo.util.TranCatBalRecordMapper;
import com.carddemo.util.ZonedDecimalCodec;

/**
 * The transaction-category-balance listing, translated from the legacy job stream
 * {@code app/jcl/PRTCATBL.jcl} at commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The stream draws on the
 * cataloged unload wrapper {@code app/proc/REPROC.prc} with its control member
 * {@code app/ctl/REPROCT.ctl}, and on the generation-group base declared in
 * {@code app/jcl/DEFGDGB.jcl}. Every figure below was measured by direct read of those members; no
 * statement text from any of them is reproduced here.
 *
 * <h2>Two corrections to the planning material, both established by measurement</h2>
 *
 * <p><strong>This job has no application COBOL antecedent.</strong> The planning material describes
 * it as a listing driven by the sequential-reader program {@code CBACT03C} by way of
 * {@link FileMaintenanceService}. Direct measurement disproves that on both counts. The job member
 * carries three steps and not one of them invokes an application COBOL program: the first invokes the
 * system no-op allocation utility, the second invokes the cataloged dataset-copy wrapper, and the
 * third invokes the external sort. There is therefore no program logic to translate, and the
 * ordering and the output format come <em>entirely</em> from this job stream's own specification -
 * see the sort specification below.
 *
 * <p><strong>{@code CBACT03C} reads the card cross-reference file, not the category balance.</strong>
 * Its own header states that its function is to read and print the account cross-reference data
 * file; its file selection assigns the cross-reference resource with indexed organisation,
 * sequential access and a record key of the cross-reference card number; its record description
 * splits a fifty-byte record into a sixteen-character card-number key plus thirty-four bytes of
 * remainder; and it includes the card cross-reference copybook. Exactly one job member invokes it -
 * the cross-reference verification job - so it belongs to the file-probe job configuration and not
 * here. The mislabel was plausible only because of a width coincidence: the cross-reference record
 * and the transaction-category-balance record are <em>both</em> fifty bytes, yet they are different
 * files with different keys. A census across the ten legacy batch programs confirms the point from
 * the other direction: only the interest program and the posting program reference the
 * category-balance resource at all, and both do so transactionally rather than as a listing driver.
 * No application program lists the category balance sequentially. Both corrections are raised for
 * {@code docs/decision-log.md}; no file outside this one is edited to record them, and in particular
 * the service layer is not "fixed".
 *
 * <p><strong>How {@link FileMaintenanceService} is nonetheless used.</strong> It owns the shared
 * sequential-read and two-level file-status discipline that every sequential listing in this estate
 * follows, and this job is a sequential listing. Only its generic entry points are used: the
 * category-balance sequential read, which performs the whole open, read-to-end and close pass and
 * reports the terminal status and the record count, and the operator-facing status display, which
 * emits and returns without raising so that a caller which has not yet decided to abend may use it.
 * No cross-reference-oriented method of that service is called from here. The service's own
 * execution banner names the legacy member it was built around, which is the very mislabel recorded
 * above; this job's banners name its own legacy steps instead.
 *
 * <h2>The measured job stream: three steps, zero condition-code gates</h2>
 *
 * <table>
 * <caption>Legacy steps and their Java counterparts</caption>
 * <tr><th>Legacy step</th><th>Invokes</th><th>Gate</th><th>Java step</th></tr>
 * <tr><td>{@code DELDEF}</td><td>no-op allocation utility, with a data definition whose disposition
 *     deletes the prior report output</td><td>none</td>
 *     <td>{@link #CLEAR_PRIOR_REPORT_STEP_NAME} - clears the prior report output idempotently</td></tr>
 * <tr><td>{@code STEP05R}</td><td>cataloged dataset-copy wrapper</td><td>none</td>
 *     <td>{@link #UNLOAD_STEP_NAME} - unloads the category balance to a new backup generation at
 *     {@link #UNLOAD_RECORD_LENGTH} bytes per record</td></tr>
 * <tr><td>{@code STEP10R}</td><td>external sort</td><td>none</td>
 *     <td>{@link #SORT_AND_REPROJECT_STEP_NAME} - orders and reprojects to the report at
 *     {@link #REPORT_RECORD_LENGTH} bytes per record</td></tr>
 * </table>
 *
 * <p>The member declares <strong>no condition-code dependency on any step</strong>, so this job
 * carries <strong>no failure-ending transition</strong>. The statement job's three gates and the
 * backup job's one are that other work's contract, not this one's, and adding one here by analogy
 * would refuse to run steps the legacy stream runs unconditionally.
 *
 * <p>The first step's data definition uses a two-element disposition whose second element deletes
 * the report output. Because the first element allocates the dataset when it is absent, the step
 * cannot fail on a first run, so the Java equivalent clears the output <em>idempotently</em> and
 * reports the success status either way. Reporting the optional-file status instead would abend,
 * because {@link FileStatus#isSuccess()} accepts only {@code "00"}.
 *
 * <p>The second step's output is a new generation of the category-balance backup base, fixed-length
 * blocked, {@link #UNLOAD_RECORD_LENGTH} bytes per record. That base is declared with a limit of
 * five generations and scratch-on-roll-off. The generation is modelled as a distinct output resource
 * per job execution, named in the legacy absolute-generation form from the job execution identifier,
 * and every resource is resolved from configuration by its logical name. Retention is deliberately
 * outside this job's remit: it is a property of the generation group, not of the job that writes a
 * generation. The copy wrapper is a parameterised single-step utility whose sole executable control
 * statement copies input to output; in Java that is one ordinary read-and-write step, with no
 * shell-out, no process spawn and no external tool.
 *
 * <h2>The fourth sort specification, and why its comparator is private to this class</h2>
 *
 * <p>Field symbols, at one-relative offsets into the {@link #UNLOAD_RECORD_LENGTH}-byte record:
 *
 * <table>
 * <caption>Sort field symbols as the job stream declares them</caption>
 * <tr><th>Field</th><th>Offset</th><th>Length</th><th>Type</th></tr>
 * <tr><td>account identifier</td><td>1</td><td>11</td><td>zoned decimal</td></tr>
 * <tr><td>transaction type code</td><td>12</td><td>2</td><td>character</td></tr>
 * <tr><td>transaction category code</td><td>14</td><td>4</td><td>zoned decimal</td></tr>
 * <tr><td>category balance</td><td>18</td><td>11</td><td>zoned decimal</td></tr>
 * </table>
 *
 * <p>Those figures independently corroborate the copybook layout: 11 + 2 + 4 is a seventeen-byte
 * composite key, and a further eleven bytes of balance brings the mapped prefix to twenty-eight,
 * leaving twenty-two bytes of trailing filler inside the fifty-byte record - exactly what
 * {@link TranCatBalRecordMapper} declares.
 *
 * <p>The ordering is <strong>account identifier, then transaction type code, then transaction
 * category code, all three ascending</strong>. The balance is <strong>not</strong> a sort key and is
 * never ordered by.
 *
 * <p>The comparator that expresses this is {@code private} to this class, is never shared and is
 * never imported. The reason is a parity defect that would compile cleanly. Across all
 * twenty-eight legacy programs there is not one internal sort or merge statement: every ordering in
 * the estate is external, and there are <strong>four</strong> distinct specifications rather than the
 * three the planning material names - this is the fourth. The specifications disagree about typing:
 * the same transaction card-number field is declared character in the statement job and zoned decimal
 * in the transaction-report job, and this specification mixes zoned-decimal and character keys
 * <em>within itself</em>. A shared comparator would silently apply one job's typing to another job's
 * data. Each key is therefore implemented with the typing its own specification declares, rather
 * than relying on any coincidence between numeric and lexicographic order: the zoned-decimal keys
 * are decoded to signed values by {@link ZonedDecimalCodec}, whose overpunch convention folds the
 * sign into the final byte, and the character key compares lexicographically.
 *
 * <h2>The reprojection and a fifth contractual output width</h2>
 *
 * <p>The reprojection emits, in order: the account identifier, one blank, the transaction type code,
 * one blank, the transaction category code, one blank, then the balance rendered under an edit mask
 * of nine integer digit positions, a decimal point and two fractional digit positions - twelve
 * characters - followed by trailing blank filler. That is {@link #REPORT_CONTENT_LENGTH} content
 * bytes.
 *
 * <p>The declared output record length is {@link #REPORT_RECORD_LENGTH} bytes, fixed-length blocked.
 * <strong>This is a fifth fixed output width in the estate</strong>, alongside the eighty-byte
 * statement, the hundred-byte HTML statement, the hundred-and-thirty-three-byte transaction report
 * and the four-hundred-and-thirty-byte reject record. The planning material names only those four.
 * The fifth width is raised here for {@code docs/gate-evidence.md} and {@code docs/decision-log.md},
 * and it needs a golden fixture of its own in the expected-output fixtures - an artefact of the test
 * estate rather than of this configuration.
 *
 * <p><strong>Anomaly - a one-byte projection conflict.</strong> The reprojection's own segments total
 * 11 + 1 + 2 + 1 + 4 + 1 + 12 plus trailing filler, and the filler the reprojection declares is nine
 * blanks, which makes forty-one bytes against a declared record length of forty. The reprojection
 * sits at lines 53 to 56 of the job member and the record length at line 61. The conflict is
 * <strong>resolved to forty</strong>, because the record-length declaration is the dataset contract
 * that every downstream reader of that dataset is written against: this job emits
 * {@link #REPORT_CONTENT_LENGTH} content bytes followed by {@link #REPORT_TRAILING_FILLER_LENGTH}
 * blanks - eight, not nine - and never forty-one. The conflict is raised for the decision log.
 *
 * <p>The width is guaranteed by construction rather than asserted afterwards: the line is assembled
 * through {@link FixedWidthFieldReader#builder(String, int)}, whose buffer is allocated at the
 * declared width, so the emitted image is exactly {@link #REPORT_RECORD_LENGTH} encoded bytes. All
 * fixed-width offset knowledge stays in the utility layer; this configuration composes the
 * placements of its own output specification rather than slicing bytes itself.
 *
 * <p><strong>The edit mask.</strong> The balance is rendered from a {@link BigDecimal} scaled by
 * {@link ZonedDecimalCodec#toMonetaryScale(BigDecimal)}; no floating-point type appears anywhere, and
 * the truncating rounding rule the estate implies - the rounding keyword occurs nowhere in it - lives
 * exclusively in that codec, so no scaling call is made here. The mask's digit selectors suppress
 * leading zeros, and the specification requests no sign characters, so the magnitude is rendered and
 * a negative balance is indistinguishable from its positive counterpart in this report. That is a
 * property of the specification rather than a translation choice and is raised for the decision log.
 * A value of exactly zero blanks the whole field, which is the convention the module's existing
 * amount masks already follow. Every balance in the measured fixture and in the seeded reference data
 * is exactly zero, so every measured record renders identically under either reading of the leading
 * zero-suppression selector; the only case the two readings could separate is a non-zero magnitude
 * below one, and that case is resolved toward the module's existing convention and recorded.
 *
 * <h2>The legacy comment contradicts the legacy code</h2>
 *
 * <p>Line 41 of the job member is a comment claiming that the sort step filters by a parameter date
 * and orders by card number. The control stream does <strong>neither</strong>: it carries no
 * record-inclusion predicate of any kind, and its keys are the account identifier, the type code and
 * the category code. The comment additionally carries a botched find-and-replace artefact and a
 * doubled article. <strong>This job therefore takes no date job parameter, applies no date filter and
 * never orders by card number</strong>, and no date-range job-parameter validator is attached to it.
 * An agent following the comment rather than the code would have invented both. Recorded here and
 * raised for the decision log, together with the observation that the comment banner at line 26
 * carries a stray backtick inside its rule.
 *
 * <h2>Wiring, layering and observability</h2>
 *
 * <p>Bean methods are not proxied, which is what lets this class be {@code final}; the job bean
 * therefore receives its three steps as qualified parameters resolved from the container rather than
 * by calling its own bean methods. The two shared collaborators the batch infrastructure publishes -
 * the job-boundary listener and the run incrementer - are injected <em>by framework type</em> rather
 * than by importing the configuration that declares them, because the module's package layering
 * permits configuration to depend on batch and not the reverse. The incrementer is what allows this
 * job to be launched and advanced by name.
 *
 * <p>Nothing fires when the context starts: the shipped launch-on-start setting is disabled, and this
 * class registers no runner, no lifecycle hook and no scheduler. Execution is strictly sequential -
 * no task executor, no partitioning, no parallel flow - because the ordering this job exists to
 * establish is compared byte for byte, and concurrency would perturb it.
 *
 * <p>Each step is timed. The step builders are given the meter registry, and each step's work runs
 * inside {@link AbstractCobolStep}, which times a whole program lifecycle and tags the sample with
 * the legacy step identity, so all three appear separately on the metrics endpoint. Because this job
 * has no application COBOL program, the identity carried into those diagnostics is the legacy
 * <em>step</em> name, each of which fits the legacy abend-culprit width.
 *
 * <p>Per-execution state is held on a short-lived object rather than on this singleton: each step's
 * tasklet constructs a fresh {@link AbstractCobolStep} for the execution it is serving and runs it,
 * so no mutable field is shared between executions and no scoped proxy is introduced. The tasklet
 * also reads the job execution identifier from the chunk context, which is how the unload step and
 * the sort step agree on the same generation without passing state between them.
 *
 * @see AbstractCobolStep
 * @see FixedWidthFlatFileReaderFactory
 * @see TranCatBalRecordMapper
 */
@Configuration(proxyBeanMethods = false)
public final class CategoryBalanceReportJobConfig {

    /**
     * Diagnostic channel for this job, replacing the legacy console-display channel. Shared with the
     * nested step implementations, which are members of this class.
     */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(CategoryBalanceReportJobConfig.class);

    // -----------------------------------------------------------------------------------------------
    // Job and step identities. Declared as constants because the batch controller launches and
    // queries by name, and because the bean names must match for the job bean's qualified parameters
    // to resolve.
    // -----------------------------------------------------------------------------------------------

    /** Name of the job, and of the bean that publishes it. */
    public static final String JOB_NAME = "categoryBalanceReportJob";

    /** Name of the step standing in for the legacy {@code DELDEF} step, and of its bean. */
    public static final String CLEAR_PRIOR_REPORT_STEP_NAME =
            "categoryBalanceReportClearPriorReportStep";

    /** Name of the step standing in for the legacy {@code STEP05R} step, and of its bean. */
    public static final String UNLOAD_STEP_NAME = "categoryBalanceReportUnloadStep";

    /** Name of the step standing in for the legacy {@code STEP10R} step, and of its bean. */
    public static final String SORT_AND_REPROJECT_STEP_NAME =
            "categoryBalanceReportSortAndReprojectStep";

    /** Number of steps this job wires, which is the number the legacy member declares. */
    public static final int STEP_COUNT = 3;

    // -----------------------------------------------------------------------------------------------
    // Record widths. The unload width comes from the record layout; the report width is this job's
    // own output contract.
    // -----------------------------------------------------------------------------------------------

    /**
     * Encoded byte width of one unloaded record, which is the category-balance record layout width.
     */
    public static final int UNLOAD_RECORD_LENGTH = TranCatBalRecordMapper.RECORD_LENGTH;

    /**
     * Encoded byte width of one report record - the fifth fixed output width in the estate, and the
     * declared record length that resolves the projection conflict described in the class
     * documentation.
     */
    public static final int REPORT_RECORD_LENGTH = 40;

    /** Width of one blank separator between two projected fields. */
    public static final int REPORT_SEPARATOR_LENGTH = 1;

    /** Width of the decimal point the edit mask inserts between the integer and fractional digits. */
    public static final int BALANCE_DECIMAL_POINT_LENGTH = 1;

    /** Number of integer digit positions the edited balance carries. */
    public static final int BALANCE_INTEGER_DIGITS =
            ZonedDecimalCodec.CATEGORY_BALANCE_WIDTH - ZonedDecimalCodec.MONETARY_SCALE;

    /**
     * Width of the edited balance: nine integer digit positions, a decimal point and two fractional
     * digit positions.
     */
    public static final int BALANCE_MASK_WIDTH =
            ZonedDecimalCodec.CATEGORY_BALANCE_WIDTH + BALANCE_DECIMAL_POINT_LENGTH;

    /** Content bytes the reprojection emits before the trailing filler. */
    public static final int REPORT_CONTENT_LENGTH = TranCatBalRecordMapper.TRANCAT_ACCT_ID_LENGTH
            + REPORT_SEPARATOR_LENGTH + TranCatBalRecordMapper.TRANCAT_TYPE_CD_LENGTH
            + REPORT_SEPARATOR_LENGTH + TranCatBalRecordMapper.TRANCAT_CD_LENGTH
            + REPORT_SEPARATOR_LENGTH + BALANCE_MASK_WIDTH;

    /**
     * Trailing blanks the report record carries - eight, not the nine the reprojection declares,
     * because the declared record length is the dataset contract.
     */
    public static final int REPORT_TRAILING_FILLER_LENGTH =
            REPORT_RECORD_LENGTH - REPORT_CONTENT_LENGTH;

    // -----------------------------------------------------------------------------------------------
    // Report-line placements. Every width above comes from the record layout in the utility layer; the
    // offsets below are this job's own output specification and belong to it, which is why they are
    // derived here rather than declared anywhere shared.
    // -----------------------------------------------------------------------------------------------

    /** Layout name this job's diagnostics report for the projected report line. */
    private static final String REPORT_LINE_ARTEFACT = "TCATBALF-REPORT-LINE (PRTCATBL STEP10R)";

    /** Offset of the account identifier within the report line. */
    private static final int REPORT_ACCOUNT_ID_OFFSET = 0;

    /** Offset of the blank separating the account identifier from the type code. */
    private static final int REPORT_SEPARATOR_1_OFFSET =
            REPORT_ACCOUNT_ID_OFFSET + TranCatBalRecordMapper.TRANCAT_ACCT_ID_LENGTH;

    /** Offset of the transaction type code within the report line. */
    private static final int REPORT_TYPE_CODE_OFFSET =
            REPORT_SEPARATOR_1_OFFSET + REPORT_SEPARATOR_LENGTH;

    /** Offset of the blank separating the type code from the category code. */
    private static final int REPORT_SEPARATOR_2_OFFSET =
            REPORT_TYPE_CODE_OFFSET + TranCatBalRecordMapper.TRANCAT_TYPE_CD_LENGTH;

    /** Offset of the transaction category code within the report line. */
    private static final int REPORT_CATEGORY_CODE_OFFSET =
            REPORT_SEPARATOR_2_OFFSET + REPORT_SEPARATOR_LENGTH;

    /** Offset of the blank separating the category code from the edited balance. */
    private static final int REPORT_SEPARATOR_3_OFFSET =
            REPORT_CATEGORY_CODE_OFFSET + TranCatBalRecordMapper.TRANCAT_CD_LENGTH;

    /** Offset of the edited balance within the report line. */
    private static final int REPORT_BALANCE_OFFSET =
            REPORT_SEPARATOR_3_OFFSET + REPORT_SEPARATOR_LENGTH;

    /** Offset at which the trailing blank filler of the report line begins. */
    private static final int REPORT_TRAILING_FILLER_OFFSET = REPORT_CONTENT_LENGTH;

    /** Offset of the decimal point within the edited balance. */
    private static final int BALANCE_DECIMAL_POINT_POSITION = BALANCE_INTEGER_DIGITS;

    /** Offset of the first fractional digit within the edited balance. */
    private static final int BALANCE_FIRST_FRACTION_POSITION =
            BALANCE_DECIMAL_POINT_POSITION + BALANCE_DECIMAL_POINT_LENGTH;

    /** The blank the reprojection inserts, and the byte every filler run of the report line carries. */
    private static final char BLANK = ' ';

    /** The digit the edit mask suppresses when it appears in a leading integer position. */
    private static final char ZERO_DIGIT = '0';

    /** The decimal point the edit mask inserts. */
    private static final char DECIMAL_POINT = '.';

    /** Sentinel for "the integer part carries no significant digit". */
    private static final int NO_SIGNIFICANT_DIGIT = -1;

    /**
     * Separator written after each fixed-length record of a staged dataset. Stated as a literal rather
     * than taken from the platform line separator, so that a staged dataset is byte-identical on every
     * host and matches the estate's own sample data files, which the fixed-width reader also expects.
     */
    private static final String RECORD_SEPARATOR = "\n";

    // -----------------------------------------------------------------------------------------------
    // Legacy identities carried into the diagnostics. These are step, data-definition and dataset
    // names, which are identifiers rather than statement text.
    // -----------------------------------------------------------------------------------------------

    /** Legacy step that clears the prior report output; carried into this step's diagnostics. */
    private static final String LEGACY_STEP_CLEAR = "DELDEF";

    /** Legacy step that unloads the cluster to a new backup generation. */
    private static final String LEGACY_STEP_UNLOAD = "STEP05R";

    /** Legacy step that orders the unload and reprojects it to the report. */
    private static final String LEGACY_STEP_SORT = "STEP10R";

    /** Data definition the legacy first step names for the report output it deletes. */
    private static final String DD_REPORT_ALLOCATION = "THEFILE";

    /** Data definition the copy wrapper names for its input, the category-balance cluster. */
    private static final String DD_COPY_INPUT = "FILEIN";

    /** Data definition the copy wrapper names for its output, the new backup generation. */
    private static final String DD_COPY_OUTPUT = "FILEOUT";

    /** Data definition the sort step names for its input, the backup generation just written. */
    private static final String DD_SORT_INPUT = "SORTIN";

    /** Data definition the sort step names for its output, the report. */
    private static final String DD_SORT_OUTPUT = "SORTOUT";

    /** Field label the account-identifier placement and key decode report in diagnostics. */
    private static final String FIELD_TRANCAT_ACCT_ID = "TRANCAT-ACCT-ID";

    /** Field label the type-code placement reports in diagnostics. */
    private static final String FIELD_TRANCAT_TYPE_CD = "TRANCAT-TYPE-CD";

    /** Field label the category-code placement and key decode report in diagnostics. */
    private static final String FIELD_TRANCAT_CD = "TRANCAT-CD";

    /** Field label the edited-balance placement reports in diagnostics. */
    private static final String FIELD_TRAN_CAT_BAL = "TRAN-CAT-BAL";

    /**
     * Modulus of a legacy absolute generation number, whose names run from the first generation to the
     * nine-thousand-nine-hundred-and-ninety-ninth and then wrap. The wrap is reproduced rather than
     * avoided so that a generation name stays the shape an operator recognises; retention, which is a
     * property of the generation group rather than of the job that writes a generation, is deliberately
     * outside this job's remit.
     */
    private static final int GENERATION_NUMBER_MODULUS = 10_000;

    /** Format of a legacy absolute generation name: the base, the generation number, the version. */
    private static final String GENERATION_NAME_FORMAT = "%s.G%04dV00";

    /**
     * Zero scale, used when a zoned-decimal sort key is decoded. The key fields carry no implied
     * decimal positions; only the balance does, and the balance is not a key.
     */
    private static final int KEY_SCALE = 0;

    /**
     * Key sequence of the category-balance cluster, which is the order a sequential unload of a
     * key-sequenced cluster emits. Applied to the unload so that the second step reproduces the
     * utility's output order; the sort specification is applied separately by the third step, exactly
     * as the legacy stream sorts an already key-ordered unload.
     */
    private static final Sort UNLOAD_KEY_SEQUENCE =
            Sort.by(Sort.Direction.ASC, "trancatAcctId", "trancatTypeCd", "trancatCd");

    // -----------------------------------------------------------------------------------------------
    // The fourth sort specification. PRIVATE to this class by necessity - see the class documentation.
    // -----------------------------------------------------------------------------------------------

    /**
     * The composite-key ordering this job's own sort specification declares: account identifier
     * ascending as a zoned-decimal value, then type code ascending as a character value, then
     * category code ascending as a zoned-decimal value.
     */
    private static final Comparator<TransactionCategoryBalanceId> SORT_KEY_ORDER =
            Comparator.<TransactionCategoryBalanceId, BigDecimal>comparing(
                            CategoryBalanceReportJobConfig::accountIdentifierSortKey)
                    // Each stage is a fully witnessed comparator rather than a bare key extractor, so
                    // that the character key cannot be resolved against a numeric overload by
                    // inference, which is precisely the confusion the four specifications invite.
                    .thenComparing(Comparator.<TransactionCategoryBalanceId, String>comparing(
                            TransactionCategoryBalanceId::getTrancatTypeCd))
                    .thenComparing(Comparator.<TransactionCategoryBalanceId, BigDecimal>comparing(
                            CategoryBalanceReportJobConfig::categoryCodeSortKey));

    /**
     * The record ordering the third step applies, expressed over the composite identifier so that the
     * three key parts are never handled as three loose strings. The balance is not consulted.
     */
    private static final Comparator<TransactionCategoryBalance> SORT_SPECIFICATION =
            Comparator.comparing(TransactionCategoryBalance::toId, SORT_KEY_ORDER);

    // -----------------------------------------------------------------------------------------------
    // Collaborators. Constructor injection only; every field final.
    // -----------------------------------------------------------------------------------------------

    /** The framework's metadata repository, given to every builder rather than to a factory. */
    private final JobRepository jobRepository;

    /** Transaction manager each step's tasklet runs under. */
    private final PlatformTransactionManager transactionManager;

    /**
     * The shared job-boundary listener the batch infrastructure publishes, injected by framework type
     * so that this package never depends on the configuration package.
     */
    private final JobExecutionListener jobBoundaryListener;

    /** The shared run incrementer, which is what allows this job to be advanced by name. */
    private final JobParametersIncrementer jobRunIncrementer;

    /**
     * Owner of the shared sequential-read and two-level file-status discipline. Only its generic
     * category-balance read and its operator-facing status display are used from here.
     */
    private final FileMaintenanceService fileMaintenanceService;

    /** Access to the category-balance cluster, which the unload reads in key sequence. */
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /** Source of the fixed-width reader over the unloaded generation. */
    private final FixedWidthFlatFileReaderFactory readerFactory;

    /** Metric registry, given to each step builder and to each step's program lifecycle. */
    private final MeterRegistry meterRegistry;

    /** The module's single clock, which stamps each program lifecycle's boundaries. */
    private final Clock clock;

    /** Directory the staged datasets of this job resolve within. */
    private final String stagingDirectory;

    /** Logical name of the backup generation base the unload writes a new generation of. */
    private final String backupDatasetBase;

    /** Logical name of the report dataset the sort writes and the first step clears. */
    private final String reportDataset;

    /**
     * Wires the job's collaborators. Every resource is named by configuration rather than by a literal
     * path, and each logical name defaults to the name the legacy stream uses for the same resource, so
     * a deployment may relocate the staging area without the job losing the identity of what it writes.
     *
     * @param jobRepository the framework's metadata repository; must not be {@code null}
     * @param transactionManager the transaction manager each step runs under; must not be {@code null}
     * @param jobBoundaryListener the shared job-boundary listener; must not be {@code null}
     * @param jobRunIncrementer the shared run incrementer; must not be {@code null}
     * @param fileMaintenanceService owner of the sequential-read and status discipline; must not be
     *                               {@code null}
     * @param transactionCategoryBalanceRepository access to the category-balance cluster; must not be
     *                                             {@code null}
     * @param readerFactory source of the fixed-width reader; must not be {@code null}
     * @param meterRegistry the metric registry; must not be {@code null}
     * @param clock the module's clock; must not be {@code null}
     * @param stagingDirectory directory the staged datasets resolve within; must not be {@code null}
     * @param backupDatasetBase logical name of the backup generation base; must not be {@code null}
     * @param reportDataset logical name of the report dataset; must not be {@code null}
     */
    public CategoryBalanceReportJobConfig(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            final JobExecutionListener jobBoundaryListener,
            final JobParametersIncrementer jobRunIncrementer,
            final FileMaintenanceService fileMaintenanceService,
            final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            final FixedWidthFlatFileReaderFactory readerFactory,
            final MeterRegistry meterRegistry,
            final Clock clock,
            @Value("${carddemo.batch.category-balance-report.staging-directory:"
                    + "${java.io.tmpdir}}") final String stagingDirectory,
            @Value("${carddemo.batch.category-balance-report.backup-dataset-base:"
                    + "AWS.M2.CARDDEMO.TCATBALF.BKUP}") final String backupDatasetBase,
            @Value("${carddemo.batch.category-balance-report.report-dataset:"
                    + "AWS.M2.CARDDEMO.TCATBALF.REPT}") final String reportDataset) {

        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.jobBoundaryListener = Objects.requireNonNull(jobBoundaryListener, "jobBoundaryListener");
        this.jobRunIncrementer = Objects.requireNonNull(jobRunIncrementer, "jobRunIncrementer");
        this.fileMaintenanceService =
                Objects.requireNonNull(fileMaintenanceService, "fileMaintenanceService");
        this.transactionCategoryBalanceRepository = Objects.requireNonNull(
                transactionCategoryBalanceRepository, "transactionCategoryBalanceRepository");
        this.readerFactory = Objects.requireNonNull(readerFactory, "readerFactory");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stagingDirectory = requireResourceName(stagingDirectory, "stagingDirectory");
        this.backupDatasetBase = requireResourceName(backupDatasetBase, "backupDatasetBase");
        this.reportDataset = requireResourceName(reportDataset, "reportDataset");
    }

    // -----------------------------------------------------------------------------------------------
    // Job and steps.
    // -----------------------------------------------------------------------------------------------

    /**
     * The job, wired as the legacy member wires it: three steps in sequence and not one condition-code
     * gate, because the member declares none. Adding a failure-ending transition here would refuse to
     * run steps the legacy stream runs unconditionally.
     *
     * <p>The steps arrive as qualified parameters rather than as calls to this class's own bean
     * methods, because bean methods are not proxied - which is what allows this class to be
     * {@code final} - so a direct call would build a second, unregistered step.
     *
     * @param clearPriorReportStep the step standing in for the legacy first step; must not be
     *                             {@code null}
     * @param unloadStep the step standing in for the legacy second step; must not be {@code null}
     * @param sortAndReprojectStep the step standing in for the legacy third step; must not be
     *                             {@code null}
     * @return the job, registered under {@link #JOB_NAME}
     */
    @Bean
    public Job categoryBalanceReportJob(
            @Qualifier(CLEAR_PRIOR_REPORT_STEP_NAME) final Step clearPriorReportStep,
            @Qualifier(UNLOAD_STEP_NAME) final Step unloadStep,
            @Qualifier(SORT_AND_REPROJECT_STEP_NAME) final Step sortAndReprojectStep) {

        Objects.requireNonNull(clearPriorReportStep, "clearPriorReportStep");
        Objects.requireNonNull(unloadStep, "unloadStep");
        Objects.requireNonNull(sortAndReprojectStep, "sortAndReprojectStep");

        return new JobBuilder(JOB_NAME, this.jobRepository)
                .incrementer(this.jobRunIncrementer)
                .listener(this.jobBoundaryListener)
                .start(clearPriorReportStep)
                .next(unloadStep)
                .next(sortAndReprojectStep)
                .build();
    }

    /**
     * Clears the prior report output, standing in for the legacy first step. The legacy data definition
     * allocates the dataset when it is absent and deletes it at normal end, so the step cannot fail on
     * a first run; this equivalent is idempotent for the same reason.
     *
     * @return the step, registered under {@link #CLEAR_PRIOR_REPORT_STEP_NAME}
     */
    @Bean
    public Step categoryBalanceReportClearPriorReportStep() {
        return new StepBuilder(CLEAR_PRIOR_REPORT_STEP_NAME, this.jobRepository)
                .tasklet(this::clearPriorReport, this.transactionManager)
                .meterRegistry(this.meterRegistry)
                .build();
    }

    /**
     * Unloads the category-balance cluster to a new backup generation at {@link #UNLOAD_RECORD_LENGTH}
     * bytes per record, standing in for the legacy second step's cataloged copy wrapper. One ordinary
     * read-and-write step: no shell-out, no process spawn, no external tool.
     *
     * @return the step, registered under {@link #UNLOAD_STEP_NAME}
     */
    @Bean
    public Step categoryBalanceReportUnloadStep() {
        return new StepBuilder(UNLOAD_STEP_NAME, this.jobRepository)
                .tasklet(this::unloadToBackupGeneration, this.transactionManager)
                .meterRegistry(this.meterRegistry)
                .build();
    }

    /**
     * Orders the unloaded generation by this job's own sort specification and reprojects it to the
     * report at {@link #REPORT_RECORD_LENGTH} bytes per record, standing in for the legacy third step's
     * external sort.
     *
     * @return the step, registered under {@link #SORT_AND_REPROJECT_STEP_NAME}
     */
    @Bean
    public Step categoryBalanceReportSortAndReprojectStep() {
        return new StepBuilder(SORT_AND_REPROJECT_STEP_NAME, this.jobRepository)
                .tasklet(this::sortAndReproject, this.transactionManager)
                .meterRegistry(this.meterRegistry)
                .build();
    }

    // -----------------------------------------------------------------------------------------------
    // Tasklet adapters. Each constructs a FRESH program lifecycle for the execution it is serving, so
    // every per-execution handle lives on a short-lived object and no mutable field is shared between
    // executions. The chunk context also supplies the job execution identifier, which is how the
    // unload and the sort agree on one generation without state passing between them.
    // -----------------------------------------------------------------------------------------------

    /**
     * Runs the prior-report clearance for one step execution.
     *
     * @param contribution the framework's per-step contribution, unused because the whole step is one
     *                     indivisible pass
     * @param chunkContext the framework's chunk context; must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always
     */
    private RepeatStatus clearPriorReport(final StepContribution contribution,
            final ChunkContext chunkContext) {

        Objects.requireNonNull(chunkContext, "chunkContext");
        new ClearPriorReportStep(this.meterRegistry, this.clock, reportResource()).run();
        return RepeatStatus.FINISHED;
    }

    /**
     * Runs the unload for one step execution, writing the generation this execution owns.
     *
     * @param contribution the framework's per-step contribution, unused for the reason above
     * @param chunkContext the framework's chunk context; must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always
     */
    private RepeatStatus unloadToBackupGeneration(final StepContribution contribution,
            final ChunkContext chunkContext) {

        final Path generation = backupGeneration(jobExecutionIdOf(chunkContext));
        new UnloadStep(this.meterRegistry, this.clock, this.fileMaintenanceService,
                this.transactionCategoryBalanceRepository, generation).run();
        return RepeatStatus.FINISHED;
    }

    /**
     * Runs the ordering and reprojection for one step execution, reading the generation the unload
     * step of the same job execution wrote.
     *
     * @param contribution the framework's per-step contribution, unused for the reason above
     * @param chunkContext the framework's chunk context; must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always
     */
    private RepeatStatus sortAndReproject(final StepContribution contribution,
            final ChunkContext chunkContext) {

        final Path generation = backupGeneration(jobExecutionIdOf(chunkContext));
        new SortAndReprojectStep(this.meterRegistry, this.clock, this.readerFactory, generation,
                reportResource()).run();
        return RepeatStatus.FINISHED;
    }

    /**
     * Reads the job execution identifier the framework assigned, which both staged-dataset steps use to
     * name the one generation their job execution owns.
     *
     * @param chunkContext the framework's chunk context; must not be {@code null}
     * @return the job execution identifier
     */
    private static long jobExecutionIdOf(final ChunkContext chunkContext) {
        Objects.requireNonNull(chunkContext, "chunkContext");
        final Long identifier =
                chunkContext.getStepContext().getStepExecution().getJobExecutionId();
        return Objects.requireNonNull(identifier,
                "the framework must have assigned a job execution identifier before a step runs")
                .longValue();
    }

    // -----------------------------------------------------------------------------------------------
    // Resource resolution. Every resource is a logical name resolved against a configured directory;
    // no path is written into this class.
    // -----------------------------------------------------------------------------------------------

    /**
     * The report dataset, which the first step clears and the third step writes.
     *
     * @return the resolved report resource
     */
    private Path reportResource() {
        return Path.of(this.stagingDirectory).resolve(this.reportDataset);
    }

    /**
     * The backup generation one job execution owns, named in the legacy absolute-generation form.
     *
     * @param jobExecutionId the job execution the generation belongs to
     * @return the resolved generation resource
     */
    private Path backupGeneration(final long jobExecutionId) {
        final String generationName = String.format(Locale.ROOT, GENERATION_NAME_FORMAT,
                this.backupDatasetBase, Math.floorMod(jobExecutionId, GENERATION_NUMBER_MODULUS));
        return Path.of(this.stagingDirectory).resolve(generationName);
    }

    /**
     * Validates a configured logical name, because an absent or blank name would resolve to the staging
     * directory itself and a job would then clear or overwrite a directory rather than a dataset.
     *
     * @param value the configured value
     * @param name the property's role, for the diagnostic
     * @return the validated value
     */
    private static String requireResourceName(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name
                    + " must name a resource; a blank logical name would resolve to the staging"
                    + " directory itself rather than to a dataset");
        }
        return value;
    }

    // -----------------------------------------------------------------------------------------------
    // Sort keys. Each key is decoded with the typing its own specification declares.
    // -----------------------------------------------------------------------------------------------

    /**
     * The account-identifier sort key, decoded as a zoned-decimal value so that the sign the final byte
     * may carry is honoured rather than compared as a character.
     *
     * @param key the composite identifier; must not be {@code null}
     * @return the decoded key value
     */
    private static BigDecimal accountIdentifierSortKey(final TransactionCategoryBalanceId key) {
        return zonedSortKey(Objects.requireNonNull(key, "key").getTrancatAcctId(),
                TranCatBalRecordMapper.TRANCAT_ACCT_ID_LENGTH, FIELD_TRANCAT_ACCT_ID);
    }

    /**
     * The category-code sort key, decoded as a zoned-decimal value for the same reason.
     *
     * @param key the composite identifier; must not be {@code null}
     * @return the decoded key value
     */
    private static BigDecimal categoryCodeSortKey(final TransactionCategoryBalanceId key) {
        return zonedSortKey(Objects.requireNonNull(key, "key").getTrancatCd(),
                TranCatBalRecordMapper.TRANCAT_CD_LENGTH, FIELD_TRANCAT_CD);
    }

    /**
     * Decodes one zoned-decimal sort key. The stored column is variable width while the field the sort
     * specification addresses is fixed, so a short value is restored to its declared width before
     * decoding; that is value-preserving for a zoned-decimal number and is what the fixed-width layer
     * does when it places the same field. The decode itself belongs to the codec, which owns the
     * overpunch convention.
     *
     * @param storedValue the stored key part; must not be {@code null}
     * @param width the declared field width
     * @param fieldName the field's legacy name, for the diagnostic
     * @return the decoded key value
     */
    private static BigDecimal zonedSortKey(final String storedValue, final int width,
            final String fieldName) {

        Objects.requireNonNull(storedValue, fieldName);
        if (storedValue.length() > width) {
            throw new IllegalArgumentException("sort key " + fieldName + " is " + storedValue.length()
                    + " characters but the field the sort specification addresses is " + width
                    + "; a wider value would order against a different field than the specification"
                    + " declares");
        }
        final String image = storedValue.length() == width
                ? storedValue
                : String.valueOf(ZERO_DIGIT).repeat(width - storedValue.length()) + storedValue;
        return ZonedDecimalCodec.decode(image, width, KEY_SCALE, fieldName);
    }

    // -----------------------------------------------------------------------------------------------
    // The edit mask and the report line.
    // -----------------------------------------------------------------------------------------------

    /**
     * Renders the balance under this job's edit mask: {@link #BALANCE_INTEGER_DIGITS} integer digit
     * positions, a decimal point and {@link ZonedDecimalCodec#MONETARY_SCALE} fractional digit
     * positions, always exactly {@link #BALANCE_MASK_WIDTH} characters.
     *
     * <p>Leading integer zeros are suppressed to blanks and a value of exactly zero blanks the whole
     * field, which is the convention the module's existing amount masks follow. The specification
     * requests no sign character, so the magnitude is rendered; a negative balance is therefore
     * indistinguishable from its positive counterpart in this report, which is a property of the
     * specification rather than a translation choice.
     *
     * @param balance the stored balance; must not be {@code null}
     * @return the rendered mask, exactly {@link #BALANCE_MASK_WIDTH} characters
     */
    private static String editedBalance(final BigDecimal balance) {
        Objects.requireNonNull(balance, FIELD_TRAN_CAT_BAL);

        // The codec owns the scale and the truncating rounding rule; no scaling is performed here.
        final BigDecimal scaled = ZonedDecimalCodec.toMonetaryScale(balance);

        final char[] mask = new char[BALANCE_MASK_WIDTH];
        Arrays.fill(mask, BLANK);

        if (scaled.signum() != 0) {
            final String digits = magnitudeDigits(scaled);
            mask[BALANCE_DECIMAL_POINT_POSITION] = DECIMAL_POINT;
            for (int fraction = 0; fraction < ZonedDecimalCodec.MONETARY_SCALE; fraction++) {
                mask[BALANCE_FIRST_FRACTION_POSITION + fraction] =
                        digits.charAt(BALANCE_INTEGER_DIGITS + fraction);
            }
            final int firstSignificant = firstSignificantIntegerDigit(digits);
            if (firstSignificant != NO_SIGNIFICANT_DIGIT) {
                for (int position = firstSignificant; position < BALANCE_INTEGER_DIGITS; position++) {
                    mask[position] = digits.charAt(position);
                }
            }
        }

        return new String(mask);
    }

    /**
     * The balance's magnitude as exactly {@link ZonedDecimalCodec#CATEGORY_BALANCE_WIDTH} digits, left
     * padded, which is the digit string the mask indexes into.
     *
     * @param scaled the balance, already at the monetary scale
     * @return the magnitude digits
     */
    private static String magnitudeDigits(final BigDecimal scaled) {
        final String digits = scaled.abs().unscaledValue().toString();
        if (digits.length() > ZonedDecimalCodec.CATEGORY_BALANCE_WIDTH) {
            throw new IllegalArgumentException(FIELD_TRAN_CAT_BAL + " needs " + digits.length()
                    + " digit(s) at scale " + ZonedDecimalCodec.MONETARY_SCALE
                    + " but the edit mask holds only " + ZonedDecimalCodec.CATEGORY_BALANCE_WIDTH);
        }
        if (digits.length() == ZonedDecimalCodec.CATEGORY_BALANCE_WIDTH) {
            return digits;
        }
        return String.valueOf(ZERO_DIGIT)
                .repeat(ZonedDecimalCodec.CATEGORY_BALANCE_WIDTH - digits.length()) + digits;
    }

    /**
     * Index of the first significant integer digit, or {@link #NO_SIGNIFICANT_DIGIT} when every integer
     * position is a leading zero.
     *
     * @param digits the magnitude digits
     * @return the index, or the sentinel
     */
    private static int firstSignificantIntegerDigit(final String digits) {
        for (int position = 0; position < BALANCE_INTEGER_DIGITS; position++) {
            if (digits.charAt(position) != ZERO_DIGIT) {
                return position;
            }
        }
        return NO_SIGNIFICANT_DIGIT;
    }

    /**
     * Assembles one report line. The buffer is allocated at the declared record length and every
     * placement, filler runs included, is stated explicitly, so the image is exactly
     * {@link #REPORT_RECORD_LENGTH} encoded bytes by construction: {@link #REPORT_CONTENT_LENGTH}
     * content bytes followed by {@link #REPORT_TRAILING_FILLER_LENGTH} blanks, and never the
     * forty-first byte the reprojection's own filler run would have produced.
     *
     * @param balance the record to project; must not be {@code null}
     * @return the report line, exactly {@link #REPORT_RECORD_LENGTH} characters
     */
    private static String reportLine(final TransactionCategoryBalance balance) {
        Objects.requireNonNull(balance, "balance");

        return FixedWidthFieldReader.builder(REPORT_LINE_ARTEFACT, REPORT_RECORD_LENGTH)
                .putNumeric(FIELD_TRANCAT_ACCT_ID, REPORT_ACCOUNT_ID_OFFSET,
                        TranCatBalRecordMapper.TRANCAT_ACCT_ID_LENGTH, balance.getTrancatAcctId())
                .putSpaceFiller(REPORT_SEPARATOR_1_OFFSET, REPORT_SEPARATOR_LENGTH)
                .putAlphanumeric(FIELD_TRANCAT_TYPE_CD, REPORT_TYPE_CODE_OFFSET,
                        TranCatBalRecordMapper.TRANCAT_TYPE_CD_LENGTH, balance.getTrancatTypeCd())
                .putSpaceFiller(REPORT_SEPARATOR_2_OFFSET, REPORT_SEPARATOR_LENGTH)
                .putNumeric(FIELD_TRANCAT_CD, REPORT_CATEGORY_CODE_OFFSET,
                        TranCatBalRecordMapper.TRANCAT_CD_LENGTH, balance.getTrancatCd())
                .putSpaceFiller(REPORT_SEPARATOR_3_OFFSET, REPORT_SEPARATOR_LENGTH)
                // The edited balance may carry blanks and a decimal point, so it is placed as an
                // alphanumeric value at its exact width, which the fixed-width layer places unchanged.
                .putAlphanumeric(FIELD_TRAN_CAT_BAL, REPORT_BALANCE_OFFSET, BALANCE_MASK_WIDTH,
                        editedBalance(balance.getTranCatBal()))
                .putSpaceFiller(REPORT_TRAILING_FILLER_OFFSET, REPORT_TRAILING_FILLER_LENGTH)
                .build()
                .image();
    }

    /**
     * Confirms an emitted record is exactly the declared number of <em>encoded</em> bytes. Character
     * count is deliberately not the measure: a record length is a byte contract, and the two figures
     * part company the moment a value carries anything outside the seven-bit range.
     *
     * @param image the record image about to be written; must not be {@code null}
     * @param declaredLength the declared record length in encoded bytes
     * @param resource the data definition the record is written to, for the diagnostic
     */
    private static void requireEncodedWidth(final String image, final int declaredLength,
            final String resource) {

        Objects.requireNonNull(image, "image");
        final int encoded = image.getBytes(StandardCharsets.US_ASCII).length;
        if (encoded != declaredLength) {
            throw new IllegalStateException("record written to " + resource + " is " + encoded
                    + " encoded byte(s) but the declared record length is " + declaredLength
                    + "; a record of the wrong width would leave every downstream reader of that"
                    + " dataset reading the wrong field");
        }
    }

    /**
     * Creates the directory a staged dataset resolves within, if it is not already present.
     *
     * @param target the dataset being written
     * @throws IOException if the directory cannot be created
     */
    private static void prepareContainingDirectory(final Path target) throws IOException {
        final Path container = target.getParent();
        if (container != null) {
            Files.createDirectories(container);
        }
    }

    /**
     * Opens a staged dataset for writing, replacing any content a previous run left behind, which is
     * what the legacy allocate-new disposition achieves.
     *
     * @param target the dataset to write
     * @return the writer
     * @throws IOException if the dataset cannot be opened
     */
    private static BufferedWriter openForWriting(final Path target) throws IOException {
        prepareContainingDirectory(target);
        return Files.newBufferedWriter(target, StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    /**
     * Releases a handle without observing the outcome, for the failure path only. A release is not a
     * second close: it reports at debug level and never raises, so it cannot displace the failure that
     * caused it.
     *
     * @param handle the handle to release, which may be {@code null} if it was never opened
     * @param resource the data definition the handle belongs to, for the diagnostic
     */
    private static void releaseQuietly(final AutoCloseable handle, final String resource) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (Exception release) {
            LOGGER.debug("RELEASING HANDLE OF {} REPORTED {}", resource,
                    release.getClass().getSimpleName());
        }
    }

    // -----------------------------------------------------------------------------------------------
    // The three program lifecycles. Each is constructed fresh for the step execution it serves, so its
    // handles and counters are per-execution instance state rather than shared singleton state. Each
    // inherits the open, read-loop, status-check, close and abend skeleton, its tri-state input/output
    // outcome, its batch timestamp form and its lifecycle timer, so none of that is re-implemented.
    // -----------------------------------------------------------------------------------------------

    /**
     * Clears the prior report output, standing in for the legacy {@code DELDEF} step. That step invokes
     * the system no-op allocation utility, so it reads no record and writes no record: its only effect
     * is its data definition's disposition, which allocates the report dataset when it is absent and
     * deletes it at normal end. The net effect is that the dataset is absent afterwards either way, so
     * this equivalent is idempotent and reports the success status whether or not it removed anything.
     * Reporting the optional-file status instead would abend, because only {@code "00"} is accepted as
     * success.
     */
    private static final class ClearPriorReportStep extends AbstractCobolStep<Void> {

        /** The report dataset to clear. */
        private final Path reportResource;

        /**
         * @param meterRegistry the metric registry; must not be {@code null}
         * @param clock the clock stamping the lifecycle boundaries; must not be {@code null}
         * @param reportResource the report dataset to clear; must not be {@code null}
         */
        private ClearPriorReportStep(final MeterRegistry meterRegistry, final Clock clock,
                final Path reportResource) {

            super(LEGACY_STEP_CLEAR, meterRegistry, clock);
            this.reportResource = Objects.requireNonNull(reportResource, "reportResource");
        }

        @Override
        protected void openResources() {
            openResource(DD_REPORT_ALLOCATION, () -> {
                prepareContainingDirectory(this.reportResource);
                final boolean removed = Files.deleteIfExists(this.reportResource);
                if (removed) {
                    LOGGER.info("CLEARED PRIOR OUTPUT OF {}", DD_REPORT_ALLOCATION);
                } else {
                    LOGGER.info("NO PRIOR OUTPUT OF {} TO CLEAR", DD_REPORT_ALLOCATION);
                }
                // Success either way: the legacy disposition allocates the dataset when it is absent,
                // so the step has no first-run failure to reproduce.
                return FileStatus.SUCCESS.getCode();
            });
        }

        @Override
        protected Optional<Void> readNextRecord() {
            // The legacy utility reads nothing, so the skeleton's read loop terminates at once.
            return Optional.empty();
        }

        @Override
        protected void processRecord(final Void record) {
            throw new IllegalStateException("the " + LEGACY_STEP_CLEAR + " equivalent reads no record,"
                    + " so no record can reach processing; reaching here means the read loop was"
                    + " changed to report a record that does not exist");
        }

        @Override
        protected void closeResources() {
            // Nothing is held open: the clearance completes within the open call, exactly as the legacy
            // step's whole effect completes within its allocation.
            LOGGER.debug("{} HOLDS NO HANDLE TO CLOSE", LEGACY_STEP_CLEAR);
        }
    }

    /**
     * Unloads the category-balance cluster to a new backup generation, standing in for the legacy
     * {@code STEP05R} step and its cataloged copy wrapper. The wrapper is a parameterised single-step
     * utility whose sole executable control statement copies input to output, so this is one ordinary
     * read-and-write step.
     *
     * <p>The input side runs through the shared sequential-read discipline first, which performs the
     * whole open, read-to-end and close pass and reports the terminal status and the record count. The
     * unload then reads the cluster in key sequence, which is the order a sequential unload of a
     * key-sequenced cluster emits, and the counts are reconciled before the step completes - the copy
     * utility's own record-count listing, not an invented check.
     */
    private static final class UnloadStep extends AbstractCobolStep<TransactionCategoryBalance> {

        /** Owner of the sequential-read and status discipline. */
        private final FileMaintenanceService fileMaintenanceService;

        /** Access to the cluster being unloaded. */
        private final TransactionCategoryBalanceRepository repository;

        /** The generation this job execution owns. */
        private final Path generation;

        /** Terminal status and record count the input-side pass reported. */
        private FileMaintenanceService.FileReadSummary inputSummary;

        /** Cursor over the cluster in key sequence. */
        private Iterator<TransactionCategoryBalance> unloadCursor;

        /** Handle on the generation being written. */
        private BufferedWriter writer;

        /** Records written, reconciled against the input-side count before the step completes. */
        private long recordsUnloaded;

        /**
         * @param meterRegistry the metric registry; must not be {@code null}
         * @param clock the clock stamping the lifecycle boundaries; must not be {@code null}
         * @param fileMaintenanceService owner of the read and status discipline; must not be
         *                               {@code null}
         * @param repository access to the cluster; must not be {@code null}
         * @param generation the generation to write; must not be {@code null}
         */
        private UnloadStep(final MeterRegistry meterRegistry, final Clock clock,
                final FileMaintenanceService fileMaintenanceService,
                final TransactionCategoryBalanceRepository repository, final Path generation) {

            super(LEGACY_STEP_UNLOAD, meterRegistry, clock);
            this.fileMaintenanceService =
                    Objects.requireNonNull(fileMaintenanceService, "fileMaintenanceService");
            this.repository = Objects.requireNonNull(repository, "repository");
            this.generation = Objects.requireNonNull(generation, "generation");
        }

        @Override
        protected void openResources() {
            // The shared discipline owns the input-side pass. Its own execution banner names the legacy
            // member it was built around, which is the mislabel this configuration's documentation
            // records; the banner is left as the service emits it rather than worked around here.
            this.inputSummary = this.fileMaintenanceService.readTransactionCategoryBalanceFile();

            if (!this.inputSummary.endedAtEndOfFile()) {
                // Status first, abend second. The display belongs to the service whose pass produced the
                // status - it emits and returns without raising - and the abend follows it, which is the
                // order the legacy diagnostic uses and the order verified against that display.
                this.fileMaintenanceService.displayIoStatus(this.inputSummary.terminalFileStatus(),
                        IoOperation.READ.legacyGerund(), DD_COPY_INPUT);
                abendOnIoFailure(IoOperation.READ, DD_COPY_INPUT,
                        this.inputSummary.terminalFileStatus());
            }

            openResource(DD_COPY_INPUT, () -> {
                this.unloadCursor = this.repository.findAll(UNLOAD_KEY_SEQUENCE).iterator();
                return FileStatus.SUCCESS.getCode();
            });

            openResource(DD_COPY_OUTPUT, () -> {
                this.writer = openForWriting(this.generation);
                return FileStatus.SUCCESS.getCode();
            });
        }

        @Override
        protected Optional<TransactionCategoryBalance> readNextRecord() {
            return this.<TransactionCategoryBalance>readRecord(DD_COPY_INPUT, () -> {
                if (this.unloadCursor.hasNext()) {
                    return IoResult.of(FileStatus.SUCCESS.getCode(), this.unloadCursor.next());
                }
                return IoResult.endOfFile();
            });
        }

        @Override
        protected void processRecord(final TransactionCategoryBalance record) {
            writeRecord(DD_COPY_OUTPUT, () -> {
                final String image = TranCatBalRecordMapper.toRecord(record);
                requireEncodedWidth(image, UNLOAD_RECORD_LENGTH, DD_COPY_OUTPUT);
                this.writer.write(image);
                // The record separator is written explicitly rather than through a platform newline, so
                // the emitted stream is the same on every host and matches the estate's own fixtures.
                this.writer.write(RECORD_SEPARATOR);
                this.recordsUnloaded++;
                return FileStatus.SUCCESS.getCode();
            });
        }

        @Override
        protected void closeResources() {
            closeResource(DD_COPY_OUTPUT, () -> {
                this.writer.flush();
                this.writer.close();
                return FileStatus.SUCCESS.getCode();
            });

            // The input side holds nothing open: the service's pass opened and closed the cluster within
            // itself, and the cursor this step iterated is an in-memory sequence over its result.
            this.unloadCursor = null;

            if (this.recordsUnloaded != this.inputSummary.recordsRead()) {
                abendOnIoFailure(IoOperation.CLOSE, DD_COPY_OUTPUT,
                        FileStatus.PERMANENT_ERROR.getCode());
            }

            LOGGER.info("{} UNLOADED {} RECORD(S) OF {} BYTE(S) TO {}", LEGACY_STEP_UNLOAD,
                    this.recordsUnloaded, UNLOAD_RECORD_LENGTH, DD_COPY_OUTPUT);
        }

        @Override
        protected void releaseResources() {
            releaseQuietly(this.writer, DD_COPY_OUTPUT);
        }
    }

    /**
     * Orders the unloaded generation and reprojects it to the report, standing in for the legacy
     * {@code STEP10R} step's external sort. The whole input is read before anything is written, because
     * that is what an external sort does and because the ordering is the output contract.
     *
     * <p>The generation this step reads is already in cluster key sequence, and the sort specification
     * is applied to it regardless - the legacy stream sorts an already key-ordered unload, and
     * reproducing that keeps the ordering a property of the specification rather than of the unload.
     */
    private static final class SortAndReprojectStep
            extends AbstractCobolStep<TransactionCategoryBalance> {

        /** Reader over the unloaded generation, at the category-balance record layout. */
        private final FlatFileItemReader<TransactionCategoryBalance> reader;

        /** The report dataset being written. */
        private final Path reportResource;

        /** The sort work area, standing in for the sort's own work file. */
        private final List<TransactionCategoryBalance> sortWorkArea = new ArrayList<>();

        /** Handle on the report being written. */
        private BufferedWriter writer;

        /**
         * @param meterRegistry the metric registry; must not be {@code null}
         * @param clock the clock stamping the lifecycle boundaries; must not be {@code null}
         * @param readerFactory source of the fixed-width reader; must not be {@code null}
         * @param generation the generation to read; must not be {@code null}
         * @param reportResource the report dataset to write; must not be {@code null}
         */
        private SortAndReprojectStep(final MeterRegistry meterRegistry, final Clock clock,
                final FixedWidthFlatFileReaderFactory readerFactory, final Path generation,
                final Path reportResource) {

            super(LEGACY_STEP_SORT, meterRegistry, clock);
            Objects.requireNonNull(readerFactory, "readerFactory");
            Objects.requireNonNull(generation, "generation");
            // The reader, and with it every offset of the fifty-byte layout, comes from the shared
            // factory; this step composes it rather than slicing the record itself.
            this.reader = readerFactory.transactionCategoryBalanceReader(new PathResource(generation));
            this.reportResource = Objects.requireNonNull(reportResource, "reportResource");
        }

        @Override
        protected void openResources() {
            openResource(DD_SORT_INPUT, () -> {
                this.reader.open(new ExecutionContext());
                return FileStatus.SUCCESS.getCode();
            });

            openResource(DD_SORT_OUTPUT, () -> {
                this.writer = openForWriting(this.reportResource);
                return FileStatus.SUCCESS.getCode();
            });
        }

        @Override
        protected Optional<TransactionCategoryBalance> readNextRecord() {
            return this.<TransactionCategoryBalance>readRecord(DD_SORT_INPUT, () -> {
                final TransactionCategoryBalance next = this.reader.read();
                if (next == null) {
                    return IoResult.endOfFile();
                }
                return IoResult.of(FileStatus.SUCCESS.getCode(), next);
            });
        }

        @Override
        protected void processRecord(final TransactionCategoryBalance record) {
            // Accumulate only: an external sort cannot emit its first record until it has read its last.
            this.sortWorkArea.add(record);
        }

        @Override
        protected void closeResources() {
            closeResource(DD_SORT_INPUT, () -> {
                this.reader.close();
                return FileStatus.SUCCESS.getCode();
            });

            this.sortWorkArea.sort(SORT_SPECIFICATION);

            for (final TransactionCategoryBalance ordered : this.sortWorkArea) {
                writeRecord(DD_SORT_OUTPUT, () -> {
                    final String line = reportLine(ordered);
                    requireEncodedWidth(line, REPORT_RECORD_LENGTH, DD_SORT_OUTPUT);
                    this.writer.write(line);
                    this.writer.write(RECORD_SEPARATOR);
                    return FileStatus.SUCCESS.getCode();
                });
            }

            closeResource(DD_SORT_OUTPUT, () -> {
                this.writer.flush();
                this.writer.close();
                return FileStatus.SUCCESS.getCode();
            });

            LOGGER.info("{} ORDERED AND REPROJECTED {} RECORD(S) OF {} BYTE(S) TO {}",
                    LEGACY_STEP_SORT, this.sortWorkArea.size(), REPORT_RECORD_LENGTH, DD_SORT_OUTPUT);
        }

        @Override
        protected void releaseResources() {
            releaseQuietly(this.writer, DD_SORT_OUTPUT);
            // The item stream contract declares its own close rather than the standard one, so the
            // release is handed the close itself.
            releaseQuietly(this.reader::close, DD_SORT_INPUT);
        }
    }
}
