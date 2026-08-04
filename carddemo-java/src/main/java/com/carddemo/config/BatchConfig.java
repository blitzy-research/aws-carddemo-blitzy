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
package com.carddemo.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Batch <em>infrastructure</em> for the migrated job tier: the small set of collaborators every job
 * configuration in {@code com.carddemo.batch} shares, and nothing else. It defines no job, no step, no
 * reader, no writer and no processor &mdash; each of the nine job configurations owns those, and
 * {@code com.carddemo.batch.step.AbstractCobolStep} owns the open / read-loop / status-check / close /
 * abend skeleton the batch programs share.
 *
 * <h2>Why there are nine job configurations and not seventy-nine</h2>
 *
 * <p>An executed census over all twenty-nine job members in {@code app/jcl} (twenty-eight lower-case and
 * one upper-case extension) and both cataloged procedures in {@code app/proc} finds <strong>seventy-nine
 * job steps that name a program to run</strong>. Only <strong>nine</strong> of them name an application
 * program. The other seventy are utility work: fifty-two name {@code IDCAMS} (dataset define, delete and
 * unload), eight name {@code SDSF} (spool inspection), five name {@code SORT} (external ordering), three
 * name {@code IEFBR14} (the null program, used for allocation and deletion), one names {@code IEBGENER}
 * (in-stream copy) and one names {@code DFHCSDUP} (the online resource-definition utility).
 *
 * <p>Those seventy do not become steps in any form. Dataset definition and unload are absorbed by the
 * four Flyway migrations and by the Compose stack; external ordering becomes a comparator or a query
 * predicate inside the one job that needs it; spool inspection, the null program and the resource
 * utility have no runtime equivalent at all once the indexed files are replaced. <strong>No step in this
 * module runs a utility</strong>, and none may be added that does.
 *
 * <p>The nine application steps, each cited by member and line, together with the job configuration each
 * becomes:</p>
 *
 * <table border="1">
 *   <caption>The nine application job steps and their target job configurations</caption>
 *   <tr><th>Member and line</th><th>Step</th><th>Program</th><th>Target configuration</th></tr>
 *   <tr><td>{@code app/jcl/INTCALC.jcl:22}</td><td>STEP15</td><td>CBACT04C</td>
 *       <td>{@code batch/InterestCalculationJobConfig}</td></tr>
 *   <tr><td>{@code app/jcl/POSTTRAN.jcl:23}</td><td>STEP15</td><td>CBTRN02C</td>
 *       <td>{@code batch/PostTransactionJobConfig}</td></tr>
 *   <tr><td>{@code app/jcl/READACCT.jcl:22}</td><td>STEP05</td><td>CBACT01C</td>
 *       <td>{@code batch/FileProbeJobConfig}</td></tr>
 *   <tr><td>{@code app/jcl/READCARD.jcl:22}</td><td>STEP05</td><td>CBACT02C</td>
 *       <td>{@code batch/FileProbeJobConfig}</td></tr>
 *   <tr><td>{@code app/jcl/READCUST.jcl:6}</td><td>STEP05</td><td>CBCUS01C</td>
 *       <td>{@code batch/FileProbeJobConfig}</td></tr>
 *   <tr><td>{@code app/jcl/READXREF.jcl:22}</td><td>STEP05</td><td>CBACT03C</td>
 *       <td>{@code batch/FileProbeJobConfig}</td></tr>
 *   <tr><td>{@code app/jcl/TRANREPT.jcl:59}</td><td>STEP10R</td><td>CBTRN03C</td>
 *       <td>{@code batch/TransactionReportJobConfig}</td></tr>
 *   <tr><td>{@code app/proc/TRANREPT.prc:57}</td><td>STEP10R</td><td>CBTRN03C</td>
 *       <td>{@code batch/TransactionReportJobConfig}, the same program through the procedure</td></tr>
 *   <tr><td>{@code app/jcl/CREASTMT.JCL:79}</td><td>STEP040</td><td>CBSTM03A</td>
 *       <td>{@code batch/CreateStatementJobConfig}</td></tr>
 * </table>
 *
 * <p>Three further steps reach the cataloged unload procedure rather than a program directly, at
 * {@code app/jcl/PRTCATBL.jcl:29}, {@code app/jcl/TRANBKP.jcl:23} and {@code app/jcl/TRANREPT.jcl:23};
 * the procedure itself, at {@code app/proc/REPROC.prc:21}, is one of the fifty-two utility steps counted
 * above. Three members &mdash; {@code app/jcl/CLOSEFIL.jcl}, {@code app/jcl/OPENFIL.jcl} and
 * {@code app/jcl/CBADMCDJ.jcl} &mdash; only toggle online file availability and drive the
 * resource-definition utility; they are <strong>intentionally not migrated</strong>, which is a recorded
 * decision rather than an omission.
 *
 * <h2>Correction carried forward: the fourth step gate tolerates a warning</h2>
 *
 * <p>The migration plan states that all four condition-code step gates in the estate demand a prior
 * return code of exactly zero. Executed inspection finds six condition-code occurrences, of which only
 * four are step gates. <strong>Three</strong> of them, on STEP020, STEP030 and STEP040 of
 * {@code app/jcl/CREASTMT.JCL} at lines 56, 66 and 79, do demand exactly zero. The
 * <strong>fourth</strong>, on STEP10 of {@code app/jcl/TRANBKP.jcl:51}, admits every prior return code up
 * to and including four &mdash; that is, <strong>a warning is tolerated</strong>. The remaining two
 * occurrences, at {@code app/jcl/TRANREPT.jcl:47} and {@code app/proc/TRANREPT.prc:45}, select records
 * inside an ordering step and are not step gates at all.
 *
 * <p>{@link ConditionCodeGate} therefore carries <strong>both</strong> forms as distinct constants and
 * they must never be collapsed into one. Holding the backup job to zero would abort it on a warning the
 * estate tolerates, which is a behavioural regression, and holding the statement job to four would run a
 * statement step the estate would have skipped. See {@code docs/decision-log.md}.
 *
 * <h2>Correction carried forward: the category-balance report has no application step</h2>
 *
 * <p>The plan describes {@code app/jcl/PRTCATBL.jcl} as driven by program CBACT03C. Executed inspection
 * of the whole member shows it names <strong>no application program at all</strong>: its steps are a
 * delete-and-define step naming the null program at line 21, an unload step reaching the cataloged
 * procedure at line 29, and an ordering step at line 43 that carries <strong>its own</strong> symbol
 * declarations at lines 47 to 50 over the transaction-category-balance layout &mdash; account identifier
 * at offset 1 for eleven zoned-decimal bytes, type code at offset 12 for two character bytes, category
 * code at offset 14 for four zoned-decimal bytes and the balance at offset 18 for eleven zoned-decimal
 * bytes &mdash; a three-key ascending order, and a reprojection at line 56 whose balance field carries an
 * edit mask rendering nine integer digits, a decimal point, two fractional digits and trailing filler.
 *
 * <p>Two consequences follow. {@code batch/CategoryBalanceReportJobConfig} derives its ordering and its
 * formatted output from that member's own ordering specification rather than from an application program,
 * and the estate holds <strong>four</strong> distinct external ordering specifications rather than three,
 * the fourth being this one. Separately verified: program CBACT03C is named by
 * {@code app/jcl/READXREF.jcl} and by nothing else.
 *
 * <h2>What this class deliberately does not declare, and why &mdash; each an executed finding</h2>
 *
 * <p><strong>No enabling annotation.</strong> Under this framework generation the batch
 * auto-configuration is guarded by a condition that backs off when the enabling annotation is present, so
 * adding it <em>switches off</em> the very infrastructure it appears to switch on and leaves the job
 * repository and its data source unwired. The application entry point carries only its own
 * application-level annotation for the same reason, and neither file may acquire an enabling annotation.
 *
 * <p><strong>No job repository, launcher, explorer, registry or operator.</strong> All five are already
 * published by the auto-configuration, together with the initialiser that registers every job bean into
 * the registry by name. Declaring any of them here would either duplicate a bean definition or shadow the
 * framework's own wiring, so {@code api/BatchJobController} injects them directly and this class stays out
 * of the way. The deprecated no-argument launcher and operator factory methods are never called.
 *
 * <p><strong>No meter registry and no observation post-processor.</strong> The observability
 * post-processor that hands the observation registry to every job and step is already published when the
 * management dependency is present, so the framework's own job-level and step-level timings reach the
 * metrics scrape endpoint without anything being added here. {@code config/ObservabilityConfig} owns the
 * registry and the trace export; this class must not register a second registry, and must not suppress
 * what is already wired. {@code batch/step/AbstractCobolStep} additionally times each translated program
 * on its own timer.
 *
 * <p><strong>No logger configuration.</strong> {@code logback-spring.xml} owns appender selection and the
 * correlation fields, and the shared configuration document and its overlays own every verbosity level,
 * including the two batch categories and the migration-tool category whose output the Compose validation
 * reads. Nothing here may pin, raise or silence a level.
 *
 * <p><strong>No schema work.</strong> The framework provisions its own metadata tables under the prefix
 * the shared configuration declares; those tables are additional to, never instead of, the eleven
 * application tables that {@code db/migration/V1__create_schema.sql} creates, and a table census must
 * exclude that prefix. {@code config/FlywayConfig} owns migration behaviour, the delivered migration
 * inventory stays at exactly four scripts, and none of them may define a metadata table.
 *
 * <p><strong>No tuning of any kind.</strong> No chunk size, commit interval, task executor, thread count,
 * skip limit, retry limit, time-out or pool setting appears here, in code or in a comment. No numeric
 * service level is documented anywhere in the migrated estate, so performance for this implementation is
 * <em>measured and recorded</em> rather than asserted, and the connection pool keeps its shipped defaults.
 *
 * <p><strong>No concurrency.</strong> The migrated batch tier is strictly sequential, record at a time.
 * Parallel or partitioned execution would reorder output that four fixed-width formats are compared byte
 * for byte, so neither is introduced.
 *
 * <p><strong>No orchestrator.</strong> The estate holds no master scheduler; the order in which jobs run
 * is an operational convention. The nine jobs are therefore never chained into a super-job, and no job
 * that "runs everything" exists. {@code batch/DailyTransactionReadJobConfig} in particular translates a
 * complete program that no job member, procedure or online resource definition invokes, so it is defined
 * and exercised by tests only and is wired into nothing that runs by default.
 *
 * <h2>Nothing fires when the context starts</h2>
 *
 * <p>A legacy batch job was submitted deliberately; bringing an application up never triggered one. The
 * shared configuration document disables launch-on-start, which is what keeps the framework's own
 * start-up runner out of the context entirely, and jobs are launched on demand through
 * {@code api/BatchJobController}. This class contributes <strong>no</strong> start-up runner, lifecycle
 * participant, initialising callback, event listener or scheduled trigger, and it declares no job name for
 * anything to resolve. Both beans it does publish are inert until a job configuration attaches them.
 *
 * <h2>Provenance</h2>
 *
 * <p>Migrated from the CardDemo mainframe estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy tree is cited, never transcribed: no
 * job-control, program, copybook or online-resource text appears in this module, and nothing here reads
 * that tree at run time.
 *
 * <p>Stateless and immutable: final, no instance fields, no mutable static state, no collaborators. Both
 * published beans are safe for unsynchronised concurrent use.
 */
