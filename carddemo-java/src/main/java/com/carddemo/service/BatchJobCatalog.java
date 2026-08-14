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
package com.carddemo.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The closed inventory of launchable batch jobs: each job's stable name, and the exact set of parameter
 * names that job accepts.
 *
 * <p><strong>Why the inventory is declared here rather than on the job configurations.</strong> Two layers
 * need it. The job configurations need each name to register their job under, and the operational control
 * surface needs the same names to resolve a launch against - and that surface may not import the batch
 * tier, because the specification's layering rule forbids the boundary from depending on it. This class is
 * the one authority both read: the batch tier names its jobs from these constants, so a registered name and
 * a launchable name are the same string by construction, and the boundary reads the inventory without
 * naming a single job configuration.
 *
 * <p>Every name is a compile-time constant, which is what lets a job configuration use it as the bean name
 * in an annotation attribute. A name is therefore declared exactly once in the whole module.
 *
 * <p><strong>The inventory being closed is the security property.</strong> A launch names one of these nine
 * constants or it reaches no framework call at all. Nothing here resolves a bean name, a class name, an
 * expression or a caller-selected type, so the control surface is a fixed set of nine operations rather
 * than an arbitrary execution facility.
 *
 * <p><strong>The parameter sets are closed for the same reason.</strong> Each job declares exactly the
 * parameter names it accepts, and every other name is refused before a launch is issued. This is not a
 * restatement of the validators: a validator decides whether a value is <em>acceptable</em> - the date's
 * width, its shape, whether the day it names exists - while this decides which names may be
 * <em>present</em> at all. The distinction is load-bearing. A job identity in the framework's metadata is
 * the job name plus its identifying parameters, so a caller free to add a name the job never declared can
 * mint an unbounded number of distinct identities for the same work and repeat a posting or an accrual run
 * behind a launch-once contract. Refusing the unknown name closes that.
 *
 * <p><strong>Eight are independent and the ninth is an orphan.</strong> The estate holds no master
 * scheduler and no orchestrator: the sequence in which jobs were submitted was an operational convention
 * held by whoever ran them, not a dependency encoded in a job member. {@link #DAILY_TRANSACTION_READ_JOB}
 * translates a complete program that no job member, cataloged procedure or online resource definition
 * invokes; it is launchable by name so that it stays exercisable rather than becoming dead code, and it is
 * part of no sequence and is never selected implicitly. Nothing here expresses an ordering, because the
 * estate expressed none.
 *
 * <p>Immutable and deeply so: every parameter set is built with the immutable set factory, the inventory is
 * built once during class initialisation and copied into an unmodifiable map, and nothing is assembled per
 * request, so this class is safe for unsynchronised concurrent use.
 *
 * @since 1.0.0
 */
public final class BatchJobCatalog {

    /**
     * Daily transaction posting, translating {@code app/jcl/POSTTRAN.jcl}. The job member carries no
     * execution parameter, so the job accepts none: its input is the current daily-transaction dataset,
     * resolved from configuration rather than from a caller.
     */
    public static final String POST_TRANSACTION_JOB = "postTransactionJob";

    /**
     * Interest and fee accrual, translating {@code app/jcl/INTCALC.jcl}, whose step carries the
     * ten-character run date as an execution parameter.
     */
    public static final String INTEREST_CALCULATION_JOB = "interestCalculationJob";

    /**
     * Transaction consolidation, translating {@code app/jcl/COMBTRAN.jcl}. The job member names both of its
     * concatenated inputs itself, as fixed dataset names with a relative generation, so neither is a
     * caller's to choose and the job accepts no parameter.
     */
    public static final String COMBINE_TRANSACTIONS_JOB = "combineTransactionsJob";

    /**
     * Customer statement generation, translating {@code app/jcl/CREASTMT.JCL}. Every dataset its four steps
     * name is declared in the job member, so the job accepts no parameter.
     */
    public static final String CREATE_STATEMENT_JOB = "createStatementJob";

    /**
     * Transaction reporting, translating {@code app/jcl/TRANREPT.jcl} and {@code app/proc/TRANREPT.prc},
     * whose in-stream parameter card carries the inclusive reporting window as two ten-character dates.
     */
    public static final String TRANSACTION_REPORT_JOB = "transactionReportJob";

    /**
     * Transaction master backup, translating {@code app/jcl/TRANBKP.jcl}. The generation it writes is named
     * by the job rather than by a caller, so the job accepts no parameter.
     */
    public static final String BACKUP_TRANSACTION_JOB = "backupTransactionJob";

    /**
     * Category balance reporting, translating {@code app/jcl/PRTCATBL.jcl}. The member names its input and
     * its output datasets itself, so the job accepts no parameter.
     */
    public static final String CATEGORY_BALANCE_REPORT_JOB = "categoryBalanceReportJob";

    /**
     * The four sequential-read verification jobs of {@code app/jcl/READACCT.jcl},
     * {@code app/jcl/READCARD.jcl}, {@code app/jcl/READCUST.jcl} and {@code app/jcl/READXREF.jcl},
     * collapsed into one parameterised job whose single parameter selects which cluster is read.
     */
    public static final String FILE_PROBE_JOB = "fileProbeJob";

    /**
     * The daily-transaction extract of {@code app/cbl/CBTRN01C.cbl}, which no job member, cataloged
     * procedure or resource definition invokes. Defined and launchable, part of no sequence, and exercised
     * by tests - a recorded decision rather than a promotion.
     */
    public static final String DAILY_TRANSACTION_READ_JOB = "dailyTransactionReadJob";

    /*
     * Each job is named once, immediately above. A PARALLEL SET OF NINE "_JOB_NAME" ALIASES MUST NOT SIT
     * here for the job configurations to read: every name would then exist twice under two spellings and a
     * reader would have to know the two were the same constant. Every reader reads the canonical name.
     */

    /**
     * The ten-character run date the accrual step carries as its execution parameter.
     *
     * <p>The value is also the literal leading characters of every transaction identifier the run
     * synthesises, which is why it is passed through byte for byte and why its shape is the job validator's
     * business rather than this class's.
     */
    public static final String INTEREST_PARM_DATE_PARAMETER = "interestParmDate";

    /** Inclusive lower bound of the reporting window, ten characters. */
    public static final String REPORT_START_DATE_PARAMETER = "reportStartDate";

    /** Inclusive upper bound of the reporting window, ten characters. */
    public static final String REPORT_END_DATE_PARAMETER = "reportEndDate";

    /** Selects which of the four clusters the file probe reads. */
    public static final String FILE_PROBE_MODE_PARAMETER = "fileProbeMode";

    /**
     * The inventory: every launchable job name mapped to the exact parameter names it accepts.
     *
     * <p>Insertion order is the order the jobs are declared above, which is the order an operator reading
     * the estate would meet them. A job that accepts nothing maps to the empty set rather than being
     * absent, so "not launchable" and "launchable with no parameter" can never be confused.
     */
    private static final Map<String, Set<String>> PARAMETER_NAMES_BY_JOB = inventory();

    /** Immutable compatibility view used by the service facade and existing contract tests. */
    public static final Set<String> LAUNCHABLE_JOB_NAMES =
            Set.copyOf(PARAMETER_NAMES_BY_JOB.keySet());

    /** Not instantiable: this class is a constant contract with two static lookups. */
    private BatchJobCatalog() {
        throw new AssertionError("BatchJobCatalog is a constant contract and is never instantiated");
    }

    /**
     * Builds the inventory from the constants above.
     *
     * @return an unmodifiable map from stable job name to the exact parameter names that job accepts
     */
    private static Map<String, Set<String>> inventory() {
        final Map<String, Set<String>> declared = new LinkedHashMap<>();
        declared.put(POST_TRANSACTION_JOB, Set.of());
        declared.put(INTEREST_CALCULATION_JOB, Set.of(INTEREST_PARM_DATE_PARAMETER));
        declared.put(COMBINE_TRANSACTIONS_JOB, Set.of());
        declared.put(CREATE_STATEMENT_JOB, Set.of());
        declared.put(TRANSACTION_REPORT_JOB,
                Set.of(REPORT_START_DATE_PARAMETER, REPORT_END_DATE_PARAMETER));
        declared.put(BACKUP_TRANSACTION_JOB, Set.of());
        declared.put(CATEGORY_BALANCE_REPORT_JOB, Set.of());
        declared.put(FILE_PROBE_JOB, Set.of(FILE_PROBE_MODE_PARAMETER));
        declared.put(DAILY_TRANSACTION_READ_JOB, Set.of());
        return Map.copyOf(declared);
    }

    /**
     * Every launchable job name.
     *
     * @return an unmodifiable set of the nine stable names
     */
    public static Set<String> launchableJobNames() {
        return PARAMETER_NAMES_BY_JOB.keySet();
    }

    /**
     * Returns the immutable job-to-accepted-parameter inventory.
     *
     * @return every launchable job mapped to its exact accepted parameter names
     */
    public static Map<String, Set<String>> parameterNamesByJob() {
        return PARAMETER_NAMES_BY_JOB;
    }

    /**
     * Whether a name addresses one of the nine launchable jobs.
     *
     * @param jobName the name a caller addressed, which may be {@code null}
     * @return {@code true} only for a name in the closed inventory
     */
    public static boolean isLaunchable(final String jobName) {
        return jobName != null && PARAMETER_NAMES_BY_JOB.containsKey(jobName);
    }

    /** Compatibility spelling for the existing service facade. */
    public static boolean launchable(final String jobName) {
        return isLaunchable(jobName);
    }

    /**
     * The exact parameter names one launchable job accepts.
     *
     * <p>Answers an empty result for a name outside the inventory, which a caller turns into an
     * absent-resource answer, and an empty <em>set</em> for a job that accepts no parameter. Those two are
     * deliberately distinguishable.
     *
     * @param jobName the stable job name, which may be {@code null}
     * @return the accepted parameter names, or an empty result when the name is not launchable
     */
    public static Optional<Set<String>> parameterNamesFor(final String jobName) {
        if (jobName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(PARAMETER_NAMES_BY_JOB.get(jobName));
    }
}
