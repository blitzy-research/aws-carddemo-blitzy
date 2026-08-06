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

import com.carddemo.batch.step.AbstractCobolStep;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.service.BatchJobCatalog;
import com.carddemo.service.FileMaintenanceService;
import com.carddemo.util.BatchCancellation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The one parameterised job that replaces the estate's four sequential-read verification job streams.
 *
 * <p>Four members of the legacy tree do the same thing to four different files: they run one program
 * that opens an indexed file, reads it from the first record to the last, emits every record to the
 * diagnostic channel and closes it, with nothing else in the stream. Each declares exactly one
 * application step, and each of those steps carries the same name, {@code STEP05}. The four collapse
 * into a single job here because nothing distinguishes them except which file they name, and that
 * distinction becomes a job parameter rather than four near-identical job definitions.
 *
 * <table border="1">
 *   <caption>The four collapsed members, their step, their program and the file each names</caption>
 *   <tr><th>Member</th><th>Lines</th><th>Step</th><th>Program</th><th>Data definition</th>
 *       <th>Mode</th></tr>
 *   <tr><td>{@code app/jcl/READACCT.jcl}</td><td>31</td><td>{@code STEP05} at line 22</td>
 *       <td>{@code CBACT01C}</td><td>{@code ACCTFILE}</td><td>{@link ProbeMode#ACCOUNT}</td></tr>
 *   <tr><td>{@code app/jcl/READCARD.jcl}</td><td>31</td><td>{@code STEP05} at line 22</td>
 *       <td>{@code CBACT02C}</td><td>{@code CARDFILE}</td><td>{@link ProbeMode#CARD}</td></tr>
 *   <tr><td>{@code app/jcl/READCUST.jcl}</td><td>15</td><td>{@code STEP05} at line 6</td>
 *       <td>{@code CBCUS01C}</td><td>{@code CUSTFILE}</td><td>{@link ProbeMode#CUSTOMER}</td></tr>
 *   <tr><td>{@code app/jcl/READXREF.jcl}</td><td>31</td><td>{@code STEP05} at line 22</td>
 *       <td>{@code CBACT03C}</td><td>{@code XREFFILE}</td>
 *       <td>{@link ProbeMode#CROSS_REFERENCE}</td></tr>
 * </table>
 *
 * <h2>The correction this file carries: the fourth program reads the card cross-reference</h2>
 *
 * <p>A published program-to-entity mapping table attributes {@code CBACT03C} to the
 * transaction-category-balance file. <strong>Direct measurement of the member disproves it.</strong>
 * Its own header states that its function is to read and print the account cross-reference data file;
 * its file selection at line 29 names the cross-reference data definition with indexed organisation,
 * sequential access and a record key that is the cross-reference card number; its record description
 * splits a 50-byte image into a 16-character key and a 34-byte remainder; it includes the card
 * cross-reference copybook; and its record emissions at lines 78 and 96 name the cross-reference
 * record. Independently, {@code CBACT03C} is named by exactly one member in the whole estate,
 * {@code app/jcl/READXREF.jcl} at line 22, whose input data definition is the card cross-reference
 * cluster. The misattribution is explained only by coincidence of width: the cross-reference record
 * and the transaction-category-balance record are <em>both</em> 50 bytes, and they are otherwise
 * unrelated files with different keys, different owners and different lifecycles.
 *
 * <p>{@link ProbeMode#CROSS_REFERENCE} therefore binds to the card cross-reference cluster, keyed on the
 * 16-character cross-reference card number, at 50 bytes. <strong>It must never bind to the transaction
 * category balance.</strong> The misattribution has since been corrected at its source:
 * {@link FileMaintenanceService} now translates {@code CBACT03C} as the cross-reference reader it is, so
 * all four modes of this job delegate to that service and this configuration carries no reader of its own.
 * An earlier revision did carry one - a second translation of the same five paragraphs, written on the
 * batch tier's step template purely to avoid the misattributed entry point - and it is gone, because a
 * paragraph of a member must have exactly one Java owner or the traceability matrix has two answers for
 * one row. The correction is recorded in {@code docs/decision-log.md}.
 *
 * <h2>Sequential access on an indexed file is ascending primary-key order</h2>
 *
 * <p>All four legacy file selections declare indexed organisation, sequential access mode and a record
 * key. Reading such a file from beginning to end therefore returns its records in ascending
 * primary-key order, and because every record reaches the diagnostic channel, <strong>that ordering is
 * observable output</strong> rather than an implementation detail. No repository in this module imposes
 * an order of its own, and an unordered query returns rows in whatever order the database finds
 * cheapest, so each mode carries an explicit ascending {@link Sort} on its business key and every scan
 * is issued with it. Omitting it would produce a diagnostic stream that differed between runs against
 * identical data. For the same reason the step is strictly sequential: no task executor, no
 * partitioning and no parallel flow, because any of them would interleave the stream.
 *
 * <h2>What this job does not have, because the four members do not have it</h2>
 *
 * <p>Each member carries exactly one application step, no condition-code gate, no program parameter and
 * no output data definition &mdash; only the shared load library and the two diagnostic output definitions.
 * Three consequences follow and none may be softened. <strong>This job writes no data file and has no
 * fixed output width</strong>: its entire product is the ordered diagnostic record stream plus the
 * terminating status, so no golden output fixture belongs to it. <strong>It contains no
 * failure-ending step transition</strong>, because zero condition-code gates were measured across the
 * four members; a gate copied here by analogy with the statement job would skip work the estate always
 * ran. And it composes <strong>no flat-file reader</strong>: every method of the fixed-width reader
 * factory opens a sequential dataset image, while these four members read indexed clusters whose
 * migrated home is the relational store, so introducing a sequential input none of the four declares
 * would be an added capability rather than a translated one. All offset knowledge stays in the utility
 * layer regardless &mdash; this configuration composes and wires, and never slices a byte.
 *
 * <h2>One job, one step, one stable name</h2>
 *
 * <p>The mode selects the file at execution time through late binding on the job parameter, so the job
 * name and the step name are single configuration-time constants. Neither is assembled from a
 * parameter value and there are not four near-duplicate jobs, because job and step identity is what the
 * framework resolves a launch against and what {@code api/BatchJobController} launches by. All four
 * legacy members declare the step name {@code STEP05}, recorded here as {@link #LEGACY_STEP_NAME}; the
 * collapse deliberately yields the one stable Java step name {@link #FILE_PROBE_STEP_NAME} rather than
 * four, and a unique name is what keeps a step execution attributable in a module of nine jobs whose
 * members reuse a handful of step names between them.
 *
 * <h2>The two-level file-status model, and where it lives</h2>
 *
 * <p>The legacy programs never branch on the raw two-byte file status. They normalise it into a coarser
 * result first &mdash; success to an all-clear value, the end-of-file code to a distinct end-of-file value
 * and everything else to a single error value &mdash; and branch on that, as {@code app/cbl/CBACT01C.cbl}
 * shows at lines 90 to 114 and as the other nine batch programs repeat. Neither level is reproduced
 * here: the coarse tri-state and the ordered failure path belong to {@link AbstractCobolStep}, which
 * logs the raw status and the operation first and only then raises the abend, and the raw two-byte
 * vocabulary belongs to {@link FileStatus}. End of file is never folded into error, and this
 * configuration adds no status handling of its own beyond naming the resource whose status is reported.
 *
 * <h2>Anomalies recorded and not propagated</h2>
 *
 * <ul>
 *   <li>{@code app/jcl/READCUST.jcl} line 2 misspells the system-user symbol on its notification
 *       keyword. There is no notification symbol in the target to misspell, so the defect has no
 *       equivalent; it is recorded and <strong>no configuration key is invented to carry it</strong>.
 *   <li>{@code app/jcl/READCUST.jcl} carries no licence block at all &mdash; 15 lines against its three
 *       siblings' 31, which is why its step sits at line 6 rather than line 22. It is the only one of
 *       the four missing it. The Java sources of this module carry the licence header unconditionally.
 *   <li>All four members declare the same step name, so the four steps were never distinguishable by
 *       name in the estate either. The collapse to one Java step name is therefore a faithful
 *       simplification rather than a loss of identity.
 *   <li>{@code CBACT03C} emits the cross-reference record <em>twice</em> per successful read, once in
 *       its read paragraph at line 96 and once in its mainline at line 78. Both emissions are
 *       reproduced, matching how the service reproduces the same duplication for the account reader.
 * </ul>
 *
 * <p>All four findings are raised for {@code docs/decision-log.md}, which this file neither creates nor
 * edits.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the CardDemo mainframe estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Every figure above was measured by direct read
 * of that checkout. The legacy tree is cited and never transcribed: no job-control, program or copybook
 * statement text appears here, and nothing under {@code app/} is read at run time.
 *
 * <p>Nothing in this class fires when the context starts. It contributes no start-up runner, lifecycle
 * participant, initialising callback, event listener or scheduled trigger, it declares no job name for
 * the framework to resolve on start-up, and launch-on-start is disabled by the shipped configuration.
 * A job runs only when something launches it.
 *
 * <p>Immutable and stateless: the class is final, every field is final, collaborators arrive through the
 * constructor and no mutable static or singleton state exists. Per-execution state lives only inside
 * the step-scoped tasklet the framework creates for one step execution.
 */
@Configuration(proxyBeanMethods = false)
public final class FileProbeJobConfig {

    /**
     * The job name, and the bean name it is published under, so a launch resolves one identity.
     *
     * <p>Stable and configuration-time. It is never assembled from a job parameter, because the
     * framework resolves a job instance by name and a name that varied with a parameter would make
     * every launch a different job.
     *
     * <p>The value is read from {@code service/BatchJobCatalog}, which is the module's single
     * declaration of the nine stable job names. Both tiers that need a name - this configuration
     * and the operational control surface above it - resolve it from there, so the name exists as
     * one literal and the two cannot drift apart across a boundary the layering keeps closed.
     */
    public static final String FILE_PROBE_JOB_NAME = BatchJobCatalog.FILE_PROBE_JOB_NAME;

    /** The one step name, and the bean name it is published under. */
    public static final String FILE_PROBE_STEP_NAME = "fileProbeStep";

    /** The bean name of the step-scoped tasklet that resolves the mode at execution time. */
    public static final String FILE_PROBE_TASKLET_BEAN_NAME = "fileProbeTasklet";

    /**
     * The step name all four collapsed members declare, recorded so the citation survives the collapse.
     *
     * <p>It is deliberately not used as the framework step name: several members of the estate reuse a
     * handful of step names between them, so a unique Java name is what keeps a step execution
     * attributable to one job.
     */
    public static final String LEGACY_STEP_NAME = "STEP05";

    /** Diagnostic channel of this configuration, replacing the legacy console display verb. */
    private static final Logger LOG = LoggerFactory.getLogger(FileProbeJobConfig.class);

    /** Timer of one probe lifecycle, tagged by mode, program, resource and outcome. */
    private static final String METRIC_PROBE_STEP = "carddemo.batch.fileprobe.step";

    /** Counter of the records one probe read, tagged the same way minus the outcome. */
    private static final String METRIC_PROBE_RECORDS = "carddemo.batch.fileprobe.records";

    /** Description of the probe timer, as the scrape endpoint publishes it. */
    private static final String METRIC_PROBE_STEP_DESCRIPTION =
            "Elapsed time of one CardDemo sequential-read file probe";

    /** Description of the probe record counter, as the scrape endpoint publishes it. */
    private static final String METRIC_PROBE_RECORDS_DESCRIPTION =
            "Records delivered by the CardDemo sequential-read file probe";

    /** Tag naming the probe mode a measurement belongs to. */
    private static final String TAG_MODE = "mode";

    /** Tag naming the legacy program a measurement belongs to. */
    private static final String TAG_PROGRAM = "program";

    /** Tag naming the logical resource a measurement belongs to. */
    private static final String TAG_RESOURCE = "resource";

    /** Tag naming how a probe ended. */
    private static final String TAG_OUTCOME = "outcome";

    /** Outcome tag value of a probe that reached end of file and closed. */
    private static final String OUTCOME_COMPLETED = "COMPLETED";

    /** Outcome tag value of a probe that ended on the abend path. */
    private static final String OUTCOME_ABENDED = "ABENDED";

    /** Outcome tag value of a probe that observed a cooperative stop. */
    private static final String OUTCOME_STOPPED = "STOPPED";

    /** Message emitted as a probe begins, naming everything the mode binds. */
    private static final String PROBE_STARTING =
            "FILE PROBE STARTING - mode={} program={} resource={} recordLength={}";

    /** Message emitted as a probe completes normally. */
    private static final String PROBE_COMPLETED =
            "FILE PROBE COMPLETED - mode={} program={} resource={} recordsRead={}";

    /** Message emitted when a probe ends on the abend path, before the failure is rethrown. */
    private static final String PROBE_ABENDED =
            "FILE PROBE OF {} TERMINATED ABNORMALLY - mode={} program={}";

    /** Message reporting the raw status that ended a delegated reader's loop. */
    private static final String PROBE_TERMINAL_STATUS =
            "FILE PROBE TERMINAL STATUS - mode={} resource={} fileStatus={} atEndOfFile={}";

    /**
     * The four files the collapsed job can probe, each carrying everything the collapse needs to know
     * about one of the four legacy members.
     *
     * <p>Nested here on purpose. The concept of a probe mode has no legacy antecedent &mdash; the legacy
     * distinction was the identity of a job stream, not the content of a parameter &mdash; so it belongs to
     * the job that owns the collapse and to nothing else. Declaring it as a domain enumeration would
     * place a translation artefact in the layer that holds record vocabulary, and it would invite a
     * second consumer to bind a fifth file this job never reads.
     *
     * <p>Each constant carries five measured facts: the exact value a launch supplies, the legacy
     * program the mode translates, the logical resource name the legacy data definition used and every
     * diagnostic reports, the canonical record length in encoded bytes, and the ascending order on the
     * business key that reproduces sequential access over an indexed file. The record length is stated
     * because it identifies the layout the mode reads; this enumeration does not slice a record and
     * holds no offset.
     *
     * <table border="1">
     *   <caption>Mode to program, entity, repository, record length and key</caption>
     *   <tr><th>Mode</th><th>Program</th><th>Entity</th><th>Bytes</th><th>Business key</th></tr>
     *   <tr><td>{@link #ACCOUNT}</td><td>{@code CBACT01C}</td><td>{@code domain/Account}</td>
     *       <td>300</td><td>11-digit account identifier</td></tr>
     *   <tr><td>{@link #CARD}</td><td>{@code CBACT02C}</td><td>{@code domain/Card}</td>
     *       <td>150</td><td>16-character card number</td></tr>
     *   <tr><td>{@link #CUSTOMER}</td><td>{@code CBCUS01C}</td><td>{@code domain/Customer}</td>
     *       <td>500</td><td>9-digit customer identifier</td></tr>
     *   <tr><td>{@link #CROSS_REFERENCE}</td><td>{@code CBACT03C}</td>
     *       <td>{@code domain/CardCrossReference}</td><td>50</td>
     *       <td>16-character cross-reference card number</td></tr>
     * </table>
     */
    public enum ProbeMode {

        /**
         * The account master, read by {@code CBACT01C} for {@code app/jcl/READACCT.jcl} at line 22.
         *
         * <p>Its record description splits the 300-byte image into an 11-digit key and a 289-byte
         * remainder, which is what corroborates the width against the account copybook.
         */
        ACCOUNT("account", "CBACT01C", "ACCTFILE", 300, "acctId"),

        /**
         * The card master, read by {@code CBACT02C} for {@code app/jcl/READCARD.jcl} at line 22.
         *
         * <p>Its record description splits the 150-byte image into a 16-character key and a 134-byte
         * remainder.
         */
        CARD("card", "CBACT02C", "CARDFILE", 150, "cardNum"),

        /**
         * The customer master, read by {@code CBCUS01C} for {@code app/jcl/READCUST.jcl} at line 6.
         *
         * <p>Its record description splits the 500-byte image into a 9-digit key and a 491-byte
         * remainder. This is the member carrying both recorded anomalies: the misspelled notification
         * symbol and the missing licence block.
         */
        CUSTOMER("customer", "CBCUS01C", "CUSTFILE", 500, "custId"),

        /**
         * The card cross-reference, read by {@code CBACT03C} for {@code app/jcl/READXREF.jcl} at
         * line 22.
         *
         * <p>Its record description splits the 50-byte image into a 16-character card-number key and a
         * 34-byte remainder, and the program includes the card cross-reference copybook.
         * <strong>Not the transaction category balance</strong>, which shares the 50-byte width and
         * nothing else; see this class's own documentation for the measurement that settles it.
         */
        CROSS_REFERENCE("crossReference", "CBACT03C", "XREFFILE", 50, "xrefCardNum");

        /**
         * The legal launch values, in declaration order, as the parameter validator reports them.
         *
         * <p>Held as an immutable list so the order a diagnostic lists them in is stable and so no
         * caller can extend the set of files this job will open.
         */
        private static final List<String> PARAMETER_VALUES = buildParameterValues();

        /** The exact value a launch supplies to select this mode. */
        private final String parameterValue;

        /** The legacy program this mode translates, as every diagnostic and metric names it. */
        private final String legacyProgramName;

        /** The logical resource name the legacy data definition used. */
        private final String logicalResourceName;

        /** The canonical record length of the layout this mode reads, in encoded bytes. */
        private final int recordLength;

        /** The business key the ascending order is taken on. */
        private final String businessKeyProperty;

        /** The ascending order that reproduces sequential access over the indexed file. */
        private final Sort keyOrder;

        /**
         * @param parameterValue      the exact, case-sensitive launch value
         * @param legacyProgramName   the legacy program this mode translates
         * @param logicalResourceName the logical resource name every diagnostic reports
         * @param recordLength         the canonical record length in encoded bytes
         * @param businessKeyProperty the entity attribute carrying the business key
         */
        ProbeMode(final String parameterValue, final String legacyProgramName,
                final String logicalResourceName, final int recordLength,
                final String businessKeyProperty) {
            this.parameterValue = parameterValue;
            this.legacyProgramName = legacyProgramName;
            this.logicalResourceName = logicalResourceName;
            this.recordLength = recordLength;
            this.businessKeyProperty = businessKeyProperty;
            this.keyOrder = Sort.by(Sort.Direction.ASC, businessKeyProperty);
        }

        /**
         * @return the exact value a launch supplies to select this mode, never {@code null}
         */
        public String parameterValue() {
            return this.parameterValue;
        }

        /**
         * @return the legacy program this mode translates, never {@code null}
         */
        public String legacyProgramName() {
            return this.legacyProgramName;
        }

        /**
         * @return the logical resource name the legacy data definition used, never {@code null}
         */
        public String logicalResourceName() {
            return this.logicalResourceName;
        }

        /**
         * @return the canonical record length of the layout this mode reads, in encoded bytes
         */
        public int recordLength() {
            return this.recordLength;
        }

        /**
         * @return the entity attribute carrying the business key, never {@code null}
         */
        public String businessKeyProperty() {
            return this.businessKeyProperty;
        }

        /**
         * The ascending order every scan for this mode is issued with.
         *
         * <p>It is not optional. Sequential access over an indexed file returns records in ascending
         * primary-key order and every record reaches the diagnostic channel, so the order is observable
         * output; an unordered query would make that output depend on the database's own plan.
         *
         * @return the ascending order on this mode's business key, never {@code null}
         */
        public Sort keyOrder() {
            return this.keyOrder;
        }

        /**
         * The legal launch values, which the job hands to the parameter validator.
         *
         * <p>The enumeration of legal modes belongs here, with the job that knows how to probe each of
         * them, rather than in the validator: that is what keeps one definition of which files may be
         * probed.
         *
         * @return the legal values in declaration order, immutable and never {@code null}
         */
        public static List<String> parameterValues() {
            return PARAMETER_VALUES;
        }

        /**
         * Resolves a supplied launch value to a mode, exactly and case sensitively.
         *
         * <p>An unrecognised value <strong>fails</strong>. It never falls back to a mode, because
         * silently probing the account file when a launch asked for something else would report a
         * healthy file the operator never named. The job's parameter validator refuses the same value
         * earlier, at the launch boundary; this check is what makes the fallback impossible rather than
         * merely unlikely.
         *
         * @param  supplied the value the launch carried, which may be {@code null} or blank
         * @return the mode it names, never {@code null}
         * @throws IllegalArgumentException if {@code supplied} is {@code null}, blank, or not one of
         *                                  {@link #parameterValues()}
         */
        public static ProbeMode ofParameterValue(final String supplied) {
            if (supplied == null || supplied.isBlank()) {
                throw new IllegalArgumentException("A file-probe mode is required but none was "
                        + "supplied; the legal values are " + PARAMETER_VALUES);
            }
            for (final ProbeMode candidate : values()) {
                if (candidate.parameterValue.equals(supplied)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("File-probe mode [" + supplied + "] is not one of the "
                    + "legal probe modes " + PARAMETER_VALUES + "; matching is exact and case "
                    + "sensitive");
        }

        /**
         * Collects the launch values once, in declaration order.
         *
         * @return an immutable list of the four legal values
         */
        private static List<String> buildParameterValues() {
            final ProbeMode[] modes = values();
            final String[] collected = new String[modes.length];
            for (int index = 0; index < modes.length; index++) {
                collected[index] = modes[index].parameterValue;
            }
            return List.of(collected);
        }
    }

    /** The framework's metadata store, handed to both builders rather than to a deprecated setter. */
    private final JobRepository jobRepository;

    /** The transaction manager the tasklet step runs its single pass in. */
    private final PlatformTransactionManager transactionManager;

    /** Owner of the launch-boundary parameter contract, including the probe-mode check. */
    private final JobParameterValidators jobParameterValidators;

    /** The shared job-boundary diagnostic every job configuration of this module attaches. */
    private final JobExecutionListener jobBoundaryListener;

    /** The shared parameter incrementer that lets an identical launch be resubmitted. */
    private final JobParametersIncrementer jobRunIncrementer;

    /** The translation of all four reader programs, driving every one of the four modes. */
    private final FileMaintenanceService fileMaintenanceService;

    /** The registry the probe timer and record counter are recorded on; never a new registry. */
    private final MeterRegistry meterRegistry;

    /** The module's single time source, from which the batch timestamps are read. */
    private final Clock clock;

    /**
     * Constructor injection only, so a missing collaborator is a start-up failure rather than a
     * null-valued field discovered during a run.
     *
     * <p>The two shared collaborators are injected by the bean names the batch infrastructure publishes
     * them under rather than by importing the class that declares them: a job configuration attaches
     * them, and the batch tier depends on no configuration class.
     *
     * @param jobRepository                the framework's metadata store
     * @param transactionManager           the transaction manager the single tasklet step runs in
     * @param jobParameterValidators       owner of the launch-boundary parameter contract
     * @param jobBoundaryListener          the shared job-boundary diagnostic
     * @param jobRunIncrementer            the shared parameter incrementer
     * @param fileMaintenanceService       the translation of the four reader programs
     * @param meterRegistry                the registry the probe meters are recorded on
     * @param clock                        the module's single time source
     * @throws NullPointerException if any collaborator is {@code null}, which is a wiring error
     */
    public FileProbeJobConfig(final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            final JobParameterValidators jobParameterValidators,
            @Qualifier("batchJobBoundaryListener") final JobExecutionListener jobBoundaryListener,
            @Qualifier("batchJobRunIncrementer") final JobParametersIncrementer jobRunIncrementer,
            final FileMaintenanceService fileMaintenanceService,
            final MeterRegistry meterRegistry,
            final Clock clock) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.jobParameterValidators =
                Objects.requireNonNull(jobParameterValidators, "jobParameterValidators");
        this.jobBoundaryListener = Objects.requireNonNull(jobBoundaryListener, "jobBoundaryListener");
        this.jobRunIncrementer = Objects.requireNonNull(jobRunIncrementer, "jobRunIncrementer");
        this.fileMaintenanceService =
                Objects.requireNonNull(fileMaintenanceService, "fileMaintenanceService");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The one job the four legacy members collapse into.
     *
     * <p>It carries a parameter validator built from {@link ProbeMode#parameterValues()}, so a launch
     * naming a file this job cannot probe is refused at the boundary rather than defaulted to a file it
     * can; the shared incrementer, so an identical resubmission runs again as a resubmitted member did;
     * the shared boundary diagnostic, attached here rather than globally so a job without one is visible
     * in its own source; and <strong>one step reached by a plain start, with no failure-ending
     * transition</strong>, because zero condition-code gates were measured across the four members.
     *
     * <p>It chains to nothing. The estate holds no master orchestrator, so no job of this module runs
     * another and no job that runs everything exists.
     *
     * @param  fileProbeStep the single step, injected by name so a module of nine jobs cannot resolve
     *                       the wrong one
     * @return the job, never {@code null}
     */
    @Bean(name = FILE_PROBE_JOB_NAME)
    public Job fileProbeJob(@Qualifier(FILE_PROBE_STEP_NAME) final Step fileProbeStep) {
        Objects.requireNonNull(fileProbeStep, "fileProbeStep");
        LOG.debug("Defining job {} over step {} for probe modes {} (legacy step name {})",
                FILE_PROBE_JOB_NAME, FILE_PROBE_STEP_NAME, ProbeMode.parameterValues(),
                LEGACY_STEP_NAME);
        return new JobBuilder(FILE_PROBE_JOB_NAME, this.jobRepository)
                .validator(this.jobParameterValidators
                        .fileProbeModeValidator(ProbeMode.parameterValues()))
                .incrementer(this.jobRunIncrementer)
                .listener(this.jobBoundaryListener)
                .start(fileProbeStep)
                .build();
    }

    /**
     * The one step, whose name is a configuration-time constant rather than a value derived from the
     * launch.
     *
     * <p>A tasklet step and not a chunk-oriented one, because each of the four programs is a single
     * indivisible pass over one file that is not restartable part-way through: its read loop runs from
     * the first record to end of file inside one program invocation. Strictly sequential, with no task
     * executor, no partitioning and no parallel flow, because the record stream this job produces is
     * ordered output and any of the three would interleave it.
     *
     * @param  fileProbeTasklet the step-scoped tasklet, injected by name; the framework resolves the
     *                          mode behind it once a step execution exists
     * @return the step, never {@code null}
     */
    @Bean(name = FILE_PROBE_STEP_NAME)
    public Step fileProbeStep(
            @Qualifier(FILE_PROBE_TASKLET_BEAN_NAME) final Tasklet fileProbeTasklet) {
        Objects.requireNonNull(fileProbeTasklet, "fileProbeTasklet");
        return new StepBuilder(FILE_PROBE_STEP_NAME, this.jobRepository)
                .tasklet(fileProbeTasklet, this.transactionManager)
                .build();
    }

    /**
     * The tasklet, bound to its mode at execution time.
     *
     * <p>This is where the collapse actually happens. The mode arrives as a job parameter and is read
     * through step-scoped late binding, so one job definition and one step definition serve all four
     * files without a name being assembled from a parameter and without four near-duplicate jobs
     * competing for a launch. The mode is resolved once, when the framework creates this bean for a step
     * execution, and an unrecognised value fails here as well as at the launch boundary &mdash; it is never
     * defaulted.
     *
     * <p>Per-execution state lives only in the object returned here, which the framework builds afresh
     * for each step execution and discards afterwards. Nothing mutable is held by this configuration.
     *
     * @param  suppliedMode the value the launch carried under the probe-mode parameter key
     * @return a tasklet bound to exactly one mode, never {@code null}
     * @throws IllegalArgumentException if {@code suppliedMode} is absent, blank or not a legal mode
     */
    @Bean(name = FILE_PROBE_TASKLET_BEAN_NAME)
    @StepScope
    public Tasklet fileProbeTasklet(
            @Value("#{jobParameters['" + JobParameterValidators.FILE_PROBE_MODE_KEY + "']}")
            final String suppliedMode) {
        final ProbeMode mode = ProbeMode.ofParameterValue(suppliedMode);
        return new FileProbeTasklet(mode, probeFor(mode), this.meterRegistry);
    }

    /**
     * Binds one mode to the reader that performs its pass.
     *
     * <p>The switch is exhaustive over the enumeration and carries no default, so adding a fifth mode
     * without binding it is a compilation failure rather than a run-time surprise, and no arm falls
     * through into another.
     *
     * <p>All four arms delegate to {@link FileMaintenanceService}, which is the translation of all four
     * reader programs and owns the read loop, the record emissions and the status normalisation for every
     * one of them. The symmetry is the point: nothing about a read loop, a record emission or a status
     * normalisation is written in this file, so this configuration cannot drift from the members while the
     * service still matches them.
     *
     * @param  mode the mode a launch selected
     * @return a supplier that performs the pass and reports how many records it read
     */
    private ToLongFunction<BooleanSupplier> probeFor(final ProbeMode mode) {
        return switch (mode) {
            case ACCOUNT -> stop -> recordsRead(
                    mode, this.fileMaintenanceService.readAccountFile(stop));
            case CARD -> stop -> recordsRead(
                    mode, this.fileMaintenanceService.readCardFile(stop));
            case CUSTOMER -> stop -> recordsRead(
                    mode, this.fileMaintenanceService.readCustomerFile(stop));
            case CROSS_REFERENCE -> stop -> recordsRead(
                    mode, this.fileMaintenanceService.readCardCrossReferenceFile(stop));
        };
    }

    /**
     * Reports what ended a delegated reader's loop and returns its record count.
     *
     * <p>The terminal status is emitted because it is the observable proof that end of file is not an
     * error: a reader returns at all only by reaching end of file, since any other rejected status
     * abends instead, so a completed probe always reports the end-of-file code.
     *
     * @param  mode    the mode that ran
     * @param  summary what the reader reported
     * @return the number of records the reader delivered
     */
    private static long recordsRead(final ProbeMode mode,
            final FileMaintenanceService.FileReadSummary summary) {
        LOG.debug(PROBE_TERMINAL_STATUS, mode.parameterValue(), summary.resourceName(),
                summary.terminalFileStatus(), summary.endedAtEndOfFile());
        return summary.recordsRead();
    }

    /**
     * The tasklet the step runs: one bound mode, one timed pass, one record count.
     *
     * <p>It owns the probe's own timing and record count and nothing else about the pass. The reader it
     * was given performs the legacy program's work, and a failure is timed as an abend and rethrown
     * unchanged so the framework marks the step and the job failed. The failure is never swallowed and
     * never re-diagnosed: the raw file status and the abend announcement have already been emitted, in
     * that order, by whichever reader raised.
     *
     * <p>It deliberately leaves the step contribution untouched. It sets no exit status, because a
     * custom exit code is how a condition-code gate elsewhere in this module recognises a warning, and
     * a healthy probe must read as an ordinary success; and it moves no read count, because a tasklet
     * step performs one indivisible pass rather than counted chunks, so the record count is published on
     * the counter and in the completion diagnostic instead.
     *
     * <p>Instances are created per step execution by the step-scoped bean method, so the three fields
     * are per-execution and immutable and no mutable singleton state exists.
     */
    private static final class FileProbeTasklet implements Tasklet {

        /** The single mode this instance is bound to for the life of one step execution. */
        private final ProbeMode mode;

        /** The bound reader: performs the pass and reports how many records it delivered. */
        private final ToLongFunction<BooleanSupplier> probe;

        /** The registry the two probe meters are recorded on. */
        private final MeterRegistry meterRegistry;

        /**
         * @param mode          the mode this tasklet is bound to
         * @param probe         the reader that performs the pass
         * @param meterRegistry the registry the probe meters are recorded on
         * @throws NullPointerException if any argument is {@code null}
         */
        FileProbeTasklet(final ProbeMode mode, final ToLongFunction<BooleanSupplier> probe,
                final MeterRegistry meterRegistry) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.probe = Objects.requireNonNull(probe, "probe");
            this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        }

        /**
         * Runs the whole pass in one invocation, because a legacy batch program is not restartable
         * part-way through its file.
         *
         * @param  contribution the framework's per-step contribution, deliberately left untouched
         * @param  chunkContext the framework's chunk context, unused because the pass is indivisible
         * @return {@link RepeatStatus#FINISHED} always
         */
        @Override
        public RepeatStatus execute(final StepContribution contribution,
                final ChunkContext chunkContext) throws Exception {
            final Timer.Sample sample = Timer.start(this.meterRegistry);
            LOG.info(PROBE_STARTING, this.mode.parameterValue(), this.mode.legacyProgramName(),
                    this.mode.logicalResourceName(), this.mode.recordLength());

            final long recordsRead;
            try {
                final BooleanSupplier stopRequested = chunkContext == null
                        ? Thread.currentThread()::isInterrupted
                        : BatchCancellation.requestedBy(chunkContext);
                recordsRead = this.probe.applyAsLong(stopRequested);
            } catch (final CancellationException stopped) {
                recordProbeDuration(sample, OUTCOME_STOPPED);
                throw BatchCancellation.interrupted(stopped);
            } catch (RuntimeException abended) {
                recordProbeDuration(sample, OUTCOME_ABENDED);
                LOG.error(PROBE_ABENDED, this.mode.logicalResourceName(),
                        this.mode.parameterValue(), this.mode.legacyProgramName());
                throw abended;
            }

            recordProbeDuration(sample, OUTCOME_COMPLETED);
            recordCounter().increment(recordsRead);
            LOG.info(PROBE_COMPLETED, this.mode.parameterValue(), this.mode.legacyProgramName(),
                    this.mode.logicalResourceName(), recordsRead);
            return RepeatStatus.FINISHED;
        }

        /**
         * Stops the sample against the timer for this mode and outcome.
         *
         * @param sample  the sample started as the pass began
         * @param outcome how the pass ended
         */
        private void recordProbeDuration(final Timer.Sample sample, final String outcome) {
            sample.stop(Timer.builder(METRIC_PROBE_STEP)
                    .description(METRIC_PROBE_STEP_DESCRIPTION)
                    .tag(TAG_MODE, this.mode.parameterValue())
                    .tag(TAG_PROGRAM, this.mode.legacyProgramName())
                    .tag(TAG_RESOURCE, this.mode.logicalResourceName())
                    .tag(TAG_OUTCOME, outcome)
                    .register(this.meterRegistry));
        }

        /**
         * @return the record counter for this mode, registered on first use
         */
        private Counter recordCounter() {
            return Counter.builder(METRIC_PROBE_RECORDS)
                    .description(METRIC_PROBE_RECORDS_DESCRIPTION)
                    .tag(TAG_MODE, this.mode.parameterValue())
                    .tag(TAG_PROGRAM, this.mode.legacyProgramName())
                    .tag(TAG_RESOURCE, this.mode.logicalResourceName())
                    .register(this.meterRegistry);
        }
    }
}