@Configuration(proxyBeanMethods = false)
public final class BatchConfig {

    /**
     * The one logger this configuration and its two member types write through.
     *
     * <p>Resolved from this class, so it sits under the application base category that the shipped
     * logging configuration declares and that the local overlay raises. The two batch package categories
     * that configuration also declares are deliberately <em>not</em> borrowed by name: this class is not
     * in either package, and naming one in a string here would create a second place where that package
     * is spelled out, which is exactly the drift the logging configuration is pinned against.</p>
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchConfig.class);

    /** Placeholder written in a diagnostic when a job execution carries no instance to name. */
    private static final String UNNAMED_JOB = "(unnamed)";

    /**
     * A clean completion, and the ceiling the three statement-job gates demand.
     *
     * <p>Cited from the gates on STEP020, STEP030 and STEP040 of {@code app/jcl/CREASTMT.JCL} at lines 56,
     * 66 and 79. A legacy contract value, not a tuning figure.</p>
     *
     * <p>Declared here rather than on {@link ConditionCodeGate}, which is where it belongs semantically,
     * for one language reason: an enum constant's argument may not name a field declared later in the same
     * class body, and the enum constants must come first.</p>
     */
    private static final int RETURN_CODE_NORMAL = 0;

    /**
     * A warning, and the ceiling the backup-job gate tolerates.
     *
     * <p>Cited from the gate on STEP10 of {@code app/jcl/TRANBKP.jcl:51}. A legacy contract value, not a
     * tuning figure, and the whole substance of the correction {@link ConditionCodeGate} carries.</p>
     */
    private static final int RETURN_CODE_WARNING = 4;

    /**
     * A failure, above every ceiling {@link ConditionCodeGate} holds, so every gate refuses.
     *
     * <p>The value the batch programs' own status normalisation produced for an error, cited at
     * {@code app/cbl/CBACT01C.cbl:L90} through {@code app/cbl/CBACT01C.cbl:L114} and already carried by
     * {@code batch/step/AbstractCobolStep} for the same reason. A legacy contract value, not a tuning
     * figure.</p>
     */
    private static final int RETURN_CODE_ERROR = 12;

    /**
     * The most decimal digits a completion code occupies, used only to bound a parse.
     *
     * <p>Reading an unbounded run of digits would overflow, and no completion code is longer than this, so
     * a longer run is not one. A parse bound, not a tuning figure.</p>
     */
    private static final int MAX_RETURN_CODE_DIGITS = 4;

    /**
     * Declared explicitly so that the absence of collaborators is visible and deliberate: batch
     * infrastructure is the layer the nine job configurations are built against, so it can depend on
     * nothing that is itself built against a job.
     */
    public BatchConfig() {
    }

    /**
     * The one job-boundary diagnostic every job configuration attaches, announcing a job's start and its
     * terminal outcome.
     *
     * <p>The framework does not apply a listener bean to a job on its own, which is deliberate here: each
     * of the nine job configurations attaches this listener explicitly on its own builder, so a job
     * carrying no announcement is visible in that job's own source rather than hidden in a global
     * default.</p>
     *
     * <p><strong>What it reproduces.</strong> The migrated estate had two diagnostic layers and this
     * listener is the outer one. The job entry system announced that a job had begun and, at the end,
     * reported the completion code; the detail of what a program did came from console statements inside
     * the program. That division is preserved exactly: this listener reports the boundary and the terminal
     * status, and {@code batch/step/AbstractCobolStep} reports each translated program's own start, record
     * count, end and abend path.</p>
     *
     * <p><strong>What it will not write, and why.</strong> No throwable is ever handed to the logging
     * framework and the framework's exit <em>description</em> is never emitted, because that description
     * carries a rendered stack trace. Job parameter <em>keys</em> are reported and their
     * <strong>values are not</strong>: a parameter map is open to any future job, the log stream leaves
     * the process, and the values remain readable in the framework's own execution-parameter table, which
     * is reached through the database rather than through the log pipeline. This is the same allow-list
     * posture the shipped logging configuration takes with the diagnostic context.</p>
     *
     * @return the shared job-boundary listener, never {@code null}
     */
    @Bean
    public JobExecutionListener batchJobBoundaryListener() {
        return new JobBoundaryListener();
    }

    /**
     * The one parameter incrementer every job configuration attaches, so a job can be resubmitted.
     *
     * <p>A job member on the mainframe could be submitted again with an identical parameter set and would
     * simply run again. The framework refuses a second instance of a job whose identifying parameters have
     * already been used, so without an incrementer a second submission of, say, the posting job on the
     * same processing date would be rejected rather than run. Attaching this incrementer restores the
     * legacy behaviour and is also what allows a job to be launched by name and advanced to its next
     * instance, which is how {@code api/BatchJobController} starts one.</p>
     *
     * <p>The framework's own run-identifier incrementer is used rather than a bespoke one: it contributes a
     * single monotonic identifying parameter and nothing else, so it cannot collide with the parameter keys
     * {@code batch/JobParameterValidators} owns and cannot change how any validator reads them.</p>
     *
     * @return the shared parameter incrementer, never {@code null}
     */
    @Bean
    public JobParametersIncrementer batchJobRunIncrementer() {
        return new RunIdIncrementer();
    }

    /**
     * The two condition-code step gates the estate actually uses, as flow deciders a job configuration
     * places between two steps.
     *
     * <h2>Why there are two constants and not one</h2>
     *
     * <p>Both gates read the same way &mdash; run the guarded step only if the highest return code any
     * earlier step produced is within a ceiling &mdash; but the two ceilings are different and the
     * difference is behavioural, not cosmetic:</p>
     *
     * <ul>
     *   <li>{@link #ALL_PRIOR_STEPS_ZERO} admits nothing above zero. This is the gate on STEP020, STEP030
     *       and STEP040 of {@code app/jcl/CREASTMT.JCL}, at lines 56, 66 and 79. A warning anywhere
     *       earlier stops the statement run.</li>
     *   <li>{@link #WARNINGS_TOLERATED} admits up to and including four. This is the gate on STEP10 of
     *       {@code app/jcl/TRANBKP.jcl:51}. A warning earlier in that job is <strong>expected</strong> and
     *       the backup step still runs.</li>
     * </ul>
     *
     * <p><strong>The two forms must never be collapsed.</strong> Applying the stricter ceiling to the
     * backup job would abort a step the estate runs; applying the looser ceiling to the statement job would
     * run a step the estate skips. Both are behavioural regressions, and the migration plan recorded only
     * the stricter form, so this enum is the correction. The two other condition-code occurrences in the
     * estate, at {@code app/jcl/TRANREPT.jcl:47} and {@code app/proc/TRANREPT.prc:45}, select records
     * inside an ordering step and are <em>not</em> step gates; nothing here models them.
     *
     * <h2>How a job configuration uses it</h2>
     *
     * <p>A constant is placed in the flow where the guarded step would otherwise follow directly, and the
     * two outcomes are routed on the names {@link #PERMITTED} and {@link #REFUSED} &mdash; the guarded step
     * on the first, and the end of the flow on the second, which is what the legacy gate did when it
     * refused. Because each constant is a singleton and holds no state, one constant serves every job that
     * needs that ceiling.</p>
     *
     * <h2>How a return code is determined</h2>
     *
     * <p>The framework records an exit status per step rather than a numeric completion code, so
     * {@link #decide} derives one from every step execution the job has accumulated and gates on the
     * highest, exactly as the legacy gate compared against the highest code so far. The derivation, in
     * order:</p>
     *
     * <ol>
     *   <li>A step whose exit code is written as decimal digits contributes that number. This is the
     *       explicit channel: a step that genuinely has a completion code to report writes it, and it is
     *       taken at face value.</li>
     *   <li>A step that has not ended contributes nothing, because no completion code exists for it yet.
     *       In a strictly sequential tier the only such step is the one whose completion invoked this
     *       decider.</li>
     *   <li>A step that ended unsuccessfully, or was stopped, contributes
     *       {@link BatchConfig#RETURN_CODE_ERROR} &mdash; above every ceiling here, so every gate
     *       refuses.</li>
     *   <li>A step that completed carrying one of the framework's own exit codes, or carrying none at
     *       all, contributes {@link BatchConfig#RETURN_CODE_NORMAL}. A step that reported nothing
     *       reported nothing wrong, so it must not be read as though it had.</li>
     *   <li>A step that completed carrying any other exit code contributes
     *       {@link BatchConfig#RETURN_CODE_WARNING}, which is the point of having two ceilings:
     *       the tolerant gate admits it and the strict gate does not.</li>
     * </ol>
     */
    public enum ConditionCodeGate implements JobExecutionDecider {

        /**
         * Run the guarded step only if every earlier step reported exactly zero.
         *
         * <p>The gate on STEP020, STEP030 and STEP040 of {@code app/jcl/CREASTMT.JCL}, at lines 56, 66
         * and 79.</p>
         */
        ALL_PRIOR_STEPS_ZERO(RETURN_CODE_NORMAL),

        /**
         * Run the guarded step if every earlier step reported at most a warning.
         *
         * <p>The gate on STEP10 of {@code app/jcl/TRANBKP.jcl:51}, where an earlier delete of a file that
         * does not yet exist is an expected warning rather than a failure.</p>
         */
        WARNINGS_TOLERATED(RETURN_CODE_WARNING);

        /**
         * The outcome name a job configuration routes the guarded step from.
         *
         * <p>Deliberately not one of the framework's own status names: routing on a framework name would
         * make a gate verdict indistinguishable from an ordinary step outcome in the same flow.</p>
         */
        public static final String PERMITTED = "CONDITION_CODE_PERMITTED";

        /** The outcome name a job configuration routes the end of the flow from. */
        public static final String REFUSED = "CONDITION_CODE_REFUSED";

        /** Shared immutable verdict for the permitting outcome. */
        private static final FlowExecutionStatus PERMITTED_STATUS = new FlowExecutionStatus(PERMITTED);

        /** Shared immutable verdict for the refusing outcome. */
        private static final FlowExecutionStatus REFUSED_STATUS = new FlowExecutionStatus(REFUSED);

        /**
         * The framework's own exit codes, which carry no completion code of their own.
         *
         * <p>Held as an immutable set so that a step completing with one of them is read as a clean
         * completion, while a step completing with anything else is read as having something to say.</p>
         */
        private static final Set<String> FRAMEWORK_EXIT_CODES = Set.of(
                ExitStatus.COMPLETED.getExitCode(),
                ExitStatus.EXECUTING.getExitCode(),
                ExitStatus.NOOP.getExitCode(),
                ExitStatus.FAILED.getExitCode(),
                ExitStatus.STOPPED.getExitCode(),
                ExitStatus.UNKNOWN.getExitCode());

        /** The ceiling this constant admits, inclusive. */
        private final int highestToleratedReturnCode;

        ConditionCodeGate(final int highestToleratedReturnCode) {
            this.highestToleratedReturnCode = highestToleratedReturnCode;
        }

        /**
         * The highest earlier return code this gate admits, inclusive.
         *
         * @return zero for the strict gate and four for the tolerant one
         */
        public int highestToleratedReturnCode() {
            return this.highestToleratedReturnCode;
        }

        /**
         * Whether this gate admits a given highest-so-far return code.
         *
         * <p>Separated from {@link #decide} so the ceiling can be asserted without assembling a job
         * execution, and so a job configuration can ask the question directly.</p>
         *
         * @param highestPriorReturnCode the highest return code any earlier step produced, never negative
         * @return {@code true} when the guarded step runs
         * @throws IllegalArgumentException if the supplied code is negative, which no completion code is
         */
        public boolean permits(final int highestPriorReturnCode) {
            if (highestPriorReturnCode < RETURN_CODE_NORMAL) {
                throw new IllegalArgumentException(
                        "a return code is never negative: " + highestPriorReturnCode);
            }
            return highestPriorReturnCode <= this.highestToleratedReturnCode;
        }

        /**
         * Evaluates the gate against everything the job has done so far.
         *
         * <p>The verdict is logged at every evaluation, because a step that silently did not run is the
         * hardest kind of batch outcome to explain after the fact, and the job entry system it replaces
         * said so on the job's own output.</p>
         *
         * @param jobExecution the job whose accumulated step outcomes are gated on, never {@code null}
         * @param stepExecution the step whose completion reached this gate; may be {@code null} when the
         *                      gate is the first thing in the flow, and is not read either way because the
         *                      gate compares against the highest code so far rather than the latest one
         * @return {@link #PERMITTED_STATUS} or {@link #REFUSED_STATUS}, never {@code null}
         * @throws NullPointerException if {@code jobExecution} is {@code null}
         */
        @Override
        public FlowExecutionStatus decide(final JobExecution jobExecution,
                final StepExecution stepExecution) {
            Objects.requireNonNull(jobExecution, "jobExecution");

            final int highest = highestReturnCodeSoFar(jobExecution);
            final boolean permitted = permits(highest);
            if (permitted) {
                LOGGER.info("CONDITION CODE GATE {} PERMITS THE NEXT STEP OF JOB {}"
                        + " - highestPriorReturnCode={} highestTolerated={}",
                        name(), jobNameOf(jobExecution), highest, this.highestToleratedReturnCode);
            } else {
                LOGGER.warn("CONDITION CODE GATE {} REFUSES THE NEXT STEP OF JOB {}; THE STEP WAS NOT"
                        + " EXECUTED - highestPriorReturnCode={} highestTolerated={}",
                        name(), jobNameOf(jobExecution), highest, this.highestToleratedReturnCode);
            }
            return permitted ? PERMITTED_STATUS : REFUSED_STATUS;
        }

        /**
         * The highest return code any step of this job has produced.
         *
         * @param jobExecution the job to scan
         * @return the highest contributed code, never below {@link BatchConfig#RETURN_CODE_NORMAL}
         */
        private static int highestReturnCodeSoFar(final JobExecution jobExecution) {
            int highest = RETURN_CODE_NORMAL;
            for (final StepExecution stepExecution : jobExecution.getStepExecutions()) {
                if (stepExecution == null) {
                    continue;
                }
                final int contributed = returnCodeOf(stepExecution);
                if (contributed > highest) {
                    highest = contributed;
                }
            }
            return highest;
        }

        /**
         * The return code one step execution contributes, by the order documented on this enum.
         *
         * @param stepExecution the step to read
         * @return the contributed code, never negative
         */
        private static int returnCodeOf(final StepExecution stepExecution) {
            final BatchStatus status = stepExecution.getStatus();
            if (status == null || status.isRunning()) {
                return RETURN_CODE_NORMAL;
            }

            final ExitStatus exitStatus = stepExecution.getExitStatus();
            final String exitCode = exitStatus == null ? null : exitStatus.getExitCode();

            final OptionalInt declared = declaredReturnCode(exitCode);
            if (declared.isPresent()) {
                return declared.getAsInt();
            }
            if (status != BatchStatus.COMPLETED) {
                return RETURN_CODE_ERROR;
            }
            if (exitCode == null || exitCode.isBlank() || FRAMEWORK_EXIT_CODES.contains(exitCode)) {
                return RETURN_CODE_NORMAL;
            }
            return RETURN_CODE_WARNING;
        }

        /**
         * Reads an exit code that is written as a completion code.
         *
         * <p>Only ASCII digits are accepted, and the digits are counted before they are read. Accepting
         * whatever the platform considers a digit would let a value outside ASCII be read as a completion
         * code, and reading an unbounded run of digits would overflow; a completion code occupies at most
         * {@link BatchConfig#MAX_RETURN_CODE_DIGITS} decimal digits, so anything longer is not one.</p>
         *
         * @param exitCode the exit code to read, possibly {@code null}
         * @return the completion code it states, or empty when it states none
         */
        private static OptionalInt declaredReturnCode(final String exitCode) {
            if (exitCode == null || exitCode.isEmpty()
                    || exitCode.length() > MAX_RETURN_CODE_DIGITS) {
                return OptionalInt.empty();
            }
            for (int position = 0; position < exitCode.length(); position++) {
                final char character = exitCode.charAt(position);
                if (character < '0' || character > '9') {
                    return OptionalInt.empty();
                }
            }
            return OptionalInt.of(Integer.parseInt(exitCode));
        }
    }

    /**
     * The job name to write into a diagnostic, or a placeholder when there is none.
     *
     * <p>A job execution normally carries the instance it belongs to, and that instance names the job. One
     * assembled directly rather than through the repository need not, so the placeholder keeps a
     * diagnostic readable instead of letting it fail on the value it was written to explain.</p>
     *
     * @param jobExecution the execution to name, never {@code null}
     * @return the job name, or {@link #UNNAMED_JOB} when the execution carries no instance
     */
    private static String jobNameOf(final JobExecution jobExecution) {
        final JobInstance jobInstance = jobExecution.getJobInstance();
        if (jobInstance == null) {
            return UNNAMED_JOB;
        }
        final String jobName = jobInstance.getJobName();
        return jobName == null || jobName.isEmpty() ? UNNAMED_JOB : jobName;
    }

    /**
     * The job parameter keys of an execution, in a stable order and without their values.
     *
     * <p>Sorted so two runs of the same job produce the same diagnostic, which is what makes one log line
     * comparable with another. The values are withheld deliberately; see
     * {@link BatchConfig#batchJobBoundaryListener()} for why.</p>
     *
     * @param jobExecution the execution to read, never {@code null}
     * @return the keys in ascending order, empty when the job takes no parameter
     */
    private static Set<String> parameterKeysOf(final JobExecution jobExecution) {
        final JobParameters parameters = jobExecution.getJobParameters();
        if (parameters == null) {
            return Set.of();
        }
        return new TreeSet<>(parameters.getParameters().keySet());
    }

    /**
     * The names of the steps of an execution that did not complete cleanly, in encounter order.
     *
     * <p>Named rather than counted, because on a job with several steps the identity of the one that
     * failed is the whole diagnostic. Only step names and framework status names are reported, both of
     * which are authored in this module or by the framework and neither of which can carry data.</p>
     *
     * @param jobExecution the execution to scan, never {@code null}
     * @return the unsuccessful step names, empty when every step completed
     */
    private static List<String> unsuccessfulStepNamesOf(final JobExecution jobExecution) {
        final List<String> unsuccessful = new ArrayList<>();
        for (final StepExecution stepExecution : jobExecution.getStepExecutions()) {
            if (stepExecution == null) {
                continue;
            }
            final BatchStatus status = stepExecution.getStatus();
            if (status != null && status != BatchStatus.COMPLETED && !status.isRunning()) {
                unsuccessful.add(stepExecution.getStepName() + '=' + status);
            }
        }
        return unsuccessful;
    }

    /**
     * The shared job-boundary diagnostic, published by {@link BatchConfig#batchJobBoundaryListener()}.
     *
     * <p>Private and stateless: it holds no field, so one instance serves every job and no concurrent run
     * can observe another's progress through it. It is reached only through the interface it implements, so
     * a job configuration cannot come to depend on anything but the contract.</p>
     *
     * <p>Both callbacks reject a {@code null} execution rather than logging around it. A {@code null} there
     * is a defect in the caller, not a runtime condition, and the framework contains a listener failure and
     * reports it, so failing loudly costs nothing and hiding it would cost the diagnostic.</p>
     */
    private static final class JobBoundaryListener implements JobExecutionListener {

        /** Nothing to construct; declared so the enclosing class is the only thing that can. */
        private JobBoundaryListener() {
        }

        /**
         * Announces that a job has begun, naming it and the parameter keys it was given.
         *
         * @param jobExecution the execution about to run, never {@code null}
         * @throws NullPointerException if {@code jobExecution} is {@code null}
         */
        @Override
        public void beforeJob(final JobExecution jobExecution) {
            Objects.requireNonNull(jobExecution, "jobExecution");
            LOGGER.info("START OF JOB {} - jobInstanceId={} jobExecutionId={} parameterKeys={}",
                    jobNameOf(jobExecution), jobExecution.getJobId(), jobExecution.getId(),
                    parameterKeysOf(jobExecution));
        }

        /**
         * Reports the terminal outcome of a job at one of three levels.
         *
         * <p>An unsuccessful terminal status is an error and names the steps responsible; a status that is
         * neither successful nor unsuccessful &mdash; a job that was stopped &mdash; is a warning, because
         * work remains undone without anything having gone wrong; a clean completion is informational. The
         * exit <em>code</em> is reported and the exit <em>description</em> never is, because the framework
         * renders a stack trace into that description.</p>
         *
         * @param jobExecution the execution that has ended, never {@code null}
         * @throws NullPointerException if {@code jobExecution} is {@code null}
         */
        @Override
        public void afterJob(final JobExecution jobExecution) {
            Objects.requireNonNull(jobExecution, "jobExecution");

            final String jobName = jobNameOf(jobExecution);
            final BatchStatus status = jobExecution.getStatus();
            final ExitStatus exitStatus = jobExecution.getExitStatus();
            final String exitCode = exitStatus == null ? null : exitStatus.getExitCode();
            final int stepsExecuted = jobExecution.getStepExecutions().size();

            if (status != null && status.isUnsuccessful()) {
                LOGGER.error("JOB {} TERMINATED ABNORMALLY - jobInstanceId={} jobExecutionId={} status={}"
                        + " exitCode={} stepsExecuted={} unsuccessfulSteps={} failureCount={}",
                        jobName, jobExecution.getJobId(), jobExecution.getId(), status, exitCode,
                        stepsExecuted, unsuccessfulStepNamesOf(jobExecution),
                        jobExecution.getAllFailureExceptions().size());
            } else if (status != BatchStatus.COMPLETED) {
                LOGGER.warn("JOB {} ENDED WITHOUT COMPLETING - jobInstanceId={} jobExecutionId={} status={}"
                        + " exitCode={} stepsExecuted={}",
                        jobName, jobExecution.getJobId(), jobExecution.getId(), status, exitCode,
                        stepsExecuted);
            } else {
                LOGGER.info("END OF JOB {} - jobInstanceId={} jobExecutionId={} status={} exitCode={}"
                        + " stepsExecuted={}",
                        jobName, jobExecution.getJobId(), jobExecution.getId(), status, exitCode,
                        stepsExecuted);
            }
        }
    }
}
